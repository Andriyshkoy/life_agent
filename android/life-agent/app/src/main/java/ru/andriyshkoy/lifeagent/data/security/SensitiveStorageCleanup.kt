package ru.andriyshkoy.lifeagent.data.security

/**
 * Retries cleanup after a failed close instead of permanently marking the
 * resources closed before both cleanup steps have completed successfully.
 */
internal class RetryableSensitiveStorageCloser(
    private val closeDatabase: () -> Unit,
    private val closeKey: () -> Unit,
) {
    private val lock = Any()
    private var closedSuccessfully = false

    fun close() {
        synchronized(lock) {
            if (closedSuccessfully) return

            val failure = closeDatabaseThenKey(
                closeDatabase = closeDatabase,
                closeKey = closeKey,
            )
            if (failure != null) {
                throw failure
            }
            closedSuccessfully = true
        }
    }
}

/**
 * Closes the database before destroying its key, while guaranteeing that key
 * cleanup runs even when database cleanup fails.
 *
 * If [primaryFailure] is supplied it remains the thrown failure and cleanup
 * failures are attached to it. Otherwise the first cleanup failure is primary.
 */
internal fun closeDatabaseThenKey(
    primaryFailure: Throwable? = null,
    closeDatabase: () -> Unit,
    closeKey: () -> Unit,
): Throwable? {
    var failure = primaryFailure

    try {
        try {
            closeDatabase()
        } catch (closeFailure: Throwable) {
            failure = failure.merge(closeFailure)
        }
    } finally {
        try {
            closeKey()
        } catch (closeFailure: Throwable) {
            failure = failure.merge(closeFailure)
        }
    }

    return failure
}

private fun Throwable?.merge(next: Throwable): Throwable {
    val current = this ?: return next
    if (current !== next) {
        current.addSuppressed(next)
    }
    return current
}
