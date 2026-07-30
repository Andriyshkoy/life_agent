package ru.andriyshkoy.lifeagent.data.local.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStagedChangeEntity
import ru.andriyshkoy.lifeagent.data.local.serialization.CanonicalNoteCodec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Schema-verified server change handed to the durable replica reducer.
 *
 * The redundant scalar projection lets Room reject identity collisions before
 * materializing the nested immutable documents. [changeJcs] is the exact JCS
 * representation of the complete serverChange.
 */
data class ReplicaChangePersistence(
    val serverSequence: Long,
    val operationId: String,
    val operationContentSha256: String,
    val captureId: String,
    val eventId: String,
    val revisionId: String,
    val currentRevisionId: String,
    val resultCode: String,
    val committedAtUtc: String,
    val changeJcs: ByteArray,
) {
    internal fun asStaged(
        bootstrapId: String,
        pageId: String,
    ) = SyncStagedChangeEntity(
        bootstrapId = bootstrapId,
        serverSequence = serverSequence,
        pageId = pageId,
        operationId = operationId,
        operationContentSha256 = operationContentSha256,
        captureId = captureId,
        eventId = eventId,
        revisionId = revisionId,
        currentRevisionId = currentRevisionId,
        resultCode = resultCode,
        committedAtUtc = committedAtUtc,
        changeJcs = changeJcs,
    )

    companion object {
        internal fun from(entity: SyncStagedChangeEntity) = ReplicaChangePersistence(
            serverSequence = entity.serverSequence,
            operationId = entity.operationId,
            operationContentSha256 = entity.operationContentSha256,
            captureId = entity.captureId,
            eventId = entity.eventId,
            revisionId = entity.revisionId,
            currentRevisionId = entity.currentRevisionId,
            resultCode = entity.resultCode,
            committedAtUtc = entity.committedAtUtc,
            changeJcs = entity.changeJcs,
        )
    }
}

class ReplicaIntegrityException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal data class DecodedReplicaChange(
    val input: ReplicaChangePersistence,
    val installation: LocalInstallationEntity,
    val owner: LocalOwnerEntity,
    val capture: LocalCaptureEntity,
    val event: LocalLifeEventEntity,
    val revision: LocalEventRevisionEntity,
    val parents: List<LocalRevisionParentEntity>,
) {
    fun serverChange(
        endpointId: String,
        requestIdentity: String,
        verifiedAtUtc: String,
    ) = SyncServerChangeEntity(
        serverSequence = input.serverSequence,
        operationId = input.operationId,
        operationContentSha256 = input.operationContentSha256,
        resultCode = input.resultCode,
        captureId = input.captureId,
        eventId = input.eventId,
        revisionId = input.revisionId,
        currentRevisionId = input.currentRevisionId,
        committedAtUtc = input.committedAtUtc,
        firstEndpointId = endpointId,
        firstRequestIdentity = requestIdentity,
        verifiedAtUtc = verifiedAtUtc,
    )
}

