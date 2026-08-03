package ru.andriyshkoy.lifeagent

import android.app.Application
import java.io.Closeable
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionPort
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionPortProvider

internal class LifeAgentApplication : Application(), SyncWorkExecutionPortProvider {
    private val storage = ProcessScopedAppStorage(
        open = { AppContainer(this) },
        afterOpened = AppContainer::enqueueSyncAtStartup,
    )

    /**
     * Resolve this from a background dispatcher. Initialization opens
     * Keystore, loads SQLCipher, and verifies the encrypted database.
     */
    fun openStorage(): Result<AppContainer> = storage.open()

    override suspend fun openSyncWorkExecutionPort(): SyncWorkExecutionPort =
        openStorage().getOrThrow().syncWorkExecutionPort

    override fun onTerminate() {
        storage.closeIfOpened()
        super.onTerminate()
    }

    override fun toString(): String = "LifeAgentApplication(redacted=true)"
}

/** Thread-safe process owner that runs non-authoritative startup work once. */
internal class ProcessScopedAppStorage<T : Closeable>(
    open: () -> T,
    private val afterOpened: (T) -> Unit,
) {
    private val closeLock = Any()
    private var closedSuccessfully = false
    private val storage: Lazy<Result<T>> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val result = runCatching(open)
        result.getOrNull()?.let { opened ->
            try {
                afterOpened(opened)
            } catch (_: Exception) {
                // A secondary startup action cannot invalidate opened local storage.
            }
        }
        result
    }

    fun open(): Result<T> = storage.value

    fun closeIfOpened() {
        synchronized(closeLock) {
            if (closedSuccessfully || !storage.isInitialized()) return
            storage.value.getOrNull()?.close()
            closedSuccessfully = true
        }
    }

    override fun toString(): String = "ProcessScopedAppStorage(redacted=true)"
}
