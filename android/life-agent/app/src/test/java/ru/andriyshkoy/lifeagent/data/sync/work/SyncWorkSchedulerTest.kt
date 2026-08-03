package ru.andriyshkoy.lifeagent.data.sync.work

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.sync.transport.M2HttpsDeploymentPresence

class SyncWorkSchedulerTest {
    @Test
    fun startupAndManualCallsUseOneContentFreeKeepLane() {
        val enqueued = mutableListOf<EnqueuedWork>()
        val scheduler = scheduler(
            presence = M2HttpsDeploymentPresence.PRESENT_UNVALIDATED,
            enqueued = enqueued,
        )

        assertEquals(SyncWorkSchedulingResult.ENQUEUED, scheduler.enqueueAtStartup())
        assertEquals(SyncWorkSchedulingResult.ENQUEUED, scheduler.enqueueNow())

        assertEquals(2, enqueued.size)
        enqueued.forEach { captured ->
            assertEquals(SyncWorkContract.UNIQUE_WORK_NAME, captured.uniqueWorkName)
            assertEquals(ExistingWorkPolicy.KEEP, captured.policy)
            assertRequestPolicy(captured.request)
        }
    }

    @Test
    fun absentAndPartialDeploymentNeverAllocateOrEnqueueWork() {
        listOf(
            M2HttpsDeploymentPresence.ABSENT to SyncWorkSchedulingResult.NOT_CONFIGURED,
            M2HttpsDeploymentPresence.PARTIAL to SyncWorkSchedulingResult.MISCONFIGURED,
        ).forEach { (presence, expected) ->
            val enqueued = mutableListOf<EnqueuedWork>()
            val scheduler = scheduler(presence, enqueued)

            assertEquals(expected, scheduler.enqueueAtStartup())
            assertEquals(expected, scheduler.enqueueNow())
            assertTrue(enqueued.isEmpty())
        }
    }

    @Test
    fun requestHasOnlyConnectedConstraintAndMinimumExponentialBackoff() {
        assertRequestPolicy(SyncWorkContract.newRequest())
    }

    private fun assertRequestPolicy(request: OneTimeWorkRequest) {
        val workSpec = request.workSpec
        val constraints = workSpec.constraints

        assertEquals(LifeAgentSyncWorker::class.java.name, workSpec.workerClassName)
        assertEquals(Data.EMPTY, workSpec.input)
        assertEquals(0L, workSpec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
        assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, workSpec.backoffDelayDuration)
        assertFalse(workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresDeviceIdle())
        assertFalse(constraints.requiresStorageNotLow())
        assertTrue(request.tags.contains(SyncWorkContract.WORK_TAG))
    }

    private fun scheduler(
        presence: M2HttpsDeploymentPresence,
        enqueued: MutableList<EnqueuedWork>,
    ) = SyncWorkScheduler(
        deploymentPresence = { presence },
        enqueuer = UniqueSyncWorkEnqueuer { name, policy, request ->
            enqueued += EnqueuedWork(name, policy, request)
        },
    )

    private data class EnqueuedWork(
        val uniqueWorkName: String,
        val policy: ExistingWorkPolicy,
        val request: OneTimeWorkRequest,
    )
}
