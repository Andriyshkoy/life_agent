package ru.andriyshkoy.lifeagent.data.local.db

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity

data class BootstrapIntentPersistence(
    val session: SyncBootstrapSessionEntity,
    val firstRequest: SyncHttpRequestEntity,
)

/**
 * Persists whole-request recovery outcomes which span transport, batch,
 * outbox, auth and stream state.
 */
class SyncRequestPersistenceStore(
    private val database: LifeAgentDatabase,
) {
    private val authDao = database.syncAuthDao()
    private val identityDao = database.identityDao()
    private val mutationDao = database.noteMutationDao()
    private val replicaDao = database.syncReplicaDao()
    private val transportDao = database.syncTransportDao()

    suspend fun reconcileExpiredOrExhaustedRequests(
        nowEpochMs: Long,
        terminalAtUtc: String,
        limit: Int = 100,
    ): Int {
        require(limit in 1..1_000)
        return database.withTransaction {
            var reconciled = 0
            transportDao.findRequestsNeedingLocalTerminalization(
                nowEpochMs = nowEpochMs,
                limit = limit,
            ).forEach { request ->
                check(
                    transportDao.markRetryBudgetExhausted(
                        endpointId = request.endpointId,
                        requestIdentity = request.requestIdentity,
                        nowEpochMs = nowEpochMs,
                        terminalAtUtc = terminalAtUtc,
                    ) == 1,
                ) {
                    "Expired durable request lost its terminalization CAS"
                }
                if (request.endpointId == AUTH_REVOKE) {
                    val generation = checkNotNull(request.accessGenerationUsed) {
                        "Revoke request lost its credential generation"
                    }
                    val current = authDao.findState()
                    val exactFamily =
                        current?.credentialEpochId == request.credentialEpochId &&
                            current.deviceId == request.deviceId &&
                            current.generation == generation
                    if (exactFamily) {
                        check(current.state == REVOKE_PENDING) {
                            "Exhausted revoke found an invalid exact-family state"
                        }
                        check(
                            authDao.quarantine(
                                credentialEpochId = current.credentialEpochId,
                                generation = current.generation,
                                expectedState = REVOKE_PENDING,
                                newState = QUARANTINED,
                                updatedAtUtc = terminalAtUtc,
                                failureCode = REVOKE_RETRY_EXHAUSTED,
                            ) == 1,
                        ) {
                            "Exhausted revoke lost its exact-family CAS"
                        }
                    }
                }
                reconciled += 1
            }
            reconciled
        }
    }

    /**
     * Installs a trusted sync_push/bootstrap_required outcome atomically.
     *
     * Returns false for a stale attempt callback or an exact terminal replay.
     */
    suspend fun commitPushBootstrapRequired(
        response: TerminalHttpResponsePersistence,
        proposedIntent: BootstrapIntentPersistence,
    ): Boolean {
        require(response.endpointId == SYNC_PUSH)
        require(response.httpStatus == 409)
        require(response.terminalErrorCode == BOOTSTRAP_REQUIRED)
        validateBootstrapIntent(proposedIntent)
        return database.withTransaction {
            val request = checkNotNull(
                transportDao.findRequest(response.endpointId, response.requestIdentity),
            ) {
                "Bootstrap-required push request is missing"
            }
            if (request.state == TERMINAL) {
                requireExactTerminalResponse(request, response)
                return@withTransaction false
            }
            if (
                request.state != SENDING ||
                request.activeAttemptId != response.expectedAttemptId
            ) {
                return@withTransaction false
            }

            val stream = checkNotNull(replicaDao.findStreamState()) {
                "Bootstrap-required push has no current stream"
            }
            val auth = checkNotNull(authDao.findState()) {
                "Bootstrap-required push has no credential family"
            }
            val identity = checkNotNull(identityDao.findIdentity()) {
                "Bootstrap-required push has no current identity"
            }
            check(
                stream.credentialEpochId == request.credentialEpochId &&
                    stream.deviceId == request.deviceId &&
                    stream.integrityErrorCode == null &&
                    auth.credentialEpochId == request.credentialEpochId &&
                    auth.deviceId == request.deviceId &&
                    auth.state == ACTIVE &&
                    auth.installationId == identity.installationId &&
                    auth.localOwnerId == identity.localOwnerId &&
                    proposedIntent.firstRequest.accessGenerationUsed ==
                    auth.generation,
            ) {
                "Bootstrap-required push lost its current binding"
            }
            val batch = checkNotNull(transportDao.findBatch(response.requestIdentity)) {
                "Bootstrap-required push batch is missing"
            }
            val items = transportDao.findBatchItems(batch.batchId)
            check(
                batch.endpointId == request.endpointId &&
                    batch.requestIdentity == request.requestIdentity &&
                    request.idempotencyKey == batch.batchId &&
                    batch.operationCount in 1..100 &&
                    items.size == batch.operationCount &&
                    items.map { it.ordinal } == items.indices.toList(),
            ) {
                "Bootstrap-required response lost its batch membership"
            }
            items.forEach { item ->
                val outbox = checkNotNull(mutationDao.findOutbox(item.operationId)) {
                    "Bootstrap-required batch member has no outbox row"
                }
                check(
                    outbox.localSequence == item.localSequence &&
                        outbox.activeBatchId == batch.batchId &&
                        outbox.state == "batched" &&
                        outbox.wireOperationContentSha256 ==
                        item.wireOperationContentSha256,
                ) {
                    "Bootstrap-required batch member drifted"
                }
            }

            check(
                transportDao.storeTerminalResponse(
                    endpointId = response.endpointId,
                    requestIdentity = response.requestIdentity,
                    expectedAttemptId = response.expectedAttemptId,
                    httpStatus = response.httpStatus,
                    exactResponseBody = response.exactResponseBody,
                    responseSha256 = response.responseSha256,
                    terminalAtUtc = response.terminalAtUtc,
                    terminalErrorCode = response.terminalErrorCode,
                ) == 1,
            ) {
                "Bootstrap-required response lost its request CAS"
            }
            check(
                transportDao.releasePushBatchForBootstrap(batch.batchId) ==
                    batch.operationCount,
            ) {
                "Bootstrap-required push did not release every batch member"
            }
            releaseOtherOpenPushBatches(
                credentialEpochId = request.credentialEpochId,
                deviceId = request.deviceId,
            )

            val retainedRequestIdentity = installOrRetainBootstrapIntent(
                expectedCredentialEpochId = request.credentialEpochId,
                expectedDeviceId = request.deviceId,
                proposedIntent = proposedIntent,
                updatedAtUtc = response.terminalAtUtc,
            )
            transportDao.invalidateSupersededSyncRequests(
                credentialEpochId = request.credentialEpochId,
                deviceId = request.deviceId,
                retainedBootstrapRequestIdentity = retainedRequestIdentity,
                terminalAtUtc = response.terminalAtUtc,
            )
            check(
                replicaDao.requireBootstrap(
                    credentialEpochId = request.credentialEpochId,
                    deviceId = request.deviceId,
                    updatedAtUtc = response.terminalAtUtc,
                ) == 1,
            ) {
                "Bootstrap-required push could not gate its stream"
            }
            check(
                authDao.setBootstrapRequired(
                    credentialEpochId = request.credentialEpochId,
                    deviceId = request.deviceId,
                    bootstrapRequired = true,
                    updatedAtUtc = response.terminalAtUtc,
                ) == 1,
            ) {
                "Bootstrap-required push could not gate its credential family"
            }
            true
        }
    }

    /**
     * Ensures one current bootstrap shadow and durable first-page request.
     * Must be called from an outer Room transaction.
     */
    internal suspend fun installOrRetainBootstrapIntent(
        expectedCredentialEpochId: String,
        expectedDeviceId: String,
        proposedIntent: BootstrapIntentPersistence,
        updatedAtUtc: String,
    ): String {
        validateBootstrapIntent(proposedIntent)
        check(
            proposedIntent.session.credentialEpochId ==
                expectedCredentialEpochId &&
                proposedIntent.session.deviceId == expectedDeviceId,
        ) {
            "Bootstrap intent belongs to another stream"
        }

        val active = replicaDao.findBootstrapSessionWithActiveSlot()
        val openRequests = transportDao.findOpenBootstrapRequests(
            credentialEpochId = expectedCredentialEpochId,
            deviceId = expectedDeviceId,
        )
        if (
            active != null &&
            active.credentialEpochId == expectedCredentialEpochId &&
            active.deviceId == expectedDeviceId &&
            active.state == STAGING &&
            openRequests.size == 1 &&
            openRequests.single().accessGenerationUsed ==
            proposedIntent.firstRequest.accessGenerationUsed &&
            bootstrapRequestBindsSession(
                request = openRequests.single(),
                session = active,
            )
        ) {
            return openRequests.single().requestIdentity
        }

        if (active != null) {
            check(
                active.credentialEpochId == expectedCredentialEpochId &&
                    active.deviceId == expectedDeviceId,
            ) {
                "Bootstrap recovery cannot supersede another family"
            }
            check(
                replicaDao.supersedeActiveBootstrapSession(
                    bootstrapId = active.bootstrapId,
                    credentialEpochId = expectedCredentialEpochId,
                    deviceId = expectedDeviceId,
                    updatedAtUtc = updatedAtUtc,
                ) == 1,
            ) {
                "Active bootstrap shadow lost its supersession CAS"
            }
        }
        transportDao.invalidateSupersededBootstrapRequests(
            credentialEpochId = expectedCredentialEpochId,
            deviceId = expectedDeviceId,
            retainedRequestIdentity =
                proposedIntent.firstRequest.requestIdentity,
            terminalAtUtc = updatedAtUtc,
        )
        replicaDao.insertBootstrapSession(proposedIntent.session)
        transportDao.insertRequest(proposedIntent.firstRequest)
        return proposedIntent.firstRequest.requestIdentity
    }

    private suspend fun releaseOtherOpenPushBatches(
        credentialEpochId: String,
        deviceId: String,
    ) {
        transportDao.findOpenPushBatchIds(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
        ).forEach { batchId ->
            val expected = transportDao.findBatchItems(batchId).size
            check(expected > 0)
            check(transportDao.releasePushBatchForBootstrap(batchId) == expected) {
                "Superseded push batch could not release every member"
            }
        }
    }

    private fun requireExactTerminalResponse(
        retained: SyncHttpRequestEntity,
        response: TerminalHttpResponsePersistence,
    ) {
        check(
            retained.state == TERMINAL &&
                retained.terminalHttpStatus == response.httpStatus &&
                retained.exactResponseBody?.contentEquals(
                    response.exactResponseBody,
                ) == true &&
                retained.responseSha256 == response.responseSha256 &&
                retained.terminalErrorCode == response.terminalErrorCode,
        ) {
            "Bootstrap-required terminal replay drifted"
        }
    }

    private companion object {
        const val AUTH_REVOKE = "auth_revoke"
        const val SYNC_PUSH = "sync_push"
        const val ACTIVE = "active"
        const val REVOKE_PENDING = "revoke_pending"
        const val QUARANTINED = "quarantined"
        const val REVOKE_RETRY_EXHAUSTED = "revoke_retry_exhausted"
        const val SENDING = "sending"
        const val TERMINAL = "terminal"
        const val BOOTSTRAP_REQUIRED = "bootstrap_required"
        const val STAGING = "staging"
    }
}

