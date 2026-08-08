package ru.andriyshkoy.lifeagent.data.local.serialization

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.data.local.db.dao.RevisionContextRow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorrectWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingCatalogException
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOption
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPayload
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPolicy
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

data class WellbeingRevisionEncoding(
    val payload: CanonicalBytes,
    val evidence: CanonicalBytes,
    val qualityFlags: CanonicalBytes,
    val contentSha256: String,
)

class CanonicalWellbeingCodec {
    fun encodeCaptureContent(payload: WellbeingPayload): CanonicalBytes = canonical(
        buildJsonObject {
            put("kind", "structured")
            put("record_type", WELLBEING_KIND)
            put("payload", payloadElement(payload))
        },
    )

    fun encodeRevision(
        ids: MutationIds,
        revisionNo: Int,
        payload: WellbeingPayload,
        status: WellbeingRecordStatus,
        effectiveTime: ResolvedPointTime,
        recordedAt: OffsetDateTime,
        correctionReason: String?,
        parentRevisionId: String?,
    ): WellbeingRevisionEncoding {
        val payloadBytes = canonical(payloadElement(payload))
        val evidence = canonical(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("capture_ref", "#/source/capture_id")
                        put("field_path", "/payload")
                        put("artifact_id", JsonNull)
                        put(
                            "locator",
                            if (status == WellbeingRecordStatus.RETRACTED) {
                                "android_form:/wellbeing/undo"
                            } else {
                                "android_form:/wellbeing"
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
                put("payload", payloadElement(payload))
                put("correction_reason", correctionReason.asElement())
                put("parent_revision_id", parentRevisionId.asElement())
            },
        )
        return WellbeingRevisionEncoding(
            payload = payloadBytes,
            evidence = evidence,
            qualityFlags = qualityFlags,
            contentSha256 = content.sha256,
        )
    }

    fun commandFingerprint(command: CreateWellbeingCommand): String {
        val payload = WellbeingPolicy.normalizePayload(command.values, command.comment)
        return canonical(
            buildJsonObject {
                put("command", "create_wellbeing")
                put("ids", ids(command.ids))
                put("payload", payloadElement(payload))
                put("effective_time", eventTime(command.effectiveTime))
                put("recorded_at", formatOffset(command.recordedAt))
            },
        ).sha256
    }

    fun commandFingerprint(command: CorrectWellbeingCommand): String {
        val payload = WellbeingPolicy.normalizePayload(command.values, command.comment)
        return canonical(
            buildJsonObject {
                put("command", "correct_wellbeing")
                put("ids", ids(command.ids))
                put("expected_current_revision_id", command.expectedCurrentRevisionId.toString())
                put("payload", payloadElement(payload))
                put("effective_time", eventTime(command.effectiveTime))
                put("recorded_at", formatOffset(command.recordedAt))
                put("reason", command.reason.asElement())
            },
        ).sha256
    }

    fun commandFingerprint(command: RetractWellbeingCommand): String = canonical(
        buildJsonObject {
            put("command", "retract_wellbeing")
            put("ids", ids(command.ids))
            put("expected_current_revision_id", command.expectedCurrentRevisionId.toString())
            put("recorded_at", formatOffset(command.recordedAt))
            put("reason", command.reason)
        },
    ).sha256

    fun decodePayload(payloadJcs: ByteArray): WellbeingPayload {
        val canonicalPayload = requireCanonical(payloadJcs)
        try {
            val document = canonicalPayload.element.requireObject("wellbeing payload")
            document.requireExactKeys(setOf("values", "comment"), "wellbeing payload")
            val values = document["values"].requireArray("wellbeing payload.values")
                .mapIndexed { index, element -> decodeValue(element, index) }
            val comment = when (val element = document["comment"]) {
                JsonNull -> null
                is JsonPrimitive -> if (element.isString) element.content else {
                    throw IllegalArgumentException("wellbeing payload.comment must be text or null")
                }
                else -> throw IllegalArgumentException("wellbeing payload.comment must be text or null")
            }
            return WellbeingPolicy.normalizePayload(values, comment)
        } finally {
            canonicalPayload.bytes.fill(0)
        }
    }

    fun encodeCanonicalEvent(
        row: RevisionContextRow,
        parents: List<LocalRevisionParentEntity>,
    ): CanonicalBytes {
        requireCanonicalAndWipe(row.revision.payloadJcs)
        requireCanonicalAndWipe(row.revision.evidenceJcs)
        requireCanonicalAndWipe(row.revision.qualityFlagsJcs)
        val revision = row.revision
        return canonical(
            buildJsonObject {
                put("schema_version", revision.schemaVersion)
                putJsonObject("identity") {
                    put("installation_id", row.installationId)
                    put("local_owner_id", row.localOwnerId)
                }
                put("event_id", revision.eventId)
                put("revision_id", revision.revisionId)
                put("revision_no", revision.revisionNo)
                put("kind", WELLBEING_KIND)
                put("assertion_status", revision.assertionStatus)
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
            },
        )
    }

    fun canonical(element: JsonElement): CanonicalBytes {
        val sorted = sortKeys(element)
        val bytes = JSON.encodeToString(JsonElement.serializer(), sorted)
            .toByteArray(StandardCharsets.UTF_8)
        return CanonicalBytes(bytes = bytes, sha256 = sha256(bytes))
    }

    private fun payloadElement(payload: WellbeingPayload): JsonObject = buildJsonObject {
        putJsonArray("values") {
            payload.values.forEach { value ->
                add(
                    buildJsonObject {
                        put("dimension_id", value.dimensionId.toString())
                        put("dimension_version", value.dimensionVersion)
                        put("dimension_label_snapshot", value.dimensionLabel)
                        put("option_id", value.optionId.toString())
                        put("option_version", value.optionVersion)
                        put("option_label_snapshot", value.optionLabel)
                        put("option_sort_order_snapshot", value.optionSortOrder)
                    },
                )
            }
        }
        put("comment", payload.comment.asElement())
    }

    private fun decodeValue(element: JsonElement, index: Int): WellbeingValueSnapshot {
        val path = "wellbeing payload.values[$index]"
        val value = element.requireObject(path)
        value.requireExactKeys(
            setOf(
                "dimension_id",
                "dimension_version",
                "dimension_label_snapshot",
                "option_id",
                "option_version",
                "option_label_snapshot",
                "option_sort_order_snapshot",
            ),
            path,
        )
        return WellbeingValueSnapshot(
            dimensionId = UUID.fromString(value.requireString("dimension_id", path)),
            dimensionVersion = value.requireInt("dimension_version", path),
            dimensionLabel = value.requireString("dimension_label_snapshot", path),
            optionId = UUID.fromString(value.requireString("option_id", path)),
            optionVersion = value.requireInt("option_version", path),
            optionLabel = value.requireString("option_label_snapshot", path),
            optionSortOrder = value.requireInt("option_sort_order_snapshot", path),
        )
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

    private fun parse(value: ByteArray): JsonElement =
        JSON.parseToJsonElement(value.toString(StandardCharsets.UTF_8))

    private fun requireCanonical(value: ByteArray): ParsedCanonical {
        val element = parse(value)
        val encoded = canonical(element)
        if (!encoded.bytes.contentEquals(value)) {
            encoded.bytes.fill(0)
            throw IllegalArgumentException("Persisted wellbeing JSON is not canonical")
        }
        return ParsedCanonical(element = element, bytes = encoded.bytes)
    }

    private fun requireCanonicalAndWipe(value: ByteArray) {
        requireCanonical(value).bytes.fill(0)
    }

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { (key, value) -> key to sortKeys(value) },
        )
        is JsonArray -> JsonArray(element.map(::sortKeys))
        else -> element
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class ParsedCanonical(
        val element: JsonElement,
        val bytes: ByteArray,
    )

    companion object {
        const val EVENT_SCHEMA_VERSION = "5.0.0"
        const val CAPTURE_SCHEMA_VERSION = "5.0.0"
        const val COLLECTOR_NAME = "life-agent-android"
        const val WELLBEING_KIND = "wellbeing"

        private val OFFSET_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
        private val LOCAL_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS")
        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
            prettyPrint = false
        }

        fun formatOffset(value: OffsetDateTime): String = OFFSET_DATE_TIME_FORMAT.format(value)

        fun formatInstant(value: Instant): String =
            value.truncatedTo(ChronoUnit.MILLIS).toString()

        fun formatLocalDateTime(value: LocalDateTime): String =
            LOCAL_DATE_TIME_FORMAT.format(value)
    }
}

