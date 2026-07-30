package ru.andriyshkoy.lifeagent.data.security

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherLoggingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun productionFactoryDisablesCoreLoggingWithoutEmittingSensitiveMarker() {
        SqlCipherRuntime.initialize()
        executeShell("logcat -c").fill(0)

        val testId = UUID.randomUUID().toString()
        val databaseName = "life-agent-log-privacy-$testId.db"
        val sentinel = "private-note-${testId.replace("-", "")}"
        val dek = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = try {
            SqlCipherKey.fromDek(dek)
        } finally {
            dek.fill(0)
        }
        val helper = SqlCipherOpenHelperFactoryProvider.create(key).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE privacy_probe(value TEXT NOT NULL)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            assertEquals("NONE", pragmaValue(database, "PRAGMA cipher_log_level"))
            assertEquals("NONE", pragmaValue(database, "PRAGMA cipher_log_source"))
            assertEquals("1", pragmaValue(database, "PRAGMA cipher_status"))

            database.execSQL(
                "INSERT INTO privacy_probe(value) VALUES(?)",
                arrayOf(sentinel),
            )
            assertBoundValueRoundTrips(sentinel, database)
            assertSyntheticSqlFailure(database)
        } finally {
            val cleanupFailure = closeDatabaseThenKey(
                closeDatabase = helper::close,
                closeKey = key::close,
            )
            context.deleteDatabase(databaseName)
            if (cleanupFailure != null) {
                throw cleanupFailure
            }
        }

        Log.i(TEST_TAG, LOGCAT_CAPTURE_CANARY)
        val logcatBytes = executeShell("logcat -d -v raw --pid ${Process.myPid()}")
        val canaryBytes = LOGCAT_CAPTURE_CANARY.toByteArray()
        val sentinelBytes = sentinel.toByteArray()
        try {
            assertTrue(
                "The test could not verify its process-scoped logcat capture.",
                logcatBytes.containsSubsequence(canaryBytes),
            )
            if (logcatBytes.containsSubsequence(sentinelBytes)) {
                throw AssertionError(
                    "SQLCipher emitted synthetic sensitive content to logcat.",
                )
            }
        } finally {
            logcatBytes.fill(0)
            canaryBytes.fill(0)
            sentinelBytes.fill(0)
        }
    }

    private fun pragmaValue(
        database: SupportSQLiteDatabase,
        pragma: String,
    ): String = database.query(pragma).use { cursor ->
        check(cursor.moveToFirst() && cursor.columnCount > 0)
        cursor.getString(0).orEmpty()
    }

    private fun assertBoundValueRoundTrips(
        sentinel: String,
        database: SupportSQLiteDatabase,
    ) {
        database.query(
            "SELECT value FROM privacy_probe WHERE value = ?",
            arrayOf(sentinel),
        ).use { cursor ->
            if (
                !cursor.moveToFirst() ||
                cursor.getString(0) != sentinel ||
                cursor.moveToNext()
            ) {
                throw AssertionError(
                    "Synthetic bound value did not round-trip exactly once.",
                )
            }
        }
    }

    private fun assertSyntheticSqlFailure(database: SupportSQLiteDatabase) {
        var failedAsExpected = false
        try {
            database.query("SELECT * FROM missing_privacy_probe_table").use { cursor ->
                cursor.moveToFirst()
            }
        } catch (_: Exception) {
            failedAsExpected = true
        }
        assertTrue("Synthetic invalid SQL did not fail as expected.", failedAsExpected)
    }

    private fun executeShell(command: String): ByteArray {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes()
        }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private companion object {
        const val TEST_TAG = "LifeAgentSecurityTest"
        const val LOGCAT_CAPTURE_CANARY = "life-agent-logcat-capture-ready"
    }
}
