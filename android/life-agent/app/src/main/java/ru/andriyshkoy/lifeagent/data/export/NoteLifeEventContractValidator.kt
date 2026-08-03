package ru.andriyshkoy.lifeagent.data.export

import java.math.BigInteger
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * On-device validation for the note subset of life-event.schema.json.
 *
 * The export boundary deliberately validates persisted canonical documents
 * again. Room constraints protect relational shape, but they cannot prove that
 * migrated JSON blobs, timestamps, enums, or content digests still satisfy the
 * public life-event contract.
 */
internal class NoteLifeEventContractValidator {
    fun inspect(
        index: Int,
        raw: CanonicalNoteRevisionJson,
        violations: MutableList<String>,
    ): NoteRevisionInspection {
        val path = "revisions[$index]"
        val document = raw.document
        document.requireExactFields(LIFE_EVENT_FIELDS, path, violations)

        document.requireString("schema_version", path, violations)
            .expect("4.0.0", "$path.schema_version", violations)
        val persistenceState = document.requireString(
            "persistence_state",
            path,
            violations,
        )
        persistenceState.expectOneOf(
            PERSISTENCE_STATES,
            "$path.persistence_state",
            violations,
        )

        val identity = validateIdentity(
            document.requireObject("identity", path, violations),
            "$path.identity",
            violations,
        )
        val eventId = document.requireString("event_id", path, violations)
        validateUuid(eventId, "$path.event_id", violations)
        val revisionId = document.requireString("revision_id", path, violations)
        validateUuid(revisionId, "$path.revision_id", violations)
        val revisionNo = document.requirePositiveInteger(
            "revision_no",
            path,
            violations,
        )

        document.requireString("kind", path, violations)
            .expect("note", "$path.kind", violations)
        document.requireString("assertion_status", path, violations)
            .expectOneOf(ASSERTION_STATUSES, "$path.assertion_status", violations)
        document.requireNull("lifecycle", path, violations)
        val recordStatus = document.requireString("record_status", path, violations)
        recordStatus.expectOneOf(RECORD_STATUSES, "$path.record_status", violations)
        val verificationStatus = document.requireString(
            "verification_status",
            path,
            violations,
        )
        verificationStatus.expectOneOf(
            VERIFICATION_STATUSES,
            "$path.verification_status",
            violations,
        )

        val source = validateSource(
            document.requireObject("source", path, violations),
            "$path.source",
            violations,
        )
        validateTime(
            document.requireObject("time", path, violations),
            "$path.time",
            violations,
        )
        validatePayload(
            document.requireObject("payload", path, violations),
            "$path.payload",
            violations,
        )
        validateEvidence(
            root = document,
            evidence = document.requireArray("evidence", path, violations),
            captureId = source.captureId,
            verificationStatus = verificationStatus,
            path = "$path.evidence",
            violations = violations,
        )
        validateQualityFlags(
            document.requireArray("quality_flags", path, violations),
            "$path.quality_flags",
            violations,
        )
        val revision = validateRevision(
            document.requireObject("revision", path, violations),
            revisionNo = revisionNo,
            recordStatus = recordStatus,
            path = "$path.revision",
            violations = violations,
        )
        val server = validateServer(
            document.requireObject("server", path, violations),
            "$path.server",
            violations,
        )

        if (source.channel != "android_manual") {
            violations += "$path.source.channel must be 'android_manual' for a note"
        }
        if (verificationStatus != "user_confirmed") {
            violations += "$path.verification_status must be 'user_confirmed' for a note"
        }
        if (
            source.sourceRecordId != null ||
            source.sourceRecordVersion != null ||
            source.sourceModifiedAt != null
        ) {
            violations += "$path.source manual source-record fields must be null"
        }
        if (source.originUserEntered != true) {
            violations += "$path.source.origin.user_entered must be true for a manual note"
        }

        when (persistenceState) {
            "local_pending" -> {
                if (server.receivedAt != null || server.sequence != null) {
                    violations += "$path.server must be empty for local_pending"
                }
            }

            "server_committed" -> {
                if (identity.deviceId == null) {
                    violations += "$path.identity.device_id is required for server_committed"
                }
                if (server.receivedAt == null || server.sequence == null) {
                    violations += "$path.server commit fields are required for server_committed"
                }
            }
        }

        validateContentHash(
            document = document,
            storedHash = revision.contentSha256,
            parentRevisionIds = revision.parentRevisionIds,
            path = "$path.revision.content_sha256",
            violations = violations,
        )

        return NoteRevisionInspection(
            raw = raw,
            eventId = eventId,
            revisionId = revisionId,
            revisionNo = revisionNo,
            operationId = source.operationId,
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            recordStatus = recordStatus,
            parentRevisionIds = revision.parentRevisionIds,
        )
    }

