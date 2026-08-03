package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.withTransaction
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncRunnableRequestCandidate
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncReplicaCursorEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestVerifier
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.RequestBodyHmacMismatchException
import ru.andriyshkoy.lifeagent.data.security.RequestBodyKeyUnavailableException
import ru.andriyshkoy.lifeagent.data.security.RequestBodyMetadataInvalidException
import ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityException
import ru.andriyshkoy.lifeagent.data.security.VerifiedDurableRequest
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenVault
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsNetworkFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsNetworkFailureKind
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsOutcome
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsProtocolFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsRawResponse
import ru.andriyshkoy.lifeagent.data.sync.transport.LazyProductionM2HttpsTransportBundle
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.ApiErrorCode
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapApiErrorExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.DecodedApiError
import ru.andriyshkoy.lifeagent.data.sync.wire.FreshBootstrapPageExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.FreshPullPageExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.PullApiErrorExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PushBatchSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.PushOperationAck
import ru.andriyshkoy.lifeagent.data.sync.wire.PushOperationError
import ru.andriyshkoy.lifeagent.data.sync.wire.PushOperationResult
import ru.andriyshkoy.lifeagent.data.sync.wire.PushResponseExpectation
import ru.andriyshkoy.lifeagent.data.sync.wire.RevokeSuccess
import ru.andriyshkoy.lifeagent.data.sync.wire.WireApiError
import ru.andriyshkoy.lifeagent.data.sync.wire.WireReplicaPersistenceMapper
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec
import ru.andriyshkoy.lifeagent.data.sync.wire.WireResponseCodec

/**
 * Closed production dispatch surface for one body-free durable candidate.
 *
 * Callers provide scheduling metadata only. Exact request bytes, bearer
 * authority, transport outcomes and response bodies never cross this port.
 */
internal fun interface ProtectedDurableDispatchPort {
    suspend fun dispatch(
        candidate: SyncRunnableRequestCandidate,
        attemptId: String,
        attemptedAtUtc: String,
        leaseExpiresAtEpochMs: Long,
    ): ProtectedDurableDispatchResult
}

/** Content-free coordinator disposition; no implementation carries identifiers. */
internal enum class ProtectedDurableDispatchResult {
    PROGRESSED,
    PULL_CONTINUATION_READY,
    PULL_CYCLE_COMPLETE,
    RETRY_LATER,
    USER_ACTION_REQUIRED,
    NO_PROGRESS,
}

/**
 * Creates the production bridge without opening the pinned client or touching
 * Room/Keystore. The lazy bundle is opened only for an actual dispatch call.
 */
internal fun createProductionProtectedDurableDispatchPort(
    context: Context,
    database: LifeAgentDatabase,
    bootstrapIntents: ProtectedBootstrapIntentBoundary,
    accessTokenVault: AccessTokenVault,
    transports: LazyProductionM2HttpsTransportBundle =
        LazyProductionM2HttpsTransportBundle(),
    responseStore: ProtectedSyncResponseStore = ProtectedSyncResponseStore(
        context = context,
        database = database,
        bootstrapIntents = bootstrapIntents,
    ),
    completionClock: Clock = Clock.systemUTC(),
): ProtectedDurableDispatchPort {
    val requests = ProtectedSyncRequestStore(context, database)
    return ProductionProtectedDurableDispatchPort(
        exchangeProvider = {
            val exact = transports.open().exact
            ProtectedDurableExactExchange { claim, bearer ->
                exact.execute(claim, bearer)
            }
        },
        claims = ProtectedDurableDispatchClaimBoundary { candidate, attemptId,
                attemptedAtEpochMs, leaseExpiresAtEpochMs, attemptedAtUtc ->
            requests.verifyAndClaimForDispatch(
                endpointId = candidate.endpointId,
                requestIdentity = candidate.requestIdentity,
                attemptId = attemptId,
                attemptedAtEpochMs = attemptedAtEpochMs,
                leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                updatedAtUtc = attemptedAtUtc,
                accessTokenVault = accessTokenVault,
            )
        },
        responses = object : ProtectedDurableDispatchResponseBoundary {
            override suspend fun reduce(
                outcome: ExactHttpsNetworkFailure,
                terminalAtUtc: String,
            ): ProtectedResponseDisposition =
                responseStore.reduceRetryableFailure(outcome, terminalAtUtc)

            override suspend fun reduce(
                outcome: ExactHttpsProtocolFailure,
                terminalAtUtc: String,
            ): ProtectedResponseDisposition =
                responseStore.reduceProtocolFailure(outcome, terminalAtUtc)

            override suspend fun reduce(
                outcome: ExactHttpsRawResponse,
                terminalAtUtc: String,
            ): ProtectedResponseDisposition =
                responseStore.reduceRawResponse(outcome, terminalAtUtc)
        },
        accessTokenVault = accessTokenVault,
        completionClock = completionClock,
    )
}

/**
 * Owns the complete exact dispatch lifecycle, including every close/wipe path.
 * Network readiness is resolved before the protected attempt CAS so missing
 * configuration cannot consume a durable attempt.
 */
