package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

internal enum class WireProtocolFailure {
    JSON_TRUST_BOUNDARY,
    SCHEMA_MISMATCH,
    CORRELATION_MISMATCH,
    STATUS_ERROR_MISMATCH,
    HASH_MISMATCH,
    ORDER_MISMATCH,
    PAGE_INVARIANT,
    AUTH_INVARIANT,
}

/** Content-free by construction; raw JSON, identifiers, and credentials are omitted. */
internal class WireProtocolException(
    val failure: WireProtocolFailure,
    cause: Throwable? = null,
) : IllegalArgumentException("M2 wire response rejected: ${failure.name.lowercase()}", cause)

private val UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val SERVER_INSTANT_PATTERN = Regex(
    "^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])" +
        "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{3})?Z$",
)
private val OFFSET_INSTANT_PATTERN = Regex(
    "^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])" +
        "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{3})?" +
        "(?:Z|[+-](?!00:00)(?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))$",
)
private val LOCAL_DATE_TIME_PATTERN = Regex(
    "^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])" +
        "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{3})?$",
)
private val DATE_PATTERN = Regex("^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$")
private val FIXED_OFFSET_PATTERN = Regex("^(?:Z|[+-](?:0[0-9]|1[0-3]):[0-5][0-9]|[+-]14:00)$")
private val INSTALLED_IANA_ZONE_IDS: Set<String> = ZoneId.getAvailableZoneIds().toSet()
private val ENROLLMENT_CODE_PATTERN = Regex("^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){6}$")
private val QUALITY_FLAG_PATTERN = Regex("^[a-z][a-z0-9_]*$")
private val FIELD_PATH_PATTERN = Regex("^/payload(?:/(?:[^~/]|~0|~1)*)*$")
private val API_FIELD_PATH_PATTERN = Regex("^(?:|/operations/(?:0|[1-9][0-9]?))$")

internal fun requireCanonicalUuid(value: String): String =
    value.takeIf(UUID_PATTERN::matches) ?: schemaFailure()

internal fun requireSha256(value: String): String =
    value.takeIf(SHA256_PATTERN::matches) ?: schemaFailure()

internal fun requireCursor(value: String): String {
    if (value.length !in 43..2048 || value.any { it !in BASE64URL_CHARACTERS }) {
        schemaFailure()
    }
    val decoded = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        schemaFailure()
    }
    try {
        val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
        if (canonical != value || decoded.size < 32) {
            schemaFailure()
        }
    } finally {
        decoded.fill(0)
    }
    return value
}

internal fun requireCanonicalServerInstant(value: String): String {
    if (!SERVER_INSTANT_PATTERN.matches(value)) {
        schemaFailure()
    }
    try {
        Instant.parse(value)
    } catch (_: DateTimeException) {
        schemaFailure()
    }
    return value
}

/**
 * Accepts the canonical precision emitted by [Instant.toString] for local
 * clocks. Server timestamps deliberately remain restricted to milliseconds,
 * while Android clocks may expose microsecond or nanosecond precision.
 */
internal fun requireCanonicalLocalInstant(value: String): String {
    val parsed = try {
        Instant.parse(value)
    } catch (_: DateTimeException) {
        schemaFailure()
    }
    if (parsed.toString() != value) schemaFailure()
    val utcYear = try {
        parsed.atOffset(ZoneOffset.UTC).year
    } catch (_: DateTimeException) {
        schemaFailure()
    }
    if (utcYear !in 1..9999) schemaFailure()
    return value
}

internal fun requireCanonicalOffsetInstant(value: String): String {
    if (!OFFSET_INSTANT_PATTERN.matches(value)) {
        schemaFailure()
    }
    try {
        val utcYear = OffsetDateTime.parse(value)
            .withOffsetSameInstant(ZoneOffset.UTC)
            .year
        if (utcYear !in 1..9999) schemaFailure()
    } catch (_: DateTimeException) {
        schemaFailure()
    }
    return value
}

internal fun requireCanonicalLocalDateTime(value: String): String {
    if (!LOCAL_DATE_TIME_PATTERN.matches(value)) {
        schemaFailure()
    }
    try {
        LocalDateTime.parse(value)
    } catch (_: DateTimeException) {
        schemaFailure()
    }
    return value
}