    private fun validateIdentity(
        identity: CanonicalJsonObject,
        path: String,
        violations: MutableList<String>,
    ): IdentityInspection {
        identity.requireExactFields(IDENTITY_FIELDS, path, violations)
        val installationId = identity.requireString(
            "installation_id",
            path,
            violations,
        )
        val localOwnerId = identity.requireString(
            "local_owner_id",
            path,
            violations,
        )
        validateUuid(installationId, "$path.installation_id", violations)
        validateUuid(localOwnerId, "$path.local_owner_id", violations)
        val deviceId = identity.requireNullableString(
            "device_id",
            path,
            violations,
        )
        if (deviceId != null) {
            validateUuid(deviceId, "$path.device_id", violations)
        }
        return IdentityInspection(
            installationId = installationId,
            localOwnerId = localOwnerId,
            deviceId = deviceId,
        )
    }

    private fun validateSource(
        source: CanonicalJsonObject,
        path: String,
        violations: MutableList<String>,
    ): SourceInspection {
        source.requireExactFields(SOURCE_FIELDS, path, violations)
        val captureId = source.requireString("capture_id", path, violations)
        val operationId = source.requireString("operation_id", path, violations)
        validateUuid(captureId, "$path.capture_id", violations)
        validateUuid(operationId, "$path.operation_id", violations)

        val channel = source.requireString("channel", path, violations)
        channel.expectOneOf(SOURCE_CHANNELS, "$path.channel", violations)
        val sourceRecordId = source.requireNullableString(
            "source_record_id",
            path,
            violations,
            requireNonEmpty = true,
        )
        val sourceRecordVersion = source.requireNullableString(
            "source_record_version",
            path,
            violations,
            requireNonEmpty = true,
        )
        val sourceModifiedAt = source.requireNullableString(
            "source_modified_at",
            path,
            violations,
        )
        sourceModifiedAt?.let {
            parseInstant(it, "$path.source_modified_at", violations)
        }
        val recordedAt = source.requireString("recorded_at", path, violations)
        parseInstant(recordedAt, "$path.recorded_at", violations)

        if (sourceRecordVersion != null && sourceRecordId == null) {
            violations += "$path.source_record_version requires source_record_id"
        }

        val origin = source.requireObject("origin", path, violations)
        origin.requireExactFields(ORIGIN_FIELDS, "$path.origin", violations)
        origin.requireNullableString(
            "provider",
            "$path.origin",
            violations,
            requireNonEmpty = true,
        )
        origin.requireNullableString(
            "app",
            "$path.origin",
            violations,
            requireNonEmpty = true,
        )
        origin.requireNullableString(
            "device",
            "$path.origin",
            violations,
            requireNonEmpty = true,
        )
        val originUserEntered = origin.requireBoolean(
            "user_entered",
            "$path.origin",
            violations,
        )

        val collector = source.requireObject("collector", path, violations)
        collector.requireExactFields(COLLECTOR_FIELDS, "$path.collector", violations)
        collector.requireNonEmptyString("name", "$path.collector", violations)
        collector.requireNonEmptyString("version", "$path.collector", violations)

        return SourceInspection(
            captureId = captureId,
            operationId = operationId,
            channel = channel,
            sourceRecordId = sourceRecordId,
            sourceRecordVersion = sourceRecordVersion,
            sourceModifiedAt = sourceModifiedAt,
            originUserEntered = originUserEntered,
        )
    }