class CanonicalWellbeingCatalogCodec(
    private val canonicalCodec: CanonicalWellbeingCodec = CanonicalWellbeingCodec(),
) {
    fun encodePayload(
        label: String,
        sortOrder: Int,
        active: Boolean,
        options: List<WellbeingOption>,
    ): CanonicalBytes = canonicalCodec.canonical(
        buildJsonObject {
            put("active", active)
            put("label", label)
            putJsonArray("options") {
                options.forEach { option ->
                    add(
                        buildJsonObject {
                            put("active", option.active)
                            put("label", option.label)
                            put("option_id", option.optionId.toString())
                            put("option_version", option.version)
                            put("sort_order", option.sortOrder)
                        },
                    )
                }
            }
            put("sort_order", sortOrder)
        },
    )

    fun decodeDimension(
        dimensionId: UUID,
        catalogVersionId: UUID,
        version: Int,
        payloadJcs: ByteArray,
    ): WellbeingDimension {
        val element = JSON.parseToJsonElement(payloadJcs.toString(StandardCharsets.UTF_8))
        val canonical = canonicalCodec.canonical(element)
        try {
            if (!canonical.bytes.contentEquals(payloadJcs)) {
                throw IllegalArgumentException("Persisted wellbeing catalog JSON is not canonical")
            }
            val document = element.requireObject("wellbeing dimension")
            document.requireExactKeys(
                setOf("active", "label", "options", "sort_order"),
                "wellbeing dimension",
            )
            val label = document.requireString("label", "wellbeing dimension")
            val active = document.requireBoolean("active", "wellbeing dimension")
            val options = document["options"].requireArray("wellbeing dimension.options")
                .mapIndexed { index, optionElement ->
                    val path = "wellbeing dimension.options[$index]"
                    val option = optionElement.requireObject(path)
                    option.requireExactKeys(
                        setOf("active", "label", "option_id", "option_version", "sort_order"),
                        path,
                    )
                    WellbeingOption(
                        optionId = UUID.fromString(option.requireString("option_id", path)),
                        version = option.requireInt("option_version", path),
                        label = option.requireString("label", path),
                        sortOrder = option.requireInt("sort_order", path),
                        active = option.requireBoolean("active", path),
                    )
                }
            try {
                if (WellbeingPolicy.normalizeCatalogLabel(label, "Dimension label") != label) {
                    throw IllegalArgumentException("Stored dimension label is not normalized")
                }
                options.forEach { option ->
                    if (
                        WellbeingPolicy.normalizeCatalogLabel(option.label, "Option label") !=
                        option.label
                    ) {
                        throw IllegalArgumentException("Stored option label is not normalized")
                    }
                }
                WellbeingPolicy.validateOptionDrafts(
                    options.map {
                        ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOptionDraft(
                            optionId = it.optionId,
                            label = it.label,
                            sortOrder = it.sortOrder,
                            active = it.active,
                        )
                    },
                    dimensionActive = active,
                )
            } catch (error: InvalidWellbeingCatalogException) {
                throw IllegalArgumentException("Stored wellbeing catalog violates policy", error)
            }
            if (version < 1 || options.any { it.version < 1 }) {
                throw IllegalArgumentException("Persisted wellbeing catalog has an invalid version")
            }
            return WellbeingDimension(
                dimensionId = dimensionId,
                catalogVersionId = catalogVersionId,
                version = version,
                label = label,
                sortOrder = document.requireInt("sort_order", "wellbeing dimension"),
                active = active,
                options = options,
            )
        } finally {
            canonical.bytes.fill(0)
        }
    }

    companion object {
        const val SCHEMA_VERSION = "1.0.0"

        private val JSON = Json {
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

private fun JsonElement?.requireObject(path: String): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("$path must be an object")

private fun JsonElement?.requireArray(path: String): JsonArray =
    this as? JsonArray ?: throw IllegalArgumentException("$path must be an array")

private fun JsonObject.requireExactKeys(expected: Set<String>, path: String) {
    if (keys != expected) {
        throw IllegalArgumentException("$path has an invalid field set")
    }
}

private fun JsonObject.requireString(name: String, path: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("$path.$name must be text")
    if (!primitive.isString) throw IllegalArgumentException("$path.$name must be text")
    return primitive.content
}

private fun JsonObject.requireInt(name: String, path: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull
        ?: throw IllegalArgumentException("$path.$name must be an integer")

private fun JsonObject.requireBoolean(name: String, path: String): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull
        ?: throw IllegalArgumentException("$path.$name must be a boolean")

private fun String?.asElement(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
