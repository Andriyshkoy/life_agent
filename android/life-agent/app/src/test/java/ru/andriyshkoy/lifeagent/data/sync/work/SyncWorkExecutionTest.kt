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
