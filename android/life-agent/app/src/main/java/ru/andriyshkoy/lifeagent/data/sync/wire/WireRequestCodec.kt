package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets

internal object WireRequestCodec {
    fun materialize(request: M2WireRequest): MaterializedWireRequest {
        val document = when (request) {
            is EnrollmentClaimRequest -> enrollmentDocument(request)
            is RefreshRequest -> refreshDocument(request)
            is RevokeRequest -> revokeDocument(request)
            is PushBatchRequest -> pushBatchDocument(request)
            is BootstrapRequest -> bootstrapDocument(request)
            is PullRequest -> pullDocument(request)
        }
        val body = StrictJson.canonicalBytes(document)
        return try {
            if (body.size > request.endpoint.requestMaxBytes) {
                throw WireProtocolException(WireProtocolFailure.SCHEMA_MISMATCH)
            }
            try {
                StrictJson.parse(
                    body,
                    StrictJsonLimits.request(request.endpoint.requestMaxBytes),
                )
            } catch (error: StrictJsonException) {
                throw WireProtocolException(WireProtocolFailure.SCHEMA_MISMATCH, error)
            }
            MaterializedWireRequest(
                endpoint = request.endpoint,
                correlationId = request.correlationId,
                idempotencyKey = (request as? PushBatchRequest)?.batchId,
                bodySha256 = sha256Hex(body),
                body = body,
            )
        } catch (error: Throwable) {
            body.fill(0)
            throw error
        }
    }

    fun createPushOperation(
        ordinal: Int,
        clientSequence: Long,
        expectedCurrentRevisionId: String?,
        capture: M2NoteCaptureWire,
        event: M2NoteEventWire,
    ): PushOperationWire {
        if (ordinal !in 0 until M2_MAX_PUSH_OPERATIONS) schemaFailure()
        if (clientSequence !in 1L..JSON_SAFE_INTEGER_MAX) schemaFailure()
        if (
            capture.persistenceState != "local_pending" ||
            event.persistenceState != "local_pending"
        ) {
            schemaFailure()
        }
        M2NoteWireDocuments.requireConsistent(capture, event)
        expectedCurrentRevisionId?.also(::requireCanonicalUuid)

        val unhashed = jsonObjectOf(
            "client_sequence" to clientSequence.asJson(),
            "operation_id" to capture.operationId.asJson(),
            "operation_kind" to "append_event_revision".asJson(),
            "capture_id" to capture.captureId.asJson(),
            "event_id" to event.eventId.asJson(),
            "revision_id" to event.revisionId.asJson(),
            "event_schema_version" to "4.0.0".asJson(),
            "event_kind" to "note".asJson(),
            "expected_current_revision_id" to expectedCurrentRevisionId.asNullableJson(),
            "capture" to capture.document,
            "body" to event.document,
        )
        val operationDigest = StrictJson.canonicalSha256(unhashed)
        val document = WireJsonObject(
            unhashed.properties + mapOf(
                "ordinal" to ordinal.asJson(),
                "operation_content_sha256" to operationDigest.asJson(),
            ),
        )
        return validatePushOperation(document, ordinal)
    }

    fun decodePushBatch(bytes: ByteArray): PushBatchRequest {
        val root = try {
            StrictJson.parse(
                bytes,
                StrictJsonLimits.request(M2Endpoint.SYNC_PUSH.requestMaxBytes),
            ) as? WireJsonObject ?: schemaFailure()
        } catch (error: StrictJsonException) {
            throw WireProtocolException(WireProtocolFailure.JSON_TRUST_BOUNDARY, error)
        }
        root.requireExactFields(PUSH_BATCH_FIELDS)
        root.requireConstant("protocol_version", M2_PROTOCOL_VERSION)
        root.requireConstant("message_type", M2Endpoint.SYNC_PUSH.requestMessageType)
        val batchId = requireCanonicalUuid(root.requireString("batch_id"))
        val deviceId = requireCanonicalUuid(root.requireString("device_id"))
        val batchDigest = requireSha256(root.requireString("batch_content_sha256"))
        val operations = root.requireArray("operations")
        if (operations.elements.size !in 1..M2_MAX_PUSH_OPERATIONS) schemaFailure()
        val calculated = StrictJson.canonicalSha256(root.without("batch_content_sha256"))
        if (!constantTimeHexEquals(batchDigest, calculated)) {
            throw WireProtocolException(WireProtocolFailure.HASH_MISMATCH)
        }
        val validated = operations.elements.mapIndexed { ordinal, raw ->
            validatePushOperation(raw as? WireJsonObject ?: schemaFailure(), ordinal)
        }
        requireSingleBatchNamespace(validated)
        return PushBatchRequest(batchId, deviceId, validated)
    }

