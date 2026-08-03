package ru.andriyshkoy.lifeagent.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SensitiveStorageCleanupTest {
    @Test
    fun initializationFailureRemainsPrimaryAndCollectsCleanupFailures() {
        val initializationFailure = IllegalStateException("initialization")
        val databaseFailure = IllegalArgumentException("database")
        val keyFailure = IllegalArgumentException("key")
        val calls = mutableListOf<String>()

        val result = closeDatabaseThenKey(
            primaryFailure = initializationFailure,
            closeDatabase = {
                calls += "database"
                throw databaseFailure
            },
            closeKey = {
                calls += "key"
                throw keyFailure
            },
        )

        assertSame(initializationFailure, result)
        assertEquals(listOf(databaseFailure, keyFailure), result?.suppressed?.toList())
        assertEquals(listOf("database", "key"), calls)
    }

    @Test
    fun databaseCloseFailureRemainsPrimaryAndKeyCleanupStillRuns() {
        val databaseFailure = IllegalStateException("database")
        val keyFailure = IllegalStateException("key")
        var keyCloseAttempted = false

        val result = closeDatabaseThenKey(
            closeDatabase = { throw databaseFailure },
            closeKey = {
                keyCloseAttempted = true
                throw keyFailure
            },
        )

        assertSame(databaseFailure, result)
        assertEquals(listOf(keyFailure), result?.suppressed?.toList())
        assertEquals(true, keyCloseAttempted)
    }

    @Test
    fun processSecretsAreWipedBeforeDatabaseAndSqlCipherKey() {
        val calls = mutableListOf<String>()

        val result = closeProcessSecretsDatabaseThenKey(
            closeProcessSecrets = { calls += "process-secrets" },
            closeDatabase = { calls += "database" },
            closeKey = { calls += "key" },
        )

        assertEquals(null, result)
        assertEquals(listOf("process-secrets", "database", "key"), calls)
    }

    @Test
    fun everyProcessCleanupStepRunsAndFailuresRetainOrder() {
        val processFailure = IllegalStateException("process")
        val databaseFailure = IllegalStateException("database")
        val keyFailure = IllegalStateException("key")
        val calls = mutableListOf<String>()

        val result = closeProcessSecretsDatabaseThenKey(
            closeProcessSecrets = {
                calls += "process-secrets"
                throw processFailure
            },
            closeDatabase = {
                calls += "database"
                throw databaseFailure
            },
            closeKey = {
                calls += "key"
                throw keyFailure
            },
        )

        assertSame(processFailure, result)
        assertEquals(listOf(databaseFailure, keyFailure), result?.suppressed?.toList())
        assertEquals(listOf("process-secrets", "database", "key"), calls)
    }

    @Test
    fun failedCloseCanBeRetriedAndSuccessfulCloseIsIdempotent() {
        val databaseFailure = IllegalStateException("first database close")
        var databaseAttempts = 0
        var keyAttempts = 0
        val closer = RetryableSensitiveStorageCloser(
            closeDatabase = {
                databaseAttempts += 1
                if (databaseAttempts == 1) {
                    throw databaseFailure
                }
            },
            closeKey = {
                keyAttempts += 1
            },
        )

        assertSame(
            databaseFailure,
            assertThrows(IllegalStateException::class.java, closer::close),
        )
        assertEquals(1, databaseAttempts)
        assertEquals(1, keyAttempts)

        closer.close()
        closer.close()

        assertEquals(2, databaseAttempts)
        assertEquals(2, keyAttempts)
    }
}