internal class ReplicaChangeCodec {
    private val json = Json {
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val canonicalCodec = CanonicalNoteCodec()

    fun decode(input: ReplicaChangePersistence): DecodedReplicaChange = try {
        decodeVerified(input)
    } catch (error: ReplicaIntegrityException) {
        throw error
    } catch (error: Exception) {
        throw ReplicaIntegrityException(
            errorCode = "replica_change_malformed",
            message = "Replica change cannot be decoded safely",
            cause = error,
        )
    }

    private fun decodeVerified(input: ReplicaChangePersistence): DecodedReplicaChange {
        val root = json.parseToJsonElement(
            input.changeJcs.toString(StandardCharsets.UTF_8),
        ).requiredObject("serverChange")
        integrity(
            canonicalCodec.canonical(root).bytes.contentEquals(input.changeJcs),
            "replica_change_not_canonical",
            "Replica change bytes are not the exact canonical representation",
        )

        requireEqual(root.string("change_kind"), "event_revision_committed", "change_kind")
        requireEqual(root.long("server_sequence"), input.serverSequence, "server_sequence")
        requireEqual(root.string("operation_id"), input.operationId, "operation_id")
        requireEqual(
            root.string("operation_content_sha256"),
            input.operationContentSha256,
            "operation_content_sha256",
        )
        requireEqual(root.string("capture_id"), input.captureId, "capture_id")
        requireEqual(root.string("event_id"), input.eventId, "event_id")
        requireEqual(root.string("revision_id"), input.revisionId, "revision_id")
        requireEqual(
            root.string("current_revision_id"),
            input.currentRevisionId,
            "current_revision_id",
        )
        requireEqual(root.string("result_code"), input.resultCode, "result_code")
        integrity(
            input.resultCode == "applied" || input.resultCode == "conflict",
            "replica_result_invalid",
            "Replica result code is not supported",
        )

        val capture = root.objectValue("capture")
        val event = root.objectValue("event")
        val captureIdentity = capture.objectValue("identity")
        val eventIdentity = event.objectValue("identity")
        listOf("installation_id", "local_owner_id", "device_id").forEach { field ->
            requireEqual(
                captureIdentity.string(field),
                eventIdentity.string(field),
                "historical identity $field",
            )
        }
        val installationId = captureIdentity.string("installation_id")
        val localOwnerId = captureIdentity.string("local_owner_id")
        val submittingDeviceId = captureIdentity.string("device_id")

        requireEqual(capture.string("capture_id"), input.captureId, "capture.capture_id")
        requireEqual(capture.string("operation_id"), input.operationId, "capture.operation_id")
        requireEqual(event.string("event_id"), input.eventId, "event.event_id")
        requireEqual(event.string("revision_id"), input.revisionId, "event.revision_id")

        val captureSource = capture.objectValue("source")
        val eventSource = event.objectValue("source")
        requireEqual(
            eventSource.string("capture_id"),
            input.captureId,
            "event.source.capture_id",
        )
        requireEqual(
            eventSource.string("operation_id"),
            input.operationId,
            "event.source.operation_id",
        )
        listOf("channel", "recorded_at").forEach { field ->
            requireEqual(
                captureSource.string(field),
                eventSource.string(field),
                "capture/event source $field",
            )
        }
        val captureSourceOrigin = captureSource.objectValue("origin")
        listOf("source_record_id", "source_record_version").forEach { field ->
            integrity(
                captureSourceOrigin.required(field) == eventSource.required(field),
                "replica_provenance_drift",
                "capture origin/event source $field differs",
            )
        }
        verifyEquivalentObject(
            captureSourceOrigin,
            eventSource.objectValue("origin"),
            fields = listOf("provider", "app", "device", "user_entered"),
            label = "capture/event origin",
        )
        verifyEquivalentObject(
            captureSource.objectValue("collector"),
            eventSource.objectValue("collector"),
            fields = listOf("name", "version"),
            label = "capture/event collector",
        )

        requireEqual(
            capture.string("persistence_state"),
            "authenticated_ingress",
            "capture.persistence_state",
        )
        requireEqual(
            event.string("persistence_state"),
            "server_committed",
            "event.persistence_state",
        )
        requireEqual(event.string("kind"), "note", "event.kind")

        val captureContent = capture.objectValue("content")
        requireEqual(captureContent.string("kind"), "structured", "capture.content.kind")
        requireEqual(
            captureContent.string("record_type"),
            "note",
            "capture.content.record_type",
        )
        val payload = event.required("payload")
        integrity(
            captureContent.required("payload") == payload,
            "replica_payload_provenance_drift",
            "Capture payload and event payload differ",
        )
        val captureContentCanonical = canonicalCodec.canonical(captureContent)
        val captureIntegrity = capture.objectValue("integrity")
        requireEqual(
            captureIntegrity.string("sha256"),
            captureContentCanonical.sha256,
            "capture.integrity.sha256",
        )
        requireEqual(
            captureIntegrity.long("byte_size"),
            captureContentCanonical.bytes.size.toLong(),
            "capture.integrity.byte_size",
        )

        val eventServer = event.objectValue("server")
        requireEqual(
            eventServer.long("server_sequence"),
            input.serverSequence,
            "event.server.server_sequence",
        )
        requireEqual(
            eventServer.string("received_at"),
            input.committedAtUtc,
            "event.server.received_at",
        )

        val eventRevision = event.objectValue("revision")
        val parentDocuments = eventRevision.array("parents")
        integrity(
            parentDocuments.size <= 1,
            "replica_topology_invalid",
            "M2 linear revisions may have at most one parent",
        )
        val parents = parentDocuments.map { parentElement ->
            val parent = parentElement.requiredObject("revision parent")
            LocalRevisionParentEntity(
                eventId = input.eventId,
                childRevisionId = input.revisionId,
                parentRevisionId = parent.string("revision_id"),
                relation = parent.string("relation"),
            )
        }
        val revisionContent = canonicalCodec.canonical(
            buildJsonObject {
                put("event_id", input.eventId)
                put("revision_id", input.revisionId)
                put("revision_no", event.int("revision_no"))
                put("capture_id", input.captureId)
                put("operation_id", input.operationId)
                put("record_status", event.string("record_status"))
                put("effective_time", event.objectValue("time"))
                put("recorded_at", eventSource.string("recorded_at"))
                put("payload", payload)
                put(
                    "correction_reason",
                    eventRevision.required("correction_reason"),
                )
                put(
                    "parent_revision_id",
                    parents.singleOrNull()
                        ?.let { JsonPrimitive(it.parentRevisionId) }
                        ?: JsonNull,
                )
            },
        )
        requireEqual(
            eventRevision.string("content_sha256"),
            revisionContent.sha256,
            "event.revision.content_sha256",
        )

        val recordedAt = eventSource.string("recorded_at")
        val recordedAtEpochMs = OffsetDateTime.parse(recordedAt).toInstant().toEpochMilli()
        val captureOrigin = captureSource.objectValue("origin")
        val captureCollector = captureSource.objectValue("collector")
        val time = event.objectValue("time")
        val eventOrigin = eventSource.objectValue("origin")
        val eventCollector = eventSource.objectValue("collector")
        val effectiveStartUtc = time.string("effective_start_utc")
        val effectiveEndUtc = time.nullableString("effective_end_utc")

        return DecodedReplicaChange(
            input = input,
            installation = LocalInstallationEntity(
                installationId = installationId,
                createdAtUtc = recordedAt,
                serverDeviceId = submittingDeviceId,
            ),
            owner = LocalOwnerEntity(
                localOwnerId = localOwnerId,
                installationId = installationId,
                createdAtUtc = recordedAt,
                serverPersonId = null,
            ),
            capture = LocalCaptureEntity(
                captureId = input.captureId,
                operationId = input.operationId,
                installationId = installationId,
                localOwnerId = localOwnerId,
                schemaVersion = capture.string("schema_version"),
                persistenceState = capture.string("persistence_state"),
                sourceChannel = captureSource.string("channel"),
                recordedAtRfc3339 = recordedAt,
                recordedAtEpochMs = recordedAtEpochMs,
                timezoneId = captureSource.string("timezone_id"),
                utcOffsetMinutes = captureSource.int("utc_offset_minutes"),
                originProvider = captureOrigin.nullableString("provider"),
                originApp = captureOrigin.nullableString("app"),
                originDevice = captureOrigin.nullableString("device"),
                originSourceRecordId = captureOrigin.nullableString("source_record_id"),
                originSourceRecordVersion =
                    captureOrigin.nullableString("source_record_version"),
                originUserEntered = captureOrigin.boolean("user_entered"),
                collectorName = captureCollector.string("name"),
                collectorVersion = captureCollector.string("version"),
                contentJcs = captureContentCanonical.bytes,
                contentSha256 = captureContentCanonical.sha256,
                byteSize = captureContentCanonical.bytes.size.toLong(),
            ),
            event = LocalLifeEventEntity(
                eventId = input.eventId,
                localOwnerId = localOwnerId,
                kind = event.string("kind"),
                createdAtUtc = eventRevision.string("created_at"),
            ),
            revision = LocalEventRevisionEntity(
                revisionId = input.revisionId,
                eventId = input.eventId,
                captureId = input.captureId,
                operationId = input.operationId,
                revisionNo = event.int("revision_no"),
                schemaVersion = event.string("schema_version"),
                assertionStatus = event.string("assertion_status"),
                lifecycle = event.nullableString("lifecycle"),
                recordStatus = event.string("record_status"),
                verificationStatus = event.string("verification_status"),
                sourceChannel = eventSource.string("channel"),
                sourceRecordId = eventSource.nullableString("source_record_id"),
                sourceRecordVersion = eventSource.nullableString("source_record_version"),
                sourceModifiedAt = eventSource.nullableString("source_modified_at"),
                recordedAtRfc3339 = recordedAt,
                originProvider = eventOrigin.nullableString("provider"),
                originApp = eventOrigin.nullableString("app"),
                originDevice = eventOrigin.nullableString("device"),
                originUserEntered = eventOrigin.boolean("user_entered"),
                collectorName = eventCollector.string("name"),
                collectorVersion = eventCollector.string("version"),
                effectiveStartUtc = effectiveStartUtc,
                effectiveStartEpochMs = Instant.parse(effectiveStartUtc).toEpochMilli(),
                effectiveEndUtc = effectiveEndUtc,
                effectiveEndEpochMs = effectiveEndUtc?.let(Instant::parse)?.toEpochMilli(),
                originalLocalStart = time.string("original_local_start"),
                originalLocalEnd = time.nullableString("original_local_end"),
                timezoneId = time.string("timezone_id"),
                startOffsetSeconds = time.int("start_offset_seconds"),
                endOffsetSeconds = time.nullableInt("end_offset_seconds"),
                temporalPrecision = time.string("temporal_precision"),
                localDate = time.string("local_date"),
                sourceExpression = time.nullableString("source_expression"),
                payloadJcs = canonicalCodec.canonical(payload).bytes,
                evidenceJcs = canonicalCodec.canonical(event.required("evidence")).bytes,
                qualityFlagsJcs =
                    canonicalCodec.canonical(event.required("quality_flags")).bytes,
                createdAtRfc3339 = eventRevision.string("created_at"),
                contentSha256 = eventRevision.string("content_sha256"),
                actor = eventRevision.string("actor"),
                correctionReason = eventRevision.nullableString("correction_reason"),
                serverReceivedAt = input.committedAtUtc,
                serverSequence = input.serverSequence,
            ),
            parents = parents,
        )
    }

    private fun verifyEquivalentObject(
        first: JsonObject,
        second: JsonObject,
        fields: List<String>,
        label: String,
    ) {
        fields.forEach { field ->
            integrity(
                first.required(field) == second.required(field),
                "replica_provenance_drift",
                "$label $field differs",
            )
        }
    }

    private fun requireEqual(
        actual: Any?,
        expected: Any?,
        field: String,
    ) {
        integrity(
            actual == expected,
            "replica_projection_drift",
            "$field differs from the durable scalar projection",
        )
    }
}

internal data class ReplicaTopologyRecord(
    val serverSequence: Long,
    val operationId: String,
    val operationContentSha256: String,
    val captureId: String,
    val eventId: String,
    val revisionId: String,
    val revisionNo: Int,
    val parentRevisionId: String?,
    val eventKind: String,
    val resultCode: String,
    val currentRevisionId: String,
)

internal class ReplicaTopologyState {
    private val operations = mutableMapOf<String, Pair<String, String>>()
    private val captures = mutableMapOf<String, String>()
    private val revisions = mutableMapOf<String, RevisionNode>()
    private val events = mutableMapOf<String, String>()
    private val sequences = mutableMapOf<Long, String>()
    private val currentByEvent = mutableMapOf<String, String>()