    /**
     * Strictly revalidates a durable exact body before it can leave storage.
     * HMAC intentionally omits request identity, so correlation is a separate
     * mandatory trust boundary.
     */
    fun decodeDurableCorrelation(
        endpoint: M2Endpoint,
        bytes: ByteArray,
    ): DurableRequestCorrelation {
        if (!endpoint.durableExactReplay) schemaFailure()
        if (endpoint == M2Endpoint.SYNC_PUSH) {
            val evidence = decodeDurablePushEvidence(bytes)
            return DurableRequestCorrelation(
                correlationId = evidence.batchId,
                deviceId = evidence.deviceId,
                credentialGeneration = null,
            )
        }

        val evidence = when (endpoint) {
            M2Endpoint.AUTH_REVOKE -> {
                val root = parseCanonicalDurableRequest(endpoint, bytes)
                root.requireConstant("protocol_version", M2_PROTOCOL_VERSION)
                root.requireConstant("message_type", endpoint.requestMessageType)
                root.requireExactFields(REVOKE_REQUEST_FIELDS)
                val requestId = requireCanonicalUuid(root.requireString("request_id"))
                val deviceId = requireCanonicalUuid(root.requireString("device_id"))
                val generation = root.requireInteger("generation", 1L..JSON_SAFE_INTEGER_MAX)
                requireRefreshToken(root.requireString("refresh_token"))
                DurableRequestCorrelation(requestId, deviceId, generation)
            }

            M2Endpoint.SYNC_BOOTSTRAP -> {
                val bootstrap = decodeDurableBootstrapEvidence(bytes)
                DurableRequestCorrelation(bootstrap.requestId, bootstrap.deviceId, null)
            }

            M2Endpoint.SYNC_PULL -> {
                val pull = decodeDurablePullEvidence(bytes)
                DurableRequestCorrelation(pull.requestId, pull.deviceId, null)
            }

            else -> schemaFailure()
        }
        return evidence
    }

    fun decodeDurablePushEvidence(bytes: ByteArray): DurablePushEvidence {
        val root = parseCanonicalDurableRequest(M2Endpoint.SYNC_PUSH, bytes)
        val request = decodePushBatch(bytes)
        return DurablePushEvidence(
            batchId = request.batchId,
            deviceId = request.deviceId,
            batchContentSha256 = root.requireString("batch_content_sha256"),
            items = request.operations.map { operation ->
                DurablePushItemEvidence(
                    ordinal = operation.ordinal,
                    clientSequence = operation.clientSequence,
                    operationId = operation.operationId,
                    operationContentSha256 = operation.operationContentSha256,
                )
            },
        )
    }

    fun decodeDurableBootstrapEvidence(bytes: ByteArray): DurableBootstrapEvidence {
        val endpoint = M2Endpoint.SYNC_BOOTSTRAP
        val root = parseCanonicalDurableRequest(endpoint, bytes)
        root.requireExactFields(BOOTSTRAP_REQUEST_FIELDS)
        root.requireConstant("protocol_version", M2_PROTOCOL_VERSION)
        root.requireConstant("message_type", endpoint.requestMessageType)
        return DurableBootstrapEvidence(
            requestId = requireCanonicalUuid(root.requireString("request_id")),
            bootstrapId = requireCanonicalUuid(root.requireString("bootstrap_id")),
            deviceId = requireCanonicalUuid(root.requireString("device_id")),
            pageSize = root.requireInteger(
                "page_size",
                1L..M2_MAX_PAGE_SIZE.toLong(),
            ).toInt(),
            pageCursor = root.requireNullableString("page_cursor")?.also(::requireCursor),
        )
    }

