package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

internal object WireResponseCodec {
    /**
     * Strictly decodes one fresh bootstrap or pull page without synthesizing
     * cross-page state. Room reducers remain authoritative for cursor history,
     * replay and replica topology.
     */
    fun decodeFreshReplicaPage(
        httpStatus: Int,
        body: ByteArray,
        expectation: FreshReplicaPageExpectation,
    ): FreshReplicaPage {
        if (httpStatus != M2_SUCCESS_STATUS) {
            throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        }
        val endpoint = expectation.request.endpoint
        val document = parseResponseObject(
            body = body,
            byteLimit = endpoint.successMaxBytes,
            shape = WireResponseShape(endpoint, apiError = false),
        )
        requireSuccessDiscriminators(document, endpoint)
        val responseBodySha256 = sha256Hex(body)
        return when (expectation) {
            is FreshBootstrapPageExpectation -> decodeBootstrapPageLocal(
                document = document,
                expectation = expectation,
                responseBodySha256 = responseBodySha256,
            )

            is FreshPullPageExpectation -> decodePullPageLocal(
                document = document,
                expectation = expectation,
                responseBodySha256 = responseBodySha256,
            )
        }
    }

    /** Strict, state-free non-success decoder for durable bootstrap/pull requests. */
    fun decodeDurableReplicaApiError(
        httpStatus: Int,
        body: ByteArray,
        expectation: DurableReplicaApiErrorExpectation,
    ): WireApiError {
        val request = expectation.request
        val endpoint = request.endpoint
        if (
            httpStatus == M2_SUCCESS_STATUS ||
            endpoint.errorPolicies.none { it.status == httpStatus }
        ) {
            throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        }
        val document = parseResponseObject(
            body = body,
            byteLimit = M2_API_ERROR_MAX_BYTES,
            shape = WireResponseShape(endpoint, apiError = true),
        )
        return decodeApiError(
            document = document,
            transportStatus = httpStatus,
            endpoint = endpoint,
            expectedCorrelation = request.correlationId,
        )
    }

    /** Strict response decoder for canonical authenticated durable revoke evidence. */
    fun decodeDurableRevokeResponse(
        httpStatus: Int,
        body: ByteArray,
        evidence: DurableRevokeEvidence,
    ): DecodedWireResponse {
        requireCanonicalUuid(evidence.requestId)
        requireCanonicalUuid(evidence.deviceId)
        if (evidence.generation !in 1L..JSON_SAFE_INTEGER_MAX) schemaFailure()
        val endpoint = M2Endpoint.AUTH_REVOKE
        if (httpStatus == M2_SUCCESS_STATUS) {
            val document = parseResponseObject(
                body = body,
                byteLimit = endpoint.successMaxBytes,
                shape = WireResponseShape(endpoint, apiError = false),
            )
            requireSuccessDiscriminators(document, endpoint)
            return decodeRevokeSuccess(document, evidence)
        }
        if (endpoint.errorPolicies.none { it.status == httpStatus }) {
            throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        }
        val document = parseResponseObject(
            body = body,
            byteLimit = M2_API_ERROR_MAX_BYTES,
            shape = WireResponseShape(endpoint, apiError = true),
        )
        return DecodedApiError(
            decodeApiError(
                document = document,
                transportStatus = httpStatus,
                endpoint = endpoint,
                expectedCorrelation = evidence.requestId,
            ),
        )
    }

    fun decode(
        httpStatus: Int,
        body: ByteArray,
        expectation: ResponseExpectation,
    ): DecodedWireResponse {
        val endpoint = expectation.request.endpoint
        if (httpStatus == M2_SUCCESS_STATUS) {
            val document = parseResponseObject(
                body = body,
                byteLimit = endpoint.successMaxBytes,
                shape = WireResponseShape(endpoint, apiError = false),
            )
            val responseBodySha256 = sha256Hex(body)
            requireSuccessDiscriminators(document, endpoint)
            return when (expectation) {
                is EnrollmentResponseExpectation -> decodeEnrollment(document, expectation)
                is RefreshResponseExpectation -> decodeRefresh(document, expectation)
                is RevokeResponseExpectation -> decodeRevoke(document, expectation)
                is PushResponseExpectation -> decodePush(document, expectation)
                is BootstrapResponseExpectation -> decodeBootstrap(
                    document,
                    expectation,
                    responseBodySha256,
                )
                is PullResponseExpectation -> decodePull(
                    document,
                    expectation,
                    responseBodySha256,
                )
            }
        }

        if (endpoint.errorPolicies.none { it.status == httpStatus }) {
            throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        }
        val document = parseResponseObject(
            body = body,
            byteLimit = M2_API_ERROR_MAX_BYTES,
            shape = WireResponseShape(endpoint, apiError = true),
        )
        return DecodedApiError(
            decodeApiError(
                document = document,
                transportStatus = httpStatus,
                endpoint = endpoint,
                expectedCorrelation = expectation.request.correlationId,
            ),
        )
    }

    private fun decodeApiError(
        document: WireJsonObject,
        transportStatus: Int,
        endpoint: M2Endpoint,
        expectedCorrelation: String,
    ): WireApiError {
        document.requireExactFields(API_ERROR_FIELDS)
        document.requireConstant("protocol_version", M2_PROTOCOL_VERSION)
        document.requireConstant("message_type", "api_error")
        val requestId = document.requireNullableString("request_id")
            ?.also(::requireCanonicalUuid)
        val errorCode = ApiErrorCode.fromWire(document.requireString("error_code"))
            ?: schemaFailure()
        val correlationMismatch = when {
            errorCode in ALWAYS_PRE_IDENTITY_ERROR_CODES -> requestId != null
            allowsDualStageError(endpoint, errorCode) ->
                requestId != null && requestId != expectedCorrelation
            else -> requestId != expectedCorrelation
        }
        if (correlationMismatch) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val bodyStatus = document.requireInteger("http_status", 1L..999L).toInt()
        if (bodyStatus != transportStatus) {
            throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        }
        val retryable = document.requireBoolean("retryable")
        val policy = endpoint.policyFor(bodyStatus, errorCode)
            ?: throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        if (retryable != policy.retryable) {
            throw WireProtocolException(WireProtocolFailure.STATUS_ERROR_MISMATCH)
        }
        val fieldErrors = decodeFieldErrors(
            document.requireArray("field_errors"),
            operationResponse = false,
        )
        if (errorCode != ApiErrorCode.REQUEST_SCHEMA_INVALID && fieldErrors.isNotEmpty()) {
            schemaFailure()
        }
        if (
            endpoint != M2Endpoint.SYNC_PUSH &&
            fieldErrors.any { it.path.startsWith("/operations/") }
        ) {
            schemaFailure()
        }
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        return WireApiError(
            requestId = requestId,
            errorCode = errorCode,
            httpStatus = bodyStatus,
            retryable = retryable,
            fieldErrors = fieldErrors,
            serverTime = serverTime,
        )
    }

