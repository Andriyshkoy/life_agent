package ru.andriyshkoy.lifeagent.data.sync.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import ru.andriyshkoy.lifeagent.data.sync.transport.M2HttpsDeploymentPresence
import ru.andriyshkoy.lifeagent.data.sync.transport.m2HttpsDeploymentPresenceFromBuildConfig

internal enum class SyncWorkSchedulingResult {
    ENQUEUED,
    NOT_CONFIGURED,
    MISCONFIGURED,
}

internal fun interface UniqueSyncWorkEnqueuer {
    fun enqueue(
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )
}

private class WorkManagerUniqueSyncWorkEnqueuer(
    context: Context,
) : UniqueSyncWorkEnqueuer {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(uniqueWorkName, policy, request)
    }
}

/**
 * One coalescing lane shared by future startup and manual "Sync now" calls.
 *
 * This class is deliberately not wired into Application yet. Configuration
 * presence is checked before a WorkRequest is allocated or persisted.
 */
internal class SyncWorkScheduler internal constructor(
    private val deploymentPresence: () -> M2HttpsDeploymentPresence,
    private val enqueuer: UniqueSyncWorkEnqueuer,
) {
    constructor(context: Context) : this(
        deploymentPresence = ::m2HttpsDeploymentPresenceFromBuildConfig,
        enqueuer = WorkManagerUniqueSyncWorkEnqueuer(context),
    )

    fun enqueueAtStartup(): SyncWorkSchedulingResult = enqueueCoalesced()

    fun enqueueNow(): SyncWorkSchedulingResult = enqueueCoalesced()

    fun enqueueFollowUp(): SyncWorkSchedulingResult =
        enqueueOneBoundedRun(ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueueCoalesced(): SyncWorkSchedulingResult =
        enqueueOneBoundedRun(ExistingWorkPolicy.KEEP)

    private fun enqueueOneBoundedRun(
        policy: ExistingWorkPolicy,
    ): SyncWorkSchedulingResult =
        when (deploymentPresence()) {
            M2HttpsDeploymentPresence.ABSENT -> SyncWorkSchedulingResult.NOT_CONFIGURED
            M2HttpsDeploymentPresence.PARTIAL -> SyncWorkSchedulingResult.MISCONFIGURED
            M2HttpsDeploymentPresence.PRESENT_UNVALIDATED -> {
                enqueuer.enqueue(
                    uniqueWorkName = SyncWorkContract.UNIQUE_WORK_NAME,
                    policy = policy,
                    request = SyncWorkContract.newRequest(),
                )
                SyncWorkSchedulingResult.ENQUEUED
            }
        }

    override fun toString(): String = "SyncWorkScheduler(redacted=true)"
}
