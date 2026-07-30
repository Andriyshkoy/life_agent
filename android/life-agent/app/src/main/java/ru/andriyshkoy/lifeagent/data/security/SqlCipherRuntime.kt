package ru.andriyshkoy.lifeagent.data.security

import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import java.util.concurrent.atomic.AtomicBoolean

object SqlCipherRuntime {
    private val initialized = AtomicBoolean(false)

    @Synchronized
    fun initialize() {
        if (initialized.get()) return

        System.loadLibrary("sqlcipher")
        Logger.setTarget(NoopTarget())
        initialized.set(true)
    }
}
