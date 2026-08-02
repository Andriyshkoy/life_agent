package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.withTransaction
import java.time.Instant
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncRequestIntegrityRecoverySnapshot
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.security.CURRENT_HMAC_KEY_GENERATION
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestProtector
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestVerifier
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.security.RequestBodyHmacMismatchException
import ru.andriyshkoy.lifeagent.data.security.RequestBodyKeyUnavailableException
import ru.andriyshkoy.lifeagent.data.security.RequestBodyMetadataInvalidException
import ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityException
import ru.andriyshkoy.lifeagent.data.security.VerifiedDurableRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.M2WireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PushBatchRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.RevokeRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec

/**
 * The only dispatch-facing Room boundary for durable exact request bodies.
 * Verification, strict body correlation and attempt claim happen in one Room
 * transaction; integrity failures are terminalized before an attempt can be
 * consumed.
 */
internal class ProtectedSyncRequestStore(
    context: Context,
    private val database: LifeAgentDatabase,
    private val keyring: KeystoreRequestBodyHmacKeyring =
        KeystoreRequestBodyHmacKeyring(context),
) {
    private val protector = DurableSyncRequestProtector(context, keyring)
    private val verifier = DurableSyncRequestVerifier(context, keyring)
    private val authDao = database.syncAuthDao()
    private val mutationDao = database.noteMutationDao()
    private val replicaDao = database.syncReplicaDao()
    private val transportDao = database.syncTransportDao()

    /**
     * Explicit first-use create boundary. The authoritative reference count is
     * read under the same database transaction; callers cannot supply it.
     * The request object is consumed and any embedded secret is closed.
     */
    suspend fun persistPush(
        request: PushBatchRequest,
        persistence: NewDurableRequestPersistence,
        batch: SyncPushBatchEntity,
        items: List<SyncPushBatchItemEntity>,
    ): PersistedDurableRequestRef = withPreparedRequest(request, persistence) { protected ->
        requirePushPersistenceRows(protected, batch, items)
        transportDao.insertPushRequest(protected, batch, items)
        protected.toPersistedRef()
    }

    suspend fun persistPull(
        request: PullRequest,
        persistence: NewDurableRequestPersistence,
    ): PersistedDurableRequestRef = withPreparedRequest(request, persistence) { protected ->
        requirePullPersistenceBinding(protected)
        check(
            transportDao.countOpenPullRequests(
                credentialEpochId = protected.credentialEpochId,
                deviceId = protected.deviceId,
            ) == 0L,
        ) {
            "A durable pull request is already open for this stream"
        }
        transportDao.insertRequest(protected)
        protected.toPersistedRef()
    }

    suspend fun beginRevoke(
        request: RevokeRequest,
        persistence: NewDurableRequestPersistence,
        nowEpochMs: Long,
        updatedAtUtc: String,
    ): PersistedDurableRequestRef = consumeRequest(request) {
        var transient: SyncHttpRequestEntity? = null
        try {
            database.withTransaction {
                require(
                    request.endpoint == M2Endpoint.AUTH_REVOKE &&
                        request.deviceId.isNotBlank() &&
                        request.generation == persistence.accessGenerationUsed &&
                        Instant.parse(updatedAtUtc).toEpochMilli() == nowEpochMs,
                )
                // Claim exact-family eligibility before a unique per-request
                // AEAD alias can be generated. A stale/ineligible call therefore
                // cannot leak Keystore entries.
                check(
                    authDao.claimRevokeFamily(
                        credentialEpochId = persistence.localCredentialEpochId,
                        deviceId = request.deviceId,
                        generation = request.generation,
                        nowEpochMs = nowEpochMs,
                        updatedAtUtc = updatedAtUtc,
                    ) == 1,
                ) {
                    "Credential family is not eligible for protected revoke"
                }
                val protected = prepareNewInCurrentTransaction(request, persistence)
                transient = protected
                transportDao.insertRequest(protected)
                protected.toPersistedRef()
            }
        } finally {
            transient?.wipeTransientProtectionBuffers()
        }
    }

    suspend fun commitEnrollmentSuccess(
        bundle: EnrollmentSuccessPersistence,
        bootstrapRequest: BootstrapRequest,
        persistence: NewDurableRequestPersistence,
    ) = withPreparedRequest(bootstrapRequest, persistence) { protected ->
        requireBootstrapPersistenceBinding(protected, bundle.bootstrapSession)
        check(protected.accessGenerationUsed == bundle.authState.generation) {
            "Protected enrollment bootstrap uses the wrong credential generation"
        }
        SyncAuthPersistenceStore(database).commitEnrollmentSuccessState(bundle)
        transportDao.insertRequest(protected)
    }

    suspend fun commitPushResponse(
        response: TerminalHttpResponsePersistence,
        results: List<PushResultPersistence>,
    ) = SyncPersistenceStore(database).commitPushResponse(response, results)

    suspend fun commitPullPage(
        response: TerminalHttpResponsePersistence,
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
    ) = SyncPersistenceStore(database).commitPullPage(response, receipt, changes)

    suspend fun commitCursorInvalid(
        response: TerminalHttpResponsePersistence,
    ) = SyncPersistenceStore(database).commitCursorInvalid(response)

    suspend fun handleTrustedSyncUnauthorized(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
        failedAccessGeneration: Long,
        nowEpochMs: Long,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): CredentialRecoveryAction = SyncAuthPersistenceStore(database)
        .handleTrustedSyncUnauthorized(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            expectedAttemptId = expectedAttemptId,
            failedAccessGeneration = failedAccessGeneration,
            nowEpochMs = nowEpochMs,
            nextAttemptAtEpochMs = nextAttemptAtEpochMs,
            updatedAtUtc = updatedAtUtc,
        )

    suspend fun commitRevokeTerminal(
        response: TerminalHttpResponsePersistence,
    ): Boolean = SyncAuthPersistenceStore(database).commitRevokeTerminal(response)

    suspend fun quarantineRevokeIntegrity(
        requestIdentity: String,
        expectedKeyAlias: String,
        expectedKeyGeneration: Int,
        expectedAadVersion: Int,
        expectedAttemptId: String,
        updatedAtUtc: String,
        failureCode: String,
    ): Boolean = SyncAuthPersistenceStore(database).quarantineRevokeIntegrity(
        requestIdentity = requestIdentity,
        expectedKeyAlias = expectedKeyAlias,
        expectedKeyGeneration = expectedKeyGeneration,
        expectedAadVersion = expectedAadVersion,
        expectedAttemptId = expectedAttemptId,
        updatedAtUtc = updatedAtUtc,
        failureCode = failureCode,
    )

    suspend fun commitPushBootstrapRequired(
        response: TerminalHttpResponsePersistence,
        session: SyncBootstrapSessionEntity,
        bootstrapRequest: BootstrapRequest,
        persistence: NewDurableRequestPersistence,
    ): Boolean {
        var transient: SyncHttpRequestEntity? = null
        return try {
            SyncRequestPersistenceStore(database)
                .commitPushBootstrapRequiredWithProtectedIntent(
                    response = response,
                    proposedIntentFactory = {
                        val protected = prepareNewInCurrentTransaction(
                            bootstrapRequest,
                            persistence,
                        )
                        transient = protected
                        requireBootstrapPersistenceBinding(protected, session)
                        BootstrapIntentPersistence(
                            session = session,
                            firstRequest = protected,
                        )
                    },
                    existingCandidateVerifier = {
                        verifyExistingBootstrapCandidates(session, response.terminalAtUtc)
                    },
                )
        } finally {
            transient?.wipeTransientProtectionBuffers()
        }
    }

    suspend fun commitBootstrapPage(
        response: TerminalHttpResponsePersistence,
        receipt: SyncPageReceiptEntity,
        changes: List<ReplicaChangePersistence>,
        continuationRequest: BootstrapRequest?,
        continuationPersistence: NewDurableRequestPersistence?,
    ) {
        require((continuationRequest == null) == (continuationPersistence == null))
        var transient: SyncHttpRequestEntity? = null
        try {
            SyncPersistenceStore(database).commitBootstrapPage(
                response = response,
                receipt = receipt,
                changes = changes,
                continuationFactory = continuationRequest?.let { request ->
                    { session ->
                        val protected = prepareNewInCurrentTransaction(
                            request,
                            checkNotNull(continuationPersistence),
                        )
                        transient = protected
                        requireBootstrapPersistenceBinding(protected, session)
                        protected
                    }
                },
            )
        } finally {
            transient?.wipeTransientProtectionBuffers()
        }
    }

    suspend fun commitBootstrapCursorExpired(
        response: TerminalHttpResponsePersistence,
        expiredBootstrapId: String,
        replacementSession: SyncBootstrapSessionEntity,
        replacementRequest: BootstrapRequest,
        replacementPersistence: NewDurableRequestPersistence,
    ) {
        var transient: SyncHttpRequestEntity? = null
        try {
            SyncPersistenceStore(database)
                .commitBootstrapCursorExpiredWithProtectedReplacement(
                    response = response,
                    bootstrapId = expiredBootstrapId,
                    replacementFactory = {
                        val protected = prepareNewInCurrentTransaction(
                            replacementRequest,
                            replacementPersistence,
                        )
                        transient = protected
                        requireBootstrapPersistenceBinding(protected, replacementSession)
                        BootstrapIntentPersistence(
                            session = replacementSession,
                            firstRequest = protected,
                        )
                    },
                    existingCandidateVerifier = { authoritativeSession ->
                        verifyExistingBootstrapCandidates(
                            authoritativeSession,
                            response.terminalAtUtc,
                        )
                    },
                )
        } finally {
            transient?.wipeTransientProtectionBuffers()
        }
    }

    suspend fun verifyAndClaim(
        endpointId: String,
        requestIdentity: String,
        attemptId: String,
        attemptedAtEpochMs: Long,
        leaseExpiresAtEpochMs: Long,
        updatedAtUtc: String,
    ): ProtectedRequestClaim {
        var pendingOwnership: VerifiedDurableRequest? = null
        return try {
            val result = database.withTransaction {
                require(attemptId.isNotBlank())
                require(attemptedAtEpochMs > 0)
                require(leaseExpiresAtEpochMs > attemptedAtEpochMs)
                require(Instant.parse(updatedAtUtc).toEpochMilli() == attemptedAtEpochMs)
                val integritySnapshot =
                    transportDao.findRequestIntegrityRecoverySnapshot(
                        endpointId,
                        requestIdentity,
                    )
                if (
                    integritySnapshot != null &&
                    integritySnapshot.state in OPEN_REQUEST_STATES &&
                    (
                        !integritySnapshot.hasCanonicalHmacStorage ||
                            !integritySnapshot.hasCanonicalHmacKeyGeneration ||
                            !integritySnapshot.hasCanonicalAccessGeneration ||
                            !integritySnapshot.hasCanonicalAttemptCount
                    )
                ) {
                    quarantineInvalidMetadataBeforeClaim(integritySnapshot, updatedAtUtc)
                    return@withTransaction ProtectedRequestClaim.IntegrityFailure(
                        RequestBodyFailure.METADATA_INVALID,
                    )
                }
                val request = transportDao.findRequest(endpointId, requestIdentity)
                    ?: return@withTransaction ProtectedRequestClaim.NotClaimed
                if (!request.isEligiblePreclaimSnapshot(attemptedAtEpochMs)) {
                    return@withTransaction ProtectedRequestClaim.NotClaimed
                }

                val verified = try {
                    verifier.loadVerified(request)
                } catch (error: Exception) {
                    val failure = error.toRequestBodyFailureOrNull() ?: throw error
                    quarantineBeforeClaim(request, failure, updatedAtUtc)
                    return@withTransaction ProtectedRequestClaim.IntegrityFailure(failure)
                }

                try {
                    val claimed = if (
                        request.endpointId == M2Endpoint.AUTH_REVOKE.endpointId
                    ) {
                        val currentAuth = authDao.findState()
                        if (
                            currentAuth == null ||
                            currentAuth.credentialEpochId != request.credentialEpochId ||
                            currentAuth.deviceId != request.deviceId ||
                            currentAuth.state != "revoke_pending"
                        ) {
                            verified.close()
                            return@withTransaction ProtectedRequestClaim.NotClaimed
                        }
                        if (request.accessGenerationUsed != currentAuth.generation) {
                            verified.close()
                            quarantineBeforeClaim(
                                request,
                                RequestBodyFailure.METADATA_INVALID,
                                updatedAtUtc,
                            )
                            return@withTransaction ProtectedRequestClaim.IntegrityFailure(
                                RequestBodyFailure.METADATA_INVALID,
                            )
                        }
                        transportDao.claimRevokeAttempt(
                            requestIdentity = request.requestIdentity,
                            attemptId = attemptId,
                            attemptedAtEpochMs = attemptedAtEpochMs,
                            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                            updatedAtUtc = updatedAtUtc,
                        )
                    } else {
                        val currentAuth = authDao.findState()
                        if (
                            currentAuth == null ||
                            currentAuth.credentialEpochId != request.credentialEpochId ||
                            currentAuth.deviceId != request.deviceId ||
                            currentAuth.state != "active"
                        ) {
                            // A refresh in flight and a replaced/inactive family
                            // are legitimate deferrals, not evidence that the
                            // authenticated request body was corrupted.
                            verified.close()
                            return@withTransaction ProtectedRequestClaim.NotClaimed
                        }
                        val currentStream = replicaDao.findStreamState()
                        val generationDrift =
                            checkNotNull(request.accessGenerationUsed) > currentAuth.generation
                        val authStreamDrift =
                            currentStream == null ||
                                currentStream.credentialEpochId != request.credentialEpochId ||
                                currentStream.deviceId != request.deviceId ||
                                currentAuth.bootstrapRequired != currentStream.bootstrapRequired
                        if (generationDrift || authStreamDrift) {
                            verified.close()
                            quarantineBeforeClaim(
                                request,
                                RequestBodyFailure.METADATA_INVALID,
                                updatedAtUtc,
                            )
                            return@withTransaction ProtectedRequestClaim.IntegrityFailure(
                                RequestBodyFailure.METADATA_INVALID,
                            )
                        }
                        if (
                            currentAuth.accessExpiresAtEpochMs <= attemptedAtEpochMs ||
                            currentAuth.familyExpiresAtEpochMs <= attemptedAtEpochMs
                        ) {
                            verified.close()
                            return@withTransaction ProtectedRequestClaim.NotClaimed
                        }
                        try {
                            requireDurableSyncMembership(request, verified)
                        } catch (error: DurableMembershipInvalidException) {
                            verified.close()
                            quarantineBeforeClaim(
                                request,
                                RequestBodyFailure.METADATA_INVALID,
                                updatedAtUtc,
                            )
                            return@withTransaction ProtectedRequestClaim.IntegrityFailure(
                                RequestBodyFailure.METADATA_INVALID,
                            )
                        }
                        transportDao.claimAttemptRow(
                            endpointId = request.endpointId,
                            requestIdentity = request.requestIdentity,
                            credentialEpochId = request.credentialEpochId,
                            accessGenerationUsed = currentAuth.generation,
                            attemptId = attemptId,
                            attemptedAtEpochMs = attemptedAtEpochMs,
                            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                            updatedAtUtc = updatedAtUtc,
                        )
                    }
                    if (claimed != 1) {
                        verified.close()
                        ProtectedRequestClaim.NotClaimed
                    } else {
                        pendingOwnership = verified
                        ProtectedRequestClaim.Claimed(verified)
                    }
                } catch (error: Throwable) {
                    verified.close()
                    throw error
                }
            }
            pendingOwnership = null
            result
        } catch (error: Throwable) {
            pendingOwnership?.close()
            throw error
        }
    }

    private suspend fun quarantineBeforeClaim(
        request: SyncHttpRequestEntity,
        failure: RequestBodyFailure,
        failedAtUtc: String,
    ) {
        check(
            transportDao.quarantineRequestBodyBeforeClaim(
                endpointId = request.endpointId,
                requestIdentity = request.requestIdentity,
                expectedHmacKeyGeneration = request.hmacKeyGeneration,
                expectedHmac = request.rawBodyHmac,
                expectedState = request.state,
                expectedAttemptCount = request.attemptCount,
                expectedActiveAttemptId = request.activeAttemptId,
                expectedLeaseExpiresAtEpochMs = request.leaseExpiresAtEpochMs,
                expectedUpdatedAtUtc = request.updatedAtUtc,
                failedAtUtc = failedAtUtc,
                failureCode = failure.storageCode,
            ) == 1,
        ) {
            "Durable request integrity quarantine lost its exact preclaim CAS"
        }
        if (request.endpointId == M2Endpoint.AUTH_REVOKE.endpointId) {
            quarantineCurrentRevokeFamilyForIntegrity(
                authDao = authDao,
                credentialEpochId = request.credentialEpochId,
                deviceId = request.deviceId,
                failedAtUtc = failedAtUtc,
                failureCode = failure.storageCode,
            )
        } else {
            val stream = replicaDao.findStreamState()
            if (
                stream?.credentialEpochId == request.credentialEpochId &&
                stream.deviceId == request.deviceId
            ) {
                if (stream.integrityErrorCode == null) {
                    check(
                        replicaDao.markIntegrityHalted(
                            credentialEpochId = request.credentialEpochId,
                            deviceId = request.deviceId,
                            errorCode = failure.storageCode,
                            updatedAtUtc = failedAtUtc,
                        ) == 1,
                    ) {
                        "Request-body integrity halt lost its exact stream"
                    }
                }
            }
        }
    }

    private suspend fun quarantineInvalidMetadataBeforeClaim(
        request: SyncRequestIntegrityRecoverySnapshot,
        failedAtUtc: String,
    ) {
        check(
            transportDao.quarantineRequestIntegrityMetadata(
                endpointId = request.endpointId,
                requestIdentity = request.requestIdentity,
                expectedHmacKeyGenerationStorageClass =
                    request.hmacKeyGenerationStorageClass,
                expectedHmacKeyGenerationQuoted = request.hmacKeyGenerationQuoted,
                expectedHmacStorageClass = request.rawBodyHmacStorageClass,
                expectedHmacHex = request.rawBodyHmacHex,
                expectedState = request.state,
                expectedAttemptCountStorageClass = request.attemptCountStorageClass,
                expectedAttemptCountQuoted = request.attemptCountQuoted,
                expectedActiveAttemptId = request.activeAttemptId,
                expectedLeaseExpiresAtEpochMs = request.leaseExpiresAtEpochMs,
                expectedUpdatedAtUtc = request.updatedAtUtc,
                failedAtUtc = failedAtUtc,
                failureCode = RequestBodyFailure.METADATA_INVALID.storageCode,
            ) == 1,
        ) {
            "Invalid durable request metadata lost its exact preclaim CAS"
        }
        if (request.endpointId == M2Endpoint.AUTH_REVOKE.endpointId) {
            quarantineCurrentRevokeFamilyForIntegrity(
                authDao = authDao,
                credentialEpochId = request.credentialEpochId,
                deviceId = request.deviceId,
                failedAtUtc = failedAtUtc,
                failureCode = RequestBodyFailure.METADATA_INVALID.storageCode,
            )
        } else {
            val stream = replicaDao.findStreamState()
            if (
                stream?.credentialEpochId == request.credentialEpochId &&
                stream.deviceId == request.deviceId &&
                stream.integrityErrorCode == null
            ) {
                check(
                    replicaDao.markIntegrityHalted(
                        credentialEpochId = request.credentialEpochId,
                        deviceId = request.deviceId,
                        errorCode = RequestBodyFailure.METADATA_INVALID.storageCode,
                        updatedAtUtc = failedAtUtc,
                    ) == 1,
                ) {
                    "Invalid request metadata could not halt its exact stream"
                }
            }
        }
    }

    private suspend fun requirePushMembership(
        request: SyncHttpRequestEntity,
        verified: VerifiedDurableRequest,
    ) {
        val evidence = verified.inspectBody(WireRequestCodec::decodeDurablePushEvidence)
        val batch = transportDao.findBatch(request.requestIdentity)
            ?: throw DurableMembershipInvalidException()
        val items = transportDao.findBatchItems(batch.batchId)
        if (!(
            evidence.batchId == request.requestIdentity &&
                evidence.deviceId == request.deviceId &&
                evidence.batchContentSha256 == batch.batchContentSha256 &&
                batch.endpointId == request.endpointId &&
                batch.requestIdentity == request.requestIdentity &&
                batch.operationCount == evidence.items.size &&
                items.size == evidence.items.size
        )) {
            throw DurableMembershipInvalidException()
        }
        evidence.items.zip(items).forEach { (wire, item) ->
            val outbox = mutationDao.findOutbox(item.operationId)
                ?: throw DurableMembershipInvalidException()
            if (!(
                wire.ordinal == item.ordinal &&
                    wire.clientSequence == item.localSequence &&
                    wire.operationId == item.operationId &&
                    wire.operationContentSha256 == item.wireOperationContentSha256 &&
                    outbox.localSequence == item.localSequence &&
                    outbox.operationId == item.operationId &&
                    outbox.activeBatchId == batch.batchId &&
                    outbox.state == "batched" &&
                    outbox.wireOperationContentSha256 == item.wireOperationContentSha256
            )) {
                throw DurableMembershipInvalidException()
            }
        }
    }

    private suspend fun requireDurableSyncMembership(
        request: SyncHttpRequestEntity,
        verified: VerifiedDurableRequest,
    ) {
        val stream = replicaDao.findStreamState()
            ?: throw DurableMembershipInvalidException()
        val streamMatchesEndpoint = when (verified.endpoint) {
            M2Endpoint.SYNC_PUSH ->
                stream.phase == "incremental" && !stream.bootstrapRequired
            M2Endpoint.SYNC_BOOTSTRAP ->
                stream.phase == "bootstrap_required" && stream.bootstrapRequired
            M2Endpoint.SYNC_PULL ->
                stream.phase in setOf("incremental", "pulling") && !stream.bootstrapRequired
            else -> false
        }
        if (!(
            stream.credentialEpochId == request.credentialEpochId &&
                stream.deviceId == request.deviceId &&
                stream.integrityErrorCode == null &&
                stream.phase != "integrity_halted" &&
                streamMatchesEndpoint
        )) {
            throw DurableMembershipInvalidException()
        }
        when (verified.endpoint) {
            M2Endpoint.SYNC_PUSH -> requirePushMembership(request, verified)
            M2Endpoint.SYNC_BOOTSTRAP -> requireBootstrapMembership(request, verified)
            M2Endpoint.SYNC_PULL -> requirePullMembership(request, verified)
            else -> throw DurableMembershipInvalidException()
        }
    }

    private suspend fun verifyExistingBootstrapCandidates(
        session: SyncBootstrapSessionEntity,
        failedAtUtc: String,
    ): Boolean {
        val currentAuth = authDao.findState()
        if (
            currentAuth == null ||
            currentAuth.credentialEpochId != session.credentialEpochId ||
            currentAuth.deviceId != session.deviceId ||
            currentAuth.state !in setOf("active", "refresh_in_flight")
        ) {
            return false
        }
        val candidates = transportDao.findOpenBootstrapRequestKeys(
            credentialEpochId = session.credentialEpochId,
            deviceId = session.deviceId,
        )
        for (candidate in candidates) {
            val integritySnapshot = checkNotNull(
                transportDao.findRequestIntegrityRecoverySnapshot(
                    candidate.endpointId,
                    candidate.requestIdentity,
                ),
            ) {
                "Open bootstrap candidate disappeared inside its transaction"
            }
            if (
                !integritySnapshot.hasCanonicalHmacStorage ||
                !integritySnapshot.hasCanonicalHmacKeyGeneration ||
                !integritySnapshot.hasCanonicalAccessGeneration ||
                !integritySnapshot.hasCanonicalAttemptCount ||
                checkNotNull(integritySnapshot.accessGenerationUsed) >
                currentAuth.generation
            ) {
                quarantineInvalidMetadataBeforeClaim(integritySnapshot, failedAtUtc)
                return false
            }
            val request = checkNotNull(
                transportDao.findRequest(
                    candidate.endpointId,
                    candidate.requestIdentity,
                ),
            ) {
                "Authenticated bootstrap candidate disappeared inside its transaction"
            }
            val verified = try {
                verifier.loadVerified(request)
            } catch (error: Exception) {
                val failure = error.toRequestBodyFailureOrNull() ?: throw error
                quarantineBeforeClaim(request, failure, failedAtUtc)
                return false
            }
            try {
                requireBootstrapMembership(request, verified)
            } catch (error: DurableMembershipInvalidException) {
                quarantineBeforeClaim(
                    request,
                    RequestBodyFailure.METADATA_INVALID,
                    failedAtUtc,
                )
                return false
            } finally {
                verified.close()
            }
        }
        return true
    }

    private suspend fun requireBootstrapMembership(
        request: SyncHttpRequestEntity,
        verified: VerifiedDurableRequest,
    ) {
        val session = replicaDao.findBootstrapSessionWithActiveSlot()
            ?: throw DurableMembershipInvalidException()
        val evidence = verified.inspectBody(WireRequestCodec::decodeDurableBootstrapEvidence)
        if (!(
            session.state == "staging" &&
                session.activeSlot == 1 &&
                session.credentialEpochId == request.credentialEpochId &&
                session.deviceId == request.deviceId &&
                evidence.requestId == request.requestIdentity &&
                evidence.bootstrapId == session.bootstrapId &&
                evidence.deviceId == session.deviceId &&
                evidence.pageCursor == session.nextPageCursor
        )) {
            throw DurableMembershipInvalidException()
        }
    }

    private suspend fun requirePullMembership(
        request: SyncHttpRequestEntity,
        verified: VerifiedDurableRequest,
    ) {
        val stream = replicaDao.findStreamState()
            ?: throw DurableMembershipInvalidException()
        val evidence = verified.inspectBody(WireRequestCodec::decodeDurablePullEvidence)
        if (!(
            stream.credentialEpochId == request.credentialEpochId &&
                stream.deviceId == request.deviceId &&
                stream.integrityErrorCode == null &&
                !stream.bootstrapRequired &&
                stream.phase in setOf("incremental", "pulling") &&
                replicaDao.findBootstrapSessionWithActiveSlot() == null &&
                evidence.requestId == request.requestIdentity &&
                evidence.deviceId == stream.deviceId &&
                evidence.cursor == stream.appliedCursor
        )) {
            throw DurableMembershipInvalidException()
        }
    }

    private suspend fun prepareNewInCurrentTransaction(
        request: M2WireRequest,
        persistence: NewDurableRequestPersistence,
    ): SyncHttpRequestEntity {
        require(
            request.endpoint.durableExactReplay &&
                CANONICAL_UUID_PATTERN.matches(request.correlationId),
        ) {
            "Durable request correlation is invalid"
        }
        check(
            transportDao.findRequest(
                request.endpoint.endpointId,
                request.correlationId,
            ) == null,
        ) {
            "Durable request identity is already persisted"
        }
        keyring.provisionCurrentKey(
            durableReferenceCount = transportDao.countRequestsReferencingHmacGeneration(
                CURRENT_HMAC_KEY_GENERATION,
            ),
        )
        return protector.protectNew(request, persistence)
    }

    private suspend fun requirePullPersistenceBinding(request: SyncHttpRequestEntity) {
        val stream = checkNotNull(replicaDao.findStreamState()) {
            "A protected pull request requires an active stream"
        }
        val auth = checkNotNull(authDao.findState()) {
            "A protected pull request requires active credentials"
        }
        val body = checkNotNull(request.rawRequestBody).copyOf()
        val evidence = try {
            WireRequestCodec.decodeDurablePullEvidence(body)
        } finally {
            body.fill(0)
        }
        check(
            stream.credentialEpochId == request.credentialEpochId &&
                stream.deviceId == request.deviceId &&
                stream.integrityErrorCode == null &&
                !stream.bootstrapRequired &&
                stream.phase in setOf("incremental", "pulling") &&
                replicaDao.findBootstrapSessionWithActiveSlot() == null &&
                auth.credentialEpochId == request.credentialEpochId &&
                auth.deviceId == request.deviceId &&
                auth.generation == request.accessGenerationUsed &&
                auth.state == "active" &&
                !auth.bootstrapRequired &&
                evidence.requestId == request.requestIdentity &&
                evidence.deviceId == request.deviceId &&
                evidence.cursor == stream.appliedCursor,
        ) {
            "Protected pull request does not bind the current stream cursor"
        }
    }

    private fun requireBootstrapPersistenceBinding(
        request: SyncHttpRequestEntity,
        session: SyncBootstrapSessionEntity,
    ) {
        val body = checkNotNull(request.rawRequestBody).copyOf()
        val evidence = try {
            WireRequestCodec.decodeDurableBootstrapEvidence(body)
        } finally {
            body.fill(0)
        }
        check(
            request.endpointId == M2Endpoint.SYNC_BOOTSTRAP.endpointId &&
                request.credentialEpochId == session.credentialEpochId &&
                request.deviceId == session.deviceId &&
                evidence.requestId == request.requestIdentity &&
                evidence.bootstrapId == session.bootstrapId &&
                evidence.deviceId == session.deviceId &&
                evidence.pageCursor == session.nextPageCursor,
        ) {
            "Protected bootstrap request does not bind its authoritative session"
        }
    }

    private suspend fun requirePushPersistenceRows(
        request: SyncHttpRequestEntity,
        batch: SyncPushBatchEntity,
        items: List<SyncPushBatchItemEntity>,
    ) {
        val body = checkNotNull(request.rawRequestBody).copyOf()
        val evidence = try {
            WireRequestCodec.decodeDurablePushEvidence(body)
        } finally {
            body.fill(0)
        }
        check(
            evidence.batchId == request.requestIdentity &&
                evidence.deviceId == request.deviceId &&
                evidence.batchContentSha256 == batch.batchContentSha256 &&
                batch.batchId == request.requestIdentity &&
                batch.endpointId == request.endpointId &&
                batch.requestIdentity == request.requestIdentity &&
                batch.operationCount == items.size &&
                evidence.items.size == items.size,
        ) {
            "Protected push body does not bind supplied persistence rows"
        }
        evidence.items.zip(items).forEach { (wire, item) ->
            check(
                wire.ordinal == item.ordinal &&
                    wire.clientSequence == item.localSequence &&
                    wire.operationId == item.operationId &&
                    wire.operationContentSha256 == item.wireOperationContentSha256 &&
                    item.batchId == batch.batchId,
            ) {
                "Protected push operation does not bind supplied persistence row"
            }
        }
    }

    private suspend inline fun <T> withPreparedRequest(
        request: M2WireRequest,
        persistence: NewDurableRequestPersistence,
        crossinline block: suspend (SyncHttpRequestEntity) -> T,
    ): T = consumeRequest(request) {
        var transient: SyncHttpRequestEntity? = null
        try {
            database.withTransaction {
                val protected = prepareNewInCurrentTransaction(request, persistence)
                transient = protected
                block(protected)
            }
        } finally {
            transient?.wipeTransientProtectionBuffers()
        }
    }
}