    fun decodeDurablePullEvidence(bytes: ByteArray): DurablePullEvidence {
        val endpoint = M2Endpoint.SYNC_PULL
        val root = parseCanonicalDurableRequest(endpoint, bytes)
        root.requireExactFields(PULL_REQUEST_FIELDS)
        root.requireConstant("protocol_version", M2_PROTOCOL_VERSION)
        root.requireConstant("message_type", endpoint.requestMessageType)
        return DurablePullEvidence(
            requestId = requireCanonicalUuid(root.requireString("request_id")),
            deviceId = requireCanonicalUuid(root.requireString("device_id")),
            cursor = requireCursor(root.requireString("cursor")),
            pageSize = root.requireInteger(
                "page_size",
                1L..M2_MAX_PAGE_SIZE.toLong(),
            ).toInt(),
        )
    }

    private fun parseCanonicalDurableRequest(
        endpoint: M2Endpoint,
        bytes: ByteArray,
    ): WireJsonObject {
        val root = try {
            StrictJson.parse(
                bytes,
                StrictJsonLimits.request(endpoint.requestMaxBytes),
            ) as? WireJsonObject ?: schemaFailure()
        } catch (error: StrictJsonException) {
            throw WireProtocolException(WireProtocolFailure.JSON_TRUST_BOUNDARY, error)
        }
        val canonical = StrictJson.canonicalBytes(root)
        try {
            if (!canonical.contentEquals(bytes)) schemaFailure()
        } finally {
            canonical.fill(0)
        }
        return root
    }

    private fun enrollmentDocument(request: EnrollmentClaimRequest): WireJsonObject {
        requireCanonicalUuid(request.requestId)
        requireEnrollmentCode(request.enrollmentCode)
        requireCanonicalUuid(request.installationId)
        requireCanonicalUuid(request.localOwnerId)
        return request.enrollmentCode.useBytes { code ->
            jsonObjectOf(
                "protocol_version" to M2_PROTOCOL_VERSION.asJson(),
                "message_type" to request.endpoint.requestMessageType.asJson(),
                "request_id" to request.requestId.asJson(),
                "enrollment_code" to code.toString(StandardCharsets.US_ASCII).asJson(),
                "installation_id" to request.installationId.asJson(),
                "local_owner_id" to request.localOwnerId.asJson(),
                "replace_active_device" to request.replaceActiveDevice.asJson(),
            )
        }
    }

    private fun refreshDocument(request: RefreshRequest): WireJsonObject {
        validateRefreshRequest(request, allowSafeMaximum = false)
        return request.refreshToken.useBytes { token ->
            jsonObjectOf(
                "protocol_version" to M2_PROTOCOL_VERSION.asJson(),
                "message_type" to request.endpoint.requestMessageType.asJson(),
                "request_id" to request.requestId.asJson(),
                "device_id" to request.deviceId.asJson(),
                "generation" to request.generation.asJson(),
                "refresh_token" to token.toString(StandardCharsets.US_ASCII).asJson(),
            )
        }
    }

    private fun revokeDocument(request: RevokeRequest): WireJsonObject {
        validateRevokeRequest(request)
        return request.refreshToken.useBytes { token ->
            jsonObjectOf(
                "protocol_version" to M2_PROTOCOL_VERSION.asJson(),
                "message_type" to request.endpoint.requestMessageType.asJson(),
                "request_id" to request.requestId.asJson(),
                "device_id" to request.deviceId.asJson(),
                "generation" to request.generation.asJson(),
                "refresh_token" to token.toString(StandardCharsets.US_ASCII).asJson(),
            )
        }
    }

