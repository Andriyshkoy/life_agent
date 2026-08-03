package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedSyncCoordinatorTest {
    @Test
    fun recoveryIsFirstAndRunStopsAtFourTotalTransitions() = runTest {
        val events = mutableListOf<String>()
        val snapshots = ArrayDeque(
            listOf(
                snapshot(
                    candidate(SyncCoordinatorAction.PULL, "pull-ignored"),
                    candidate(SyncCoordinatorAction.PUSH, "push-ignored"),
                    candidate(SyncCoordinatorAction.BOOTSTRAP, "bootstrap-1"),
                    candidate(
                        SyncCoordinatorAction.REFRESH_MISSING_ACCESS_TOKEN,
                        "refresh-1",
                    ),
                    candidate(SyncCoordinatorAction.REVOKE, "revoke-1"),
                ),
                snapshot(
                    candidate(SyncCoordinatorAction.PULL, "pull-ignored"),
                    candidate(SyncCoordinatorAction.PUSH, "push-ignored"),
                    candidate(SyncCoordinatorAction.BOOTSTRAP, "bootstrap-1"),
                    candidate(
                        SyncCoordinatorAction.REFRESH_MISSING_ACCESS_TOKEN,
                        "refresh-1",
                    ),
                ),
                snapshot(
                    candidate(SyncCoordinatorAction.PULL, "pull-ignored"),
                    candidate(SyncCoordinatorAction.PUSH, "push-ignored"),
                    candidate(SyncCoordinatorAction.BOOTSTRAP, "bootstrap-1"),
                ),
            ),
        )
        val coordinator = BoundedSyncCoordinator(
            recovery = SyncCoordinatorRecoveryPort {
                events += "recovery"
                SyncCoordinatorRecoveryDisposition.READY
            },
            actionSource = SyncCoordinatorActionSource {
                snapshots.removeFirstOrNull() ?: snapshot()
            },
            actionPort = SyncCoordinatorActionPort { selected ->
                events += selected.action.name
                SyncCoordinatorActionDisposition.PROGRESSED
            },
            executionMutex = Mutex(),
        )

        val outcome = coordinator.run()

        assertEquals(
            listOf(
                "recovery",
                "REVOKE",
                "REFRESH_MISSING_ACCESS_TOKEN",
                "BOOTSTRAP",
            ),
            events,
        )
        assertEquals(4, outcome.transitionCount)
        assertEquals(SyncCoordinatorStopReason.TRANSITION_LIMIT, outcome.stopReason)
    }

    @Test
    fun durablePriorityIsBootstrapThenPushThenPull() = runTest {
        val selected = mutableListOf<SyncCoordinatorAction>()
        val snapshots = ArrayDeque(
            listOf(
                snapshot(
                    candidate(SyncCoordinatorAction.PULL, "pull-1"),
                    candidate(SyncCoordinatorAction.PUSH, "push-1"),
                    candidate(SyncCoordinatorAction.BOOTSTRAP, "bootstrap-1"),
                ),
                snapshot(
                    candidate(SyncCoordinatorAction.PULL, "pull-1"),
                    candidate(SyncCoordinatorAction.PUSH, "push-1"),
                ),
                snapshot(candidate(SyncCoordinatorAction.PULL, "pull-1")),
            ),
        )
        val coordinator = coordinator(
            actionSource = {
                snapshots.removeFirstOrNull() ?: snapshot()
            },
            action = {
                selected += it.action
                if (it.action == SyncCoordinatorAction.PULL) {
                    SyncCoordinatorActionDisposition.PULL_CYCLE_COMPLETE
                } else {
                    SyncCoordinatorActionDisposition.PROGRESSED
                }
            },
        )

        val outcome = coordinator.run()

        assertEquals(
            listOf(
                SyncCoordinatorAction.BOOTSTRAP,
                SyncCoordinatorAction.PUSH,
                SyncCoordinatorAction.PULL,
            ),
            selected,
        )
        assertEquals(4, outcome.transitionCount)
        assertEquals(SyncCoordinatorStopReason.PULL_CYCLE_COMPLETE, outcome.stopReason)
    }

    @Test
    fun repeatedAuthorityIsNotPerformedTwice() = runTest {
        val repeated = candidate(SyncCoordinatorAction.PUSH, "same-push-authority")
        var calls = 0
        val coordinator = coordinator(
            actionSource = { snapshot(repeated) },
            action = {
                calls += 1
                SyncCoordinatorActionDisposition.PROGRESSED
            },
        )

        val outcome = coordinator.run()

        assertEquals(1, calls)
        assertEquals(2, outcome.transitionCount)
        assertEquals(SyncCoordinatorStopReason.DUPLICATE_AUTHORITY, outcome.stopReason)
    }

    @Test
    fun terminalPullStopsBeforeASecondFreshPullAuthority() = runTest {
        var sourceReads = 0
        val performed = mutableListOf<String>()
        val coordinator = coordinator(
            actionSource = {
                sourceReads += 1
                snapshot(candidate(SyncCoordinatorAction.PULL, "pull-$sourceReads"))
            },
            action = {
                performed += it.deduplicationKey
                SyncCoordinatorActionDisposition.PULL_CYCLE_COMPLETE
            },
        )

        val outcome = coordinator.run()

        assertEquals(listOf("pull-1"), performed)
        assertEquals(1, sourceReads)
        assertEquals(2, outcome.transitionCount)
        assertEquals(SyncCoordinatorStopReason.PULL_CYCLE_COMPLETE, outcome.stopReason)
    }

    @Test
    fun authoritativePullContinuationAllowsExactlyTheNextPage() = runTest {
        val snapshots = ArrayDeque(
            listOf(
                snapshot(candidate(SyncCoordinatorAction.PULL, "pull-page-1")),
                snapshot(candidate(SyncCoordinatorAction.PULL, "pull-page-2")),
            ),
        )
        val performed = mutableListOf<String>()
        val coordinator = coordinator(
            actionSource = { snapshots.removeFirstOrNull() ?: snapshot() },
            action = {
                performed += it.deduplicationKey
                if (performed.size == 1) {
                    SyncCoordinatorActionDisposition.PULL_CONTINUATION_READY
                } else {
                    SyncCoordinatorActionDisposition.PULL_CYCLE_COMPLETE
                }
            },
        )

        val outcome = coordinator.run()

        assertEquals(listOf("pull-page-1", "pull-page-2"), performed)
        assertEquals(3, outcome.transitionCount)
        assertEquals(SyncCoordinatorStopReason.PULL_CYCLE_COMPLETE, outcome.stopReason)
    }

    @Test
    fun genericProgressCannotStartAnotherPullCycle() = runTest {
        var sourceReads = 0
        val coordinator = coordinator(
            actionSource = {
                sourceReads += 1
                snapshot(candidate(SyncCoordinatorAction.PULL, "fresh-pull-$sourceReads"))
            },
            action = { SyncCoordinatorActionDisposition.PROGRESSED },
        )

        val outcome = coordinator.run()

        assertEquals(1, sourceReads)
        assertEquals(2, outcome.transitionCount)
        assertEquals(
            SyncCoordinatorStopReason.INVALID_ACTION_DISPOSITION,
            outcome.stopReason,
        )
    }

    @Test
    fun pullOnlyDispositionOnAnotherActionStopsFailClosed() = runTest {
        val coordinator = coordinator(
            actionSource = {
                snapshot(candidate(SyncCoordinatorAction.PUSH, "push-1"))
            },
            action = { SyncCoordinatorActionDisposition.PULL_CONTINUATION_READY },
        )

        val outcome = coordinator.run()

        assertEquals(2, outcome.transitionCount)
        assertEquals(
            SyncCoordinatorStopReason.INVALID_ACTION_DISPOSITION,
            outcome.stopReason,
        )
    }

    @Test
    fun noProgressAndRetryDispositionStopWithoutASecondAction() = runTest {
        listOf(
            SyncCoordinatorActionDisposition.NO_PROGRESS to
                SyncCoordinatorStopReason.NO_PROGRESS,
            SyncCoordinatorActionDisposition.RETRY_LATER to
                SyncCoordinatorStopReason.ACTION_RETRY_LATER,
            SyncCoordinatorActionDisposition.USER_ACTION_REQUIRED to
                SyncCoordinatorStopReason.USER_ACTION_REQUIRED,
        ).forEach { (disposition, expectedReason) ->
            var calls = 0
            val coordinator = coordinator(
                actionSource = {
                    snapshot(candidate(SyncCoordinatorAction.PUSH, "push-$disposition"))
                },
                action = {
                    calls += 1
                    disposition
                },
            )

            val outcome = coordinator.run()

            assertEquals(1, calls)
            assertEquals(2, outcome.transitionCount)
            assertEquals(expectedReason, outcome.stopReason)
        }
    }

    @Test
    fun recoveryCanDeferOrBlockWithoutReadingActions() = runTest {
        listOf(
            SyncCoordinatorRecoveryDisposition.RETRY_LATER to
                SyncCoordinatorStopReason.RECOVERY_RETRY_LATER,
            SyncCoordinatorRecoveryDisposition.USER_ACTION_REQUIRED to
                SyncCoordinatorStopReason.USER_ACTION_REQUIRED,
        ).forEach { (disposition, expectedReason) ->
            var sourceReads = 0
            val coordinator = BoundedSyncCoordinator(
                recovery = SyncCoordinatorRecoveryPort { disposition },
                actionSource = SyncCoordinatorActionSource {
                    sourceReads += 1
                    snapshot(candidate(SyncCoordinatorAction.REVOKE, "must-not-run"))
                },
                actionPort = SyncCoordinatorActionPort {
                    fail("Action must not run when recovery did not complete")
                    SyncCoordinatorActionDisposition.NO_PROGRESS
                },
                executionMutex = Mutex(),
            )

            val outcome = coordinator.run()

            assertEquals(0, sourceReads)
            assertEquals(1, outcome.transitionCount)
            assertEquals(expectedReason, outcome.stopReason)
        }
    }

    @Test
    fun defaultProcessMutexSerializesIndependentCoordinatorInstances() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondRecoveryEntered = CompletableDeferred<Unit>()
        val activeHttpActions = AtomicInteger(0)
        val maximumActiveHttpActions = AtomicInteger(0)

        val first = BoundedSyncCoordinator(
            recovery = SyncCoordinatorRecoveryPort {
                SyncCoordinatorRecoveryDisposition.READY
            },
            actionSource = oneActionSource("first"),
            actionPort = SyncCoordinatorActionPort {
                val active = activeHttpActions.incrementAndGet()
                maximumActiveHttpActions.updateAndGet { previous -> maxOf(previous, active) }
                firstEntered.complete(Unit)
                try {
                    releaseFirst.await()
                    SyncCoordinatorActionDisposition.NO_PROGRESS
                } finally {
                    activeHttpActions.decrementAndGet()
                }
            },
        )
        val second = BoundedSyncCoordinator(
            recovery = SyncCoordinatorRecoveryPort {
                secondRecoveryEntered.complete(Unit)
                SyncCoordinatorRecoveryDisposition.READY
            },
            actionSource = oneActionSource("second"),
            actionPort = SyncCoordinatorActionPort {
                val active = activeHttpActions.incrementAndGet()
                maximumActiveHttpActions.updateAndGet { previous -> maxOf(previous, active) }
                activeHttpActions.decrementAndGet()
                SyncCoordinatorActionDisposition.NO_PROGRESS
            },
        )

        val firstRun = async { first.run() }
        firstEntered.await()
        val secondRun = async { second.run() }
        runCurrent()

        assertFalse(secondRecoveryEntered.isCompleted)
        assertEquals(1, maximumActiveHttpActions.get())

        releaseFirst.complete(Unit)
        firstRun.await()
        secondRun.await()

        assertTrue(secondRecoveryEntered.isCompleted)
        assertEquals(1, maximumActiveHttpActions.get())
    }

    @Test
    fun cancellationPropagatesAndReleasesExecutionMutex() = runTest {
        val mutex = Mutex()
        val cancelled = coordinator(
            actionSource = {
                snapshot(candidate(SyncCoordinatorAction.PUSH, "cancelled-push"))
            },
            action = { throw CancellationException("synthetic cancellation") },
            mutex = mutex,
        )

        try {
            cancelled.run()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: the coordinator must not turn cancellation into retry.
        }

        val next = coordinator(
            actionSource = { snapshot() },
            action = { SyncCoordinatorActionDisposition.NO_PROGRESS },
            mutex = mutex,
        )
        val outcome = next.run()

        assertEquals(SyncCoordinatorStopReason.IDLE, outcome.stopReason)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun cancellingCallerWhileActionIsSuspendedDoesNotStartAnotherTransition() = runTest {
        val entered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        var actionCalls = 0
        val coordinator = coordinator(
            actionSource = {
                snapshot(candidate(SyncCoordinatorAction.BOOTSTRAP, "bootstrap-cancel"))
            },
            action = {
                actionCalls += 1
                entered.complete(Unit)
                neverReleased.await()
                SyncCoordinatorActionDisposition.PROGRESSED
            },
        )

        val job = async { coordinator.run() }
        entered.await()
        job.cancelAndJoin()

        assertEquals(1, actionCalls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun diagnosticsNeverExposeOpaqueAuthorityKey() {
        val secretLookingKey = "laa_secret_authority_that_must_never_be_rendered"
        val selected = candidate(SyncCoordinatorAction.REVOKE, secretLookingKey)
        val snapshot = snapshot(selected)
        val outcome = SyncCoordinatorRunOutcome(
            transitionCount = 2,
            stopReason = SyncCoordinatorStopReason.NO_PROGRESS,
        )

        listOf(selected.toString(), snapshot.toString(), outcome.toString()).forEach { rendered ->
            assertTrue(rendered.contains("redacted=true"))
            assertFalse(rendered.contains(secretLookingKey))
        }
    }

    @Test
    fun snapshotRejectsTwoAuthoritiesForTheSameActionClass() {
        try {
            snapshot(
                candidate(SyncCoordinatorAction.PUSH, "push-1"),
                candidate(SyncCoordinatorAction.PUSH, "push-2"),
            )
            fail("Duplicate action classes must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun coordinator(
        actionSource: suspend () -> SyncCoordinatorActionSnapshot,
        action: suspend (SyncCoordinatorActionCandidate) -> SyncCoordinatorActionDisposition,
        mutex: Mutex = Mutex(),
    ) = BoundedSyncCoordinator(
        recovery = SyncCoordinatorRecoveryPort {
            SyncCoordinatorRecoveryDisposition.READY
        },
        actionSource = SyncCoordinatorActionSource { actionSource() },
        actionPort = SyncCoordinatorActionPort { action(it) },
        executionMutex = mutex,
    )

    private fun oneActionSource(key: String): SyncCoordinatorActionSource {
        var returned = false
        return SyncCoordinatorActionSource {
            if (returned) {
                snapshot()
            } else {
                returned = true
                snapshot(candidate(SyncCoordinatorAction.PUSH, key))
            }
        }
    }

    private fun snapshot(
        vararg candidates: SyncCoordinatorActionCandidate,
    ) = SyncCoordinatorActionSnapshot(candidates.toList())

    private fun candidate(
        action: SyncCoordinatorAction,
        key: String,
    ): SyncCoordinatorActionCandidate = TestCandidate(action, key)

    private class TestCandidate(
        action: SyncCoordinatorAction,
        key: String,
    ) : SyncCoordinatorActionCandidate(action, key)
}
