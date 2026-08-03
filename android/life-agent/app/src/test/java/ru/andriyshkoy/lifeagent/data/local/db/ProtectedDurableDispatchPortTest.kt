package ru.andriyshkoy.lifeagent.data.local.db

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncRunnableRequestCandidate
import ru.andriyshkoy.lifeagent.data.security.VerifiedDurableRequest
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenKey
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenVault
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsNetworkFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsNetworkFailureKind
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsProtocolFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsProtocolFailureKind
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsRawResponse
import ru.andriyshkoy.lifeagent.data.sync.transport.LazyProductionM2HttpsTransportBundle
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

class ProtectedDurableDispatchPortTest {
    @Test
    fun networkProtocolAndRawOutcomesUseTheirClosedReducerMappings() {
        data class Case(
            val disposition: ProtectedResponseDisposition,
            val expectedResult: ProtectedDurableDispatchResult,
            val expectedReduction: ReductionKind,
        )

        val cases = listOf(
            Case(
                disposition = ProtectedResponseDisposition.RETRY_SCHEDULED,
                expectedResult = ProtectedDurableDispatchResult.RETRY_LATER,
                expectedReduction = ReductionKind.NETWORK,
            ),
            Case(
                disposition = ProtectedResponseDisposition.QUARANTINED,
                expectedResult = ProtectedDurableDispatchResult.USER_ACTION_REQUIRED,
                expectedReduction = ReductionKind.PROTOCOL,
            ),
            Case(
                disposition = ProtectedResponseDisposition.COMMITTED,
                expectedResult = ProtectedDurableDispatchResult.PROGRESSED,
                expectedReduction = ReductionKind.RAW,
            ),
        )

        cases.forEach { case ->
            val vault = AccessTokenVault()
            try {
                val dispatchClaim = dispatchClaim(M2Endpoint.AUTH_REVOKE, vault)
                val requestStorage = requestStorage(dispatchClaim)
                val responses = RecordingResponses(case.disposition)
                var rawStorage: ByteArray? = null
                val exchange = ProtectedDurableExactExchange { claim, bearer ->
                    assertNull(bearer)
                    when (case.expectedReduction) {
                        ReductionKind.NETWORK -> ExactHttpsNetworkFailure(
                            claim = claim,
                            kind = ExactHttpsNetworkFailureKind.IO,
                            httpStatus = null,
                        )

                        ReductionKind.PROTOCOL -> ExactHttpsProtocolFailure(
                            claim = claim,
                            kind = ExactHttpsProtocolFailureKind.ROUTE_MISMATCH,
                        )

                        ReductionKind.RAW -> ExactHttpsRawResponse(
                            claim = claim,
                            httpStatus = 200,
                            retryAfterSeconds = null,
                            body = "response".encodeToByteArray(),
                        ).also { rawStorage = rawResponseStorage(it) }
                    }
                }
                val port = port(
                    vault = vault,
                    dispatchClaim = dispatchClaim,
                    exchange = exchange,
                    responses = responses,
                )

                val result = runBlocking {
                    dispatch(port, candidate(M2Endpoint.AUTH_REVOKE))
                }

                assertEquals(case.expectedResult, result)
                assertEquals(listOf(case.expectedReduction), responses.reductions)
                assertAllZero(requestStorage)
                rawStorage?.let(::assertAllZero)
            } finally {
                vault.close()
            }
        }
    }