    private fun pushBatchDocument(request: PushBatchRequest): WireJsonObject {
        requireCanonicalUuid(request.batchId)
        requireCanonicalUuid(request.deviceId)
        if (request.operations.size !in 1..M2_MAX_PUSH_OPERATIONS) schemaFailure()
        val validated = request.operations.mapIndexed { ordinal, operation ->
            validatePushOperation(operation.document, operation.ordinal)
            val reordinaled = WireJsonObject(
                operation.document.properties + ("ordinal" to ordinal.asJson()),
            )
            validatePushOperation(reordinaled, ordinal)
        }
        requireSingleBatchNamespace(validated)
        val operations = validated.map { it.document }
        val unhashed = jsonObjectOf(
            "protocol_version" to M2_PROTOCOL_VERSION.asJson(),
            "message_type" to request.endpoint.requestMessageType.asJson(),
            "batch_id" to request.batchId.asJson(),
            "device_id" to request.deviceId.asJson(),
            "operations" to jsonArrayOf(operations),
        )
        val batchDigest = StrictJson.canonicalSha256(unhashed)
        return WireJsonObject(
            unhashed.properties + ("batch_content_sha256" to batchDigest.asJson()),
        )
    }

    private fun bootstrapDocument(request: BootstrapRequest): WireJsonObject {
        requireCanonicalUuid(request.requestId)
        requireCanonicalUuid(request.bootstrapId)
        requireCanonicalUuid(request.deviceId)
        if (request.pageSize !in 1..M2_MAX_PAGE_SIZE) schemaFailure()
        request.pageCursor?.also(::requireCursor)
        return jsonObjectOf(
            "protocol_version" to M2_PROTOCOL_VERSION.asJson(),
            "message_type" to request.endpoint.requestMessageType.asJson(),
            "request_id" to request.requestId.asJson(),
            "bootstrap_id" to request.bootstrapId.asJson(),
            "device_id" to request.deviceId.asJson(),
            "page_size" to request.pageSize.asJson(),
            "page_cursor" to request.pageCursor.asNullableJson(),
        )
    }

    private fun pullDocument(request: PullRequest): WireJsonObject {
        requireCanonicalUuid(request.requestId)
        requireCanonicalUuid(request.deviceId)
        requireCursor(request.cursor)
        if (request.pageSize !in 1..M2_MAX_PAGE_SIZE) schemaFailure()
        return jsonObjectOf(
            "protocol_version" to M2_PROTOCOL_VERSION.asJson(),
            "message_type" to request.endpoint.requestMessageType.asJson(),
            "request_id" to request.requestId.asJson(),
            "device_id" to request.deviceId.asJson(),
            "cursor" to request.cursor.asJson(),
            "page_size" to request.pageSize.asJson(),
        )
    }

    private fun validatePushOperation(
        document: WireJsonObject,
        physicalOrdinal: Int,
    ): PushOperationWire {
        document.requireExactFields(PUSH_OPERATION_FIELDS)
        val ordinal = document.requireInteger("ordinal", 0L..99L).toInt()
        if (ordinal != physicalOrdinal) schemaFailure()
        val clientSequence = document.requireInteger(
            "client_sequence",
            1L..JSON_SAFE_INTEGER_MAX,
        )
        val operationId = requireCanonicalUuid(document.requireString("operation_id"))
        document.requireConstant("operation_kind", "append_event_revision")
        val captureId = requireCanonicalUuid(document.requireString("capture_id"))
        val eventId = requireCanonicalUuid(document.requireString("event_id"))
        val revisionId = requireCanonicalUuid(document.requireString("revision_id"))
        document.requireConstant("event_schema_version", "4.0.0")
        document.requireConstant("event_kind", "note")
        val expectedCurrentRevisionId = document.requireNullableString(
            "expected_current_revision_id",
        )?.also(::requireCanonicalUuid)
        val operationDigest = requireSha256(document.requireString("operation_content_sha256"))
        val capture = M2NoteWireDocuments.validateCapture(
            document.requireObject("capture"),
            expectedState = "local_pending",
            committed = false,
        )
        val event = M2NoteWireDocuments.validateEvent(
            document.requireObject("body"),
            expectedState = "local_pending",
            committed = false,
        )
        M2NoteWireDocuments.requireConsistent(capture, event)
        if (
            operationId != capture.operationId ||
            operationId != event.operationId ||
            captureId != capture.captureId ||
            captureId != event.captureId ||
            eventId != event.eventId ||
            revisionId != event.revisionId
        ) {
            schemaFailure()
        }
        val calculated = StrictJson.canonicalSha256(
            WireJsonObject(document.properties - setOf("ordinal", "operation_content_sha256")),
        )
        if (!constantTimeHexEquals(operationDigest, calculated)) {
            throw WireProtocolException(WireProtocolFailure.HASH_MISMATCH)
        }
        return PushOperationWire(
            document = document,
            ordinal = ordinal,
            clientSequence = clientSequence,
            operationId = operationId,
            captureId = captureId,
            eventId = eventId,
            revisionId = revisionId,
            expectedCurrentRevisionId = expectedCurrentRevisionId,
            operationContentSha256 = operationDigest,
            capture = capture,
            event = event,
        )
    }

