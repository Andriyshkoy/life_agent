package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.PersistedDurableRequestRef
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchPort
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchResult
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningOutcome
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncRunnableRequestCandidate
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionDisposition

class M2SyncWorkCompositionTest {
    @Test
    fun recoveryRepeatsUntilAZeroChangePassThenStaysLatched() = runTest {
        val auth = FakeAuthBoundary(
            recoveryResults = ArrayDeque(
                listOf(
                    M2AuthRuntimeResult.RecoveryComplete(2),
                    M2AuthRuntimeResult.RecoveryComplete(0),
                ),
            ),
        )
        var planningCalls = 0
        val port = port(
            auth = auth,
            planning = {
                planningCalls += 1
                noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
            },
        )

        assertEquals(SyncWorkExecutionDisposition.RETRY, port.runOneBoundedSync())
        assertEquals(0, planningCalls)
        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())

        assertEquals(2, auth.recoveryCalls)
        assertEquals(2, planningCalls)
    }

    @Test
    fun requestRecoveryRunsEveryTimeAndSaturationYieldsBeforePlanning() = runTest {
        var requestRecoveryCalls = 0
        var planningCalls = 0
        val port = port(
            requestRecovery = {
                requestRecoveryCalls += 1
                M2SyncRequestRecoveryResult(
                    recoveredCount = if (requestRecoveryCalls == 1) 100 else 0,
                    saturated = requestRecoveryCalls == 1,
                )
            },
            planning = {
                planningCalls += 1
                noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
            },
        )

        assertEquals(SyncWorkExecutionDisposition.RETRY, port.runOneBoundedSync())
        assertEquals(0, planningCalls)
        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())

        assertEquals(3, requestRecoveryCalls)
        assertEquals(2, planningCalls)
    }

    @Test
    fun missingExactGenerationAccessRefreshesBeforeTheDurableDispatch() = runTest {
        var requestPresent = true
        var dispatchCalls = 0
        val auth = FakeAuthBoundary(
            ensureAccessBlock = {
                M2AuthRuntimeResult.AccessReady(ACCESS_KEY, AuthAccessSource.REFRESH)
            },
        )
        val port = port(
            auth = auth,
            planning = {
                if (requestPresent) {
                    retained(
                        DurableSyncRequestKind.PUSH,
                        PUSH_CANDIDATE.copy(accessGenerationUsed = 1L),
                    )
                } else {
                    noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
                }
            },
            dispatch = {
                dispatchCalls += 1
                requestPresent = false
                ProtectedSyncDispatchDisposition.PROGRESSED
            },
        )

        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(1, auth.ensureAccessCalls)
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun aCreatedRequestMustResolveToTheSameRetainedAuthority() = runTest {
        val outcomes = ArrayDeque<ProtectedSyncRequestPlanningOutcome>().apply {
            add(
                ProtectedSyncRequestPlanningOutcome.Created(
                    plan = DurableSyncRequestPlan.CreatePush,
                    kind = DurableSyncRequestKind.PUSH,
                    request = PersistedDurableRequestRef(
                        endpointId = PUSH_CANDIDATE.endpointId,
                        requestIdentity = PUSH_CANDIDATE.requestIdentity,
                    ),
                ),
            )
            add(retained(DurableSyncRequestKind.PUSH, PUSH_CANDIDATE))
            add(retained(DurableSyncRequestKind.PUSH, PUSH_CANDIDATE))
            add(noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING))
        }
        var dispatchCalls = 0
        val port = port(
            planning = { outcomes.removeFirst() },
            dispatch = {
                dispatchCalls += 1
                ProtectedSyncDispatchDisposition.PROGRESSED
            },
        )

        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(1, dispatchCalls)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun aCreatedRequestWithDifferentRetainedIdentityFailsClosed() = runTest {
        val outcomes = ArrayDeque<ProtectedSyncRequestPlanningOutcome>().apply {
            add(
                ProtectedSyncRequestPlanningOutcome.Created(
                    plan = DurableSyncRequestPlan.CreatePush,
                    kind = DurableSyncRequestKind.PUSH,
                    request = PersistedDurableRequestRef(
                        endpointId = PUSH_CANDIDATE.endpointId,
                        requestIdentity = PUSH_CANDIDATE.requestIdentity,
                    ),
                ),
            )
            add(
                retained(
                    DurableSyncRequestKind.PUSH,
                    PUSH_CANDIDATE.copy(requestIdentity = OTHER_REQUEST_ID),
                ),
            )
        }
        var dispatchCalls = 0
        val port = port(
            planning = { outcomes.removeFirst() },
            dispatch = {
                dispatchCalls += 1
                ProtectedSyncDispatchDisposition.PROGRESSED
            },
        )

        assertEquals(
            SyncWorkExecutionDisposition.PERMANENT_FAILURE,
            port.runOneBoundedSync(),
        )
        assertEquals(0, dispatchCalls)
    }

    @Test
    fun pullContinuationCanAdvanceButACompletedPullStopsTheCycle() = runTest {
        var candidate: SyncRunnableRequestCandidate? = PULL_CANDIDATE
        var dispatchCalls = 0
        val port = port(
            planning = {
                candidate?.let { retained(DurableSyncRequestKind.PULL, it) }
                    ?: noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
            },
            dispatch = {
                dispatchCalls += 1
                if (dispatchCalls == 1) {
                    candidate = PULL_CANDIDATE.copy(requestIdentity = OTHER_REQUEST_ID)
                    ProtectedSyncDispatchDisposition.PULL_CONTINUATION_READY
                } else {
                    candidate = null
                    ProtectedSyncDispatchDisposition.PULL_CYCLE_COMPLETE
                }
            },
        )

        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(2, dispatchCalls)
    }

    @Test
    fun boundedPullProgressRequestsAPromptFreshFollowUpWithoutRetryBackoff() = runTest {
        var candidate: SyncRunnableRequestCandidate? = PULL_CANDIDATE
        var dispatchCalls = 0
        val auth = FakeAuthBoundary()
        val port = port(
            auth = auth,
            planning = {
                candidate?.let { retained(DurableSyncRequestKind.PULL, it) }
                    ?: noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
            },
            dispatch = {
                dispatchCalls += 1
                when (dispatchCalls) {
                    1 -> {
                        candidate = PULL_CANDIDATE.copy(
                            requestIdentity = OTHER_REQUEST_ID,
                        )
                        ProtectedSyncDispatchDisposition.PULL_CONTINUATION_READY
                    }

                    2 -> {
                        candidate = PULL_CANDIDATE.copy(
                            requestIdentity = THIRD_REQUEST_ID,
                        )
                        ProtectedSyncDispatchDisposition.PULL_CONTINUATION_READY
                    }

                    else -> {
                        candidate = null
                        ProtectedSyncDispatchDisposition.PULL_CYCLE_COMPLETE
                    }
                }
            },
        )

        assertEquals(
            SyncWorkExecutionDisposition.FOLLOW_UP_REQUIRED,
            port.runOneBoundedSync(),
        )
        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(1, auth.ensureAccessCalls)
        assertEquals(3, dispatchCalls)
    }

    @Test
    fun repeatedAuthorityAfterClaimedProgressIsDetectedWithoutASecondSend() = runTest {
        var dispatchCalls = 0
        val port = port(
            planning = { retained(DurableSyncRequestKind.PUSH, PUSH_CANDIDATE) },
            dispatch = {
                dispatchCalls += 1
                ProtectedSyncDispatchDisposition.PROGRESSED
            },
        )

        assertEquals(
            SyncWorkExecutionDisposition.PERMANENT_FAILURE,
            port.runOneBoundedSync(),
        )
        assertEquals(1, dispatchCalls)
    }

    @Test
    fun durableCredentialCommitWithoutProcessAccessDoesNotRotateAgain() = runTest {
        var requestRecoveryCalls = 0
        var planningCalls = 0
        var dispatchCalls = 0
        val auth = FakeAuthBoundary(
            ensureAccessBlock = {
                M2AuthRuntimeResult.DurableCredentialsCommitted(ACCESS_KEY)
            },
        )
        val port = port(
            auth = auth,
            requestRecovery = {
                requestRecoveryCalls += 1
                M2SyncRequestRecoveryResult(0, false)
            },
            planning = {
                planningCalls += 1
                noRequest(DurableSyncNoRequestReason.REFRESH_REQUIRED)
            },
            dispatch = {
                dispatchCalls += 1
                ProtectedSyncDispatchDisposition.PROGRESSED
            },
        )

        assertEquals(
            SyncWorkExecutionDisposition.PERMANENT_FAILURE,
            port.runOneBoundedSync(),
        )
        val countersAfterDurableCommit = listOf(
            auth.recoveryCalls,
            auth.ensureAccessCalls,
            requestRecoveryCalls,
            planningCalls,
            dispatchCalls,
        )
        assertEquals(
            SyncWorkExecutionDisposition.PERMANENT_FAILURE,
            port.runOneBoundedSync(),
        )
        assertEquals(
            countersAfterDurableCommit,
            listOf(
                auth.recoveryCalls,
                auth.ensureAccessCalls,
                requestRecoveryCalls,
                planningCalls,
                dispatchCalls,
            ),
        )
        assertEquals(1, auth.ensureAccessCalls)
        assertEquals(0, dispatchCalls)

        var requestPresentAfterRestart = true
        var dispatchCallsAfterRestart = 0
        val authAfterRestart = FakeAuthBoundary()
        val portAfterRestart = port(
            auth = authAfterRestart,
            planning = {
                if (requestPresentAfterRestart) {
                    retained(DurableSyncRequestKind.PUSH, PUSH_CANDIDATE)
                } else {
                    noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
                }
            },
            dispatch = {
                dispatchCallsAfterRestart += 1
                requestPresentAfterRestart = false
                ProtectedSyncDispatchDisposition.PROGRESSED
            },
        )

        assertEquals(
            SyncWorkExecutionDisposition.COMPLETE,
            portAfterRestart.runOneBoundedSync(),
        )
        assertEquals(1, authAfterRestart.recoveryCalls)
        assertEquals(1, authAfterRestart.ensureAccessCalls)
        assertEquals(1, dispatchCallsAfterRestart)
    }

    @Test
    fun noProgressClearsCachedAuthorityBeforeTheBackedOffRetry() = runTest {
        var requestPresent = true
        var dispatchCalls = 0
        val auth = FakeAuthBoundary()
        val port = port(
            auth = auth,
            planning = {
                if (requestPresent) retained(DurableSyncRequestKind.PUSH, PUSH_CANDIDATE)
                else noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
            },
            dispatch = {
                dispatchCalls += 1
                if (dispatchCalls == 1) {
                    ProtectedSyncDispatchDisposition.NO_PROGRESS
                } else {
                    requestPresent = false
                    ProtectedSyncDispatchDisposition.PROGRESSED
                }
            },
        )

        assertEquals(SyncWorkExecutionDisposition.RETRY, port.runOneBoundedSync())
        assertEquals(SyncWorkExecutionDisposition.COMPLETE, port.runOneBoundedSync())
        assertEquals(2, auth.ensureAccessCalls)
        assertEquals(2, dispatchCalls)
    }

    @Test
    fun attemptLeaseIsClampedToTheDurableDeadline() = runTest {
        var observedAttemptId: String? = null
        var observedAttemptedAtUtc: String? = null
        var observedLeaseEnd: Long? = null
        val deadline = FIXED_NOW.toEpochMilli() + 1_000L
        val dispatch = ProductionProtectedDurableSyncDispatchBoundary(
            delegate = ProtectedDurableDispatchPort {
                _, attemptId, attemptedAtUtc, leaseExpiresAtEpochMs ->
                observedAttemptId = attemptId
                observedAttemptedAtUtc = attemptedAtUtc
                observedLeaseEnd = leaseExpiresAtEpochMs
                ProtectedDurableDispatchResult.NO_PROGRESS
            },
            clock = FIXED_CLOCK,
            uuidGenerator = FIXED_UUID_GENERATOR,
            policy = ProtectedSyncAttemptPolicy(leaseDurationMillis = 120_000L),
        )

        assertEquals(
            ProtectedSyncDispatchDisposition.NO_PROGRESS,
            dispatch.dispatch(PUSH_CANDIDATE.copy(deadlineAtEpochMs = deadline)),
        )
        assertEquals(FIXED_ATTEMPT_ID, observedAttemptId)
        assertEquals(FIXED_NOW.toString(), observedAttemptedAtUtc)
        assertEquals(deadline, observedLeaseEnd)
        assertFalse(dispatch.toString().contains(FIXED_ATTEMPT_ID))
    }

    @Test
    fun protectedDispatchResultsMapExhaustivelyToCoordinatorDispositions() = runTest {
        val expected = listOf(
            ProtectedDurableDispatchResult.PROGRESSED to
                ProtectedSyncDispatchDisposition.PROGRESSED,
            ProtectedDurableDispatchResult.PULL_CONTINUATION_READY to
                ProtectedSyncDispatchDisposition.PULL_CONTINUATION_READY,
            ProtectedDurableDispatchResult.PULL_CYCLE_COMPLETE to
                ProtectedSyncDispatchDisposition.PULL_CYCLE_COMPLETE,
            ProtectedDurableDispatchResult.RETRY_LATER to
                ProtectedSyncDispatchDisposition.RETRY_LATER,
            ProtectedDurableDispatchResult.USER_ACTION_REQUIRED to
                ProtectedSyncDispatchDisposition.USER_ACTION_REQUIRED,
            ProtectedDurableDispatchResult.NO_PROGRESS to
                ProtectedSyncDispatchDisposition.NO_PROGRESS,
        )

        expected.forEach { (protectedResult, expectedResult) ->
            val dispatch = ProductionProtectedDurableSyncDispatchBoundary(
                delegate = ProtectedDurableDispatchPort { _, _, _, _ -> protectedResult },
                clock = FIXED_CLOCK,
                uuidGenerator = FIXED_UUID_GENERATOR,
            )
            assertEquals(expectedResult, dispatch.dispatch(PUSH_CANDIDATE))
        }
    }

    @Test
    fun cancellationFromAuthOrDispatchPropagates() = runTest {
        assertCancellationPropagates(
            port(
                auth = FakeAuthBoundary(
                    ensureAccessBlock = {
                        throw CancellationException("synthetic auth cancellation")
                    },
                ),
                planning = { noRequest(DurableSyncNoRequestReason.REFRESH_REQUIRED) },
            )::runOneBoundedSync,
        )
        assertCancellationPropagates(
            port(
                planning = { retained(DurableSyncRequestKind.PUSH, PUSH_CANDIDATE) },
                dispatch = {
                    throw CancellationException("synthetic dispatch cancellation")
                },
            )::runOneBoundedSync,
        )
    }

    private fun port(
        auth: M2SyncAuthRuntimeBoundary = FakeAuthBoundary(),
        requestRecovery: M2SyncRequestRecoveryBoundary =
            M2SyncRequestRecoveryBoundary { M2SyncRequestRecoveryResult(0, false) },
        planning: ProtectedSyncPlanningBoundary = ProtectedSyncPlanningBoundary {
            noRequest(DurableSyncNoRequestReason.AUTHORITY_MISSING)
        },
        dispatch: ProtectedDurableSyncDispatchBoundary =
            ProtectedDurableSyncDispatchBoundary {
                ProtectedSyncDispatchDisposition.NO_PROGRESS
            },
    ) = M2SyncWorkExecutionPort(
        auth = auth,
        requestRecovery = requestRecovery,
        planning = planning,
        dispatch = dispatch,
        clock = FIXED_CLOCK,
    )

    private suspend fun assertCancellationPropagates(block: suspend () -> Unit) {
        try {
            block()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private class FakeAuthBoundary(
        private val recoveryResults: ArrayDeque<M2AuthRuntimeResult> = ArrayDeque(
            listOf(M2AuthRuntimeResult.RecoveryComplete(0)),
        ),
        private val ensureAccessBlock: suspend () -> M2AuthRuntimeResult = {
            M2AuthRuntimeResult.AccessReady(ACCESS_KEY, AuthAccessSource.VAULT)
        },
    ) : M2SyncAuthRuntimeBoundary {
        var recoveryCalls = 0
            private set
        var ensureAccessCalls = 0
            private set

        override suspend fun recoverInterrupted(): M2AuthRuntimeResult {
            recoveryCalls += 1
            return recoveryResults.removeFirst()
        }

        override suspend fun ensureAccess(): M2AuthRuntimeResult {
            ensureAccessCalls += 1
            return ensureAccessBlock()
        }
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-08-03T00:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        const val CREDENTIAL_EPOCH_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DEVICE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val PUSH_REQUEST_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val PULL_REQUEST_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val OTHER_REQUEST_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val THIRD_REQUEST_ID = "12121212-1212-4212-8212-121212121212"
        const val FIXED_ATTEMPT_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val FIXED_UUID_GENERATOR = UuidGenerator { UUID.fromString(FIXED_ATTEMPT_ID) }
        val ACCESS_KEY = AccessTokenKey(CREDENTIAL_EPOCH_ID, 3L)
        val PUSH_CANDIDATE = candidate(M2Endpoint.SYNC_PUSH, PUSH_REQUEST_ID)
        val PULL_CANDIDATE = candidate(M2Endpoint.SYNC_PULL, PULL_REQUEST_ID)

        fun candidate(
            endpoint: M2Endpoint,
            requestIdentity: String,
        ) = SyncRunnableRequestCandidate(
            endpointId = endpoint.endpointId,
            requestIdentity = requestIdentity,
            credentialEpochId = CREDENTIAL_EPOCH_ID,
            deviceId = DEVICE_ID,
            accessGenerationUsed = ACCESS_KEY.accessGeneration,
            state = "ready",
            attemptCount = 0,
            attemptBudget = 8,
            deadlineAtEpochMs = FIXED_NOW.toEpochMilli() + 600_000L,
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            leaseExpiresAtEpochMs = null,
            activeAttemptId = null,
            scheduledAtEpochMs = FIXED_NOW.toEpochMilli(),
            routePriority = 0,
        )

        fun retained(
            kind: DurableSyncRequestKind,
            candidate: SyncRunnableRequestCandidate,
        ) = ProtectedSyncRequestPlanningOutcome.Retained(
            plan = DurableSyncRequestPlan.RetainExisting(kind),
            candidate = candidate,
        )

        fun noRequest(
            reason: DurableSyncNoRequestReason,
        ) = ProtectedSyncRequestPlanningOutcome.NoRequest(
            DurableSyncRequestPlan.NoRequest(reason),
        )
    }
}