private suspend inline fun <T> consumeRequest(
    request: M2WireRequest,
    crossinline block: suspend () -> T,
): T = try {
    block()
} finally {
    (request as? AutoCloseable)?.close()
}

internal sealed interface ProtectedRequestClaim {
    class Claimed(
        val request: VerifiedDurableRequest,
    ) : ProtectedRequestClaim {
        override fun toString(): String = "ProtectedRequestClaim.Claimed(redacted=true)"
    }

    data object NotClaimed : ProtectedRequestClaim

    class IntegrityFailure(
        val failure: RequestBodyFailure,
    ) : ProtectedRequestClaim {
        override fun toString(): String =
            "ProtectedRequestClaim.IntegrityFailure(failure=$failure,redacted=true)"
    }
}

internal class PersistedDurableRequestRef(
    val endpointId: String,
    val requestIdentity: String,
) {
    override fun toString(): String =
        "PersistedDurableRequestRef(endpoint=$endpointId,redacted=true)"
}

internal enum class RequestBodyFailure(
    val storageCode: String,
) {
    KEY_UNAVAILABLE("request_body_key_unavailable"),
    METADATA_INVALID("request_body_metadata_invalid"),
    AEAD_AUTH_FAILED("request_body_aead_authentication_failed"),
    HMAC_MISMATCH("request_body_hmac_mismatch"),
}