    private fun requireSingleBatchNamespace(operations: List<PushOperationWire>) {
        val namespaces = operations.map { operation ->
            operation.capture.installationId to operation.capture.localOwnerId
        }.toSet()
        if (namespaces.size != 1) schemaFailure()
    }
}

internal fun validateRefreshRequest(request: RefreshRequest, allowSafeMaximum: Boolean) {
    requireCanonicalUuid(request.requestId)
    requireCanonicalUuid(request.deviceId)
    val upper = if (allowSafeMaximum) JSON_SAFE_INTEGER_MAX else JSON_SAFE_INTEGER_MAX - 1L
    if (request.generation !in 1L..upper) schemaFailure()
    requireRefreshToken(request.refreshToken)
}

internal fun validateRevokeRequest(request: RevokeRequest) {
    requireCanonicalUuid(request.requestId)
    requireCanonicalUuid(request.deviceId)
    if (request.generation !in 1L..JSON_SAFE_INTEGER_MAX) schemaFailure()
    requireRefreshToken(request.refreshToken)
}

internal val PUSH_BATCH_FIELDS = setOf(
    "protocol_version", "message_type", "batch_id", "device_id",
    "batch_content_sha256", "operations",
)
internal val REVOKE_REQUEST_FIELDS = setOf(
    "protocol_version", "message_type", "request_id", "device_id", "generation",
    "refresh_token",
)
internal val BOOTSTRAP_REQUEST_FIELDS = setOf(
    "protocol_version", "message_type", "request_id", "bootstrap_id", "device_id",
    "page_size", "page_cursor",
)
internal val PULL_REQUEST_FIELDS = setOf(
    "protocol_version", "message_type", "request_id", "device_id", "cursor", "page_size",
)

internal data class DurableRequestCorrelation(
    val correlationId: String,
    val deviceId: String,
    val credentialGeneration: Long?,
) {
    override fun toString(): String = "DurableRequestCorrelation(redacted=true)"
}

internal data class DurablePushEvidence(
    val batchId: String,
    val deviceId: String,
    val batchContentSha256: String,
    val items: List<DurablePushItemEvidence>,
) {
    override fun toString(): String = "DurablePushEvidence(itemCount=${items.size},redacted=true)"
}

internal data class DurablePushItemEvidence(
    val ordinal: Int,
    val clientSequence: Long,
    val operationId: String,
    val operationContentSha256: String,
) {
    override fun toString(): String = "DurablePushItemEvidence(ordinal=$ordinal,redacted=true)"
}

internal data class DurableBootstrapEvidence(
    val requestId: String,
    val bootstrapId: String,
    val deviceId: String,
    val pageSize: Int,
    val pageCursor: String?,
) {
    override fun toString(): String = "DurableBootstrapEvidence(redacted=true)"
}

internal data class DurablePullEvidence(
    val requestId: String,
    val deviceId: String,
    val cursor: String,
    val pageSize: Int,
) {
    override fun toString(): String = "DurablePullEvidence(redacted=true)"
}
internal val PUSH_OPERATION_FIELDS = setOf(
    "ordinal", "client_sequence", "operation_id", "operation_kind", "capture_id",
    "event_id", "revision_id", "event_schema_version", "event_kind",
    "expected_current_revision_id", "operation_content_sha256", "capture", "body",
)