internal fun validateBootstrapIntent(intent: BootstrapIntentPersistence) {
    val session = intent.session
    val request = intent.firstRequest
    require(
        session.state == "staging" &&
            session.activeSlot == 1 &&
            session.snapshotId == null &&
            session.nextPageCursor == null &&
            session.candidateIncrementalCursor == null &&
            session.nextPageIndex == 0 &&
            session.lastStagedServerSequence == null &&
            session.stagedPageCount == 0 &&
            session.stagedBodyBytes == 0L,
    )
    require(
        request.endpointId == "sync_bootstrap" &&
            request.credentialEpochId == session.credentialEpochId &&
            request.deviceId == session.deviceId &&
            request.idempotencyKey == null &&
            request.bodyStorageKind == SyncHttpRequestEntity.BODY_STORAGE_RAW &&
            request.state == "ready" &&
            request.attemptCount == 0 &&
            request.activeAttemptId == null &&
            request.accessGenerationUsed != null &&
            request.accessGenerationUsed > 0 &&
            request.terminalHttpStatus == null &&
            request.exactResponseBody == null &&
            request.responseSha256 == null &&
            request.terminalAtUtc == null &&
            request.terminalErrorCode == null,
    )
    val body = checkNotNull(request.rawRequestBody)
    val root = Json.parseToJsonElement(body.decodeToString()) as? JsonObject
        ?: error("Bootstrap intent body must be a JSON object")
    require(
        root.keys == setOf(
            "protocol_version",
            "message_type",
            "request_id",
            "bootstrap_id",
            "device_id",
            "page_size",
            "page_cursor",
        ),
    )
    require(root.requiredString("protocol_version") == request.protocolVersion)
    require(root.requiredString("message_type") == "bootstrap_request")
    require(root.requiredString("request_id") == request.requestIdentity)
    require(root.requiredString("bootstrap_id") == session.bootstrapId)
    require(root.requiredString("device_id") == session.deviceId)
    val pageSizePrimitive = root["page_size"] as? JsonPrimitive
    require(pageSizePrimitive != null && !pageSizePrimitive.isString)
    val pageSize = pageSizePrimitive.content.toIntOrNull()
    require(pageSize != null && pageSize in 1..500)
    require(root["page_cursor"] is JsonNull)
}