    private fun validateTime(
        time: CanonicalJsonObject,
        path: String,
        violations: MutableList<String>,
    ) {
        time.requireExactFields(TIME_FIELDS, path, violations)
        val effectiveStartText = time.requireNullableString(
            "effective_start_utc",
            path,
            violations,
        )
        val effectiveStart = effectiveStartText?.let {
            parseInstant(it, "$path.effective_start_utc", violations)
        }
        val effectiveEndText = time.requireNullableString(
            "effective_end_utc",
            path,
            violations,
        )
        val effectiveEnd = effectiveEndText?.let {
            parseInstant(it, "$path.effective_end_utc", violations)
        }
        val originalLocalStartText = time.requireNullableString(
            "original_local_start",
            path,
            violations,
            requireNonEmpty = true,
        )
        val originalLocalStart = originalLocalStartText?.let {
            parseLocalDateTime(it, "$path.original_local_start", violations)
        }
        val originalLocalEndText = time.requireNullableString(
            "original_local_end",
            path,
            violations,
            requireNonEmpty = true,
        )
        originalLocalEndText?.let {
            parseLocalDateTime(it, "$path.original_local_end", violations)
        }
        val timezoneText = time.requireNonEmptyString(
            "timezone_id",
            path,
            violations,
        )
        val timezone = parseTimezone(timezoneText, "$path.timezone_id", violations)
        val startOffset = time.requireNullableInteger(
            "start_offset_seconds",
            path,
            violations,
            minimum = MIN_TIMEZONE_OFFSET,
            maximum = MAX_TIMEZONE_OFFSET,
        )?.toInt()
        val endOffset = time.requireNullableInteger(
            "end_offset_seconds",
            path,
            violations,
            minimum = MIN_TIMEZONE_OFFSET,
            maximum = MAX_TIMEZONE_OFFSET,
        )
        val precision = time.requireString("temporal_precision", path, violations)
        precision.expectOneOf(TEMPORAL_PRECISIONS, "$path.temporal_precision", violations)
        val localDateText = time.requireNullableString(
            "local_date",
            path,
            violations,
        )
        val localDate = localDateText?.let {
            parseLocalDate(it, "$path.local_date", violations)
        }
        time.requireNullableString(
            "source_expression",
            path,
            violations,
            requireNonEmpty = true,
        )

        if (effectiveStartText == null) {
            violations += "$path.effective_start_utc is required for an M1 note"
        }
        if (effectiveEndText != null) {
            violations += "$path.effective_end_utc must be null for a point note"
        }
        if (originalLocalStartText == null) {
            violations += "$path.original_local_start is required for an M1 note"
        }
        if (originalLocalEndText != null) {
            violations += "$path.original_local_end must be null for a point note"
        }
        if (startOffset == null) {
            violations += "$path.start_offset_seconds is required for an M1 note"
        }
        if (endOffset != null) {
            violations += "$path.end_offset_seconds must be null for a point note"
        }
        if (localDateText == null) {
            violations += "$path.local_date is required for an M1 note"
        }
        if (precision !in NOTE_POINT_PRECISIONS) {
            violations += "$path.temporal_precision is not valid for an M1 point note"
        }
        if (
            effectiveStart != null &&
            originalLocalStart != null &&
            timezone != null &&
            startOffset != null
        ) {
            val offset = try {
                ZoneOffset.ofTotalSeconds(startOffset)
            } catch (_: DateTimeException) {
                null
            }
            if (
                offset == null ||
                offset !in timezone.rules.getValidOffsets(originalLocalStart) ||
                originalLocalStart.toInstant(offset) != effectiveStart
            ) {
                violations +=
                    "$path UTC/local/timezone/offset values do not identify one instant"
            }
        }
        if (
            originalLocalStart != null &&
            localDate != null &&
            originalLocalStart.toLocalDate() != localDate
        ) {
            violations += "$path.local_date differs from original_local_start"
        }
        if (
            effectiveStart != null &&
            effectiveEnd != null &&
            effectiveEnd < effectiveStart
        ) {
            violations += "$path.effective_end_utc precedes effective_start_utc"
        }
    }