    var lastServerSequence: Long = 0
        private set

    fun accept(change: DecodedReplicaChange) {
        accept(
            ReplicaTopologyRecord(
                serverSequence = change.input.serverSequence,
                operationId = change.input.operationId,
                operationContentSha256 = change.input.operationContentSha256,
                captureId = change.input.captureId,
                eventId = change.input.eventId,
                revisionId = change.input.revisionId,
                revisionNo = change.revision.revisionNo,
                parentRevisionId = change.parents.singleOrNull()?.parentRevisionId,
                eventKind = change.event.kind,
                resultCode = change.input.resultCode,
                currentRevisionId = change.input.currentRevisionId,
            ),
        )
    }

    fun accept(record: ReplicaTopologyRecord) {
        integrity(
            record.serverSequence > lastServerSequence,
            "replica_topology_invalid",
            "Server sequence did not strictly advance",
        )
        integrity(
            operations.putIfAbsent(
                record.operationId,
                record.operationContentSha256 to record.revisionId,
            ) == null,
            "replica_operation_collision",
            "Operation identity was reused in the applied stream",
        )
        integrity(
            captures.putIfAbsent(record.captureId, record.operationId) == null,
            "replica_capture_collision",
            "Capture identity was reused in the applied stream",
        )
        integrity(
            sequences.putIfAbsent(record.serverSequence, record.operationId) == null,
            "replica_sequence_collision",
            "Server sequence was reused in the applied stream",
        )
        integrity(
            revisions[record.revisionId] == null,
            "replica_revision_collision",
            "Revision identity was reused in the applied stream",
        )

        val priorKind = events[record.eventId]
        val parent = record.parentRevisionId?.let(revisions::get)
        if (record.parentRevisionId == null) {
            integrity(
                priorKind == null,
                "replica_event_collision",
                "A second root reused an event identity",
            )
            integrity(
                record.revisionNo == 1,
                "replica_topology_invalid",
                "A root revision must have revision_no 1",
            )
        } else {
            integrity(
                parent != null && parent.eventId == record.eventId,
                "replica_topology_invalid",
                "Revision parent is absent or belongs to another event",
            )
            integrity(
                record.revisionNo == checkNotNull(parent).revisionNo + 1,
                "replica_topology_invalid",
                "Revision number does not follow its parent",
            )
            integrity(
                priorKind == record.eventKind,
                "replica_event_collision",
                "Event kind changed inside one event history",
            )
        }

        val priorHead = currentByEvent[record.eventId]
        val expectedResult =
            if (record.parentRevisionId == priorHead) "applied" else "conflict"
        val expectedHead =
            if (expectedResult == "applied") record.revisionId else priorHead
        integrity(
            record.resultCode == expectedResult,
            "replica_cas_drift",
            "Result code does not match the historical event head",
        )
        integrity(
            expectedHead != null && record.currentRevisionId == expectedHead,
            "replica_cas_drift",
            "Current revision does not match the historical event head",
        )

        events.putIfAbsent(record.eventId, record.eventKind)
        revisions[record.revisionId] = RevisionNode(
            eventId = record.eventId,
            revisionNo = record.revisionNo,
        )
        if (record.resultCode == "applied") {
            currentByEvent[record.eventId] = record.revisionId
        }
        lastServerSequence = record.serverSequence
    }

