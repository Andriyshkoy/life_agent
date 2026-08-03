package ru.andriyshkoy.lifeagent.data.local.db

import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalNoteCodec
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.M2NoteWireDocuments
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_MAX_PUSH_OPERATIONS
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_PROTOCOL_VERSION
import ru.andriyshkoy.lifeagent.data.sync.wire.PushBatchRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PushOperationWire
import ru.andriyshkoy.lifeagent.data.sync.wire.StrictJson
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonObject
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec
import ru.andriyshkoy.lifeagent.data.sync.wire.constantTimeHexEquals
import ru.andriyshkoy.lifeagent.data.sync.wire.sha256Hex

/**
 * Room-owned production materializer for pending M1 note rows.
 *
 * The caller must already own the planning transaction. Therefore installing
 * immutable per-operation wire material, freezing a batch and persisting its
 * protected exact request either commit together or roll back together.
 */
internal class ProductionProtectedActionablePushConstructionPort(
    private val database: LifeAgentDatabase,
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val noteCodec: CanonicalNoteCodec = CanonicalNoteCodec(),
) : ProtectedActionablePushConstructionPort {
    private val mutationDao = database.noteMutationDao()
    private val noteQueryDao = database.noteQueryDao()
    private val outboxDao = database.outboxDao()

    override suspend fun build(
        authority: ProtectedActionablePushAuthority,
    ): ProtectedActionablePushConstruction {
        check(database.inTransaction()) {
            "Protected push construction requires the planning transaction"
        }
        requireAuthority(authority)
        currentCoroutineContext().ensureActive()

        materializePendingOperations(authority.createdAtUtc)
        val actionable = outboxDao.actionableForBatch(M2_MAX_PUSH_OPERATIONS)
        if (actionable.isEmpty()) materializationFailure()

        val operations = ArrayList<PushOperationWire>(actionable.size)
        try {
            actionable.forEachIndexed { ordinal, outbox ->
                currentCoroutineContext().ensureActive()
                val materialized = reconstruct(outbox, ordinal)
                try {
                    requireRetainedMaterial(outbox, materialized.contentJcs, materialized.operation)
                    operations += materialized.operation
                } finally {
                    materialized.contentJcs.fill(0)
                    outbox.wireOperationMaterialJcs?.fill(0)
                }
            }

            currentCoroutineContext().ensureActive()
            val batchId = uuidGenerator.next().toString()
            val request = PushBatchRequest(
                batchId = batchId,
                deviceId = authority.deviceId,
                operations = operations,
            )
            val batchContentSha256 = batchContentSha256(request)
            val items = operations.map { operation ->
                SyncPushBatchItemEntity(
                    batchId = batchId,
                    ordinal = operation.ordinal,
                    localSequence = operation.clientSequence,
                    operationId = operation.operationId,
                    wireOperationContentSha256 = operation.operationContentSha256,
                )
            }
            return ProtectedActionablePushConstruction(
                request = request,
                persistence = NewDurableRequestPersistence(
                    localCredentialEpochId = authority.credentialEpochId,
                    accessGenerationUsed = authority.accessGeneration,
                    attemptBudget = authority.attemptBudget,
                    deadlineAtEpochMs = authority.deadlineAtEpochMs,
                    createdAtUtc = authority.createdAtUtc,
                ),
                batch = SyncPushBatchEntity(
                    batchId = batchId,
                    endpointId = M2Endpoint.SYNC_PUSH.endpointId,
                    requestIdentity = batchId,
                    batchContentSha256 = batchContentSha256,
                    operationCount = operations.size,
                    createdAtUtc = authority.createdAtUtc,
                ),
                items = items,
            )
        } catch (error: Throwable) {
            operations.clear()
            throw error
        }
    }

    override fun toString(): String =
        "ProductionProtectedActionablePushConstructionPort(redacted=true)"

    private suspend fun materializePendingOperations(materializedAtUtc: String) {
        val pending = outboxDao.awaitingWireMaterialization(M2_MAX_PUSH_OPERATIONS)
        pending.forEach { outbox ->
            currentCoroutineContext().ensureActive()
            val materialized = reconstruct(outbox, MATERIALIZATION_ORDINAL)
            try {
                if (
                    outboxDao.installWireMaterial(
                        localSequence = outbox.localSequence,
                        operationId = outbox.operationId,
                        protocolVersion = M2_PROTOCOL_VERSION,
                        materialJcs = materialized.contentJcs,
                        contentSha256 = materialized.operation.operationContentSha256,
                        materializedAtUtc = materializedAtUtc,
                    ) != 1
                ) {
                    materializationFailure()
                }
            } finally {
                materialized.contentJcs.fill(0)
            }
        }
    }

    private suspend fun reconstruct(
        outbox: SyncOutboxEntity,
        ordinal: Int,
    ): ReconstructedOperation {
        requireOutboxShape(outbox)
        val capture = mutationDao.findCapture(outbox.captureId, outbox.operationId)
            ?: materializationFailure()
        val revision = noteQueryDao.findByOperationId(outbox.operationId)
            ?: materializationFailure()
        if (
            capture.captureId != outbox.captureId ||
            capture.operationId != outbox.operationId ||
            capture.installationId != outbox.installationId ||
            capture.localOwnerId != outbox.localOwnerId ||
            capture.persistenceState != LOCAL_PENDING ||
            revision.localSequence != outbox.localSequence ||
            revision.installationId != outbox.installationId ||
            revision.localOwnerId != outbox.localOwnerId ||
            revision.revision.eventId != outbox.eventId ||
            revision.revision.revisionId != outbox.revisionId ||
            revision.revision.captureId != outbox.captureId ||
            revision.revision.operationId != outbox.operationId ||
            revision.revision.schemaVersion != outbox.schemaVersion ||
            revision.revision.serverReceivedAt != null ||
            revision.revision.serverSequence != null
        ) {
            materializationFailure()
        }

        val parents = noteQueryDao.findParents(outbox.revisionId)
        val captureBytes = noteCodec.encodeCanonicalPendingCapture(capture).bytes
        val eventBytes = noteCodec.encodeCanonicalPendingEvent(revision, parents).bytes
        return try {
            val wireCapture = M2NoteWireDocuments.decodePendingCapture(captureBytes)
            val wireEvent = M2NoteWireDocuments.decodePendingEvent(eventBytes)
            if (wireEvent.parentRevisionId != outbox.baseRevisionId) {
                materializationFailure()
            }
            val operation = WireRequestCodec.createPushOperation(
                ordinal = ordinal,
                clientSequence = outbox.localSequence,
                expectedCurrentRevisionId = outbox.baseRevisionId,
                capture = wireCapture,
                event = wireEvent,
            )
            val content = WireJsonObject(
                operation.document.properties - OPERATION_ENVELOPE_FIELDS,
            )
            ReconstructedOperation(
                operation = operation,
                contentJcs = StrictJson.canonicalBytes(content),
            )
        } finally {
            captureBytes.fill(0)
            eventBytes.fill(0)
        }
    }

    private fun requireRetainedMaterial(
        outbox: SyncOutboxEntity,
        reconstructedJcs: ByteArray,
        operation: PushOperationWire,
    ) {
        val retainedJcs = outbox.wireOperationMaterialJcs ?: materializationFailure()
        val retainedSha = outbox.wireOperationContentSha256 ?: materializationFailure()
        if (
            outbox.wireState != WIRE_READY ||
            outbox.wireProtocolVersion != M2_PROTOCOL_VERSION ||
            outbox.wireMaterializedAtUtc == null ||
            !retainedJcs.contentEquals(reconstructedJcs) ||
            !constantTimeHexEquals(retainedSha, sha256Hex(retainedJcs)) ||
            !constantTimeHexEquals(retainedSha, operation.operationContentSha256)
        ) {
            materializationFailure()
        }
    }

    private fun requireOutboxShape(outbox: SyncOutboxEntity) {
        if (
            outbox.localSequence <= 0L ||
            outbox.operationKind != APPEND_EVENT_REVISION ||
            outbox.schemaVersion != CanonicalNoteCodec.EVENT_SCHEMA_VERSION ||
            outbox.activeBatchId != null ||
            outbox.state !in setOf(OUTBOX_PENDING, OUTBOX_WAITING_PARENT)
        ) {
            materializationFailure()
        }
    }

    private fun requireAuthority(authority: ProtectedActionablePushAuthority) {
        if (
            authority.credentialEpochId.isBlank() ||
            authority.accessGeneration <= 0L ||
            authority.attemptBudget <= 0 ||
            authority.deadlineAtEpochMs <= Instant.parse(authority.createdAtUtc).toEpochMilli()
        ) {
            materializationFailure()
        }
    }

    private fun batchContentSha256(request: PushBatchRequest): String =
        WireRequestCodec.materialize(request).use { materialized ->
            val body = materialized.copyBody()
            try {
                WireRequestCodec.decodeDurablePushEvidence(body).batchContentSha256
            } finally {
                body.fill(0)
            }
        }

    private class ReconstructedOperation(
        val operation: PushOperationWire,
        val contentJcs: ByteArray,
    )

    private companion object {
        const val MATERIALIZATION_ORDINAL = 0
        const val LOCAL_PENDING = "local_pending"
        const val WIRE_READY = "ready"
        const val OUTBOX_PENDING = "pending"
        const val OUTBOX_WAITING_PARENT = "waiting_parent"
        const val APPEND_EVENT_REVISION = "append_event_revision"
        val OPERATION_ENVELOPE_FIELDS = setOf("ordinal", "operation_content_sha256")
    }
}

internal class PushWireMaterializationException :
    IllegalStateException("Canonical push materialization failed") {
    override fun toString(): String = "PushWireMaterializationException(redacted=true)"
}

private fun materializationFailure(): Nothing = throw PushWireMaterializationException()