    private fun validatePayload(
        payload: CanonicalJsonObject,
        path: String,
        violations: MutableList<String>,
    ) {
        payload.requireExactFields(PAYLOAD_FIELDS, path, violations)
        val text = payload.requireString("text", path, violations)
        val codePoints = text.codePointCount(0, text.length)
        if (text.isBlank()) {
            violations += "$path.text must contain a visible character"
        }
        if (codePoints > MAX_NOTE_CODE_POINTS) {
            violations += "$path.text exceeds $MAX_NOTE_CODE_POINTS Unicode code points"
        }
    }

    private fun validateEvidence(
        root: CanonicalJsonObject,
        evidence: CanonicalJsonArray,
        captureId: String,
        verificationStatus: String,
        path: String,
        violations: MutableList<String>,
    ) {
        if (evidence.elements.isEmpty()) {
            violations += "$path must contain at least one item"
        }
        var hasHumanConfirmation = false
        evidence.elements.forEachIndexed { index, value ->
            val itemPath = "$path[$index]"
            val item = value as? CanonicalJsonObject
            if (item == null) {
                violations += "$itemPath must be an object"
                return@forEachIndexed
            }
            item.requireExactFields(EVIDENCE_FIELDS, itemPath, violations)
            val captureRef = item.requireString("capture_ref", itemPath, violations)
            captureRef.expect("#/source/capture_id", "$itemPath.capture_ref", violations)
            val fieldPath = item.requireString("field_path", itemPath, violations)
            if (!EVIDENCE_FIELD_PATH.matches(fieldPath)) {
                violations += "$itemPath.field_path is not a valid payload JSON Pointer"
            } else if (resolveJsonPointer(root, fieldPath) == null) {
                violations += "$itemPath.field_path does not resolve"
            }
            item.requireNullableString(
                "artifact_id",
                itemPath,
                violations,
                requireNonEmpty = true,
            )
            val locator = item.requireNullableString(
                "locator",
                itemPath,
                violations,
                requireNonEmpty = true,
            )
            val excerpt = item.requireNullableString(
                "excerpt",
                itemPath,
                violations,
                requireNonEmpty = true,
            )
            val humanConfirmed = item.requireBoolean(
                "human_confirmed",
                itemPath,
                violations,
            )
            if (humanConfirmed == true) {
                hasHumanConfirmation = true
            }
            if (locator == null && excerpt == null && humanConfirmed != true) {
                violations +=
                    "$itemPath requires locator, excerpt, or human confirmation"
            }
            if (captureRef == "#/source/capture_id") {
                val resolvedCapture = resolveJsonPointer(root, captureRef.removePrefix("#"))
                if (
                    resolvedCapture !is CanonicalJsonString ||
                    resolvedCapture.value != captureId
                ) {
                    violations += "$itemPath.capture_ref does not identify source.capture_id"
                }
            }
        }
        if (verificationStatus == "user_confirmed" && !hasHumanConfirmation) {
            violations += "$path requires human confirmation for user_confirmed"
        }
    }

    private fun validateQualityFlags(
        qualityFlags: CanonicalJsonArray,
        path: String,
        violations: MutableList<String>,
    ) {
        val seen = mutableSetOf<String>()
        qualityFlags.elements.forEachIndexed { index, value ->
            val flag = (value as? CanonicalJsonString)?.value
            if (flag == null) {
                violations += "$path[$index] must be a string"
                return@forEachIndexed
            }
            if (!QUALITY_FLAG.matches(flag)) {
                violations += "$path[$index] has an invalid quality-flag format"
            }
            if (!seen.add(flag)) {
                violations += "$path contains a duplicate quality flag"
            }
        }
    }

