package ru.andriyshkoy.lifeagent.data.sync.work

import androidx.work.Data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SyncWorkExecutionTest {
    @Test
    fun opaqueDispositionMapsToPureWorkerCompletion() = runTest {
        listOf(
            SyncWorkExecutionDisposition.COMPLETE to SyncWorkerCompletion.SUCCESS,
            SyncWorkExecutionDisposition.RETRY to SyncWorkerCompletion.RETRY,
            SyncWorkExecutionDisposition.PERMANENT_FAILURE to SyncWorkerCompletion.FAILURE,
        ).forEach { (disposition, expected) ->
            var openCount = 0
            var runCount = 0

            val actual = executeSyncWork {
                openCount += 1
                SyncWorkExecutionPort {
                    runCount += 1
                    disposition
                }
            }

            assertEquals(expected, actual)
            assertEquals(1, openCount)
            assertEquals(1, runCount)
            assertEquals(Data.EMPTY, actual.toWorkManagerResult().outputData)
        }
    }

    @Test
    fun boundedProgressEnqueuesOneFreshFollowUpWithoutWorkDataOrBackoff() = runTest {
        var enqueueCount = 0
        val completion = executeSyncWork(
            enqueueFollowUp = {
                enqueueCount += 1
                true
            },
        ) {
            SyncWorkExecutionPort {
                SyncWorkExecutionDisposition.FOLLOW_UP_REQUIRED
            }
        }

        assertEquals(SyncWorkerCompletion.SUCCESS, completion)
        assertEquals(1, enqueueCount)
        assertEquals(Data.EMPTY, completion.toWorkManagerResult().outputData)

        val unavailableFollowUp = executeSyncWork(enqueueFollowUp = { false }) {
            SyncWorkExecutionPort {
                SyncWorkExecutionDisposition.FOLLOW_UP_REQUIRED
            }
        }
        assertEquals(SyncWorkerCompletion.RETRY, unavailableFollowUp)
        assertEquals(Data.EMPTY, unavailableFollowUp.toWorkManagerResult().outputData)

        val failedFollowUp = executeSyncWork(
            enqueueFollowUp = { throw IllegalStateException("synthetic enqueue failure") },
        ) {
            SyncWorkExecutionPort {
                SyncWorkExecutionDisposition.FOLLOW_UP_REQUIRED
            }
        }
        assertEquals(SyncWorkerCompletion.RETRY, failedFollowUp)
    }

    @Test
    fun unexpectedOpenOrPortFailureFailsClosed() = runTest {
        assertEquals(
            SyncWorkerCompletion.FAILURE,
            executeSyncWork { throw IllegalStateException("synthetic open failure") },
        )
        assertEquals(
            SyncWorkerCompletion.FAILURE,
            executeSyncWork {
                SyncWorkExecutionPort {
                    throw IllegalStateException("synthetic execution failure")
                }
            },
        )
    }

    @Test
    fun cancellationFromOpenOrExecutionPropagates() = runTest {
        assertCancellationPropagates {
            executeSyncWork { throw CancellationException("synthetic open cancellation") }
        }
        assertCancellationPropagates {
            executeSyncWork {
                SyncWorkExecutionPort {
                    throw CancellationException("synthetic execution cancellation")
                }
            }
        }
        assertCancellationPropagates {
            executeSyncWork(
                enqueueFollowUp = {
                    throw CancellationException("synthetic enqueue cancellation")
                },
            ) {
                SyncWorkExecutionPort {
                    SyncWorkExecutionDisposition.FOLLOW_UP_REQUIRED
                }
            }
        }
    }

    private suspend fun assertCancellationPropagates(block: suspend () -> Unit) {
        try {
            block()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: WorkManager cancellation must reach the coordinator and transport.
        }
    }
}