internal class ProductionProtectedDurableDispatchPort internal constructor(
    private val exchangeProvider: () -> ProtectedDurableExactExchange?,
    private val claims: ProtectedDurableDispatchClaimBoundary,
    private val responses: ProtectedDurableDispatchResponseBoundary,
    private val accessTokenVault: AccessTokenVault,
    private val completionClock: Clock,
) : ProtectedDurableDispatchPort {
    override suspend fun dispatch(
        candidate: SyncRunnableRequestCandidate,
        attemptId: String,
        attemptedAtUtc: String,
        leaseExpiresAtEpochMs: Long,
    ): ProtectedDurableDispatchResult {
        val attemptedAt = requireValidDispatchAttempt(
            candidate = candidate,
            attemptId = attemptId,
            attemptedAtUtc = attemptedAtUtc,
            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
        )
        val exchange = try {
            exchangeProvider()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return ProtectedDurableDispatchResult.USER_ACTION_REQUIRED

        return when (
            val claim = claims.claim(
                candidate = candidate,
                attemptId = attemptId,
                attemptedAtEpochMs = attemptedAt.toEpochMilli(),
                leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                attemptedAtUtc = attemptedAtUtc,
            )
        ) {
            is ProtectedDispatchRequestClaim.Claimed -> claim.use {
                exchangeClaimed(candidate, attemptedAt, exchange, claim)
            }

            is ProtectedDispatchRequestClaim.IntegrityFailure ->
                ProtectedDurableDispatchResult.USER_ACTION_REQUIRED

            ProtectedDispatchRequestClaim.NotClaimed ->
                ProtectedDurableDispatchResult.NO_PROGRESS
        }
    }

    private suspend fun exchangeClaimed(
        candidate: SyncRunnableRequestCandidate,
        attemptedAt: Instant,
        exchange: ProtectedDurableExactExchange,
        claim: ProtectedDispatchRequestClaim.Claimed,
    ): ProtectedDurableDispatchResult {
        val accessTokenKey = claim.accessTokenClaim?.key
        val outcome = exchange.execute(
            claim = claim.requestClaim,
            bearerAccessToken = claim.accessTokenClaim?.bearerAccessToken,
        )
        val disposition = outcome.use {
            val completedAt = completionClock.instant().let { now ->
                if (now.isBefore(attemptedAt)) attemptedAt else now
            }.toString()
            when (outcome) {
                is ExactHttpsNetworkFailure -> responses.reduce(outcome, completedAt)
                is ExactHttpsProtocolFailure -> responses.reduce(outcome, completedAt)
                is ExactHttpsRawResponse -> responses.reduce(outcome, completedAt)
            }
        }
        if (disposition == ProtectedResponseDisposition.REFRESH_REQUIRED) {
            accessTokenVault.revoke(
                checkNotNull(accessTokenKey) {
                    "Trusted unauthorized response lost exact bearer authority"
                },
            )
        }
        return disposition.toDispatchResult(candidate.endpointId)
    }

    override fun toString(): String =
        "ProductionProtectedDurableDispatchPort(redacted=true)"
}

internal fun interface ProtectedDurableExactExchange {
    suspend fun execute(
        claim: ProtectedRequestClaim.Claimed,
        bearerAccessToken: WipeableSecret?,
    ): ExactHttpsOutcome
}

internal fun interface ProtectedDurableDispatchClaimBoundary {
    suspend fun claim(
        candidate: SyncRunnableRequestCandidate,
        attemptId: String,
        attemptedAtEpochMs: Long,
        leaseExpiresAtEpochMs: Long,
        attemptedAtUtc: String,
    ): ProtectedDispatchRequestClaim
}

internal interface ProtectedDurableDispatchResponseBoundary {
    suspend fun reduce(
        outcome: ExactHttpsNetworkFailure,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition

    suspend fun reduce(
        outcome: ExactHttpsProtocolFailure,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition

    suspend fun reduce(
        outcome: ExactHttpsRawResponse,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition
}

/**
 * The only Room boundary allowed to reduce a response for a claimed durable
 * request.
 *
 * A stale or superseded callback is classified without touching either body
 * and without consulting Android Keystore. A terminal replay compares its raw
 * status, body and digest in SQL. Only a fresh authoritative attempt reaches
 * typed Room hydration, exact-request verification and the strict decoder.
 */
internal class ProtectedSyncResponseStore(
    context: Context,
    private val database: LifeAgentDatabase,
    private val bootstrapIntents: ProtectedBootstrapIntentBoundary,
    keyring: KeystoreRequestBodyHmacKeyring = KeystoreRequestBodyHmacKeyring(context),
) {
    private val decoder: ProtectedFreshResponseDecoder =
        ProductionProtectedFreshResponseDecoder
    private val verifier = DurableSyncRequestVerifier(context, keyring)
    private val authDao = database.syncAuthDao()
    private val mutationDao = database.noteMutationDao()
    private val replicaDao = database.syncReplicaDao()
    private val transportDao = database.syncTransportDao()
    private val persistenceStore = SyncPersistenceStore(database)
    private val requestStore = SyncRequestPersistenceStore(database)
    private val authStore = SyncAuthPersistenceStore(database)

    suspend fun reduceRetryableFailure(
        outcome: ExactHttpsNetworkFailure,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition = try {
        val failureCode = when (outcome.kind) {
            ExactHttpsNetworkFailureKind.IO -> "transport_io"
            ExactHttpsNetworkFailureKind.TIMEOUT -> "transport_timeout"
            ExactHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS ->
                "transport_http_${checkNotNull(outcome.httpStatus)}"
        }
        reduceBodylessFreshOutcome(
            claim = outcome.claim,
            terminalAtUtc = terminalAtUtc,
        ) { request, _ ->
            val nowEpochMs = Instant.parse(terminalAtUtc).toEpochMilli()
            scheduleRetryOrTerminalizeInCurrentTransaction(
                request = request,
                claim = outcome.claim,
                nowEpochMs = nowEpochMs,
                retryAfterSeconds = null,
                failureCode = failureCode,
                terminalAtUtc = terminalAtUtc,
            )
        }
    } finally {
        outcome.close()
    }

    suspend fun reduceProtocolFailure(
        outcome: ExactHttpsProtocolFailure,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition = try {
        val failureCode = "transport_protocol_${outcome.kind.name.lowercase()}"
        reduceBodylessFreshOutcome(
            claim = outcome.claim,
            terminalAtUtc = terminalAtUtc,
        ) { request, _ ->
            requireResponseIntegrity(
                transportDao.quarantineFreshResponseMetadata(
                    endpointId = request.endpointId,
                    requestIdentity = request.requestIdentity,
                    credentialEpochId = outcome.claim.credentialEpochId,
                    accessGenerationUsed = outcome.claim.accessGenerationUsed,
                    expectedAttemptId = outcome.claim.attemptId,
                    failedAtUtc = terminalAtUtc,
                    failureCode = failureCode,
                ) == 1,
                "protocol_failure_quarantine_lost",
                "Protocol failure lost its exact attempt quarantine",
            )
            quarantineCurrentRoute(
                endpoint = outcome.claim.request.endpoint,
                credentialEpochId = request.credentialEpochId,
                deviceId = request.deviceId,
                accessGenerationUsed = outcome.claim.accessGenerationUsed,
                failedAtUtc = terminalAtUtc,
                failure = RequestBodyFailure.METADATA_INVALID,
                storedFailureCode = failureCode,
            )
            ProtectedResponseDisposition.QUARANTINED
        }
    } finally {
        outcome.close()
    }

    suspend fun reduceRawResponse(
        outcome: ExactHttpsRawResponse,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition = try {
        reduceRawResponseOwned(outcome, terminalAtUtc)
    } finally {
        outcome.close()
    }

    private suspend fun reduceRawResponseOwned(
        outcome: ExactHttpsRawResponse,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition {
        Instant.parse(terminalAtUtc)
        val claim = outcome.claim
        return try {
            val preliminary = database.withTransaction {
                classifyBodyBlind(claim)
            }
            if (!preliminary.terminalReplayPending && preliminary.disposition != null) {
                checkNotNull(preliminary.disposition)
            } else {
                outcome.consumeBody { exactResponseBody ->
                    database.withTransaction {
                        val classification = classifyBodyBlind(claim)
                        if (classification.terminalReplayPending) {
                            return@withTransaction requireExactTerminalReplay(
                                claim = claim,
                                httpStatus = outcome.httpStatus,
                                exactResponseBody = exactResponseBody,
                            )
                        }
                        classification.disposition?.let { disposition ->
                            return@withTransaction disposition
                        }
                        reduceFreshInCurrentTransaction(
                            claim = claim,
                            httpStatus = outcome.httpStatus,
                            retryAfterSeconds = outcome.retryAfterSeconds,
                            exactResponseBody = exactResponseBody,
                            terminalAtUtc = terminalAtUtc,
                            snapshot = checkNotNull(classification.snapshot),
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ReplicaIntegrityException) {
            haltAfterRollback(claim, error, terminalAtUtc)
            throw error
        } catch (error: Exception) {
            val integrity = ReplicaIntegrityException(
                errorCode = "protected_response_reduction_failed",
                message = "Verified durable response could not reduce atomically",
                cause = error,
            )
            haltAfterRollback(claim, integrity, terminalAtUtc)
            throw integrity
        }
    }

    private suspend fun reduceBodylessFreshOutcome(
        claim: ProtectedRequestClaim.Claimed,
        terminalAtUtc: String,
        reducer: suspend (SyncHttpRequestEntity, VerifiedDurableRequest) ->
        ProtectedResponseDisposition,
    ): ProtectedResponseDisposition {
        Instant.parse(terminalAtUtc)
        return try {
            val preliminary = database.withTransaction { classifyBodyBlind(claim) }
            when {
                preliminary.terminalReplayPending ->
                    ProtectedResponseDisposition.STALE_CALLBACK

                preliminary.disposition != null -> checkNotNull(preliminary.disposition)

                else -> database.withTransaction {
                    val classification = classifyBodyBlind(claim)
                    if (classification.terminalReplayPending) {
                        return@withTransaction ProtectedResponseDisposition.STALE_CALLBACK
                    }
                    classification.disposition?.let { return@withTransaction it }
                    val snapshot = checkNotNull(classification.snapshot)
                    if (
                        !snapshot.hasRoomSafeStorageClasses ||
                        !snapshot.hasRoomSafeEntityShape ||
                        !snapshot.hasFreshResponseMetadataShape
                    ) {
                        quarantineMalformedFreshMetadata(snapshot, claim, terminalAtUtc)
                        return@withTransaction ProtectedResponseDisposition.QUARANTINED
                    }
                    requireFreshClaimProvenance(snapshot, claim)
                    val request = transportDao.findRequest(
                        endpointId = claim.request.endpoint.endpointId,
                        requestIdentity = claim.request.requestIdentity,
                    ) ?: throw ReplicaIntegrityException(
                        errorCode = "sync_request_missing",
                        message = "Fresh protected outcome lost its durable request",
                    )
                    requireFreshClaimProvenance(request, claim)
                    val verified = try {
                        verifier.loadVerified(request)
                    } catch (error: Exception) {
                        val failure = error.toResponseRequestBodyFailureOrNull() ?: throw error
                        quarantineVerifiedRequestFailure(request, claim, terminalAtUtc, failure)
                        return@withTransaction ProtectedResponseDisposition.QUARANTINED
                    }
                    try {
                        requireAuthoritativeMembership(request, verified)
                        reducer(request, verified)
                    } finally {
                        verified.close()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ReplicaIntegrityException) {
            haltAfterRollback(claim, error, terminalAtUtc)
            throw error
        } catch (error: Exception) {
            val integrity = ReplicaIntegrityException(
                errorCode = "protected_response_transition_failed",
                message = "Protected response transition could not commit atomically",
                cause = error,
            )
            haltAfterRollback(claim, integrity, terminalAtUtc)
            throw integrity
        }
    }

    private suspend fun reduceFreshInCurrentTransaction(
        claim: ProtectedRequestClaim.Claimed,
        httpStatus: Int,
        retryAfterSeconds: Int?,
        exactResponseBody: ByteArray,
        terminalAtUtc: String,
        snapshot: ru.andriyshkoy.lifeagent.data.local.db.dao.SyncResponseRouteSnapshot,
    ): ProtectedResponseDisposition {
        if (
            !snapshot.hasRoomSafeStorageClasses ||
            !snapshot.hasRoomSafeEntityShape ||
            !snapshot.hasFreshResponseMetadataShape
        ) {
            quarantineMalformedFreshMetadata(snapshot, claim, terminalAtUtc)
            return ProtectedResponseDisposition.QUARANTINED
        }
        requireFreshClaimProvenance(snapshot, claim)
        val request = transportDao.findRequest(
            endpointId = claim.request.endpoint.endpointId,
            requestIdentity = claim.request.requestIdentity,
        ) ?: throw ReplicaIntegrityException(
            errorCode = "sync_request_missing",
            message = "Fresh protected response lost its durable request",
        )
        requireFreshClaimProvenance(request, claim)

        val verified = try {
            verifier.loadVerified(request)
        } catch (error: Exception) {
            val failure = error.toResponseRequestBodyFailureOrNull() ?: throw error
            quarantineVerifiedRequestFailure(request, claim, terminalAtUtc, failure)
            return ProtectedResponseDisposition.QUARANTINED
        }
        try {
            val replicaPageIndex = requireAuthoritativeMembership(request, verified)
            val responseSha256 = sha256Hex(exactResponseBody)
            val decoderResponseBody = exactResponseBody.copyOf()
            val command = try {
                verified.inspectBody { exactRequestBody ->
                    decoder.decode(
                        ProtectedFreshResponseInput(
                            endpoint = verified.endpoint,
                            requestIdentity = verified.requestIdentity,
                            httpStatus = httpStatus,
                            retryAfterSeconds = retryAfterSeconds,
                            terminalAtUtc = terminalAtUtc,
                            replicaPageIndex = replicaPageIndex,
                            exactRequestBody = exactRequestBody,
                            exactResponseBody = decoderResponseBody,
                        ),
                    )
                }
            } finally {
                decoderResponseBody.fill(0)
            }
            return applyCommandInCurrentTransaction(
                claim = claim,
                request = request,
                command = command,
                response = TerminalHttpResponsePersistence(
                    endpointId = request.endpointId,
                    requestIdentity = request.requestIdentity,
                    expectedAttemptId = claim.attemptId,
                    httpStatus = httpStatus,
                    exactResponseBody = exactResponseBody,
                    responseSha256 = responseSha256,
                    terminalAtUtc = terminalAtUtc,
                    terminalErrorCode = command.terminalErrorCode,
                ),
                retryAfterSeconds = retryAfterSeconds,
            )
        } finally {
            verified.close()
        }
    }

    private suspend fun applyCommandInCurrentTransaction(
        claim: ProtectedRequestClaim.Claimed,
        request: SyncHttpRequestEntity,
        command: ProtectedResponseCommand,
        response: TerminalHttpResponsePersistence,
        retryAfterSeconds: Int?,
    ): ProtectedResponseDisposition {
        check(database.inTransaction())
        return when (command) {
            is ProtectedResponseCommand.PushSuccess -> {
                requireRoute(response, M2Endpoint.SYNC_PUSH, 200, null)
                persistenceStore.commitPushResponseInCurrentTransaction(
                    response,
                    command.results,
                )
                ProtectedResponseDisposition.COMMITTED
            }

            is ProtectedResponseCommand.BootstrapPage -> {
                requireRoute(response, M2Endpoint.SYNC_BOOTSTRAP, 200, null)
                persistenceStore.commitBootstrapPageInCurrentTransaction(
                    response = response,
                    receipt = command.receipt,
                    changes = command.changes,
                    continuationFactory = if (command.receipt.completeOrHasMore) {
                        null
                    } else {
                        { session ->
                            bootstrapIntents.createContinuation(
                                session = session,
                                createdAtUtc = response.terminalAtUtc,
                            )
                        }
                    },
                )
                ProtectedResponseDisposition.COMMITTED
            }

            is ProtectedResponseCommand.PullPage -> {
                requireRoute(response, M2Endpoint.SYNC_PULL, 200, null)
                persistenceStore.commitPullPageInCurrentTransaction(
                    response,
                    command.receipt,
                    command.changes,
                )
                if (command.receipt.completeOrHasMore) {
                    ProtectedResponseDisposition.PULL_CONTINUATION_READY
                } else {
                    ProtectedResponseDisposition.PULL_CYCLE_COMPLETE
                }
            }

            is ProtectedResponseCommand.BootstrapCursorExpired -> {
                requireRoute(
                    response,
                    M2Endpoint.SYNC_BOOTSTRAP,
                    410,
                    ApiErrorCode.CURSOR_EXPIRED.wireName,
                )
                persistenceStore.commitBootstrapCursorExpiredInCurrentTransaction(
                    response = response,
                    bootstrapId = command.expiredBootstrapId,
                    replacementFactory = {
                        bootstrapIntents.createInitial(
                            credentialEpochId = request.credentialEpochId,
                            deviceId = request.deviceId,
                            createdAtUtc = response.terminalAtUtc,
                        )
                    },
                    existingCandidateVerifier = { session ->
                        bootstrapIntents.verifyExistingCandidates(
                            session,
                            response.terminalAtUtc,
                        )
                    },
                )
                ProtectedResponseDisposition.COMMITTED
            }

            ProtectedResponseCommand.CursorInvalid -> {
                require(
                    response.endpointId in setOf(
                        M2Endpoint.SYNC_BOOTSTRAP.endpointId,
                        M2Endpoint.SYNC_PULL.endpointId,
                    ),
                )
                require(response.httpStatus == 400)
                require(response.terminalErrorCode == ApiErrorCode.CURSOR_INVALID.wireName)
                persistenceStore.commitCursorInvalidInCurrentTransaction(response)
                ProtectedResponseDisposition.COMMITTED
            }

            ProtectedResponseCommand.BootstrapRequired -> {
                require(
                    response.endpointId in setOf(
                        M2Endpoint.SYNC_PUSH.endpointId,
                        M2Endpoint.SYNC_PULL.endpointId,
                    ),
                )
                require(response.httpStatus == 409)
                require(response.terminalErrorCode == ApiErrorCode.BOOTSTRAP_REQUIRED.wireName)
                requestStore.commitBootstrapRequiredInCurrentTransaction(
                    response = response,
                    proposedIntentFactory = {
                        bootstrapIntents.createInitial(
                            credentialEpochId = request.credentialEpochId,
                            deviceId = request.deviceId,
                            createdAtUtc = response.terminalAtUtc,
                        )
                    },
                    existingCandidateVerifier = {
                        val session = replicaDao.findBootstrapSessionWithActiveSlot()
                        if (session == null) {
                            true
                        } else {
                            bootstrapIntents.verifyExistingCandidates(
                                session,
                                response.terminalAtUtc,
                            )
                        }
                    },
                )
                ProtectedResponseDisposition.COMMITTED
            }

            ProtectedResponseCommand.TrustedUnauthorized -> {
                require(
                    request.endpointId in setOf(
                        M2Endpoint.SYNC_PUSH.endpointId,
                        M2Endpoint.SYNC_BOOTSTRAP.endpointId,
                        M2Endpoint.SYNC_PULL.endpointId,
                    ),
                )
                require(response.httpStatus == 401)
                require(
                    response.terminalErrorCode == ApiErrorCode.CREDENTIAL_UNAVAILABLE.wireName,
                )
                val nowEpochMs = Instant.parse(response.terminalAtUtc).toEpochMilli()
                when (
                    authStore.handleTrustedSyncUnauthorizedInCurrentTransaction(
                        endpointId = request.endpointId,
                        requestIdentity = request.requestIdentity,
                        expectedAttemptId = claim.attemptId,
                        failedAccessGeneration = claim.accessGenerationUsed,
                        nowEpochMs = nowEpochMs,
                        nextAttemptAtEpochMs = proposedRetryAtEpochMs(
                            request = request,
                            nowEpochMs = nowEpochMs,
                            retryAfterSeconds = retryAfterSeconds,
                        ),
                        updatedAtUtc = response.terminalAtUtc,
                    )
                ) {
                    CredentialRecoveryAction.STALE_CALLBACK ->
                        ProtectedResponseDisposition.STALE_CALLBACK
                    CredentialRecoveryAction.RETRY_WITH_INSTALLED_GENERATION ->
                        ProtectedResponseDisposition.RETRY_SCHEDULED
                    CredentialRecoveryAction.WAITING_FOR_REFRESH ->
                        ProtectedResponseDisposition.REFRESH_REQUIRED
                    CredentialRecoveryAction.QUARANTINED ->
                        ProtectedResponseDisposition.QUARANTINED
                }
            }

            ProtectedResponseCommand.RevokeSuccess,
            ProtectedResponseCommand.RevokeCredentialUnavailable,
            -> {
                val expectedStatus = if (
                    command == ProtectedResponseCommand.RevokeSuccess
                ) {
                    200
                } else {
                    401
                }
                val expectedError = if (
                    command == ProtectedResponseCommand.RevokeSuccess
                ) {
                    null
                } else {
                    ApiErrorCode.CREDENTIAL_UNAVAILABLE.wireName
                }
                requireRoute(response, M2Endpoint.AUTH_REVOKE, expectedStatus, expectedError)
                authStore.commitRevokeTerminalInCurrentTransaction(response)
                ProtectedResponseDisposition.COMMITTED
            }

            is ProtectedResponseCommand.RetryableApiError -> {
                val endpoint = checkNotNull(M2Endpoint.fromId(request.endpointId))
                val policy = endpoint.policyFor(response.httpStatus, command.errorCode)
                require(policy?.retryable == true)
                require(response.terminalErrorCode == command.errorCode.wireName)
                val nowEpochMs = Instant.parse(response.terminalAtUtc).toEpochMilli()
                scheduleRetryOrTerminalizeInCurrentTransaction(
                    request = request,
                    claim = claim,
                    nowEpochMs = nowEpochMs,
                    retryAfterSeconds = retryAfterSeconds,
                    failureCode = command.errorCode.wireName,
                    terminalAtUtc = response.terminalAtUtc,
                )
            }

            is ProtectedResponseCommand.PermanentApiError -> {
                val endpoint = checkNotNull(M2Endpoint.fromId(request.endpointId))
                val policy = endpoint.policyFor(response.httpStatus, command.errorCode)
                require(policy != null && !policy.retryable)
                require(response.terminalErrorCode == command.errorCode.wireName)
                throw ReplicaIntegrityException(
                    errorCode = "terminal_api_error_${command.errorCode.wireName}",
                    message = "Permanent API error requires explicit manual recovery",
                )
            }
        }
    }

    private suspend fun classifyBodyBlind(
        claim: ProtectedRequestClaim.Claimed,
    ): BodyBlindClassification {
        val endpoint = claim.request.endpoint
        val requestIdentity = claim.request.requestIdentity
        val snapshot = transportDao.findResponseRouteSnapshot(
            endpointId = endpoint.endpointId,
            requestIdentity = requestIdentity,
            expectedAttemptId = claim.attemptId,
        ) ?: return BodyBlindClassification(ProtectedResponseDisposition.STALE_CALLBACK)
        if (snapshot.state == "terminal") {
            return BodyBlindClassification(
                snapshot = snapshot,
                terminalReplayPending = true,
            )
        }
        if (snapshot.state in LOCALLY_TERMINAL_REQUEST_STATES) {
            return BodyBlindClassification(ProtectedResponseDisposition.SUPERSEDED)
        }
        if (
            snapshot.state in NON_SENDING_OPEN_REQUEST_STATES &&
            snapshot.activeAttemptId == null
        ) {
            return BodyBlindClassification(ProtectedResponseDisposition.STALE_CALLBACK)
        }
        if (
            snapshot.activeAttemptId != null &&
            snapshot.activeAttemptId != claim.attemptId
        ) {
            return BodyBlindClassification(ProtectedResponseDisposition.STALE_CALLBACK)
        }
        if (
            snapshot.state != "sending" ||
            snapshot.activeAttemptId != claim.attemptId
        ) {
            // An exact route with an unprojectable state/attempt, or a row that
            // still names this attempt in an impossible state, is malformed.
            // It must reach the guarded quarantine/halt path instead of being
            // mistaken for an ordinary stale callback.
            return BodyBlindClassification(snapshot = snapshot)
        }
        // A row that still belongs to the exact active attempt must reach the
        // metadata quarantine before any nullable guarded projection can make
        // it look like a legitimately superseded route.
        if (
            !snapshot.hasRoomSafeStorageClasses ||
            !snapshot.hasRoomSafeEntityShape ||
            !snapshot.hasFreshResponseMetadataShape
        ) {
            return BodyBlindClassification(snapshot = snapshot)
        }
        if (
            snapshot.endpointId != endpoint.endpointId ||
            snapshot.requestIdentity != requestIdentity ||
            snapshot.credentialEpochId != claim.credentialEpochId ||
            snapshot.accessGenerationUsed != claim.accessGenerationUsed
        ) {
            return BodyBlindClassification(snapshot = snapshot)
        }
        val routeStillAuthoritative = when (endpoint) {
            M2Endpoint.AUTH_REVOKE -> authDao.findState()?.let { auth ->
                auth.credentialEpochId == claim.credentialEpochId &&
                    auth.generation == claim.accessGenerationUsed &&
                    auth.state == "revoke_pending"
            } == true

            M2Endpoint.SYNC_PUSH,
            M2Endpoint.SYNC_BOOTSTRAP,
            M2Endpoint.SYNC_PULL,
            -> replicaDao.findStreamState()?.let { stream ->
                stream.credentialEpochId == claim.credentialEpochId &&
                    stream.deviceId == snapshot.deviceId &&
                    stream.integrityErrorCode == null &&
                    stream.phase != "integrity_halted"
            } == true

            else -> false
        }
        return if (routeStillAuthoritative) {
            BodyBlindClassification(snapshot = snapshot)
        } else {
            BodyBlindClassification(ProtectedResponseDisposition.SUPERSEDED)
        }
    }

    private suspend fun requireExactTerminalReplay(
        claim: ProtectedRequestClaim.Claimed,
        httpStatus: Int,
        exactResponseBody: ByteArray,
    ): ProtectedResponseDisposition {
        val responseSha256 = sha256Hex(exactResponseBody)
        if (
            !transportDao.matchesExactTerminalResponse(
                endpointId = claim.request.endpoint.endpointId,
                requestIdentity = claim.request.requestIdentity,
                httpStatus = httpStatus,
                exactResponseBody = exactResponseBody,
                responseSha256 = responseSha256,
            )
        ) {
            throw ReplicaIntegrityException(
                errorCode = "terminal_response_drift",
                message = "Terminal response replay differs from its exact durable receipt",
            )
        }
        return ProtectedResponseDisposition.EXACT_REPLAY
    }

    private fun requireFreshClaimProvenance(
        snapshot: ru.andriyshkoy.lifeagent.data.local.db.dao.SyncResponseRouteSnapshot,
        claim: ProtectedRequestClaim.Claimed,
    ) {
        if (
            snapshot.endpointId != claim.request.endpoint.endpointId ||
            snapshot.requestIdentity != claim.request.requestIdentity ||
            snapshot.credentialEpochId != claim.credentialEpochId ||
            snapshot.state != "sending" ||
            snapshot.activeAttemptId != claim.attemptId ||
            snapshot.accessGenerationUsed != claim.accessGenerationUsed
        ) {
            throw ReplicaIntegrityException(
                errorCode = "response_claim_provenance_drift",
                message = "Fresh response no longer binds its claimed durable attempt",
            )
        }
    }

    private fun requireFreshClaimProvenance(
        request: SyncHttpRequestEntity,
        claim: ProtectedRequestClaim.Claimed,
    ) {
        if (
            request.endpointId != claim.request.endpoint.endpointId ||
            request.requestIdentity != claim.request.requestIdentity ||
            request.credentialEpochId != claim.credentialEpochId ||
            request.state != "sending" ||
            request.activeAttemptId != claim.attemptId ||
            request.accessGenerationUsed != claim.accessGenerationUsed
        ) {
            throw ReplicaIntegrityException(
                errorCode = "response_claim_provenance_drift",
                message = "Hydrated response request differs from its claimed attempt",
            )
        }
    }

    private suspend fun requireAuthoritativeMembership(
        request: SyncHttpRequestEntity,
        verified: VerifiedDurableRequest,
    ): Int? {
        val binding = verified.routeBinding ?: throw ReplicaIntegrityException(
            errorCode = "response_request_binding_drift",
            message = "Verified durable response lost route evidence",
        )
        requireResponseIntegrity(
            binding.endpoint.endpointId == request.endpointId &&
                binding.requestIdentity == request.requestIdentity &&
                binding.credentialEpochId == request.credentialEpochId &&
                binding.deviceId == request.deviceId,
            "response_request_binding_drift",
            "Verified response route differs from durable request metadata",
        )
        return when (verified.endpoint) {
            M2Endpoint.AUTH_REVOKE -> {
                val auth = authDao.findState()
                requireResponseIntegrity(
                    auth?.credentialEpochId == request.credentialEpochId &&
                        auth.deviceId == request.deviceId &&
                        auth.generation == request.accessGenerationUsed &&
                        auth.state == "revoke_pending",
                    "revoke_response_superseded",
                    "Revoke response no longer belongs to the current family",
                )
                null
            }

            M2Endpoint.SYNC_PUSH -> {
                requirePushMembership(request, verified)
                null
            }

            M2Endpoint.SYNC_BOOTSTRAP -> {
                val session = binding.bootstrapId?.let { bootstrapId ->
                    replicaDao.findBootstrapSession(bootstrapId)
                }
                requireResponseIntegrity(
                    session != null &&
                        session.state == "staging" &&
                        session.activeSlot == 1 &&
                        replicaDao.findBootstrapSessionWithActiveSlot()?.bootstrapId ==
                        session.bootstrapId &&
                        session.credentialEpochId == request.credentialEpochId &&
                        session.deviceId == request.deviceId &&
                        session.nextPageCursor == binding.pageCursor,
                    "bootstrap_response_superseded",
                    "Bootstrap response no longer belongs to the active shadow",
                )
                val authoritativeSession = checkNotNull(session)
                requireResponseIntegrity(
                    authoritativeSession.nextPageIndex >= 0,
                    "bootstrap_page_index_invalid",
                    "Bootstrap response has no authoritative page index",
                )
                authoritativeSession.nextPageIndex
            }

            M2Endpoint.SYNC_PULL -> {
                val stream = replicaDao.findStreamState()
                requireResponseIntegrity(
                    stream?.credentialEpochId == request.credentialEpochId &&
                        stream.deviceId == request.deviceId &&
                        stream.integrityErrorCode == null &&
                        !stream.bootstrapRequired &&
                        stream.phase in setOf("incremental", "pulling") &&
                        replicaDao.findBootstrapSessionWithActiveSlot() == null &&
                        binding.pullCursor == stream.appliedCursor,
                    "pull_response_superseded",
                    "Pull response no longer continues the authoritative cursor",
                )
                val authoritative = checkNotNull(stream)
                val lineageId = authoritative.replicaLineageId
                val appliedCursor = authoritative.appliedCursor
                val cursor = if (lineageId == null || appliedCursor == null) {
                    null
                } else {
                    replicaDao.findReplicaCursor(lineageId, appliedCursor)
                }
                val retainedIncrementalCursorCount = if (lineageId == null) {
                    0
                } else {
                    replicaDao.countReplicaCursorsByRole(
                        lineageId = lineageId,
                        role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
                    )
                }
                requireResponseIntegrity(
                    lineageId != null &&
                        cursor?.role == SyncReplicaCursorEntity.ROLE_INCREMENTAL &&
                        retainedIncrementalCursorCount > 0,
                    "pull_page_index_invalid",
                    "Pull response has no authoritative incremental page index",
                )
                retainedIncrementalCursorCount - 1
            }

            else -> throw ReplicaIntegrityException(
                errorCode = "response_endpoint_invalid",
                message = "Non-durable endpoint reached the protected response boundary",
            )
        }
    }

    private suspend fun requirePushMembership(
        request: SyncHttpRequestEntity,
        verified: VerifiedDurableRequest,
    ) {
        val evidence = verified.inspectBody(WireRequestCodec::decodeDurablePushEvidence)
        val stream = replicaDao.findStreamState()
        val batch = transportDao.findBatch(request.requestIdentity)
        val items = batch?.let { transportDao.findBatchItems(it.batchId) }.orEmpty()
        requireResponseIntegrity(
            stream?.credentialEpochId == request.credentialEpochId &&
                stream.deviceId == request.deviceId &&
                stream.integrityErrorCode == null &&
                !stream.bootstrapRequired &&
                stream.phase == "incremental" &&
                batch != null &&
                evidence.batchId == request.requestIdentity &&
                evidence.deviceId == request.deviceId &&
                evidence.batchContentSha256 == batch.batchContentSha256 &&
                batch.endpointId == request.endpointId &&
                batch.requestIdentity == request.requestIdentity &&
                batch.operationCount == evidence.items.size &&
                items.size == evidence.items.size,
            "push_response_membership_drift",
            "Push response request no longer binds its durable batch",
        )
        val durableBatch = checkNotNull(batch)
        evidence.items.zip(items).forEach { (wire, item) ->
            val outbox = mutationDao.findOutbox(item.operationId)
            requireResponseIntegrity(
                wire.ordinal == item.ordinal &&
                    wire.clientSequence == item.localSequence &&
                    wire.operationId == item.operationId &&
                    wire.operationContentSha256 == item.wireOperationContentSha256 &&
                    outbox?.localSequence == item.localSequence &&
                    outbox?.operationId == item.operationId &&
                    outbox?.activeBatchId == durableBatch.batchId &&
                    outbox?.state == "batched" &&
                    outbox?.wireOperationContentSha256 == item.wireOperationContentSha256,
                "push_response_membership_drift",
                "Push response batch membership changed after dispatch",
            )
        }
    }

    private suspend fun quarantineMalformedFreshMetadata(
        snapshot: ru.andriyshkoy.lifeagent.data.local.db.dao.SyncResponseRouteSnapshot,
        claim: ProtectedRequestClaim.Claimed,
        failedAtUtc: String,
    ) {
        val quarantined = transportDao.quarantineFreshResponseMetadata(
            endpointId = claim.request.endpoint.endpointId,
            requestIdentity = claim.request.requestIdentity,
            credentialEpochId = claim.credentialEpochId,
            accessGenerationUsed = claim.accessGenerationUsed,
            expectedAttemptId = claim.attemptId,
            failedAtUtc = failedAtUtc,
            failureCode = RequestBodyFailure.METADATA_INVALID.storageCode,
        )
        requireResponseIntegrity(
            quarantined == 1,
            "response_metadata_quarantine_lost",
            "Malformed response request lost its exact attempt quarantine",
        )
        quarantineCurrentRoute(
            endpoint = claim.request.endpoint,
            credentialEpochId = claim.credentialEpochId,
            deviceId = snapshot.deviceId,
            accessGenerationUsed = claim.accessGenerationUsed,
            failedAtUtc = failedAtUtc,
            failure = RequestBodyFailure.METADATA_INVALID,
        )
    }

    private suspend fun quarantineVerifiedRequestFailure(
        request: SyncHttpRequestEntity,
        claim: ProtectedRequestClaim.Claimed,
        failedAtUtc: String,
        failure: RequestBodyFailure,
    ) {
        requireResponseIntegrity(
            transportDao.quarantineFreshResponseMetadata(
                endpointId = request.endpointId,
                requestIdentity = request.requestIdentity,
                credentialEpochId = claim.credentialEpochId,
                accessGenerationUsed = claim.accessGenerationUsed,
                expectedAttemptId = claim.attemptId,
                failedAtUtc = failedAtUtc,
                failureCode = failure.storageCode,
            ) == 1,
            "response_request_quarantine_lost",
            "Invalid verified request lost its exact response quarantine",
        )
        quarantineCurrentRoute(
            endpoint = claim.request.endpoint,
            credentialEpochId = request.credentialEpochId,
            deviceId = request.deviceId,
            accessGenerationUsed = claim.accessGenerationUsed,
            failedAtUtc = failedAtUtc,
            failure = failure,
        )
    }

    private suspend fun quarantineCurrentRoute(
        endpoint: M2Endpoint,
        credentialEpochId: String,
        deviceId: String?,
        accessGenerationUsed: Long,
        failedAtUtc: String,
        failure: RequestBodyFailure,
        storedFailureCode: String = failure.storageCode,
    ) {
        if (endpoint == M2Endpoint.AUTH_REVOKE) {
            val auth = authDao.findState()
            if (
                auth?.credentialEpochId == credentialEpochId &&
                auth.generation == accessGenerationUsed &&
                auth.state == "revoke_pending"
            ) {
                check(
                    authDao.quarantine(
                        credentialEpochId = auth.credentialEpochId,
                        generation = auth.generation,
                        expectedState = "revoke_pending",
                        newState = "integrity_failure",
                        updatedAtUtc = failedAtUtc,
                        failureCode = storedFailureCode,
                    ) == 1,
                )
            }
            return
        }
        val stream = replicaDao.findStreamState()
        if (
            stream?.credentialEpochId == credentialEpochId &&
            (deviceId == null || stream.deviceId == deviceId) &&
            stream.integrityErrorCode == null
        ) {
            check(
                replicaDao.markIntegrityHalted(
                    credentialEpochId = stream.credentialEpochId,
                    deviceId = stream.deviceId,
                    errorCode = storedFailureCode,
                    updatedAtUtc = failedAtUtc,
                ) == 1,
            )
        }
    }

    private suspend fun haltAfterRollback(
        claim: ProtectedRequestClaim.Claimed,
        error: ReplicaIntegrityException,
        updatedAtUtc: String,
    ) {
        if (claim.request.endpoint == M2Endpoint.AUTH_REVOKE) {
            authStore.haltVerifiedRevokeAfterRollback(
                credentialEpochId = claim.credentialEpochId,
                accessGenerationUsed = claim.accessGenerationUsed,
                errorCode = error.errorCode,
                updatedAtUtc = updatedAtUtc,
            )
        } else {
            persistenceStore.haltVerifiedRouteAfterRollback(
                credentialEpochId = claim.credentialEpochId,
                endpointId = claim.request.endpoint.endpointId,
                error = error,
                updatedAtUtc = updatedAtUtc,
            )
        }
    }

    private suspend fun scheduleRetryOrTerminalizeInCurrentTransaction(
        request: SyncHttpRequestEntity,
        claim: ProtectedRequestClaim.Claimed,
        nowEpochMs: Long,
        retryAfterSeconds: Int?,
        failureCode: String,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition {
        check(database.inTransaction())
        val proposedNextAttemptAtEpochMs = proposedRetryAtEpochMs(
            request = request,
            nowEpochMs = nowEpochMs,
            retryAfterSeconds = retryAfterSeconds,
        )
        if (
            transportDao.scheduleRetry(
                endpointId = request.endpointId,
                requestIdentity = request.requestIdentity,
                expectedAttemptId = claim.attemptId,
                nextAttemptAtEpochMs = proposedNextAttemptAtEpochMs,
                lastErrorCode = failureCode,
                updatedAtUtc = terminalAtUtc,
            ) == 1
        ) {
            return ProtectedResponseDisposition.RETRY_SCHEDULED
        }
        requireResponseIntegrity(
            transportDao.terminalizeCompletedRetryFailure(
                endpointId = request.endpointId,
                requestIdentity = request.requestIdentity,
                expectedAttemptId = claim.attemptId,
                proposedNextAttemptAtEpochMs = proposedNextAttemptAtEpochMs,
                terminalAtUtc = terminalAtUtc,
                failureCode = "retry_exhausted_$failureCode",
            ) == 1,
            "retry_completion_cas_lost",
            "Completed retryable failure lost its exact attempt CAS",
        )
        if (request.endpointId == M2Endpoint.SYNC_PUSH.endpointId) {
            val expected = transportDao.findBatchItems(request.requestIdentity).size
            requireResponseIntegrity(
                expected > 0 &&
                    transportDao.releasePushBatchForBootstrap(request.requestIdentity) == expected,
                "retry_exhausted_batch_release_failed",
                "Exhausted push retry could not release its exact batch",
            )
        } else if (request.endpointId == M2Endpoint.AUTH_REVOKE.endpointId) {
            val current = authDao.findState()
            if (
                current?.credentialEpochId == claim.credentialEpochId &&
                current.generation == claim.accessGenerationUsed &&
                current.state == "revoke_pending"
            ) {
                check(
                    authDao.quarantine(
                        credentialEpochId = current.credentialEpochId,
                        generation = current.generation,
                        expectedState = "revoke_pending",
                        newState = "quarantined",
                        updatedAtUtc = terminalAtUtc,
                        failureCode = "revoke_retry_exhausted",
                    ) == 1,
                )
            }
        }
        return ProtectedResponseDisposition.RETRY_EXHAUSTED
    }
}

internal sealed interface ProtectedFreshResponseDecoder {
    fun decode(input: ProtectedFreshResponseInput): ProtectedResponseCommand
}

/** Closed production adapter from strict wire values to trusted reducer commands. */
internal object ProductionProtectedFreshResponseDecoder : ProtectedFreshResponseDecoder {
    override fun decode(
        input: ProtectedFreshResponseInput,
    ): ProtectedResponseCommand = when (input.endpoint) {
        M2Endpoint.AUTH_REVOKE -> decodeRevoke(input)
        M2Endpoint.SYNC_PUSH -> decodePush(input)
        M2Endpoint.SYNC_BOOTSTRAP -> decodeBootstrap(input)
        M2Endpoint.SYNC_PULL -> decodePull(input)
        else -> throw ReplicaIntegrityException(
            errorCode = "response_endpoint_invalid",
            message = "Non-durable endpoint reached the production response decoder",
        )
    }

    private fun decodeRevoke(input: ProtectedFreshResponseInput): ProtectedResponseCommand {
        requireNoReplicaPageIndex(input)
        val evidence = WireRequestCodec.decodeDurableRevokeEvidence(input.exactRequestBody)
        requireRequestIdentity(evidence.requestId, input)
        return when (
            val decoded = WireResponseCodec.decodeDurableRevokeResponse(
                httpStatus = input.httpStatus,
                body = input.exactResponseBody,
                evidence = evidence,
            )
        ) {
            is RevokeSuccess -> ProtectedResponseCommand.RevokeSuccess
            is DecodedApiError -> mapApiError(
                input = input,
                error = decoded.value,
                bootstrapId = null,
            )
            else -> unexpectedDecodedType()
        }
    }

    private fun decodePush(input: ProtectedFreshResponseInput): ProtectedResponseCommand {
        requireNoReplicaPageIndex(input)
        val request = WireRequestCodec.decodePushBatch(input.exactRequestBody)
        requireRequestIdentity(request.batchId, input)
        return when (
            val decoded = WireResponseCodec.decode(
                httpStatus = input.httpStatus,
                body = input.exactResponseBody,
                expectation = PushResponseExpectation(request),
            )
        ) {
            is PushBatchSuccess -> ProtectedResponseCommand.PushSuccess(
                decoded.results.map { result -> mapPushResult(result, input) },
            )
            is DecodedApiError -> mapApiError(
                input = input,
                error = decoded.value,
                bootstrapId = null,
            )
            else -> unexpectedDecodedType()
        }
    }

    private fun decodeBootstrap(input: ProtectedFreshResponseInput): ProtectedResponseCommand {
        val evidence = WireRequestCodec.decodeDurableBootstrapEvidence(input.exactRequestBody)
        requireRequestIdentity(evidence.requestId, input)
        val request = BootstrapRequest(
            requestId = evidence.requestId,
            bootstrapId = evidence.bootstrapId,
            deviceId = evidence.deviceId,
            pageSize = evidence.pageSize,
            pageCursor = evidence.pageCursor,
        )
        if (input.httpStatus != SUCCESS_HTTP_STATUS) {
            return mapApiError(
                input = input,
                error = WireResponseCodec.decodeDurableReplicaApiError(
                    httpStatus = input.httpStatus,
                    body = input.exactResponseBody,
                    expectation = BootstrapApiErrorExpectation(request),
                ),
                bootstrapId = evidence.bootstrapId,
            )
        }
        val decoded = WireResponseCodec.decodeFreshReplicaPage(
            httpStatus = input.httpStatus,
            body = input.exactResponseBody,
            expectation = FreshBootstrapPageExpectation(
                request = request,
                persistedRequestBodySha256 = sha256Hex(input.exactRequestBody),
            ),
        )
        val persistence = WireReplicaPersistenceMapper.map(
            response = decoded,
            pageIndex = requireReplicaPageIndex(input),
            terminalAtUtc = input.terminalAtUtc,
        )
        return ProtectedResponseCommand.BootstrapPage(
            receipt = persistence.receipt,
            changes = persistence.changes,
        )
    }

    private fun decodePull(input: ProtectedFreshResponseInput): ProtectedResponseCommand {
        val evidence = WireRequestCodec.decodeDurablePullEvidence(input.exactRequestBody)
        requireRequestIdentity(evidence.requestId, input)
        val request = PullRequest(
            requestId = evidence.requestId,
            deviceId = evidence.deviceId,
            cursor = evidence.cursor,
            pageSize = evidence.pageSize,
        )
        if (input.httpStatus != SUCCESS_HTTP_STATUS) {
            return mapApiError(
                input = input,
                error = WireResponseCodec.decodeDurableReplicaApiError(
                    httpStatus = input.httpStatus,
                    body = input.exactResponseBody,
                    expectation = PullApiErrorExpectation(request),
                ),
                bootstrapId = null,
            )
        }
        val decoded = WireResponseCodec.decodeFreshReplicaPage(
            httpStatus = input.httpStatus,
            body = input.exactResponseBody,
            expectation = FreshPullPageExpectation(
                request = request,
                persistedRequestBodySha256 = sha256Hex(input.exactRequestBody),
            ),
        )
        val persistence = WireReplicaPersistenceMapper.map(
            response = decoded,
            pageIndex = requireReplicaPageIndex(input),
            terminalAtUtc = input.terminalAtUtc,
        )
        return ProtectedResponseCommand.PullPage(
            receipt = persistence.receipt,
            changes = persistence.changes,
        )
    }

    private fun mapPushResult(
        result: PushOperationResult,
        input: ProtectedFreshResponseInput,
    ): PushResultPersistence = when (result) {
        is PushOperationAck -> PushAckPersistence(
            ordinal = result.ordinal,
            change = SyncServerChangeEntity(
                serverSequence = result.serverSequence,
                operationId = result.operationId,
                operationContentSha256 = result.operationContentSha256,
                resultCode = result.resultCode.wireName,
                captureId = result.captureId,
                eventId = result.eventId,
                revisionId = result.revisionId,
                currentRevisionId = result.currentRevisionId,
                committedAtUtc = result.committedAt,
                firstEndpointId = M2Endpoint.SYNC_PUSH.endpointId,
                firstRequestIdentity = input.requestIdentity,
                verifiedAtUtc = input.terminalAtUtc,
            ),
        )

        is PushOperationError -> {
            requireResponseIntegrity(
                result.fieldErrors.isEmpty(),
                "push_error_projection_invalid",
                "Strict push error retained an unsupported field projection",
            )
            PushErrorPersistence(
                ordinal = result.ordinal,
                operationId = result.operationId,
                operationContentSha256 = result.operationContentSha256,
                errorCode = result.errorCode.wireName,
                retryable = result.retryable,
                detailsJcs = EMPTY_FIELD_ERRORS_JCS.copyOf(),
            )
        }
    }

    private fun mapApiError(
        input: ProtectedFreshResponseInput,
        error: WireApiError,
        bootstrapId: String?,
    ): ProtectedResponseCommand {
        requireResponseIntegrity(
            error.httpStatus == input.httpStatus,
            "api_error_status_drift",
            "Decoded API error differs from its transport status",
        )
        return when {
            error.errorCode == ApiErrorCode.CREDENTIAL_UNAVAILABLE &&
                input.endpoint == M2Endpoint.AUTH_REVOKE ->
                ProtectedResponseCommand.RevokeCredentialUnavailable

            error.errorCode == ApiErrorCode.CREDENTIAL_UNAVAILABLE &&
                input.endpoint.sync401RecoveryEligible ->
                ProtectedResponseCommand.TrustedUnauthorized

            error.errorCode == ApiErrorCode.CURSOR_EXPIRED &&
                input.endpoint == M2Endpoint.SYNC_BOOTSTRAP ->
                ProtectedResponseCommand.BootstrapCursorExpired(
                    expiredBootstrapId = checkNotNull(bootstrapId),
                )

            error.errorCode == ApiErrorCode.CURSOR_INVALID &&
                input.endpoint in setOf(M2Endpoint.SYNC_BOOTSTRAP, M2Endpoint.SYNC_PULL) ->
                ProtectedResponseCommand.CursorInvalid

            error.errorCode == ApiErrorCode.BOOTSTRAP_REQUIRED &&
                input.endpoint in setOf(M2Endpoint.SYNC_PUSH, M2Endpoint.SYNC_PULL) ->
                ProtectedResponseCommand.BootstrapRequired

            error.retryable -> ProtectedResponseCommand.RetryableApiError(error.errorCode)
            else -> ProtectedResponseCommand.PermanentApiError(error.errorCode)
        }
    }

    private fun requireRequestIdentity(
        decodedRequestIdentity: String,
        input: ProtectedFreshResponseInput,
    ) = requireResponseIntegrity(
        decodedRequestIdentity == input.requestIdentity,
        "response_request_identity_drift",
        "Decoded request identity differs from its protected claim",
    )

    private fun requireReplicaPageIndex(input: ProtectedFreshResponseInput): Int {
        val pageIndex = input.replicaPageIndex
        requireResponseIntegrity(
            pageIndex != null && pageIndex >= 0,
            "response_page_index_missing",
            "Replica page response has no authoritative Room page index",
        )
        return checkNotNull(pageIndex)
    }

    private fun requireNoReplicaPageIndex(input: ProtectedFreshResponseInput) {
        requireResponseIntegrity(
            input.replicaPageIndex == null,
            "response_page_index_unexpected",
            "Non-page response carried a replica page index",
        )
    }

    private fun unexpectedDecodedType(): Nothing = throw ReplicaIntegrityException(
        errorCode = "response_decoder_type_drift",
        message = "Strict wire decoder returned an impossible durable response type",
    )

    private val EMPTY_FIELD_ERRORS_JCS = "[]".encodeToByteArray()
    private const val SUCCESS_HTTP_STATUS = 200
}

/** Borrowed exact bytes are valid only for the synchronous decoder callback. */
internal class ProtectedFreshResponseInput internal constructor(
    val endpoint: M2Endpoint,
    val requestIdentity: String,
    val httpStatus: Int,
    val retryAfterSeconds: Int?,
    val terminalAtUtc: String,
    val replicaPageIndex: Int?,
    val exactRequestBody: ByteArray,
    val exactResponseBody: ByteArray,
) {
    override fun toString(): String =
        "ProtectedFreshResponseInput(endpoint=${endpoint.endpointId},redacted=true)"
}

internal sealed interface ProtectedResponseCommand {
    val terminalErrorCode: String?

    class PushSuccess(
        val results: List<PushResultPersistence>,
    ) : ProtectedResponseCommand {
        override val terminalErrorCode: String? = null
    }

    class BootstrapPage(
        val receipt: SyncPageReceiptEntity,
        val changes: List<ReplicaChangePersistence>,
    ) : ProtectedResponseCommand {
        override val terminalErrorCode: String? = null
    }

    class PullPage(
        val receipt: SyncPageReceiptEntity,
        val changes: List<ReplicaChangePersistence>,
    ) : ProtectedResponseCommand {
        override val terminalErrorCode: String? = null
    }

    class BootstrapCursorExpired(
        val expiredBootstrapId: String,
    ) : ProtectedResponseCommand {
        override val terminalErrorCode: String = ApiErrorCode.CURSOR_EXPIRED.wireName
    }

    data object CursorInvalid : ProtectedResponseCommand {
        override val terminalErrorCode: String = ApiErrorCode.CURSOR_INVALID.wireName
    }

    data object BootstrapRequired : ProtectedResponseCommand {
        override val terminalErrorCode: String = ApiErrorCode.BOOTSTRAP_REQUIRED.wireName
    }

    data object TrustedUnauthorized : ProtectedResponseCommand {
        override val terminalErrorCode: String = ApiErrorCode.CREDENTIAL_UNAVAILABLE.wireName
    }

    data object RevokeSuccess : ProtectedResponseCommand {
        override val terminalErrorCode: String? = null
    }

    data object RevokeCredentialUnavailable : ProtectedResponseCommand {
        override val terminalErrorCode: String = ApiErrorCode.CREDENTIAL_UNAVAILABLE.wireName
    }

    class RetryableApiError(
        val errorCode: ApiErrorCode,
    ) : ProtectedResponseCommand {
        override val terminalErrorCode: String = errorCode.wireName
    }

    class PermanentApiError(
        val errorCode: ApiErrorCode,
    ) : ProtectedResponseCommand {
        override val terminalErrorCode: String = errorCode.wireName
    }
}

/**
 * Narrow request-creation capability. Its implementation authenticates or
 * creates bootstrap candidates only when the response boundary invokes it
 * from the already-open Room transaction.
 */
internal interface ProtectedBootstrapIntentBoundary {
    suspend fun createContinuation(
        session: SyncBootstrapSessionEntity,
        createdAtUtc: String,
    ): SyncHttpRequestEntity

    suspend fun createInitial(
        credentialEpochId: String,
        deviceId: String,
        createdAtUtc: String,
    ): BootstrapIntentPersistence

    suspend fun verifyExistingCandidates(
        session: SyncBootstrapSessionEntity,
        failedAtUtc: String,
    ): Boolean
}

internal enum class ProtectedResponseDisposition {
    COMMITTED,
    PULL_CONTINUATION_READY,
    PULL_CYCLE_COMPLETE,
    EXACT_REPLAY,
    STALE_CALLBACK,
    SUPERSEDED,
    QUARANTINED,
    RETRY_SCHEDULED,
    RETRY_EXHAUSTED,
    REFRESH_REQUIRED,
}

private fun requireValidDispatchAttempt(
    candidate: SyncRunnableRequestCandidate,
    attemptId: String,
    attemptedAtUtc: String,
    leaseExpiresAtEpochMs: Long,
): Instant {
    val endpoint = requireNotNull(M2Endpoint.fromId(candidate.endpointId)) {
        "Durable dispatch endpoint is unknown"
    }
    require(endpoint.durableExactReplay) {
        "Protected durable dispatch accepts exact-replay endpoints only"
    }
    val attemptedAt = Instant.parse(attemptedAtUtc)
    val attemptedAtEpochMs = attemptedAt.toEpochMilli()
    require(attemptId.isNotBlank()) { "Dispatch attempt id must not be blank" }
    require(attemptedAtEpochMs > 0) { "Dispatch attempt time must be positive" }
    require(leaseExpiresAtEpochMs > attemptedAtEpochMs) {
        "Dispatch lease must end after its attempt starts"
    }
    require(leaseExpiresAtEpochMs <= candidate.deadlineAtEpochMs) {
        "Dispatch lease cannot exceed the durable request deadline"
    }
    return attemptedAt
}

private fun ProtectedResponseDisposition.toDispatchResult(
    endpointId: String,
): ProtectedDurableDispatchResult = when (this) {
    ProtectedResponseDisposition.COMMITTED ->
        endpointId.authoritativeFollowUpResult()

    ProtectedResponseDisposition.PULL_CONTINUATION_READY -> {
        check(endpointId == M2Endpoint.SYNC_PULL.endpointId) {
            "Pull continuation disposition belongs to a non-pull endpoint"
        }
        ProtectedDurableDispatchResult.PULL_CONTINUATION_READY
    }

    ProtectedResponseDisposition.PULL_CYCLE_COMPLETE -> {
        check(endpointId == M2Endpoint.SYNC_PULL.endpointId) {
            "Pull completion disposition belongs to a non-pull endpoint"
        }
        ProtectedDurableDispatchResult.PULL_CYCLE_COMPLETE
    }

    ProtectedResponseDisposition.EXACT_REPLAY,
    ProtectedResponseDisposition.SUPERSEDED,
    ProtectedResponseDisposition.REFRESH_REQUIRED,
    -> endpointId.authoritativeFollowUpResult()

    ProtectedResponseDisposition.RETRY_SCHEDULED ->
        ProtectedDurableDispatchResult.RETRY_LATER

    ProtectedResponseDisposition.QUARANTINED,
    ProtectedResponseDisposition.RETRY_EXHAUSTED,
    -> ProtectedDurableDispatchResult.USER_ACTION_REQUIRED

    ProtectedResponseDisposition.STALE_CALLBACK ->
        ProtectedDurableDispatchResult.NO_PROGRESS
}

private fun String.authoritativeFollowUpResult(): ProtectedDurableDispatchResult =
    if (this == M2Endpoint.SYNC_PULL.endpointId) {
        // The coordinator accepts only pull-continuation or pull-complete as a
        // progressing pull result. A trusted 401, bootstrap requirement or
        // superseding authority must rescan immediately for that follow-up.
        ProtectedDurableDispatchResult.PULL_CONTINUATION_READY
    } else {
        ProtectedDurableDispatchResult.PROGRESSED
    }

private data class BodyBlindClassification(
    val disposition: ProtectedResponseDisposition? = null,
    val snapshot: ru.andriyshkoy.lifeagent.data.local.db.dao.SyncResponseRouteSnapshot? = null,
    val terminalReplayPending: Boolean = false,
)

private fun requireRoute(
    response: TerminalHttpResponsePersistence,
    endpoint: M2Endpoint,
    status: Int,
    errorCode: String?,
) {
    require(
        response.endpointId == endpoint.endpointId &&
            response.httpStatus == status &&
            response.terminalErrorCode == errorCode,
    )
}

private fun requireResponseIntegrity(
    condition: Boolean,
    errorCode: String,
    message: String,
) {
    if (!condition) throw ReplicaIntegrityException(errorCode, message)
}

private fun Exception.toResponseRequestBodyFailureOrNull(): RequestBodyFailure? = when (this) {
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

private fun proposedRetryAtEpochMs(
    request: SyncHttpRequestEntity,
    nowEpochMs: Long,
    retryAfterSeconds: Int?,
): Long {
    val exponent = (request.attemptCount - 1).coerceIn(0, 8)
    val exponentialMs = (1_000L shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    val retryAfterMs = retryAfterSeconds?.toLong()?.times(1_000L) ?: 0L
    val requested = try {
        Math.addExact(nowEpochMs, maxOf(exponentialMs, retryAfterMs))
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    return minOf(requested, request.deadlineAtEpochMs)
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return try {
        digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } finally {
        digest.fill(0)
    }
}

private val LOCALLY_TERMINAL_REQUEST_STATES = setOf(
    "terminal_local",
    "integrity_failure",
)

private val NON_SENDING_OPEN_REQUEST_STATES = setOf(
    "ready",
    "retry_wait",
    "waiting_refresh",
)

private const val MAX_RETRY_DELAY_MS = 300_000L