    private fun validateRevision(
        revision: CanonicalJsonObject,
        revisionNo: BigInteger,
        recordStatus: String,
        path: String,
        violations: MutableList<String>,
    ): RevisionInspection {
        revision.requireExactFields(REVISION_FIELDS, path, violations)
        val createdAt = revision.requireString("created_at", path, violations)
        parseInstant(createdAt, "$path.created_at", violations)
        val contentSha256 = revision.requireString(
            "content_sha256",
            path,
            violations,
        )
        if (!SHA256.matches(contentSha256)) {
            violations += "$path.content_sha256 must be a lowercase SHA-256 digest"
        }
        revision.requireString("actor", path, violations)
            .expectOneOf(REVISION_ACTORS, "$path.actor", violations)
        val correctionReason = revision.requireNullableString(
            "correction_reason",
            path,
            violations,
            requireNonEmpty = true,
        )
        val parents = revision.requireArray("parents", path, violations)
        if (parents.elements.size > MAX_REVISION_PARENTS) {
            violations += "$path.parents contains too many entries"
        }
        val parentRevisionIds = mutableListOf<String>()
        val uniqueParents = mutableSetOf<Pair<String, String>>()
        val relations = mutableListOf<String>()
        parents.elements.forEachIndexed { index, value ->
            val parentPath = "$path.parents[$index]"
            val parent = value as? CanonicalJsonObject
            if (parent == null) {
                violations += "$parentPath must be an object"
                return@forEachIndexed
            }
            parent.requireExactFields(PARENT_FIELDS, parentPath, violations)
            val parentRevisionId = parent.requireString(
                "revision_id",
                parentPath,
                violations,
            )
            validateUuid(parentRevisionId, "$parentPath.revision_id", violations)
            val relation = parent.requireString("relation", parentPath, violations)
            relation.expectOneOf(PARENT_RELATIONS, "$parentPath.relation", violations)
            if (!uniqueParents.add(parentRevisionId to relation)) {
                violations += "$path.parents contains a duplicate parent"
            }
            parentRevisionIds += parentRevisionId
            relations += relation
        }

        when (parents.elements.size) {
            0 -> Unit
            1 -> if (relations.singleOrNull() != "supersedes") {
                violations += "$path.parents single parent must supersede"
            }
            2 -> if (relations.any { it != "resolves" }) {
                violations += "$path.parents merge parents must resolve"
            }
        }
        if (revisionNo == BigInteger.ONE && parents.elements.isNotEmpty()) {
            violations += "$path.parents must be empty for revision_no=1"
        }
        if (revisionNo > BigInteger.ONE && parents.elements.isEmpty()) {
            violations += "$path.parents is required after revision_no=1"
        }
        if (recordStatus == "retracted" && correctionReason == null) {
            violations += "$path.correction_reason is required for a retraction"
        }

        return RevisionInspection(
            contentSha256 = contentSha256,
            parentRevisionIds = parentRevisionIds,
        )
    }

    private fun validateServer(
        server: CanonicalJsonObject,
        path: String,
        violations: MutableList<String>,
    ): ServerInspection {
        server.requireExactFields(SERVER_FIELDS, path, violations)
        val receivedAt = server.requireNullableString(
            "received_at",
            path,
            violations,
        )
        receivedAt?.let { parseInstant(it, "$path.received_at", violations) }
        val sequence = server.requireNullableInteger(
            "server_sequence",
            path,
            violations,
            minimum = BigInteger.ONE,
        )
        return ServerInspection(receivedAt = receivedAt, sequence = sequence)
    }