private fun Exception.toRequestBodyFailureOrNull(): RequestBodyFailure? = when (this) {
    is RequestBodyKeyUnavailableException -> RequestBodyFailure.KEY_UNAVAILABLE
    is RequestBodyHmacMismatchException -> RequestBodyFailure.HMAC_MISMATCH
    is RequestBodyMetadataInvalidException -> RequestBodyFailure.METADATA_INVALID
    is SensitivePayloadIntegrityException -> when (failure) {
        ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE ->
            RequestBodyFailure.KEY_UNAVAILABLE
        ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityFailure.METADATA_INVALID ->
            RequestBodyFailure.METADATA_INVALID
        ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityFailure.AEAD_AUTH_FAILED ->
            RequestBodyFailure.AEAD_AUTH_FAILED
    }
    else -> null
}

private fun SyncHttpRequestEntity.isEligiblePreclaimSnapshot(nowEpochMs: Long): Boolean =
    attemptCount < attemptBudget &&
        nowEpochMs < deadlineAtEpochMs &&
        when (state) {
            "ready" ->
                nextAttemptAtEpochMs == null &&
                    activeAttemptId == null &&
                    leaseExpiresAtEpochMs == null

            "retry_wait" ->
                nextAttemptAtEpochMs != null &&
                    nextAttemptAtEpochMs <= nowEpochMs &&
                    activeAttemptId == null &&
                    leaseExpiresAtEpochMs == null

            "sending" ->
                activeAttemptId != null &&
                    leaseExpiresAtEpochMs != null &&
                    leaseExpiresAtEpochMs <= nowEpochMs

            else -> false
        }

private val CANONICAL_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

private val OPEN_REQUEST_STATES = setOf(
    "ready",
    "retry_wait",
    "sending",
    "waiting_refresh",
)

private fun SyncHttpRequestEntity.toPersistedRef() = PersistedDurableRequestRef(
    endpointId = endpointId,
    requestIdentity = requestIdentity,
)

private fun SyncHttpRequestEntity.wipeTransientProtectionBuffers() {
    rawRequestBody?.fill(0)
    sealedBodyCiphertext?.fill(0)
    sealedBodyNonce?.fill(0)
    rawBodyHmac.fill(0)
}

private class DurableMembershipInvalidException :
    IllegalStateException("Protected durable request membership is invalid")
