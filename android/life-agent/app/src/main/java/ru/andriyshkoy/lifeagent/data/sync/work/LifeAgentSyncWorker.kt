package ru.andriyshkoy.lifeagent.data.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

/**
 * Stable WorkManager entry point for bounded sync execution.
 *
 * Direct or stale invocations still require the application-owned opaque port;
 * the worker never constructs storage, credentials, request bodies or HTTP.
 */
internal class LifeAgentSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): ListenableWorker.Result {
        val provider = applicationContext as? SyncWorkExecutionPortProvider
            ?: return ListenableWorker.Result.failure()
        return executeSyncWork(
            enqueueFollowUp = {
                SyncWorkScheduler(applicationContext).enqueueFollowUp() ==
                    SyncWorkSchedulingResult.ENQUEUED
            },
            openPort = provider::openSyncWorkExecutionPort,
        )
            .toWorkManagerResult()
    }
}