private fun JsonObject.requiredString(field: String): String {
    val primitive = checkNotNull(this[field]) {
        "Bootstrap intent is missing $field"
    }.jsonPrimitive
    require(primitive.isString) {
        "Bootstrap intent field $field must be a JSON string"
    }
    return primitive.content
}

private fun bootstrapRequestBindsSession(
    request: SyncHttpRequestEntity,
    session: SyncBootstrapSessionEntity,
): Boolean = runCatching {
    if (
        request.endpointId != "sync_bootstrap" ||
        request.bodyStorageKind != SyncHttpRequestEntity.BODY_STORAGE_RAW ||
        request.credentialEpochId != session.credentialEpochId ||
        request.deviceId != session.deviceId ||
        session.state != "staging" ||
        session.activeSlot != 1
    ) {
        return@runCatching false
    }
    val body = checkNotNull(request.rawRequestBody)
    val root = Json.parseToJsonElement(body.decodeToString()) as? JsonObject
        ?: return@runCatching false
    if (
        root.keys != setOf(
            "protocol_version",
            "message_type",
            "request_id",
            "bootstrap_id",
            "device_id",
            "page_size",
            "page_cursor",
        )
    ) {
        return@runCatching false
    }
    val pageCursor = when (val value = root["page_cursor"]) {
        JsonNull -> null
        is JsonPrimitive ->
            value.takeIf { it.isString }?.content ?: return@runCatching false
        else -> return@runCatching false
    }
    val pageSizePrimitive =
        root["page_size"] as? JsonPrimitive ?: return@runCatching false
    if (pageSizePrimitive.isString) {
        return@runCatching false
    }
    val pageSize = pageSizePrimitive.content.toIntOrNull()
    root.requiredString("protocol_version") == request.protocolVersion &&
        root.requiredString("message_type") == "bootstrap_request" &&
        root.requiredString("request_id") == request.requestIdentity &&
        root.requiredString("bootstrap_id") == session.bootstrapId &&
        root.requiredString("device_id") == session.deviceId &&
        pageSize != null &&
        pageSize in 1..500 &&
        pageCursor == session.nextPageCursor
}.getOrDefault(false)