    @Test
    fun pullCommitPreservesContinuationVersusCycleCompletion() {
        val cases = listOf(
            ProtectedResponseDisposition.PULL_CONTINUATION_READY to
                ProtectedDurableDispatchResult.PULL_CONTINUATION_READY,
            ProtectedResponseDisposition.PULL_CYCLE_COMPLETE to
                ProtectedDurableDispatchResult.PULL_CYCLE_COMPLETE,
            ProtectedResponseDisposition.COMMITTED to
                ProtectedDurableDispatchResult.PULL_CONTINUATION_READY,
        )
        cases.forEach { (disposition, expected) ->
            val vault = vaultWithAccessToken()
            try {
                val dispatchClaim = dispatchClaim(M2Endpoint.SYNC_PULL, vault)
                val port = port(
                    vault = vault,
                    dispatchClaim = dispatchClaim,
                    exchange = ProtectedDurableExactExchange { claim, bearer ->
                        assertTrue(bearer != null)
                        ExactHttpsRawResponse(
                            claim = claim,
                            httpStatus = 200,
                            retryAfterSeconds = null,
                            body = "pull-response".encodeToByteArray(),
                        )
                    },
                    responses = RecordingResponses(disposition),
                )

                val result = runBlocking {
                    dispatch(port, candidate(M2Endpoint.SYNC_PULL))
                }

                assertEquals(expected, result)
            } finally {
                vault.close()
            }
        }
    }

    @Test
    fun trustedUnauthorizedRevokesOnlyItsExactGenerationAfterReduction() {
        val vault = vaultWithAccessToken()
        try {
            vault.replace(
                OTHER_ACCESS_TOKEN_KEY,
                WipeableSecret.ascii(VALID_ACCESS_TOKEN),
            )
            val dispatchClaim = dispatchClaim(M2Endpoint.SYNC_PULL, vault)
            val port = port(
                vault = vault,
                dispatchClaim = dispatchClaim,
                exchange = rawExchange(),
                responses = RecordingResponses(ProtectedResponseDisposition.REFRESH_REQUIRED),
            )

            val result = runBlocking {
                dispatch(port, candidate(M2Endpoint.SYNC_PULL))
            }

            assertEquals(ProtectedDurableDispatchResult.PULL_CONTINUATION_READY, result)
            assertNull(vault.claim(ACCESS_TOKEN_KEY))
            val otherGeneration = vault.claim(OTHER_ACCESS_TOKEN_KEY)
            assertTrue(otherGeneration != null)
            otherGeneration?.close()
        } finally {
            vault.close()
        }
    }

    @Test
    fun staleAndReplayCallbacksDoNotRevokeTheExactGeneration() {
        val cases = listOf(
            ProtectedResponseDisposition.STALE_CALLBACK to
                ProtectedDurableDispatchResult.NO_PROGRESS,
            ProtectedResponseDisposition.EXACT_REPLAY to
                ProtectedDurableDispatchResult.PULL_CONTINUATION_READY,
        )
        cases.forEach { (disposition, expected) ->
            val vault = vaultWithAccessToken()
            try {
                val port = port(
                    vault = vault,
                    dispatchClaim = dispatchClaim(M2Endpoint.SYNC_PULL, vault),
                    exchange = rawExchange(),
                    responses = RecordingResponses(disposition),
                )

                val result = runBlocking {
                    dispatch(port, candidate(M2Endpoint.SYNC_PULL))
                }

                assertEquals(expected, result)
                val retained = vault.claim(ACCESS_TOKEN_KEY)
                assertTrue(retained != null)
                retained?.close()
            } finally {
                vault.close()
            }
        }
    }

    @Test
    fun unavailableBridgeAndMissingTokenCannotReachAnAttemptOrNetwork() {
        val vault = AccessTokenVault()
        try {
            val claimCount = AtomicInteger(0)
            val executeCount = AtomicInteger(0)
            val unavailable = ProductionProtectedDurableDispatchPort(
                exchangeProvider = { null },
                claims = ProtectedDurableDispatchClaimBoundary { _, _, _, _, _ ->
                    claimCount.incrementAndGet()
                    ProtectedDispatchRequestClaim.NotClaimed
                },
                responses = RecordingResponses(ProtectedResponseDisposition.COMMITTED),
                accessTokenVault = vault,
                completionClock = COMPLETION_CLOCK,
            )

            assertEquals(
                ProtectedDurableDispatchResult.USER_ACTION_REQUIRED,
                runBlocking { dispatch(unavailable, candidate(M2Endpoint.SYNC_PULL)) },
            )
            assertEquals(0, claimCount.get())
            assertEquals(0, executeCount.get())

            val missingToken = ProductionProtectedDurableDispatchPort(
                exchangeProvider = {
                    ProtectedDurableExactExchange { _, _ ->
                        executeCount.incrementAndGet()
                        error("Network must not run without exact token authority")
                    }
                },
                claims = ProtectedDurableDispatchClaimBoundary { _, _, _, _, _ ->
                    claimCount.incrementAndGet()
                    ProtectedDispatchRequestClaim.NotClaimed
                },
                responses = RecordingResponses(ProtectedResponseDisposition.COMMITTED),
                accessTokenVault = vault,
                completionClock = COMPLETION_CLOCK,
            )

            assertEquals(
                ProtectedDurableDispatchResult.NO_PROGRESS,
                runBlocking { dispatch(missingToken, candidate(M2Endpoint.SYNC_PULL)) },
            )
            assertEquals(1, claimCount.get())
            assertEquals(0, executeCount.get())
        } finally {
            vault.close()
        }
    }

