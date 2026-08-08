package ru.andriyshkoy.lifeagent

import android.app.Application
import java.io.Closeable

internal class LifeAgentApplication : Application() {
    private val storage = ProcessScopedAppStorage(
        open = { AppContainer(this) },
    )

    /**
     * Resolve this from a background dispatcher. Initialization opens
     * Keystore, loads SQLCipher, and verifies the encrypted database.
     */
    fun openStorage(): Result<AppContainer> = storage.open()

    override fun onTerminate() {
        storage.closeIfOpened()
        super.onTerminate()
    }

    override fun toString(): String = "LifeAgentApplication(redacted=true)"
}

/** Thread-safe process owner for the local application graph. */
internal class ProcessScopedAppStorage<T : Closeable>(
    open: () -> T,
) {
    private val closeLock = Any()
    private var closedSuccessfully = false
    private val storage: Lazy<Result<T>> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching(open)
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
