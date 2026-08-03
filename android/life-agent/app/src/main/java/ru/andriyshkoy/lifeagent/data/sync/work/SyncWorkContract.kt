package ru.andriyshkoy.lifeagent.data.sync.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/** Stable, content-free WorkManager contract for one bounded sync run. */
internal object SyncWorkContract {
    const val UNIQUE_WORK_NAME = "life-agent-sync-v1"
    const val WORK_TAG = "life-agent-sync-v1"

    fun newRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<LifeAgentSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(Data.EMPTY)
            .addTag(WORK_TAG)
            .build()
}