    @Test
    fun configurationFailureStopsBeforeClaimOrExactReplay() {
        val vault = AccessTokenVault()
        try {
            var bundleOpenCount = 0
            var claimCount = 0
            val transports = LazyProductionM2HttpsTransportBundle {
                bundleOpenCount += 1
                throw IllegalArgumentException("synthetic unavailable HTTPS configuration")
            }
            val port = ProductionProtectedDurableDispatchPort(
                exchangeProvider = {
                    val exact = transports.open().exact
                    ProtectedDurableExactExchange { claim, bearer ->
                        exact.execute(claim, bearer)
                    }
                },
                claims = ProtectedDurableDispatchClaimBoundary { _, _, _, _, _ ->
                    claimCount += 1
                    ProtectedDispatchRequestClaim.NotClaimed
                },
                responses = RecordingResponses(ProtectedResponseDisposition.COMMITTED),
                accessTokenVault = vault,
                completionClock = COMPLETION_CLOCK,
            )

            assertEquals(
                ProtectedDurableDispatchResult.USER_ACTION_REQUIRED,
                runBlocking { dispatch(port, candidate(M2Endpoint.SYNC_PULL)) },
            )
            assertEquals(1, bundleOpenCount)
            assertEquals(0, claimCount)

            val cancellation = CancellationException("synthetic provider cancellation")
            val cancellingPort = ProductionProtectedDurableDispatchPort(
                exchangeProvider = { throw cancellation },
                claims = ProtectedDurableDispatchClaimBoundary { _, _, _, _, _ ->
                    claimCount += 1
                    ProtectedDispatchRequestClaim.NotClaimed
                },
                responses = RecordingResponses(ProtectedResponseDisposition.COMMITTED),
                accessTokenVault = vault,
                completionClock = COMPLETION_CLOCK,
            )
            val propagated = assertThrows(CancellationException::class.java) {
                runBlocking { dispatch(cancellingPort, candidate(M2Endpoint.SYNC_PULL)) }
            }
            assertTrue(propagated === cancellation)
            assertEquals(0, claimCount)
        } finally {
            vault.close()
        }
    }

