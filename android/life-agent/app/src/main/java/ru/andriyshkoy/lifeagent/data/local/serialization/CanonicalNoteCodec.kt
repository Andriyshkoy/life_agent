package ru.andriyshkoy.lifeagent.data.local.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.data.local.db.dao.RevisionContextRow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class CanonicalBytes(
    val bytes: ByteArray,
    val sha256: String,
) {
    val utf8: String
        get() = bytes.toString(StandardCharsets.UTF_8)
}

data class NoteRevisionEncoding(
    val payload: CanonicalBytes,
    val evidence: CanonicalBytes,
    val qualityFlags: CanonicalBytes,
    val contentSha256: String,
)

class CanonicalNoteCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    fun encodeCaptureContent(text: String): CanonicalBytes = canonical(
        buildJsonObject {
            put("kind", "structured")
            put("record_type", "note")
            putJsonObject("payload") {
                put("text", text)
            }
        },
    )

    fun encodeRevision(
        ids: MutationIds,
        revisionNo: Int,
        text: String,
        status: NoteRecordStatus,
        effectiveTime: ResolvedPointTime,
        recordedAt: OffsetDateTime,
        correctionReason: String?,
        parentRevisionId: String?,
    ): NoteRevisionEncoding {
        val payload = canonical(
            buildJsonObject {
                put("text", text)
            },
        )
        val evidence = canonical(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("capture_ref", "#/source/capture_id")
                        put("field_path", "/payload/text")
                        put("artifact_id", JsonNull)
                        put(
                            "locator",
                            if (status == NoteRecordStatus.RETRACTED) {
                                "android_form:/note/undo"
                            } else {
                                "android_form:/note/text"
                            },
                        )
                        put("excerpt", JsonNull)
                        put("human_confirmed", true)
                    },
                )
            },
        )
        val qualityFlags = canonical(JsonArray(emptyList()))
        val content = canonical(
            buildJsonObject {
                put("event_id", ids.eventId.toString())
                put("revision_id", ids.revisionId.toString())
                put("revision_no", revisionNo)
                put("capture_id", ids.captureId.toString())
                put("operation_id", ids.operationId.toString())
                put("record_status", status.storageValue)
                put("effective_time", eventTime(effectiveTime))
                put("recorded_at", formatOffset(recordedAt))
                put("payload", parse(payload))
                put("correction_reason", correctionReason.asElement())
                put("parent_revision_id", parentRevisionId.asElement())
            },
        )
        return NoteRevisionEncoding(
            payload = payload,
            evidence = evidence,
            qualityFlags = qualityFlags,
            contentSha256 = content.sha256,
        )
    }

    fun encodePendingOperation(
        ids: MutationIds,
        baseRevisionId: String?,
        status: NoteRecordStatus,
        revisionContentSha256: String,
    ): CanonicalBytes = canonical(
        buildJsonObject {
            put("schema_version", "pending-append-revision/1")
            put("operation_id", ids.operationId.toString())
            put("operation_kind", "append_event_revision")
            put("capture_id", ids.captureId.toString())
            put("event_id", ids.eventId.toString())
            put("revision_id", ids.revisionId.toString())
            put("base_revision_id", baseRevisionId.asElement())
            put("record_status", status.storageValue)
            put("revision_content_sha256", revisionContentSha256)
        },
    )

    fun commandFingerprint(command: CreateNoteCommand): String = canonical(
        buildJsonObject {
            put("command", "create_note")
            put("ids", ids(command.ids))
            put("text", command.text)
            put("effective_time", eventTime(command.effectiveTime))
            put("recorded_at", formatOffset(command.recordedAt))
        },
    ).sha256

    fun commandFingerprint(command: CorrectNoteCommand): String = canonical(
        buildJsonObject {
            put("command", "correct_note")
            put("ids", ids(command.ids))
            put("expected_current_revision_id", command.expectedCurrentRevisionId.toString())
            put("text", command.text)
            put("effective_time", eventTime(command.effectiveTime))
            put("recorded_at", formatOffset(command.recordedAt))
            put("reason", command.reason.asElement())
        },
    ).sha256

    fun commandFingerprint(command: RetractNoteCommand): String = canonical(
        buildJsonObject {
            put("command", "retract_note")
            put("ids", ids(command.ids))
            put("expected_current_revision_id", command.expectedCurrentRevisionId.toString())
            put("recorded_at", formatOffset(command.recordedAt))
            put("reason", command.reason)
        },
    ).sha256

    fun decodeNoteText(payloadJcs: ByteArray): String {
        val payload = parse(payloadJcs)
        return (payload as? JsonObject)
            ?.get("text")
            ?.let { it as? JsonPrimitive }
            ?.content
            ?: throw IllegalArgumentException("Persisted note payload has no text")
    }

    fun encodeCanonicalEvent(
        row: RevisionContextRow,
        parents: List<LocalRevisionParentEntity>,
    ): CanonicalBytes {
        val revision = row.revision
        val serverCommitted =
            revision.serverReceivedAt != null && revision.serverSequence != null
        return canonical(
            buildJsonObject {
                put("schema_version", revision.schemaVersion)
                put(
                    "persistence_state",
                    if (serverCommitted) "server_committed" else "local_pending",
                )
                putJsonObject("identity") {
                    put("installation_id", row.installationId)
                    put("local_owner_id", row.localOwnerId)
                    put("device_id", row.serverDeviceId.asElement())
                }
                put("event_id", revision.eventId)
                put("revision_id", revision.revisionId)
                put("revision_no", revision.revisionNo)
                put("kind", "note")
                put("assertion_status", revision.assertionStatus)
                put("lifecycle", revision.lifecycle.asElement())
                put("record_status", revision.recordStatus)
                put("verification_status", revision.verificationStatus)
                putJsonObject("source") {
                    put("capture_id", revision.captureId)
                    put("operation_id", revision.operationId)
                    put("channel", revision.sourceChannel)
                    put("source_record_id", revision.sourceRecordId.asElement())
                    put("source_record_version", revision.sourceRecordVersion.asElement())
                    put("source_modified_at", revision.sourceModifiedAt.asElement())
                    put("recorded_at", revision.recordedAtRfc3339)
                    putJsonObject("origin") {
                        put("provider", revision.originProvider.asElement())
                        put("app", revision.originApp.asElement())
                        put("device", revision.originDevice.asElement())
                        put("user_entered", revision.originUserEntered)
                    }
                    putJsonObject("collector") {
                        put("name", revision.collectorName)
                        put("version", revision.collectorVersion)
                    }
                }
                putJsonObject("time") {
                    put("effective_start_utc", revision.effectiveStartUtc)
                    put("effective_end_utc", revision.effectiveEndUtc.asElement())
                    put("original_local_start", revision.originalLocalStart)
                    put("original_local_end", revision.originalLocalEnd.asElement())
                    put("timezone_id", revision.timezoneId)
                    put("start_offset_seconds", revision.startOffsetSeconds)
                    put(
                        "end_offset_seconds",
                        revision.endOffsetSeconds?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put("temporal_precision", revision.temporalPrecision)
                    put("local_date", revision.localDate)
                    put("source_expression", revision.sourceExpression.asElement())
                }
                put("payload", parse(revision.payloadJcs))
                put("evidence", parse(revision.evidenceJcs))
                put("quality_flags", parse(revision.qualityFlagsJcs))
                putJsonObject("revision") {
                    put("created_at", revision.createdAtRfc3339)
                    put("content_sha256", revision.contentSha256)
                    put("actor", revision.actor)
                    put("correction_reason", revision.correctionReason.asElement())
                    putJsonArray("parents") {
                        parents.forEach { parent ->
                            add(
                                buildJsonObject {
                                    put("revision_id", parent.parentRevisionId)
                                    put("relation", parent.relation)
                                },
                            )
                        }
                    }
                }
                putJsonObject("server") {
                    put("received_at", revision.serverReceivedAt.asElement())
                    put(
                        "server_sequence",
                        revision.serverSequence?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            },
        )
    }

    fun canonical(element: JsonElement): CanonicalBytes {
        val sorted = sortKeys(element)
        val bytes = json.encodeToString(JsonElement.serializer(), sorted)
            .toByteArray(StandardCharsets.UTF_8)
        return CanonicalBytes(bytes = bytes, sha256 = sha256(bytes))
    }

    private fun ids(ids: MutationIds): JsonObject = buildJsonObject {
        put("operation_id", ids.operationId.toString())
        put("capture_id", ids.captureId.toString())
        put("event_id", ids.eventId.toString())
        put("revision_id", ids.revisionId.toString())
    }

    private fun eventTime(time: ResolvedPointTime): JsonObject = buildJsonObject {
        put("effective_start_utc", formatInstant(time.effectiveAt))
        put("effective_end_utc", JsonNull)
        put("original_local_start", formatLocalDateTime(time.originalLocal))
        put("original_local_end", JsonNull)
        put("timezone_id", time.timezoneId.id)
        put("start_offset_seconds", time.offset.totalSeconds)
        put("end_offset_seconds", JsonNull)
        put("temporal_precision", time.precision.storageValue)
        put("local_date", time.localDate.toString())
        put("source_expression", JsonNull)
    }

    private fun parse(value: CanonicalBytes): JsonElement = parse(value.bytes)

    private fun parse(value: ByteArray): JsonElement =
        json.parseToJsonElement(value.toString(StandardCharsets.UTF_8))

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to sortKeys(value) },
        )

        is JsonArray -> JsonArray(element.map(::sortKeys))
        else -> element
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val EVENT_SCHEMA_VERSION = "4.0.0"
        const val CAPTURE_SCHEMA_VERSION = "4.0.0"
        const val COLLECTOR_NAME = "life-agent-android"

        private val OFFSET_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
        private val LOCAL_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS")

        fun formatOffset(value: OffsetDateTime): String =
            OFFSET_DATE_TIME_FORMAT.format(value)

        fun formatInstant(value: Instant): String =
            value.truncatedTo(ChronoUnit.MILLIS).toString()

        fun formatLocalDateTime(value: LocalDateTime): String =
            LOCAL_DATE_TIME_FORMAT.format(value)
    }
}

private fun String?.asElement(): JsonElement =
    this?.let(::JsonPrimitive) ?: JsonNull