    private data class RevisionNode(
        val eventId: String,
        val revisionNo: Int,
    )
}

private fun JsonElement.requiredObject(label: String): JsonObject =
    this as? JsonObject ?: integrityFailure(
        "replica_change_malformed",
        "$label must be an object",
    )

private fun JsonObject.required(name: String): JsonElement =
    this[name] ?: integrityFailure(
        "replica_change_malformed",
        "Replica change is missing $name",
    )

private fun JsonObject.objectValue(name: String): JsonObject =
    required(name).requiredObject(name)

private fun JsonObject.array(name: String): JsonArray =
    required(name) as? JsonArray ?: integrityFailure(
        "replica_change_malformed",
        "$name must be an array",
    )

private fun JsonObject.primitive(name: String): JsonPrimitive =
    required(name) as? JsonPrimitive ?: integrityFailure(
        "replica_change_malformed",
        "$name must be a primitive",
    )

private fun JsonObject.string(name: String): String {
    val value = primitive(name)
    return if (value.isString) {
        value.content
    } else {
        integrityFailure("replica_change_malformed", "$name must be a string")
    }
}

private fun JsonObject.nullableString(name: String): String? =
    when (val value = required(name)) {
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) {
            value.content
        } else {
            integrityFailure("replica_change_malformed", "$name must be a string or null")
        }
        else -> integrityFailure(
            "replica_change_malformed",
            "$name must be a string or null",
        )
    }

private fun JsonObject.int(name: String): Int = primitive(name).int

private fun JsonObject.nullableInt(name: String): Int? =
    when (val value = required(name)) {
        JsonNull -> null
        is JsonPrimitive -> value.int
        else -> integrityFailure(
            "replica_change_malformed",
            "$name must be an integer or null",
        )
    }

private fun JsonObject.long(name: String): Long = primitive(name).long

private fun JsonObject.boolean(name: String): Boolean = primitive(name).boolean

private fun integrity(
    condition: Boolean,
    errorCode: String,
    message: String,
) {
    if (!condition) {
        integrityFailure(errorCode, message)
    }
}

private fun integrityFailure(
    errorCode: String,
    message: String,
): Nothing = throw ReplicaIntegrityException(errorCode, message)
