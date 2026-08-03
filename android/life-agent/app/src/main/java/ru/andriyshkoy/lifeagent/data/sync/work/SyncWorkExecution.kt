package ru.andriyshkoy.lifeagent.data.sync.work

import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException

/** Opaque adapter result; durable bodies, credentials and identifiers stay behind the port. */
internal enum class SyncWorkExecutionDisposition {
    COMPLETE,
    RETRY,
    PERMANENT_FAILURE,
}

internal fun interface SyncWorkExecutionPort {
    suspend fun runOneBoundedSync(): SyncWorkExecutionDisposition
}

/** Implemented by the Application only after production coordinator adapters exist. */
internal interface SyncWorkExecutionPortProvider {
    suspend fun openSyncWorkExecutionPort(): SyncWorkExecutionPort
}

/** Pure worker policy, kept separate from WorkManager construction for focused tests. */
internal enum class SyncWorkerCompletion {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal suspend fun executeSyncWork(
    openPort: suspend () -> SyncWorkExecutionPort,
): SyncWorkerCompletion = try {
    when (openPort().runOneBoundedSync()) {
        SyncWorkExecutionDisposition.COMPLETE -> SyncWorkerCompletion.SUCCESS
        SyncWorkExecutionDisposition.RETRY -> SyncWorkerCompletion.RETRY
        SyncWorkExecutionDisposition.PERMANENT_FAILURE -> SyncWorkerCompletion.FAILURE
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    SyncWorkerCompletion.FAILURE
}

internal fun SyncWorkerCompletion.toWorkManagerResult(): ListenableWorker.Result =
    when (this) {
        SyncWorkerCompletion.SUCCESS -> ListenableWorker.Result.success()
        SyncWorkerCompletion.RETRY -> ListenableWorker.Result.retry()
        SyncWorkerCompletion.FAILURE -> ListenableWorker.Result.failure()
    }