internal fun requireCanonicalDate(value: String): String {
    if (!DATE_PATTERN.matches(value)) {
        schemaFailure()
    }
    try {
        LocalDate.parse(value)
    } catch (_: DateTimeException) {
        schemaFailure()
    }
    return value
}

internal fun requireEnrollmentCode(secret: WipeableSecret) {
    val text = secret.asciiText()
    if (!ENROLLMENT_CODE_PATTERN.matches(text)) {
        schemaFailure()
    }
}

internal fun requireAccessToken(value: String) {
    requireCanonicalToken(value, prefix = "laa_")
}

internal fun requireRefreshToken(secret: WipeableSecret) {
    requireCanonicalToken(secret.asciiText(), prefix = "lar_")
}

internal fun requireRefreshToken(value: String) {
    requireCanonicalToken(value, prefix = "lar_")
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun constantTimeHexEquals(left: String, right: String): Boolean =
    runCatching {
        MessageDigest.isEqual(left.hexBytes(), right.hexBytes())
    }.getOrDefault(false)

internal object M2NoteWireDocuments {
    fun decodePendingCapture(bytes: ByteArray): M2NoteCaptureWire =
        validateCaptureDocument(
            parseRequestObject(bytes),
            expectedState = "local_pending",
            committed = false,
        )

    fun decodePendingEvent(bytes: ByteArray): M2NoteEventWire =
        validateEventDocument(
            parseRequestObject(bytes),
            expectedState = "local_pending",
            committed = false,
        )

    internal fun validateCapture(
        document: WireJsonObject,
        expectedState: String,
        committed: Boolean,
    ): M2NoteCaptureWire = validateCaptureDocument(document, expectedState, committed)

    internal fun validateEvent(
        document: WireJsonObject,
        expectedState: String,
        committed: Boolean,
    ): M2NoteEventWire = validateEventDocument(document, expectedState, committed)

    internal fun requireConsistent(
        capture: M2NoteCaptureWire,
        event: M2NoteEventWire,
    ) {
        if (
            capture.captureId != event.captureId ||
            capture.operationId != event.operationId ||
            capture.installationId != event.installationId ||
            capture.localOwnerId != event.localOwnerId ||
            capture.deviceId != event.deviceId
        ) {
            schemaFailure()
        }

        val captureSource = capture.document.requireObject("source")
        val eventSource = event.document.requireObject("source")
        if (
            captureSource.requireString("channel") != eventSource.requireString("channel") ||
            captureSource.requireString("recorded_at") != eventSource.requireString("recorded_at") ||
            captureSource.requireObject("collector") != eventSource.requireObject("collector")
        ) {
            schemaFailure()
        }
        val captureOrigin = captureSource.requireObject("origin")
        val eventOrigin = eventSource.requireObject("origin")
        for (name in listOf("provider", "app", "device", "user_entered")) {
            if (captureOrigin.requireValue(name) != eventOrigin.requireValue(name)) {
                schemaFailure()
            }
        }
        if (
            captureOrigin.requireValue("source_record_id") !=
            eventSource.requireValue("source_record_id") ||
            captureOrigin.requireValue("source_record_version") !=
            eventSource.requireValue("source_record_version")
        ) {
            schemaFailure()
        }
        val content = capture.document.requireObject("content")
        if (content.requireObject("payload") != event.document.requireObject("payload")) {
            schemaFailure()
        }
    }

    private fun parseRequestObject(bytes: ByteArray): WireJsonObject =
        try {
            StrictJson.parse(
                bytes,
                StrictJsonLimits.request(M2Endpoint.SYNC_PUSH.requestMaxBytes),
            ) as? WireJsonObject ?: schemaFailure()
        } catch (error: StrictJsonException) {
            throw WireProtocolException(WireProtocolFailure.JSON_TRUST_BOUNDARY, error)
        }
}

internal fun validateCaptureDocument(
    document: WireJsonObject,
    expectedState: String,
    committed: Boolean,
): M2NoteCaptureWire {
    document.requireExactFields(CAPTURE_FIELDS)
    document.requireConstant("schema_version", "4.0.0")
    document.requireConstant("persistence_state", expectedState)
    val captureId = requireCanonicalUuid(document.requireString("capture_id"))
    val operationId = requireCanonicalUuid(document.requireString("operation_id"))
    val identity = document.requireObject("identity")
    identity.requireExactFields(IDENTITY_FIELDS)
    val installationId = requireCanonicalUuid(identity.requireString("installation_id"))
    val localOwnerId = requireCanonicalUuid(identity.requireString("local_owner_id"))
    val deviceId = identity.requireNullableString("device_id")
    if (committed) {
        requireCanonicalUuid(deviceId ?: schemaFailure())
    } else if (deviceId != null) {
        schemaFailure()
    }

    validateCaptureSource(document.requireObject("source"))
    val content = document.requireObject("content")
    content.requireExactFields(CONTENT_FIELDS)
    content.requireConstant("kind", "structured")
    content.requireConstant("record_type", "note")
    validateNotePayload(content.requireObject("payload"))

    val integrity = document.requireObject("integrity")
    integrity.requireExactFields(INTEGRITY_FIELDS)
    val digest = requireSha256(integrity.requireString("sha256"))
    val byteSize = integrity.requireInteger("byte_size", 1L..536_870_912L)
    val canonicalContent = StrictJson.canonicalBytes(content)
    if (
        !constantTimeHexEquals(digest, sha256Hex(canonicalContent)) ||
        byteSize != canonicalContent.size.toLong()
    ) {
        schemaFailure()
    }

    return M2NoteCaptureWire(
        document = document,
        captureId = captureId,
        operationId = operationId,
        installationId = installationId,
        localOwnerId = localOwnerId,
        deviceId = deviceId,
        persistenceState = expectedState,
    )
}

internal fun validateEventDocument(
    document: WireJsonObject,
    expectedState: String,
    committed: Boolean,
): M2NoteEventWire {
    document.requireExactFields(EVENT_FIELDS)
    document.requireConstant("schema_version", "4.0.0")
    document.requireConstant("persistence_state", expectedState)
    val identity = document.requireObject("identity")
    identity.requireExactFields(IDENTITY_FIELDS)
    val installationId = requireCanonicalUuid(identity.requireString("installation_id"))
    val localOwnerId = requireCanonicalUuid(identity.requireString("local_owner_id"))
    val deviceId = identity.requireNullableString("device_id")
    if (committed) {
        requireCanonicalUuid(deviceId ?: schemaFailure())
    } else if (deviceId != null) {
        schemaFailure()
    }
    val eventId = requireCanonicalUuid(document.requireString("event_id"))
    val revisionId = requireCanonicalUuid(document.requireString("revision_id"))
    val revisionNo = document.requireInteger("revision_no", 1L..2_147_483_647L).toInt()
    document.requireConstant("kind", "note")
    document.requireEnum("assertion_status", setOf("observed", "uncertain"))
    document.requireNull("lifecycle")
    val recordStatus = document.requireEnum("record_status", setOf("active", "retracted"))
    document.requireConstant("verification_status", "user_confirmed")

    val source = validateEventSource(document.requireObject("source"))
    validateNoteEventTime(document.requireObject("time"))
    validateNotePayload(document.requireObject("payload"))
    validateEvidence(document, document.requireArray("evidence"))
    validateQualityFlags(document.requireArray("quality_flags"))
    val revision = validateRevision(
        body = document,
        revision = document.requireObject("revision"),
        revisionNo = revisionNo,
        recordStatus = recordStatus,
    )
    val server = document.requireObject("server")
    server.requireExactFields(SERVER_FIELDS)
    val receivedAt: String?
    val serverSequence: Long?
    if (committed) {
        receivedAt = requireCanonicalServerInstant(server.requireString("received_at"))
        serverSequence = server.requireInteger("server_sequence", 1L..JSON_SAFE_INTEGER_MAX)
    } else {
        server.requireNull("received_at")
        server.requireNull("server_sequence")
        receivedAt = null
        serverSequence = null
    }

    return M2NoteEventWire(
        document = document,
        eventId = eventId,
        revisionId = revisionId,
        revisionNo = revisionNo,
        captureId = requireCanonicalUuid(source.requireString("capture_id")),
        operationId = requireCanonicalUuid(source.requireString("operation_id")),
        installationId = installationId,
        localOwnerId = localOwnerId,
        deviceId = deviceId,
        parentRevisionId = revision.parentRevisionId,
        recordStatus = recordStatus,
        persistenceState = expectedState,
        serverSequence = serverSequence,
        receivedAt = receivedAt,
    )
}

private fun validateCaptureSource(source: WireJsonObject) {
    source.requireExactFields(CAPTURE_SOURCE_FIELDS)
    source.requireConstant("channel", "android_manual")
    val recordedAt = requireCanonicalOffsetInstant(source.requireString("recorded_at"))
    val timezoneId = source.requireString("timezone_id").requireCodePointLength(1, 64)
    val offsetMinutes = source.requireInteger("utc_offset_minutes", -840L..840L).toInt()
    val recorded = OffsetDateTime.parse(recordedAt)
    if (recorded.offset.totalSeconds / 60 != offsetMinutes) {
        schemaFailure()
    }
    validateRecordedZone(recorded.toInstant(), recorded.offset, timezoneId)

    val origin = source.requireObject("origin")
    origin.requireExactFields(CAPTURE_ORIGIN_FIELDS)
    origin.requireNullableNonEmptyString("provider")
    origin.requireNullableNonEmptyString("app")
    origin.requireNullableNonEmptyString("device")
    origin.requireNull("source_record_id")
    origin.requireNull("source_record_version")
    if (!origin.requireBoolean("user_entered")) {
        schemaFailure()
    }
    validateCollector(source.requireObject("collector"))
}

private fun validateEventSource(source: WireJsonObject): WireJsonObject {
    source.requireExactFields(EVENT_SOURCE_FIELDS)
    requireCanonicalUuid(source.requireString("capture_id"))
    requireCanonicalUuid(source.requireString("operation_id"))
    source.requireConstant("channel", "android_manual")
    source.requireNull("source_record_id")
    source.requireNull("source_record_version")
    source.requireNull("source_modified_at")
    requireCanonicalOffsetInstant(source.requireString("recorded_at"))
    val origin = source.requireObject("origin")
    origin.requireExactFields(EVENT_ORIGIN_FIELDS)
    origin.requireNullableNonEmptyString("provider")
    origin.requireNullableNonEmptyString("app")
    origin.requireNullableNonEmptyString("device")
    if (!origin.requireBoolean("user_entered")) {
        schemaFailure()
    }
    validateCollector(source.requireObject("collector"))
    return source
}

private fun validateCollector(collector: WireJsonObject) {
    collector.requireExactFields(COLLECTOR_FIELDS)
    collector.requireString("name").requireCodePointLength(minimum = 1)
    collector.requireString("version").requireCodePointLength(minimum = 1)
}

private fun validateNotePayload(payload: WireJsonObject) {
    payload.requireExactFields(NOTE_PAYLOAD_FIELDS)
    payload.requireString("text").requireCodePointLength(minimum = 1, maximum = 50_000)
}

private fun validateEventTime(time: WireJsonObject) {
    time.requireExactFields(EVENT_TIME_FIELDS)
    val start = time.requireNullableString("effective_start_utc")
        ?.also(::requireCanonicalServerInstant)
    val end = time.requireNullableString("effective_end_utc")
        ?.also(::requireCanonicalServerInstant)
    val localStart = time.requireNullableString("original_local_start")
        ?.also(::requireCanonicalLocalDateTime)
    val localEnd = time.requireNullableString("original_local_end")
        ?.also(::requireCanonicalLocalDateTime)
    val timezoneId = time.requireString("timezone_id").requireCodePointLength(minimum = 1)
    val zone = requireIanaZone(timezoneId)
    val startOffset = time.requireNullableInteger("start_offset_seconds", -50_400L..50_400L)
    val endOffset = time.requireNullableInteger("end_offset_seconds", -50_400L..50_400L)
    val precision = time.requireEnum(
        "temporal_precision",
        setOf("exact", "minute", "hour", "part_of_day", "date", "approximate", "unknown"),
    )
    val localDate = time.requireNullableString("local_date")?.also(::requireCanonicalDate)
    time.requireNullableNonEmptyString("source_expression")

    if (start == null) {
        if (startOffset != null) schemaFailure()
    } else {
        if (localStart == null || startOffset == null || localDate == null) schemaFailure()
        requireLocalTimeMatch(localStart, zone, startOffset.toInt(), start)
    }
    if (end == null) {
        if (localEnd != null || endOffset != null) schemaFailure()
    } else {
        if (
            start == null || localStart == null || localEnd == null ||
            startOffset == null || endOffset == null || localDate == null
        ) {
            schemaFailure()
        }
        requireLocalTimeMatch(localEnd, zone, endOffset.toInt(), end)
        if (Instant.parse(end) < Instant.parse(start)) schemaFailure()
    }
    if (localStart != null && LocalDateTime.parse(localStart).toLocalDate().toString() != localDate) {
        schemaFailure()
    }
    when (precision) {
        "exact", "minute", "hour" -> if (
            start == null || localStart == null || startOffset == null || localDate == null
        ) {
            schemaFailure()
        }
        "date", "part_of_day" -> if (
            start != null || end != null || localStart == null || localEnd != null ||
            startOffset != null || endOffset != null || localDate == null
        ) {
            schemaFailure()
        }
        "unknown" -> if (
            start != null || end != null || localStart != null || localEnd != null ||
            startOffset != null || endOffset != null || localDate != null
        ) {
            schemaFailure()
        }
    }
}

private fun validateNoteEventTime(time: WireJsonObject) {
    validateEventTime(time)
    time.requireNull("effective_end_utc")
    time.requireNull("original_local_end")
    time.requireNull("end_offset_seconds")
}

private fun validateEvidence(body: WireJsonObject, evidence: WireJsonArray) {
    if (evidence.elements.isEmpty()) {
        schemaFailure()
    }
    var hasHumanConfirmation = false
    evidence.elements.forEach { raw ->
        val item = raw as? WireJsonObject ?: schemaFailure()
        item.requireExactFields(EVIDENCE_FIELDS)
        item.requireConstant("capture_ref", "#/source/capture_id")
        val fieldPath = item.requireString("field_path")
        if (!FIELD_PATH_PATTERN.matches(fieldPath)) schemaFailure()
        item.requireNullableNonEmptyString("artifact_id")
        val locator = item.requireNullableNonEmptyString("locator")
        val excerpt = item.requireNullableNonEmptyString("excerpt")
        val humanConfirmed = item.requireBoolean("human_confirmed")
        if (locator == null && excerpt == null && !humanConfirmed) schemaFailure()
        hasHumanConfirmation = hasHumanConfirmation || humanConfirmed
        resolveJsonPointer(body, fieldPath)
        val captureId = resolveJsonPointer(body, "/source/capture_id")
        if (captureId != body.requireObject("source").requireValue("capture_id")) {
            schemaFailure()
        }
    }
    if (!hasHumanConfirmation) schemaFailure()
}

private fun validateQualityFlags(flags: WireJsonArray) {
    val seen = if (flags.hasParserVerifiedUniqueItems) null else mutableSetOf<String>()
    flags.elements.forEach { raw ->
        val value = (raw as? WireJsonString)?.value ?: schemaFailure()
        if (!QUALITY_FLAG_PATTERN.matches(value) || (seen != null && !seen.add(value))) {
            schemaFailure()
        }
    }
}

private data class ValidatedRevision(
    val parentRevisionId: String?,
)

private fun validateRevision(
    body: WireJsonObject,
    revision: WireJsonObject,
    revisionNo: Int,
    recordStatus: String,
): ValidatedRevision {
    revision.requireExactFields(REVISION_FIELDS)
    requireCanonicalOffsetInstant(revision.requireString("created_at"))
    val contentSha = requireSha256(revision.requireString("content_sha256"))
    revision.requireConstant("actor", "user")
    val correctionReason = revision.requireNullableNonEmptyString("correction_reason")
    if (recordStatus == "retracted" && correctionReason == null) schemaFailure()
    val parents = revision.requireArray("parents")
    if (parents.elements.size > 1) schemaFailure()
    val parentRevisionId = parents.elements.singleOrNull()?.let { raw ->
        val parent = raw as? WireJsonObject ?: schemaFailure()
        parent.requireExactFields(PARENT_FIELDS)
        parent.requireConstant("relation", "supersedes")
        requireCanonicalUuid(parent.requireString("revision_id"))
    }
    if ((revisionNo == 1) != (parentRevisionId == null)) schemaFailure()

    val source = body.requireObject("source")
    val digestInput = jsonObjectOf(
        "event_id" to body.requireValue("event_id"),
        "revision_id" to body.requireValue("revision_id"),
        "revision_no" to body.requireValue("revision_no"),
        "capture_id" to source.requireValue("capture_id"),
        "operation_id" to source.requireValue("operation_id"),
        "record_status" to body.requireValue("record_status"),
        "effective_time" to body.requireValue("time"),
        "recorded_at" to source.requireValue("recorded_at"),
        "payload" to body.requireValue("payload"),
        "correction_reason" to revision.requireValue("correction_reason"),
        "parent_revision_id" to parentRevisionId.asNullableJson(),
    )
    if (!constantTimeHexEquals(contentSha, StrictJson.canonicalSha256(digestInput))) {
        schemaFailure()
    }
    return ValidatedRevision(parentRevisionId)
}

private fun validateRecordedZone(instant: Instant, recordedOffset: ZoneOffset, timezoneId: String) {
    if (FIXED_OFFSET_PATTERN.matches(timezoneId)) {
        val expected = if (timezoneId == "Z") ZoneOffset.UTC else ZoneOffset.of(timezoneId)
        if (expected != recordedOffset) schemaFailure()
        return
    }
    val zone = requireIanaZone(timezoneId)
    if (zone.rules.getOffset(instant) != recordedOffset) schemaFailure()
}

private fun requireIanaZone(value: String): ZoneId {
    if (value !in INSTALLED_IANA_ZONE_IDS) schemaFailure()
    return try {
        ZoneId.of(value)
    } catch (_: DateTimeException) {
        schemaFailure()
    }
}

private fun requireLocalTimeMatch(
    localText: String,
    zone: ZoneId,
    offsetSeconds: Int,
    instantText: String,
) {
    val local = LocalDateTime.parse(localText)
    val expectedInstant = Instant.parse(instantText)
    val valid = zone.rules.getValidOffsets(local).any { offset ->
        offset.totalSeconds == offsetSeconds && local.toInstant(offset) == expectedInstant
    }
    if (!valid) schemaFailure()
}

private fun resolveJsonPointer(document: WireJsonValue, pointer: String): WireJsonValue {
    if (pointer.isEmpty()) return document
    if (!pointer.startsWith('/')) schemaFailure()
    var current = document
    pointer.substring(1).split('/').forEach { rawToken ->
        val token = rawToken.replace("~1", "/").replace("~0", "~")
        current = when (val value = current) {
            is WireJsonObject -> value.properties[token] ?: schemaFailure()
            is WireJsonArray -> {
                if (token.isEmpty() || (token.length > 1 && token.startsWith('0'))) schemaFailure()
                val position = token.toIntOrNull() ?: schemaFailure()
                value.elements.getOrNull(position) ?: schemaFailure()
            }
            else -> schemaFailure()
        }
    }
    return current
}

internal fun WireJsonObject.requireExactFields(expected: Set<String>) {
    if (properties.keys != expected) schemaFailure()
}

internal fun WireJsonObject.requireValue(name: String): WireJsonValue =
    properties[name] ?: schemaFailure()

internal fun WireJsonObject.requireObject(name: String): WireJsonObject =
    requireValue(name) as? WireJsonObject ?: schemaFailure()

internal fun WireJsonObject.requireArray(name: String): WireJsonArray =
    requireValue(name) as? WireJsonArray ?: schemaFailure()

internal fun WireJsonObject.requireString(name: String): String =
    (requireValue(name) as? WireJsonString)?.value ?: schemaFailure()

internal fun WireJsonObject.requireNullableString(name: String): String? =
    when (val value = requireValue(name)) {
        WireJsonNull -> null
        is WireJsonString -> value.value
        else -> schemaFailure()
    }

internal fun WireJsonObject.requireNullableNonEmptyString(name: String): String? =
    requireNullableString(name)?.requireCodePointLength(minimum = 1)

internal fun WireJsonObject.requireInteger(name: String, range: LongRange): Long =
    ((requireValue(name) as? WireJsonInteger)?.value ?: schemaFailure())
        .takeIf { it in range } ?: schemaFailure()

internal fun WireJsonObject.requireNullableInteger(name: String, range: LongRange): Long? =
    when (val value = requireValue(name)) {
        WireJsonNull -> null
        is WireJsonInteger -> value.value.takeIf { it in range } ?: schemaFailure()
        else -> schemaFailure()
    }

internal fun WireJsonObject.requireBoolean(name: String): Boolean =
    (requireValue(name) as? WireJsonBoolean)?.value ?: schemaFailure()

internal fun WireJsonObject.requireNull(name: String) {
    if (requireValue(name) !== WireJsonNull) schemaFailure()
}

internal fun WireJsonObject.requireConstant(name: String, expected: String) {
    if (requireString(name) != expected) schemaFailure()
}

internal fun WireJsonObject.requireEnum(name: String, allowed: Set<String>): String =
    requireString(name).takeIf { it in allowed } ?: schemaFailure()

internal fun String.requireCodePointLength(
    minimum: Int = 0,
    maximum: Int = Int.MAX_VALUE,
): String {
    val count = codePointCount(0, length)
    if (count !in minimum..maximum) schemaFailure()
    return this
}

internal fun schemaFailure(): Nothing =
    throw WireProtocolException(WireProtocolFailure.SCHEMA_MISMATCH)

private fun WipeableSecret.asciiText(): String = useBytes { bytes ->
    if (bytes.any { (it.toInt() and 0xff) > 0x7f }) schemaFailure()
    val text = bytes.toString(StandardCharsets.US_ASCII)
    text
}

private fun requireCanonicalToken(value: String, prefix: String) {
    if (value.length != 47 || !value.startsWith(prefix)) schemaFailure()
    val encoded = value.substring(prefix.length)
    if (encoded.length != 43 || encoded.any { it !in BASE64URL_CHARACTERS }) schemaFailure()
    val decoded = try {
        Base64.getUrlDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        schemaFailure()
    }
    try {
        if (
            decoded.size != 32 ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != encoded
        ) {
            schemaFailure()
        }
    } finally {
        decoded.fill(0)
    }
}

private fun String.hexBytes(): ByteArray {
    if (length % 2 != 0) throw IllegalArgumentException()
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private val BASE64URL_CHARACTERS =
    ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('-', '_')

internal val CAPTURE_FIELDS = setOf(
    "schema_version", "persistence_state", "capture_id", "operation_id",
    "identity", "source", "content", "integrity",
)
internal val IDENTITY_FIELDS = setOf("installation_id", "local_owner_id", "device_id")
internal val CAPTURE_SOURCE_FIELDS = setOf(
    "channel", "recorded_at", "timezone_id", "utc_offset_minutes", "origin", "collector",
)
internal val CAPTURE_ORIGIN_FIELDS = setOf(
    "provider", "app", "device", "source_record_id", "source_record_version", "user_entered",
)
internal val COLLECTOR_FIELDS = setOf("name", "version")
internal val CONTENT_FIELDS = setOf("kind", "record_type", "payload")
internal val NOTE_PAYLOAD_FIELDS = setOf("text")
internal val INTEGRITY_FIELDS = setOf("sha256", "byte_size")
internal val EVENT_FIELDS = setOf(
    "schema_version", "persistence_state", "identity", "event_id", "revision_id",
    "revision_no", "kind", "assertion_status", "lifecycle", "record_status",
    "verification_status", "source", "time", "payload", "evidence", "quality_flags",
    "revision", "server",
)
internal val EVENT_SOURCE_FIELDS = setOf(
    "capture_id", "operation_id", "channel", "source_record_id", "source_record_version",
    "source_modified_at", "recorded_at", "origin", "collector",
)
internal val EVENT_ORIGIN_FIELDS = setOf("provider", "app", "device", "user_entered")
internal val EVENT_TIME_FIELDS = setOf(
    "effective_start_utc", "effective_end_utc", "original_local_start", "original_local_end",
    "timezone_id", "start_offset_seconds", "end_offset_seconds", "temporal_precision",
    "local_date", "source_expression",
)
internal val EVIDENCE_FIELDS = setOf(
    "capture_ref", "field_path", "artifact_id", "locator", "excerpt", "human_confirmed",
)
internal val REVISION_FIELDS = setOf(
    "created_at", "content_sha256", "actor", "correction_reason", "parents",
)
internal val PARENT_FIELDS = setOf("revision_id", "relation")
internal val SERVER_FIELDS = setOf("received_at", "server_sequence")
internal val API_FIELD_ERROR_FIELDS = setOf("path", "code")

internal fun validateApiFieldPath(value: String): String =
    value.takeIf(API_FIELD_PATH_PATTERN::matches) ?: schemaFailure()