    @Test
    fun cancellationPropagatesAndClosesClaimAndRawOutcomeOwnership() {
        val exchangeCancellationVault = vaultWithAccessToken()
        try {
            val claim = dispatchClaim(M2Endpoint.SYNC_PULL, exchangeCancellationVault)
            val requestStorage = requestStorage(claim)
            val tokenStorage = tokenStorage(claim)
            val port = port(
                vault = exchangeCancellationVault,
                dispatchClaim = claim,
                exchange = ProtectedDurableExactExchange { _, _ ->
                    throw CancellationException("synthetic")
                },
                responses = RecordingResponses(ProtectedResponseDisposition.COMMITTED),
            )

            assertThrows(CancellationException::class.java) {
                runBlocking { dispatch(port, candidate(M2Endpoint.SYNC_PULL)) }
            }
            assertAllZero(requestStorage)
            assertAllZero(tokenStorage)
        } finally {
            exchangeCancellationVault.close()
        }

        val reductionCancellationVault = vaultWithAccessToken()
        try {
            val claim = dispatchClaim(M2Endpoint.SYNC_PULL, reductionCancellationVault)
            val requestStorage = requestStorage(claim)
            val tokenStorage = tokenStorage(claim)
            var rawStorage: ByteArray? = null
            val cancellingResponses = object : ProtectedDurableDispatchResponseBoundary {
                override suspend fun reduce(
                    outcome: ExactHttpsNetworkFailure,
                    terminalAtUtc: String,
                ): ProtectedResponseDisposition = error("Unexpected network outcome")

                override suspend fun reduce(
                    outcome: ExactHttpsProtocolFailure,
                    terminalAtUtc: String,
                ): ProtectedResponseDisposition = error("Unexpected protocol outcome")

                override suspend fun reduce(
                    outcome: ExactHttpsRawResponse,
                    terminalAtUtc: String,
                ): ProtectedResponseDisposition {
                    throw CancellationException("synthetic reduction")
                }
            }
            val port = port(
                vault = reductionCancellationVault,
                dispatchClaim = claim,
                exchange = ProtectedDurableExactExchange { exactClaim, _ ->
                    ExactHttpsRawResponse(
                        claim = exactClaim,
                        httpStatus = 200,
                        retryAfterSeconds = null,
                        body = "sensitive-response".encodeToByteArray(),
                    ).also { rawStorage = rawResponseStorage(it) }
                },
                responses = cancellingResponses,
            )

            assertThrows(CancellationException::class.java) {
                runBlocking { dispatch(port, candidate(M2Endpoint.SYNC_PULL)) }
            }
            assertAllZero(requestStorage)
            assertAllZero(tokenStorage)
            assertAllZero(checkNotNull(rawStorage))
        } finally {
            reductionCancellationVault.close()
        }
    }

    private fun port(
        vault: AccessTokenVault,
        dispatchClaim: ProtectedDispatchRequestClaim,
        exchange: ProtectedDurableExactExchange,
        responses: ProtectedDurableDispatchResponseBoundary,
    ) = ProductionProtectedDurableDispatchPort(
        exchangeProvider = { exchange },
        claims = ProtectedDurableDispatchClaimBoundary { _, _, _, _, _ -> dispatchClaim },
        responses = responses,
        accessTokenVault = vault,
        completionClock = COMPLETION_CLOCK,
    )

    private suspend fun dispatch(
        port: ProtectedDurableDispatchPort,
        candidate: SyncRunnableRequestCandidate,
    ) = port.dispatch(
        candidate = candidate,
        attemptId = ATTEMPT_ID,
        attemptedAtUtc = ATTEMPTED_AT.toString(),
        leaseExpiresAtEpochMs = LEASE_EXPIRES_AT_EPOCH_MS,
    )

    private fun rawExchange() = ProtectedDurableExactExchange { claim, bearer ->
        assertTrue(bearer != null)
        ExactHttpsRawResponse(
            claim = claim,
            httpStatus = 401,
            retryAfterSeconds = null,
            body = "trusted-response".encodeToByteArray(),
        )
    }

    private fun dispatchClaim(
        endpoint: M2Endpoint,
        vault: AccessTokenVault,
    ): ProtectedDispatchRequestClaim.Claimed {
        val requestClaim = ProtectedRequestClaim.Claimed(
            request = VerifiedDurableRequest(
                endpoint = endpoint,
                requestIdentity = REQUEST_ID,
                idempotencyKey = REQUEST_ID.takeIf { endpoint.idempotencyKeyRequired },
                body = "sensitive-request".encodeToByteArray(),
            ),
            attemptId = ATTEMPT_ID,
            credentialEpochId = CREDENTIAL_EPOCH_ID,
            accessGenerationUsed = ACCESS_GENERATION,
        )
        return ProtectedDispatchRequestClaim.Claimed(
            requestClaim = requestClaim,
            accessTokenClaim = if (endpoint.usesBearerAccess) {
                checkNotNull(vault.claim(ACCESS_TOKEN_KEY))
            } else {
                null
            },
        )
    }

