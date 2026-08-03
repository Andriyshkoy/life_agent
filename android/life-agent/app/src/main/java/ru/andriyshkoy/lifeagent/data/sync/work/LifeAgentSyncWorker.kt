package ru.andriyshkoy.lifeagent.data.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

/**
 * Stable WorkManager entry point for bounded sync execution.
 *
 * Until the Application implements [SyncWorkExecutionPortProvider], direct or
 * stale invocations fail closed without opening storage or constructing HTTP.
 */
internal class LifeAgentSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): ListenableWorker.Result {
        val provider = applicationContext as? SyncWorkExecutionPortProvider
            ?: return ListenableWorker.Result.failure()
        return executeSyncWork(provider::openSyncWorkExecutionPort)
            .toWorkManagerResult()
    }
}
