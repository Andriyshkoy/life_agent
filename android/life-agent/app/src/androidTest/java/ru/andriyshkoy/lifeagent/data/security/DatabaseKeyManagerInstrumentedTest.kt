package ru.andriyshkoy.lifeagent.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseKeyManagerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testId: String
    private lateinit var keyAlias: String
    private lateinit var databaseName: String
    private lateinit var envelopeRelativePath: String

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        keyAlias = "life_agent_test_wrap_$testId"
        databaseName = "life-agent-key-test-$testId.db"
        envelopeRelativePath = "crypto-tests/$testId/room-dek-v1"
        SqlCipherRuntime.initialize()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        deleteAtomicEnvelopeArtifacts(envelopeFile())
        envelopeFile().parentFile?.delete()

        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(keyAlias)) {
                deleteEntry(keyAlias)
            }
        }
    }

    @Test
    fun provisionedEnvelopeReopensTheSameEncryptedDatabase() {
        val manager = manager()
        val databaseFile = context.getDatabasePath(databaseName)

        val firstKey = manager.openSqlCipherKey()
        val rawKeySnapshot = firstKey.bytesForOpenHelperFactory().copyOf()
        try {
            openDatabase(databaseFile, firstKey).use { database ->
                database.execSQL("CREATE TABLE key_probe(value TEXT NOT NULL)")
                database.execSQL(
                    "INSERT INTO key_probe(value) VALUES(?)",
                    arrayOf(PROBE_VALUE),
                )
            }

            assertTrue(envelopeFile().isFile)
            assertFalse(
                envelopeFile().readBytes().containsSubsequence(rawKeySnapshot),
            )
        } finally {
            rawKeySnapshot.fill(0)
            firstKey.close()
        }

        manager.openSqlCipherKey().use { reopenedKey ->
            openDatabase(databaseFile, reopenedKey).use { database ->
                database.rawQuery("SELECT value FROM key_probe", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(PROBE_VALUE == cursor.getString(0))
                }
            }
        }
    }

    @Test
    fun closingKeyZeroizesBytesRetainedByFactory() {
        val key = manager().openSqlCipherKey()
        val keyBytes = key.bytesForOpenHelperFactory()
        val factory = SqlCipherOpenHelperFactoryProvider.create(key)
        val retainedBytes = factory.javaClass
            .getDeclaredField("password")
            .apply { isAccessible = true }
            .get(factory) as ByteArray

        assertSame(keyBytes, retainedBytes)
        assertTrue(retainedBytes.any { it.toInt() != 0 })
        key.close()
        assertTrue(retainedBytes.all { it.toInt() == 0 })
        assertThrows(IllegalStateException::class.java) {
            key.bytesForOpenHelperFactory()
        }
    }

    @Test
    fun tamperedEnvelopeFailsClosed() {
        manager().openSqlCipherKey().close()
        val tampered = envelopeFile().readBytes()
        tampered[tampered.lastIndex] =
            (tampered.last().toInt() xor 0x01).toByte()
        envelopeFile().writeBytes(tampered)

        assertThrows(DatabaseKeyUnavailableException::class.java) {
            manager().openSqlCipherKey()
        }
    }

    @Test
    fun existingDatabaseWithoutEnvelopeFailsClosed() {
        val manager = manager()
        val databaseFile = context.getDatabasePath(databaseName)
        manager.openSqlCipherKey().use { key ->
            openDatabase(databaseFile, key).use { database ->
                database.execSQL("CREATE TABLE fail_closed_probe(value INTEGER)")
            }
        }
        assertTrue(envelopeFile().delete())

        assertThrows(DatabaseKeyUnavailableException::class.java) {
            manager().openSqlCipherKey()
        }
        assertFalse(envelopeFile().exists())
    }

    @Test
    fun envelopeWithoutKeystoreAliasFailsClosed() {
        manager().openSqlCipherKey().close()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            deleteEntry(keyAlias)
        }

        assertThrows(DatabaseKeyUnavailableException::class.java) {
            manager().openSqlCipherKey()
        }
        assertTrue(envelopeFile().isFile)
    }

    private fun manager() = DatabaseKeyManager(
        context = context,
        keyAlias = keyAlias,
        databaseName = databaseName,
        envelopeRelativePath = envelopeRelativePath,
    )

    private fun envelopeFile() = File(context.noBackupFilesDir, envelopeRelativePath)

    private fun deleteAtomicEnvelopeArtifacts(envelope: File) {
        listOf("", ".bak", ".new").forEach { suffix ->
            File(envelope.path + suffix).delete()
        }
    }

    private fun openDatabase(
        file: File,
        key: SqlCipherKey,
    ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(
        file,
        key.bytesForOpenHelperFactory(),
        null,
        null,
        null,
    )

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PROBE_VALUE = "reopened-with-the-same-device-bound-key"
    }
}