    private fun candidate(endpoint: M2Endpoint) = SyncRunnableRequestCandidate(
        endpointId = endpoint.endpointId,
        requestIdentity = REQUEST_ID,
        credentialEpochId = CREDENTIAL_EPOCH_ID,
        deviceId = DEVICE_ID,
        accessGenerationUsed = ACCESS_GENERATION,
        state = "ready",
        attemptCount = 0,
        attemptBudget = 4,
        deadlineAtEpochMs = DEADLINE_AT_EPOCH_MS,
        nextAttemptAtEpochMs = null,
        lastAttemptAtEpochMs = null,
        leaseExpiresAtEpochMs = null,
        activeAttemptId = null,
        scheduledAtEpochMs = ATTEMPTED_AT.toEpochMilli(),
        routePriority = 0,
    )

    private fun vaultWithAccessToken() = AccessTokenVault().also { vault ->
        vault.replace(ACCESS_TOKEN_KEY, WipeableSecret.ascii(VALID_ACCESS_TOKEN))
    }

    private fun requestStorage(
        claim: ProtectedDispatchRequestClaim.Claimed,
    ): ByteArray = VerifiedDurableRequest::class.java
        .getDeclaredField("bodyStorage")
        .apply { isAccessible = true }
        .get(claim.requestClaim.request) as ByteArray

    private fun tokenStorage(
        claim: ProtectedDispatchRequestClaim.Claimed,
    ): ByteArray = WipeableSecret::class.java
        .getDeclaredField("storage")
        .apply { isAccessible = true }
        .get(checkNotNull(claim.accessTokenClaim).bearerAccessToken) as ByteArray

    private fun rawResponseStorage(response: ExactHttpsRawResponse): ByteArray =
        ExactHttpsRawResponse::class.java
            .getDeclaredField("bodyStorage")
            .apply { isAccessible = true }
            .get(response) as ByteArray

    private fun assertAllZero(bytes: ByteArray) {
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private companion object {
        val ATTEMPTED_AT: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val COMPLETION_CLOCK: Clock = Clock.fixed(
            Instant.parse("2030-01-01T00:00:05Z"),
            ZoneOffset.UTC,
        )
        val LEASE_EXPIRES_AT_EPOCH_MS: Long = ATTEMPTED_AT.toEpochMilli() + 30_000L
        val DEADLINE_AT_EPOCH_MS: Long = ATTEMPTED_AT.toEpochMilli() + 60_000L
        const val REQUEST_ID = "00000000-0000-4000-8000-000000000001"
        const val ATTEMPT_ID = "00000000-0000-4000-8000-000000000002"
        const val CREDENTIAL_EPOCH_ID = "00000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000004"
        const val ACCESS_GENERATION = 2L
        val ACCESS_TOKEN_KEY = AccessTokenKey(CREDENTIAL_EPOCH_ID, ACCESS_GENERATION)
        val OTHER_ACCESS_TOKEN_KEY = AccessTokenKey(
            CREDENTIAL_EPOCH_ID,
            ACCESS_GENERATION + 1,
        )
        val VALID_ACCESS_TOKEN: String = "laa_" +
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32) { index -> (index + 1).toByte() })
    }
}

private enum class ReductionKind {
    NETWORK,
    PROTOCOL,
    RAW,
}

private class RecordingResponses(
    private val disposition: ProtectedResponseDisposition,
) : ProtectedDurableDispatchResponseBoundary {
    val reductions = mutableListOf<ReductionKind>()

    override suspend fun reduce(
        outcome: ExactHttpsNetworkFailure,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition {
        Instant.parse(terminalAtUtc)
        reductions += ReductionKind.NETWORK
        return disposition
    }

    override suspend fun reduce(
        outcome: ExactHttpsProtocolFailure,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition {
        Instant.parse(terminalAtUtc)
        reductions += ReductionKind.PROTOCOL
        return disposition
    }

    override suspend fun reduce(
        outcome: ExactHttpsRawResponse,
        terminalAtUtc: String,
    ): ProtectedResponseDisposition {
        Instant.parse(terminalAtUtc)
        reductions += ReductionKind.RAW
        return disposition
    }
}