    private fun decodeEnrollment(
        document: WireJsonObject,
        expectation: EnrollmentResponseExpectation,
    ): EnrollmentClaimSuccess {
        document.requireExactFields(ENROLLMENT_RESPONSE_FIELDS)
        val request = expectation.request
        requireCorrelation(document.requireString("request_id"), request.requestId)
        val installationId = requireCanonicalUuid(document.requireString("installation_id"))
        val localOwnerId = requireCanonicalUuid(document.requireString("local_owner_id"))
        if (installationId != request.installationId || localOwnerId != request.localOwnerId) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val deviceId = requireCanonicalUuid(document.requireString("device_id"))
        expectation.forbiddenExistingDeviceIds.forEach(::requireCanonicalUuid)
        if (
            expectation.expectedStableDeviceId != null &&
            deviceId != expectation.expectedStableDeviceId
        ) {
            throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)
        }
        if (deviceId in expectation.forbiddenExistingDeviceIds) authFailure()
        val personId = requireCanonicalUuid(document.requireString("person_id"))
        if (
            expectation.expectedStablePersonId != null &&
            personId != expectation.expectedStablePersonId
        ) {
            throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)
        }
        val bootstrapRequired = document.requireBoolean("bootstrap_required")
        if (!bootstrapRequired) {
            throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)
        }
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        val credentials = decodeTokenPair(
            document = document.requireObject("credentials"),
            serverTime = serverTime,
            expectedGeneration = 1L,
            expectedFamilyExpiresAt = null,
            maximumFamilyLifetime = Duration.ofDays(90),
            forbiddenTokenDigests = emptySet(),
        )
        return EnrollmentClaimSuccess(
            requestId = request.requestId,
            installationId = installationId,
            localOwnerId = localOwnerId,
            deviceId = deviceId,
            personId = personId,
            credentials = credentials,
            bootstrapRequired = bootstrapRequired,
            serverTime = serverTime,
        )
    }

    private fun decodeRefresh(
        document: WireJsonObject,
        expectation: RefreshResponseExpectation,
    ): RefreshSuccess {
        document.requireExactFields(REFRESH_RESPONSE_FIELDS)
        val request = expectation.request
        validateRefreshRequest(request, allowSafeMaximum = false)
        requireCorrelation(document.requireString("request_id"), request.requestId)
        val deviceId = requireCanonicalUuid(document.requireString("device_id"))
        if (deviceId != request.deviceId) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val expectedGeneration = try {
            Math.addExact(request.generation, 1L)
        } catch (_: ArithmeticException) {
            throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)
        }
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        val predecessorDigest = request.refreshToken.useBytes(::sha256Hex)
        val forbiddenDigests = expectation.previouslyIssuedTokenSha256 + predecessorDigest
        forbiddenDigests.forEach(::requireSha256)
        val credentials = decodeTokenPair(
            document = document.requireObject("credentials"),
            serverTime = serverTime,
            expectedGeneration = expectedGeneration,
            expectedFamilyExpiresAt = expectation.expectedFamilyExpiresAt,
            maximumFamilyLifetime = null,
            forbiddenTokenDigests = forbiddenDigests,
        )
        return RefreshSuccess(
            requestId = request.requestId,
            deviceId = deviceId,
            credentials = credentials,
            serverTime = serverTime,
        )
    }

    private fun decodeRevoke(
        document: WireJsonObject,
        expectation: RevokeResponseExpectation,
    ): RevokeSuccess {
        val request = expectation.request
        validateRevokeRequest(request)
        return decodeRevokeSuccess(
            document = document,
            evidence = DurableRevokeEvidence(
                requestId = request.requestId,
                deviceId = request.deviceId,
                generation = request.generation,
            ),
        )
    }

    private fun decodeRevokeSuccess(
        document: WireJsonObject,
        evidence: DurableRevokeEvidence,
    ): RevokeSuccess {
        document.requireExactFields(REVOKE_RESPONSE_FIELDS)
        requireCorrelation(document.requireString("request_id"), evidence.requestId)
        val deviceId = requireCanonicalUuid(document.requireString("device_id"))
        val generation = document.requireInteger("generation", 1L..JSON_SAFE_INTEGER_MAX)
        if (deviceId != evidence.deviceId || generation != evidence.generation) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        document.requireConstant("status", "revoked")
        val revokedAt = requireCanonicalServerInstant(document.requireString("revoked_at"))
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        if (Instant.parse(revokedAt) > Instant.parse(serverTime)) {
            throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)
        }
        return RevokeSuccess(
            requestId = evidence.requestId,
            deviceId = deviceId,
            generation = generation,
            revokedAt = revokedAt,
            serverTime = serverTime,
        )
    }

    private fun decodePush(
        document: WireJsonObject,
        expectation: PushResponseExpectation,
    ): PushBatchSuccess {
        document.requireExactFields(PUSH_RESPONSE_FIELDS)
        val request = expectation.request
        requireCorrelation(document.requireString("batch_id"), request.batchId)
        val deviceId = requireCanonicalUuid(document.requireString("device_id"))
        if (deviceId != request.deviceId) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        val rawResults = document.requireArray("results")
        if (
            rawResults.elements.size !in 1..M2_MAX_PUSH_OPERATIONS ||
            rawResults.elements.size != request.operations.size
        ) {
            throw WireProtocolException(WireProtocolFailure.ORDER_MISMATCH)
        }
        val results = rawResults.elements.mapIndexed { ordinal, raw ->
            val result = raw as? WireJsonObject ?: schemaFailure()
            decodePushResult(
                result,
                ordinal,
                request.operations[ordinal],
                serverTime,
            )
        }
        if (results.toSet().size != results.size) schemaFailure()
        val ownershipErrorCount = results.count {
            (it as? PushOperationError)?.errorCode ==
                PushOperationErrorCode.OWNERSHIP_VIOLATION
        }
        if (ownershipErrorCount != 0 && ownershipErrorCount != results.size) orderFailure()
        validateIntraBatchRegistryOutcomes(request.operations, results)
        validateIntraBatchEventHeadOutcomes(request.operations, results)
        val acknowledgements = results.filterIsInstance<PushOperationAck>()
        val ackSequences = acknowledgements.map { it.serverSequence }
        if (ackSequences.size != ackSequences.toSet().size) orderFailure()
        val freshSequences = acknowledgements.filterNot { it.replayed }.map { it.serverSequence }
        if (freshSequences.zipWithNext().any { (previous, current) -> current <= previous }) {
            orderFailure()
        }
        val maximumReplayedSequence = acknowledgements.filter { it.replayed }
            .maxOfOrNull { it.serverSequence }
        if (
            maximumReplayedSequence != null &&
            freshSequences.any { it <= maximumReplayedSequence }
        ) {
            orderFailure()
        }
        return PushBatchSuccess(
            batchId = request.batchId,
            deviceId = deviceId,
            results = results,
            serverHighWatermark = requireCursor(document.requireString("server_high_watermark")),
            serverTime = serverTime,
        )
    }

    private fun decodePushResult(
        document: WireJsonObject,
        physicalOrdinal: Int,
        operation: PushOperationWire,
        serverTime: String,
    ): PushOperationResult {
        val status = document.requireString("status")
        return when (status) {
            "ack" -> decodePushAck(document, physicalOrdinal, operation, serverTime)
            "error" -> decodePushError(document, physicalOrdinal, operation)
            else -> schemaFailure()
        }
    }

    private fun validateIntraBatchRegistryOutcomes(
        operations: List<PushOperationWire>,
        results: List<PushOperationResult>,
    ) {
        val operationIds = mutableSetOf<String>()
        val replayedOperationDigests = mutableMapOf<String, String>()
        val clientSequences = mutableMapOf<Pair<String, Long>, RegistryClaim>()
        val captureIds = mutableMapOf<String, RegistryClaim>()
        val revisionIds = mutableMapOf<String, RegistryClaim>()
        val firstOperationOrdinal = buildMap {
            operations.forEachIndexed { ordinal, operation ->
                putIfAbsent(operation.operationId, ordinal)
            }
        }

        // A replayed ACK proves immutable claims existed before this batch, regardless of
        // where that replay appears physically. Its own operation remains exempt from its
        // claims, while a different operation must collide at the earliest occupied gate.
        results.forEachIndexed { ordinal, result ->
            val ack = result as? PushOperationAck ?: return@forEachIndexed
            if (!ack.replayed) return@forEachIndexed
            val operation = operations[ordinal]
            if (firstOperationOrdinal[operation.operationId] != ordinal) orderFailure()
            val priorDigest = replayedOperationDigests.putIfAbsent(
                operation.operationId,
                operation.operationContentSha256,
            )
            if (priorDigest != null && priorDigest != operation.operationContentSha256) {
                orderFailure()
            }
            bindRegistryClaim(
                clientSequences,
                operation.capture.installationId to operation.clientSequence,
                operation.operationId,
            )
            bindRegistryClaim(captureIds, operation.captureId, operation.operationId)
            bindRegistryClaim(revisionIds, operation.revisionId, operation.operationId)
        }

        operations.forEachIndexed { ordinal, operation ->
            val clientSequence = operation.capture.installationId to operation.clientSequence
            val result = results[ordinal]
            val resultError = (result as? PushOperationError)?.errorCode

            // Ownership is checked before any registry. The uniform batch outcome check
            // above prevents this unknown binding from being mixed with registry evidence.
            if (resultError == PushOperationErrorCode.OWNERSHIP_VIOLATION) {
                return@forEachIndexed
            }

            // Every non-ownership item reaches the operation registry, even when a later
            // registry collides. A repeated in-batch operation id therefore wins first.
            val expectedError = when {
                !operationIds.add(operation.operationId) ->
                    PushOperationErrorCode.OPERATION_ID_COLLISION
                replayedOperationDigests[operation.operationId]?.let {
                    it != operation.operationContentSha256
                } == true -> PushOperationErrorCode.OPERATION_ID_COLLISION
                clientSequences[clientSequence].isKnownOtherThan(operation.operationId) ->
                    PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION
                captureIds[operation.captureId].isKnownOtherThan(operation.operationId) ->
                    PushOperationErrorCode.CAPTURE_ID_COLLISION
                revisionIds[operation.revisionId].isKnownOtherThan(operation.operationId) ->
                    PushOperationErrorCode.REVISION_ID_COLLISION
                else -> null
            }

            if (resultError in PREDEPENDENCY_REGISTRY_COLLISION_CODES) {
                val actualIndex = PREDEPENDENCY_REGISTRY_COLLISION_CODES.indexOf(resultError)
                val expectedIndex = expectedError?.let(
                    PREDEPENDENCY_REGISTRY_COLLISION_CODES::indexOf,
                ) ?: -1
                if (expectedIndex >= 0 && actualIndex > expectedIndex) orderFailure()
                val unknownBarrierIndex = listOf(
                    null,
                    clientSequences[clientSequence],
                    captureIds[operation.captureId],
                    revisionIds[operation.revisionId],
                ).indexOfFirst { it == RegistryClaim.OccupiedUnknown }
                if (unknownBarrierIndex >= 0 && actualIndex > unknownBarrierIndex) {
                    orderFailure()
                }
                when (resultError) {
                    PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION -> {
                        markRegistryCollision(
                            clientSequences,
                            clientSequence,
                            operation.operationId,
                        )
                    }
                    PushOperationErrorCode.CAPTURE_ID_COLLISION -> {
                        markRegistryCollision(
                            captureIds,
                            operation.captureId,
                            operation.operationId,
                        )
                    }
                    PushOperationErrorCode.REVISION_ID_COLLISION -> {
                        markRegistryCollision(
                            revisionIds,
                            operation.revisionId,
                            operation.operationId,
                        )
                    }
                    PushOperationErrorCode.OPERATION_ID_COLLISION -> Unit
                    else -> orderFailure()
                }
                return@forEachIndexed
            }

            // A post-registry result is impossible once an earlier local claim proves a
            // collision at one of those gates.
            if (expectedError != null) orderFailure()

            // ACK and post-registry errors prove all four predependency claims.
            val resolvedUnknownClient = bindRegistryClaim(
                clientSequences,
                clientSequence,
                operation.operationId,
            )
            val resolvedUnknownCapture = bindRegistryClaim(
                captureIds,
                operation.captureId,
                operation.operationId,
            )
            val resolvedUnknownRevision = bindRegistryClaim(
                revisionIds,
                operation.revisionId,
                operation.operationId,
            )
            if (
                result is PushOperationAck &&
                !result.replayed &&
                (resolvedUnknownClient || resolvedUnknownCapture || resolvedUnknownRevision) &&
                !operation.hasValidChildLineage()
            ) {
                orderFailure()
            }
        }
    }

    private fun <K> bindRegistryClaim(
        claims: MutableMap<K, RegistryClaim>,
        key: K,
        operationId: String,
    ): Boolean = when (val prior = claims[key]) {
        null -> {
            claims[key] = RegistryClaim.Known(operationId)
            false
        }
        RegistryClaim.OccupiedUnknown -> {
            claims[key] = RegistryClaim.Known(operationId)
            true
        }
        is RegistryClaim.Known -> {
            if (prior.operationId != operationId) orderFailure()
            false
        }
    }

    private fun <K> markRegistryCollision(
        claims: MutableMap<K, RegistryClaim>,
        key: K,
        collidingOperationId: String,
    ) {
        when (val prior = claims[key]) {
            null -> claims[key] = RegistryClaim.OccupiedUnknown
            RegistryClaim.OccupiedUnknown -> Unit
            is RegistryClaim.Known -> if (prior.operationId == collidingOperationId) {
                orderFailure()
            }
        }
    }

    private fun RegistryClaim?.isKnownOtherThan(operationId: String): Boolean =
        this is RegistryClaim.Known && this.operationId != operationId

    private fun PushOperationWire.hasValidChildLineage(): Boolean {
        val parent = event.parentRevisionId ?: return false
        return expectedCurrentRevisionId == parent &&
            parent != revisionId &&
            event.revisionNo > 1
    }

    private sealed interface RegistryClaim {
        data object OccupiedUnknown : RegistryClaim
        data class Known(val operationId: String) : RegistryClaim
    }

    private fun validateIntraBatchEventHeadOutcomes(
        operations: List<PushOperationWire>,
        results: List<PushOperationResult>,
    ) {
        val knownEvents = mutableSetOf<String>()
        val revisionEvents = mutableMapOf<String, String>()
        val revisions = mutableMapOf<String, RevisionStreamFact>()
        val heads = mutableMapOf<String, String>()
        val replayedRootEvents = mutableSetOf<String>()
        val lineageShapes = operations.map { operation ->
            val event = operation.event
            val parent = event.parentRevisionId
            val expected = operation.expectedCurrentRevisionId
            when {
                parent == null && expected == null && event.revisionNo == 1 ->
                    PushLineageShape.ROOT
                parent != null &&
                    expected == parent &&
                    parent != operation.revisionId &&
                    event.revisionNo > 1 -> PushLineageShape.CHILD
                else -> PushLineageShape.INVALID
            }
        }
        val nonCommittedClaimedRevisionIds = results.mapIndexedNotNull { ordinal, result ->
            val errorCode = (result as? PushOperationError)?.errorCode
            if (errorCode in NONCOMMIT_POST_REGISTRY_ERROR_CODES) {
                operations[ordinal].revisionId
            } else {
                null
            }
        }.toSet()
        val replayProvenRevisionIds = buildSet {
            results.forEachIndexed { ordinal, result ->
                val ack = result as? PushOperationAck ?: return@forEachIndexed
                if (!ack.replayed) return@forEachIndexed
                val operation = operations[ordinal]
                add(operation.revisionId)
                add(ack.currentRevisionId)
                if (lineageShapes[ordinal] == PushLineageShape.CHILD) {
                    add(operation.event.parentRevisionId ?: orderFailure())
                }
            }
        }
        val freshSubmittedRevisionOrdinal = buildMap {
            results.forEachIndexed { ordinal, result ->
                if (result is PushOperationAck && !result.replayed) {
                    put(operations[ordinal].revisionId, ordinal)
                }
            }
        }
        if (freshSubmittedRevisionOrdinal.keys.any(replayProvenRevisionIds::contains)) {
            orderFailure()
        }

        // Replayed ACK facts predate this batch. A fresh ACK dependency also predates the
        // batch unless an earlier fresh ACK in this batch submitted that exact revision.
        // Frozen replay receipts do not prove the current pre-batch event head.
        results.forEachIndexed { ordinal, result ->
            val ack = result as? PushOperationAck ?: return@forEachIndexed
            val operation = operations[ordinal]
            val parentRevisionId = operation.event.parentRevisionId
            val parentProviderOrdinal = parentRevisionId?.let(freshSubmittedRevisionOrdinal::get)
            val conflictHeadProviderOrdinal = if (ack.resultCode == PushResultCode.CONFLICT) {
                freshSubmittedRevisionOrdinal[ack.currentRevisionId]
            } else {
                null
            }
            if (
                operation.revisionId in nonCommittedClaimedRevisionIds ||
                (parentRevisionId != null &&
                    parentRevisionId in nonCommittedClaimedRevisionIds) ||
                ack.currentRevisionId in nonCommittedClaimedRevisionIds
            ) {
                orderFailure()
            }
            if (
                lineageShapes[ordinal] == PushLineageShape.INVALID ||
                (lineageShapes[ordinal] == PushLineageShape.ROOT &&
                    ack.resultCode != PushResultCode.APPLIED) ||
                (lineageShapes[ordinal] == PushLineageShape.CHILD &&
                    ack.resultCode == PushResultCode.CONFLICT &&
                    ack.currentRevisionId == operation.expectedCurrentRevisionId) ||
                (parentProviderOrdinal != null && parentProviderOrdinal >= ordinal) ||
                (conflictHeadProviderOrdinal != null && conflictHeadProviderOrdinal >= ordinal)
            ) {
                orderFailure()
            }
            if (!ack.replayed) {
                if (
                    lineageShapes[ordinal] == PushLineageShape.CHILD &&
                    parentProviderOrdinal == null
                ) {
                    val parent = parentRevisionId ?: orderFailure()
                    knownEvents += operation.eventId
                    bindRevisionEvent(revisionEvents, parent, operation.eventId)
                    val parentFact = RevisionStreamFact(
                        operation.eventId,
                        operation.event.revisionNo - 1,
                    )
                    val priorParent = revisions.putIfAbsent(parent, parentFact)
                    if (priorParent != null && priorParent != parentFact) orderFailure()
                }
                if (
                    ack.resultCode == PushResultCode.CONFLICT &&
                    conflictHeadProviderOrdinal == null
                ) {
                    knownEvents += operation.eventId
                    bindRevisionEvent(
                        revisionEvents,
                        ack.currentRevisionId,
                        operation.eventId,
                    )
                }
                return@forEachIndexed
            }
            if (
                lineageShapes[ordinal] == PushLineageShape.ROOT &&
                !replayedRootEvents.add(operation.eventId)
            ) {
                orderFailure()
            }
            knownEvents += operation.eventId
            if (lineageShapes[ordinal] == PushLineageShape.CHILD) {
                val parent = operation.event.parentRevisionId ?: orderFailure()
                bindRevisionEvent(revisionEvents, parent, operation.eventId)
                val parentFact = RevisionStreamFact(
                    operation.eventId,
                    operation.event.revisionNo - 1,
                )
                val priorParent = revisions.putIfAbsent(parent, parentFact)
                if (priorParent != null && priorParent != parentFact) orderFailure()
            }
            bindRevisionEvent(revisionEvents, operation.revisionId, operation.eventId)
            bindRevisionEvent(revisionEvents, ack.currentRevisionId, operation.eventId)
            val fact = RevisionStreamFact(operation.eventId, operation.event.revisionNo)
            val prior = revisions.putIfAbsent(operation.revisionId, fact)
            if (prior != null && prior != fact) orderFailure()
        }

        results.forEachIndexed { ordinal, result ->
            val operation = operations[ordinal]
            val event = operation.event
            val shape = lineageShapes[ordinal]
            val error = result as? PushOperationError
            if (error?.errorCode in PRE_EVENT_OPERATION_ERROR_CODES) {
                return@forEachIndexed
            }
            if (error?.errorCode == PushOperationErrorCode.EVENT_ID_COLLISION) {
                return@forEachIndexed
            }
            if (shape == PushLineageShape.INVALID) {
                if (error?.errorCode != PushOperationErrorCode.INVALID_PARENT) orderFailure()
                return@forEachIndexed
            }
            if (shape == PushLineageShape.ROOT) {
                val ack = result as? PushOperationAck ?: orderFailure()
                if (ack.replayed) return@forEachIndexed
                if (operation.eventId in knownEvents) orderFailure()
                knownEvents += operation.eventId
                bindRevisionEvent(revisionEvents, operation.revisionId, operation.eventId)
                val fact = RevisionStreamFact(operation.eventId, event.revisionNo)
                val prior = revisions.putIfAbsent(operation.revisionId, fact)
                if (prior != null && prior != fact) orderFailure()
                heads[operation.eventId] = operation.revisionId
                return@forEachIndexed
            }

            val childParent = event.parentRevisionId ?: orderFailure()
            if (error?.errorCode == PushOperationErrorCode.INVALID_PARENT) {
                return@forEachIndexed
            }
            val childAck = result as? PushOperationAck
            if (childAck != null && !childAck.replayed) {
                bindRevisionEvent(revisionEvents, childParent, operation.eventId)
                val ackParentFact = RevisionStreamFact(
                    operation.eventId,
                    event.revisionNo - 1,
                )
                val priorParent = revisions.putIfAbsent(childParent, ackParentFact)
                if (priorParent != null && priorParent != ackParentFact) orderFailure()
            }
            val parentEventId = revisionEvents[childParent]
            if (parentEventId != null && parentEventId != operation.eventId) {
                orderFailure()
            }
            val parentFact = revisions[childParent]
            val provenParentValid = parentFact != null &&
                parentFact.eventId == operation.eventId &&
                event.revisionNo == parentFact.revisionNo + 1
            if (parentFact != null && !provenParentValid) {
                orderFailure()
            }
            if (error != null) {
                if (error.errorCode != PushOperationErrorCode.MISSING_PARENT) orderFailure()
                if (childParent in revisionEvents) orderFailure()
                return@forEachIndexed
            }

            val ack = result as PushOperationAck
            if (ack.replayed) return@forEachIndexed
            val knownHead = heads[operation.eventId]
            if (knownHead != null) {
                if (operation.expectedCurrentRevisionId == knownHead) {
                    if (ack.resultCode != PushResultCode.APPLIED) orderFailure()
                } else if (
                    ack.resultCode != PushResultCode.CONFLICT ||
                    ack.currentRevisionId != knownHead
                ) {
                    orderFailure()
                }
            }

            knownEvents += operation.eventId
            bindRevisionEvent(revisionEvents, operation.revisionId, operation.eventId)
            bindRevisionEvent(revisionEvents, ack.currentRevisionId, operation.eventId)
            val fact = RevisionStreamFact(operation.eventId, event.revisionNo)
            val prior = revisions.putIfAbsent(operation.revisionId, fact)
            if (prior != null && prior != fact) orderFailure()
            heads[operation.eventId] = when (ack.resultCode) {
                PushResultCode.APPLIED -> operation.revisionId
                PushResultCode.CONFLICT -> ack.currentRevisionId
            }
        }
    }

    private fun bindRevisionEvent(
        revisionEvents: MutableMap<String, String>,
        revisionId: String,
        eventId: String,
    ) {
        val prior = revisionEvents.putIfAbsent(revisionId, eventId)
        if (prior != null && prior != eventId) orderFailure()
    }

    private enum class PushLineageShape {
        ROOT,
        CHILD,
        INVALID,
    }

    private fun decodePushAck(
        document: WireJsonObject,
        physicalOrdinal: Int,
        operation: PushOperationWire,
        serverTime: String,
    ): PushOperationAck {
        document.requireExactFields(PUSH_ACK_FIELDS)
        val ordinal = document.requireInteger("ordinal", 0L..99L).toInt()
        if (ordinal != physicalOrdinal) orderFailure()
        val operationId = requireCanonicalUuid(document.requireString("operation_id"))
        val operationDigest = requireSha256(document.requireString("operation_content_sha256"))
        val resultCode = decodeResultCode(document.requireString("result_code"))
        val captureId = requireCanonicalUuid(document.requireString("capture_id"))
        val eventId = requireCanonicalUuid(document.requireString("event_id"))
        val revisionId = requireCanonicalUuid(document.requireString("revision_id"))
        val currentRevisionId = requireCanonicalUuid(document.requireString("current_revision_id"))
        if (
            operationId != operation.operationId ||
            operationDigest != operation.operationContentSha256 ||
            captureId != operation.captureId ||
            eventId != operation.eventId ||
            revisionId != operation.revisionId
        ) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        if (
            (resultCode == PushResultCode.APPLIED && currentRevisionId != revisionId) ||
            (resultCode == PushResultCode.CONFLICT && currentRevisionId == revisionId)
        ) {
            orderFailure()
        }
        val committedAt = requireCanonicalServerInstant(document.requireString("committed_at"))
        if (Instant.parse(committedAt) > Instant.parse(serverTime)) orderFailure()
        return PushOperationAck(
            ordinal = ordinal,
            operationId = operationId,
            operationContentSha256 = operationDigest,
            resultCode = resultCode,
            replayed = document.requireBoolean("replayed"),
            captureId = captureId,
            eventId = eventId,
            revisionId = revisionId,
            currentRevisionId = currentRevisionId,
            serverSequence = document.requireInteger("server_sequence", 1L..JSON_SAFE_INTEGER_MAX),
            committedAt = committedAt,
        )
    }

    private fun decodePushError(
        document: WireJsonObject,
        physicalOrdinal: Int,
        operation: PushOperationWire,
    ): PushOperationError {
        document.requireExactFields(PUSH_ERROR_FIELDS)
        val ordinal = document.requireInteger("ordinal", 0L..99L).toInt()
        if (ordinal != physicalOrdinal) orderFailure()
        val operationId = document.requireNullableString("operation_id")
            ?.also(::requireCanonicalUuid)
        val operationDigest = document.requireNullableString("operation_content_sha256")
            ?.also(::requireSha256)
        if (
            operationId != operation.operationId ||
            operationDigest != operation.operationContentSha256
        ) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val errorCode = PushOperationErrorCode.fromWire(document.requireString("error_code"))
            ?: schemaFailure()
        if (errorCode !in STRICT_LOCAL_OPERATION_ERROR_CODES) schemaFailure()
        val retryable = document.requireBoolean("retryable")
        if (retryable != errorCode.retryable) schemaFailure()
        val fieldErrors = decodeFieldErrors(
            document.requireArray("field_errors"),
            operationResponse = true,
        )
        if (fieldErrors.any { it.path != "/operations/$ordinal" }) schemaFailure()
        if (fieldErrors.isNotEmpty()) schemaFailure()
        return PushOperationError(
            ordinal = ordinal,
            operationId = operationId,
            operationContentSha256 = operationDigest,
            errorCode = errorCode,
            retryable = retryable,
            fieldErrors = fieldErrors,
        )
    }

    private fun decodeBootstrap(
        document: WireJsonObject,
        expectation: BootstrapResponseExpectation,
        responseBodySha256: String,
    ): ValidatedBootstrapPage {
        val fresh = decodeBootstrapPageLocal(
            document = document,
            expectation = FreshBootstrapPageExpectation(
                request = expectation.request,
                persistedRequestBodySha256 = expectation.persistedRequestBodySha256,
            ),
            responseBodySha256 = responseBodySha256,
        )
        val request = expectation.request
        val page = fresh.page
        val state = expectation.streamState
        validateReplicaChainState(state)
        val receipt = PageReplayReceipt(fresh.requestBodySha256, responseBodySha256)
        val retainedReceipt = state.successfulBootstrapPageReceipts[request.requestId]
        val exactReplay = retainedReceipt != null
        if (exactReplay && retainedReceipt != receipt) pageFailure()
        if (!exactReplay) validateBootstrapChainRequest(request, state)
        if (
            !exactReplay &&
            (
                (state.bootstrapSnapshotId != null &&
                    state.bootstrapSnapshotId != page.snapshotId) ||
                    (state.bootstrapIncrementalCursor != null &&
                        state.bootstrapIncrementalCursor != page.incrementalCursor)
                )
        ) {
            pageFailure()
        }
        val seenBootstrapPageCursors = state.seenBootstrapPageCursors.toMutableSet()
        if (
            !exactReplay &&
            (
                page.incrementalCursor in seenBootstrapPageCursors ||
                    page.incrementalCursor in state.seenPullCursors ||
                    (page.nextPageCursor != null &&
                        (page.nextPageCursor in state.seenPullCursors ||
                            !seenBootstrapPageCursors.add(page.nextPageCursor)))
                )
        ) {
            pageFailure()
        }
        if (exactReplay) {
            validateReplayedChangeReceipts(page.changes, state)
            return ValidatedBootstrapPage(
                page,
                state,
                fresh.requestBodySha256,
                responseBodySha256,
                replayed = true,
            )
        }
        val advanced = validateChangeStream(
            changes = page.changes,
            pageId = page.pageId,
            state = state.copy(
                seenSuccessfulBootstrapRequestIds =
                    state.seenSuccessfulBootstrapRequestIds + request.requestId,
                successfulBootstrapPageReceipts =
                    state.successfulBootstrapPageReceipts + (request.requestId to receipt),
                seenBootstrapPageCursors = seenBootstrapPageCursors,
                receivingDeviceId = request.deviceId,
                activeBootstrapId = request.bootstrapId,
                bootstrapPhase = if (page.complete) {
                    BootstrapValidationPhase.COMPLETE
                } else {
                    BootstrapValidationPhase.IN_PROGRESS
                },
                expectedBootstrapPageCursor = page.nextPageCursor,
                expectedPullCursor = if (page.complete) page.incrementalCursor else null,
                pullContinuationRequired = false,
                bootstrapSnapshotId = page.snapshotId,
                bootstrapIncrementalCursor = page.incrementalCursor,
            ),
        )
        return ValidatedBootstrapPage(
            page,
            advanced,
            fresh.requestBodySha256,
            responseBodySha256,
        )
    }

    private fun decodePull(
        document: WireJsonObject,
        expectation: PullResponseExpectation,
        responseBodySha256: String,
    ): ValidatedPullPage {
        val fresh = decodePullPageLocal(
            document = document,
            expectation = FreshPullPageExpectation(
                request = expectation.request,
                persistedRequestBodySha256 = expectation.persistedRequestBodySha256,
            ),
            responseBodySha256 = responseBodySha256,
        )
        val request = expectation.request
        val page = fresh.page
        val state = expectation.streamState
        validateReplicaChainState(state)
        val receipt = PageReplayReceipt(fresh.requestBodySha256, responseBodySha256)
        val retainedReceipt = state.successfulPullPageReceipts[request.requestId]
        val exactReplay = retainedReceipt != null
        if (exactReplay && retainedReceipt != receipt) pageFailure()
        if (!exactReplay) validatePullChainRequest(request, state)
        val seenPullCursors = state.seenPullCursors.toMutableSet().apply {
            add(page.fromCursor)
        }
        if (page.changes.isNotEmpty() && !exactReplay) {
            if (
                page.nextCursor in seenPullCursors ||
                page.nextCursor in state.seenBootstrapPageCursors
            ) {
                pageFailure()
            }
            seenPullCursors += page.nextCursor
        }
        if (exactReplay) {
            validateReplayedChangeReceipts(page.changes, state)
            return ValidatedPullPage(
                page,
                state,
                fresh.requestBodySha256,
                responseBodySha256,
                replayed = true,
            )
        }
        val advanced = validateChangeStream(
            page.changes,
            page.pageId,
            state.copy(
                seenSuccessfulPullRequestIds =
                    state.seenSuccessfulPullRequestIds + request.requestId,
                successfulPullPageReceipts =
                    state.successfulPullPageReceipts + (request.requestId to receipt),
                seenPullCursors = seenPullCursors,
                bootstrapPhase = BootstrapValidationPhase.INCREMENTAL,
                expectedPullCursor = page.nextCursor,
                pullContinuationRequired = page.hasMore,
            ),
        )
        return ValidatedPullPage(
            page,
            advanced,
            fresh.requestBodySha256,
            responseBodySha256,
        )
    }

    private fun decodeBootstrapPageLocal(
        document: WireJsonObject,
        expectation: FreshBootstrapPageExpectation,
        responseBodySha256: String,
    ): FreshBootstrapPage {
        document.requireExactFields(BOOTSTRAP_RESPONSE_FIELDS)
        val request = expectation.request
        requireCorrelation(document.requireString("request_id"), request.requestId)
        val bootstrapId = requireCanonicalUuid(document.requireString("bootstrap_id"))
        val deviceId = requireCanonicalUuid(document.requireString("device_id"))
        val fromPageCursor = document.requireNullableString("from_page_cursor")
            ?.also(::requireCursor)
        if (
            bootstrapId != request.bootstrapId ||
            deviceId != request.deviceId ||
            fromPageCursor != request.pageCursor
        ) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val requestBodySha256 = validatedPageRequestBodySha256(
            request,
            expectation.persistedRequestBodySha256,
        )
        validatePageHash(document)
        val pageSha256 = requireSha256(document.requireString("page_sha256"))
        val snapshotId = requireCanonicalUuid(document.requireString("snapshot_id"))
        val incrementalCursor = requireCursor(document.requireString("incremental_cursor"))
        val complete = document.requireBoolean("complete")
        val nextPageCursor = document.requireNullableString("next_page_cursor")
            ?.also(::requireCursor)
        if (
            fromPageCursor == incrementalCursor ||
            nextPageCursor == incrementalCursor
        ) {
            pageFailure()
        }
        val rawChanges = document.requireArray("changes")
        if (rawChanges.elements.size > request.pageSize) pageFailure()
        if (complete) {
            if (nextPageCursor != null) pageFailure()
        } else if (
            nextPageCursor == null ||
            nextPageCursor == fromPageCursor ||
            rawChanges.elements.isEmpty()
        ) {
            pageFailure()
        }
        val pageId = requireCanonicalUuid(document.requireString("page_id"))
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        return FreshBootstrapPage(
            page = BootstrapPageSuccess(
                requestId = request.requestId,
                bootstrapId = bootstrapId,
                deviceId = deviceId,
                fromPageCursor = fromPageCursor,
                snapshotId = snapshotId,
                pageId = pageId,
                pageSha256 = pageSha256,
                changes = decodeChanges(rawChanges, serverTime),
                nextPageCursor = nextPageCursor,
                incrementalCursor = incrementalCursor,
                complete = complete,
                serverTime = serverTime,
            ),
            requestBodySha256 = requestBodySha256,
            responseBodySha256 = responseBodySha256,
        )
    }

    private fun decodePullPageLocal(
        document: WireJsonObject,
        expectation: FreshPullPageExpectation,
        responseBodySha256: String,
    ): FreshPullPage {
        document.requireExactFields(PULL_RESPONSE_FIELDS)
        val request = expectation.request
        requireCorrelation(document.requireString("request_id"), request.requestId)
        val deviceId = requireCanonicalUuid(document.requireString("device_id"))
        val fromCursor = requireCursor(document.requireString("from_cursor"))
        if (deviceId != request.deviceId || fromCursor != request.cursor) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
        val requestBodySha256 = validatedPageRequestBodySha256(
            request,
            expectation.persistedRequestBodySha256,
        )
        validatePageHash(document)
        val pageSha256 = requireSha256(document.requireString("page_sha256"))
        val rawChanges = document.requireArray("changes")
        if (rawChanges.elements.size > request.pageSize) pageFailure()
        val nextCursor = requireCursor(document.requireString("next_cursor"))
        val hasMore = document.requireBoolean("has_more")
        if (rawChanges.elements.isEmpty()) {
            if (hasMore || nextCursor != fromCursor) pageFailure()
        } else if (nextCursor == fromCursor) {
            pageFailure()
        }
        val pageId = requireCanonicalUuid(document.requireString("page_id"))
        val serverTime = requireCanonicalServerInstant(document.requireString("server_time"))
        return FreshPullPage(
            page = PullPageSuccess(
                requestId = request.requestId,
                deviceId = deviceId,
                fromCursor = fromCursor,
                pageId = pageId,
                pageSha256 = pageSha256,
                changes = decodeChanges(rawChanges, serverTime),
                nextCursor = nextCursor,
                hasMore = hasMore,
                serverTime = serverTime,
            ),
            requestBodySha256 = requestBodySha256,
            responseBodySha256 = responseBodySha256,
        )
    }

    private fun validateBootstrapChainRequest(
        request: BootstrapRequest,
        state: ReplicaStreamValidationState,
    ) {
        validateReplicaChainState(state)
        if (
            request.pageSize !in 1..M2_MAX_PAGE_SIZE ||
            request.requestId in state.seenSuccessfulBootstrapRequestIds
        ) {
            pageFailure()
        }
        when (state.bootstrapPhase) {
            BootstrapValidationPhase.INITIAL -> if (request.pageCursor != null) pageFailure()
            BootstrapValidationPhase.IN_PROGRESS -> if (
                request.deviceId != state.receivingDeviceId ||
                request.bootstrapId != state.activeBootstrapId ||
                request.pageCursor != state.expectedBootstrapPageCursor
            ) {
                pageFailure()
            }
            BootstrapValidationPhase.COMPLETE,
            BootstrapValidationPhase.INCREMENTAL,
            -> pageFailure()
        }
    }

    private fun validatePullChainRequest(
        request: PullRequest,
        state: ReplicaStreamValidationState,
    ) {
        validateReplicaChainState(state)
        if (
            request.pageSize !in 1..M2_MAX_PAGE_SIZE ||
            request.requestId in state.seenSuccessfulPullRequestIds ||
            state.bootstrapPhase !in setOf(
                BootstrapValidationPhase.COMPLETE,
                BootstrapValidationPhase.INCREMENTAL,
            ) ||
            request.deviceId != state.receivingDeviceId ||
            request.cursor != state.expectedPullCursor
        ) {
            pageFailure()
        }
    }

    private fun validatedPageRequestBodySha256(
        request: M2WireRequest,
        persistedRequestBodySha256: String?,
    ): String {
        val pageSize = when (request) {
            is BootstrapRequest -> request.pageSize
            is PullRequest -> request.pageSize
            else -> pageFailure()
        }
        if (pageSize !in 1..M2_MAX_PAGE_SIZE) pageFailure()
        val calculated = try {
            WireRequestCodec.materialize(request).use { it.bodySha256 }
        } catch (_: WireProtocolException) {
            pageFailure()
        }
        if (persistedRequestBodySha256 != null) {
            val persisted = try {
                requireSha256(persistedRequestBodySha256)
            } catch (_: WireProtocolException) {
                pageFailure()
            }
            if (!constantTimeHexEquals(persisted, calculated)) pageFailure()
        }
        return calculated
    }

    private fun validateReplicaChainState(state: ReplicaStreamValidationState) {
        val replayReceiptsValid =
            state.seenSuccessfulBootstrapRequestIds ==
                state.successfulBootstrapPageReceipts.keys &&
                state.seenSuccessfulPullRequestIds ==
                state.successfulPullPageReceipts.keys &&
                (state.successfulBootstrapPageReceipts.values +
                    state.successfulPullPageReceipts.values).all { receipt ->
                    receipt.requestBodySha256.isCanonicalSha256() &&
                        receipt.responseBodySha256.isCanonicalSha256()
                }
        val cursorRolesValid =
            state.seenBootstrapPageCursors.intersect(state.seenPullCursors).isEmpty() &&
                state.bootstrapIncrementalCursor?.let {
                    it !in state.seenBootstrapPageCursors
                } != false &&
                state.expectedBootstrapPageCursor?.let {
                    it !in state.seenPullCursors
                } != false &&
                state.expectedPullCursor?.let {
                    it !in state.seenBootstrapPageCursors
                } != false
        val valid = replayReceiptsValid && cursorRolesValid && when (state.bootstrapPhase) {
            BootstrapValidationPhase.INITIAL ->
                state.receivingDeviceId == null &&
                    state.activeBootstrapId == null &&
                    state.expectedBootstrapPageCursor == null &&
                    state.expectedPullCursor == null &&
                    !state.pullContinuationRequired &&
                    state.bootstrapSnapshotId == null &&
                    state.bootstrapIncrementalCursor == null &&
                    state.seenBootstrapPageCursors.isEmpty() &&
                    state.seenPullCursors.isEmpty()
            BootstrapValidationPhase.IN_PROGRESS ->
                state.receivingDeviceId != null &&
                    state.activeBootstrapId != null &&
                    state.expectedBootstrapPageCursor != null &&
                    state.expectedPullCursor == null &&
                    !state.pullContinuationRequired &&
                    state.bootstrapSnapshotId != null &&
                    state.bootstrapIncrementalCursor != null &&
                    state.expectedBootstrapPageCursor in state.seenBootstrapPageCursors &&
                    state.seenPullCursors.isEmpty()
            BootstrapValidationPhase.COMPLETE ->
                state.receivingDeviceId != null &&
                    state.activeBootstrapId != null &&
                    state.expectedBootstrapPageCursor == null &&
                    state.expectedPullCursor != null &&
                    !state.pullContinuationRequired &&
                    state.bootstrapSnapshotId != null &&
                    state.bootstrapIncrementalCursor != null &&
                    state.expectedPullCursor == state.bootstrapIncrementalCursor &&
                    state.seenPullCursors.isEmpty()
            BootstrapValidationPhase.INCREMENTAL ->
                state.receivingDeviceId != null &&
                    state.activeBootstrapId != null &&
                    state.expectedBootstrapPageCursor == null &&
                    state.expectedPullCursor != null &&
                    state.bootstrapSnapshotId != null &&
                    state.bootstrapIncrementalCursor != null &&
                    state.expectedPullCursor in state.seenPullCursors
        }
        if (!valid) pageFailure()
    }

    private fun String.isCanonicalSha256(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

    private fun decodeChanges(rawChanges: WireJsonArray, serverTime: String): List<ServerChangeWire> =
        rawChanges.elements.map { raw ->
            val document = raw as? WireJsonObject ?: schemaFailure()
            document.requireExactFields(SERVER_CHANGE_FIELDS)
            val serverSequence = document.requireInteger(
                "server_sequence",
                1L..JSON_SAFE_INTEGER_MAX,
            )
            document.requireConstant("change_kind", "event_revision_committed")
            val resultCode = decodeResultCode(document.requireString("result_code"))
            val operationId = requireCanonicalUuid(document.requireString("operation_id"))
            val captureId = requireCanonicalUuid(document.requireString("capture_id"))
            val eventId = requireCanonicalUuid(document.requireString("event_id"))
            val revisionId = requireCanonicalUuid(document.requireString("revision_id"))
            val currentRevisionId = requireCanonicalUuid(
                document.requireString("current_revision_id"),
            )
            val operationDigest = requireSha256(
                document.requireString("operation_content_sha256"),
            )
            val capture = M2NoteWireDocuments.validateCapture(
                document.requireObject("capture"),
                expectedState = "authenticated_ingress",
                committed = true,
            )
            val event = M2NoteWireDocuments.validateEvent(
                document.requireObject("event"),
                expectedState = "server_committed",
                committed = true,
            )
            M2NoteWireDocuments.requireConsistent(capture, event)
            if (
                operationId != capture.operationId || operationId != event.operationId ||
                captureId != capture.captureId || captureId != event.captureId ||
                eventId != event.eventId || revisionId != event.revisionId ||
                serverSequence != event.serverSequence
            ) {
                schemaFailure()
            }
            val receivedAt = checkNotNull(event.receivedAt)
            if (Instant.parse(receivedAt) > Instant.parse(serverTime)) orderFailure()
            ServerChangeWire(
                serverSequence = serverSequence,
                resultCode = resultCode,
                operationId = operationId,
                captureId = captureId,
                eventId = eventId,
                revisionId = revisionId,
                currentRevisionId = currentRevisionId,
                operationContentSha256 = operationDigest,
                capture = capture,
                event = event,
            )
        }

    private fun validateChangeStream(
        changes: List<ServerChangeWire>,
        pageId: String,
        state: ReplicaStreamValidationState,
    ): ReplicaStreamValidationState {
        if (pageId in state.seenPageIds) pageFailure()
        var lastSequence = state.lastServerSequence
        val revisions = state.revisionsById.toMutableMap()
        val heads = state.currentRevisionByEvent.toMutableMap()
        val receipts = state.terminalReceiptsByOperationId.toMutableMap()
        val captureIds = state.captureIds.toMutableSet()
        changes.forEach { change ->
            lastSequence?.let { previous ->
                if (change.serverSequence <= previous) orderFailure()
            }
            lastSequence = change.serverSequence
            if (change.revisionId in revisions || !captureIds.add(change.captureId)) orderFailure()
            val event = change.event
            val priorHead = heads[change.eventId]
            val parent = event.parentRevisionId
            if (parent == null) {
                if (event.revisionNo != 1 || priorHead != null) orderFailure()
            } else {
                val parentFact = revisions[parent] ?: orderFailure()
                if (
                    parentFact.eventId != change.eventId ||
                    event.revisionNo != parentFact.revisionNo + 1
                ) {
                    orderFailure()
                }
            }
            when (change.resultCode) {
                PushResultCode.APPLIED -> {
                    if (change.currentRevisionId != change.revisionId || parent != priorHead) {
                        orderFailure()
                    }
                    heads[change.eventId] = change.revisionId
                }
                PushResultCode.CONFLICT -> {
                    if (
                        priorHead == null ||
                        parent == priorHead ||
                        change.currentRevisionId != priorHead ||
                        change.currentRevisionId == change.revisionId
                    ) {
                        orderFailure()
                    }
                }
            }
            revisions[change.revisionId] = RevisionStreamFact(
                eventId = change.eventId,
                revisionNo = event.revisionNo,
            )
            val receipt = OperationReceiptFact(
                operationContentSha256 = change.operationContentSha256,
                resultCode = change.resultCode,
                captureId = change.captureId,
                eventId = change.eventId,
                revisionId = change.revisionId,
                currentRevisionId = change.currentRevisionId,
                serverSequence = change.serverSequence,
                committedAt = checkNotNull(event.receivedAt),
            )
            val existing = receipts.putIfAbsent(change.operationId, receipt)
            if (existing != null && existing != receipt) orderFailure()
        }
        return state.copy(
            lastServerSequence = lastSequence,
            seenPageIds = state.seenPageIds + pageId,
            revisionsById = revisions,
            currentRevisionByEvent = heads,
            terminalReceiptsByOperationId = receipts,
            captureIds = captureIds,
        )
    }

    private fun validateReplayedChangeReceipts(
        changes: List<ServerChangeWire>,
        state: ReplicaStreamValidationState,
    ) {
        changes.forEach { change ->
            val expected = OperationReceiptFact(
                operationContentSha256 = change.operationContentSha256,
                resultCode = change.resultCode,
                captureId = change.captureId,
                eventId = change.eventId,
                revisionId = change.revisionId,
                currentRevisionId = change.currentRevisionId,
                serverSequence = change.serverSequence,
                committedAt = checkNotNull(change.event.receivedAt),
            )
            val existing = state.terminalReceiptsByOperationId[change.operationId]
            if (existing != null && existing != expected) {
                orderFailure()
            }
        }
    }

    private fun validatePageHash(document: WireJsonObject) {
        val declared = requireSha256(document.requireString("page_sha256"))
        val calculated = StrictJson.canonicalSha256(document.without("page_sha256"))
        if (!constantTimeHexEquals(declared, calculated)) {
            throw WireProtocolException(WireProtocolFailure.HASH_MISMATCH)
        }
    }

    private fun decodeTokenPair(
        document: WireJsonObject,
        serverTime: String,
        expectedGeneration: Long,
        expectedFamilyExpiresAt: String?,
        maximumFamilyLifetime: Duration?,
        forbiddenTokenDigests: Set<String>,
    ): EphemeralTokenPair {
        document.requireExactFields(TOKEN_PAIR_FIELDS)
        document.requireConstant("token_type", "Bearer")
        val accessToken = document.requireString("access_token")
        val refreshToken = document.requireString("refresh_token")
        requireAccessToken(accessToken)
        requireRefreshToken(refreshToken)
        val accessExpiresAt = requireCanonicalServerInstant(
            document.requireString("access_expires_at"),
        )
        val refreshExpiresAt = requireCanonicalServerInstant(
            document.requireString("refresh_expires_at"),
        )
        val familyExpiresAt = requireCanonicalServerInstant(
            document.requireString("family_expires_at"),
        )
        if (expectedFamilyExpiresAt != null && familyExpiresAt != expectedFamilyExpiresAt) {
            throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)
        }
        val generation = document.requireInteger("generation", 1L..JSON_SAFE_INTEGER_MAX)
        if (generation != expectedGeneration) authFailure()
        val server = Instant.parse(serverTime)
        val accessExpiry = Instant.parse(accessExpiresAt)
        val refreshExpiry = Instant.parse(refreshExpiresAt)
        val familyExpiry = Instant.parse(familyExpiresAt)
        val accessLifetime = Duration.between(server, accessExpiry)
        val refreshLifetime = Duration.between(server, refreshExpiry)
        val familyLifetime = Duration.between(server, familyExpiry)
        if (
            accessLifetime <= Duration.ZERO || accessLifetime > Duration.ofMinutes(15) ||
            refreshLifetime <= Duration.ZERO || refreshLifetime > Duration.ofDays(30) ||
            familyLifetime <= Duration.ZERO || refreshExpiry > familyExpiry ||
            accessExpiry >= refreshExpiry ||
            (maximumFamilyLifetime != null && familyLifetime > maximumFamilyLifetime)
        ) {
            authFailure()
        }
        val accessBytes = accessToken.toByteArray(StandardCharsets.US_ASCII)
        val refreshBytes = refreshToken.toByteArray(StandardCharsets.US_ASCII)
        val accessDigest = sha256Hex(accessBytes)
        val refreshDigest = sha256Hex(refreshBytes)
        if (
            accessDigest == refreshDigest ||
            accessDigest in forbiddenTokenDigests ||
            refreshDigest in forbiddenTokenDigests
        ) {
            accessBytes.fill(0)
            refreshBytes.fill(0)
            authFailure()
        }
        return EphemeralTokenPair(
            accessToken = accessBytes,
            refreshToken = refreshBytes,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
            familyExpiresAt = familyExpiresAt,
            generation = generation,
        )
    }

    private fun decodeFieldErrors(
        array: WireJsonArray,
        operationResponse: Boolean,
    ): List<WireFieldError> {
        if (array.elements.size > 8) schemaFailure()
        val allowedCodes = if (operationResponse) OPERATION_FIELD_CODES else API_FIELD_CODES
        val errors = array.elements.map { raw ->
            val item = raw as? WireJsonObject ?: schemaFailure()
            item.requireExactFields(API_FIELD_ERROR_FIELDS)
            val path = validateApiFieldPath(item.requireString("path"))
            val code = FieldErrorCode.fromWire(item.requireString("code"))
                ?.takeIf { it in allowedCodes } ?: schemaFailure()
            WireFieldError(path, code)
        }
        if (errors.toSet().size != errors.size) schemaFailure()
        return errors
    }

    private fun parseResponseObject(
        body: ByteArray,
        byteLimit: Int,
        shape: WireResponseShape,
    ): WireJsonObject = try {
        StrictJson.parse(
            body,
            StrictJsonLimits.response(byteLimit),
            shape,
        ) as? WireJsonObject ?: schemaFailure()
    } catch (error: StrictJsonException) {
        throw WireProtocolException(WireProtocolFailure.JSON_TRUST_BOUNDARY, error)
    }

    private fun requireSuccessDiscriminators(document: WireJsonObject, endpoint: M2Endpoint) {
        document.requireConstant("protocol_version", M2_PROTOCOL_VERSION)
        document.requireConstant("message_type", endpoint.successMessageType)
    }

    private fun requireCorrelation(actual: String, expected: String) {
        requireCanonicalUuid(actual)
        if (actual != expected) {
            throw WireProtocolException(WireProtocolFailure.CORRELATION_MISMATCH)
        }
    }

    private fun decodeResultCode(value: String): PushResultCode = when (value) {
        "applied" -> PushResultCode.APPLIED
        "conflict" -> PushResultCode.CONFLICT
        else -> schemaFailure()
    }

    private fun orderFailure(): Nothing =
        throw WireProtocolException(WireProtocolFailure.ORDER_MISMATCH)

    private fun pageFailure(): Nothing =
        throw WireProtocolException(WireProtocolFailure.PAGE_INVARIANT)

    private fun authFailure(): Nothing =
        throw WireProtocolException(WireProtocolFailure.AUTH_INVARIANT)

    private val API_FIELD_CODES = setOf(
        FieldErrorCode.MISSING_REQUIRED_FIELD,
        FieldErrorCode.INVALID_TYPE,
        FieldErrorCode.INVALID_FORMAT,
        FieldErrorCode.INVALID_VALUE,
        FieldErrorCode.UNSUPPORTED_VALUE,
        FieldErrorCode.UNEXPECTED_FIELD,
    )
    private val OPERATION_FIELD_CODES = setOf(
        FieldErrorCode.SCHEMA_INVALID,
        FieldErrorCode.MISSING_REQUIRED_FIELD,
        FieldErrorCode.UNEXPECTED_FIELD,
        FieldErrorCode.INVALID_FIELD_TYPE,
        FieldErrorCode.INVALID_FIELD_VALUE,
        FieldErrorCode.UNSUPPORTED_SCHEMA_VERSION,
        FieldErrorCode.UNSUPPORTED_OPERATION_KIND,
        FieldErrorCode.UNSUPPORTED_EVENT_KIND,
        FieldErrorCode.UNSUPPORTED_SOURCE_CHANNEL,
    )
    private val STRICT_LOCAL_OPERATION_ERROR_CODES = setOf(
        PushOperationErrorCode.OPERATION_ID_COLLISION,
        PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
        PushOperationErrorCode.CAPTURE_ID_COLLISION,
        PushOperationErrorCode.REVISION_ID_COLLISION,
        PushOperationErrorCode.EVENT_ID_COLLISION,
        PushOperationErrorCode.MISSING_PARENT,
        PushOperationErrorCode.INVALID_PARENT,
        PushOperationErrorCode.OWNERSHIP_VIOLATION,
    )
    private val PREDEPENDENCY_REGISTRY_COLLISION_CODES = listOf(
        PushOperationErrorCode.OPERATION_ID_COLLISION,
        PushOperationErrorCode.CLIENT_SEQUENCE_COLLISION,
        PushOperationErrorCode.CAPTURE_ID_COLLISION,
        PushOperationErrorCode.REVISION_ID_COLLISION,
    )
    private val PRE_EVENT_OPERATION_ERROR_CODES =
        PREDEPENDENCY_REGISTRY_COLLISION_CODES.toSet() +
            PushOperationErrorCode.OWNERSHIP_VIOLATION
    private val NONCOMMIT_POST_REGISTRY_ERROR_CODES = setOf(
        PushOperationErrorCode.EVENT_ID_COLLISION,
        PushOperationErrorCode.MISSING_PARENT,
        PushOperationErrorCode.INVALID_PARENT,
    )

    private fun allowsDualStageError(endpoint: M2Endpoint, code: ApiErrorCode): Boolean =
        code in GENERIC_DUAL_STAGE_ERROR_CODES ||
            (endpoint.usesBearerAccess && code == ApiErrorCode.CREDENTIAL_UNAVAILABLE) ||
            (endpoint == M2Endpoint.SYNC_PUSH && code == ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH)

    /** Strict ingress cannot have extracted a trusted identifier for these failures. */
    private val ALWAYS_PRE_IDENTITY_ERROR_CODES = setOf(
        ApiErrorCode.MALFORMED_JSON,
        ApiErrorCode.REQUEST_TOO_LARGE,
        ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
    )
    private val GENERIC_DUAL_STAGE_ERROR_CODES = setOf(
        ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
        ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ApiErrorCode.TEMPORARILY_UNAVAILABLE,
    )
    private val API_ERROR_FIELDS = setOf(
        "protocol_version", "message_type", "request_id", "error_code", "http_status",
        "retryable", "field_errors", "server_time",
    )
    private val TOKEN_PAIR_FIELDS = setOf(
        "token_type", "access_token", "access_expires_at", "refresh_token",
        "refresh_expires_at", "family_expires_at", "generation",
    )
    private val ENROLLMENT_RESPONSE_FIELDS = setOf(
        "protocol_version", "message_type", "request_id", "installation_id", "local_owner_id",
        "device_id", "person_id", "credentials", "bootstrap_required", "server_time",
    )
    private val REFRESH_RESPONSE_FIELDS = setOf(
        "protocol_version", "message_type", "request_id", "device_id", "credentials",
        "server_time",
    )
    private val REVOKE_RESPONSE_FIELDS = setOf(
        "protocol_version", "message_type", "request_id", "device_id", "generation", "status",
        "revoked_at", "server_time",
    )
    private val PUSH_RESPONSE_FIELDS = setOf(
        "protocol_version", "message_type", "batch_id", "device_id", "results",
        "server_high_watermark", "server_time",
    )
    private val PUSH_ACK_FIELDS = setOf(
        "ordinal", "operation_id", "status", "operation_content_sha256", "result_code",
        "replayed", "capture_id", "event_id", "revision_id", "current_revision_id",
        "server_sequence", "committed_at",
    )
    private val PUSH_ERROR_FIELDS = setOf(
        "ordinal", "operation_id", "status", "operation_content_sha256", "error_code",
        "retryable", "field_errors",
    )
    private val BOOTSTRAP_RESPONSE_FIELDS = setOf(
        "protocol_version", "message_type", "request_id", "bootstrap_id", "device_id",
        "from_page_cursor", "snapshot_id", "page_id", "page_sha256", "changes",
        "next_page_cursor", "incremental_cursor", "complete", "server_time",
    )
    private val PULL_RESPONSE_FIELDS = setOf(
        "protocol_version", "message_type", "request_id", "device_id", "from_cursor", "page_id",
        "page_sha256", "changes", "next_cursor", "has_more", "server_time",
    )
    private val SERVER_CHANGE_FIELDS = setOf(
        "server_sequence", "change_kind", "result_code", "operation_id", "capture_id",
        "event_id", "revision_id", "current_revision_id", "operation_content_sha256",
        "capture", "event",
    )
}
