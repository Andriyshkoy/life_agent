package ru.andriyshkoy.lifeagent

import android.app.Application

class LifeAgentApplication : Application() {
    private val storage: Lazy<Result<AppContainer>> = lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        runCatching { AppContainer(this) }
    }

    /**
     * Resolve this from a background dispatcher. Initialization opens
     * Keystore, loads SQLCipher, and verifies the encrypted database.
     */
    fun openStorage(): Result<AppContainer> = storage.value

    override fun onTerminate() {
        if (storage.isInitialized()) {
            storage.value.getOrNull()?.close()
        }
        super.onTerminate()
    }
}