    private fun validateContentHash(
        document: CanonicalJsonObject,
        storedHash: String,
        parentRevisionIds: List<String>,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!SHA256.matches(storedHash)) {
            return
        }
        val expected = NoteRevisionContentHash.expectedForLinearRevision(document)
        if (expected == null) {
            if (parentRevisionIds.size <= 1) {
                violations += "$path cannot be verified from the revision document"
            }
            return
        }
        if (storedHash != expected) {
            violations += "$path does not match canonical immutable revision content"
        }
    }

    private fun CanonicalJsonObject.requireExactFields(
        expected: Set<String>,
        path: String,
        violations: MutableList<String>,
    ) {
        val actual = properties.keys
        val missing = expected - actual
        val unexpected = actual - expected
        if (missing.isNotEmpty()) {
            violations += "$path is missing fields: ${missing.sorted()}"
        }
        if (unexpected.isNotEmpty()) {
            violations += "$path has unknown fields: ${unexpected.sorted()}"
        }
    }

    private fun CanonicalJsonObject.requireString(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): String {
        val value = properties[name]
        if (value !is CanonicalJsonString) {
            violations += "$path.$name must be a string"
            return INVALID_STRING
        }
        return value.value
    }

    private fun CanonicalJsonObject.requireNonEmptyString(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): String {
        val value = requireString(name, path, violations)
        if (value.isEmpty()) {
            violations += "$path.$name must not be empty"
        }
        return value
    }

    private fun CanonicalJsonObject.requireNullableString(
        name: String,
        path: String,
        violations: MutableList<String>,
        requireNonEmpty: Boolean = false,
    ): String? = when (val value = properties[name]) {
        CanonicalJsonNull -> null
        is CanonicalJsonString -> {
            if (requireNonEmpty && value.value.isEmpty()) {
                violations += "$path.$name must not be empty"
            }
            value.value
        }
        else -> {
            violations += "$path.$name must be a string or null"
            null
        }
    }

    private fun CanonicalJsonObject.requireObject(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): CanonicalJsonObject {
        val value = properties[name]
        if (value !is CanonicalJsonObject) {
            violations += "$path.$name must be an object"
            return EMPTY_OBJECT
        }
        return value
    }

    private fun CanonicalJsonObject.requireArray(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): CanonicalJsonArray {
        val value = properties[name]
        if (value !is CanonicalJsonArray) {
            violations += "$path.$name must be an array"
            return EMPTY_ARRAY
        }
        return value
    }

    private fun CanonicalJsonObject.requireBoolean(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): Boolean? {
        val value = properties[name]
        if (value !is CanonicalJsonBoolean) {
            violations += "$path.$name must be a boolean"
            return null
        }
        return value.value
    }

    private fun CanonicalJsonObject.requireNull(
        name: String,
        path: String,
        violations: MutableList<String>,
    ) {
        if (properties[name] !== CanonicalJsonNull) {
            violations += "$path.$name must be null"
        }
    }

    private fun CanonicalJsonObject.requirePositiveInteger(
        name: String,
        path: String,
        violations: MutableList<String>,
    ): BigInteger {
        val value = properties[name]
        if (value !is CanonicalJsonInteger) {
            violations += "$path.$name must be an integer"
            return BigInteger.ZERO
        }
        if (value.value < BigInteger.ONE) {
            violations += "$path.$name must be at least 1"
        }
        return value.value
    }

    private fun CanonicalJsonObject.requireNullableInteger(
        name: String,
        path: String,
        violations: MutableList<String>,
        minimum: BigInteger? = null,
        maximum: BigInteger? = null,
    ): BigInteger? = when (val value = properties[name]) {
        CanonicalJsonNull -> null
        is CanonicalJsonInteger -> {
            if (minimum != null && value.value < minimum) {
                violations += "$path.$name is below its minimum"
            }
            if (maximum != null && value.value > maximum) {
                violations += "$path.$name exceeds its maximum"
            }
            value.value
        }
        else -> {
            violations += "$path.$name must be an integer or null"
            null
        }
    }

    private fun String.expect(
        expected: String,
        path: String,
        violations: MutableList<String>,
    ) {
        if (this != expected) {
            violations += "$path must equal '$expected'"
        }
    }

    private fun String.expectOneOf(
        expected: Set<String>,
        path: String,
        violations: MutableList<String>,
    ) {
        if (this !in expected) {
            violations += "$path has an unsupported value"
        }
    }

    private fun validateUuid(
        value: String,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!CANONICAL_UUID.matches(value)) {
            violations += "$path must be a lowercase canonical UUID"
        }
    }

    private fun parseInstant(
        value: String,
        path: String,
        violations: MutableList<String>,
    ): Instant? {
        if (!RFC3339_DATE_TIME.matches(value)) {
            violations += "$path must be an RFC 3339 date-time"
            return null
        }
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeException) {
            violations += "$path must be an RFC 3339 date-time"
            null
        }
    }

    private fun parseLocalDateTime(
        value: String,
        path: String,
        violations: MutableList<String>,
    ): LocalDateTime? {
        if (!LOCAL_DATE_TIME.matches(value)) {
            violations += "$path must be an ISO local date-time"
            return null
        }
        return try {
            LocalDateTime.parse(value)
        } catch (_: DateTimeException) {
            violations += "$path must be an ISO local date-time"
            null
        }
    }

    private fun parseLocalDate(
        value: String,
        path: String,
        violations: MutableList<String>,
    ): LocalDate? = try {
        LocalDate.parse(value)
    } catch (_: DateTimeException) {
        violations += "$path must be an ISO calendar date"
        null
    }

    private fun parseTimezone(
        value: String,
        path: String,
        violations: MutableList<String>,
    ): ZoneId? = try {
        ZoneId.of(value)
    } catch (_: DateTimeException) {
        violations += "$path must be an installed IANA timezone"
        null
    }

    private fun resolveJsonPointer(
        root: CanonicalJsonValue,
        pointer: String,
    ): CanonicalJsonValue? {
        if (pointer.isEmpty()) {
            return root
        }
        if (!pointer.startsWith('/')) {
            return null
        }
        var current = root
        for (rawToken in pointer.substring(1).split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is CanonicalJsonObject -> current.properties[token] ?: return null
                is CanonicalJsonArray -> {
                    if (!ARRAY_INDEX.matches(token)) {
                        return null
                    }
                    val index = token.toIntOrNull() ?: return null
                    current.elements.getOrNull(index) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    private data class IdentityInspection(
        val installationId: String,
        val localOwnerId: String,
        val deviceId: String?,
    )

    private data class SourceInspection(
        val captureId: String,
        val operationId: String,
        val channel: String,
        val sourceRecordId: String?,
        val sourceRecordVersion: String?,
        val sourceModifiedAt: String?,
        val originUserEntered: Boolean?,
    )

    private data class RevisionInspection(
        val contentSha256: String,
        val parentRevisionIds: List<String>,
    )

    private data class ServerInspection(
        val receivedAt: String?,
        val sequence: BigInteger?,
    )

    private companion object {
        val MIN_TIMEZONE_OFFSET = BigInteger.valueOf(-50_400)
        val MAX_TIMEZONE_OFFSET = BigInteger.valueOf(50_400)
        const val MAX_NOTE_CODE_POINTS = 50_000
        const val MAX_REVISION_PARENTS = 2
        const val INVALID_STRING = "<invalid>"

        val EMPTY_OBJECT = CanonicalJsonObject(emptyMap())
        val EMPTY_ARRAY = CanonicalJsonArray(emptyList())
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        val SHA256 = Regex("^[a-f0-9]{64}$")
        val RFC3339_DATE_TIME = Regex(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?" +
                "(?:Z|[+-]\\d{2}:\\d{2})$",
        )
        val LOCAL_DATE_TIME = Regex(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?$",
        )
        val QUALITY_FLAG = Regex("^[a-z][a-z0-9_]*$")
        val EVIDENCE_FIELD_PATH = Regex("^/payload(?:/(?:[^~/]|~0|~1)*)*$")
        val ARRAY_INDEX = Regex("0|[1-9][0-9]*")

        val PERSISTENCE_STATES = setOf("local_pending", "server_committed")
        val ASSERTION_STATUSES = setOf("observed", "uncertain")
        val RECORD_STATUSES = setOf("active", "retracted")
        val VERIFICATION_STATUSES = setOf(
            "source_recorded",
            "user_confirmed",
            "machine_inferred",
            "needs_review",
        )
        val SOURCE_CHANNELS = setOf(
            "android_manual",
            "health_connect",
            "connector",
            "system",
        )
        val TEMPORAL_PRECISIONS = setOf(
            "exact",
            "minute",
            "hour",
            "part_of_day",
            "date",
            "approximate",
            "unknown",
        )
        val NOTE_POINT_PRECISIONS = setOf("exact", "minute", "hour")
        val REVISION_ACTORS = setOf("user", "system", "connector")
        val PARENT_RELATIONS = setOf("supersedes", "resolves")

        val LIFE_EVENT_FIELDS = setOf(
            "schema_version",
            "persistence_state",
            "identity",
            "event_id",
            "revision_id",
            "revision_no",
            "kind",
            "assertion_status",
            "lifecycle",
            "record_status",
            "verification_status",
            "source",
            "time",
            "payload",
            "evidence",
            "quality_flags",
            "revision",
            "server",
        )
        val IDENTITY_FIELDS = setOf(
            "installation_id",
            "local_owner_id",
            "device_id",
        )
        val SOURCE_FIELDS = setOf(
            "capture_id",
            "operation_id",
            "channel",
            "source_record_id",
            "source_record_version",
            "source_modified_at",
            "recorded_at",
            "origin",
            "collector",
        )
        val ORIGIN_FIELDS = setOf("provider", "app", "device", "user_entered")
        val COLLECTOR_FIELDS = setOf("name", "version")
        val TIME_FIELDS = setOf(
            "effective_start_utc",
            "effective_end_utc",
            "original_local_start",
            "original_local_end",
            "timezone_id",
            "start_offset_seconds",
            "end_offset_seconds",
            "temporal_precision",
            "local_date",
            "source_expression",
        )
        val PAYLOAD_FIELDS = setOf("text")
        val EVIDENCE_FIELDS = setOf(
            "capture_ref",
            "field_path",
            "artifact_id",
            "locator",
            "excerpt",
            "human_confirmed",
        )
        val REVISION_FIELDS = setOf(
            "created_at",
            "content_sha256",
            "actor",
            "correction_reason",
            "parents",
        )
        val PARENT_FIELDS = setOf("revision_id", "relation")
        val SERVER_FIELDS = setOf("received_at", "server_sequence")
    }
}

internal object NoteRevisionContentHash {
    fun expectedForLinearRevision(
        document: CanonicalJsonObject,
    ): String? {
        val source = document.properties["source"] as? CanonicalJsonObject ?: return null
        val time = document.properties["time"] ?: return null
        val payload = document.properties["payload"] ?: return null
        val revision = document.properties["revision"] as? CanonicalJsonObject ?: return null
        val parents = revision.properties["parents"] as? CanonicalJsonArray ?: return null
        if (parents.elements.size > 1) {
            return null
        }
        val parentRevisionId = when (val parent = parents.elements.singleOrNull()) {
            null -> CanonicalJsonNull
            is CanonicalJsonObject -> {
                parent.properties["revision_id"] as? CanonicalJsonString ?: return null
            }
            else -> return null
        }

        val immutableContent = CanonicalJsonObject(
            mapOf(
                "event_id" to (document.properties["event_id"] ?: return null),
                "revision_id" to (document.properties["revision_id"] ?: return null),
                "revision_no" to (document.properties["revision_no"] ?: return null),
                "capture_id" to (source.properties["capture_id"] ?: return null),
                "operation_id" to (source.properties["operation_id"] ?: return null),
                "record_status" to (document.properties["record_status"] ?: return null),
                "effective_time" to time,
                "recorded_at" to (source.properties["recorded_at"] ?: return null),
                "payload" to payload,
                "correction_reason" to
                    (revision.properties["correction_reason"] ?: return null),
                "parent_revision_id" to parentRevisionId,
            ),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(CanonicalJson.encode(immutableContent))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
