package ru.andriyshkoy.lifeagent.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherAtRestInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testId: String
    private lateinit var keyAlias: String
    private lateinit var databaseName: String
    private lateinit var envelopeRelativePath: String

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        keyAlias = "life_agent_at_rest_test_$testId"
        databaseName = "life-agent-at-rest-$testId.db"
        envelopeRelativePath = "crypto-tests/$testId/at-rest-dek-v1"
        SqlCipherRuntime.initialize()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        File(context.noBackupFilesDir, envelopeRelativePath).let { envelope ->
            listOf("", ".bak", ".new").forEach { suffix ->
                File(envelope.path + suffix).delete()
            }
            envelope.parentFile?.delete()
        }
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(keyAlias)) {
                deleteEntry(keyAlias)
            }
        }
    }

    @Test
    fun plaintextIsAbsentFromDatabaseWalAndShm() {
        val manager = DatabaseKeyManager(
            context = context,
            keyAlias = keyAlias,
            databaseName = databaseName,
            envelopeRelativePath = envelopeRelativePath,
        )
        val databaseFile = context.getDatabasePath(databaseName)

        manager.openSqlCipherKey().use { key ->
            val database = SQLiteDatabase.openDatabase(
                databaseFile.path,
                key.bytesForOpenHelperFactory(),
                null,
                SQLiteDatabase.CREATE_IF_NECESSARY or
                    SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                null,
            )
            try {
                database.execSQL(
                    "CREATE TABLE note_probe(" +
                        "note_text TEXT NOT NULL, outbox_payload TEXT NOT NULL)",
                )
                database.execSQL(
                    "INSERT INTO note_probe(note_text, outbox_payload) VALUES(?, ?)",
                    arrayOf(SENTINEL, """{"note":"$SENTINEL"}"""),
                )

                database.rawQuery(
                    "SELECT note_text, outbox_payload FROM note_probe",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(SENTINEL, cursor.getString(0))
                    assertTrue(cursor.getString(1).contains(SENTINEL))
                }
                database.rawQuery("PRAGMA cipher_status", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                database.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("wal", cursor.getString(0).lowercase())
                }

                val openArtifacts = databaseArtifacts(databaseFile)
                assertTrue(databaseFile.isFile)
                assertTrue(File(databaseFile.path + "-wal").isFile)
                assertTrue(File(databaseFile.path + "-shm").isFile)
                assertPlaintextAbsent(openArtifacts)
            } finally {
                database.close()
            }

            assertPlaintextAbsent(databaseArtifacts(databaseFile))
        }

        val mainBytes = databaseFile.readBytes()
        assertFalse(
            mainBytes.startsWith(SQLITE_PLAINTEXT_HEADER),
        )

        val wrongKeyProbe = File(context.cacheDir, "wrong-key-$testId.db")
        databaseFile.copyTo(wrongKeyProbe)
        try {
            val wrongDek = ByteArray(32).also(SecureRandom()::nextBytes)
            val wrongKey = try {
                SqlCipherKey.fromDek(wrongDek)
            } finally {
                wrongDek.fill(0)
            }
            wrongKey.use { key ->
                assertThrows(Exception::class.java) {
                    SQLiteDatabase.openDatabase(
                        wrongKeyProbe.path,
                        key.bytesForOpenHelperFactory(),
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                        null,
                    ).use { wrongDatabase ->
                        wrongDatabase.rawQuery(
                            "SELECT count(*) FROM sqlite_schema",
                            null,
                        ).use { it.moveToFirst() }
                    }
                }
            }
        } finally {
            wrongKeyProbe.delete()
        }

        manager.openSqlCipherKey().use { key ->
            SQLiteDatabase.openDatabase(
                databaseFile.path,
                key.bytesForOpenHelperFactory(),
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            ).use { reopenedDatabase ->
                reopenedDatabase.rawQuery(
                    "SELECT note_text FROM note_probe",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(SENTINEL, cursor.getString(0))
                }
            }
        }
    }

    private fun databaseArtifacts(databaseFile: File): List<File> =
        ARTIFACT_SUFFIXES
            .map { suffix -> File(databaseFile.path + suffix) }
            .filter(File::isFile)

    private fun assertPlaintextAbsent(files: List<File>) {
        val encodings = listOf(
            SENTINEL.toByteArray(StandardCharsets.UTF_8),
            SENTINEL.toByteArray(StandardCharsets.UTF_16LE),
            SENTINEL.toByteArray(StandardCharsets.UTF_16BE),
        )
        files.forEach { file ->
            val contents = file.readBytes()
            encodings.forEach { plaintext ->
                assertFalse(
                    "Plaintext marker found in ${file.name}",
                    contents.containsSubsequence(plaintext),
                )
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SENTINEL =
            "LIFE_AGENT_DB_PLAINTEXT_SENTINEL_6E58F642_ёж"
        private val SQLITE_PLAINTEXT_HEADER =
            "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
        private val ARTIFACT_SUFFIXES = listOf("", "-wal", "-shm")
    }
}
