package ru.andriyshkoy.lifeagent.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns the device-bound envelope for the random SQLCipher database key.
 *
 * The wrapping key never leaves Android Keystore. The wrapped envelope is kept
 * in [Context.getNoBackupFilesDir], independently of the Android backup policy.
 */
class DatabaseKeyManager(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
    envelopeRelativePath: String = DEFAULT_ENVELOPE_RELATIVE_PATH,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val appContext = context.applicationContext
    private val envelopeFile = File(appContext.noBackupFilesDir, envelopeRelativePath)
    private val atomicEnvelope = AtomicFile(envelopeFile)

    /**
     * Returns SQLCipher raw-key bytes whose lifetime must match the open Room
     * database. The caller must close the returned holder after closing Room.
     */
    fun openSqlCipherKey(): SqlCipherKey = synchronized(provisioningLock) {
        val keyStore = loadKeyStore()

        when {
            envelopeExists() -> openExistingEnvelope(keyStore)
            databaseArtifactsExist() -> {
                throw DatabaseKeyUnavailableException(
                    "Encrypted database exists without its key envelope.",
                )
            }
            else -> provisionNewEnvelope(keyStore)
        }
    }

    private fun openExistingEnvelope(keyStore: KeyStore): SqlCipherKey {
        val wrappingKey = keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw DatabaseKeyUnavailableException(
                "Database key envelope cannot be opened on this installation.",
            )

        val envelope = try {
            decodeEnvelope(atomicEnvelope.readFully())
        } catch (error: DatabaseKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope is invalid.",
                error,
            )
        }

        val dek = try {
            unwrap(envelope, wrappingKey)
        } catch (error: AEADBadTagException) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope authentication failed.",
                error,
            )
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope cannot be decrypted.",
                error,
            )
        }

        return try {
            require(dek.size == DEK_SIZE_BYTES) {
                "Unexpected database key size."
            }
            SqlCipherKey.fromDek(dek)
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope contains an invalid key.",
                error,
            )
        } finally {
            dek.fill(0)
        }
    }

    private fun provisionNewEnvelope(keyStore: KeyStore): SqlCipherKey {
        /*
         * An alias without an envelope and without database artifacts can only
         * be an interrupted first-run provision. It is safe to replace it.
         */
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }

        envelopeFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw DatabaseKeyUnavailableException(
                    "Database key directory cannot be created.",
                )
            }
        }

        val wrappingKey = try {
            generateWrappingKey()
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Android Keystore could not create the database wrapping key.",
                error,
            )
        }

        val dek = ByteArray(DEK_SIZE_BYTES).also(secureRandom::nextBytes)
        try {
            val envelope = wrap(dek, wrappingKey)
            writeEnvelope(encodeEnvelope(envelope))
            return SqlCipherKey.fromDek(dek)
        } catch (error: DatabaseKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope could not be created.",
                error,
            )
        } finally {
            dek.fill(0)
        }
    }

    private fun wrap(dek: ByteArray, wrappingKey: SecretKey): KeyEnvelope {
        val cipher = Cipher.getInstance(WRAPPING_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        cipher.updateAAD(envelopeAad())
        return KeyEnvelope(
            version = ENVELOPE_VERSION,
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(dek),
        )
    }

    private fun unwrap(envelope: KeyEnvelope, wrappingKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(WRAPPING_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey,
            GCMParameterSpec(GCM_TAG_SIZE_BITS, envelope.iv),
        )
        cipher.updateAAD(envelopeAad())
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun envelopeAad(): ByteArray = buildString {
        append(AAD_PREFIX)
        append('|')
        append(ENVELOPE_VERSION)
        append('|')
        append(appContext.packageName)
        append('|')
        append(databaseName)
        append('|')
        append(keyAlias)
    }.toByteArray(StandardCharsets.UTF_8)

    private fun generateWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(WRAPPING_KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (error: Exception) {
        throw DatabaseKeyUnavailableException(
            "Android Keystore is unavailable.",
            error,
        )
    }

    private fun databaseArtifactsExist(): Boolean {
        val databaseFile = appContext.getDatabasePath(databaseName)
        return DATABASE_ARTIFACT_SUFFIXES.any { suffix ->
            File(databaseFile.path + suffix).exists()
        }
    }

    private fun envelopeExists(): Boolean =
        envelopeFile.isFile || File(envelopeFile.path + ATOMIC_BACKUP_SUFFIX).isFile

    private fun writeEnvelope(bytes: ByteArray) {
        val stream = try {
            atomicEnvelope.startWrite()
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope cannot be written.",
                error,
            )
        }

        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicEnvelope.finishWrite(stream)
        } catch (error: Exception) {
            atomicEnvelope.failWrite(stream)
            throw DatabaseKeyUnavailableException(
                "Database key envelope write did not complete.",
                error,
            )
        }
    }

    private fun encodeEnvelope(envelope: KeyEnvelope): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(ENVELOPE_MAGIC)
                data.writeInt(envelope.version)
                data.writeInt(envelope.iv.size)
                data.write(envelope.iv)
                data.writeInt(envelope.ciphertext.size)
                data.write(envelope.ciphertext)
            }
            output.toByteArray()
        }

    private fun decodeEnvelope(bytes: ByteArray): KeyEnvelope {
        if (bytes.size > MAX_ENVELOPE_SIZE_BYTES) {
            throw DatabaseKeyUnavailableException("Database key envelope is too large.")
        }

        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(ENVELOPE_MAGIC.size)
                data.readFully(magic)
                if (!magic.contentEquals(ENVELOPE_MAGIC)) {
                    throw DatabaseKeyUnavailableException(
                        "Database key envelope has an unknown format.",
                    )
                }

                val version = data.readInt()
                if (version != ENVELOPE_VERSION) {
                    throw DatabaseKeyUnavailableException(
                        "Database key envelope version is unsupported.",
                    )
                }

                val ivLength = data.readInt()
                if (ivLength !in MIN_GCM_IV_SIZE_BYTES..MAX_GCM_IV_SIZE_BYTES) {
                    throw DatabaseKeyUnavailableException(
                        "Database key envelope IV is invalid.",
                    )
                }
                val iv = ByteArray(ivLength).also(data::readFully)

                val ciphertextLength = data.readInt()
                if (ciphertextLength !in MIN_CIPHERTEXT_SIZE_BYTES..MAX_CIPHERTEXT_SIZE_BYTES) {
                    throw DatabaseKeyUnavailableException(
                        "Database key envelope ciphertext is invalid.",
                    )
                }
                val ciphertext = ByteArray(ciphertextLength).also(data::readFully)

                if (data.read() != -1) {
                    throw DatabaseKeyUnavailableException(
                        "Database key envelope contains trailing data.",
                    )
                }

                KeyEnvelope(version, iv, ciphertext)
            }
        } catch (error: DatabaseKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException(
                "Database key envelope is truncated.",
                error,
            )
        }
    }

    private data class KeyEnvelope(
        val version: Int,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    companion object {
        const val DEFAULT_DATABASE_NAME = "life-agent.db"
        const val DEFAULT_KEY_ALIAS = "life_agent_room_wrap_v1"
        const val DEFAULT_ENVELOPE_RELATIVE_PATH = "crypto/room-dek-v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPING_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WRAPPING_KEY_SIZE_BITS = 256
        private const val GCM_TAG_SIZE_BITS = 128
        private const val DEK_SIZE_BYTES = 32
        private const val ENVELOPE_VERSION = 1
        private const val AAD_PREFIX = "life-agent-room-key-envelope"
        private const val MIN_GCM_IV_SIZE_BYTES = 12
        private const val MAX_GCM_IV_SIZE_BYTES = 32
        private const val MIN_CIPHERTEXT_SIZE_BYTES = DEK_SIZE_BYTES + 16
        private const val MAX_CIPHERTEXT_SIZE_BYTES = 128
        private const val MAX_ENVELOPE_SIZE_BYTES = 512
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"

        private val ENVELOPE_MAGIC = byteArrayOf(
            'L'.code.toByte(),
            'A'.code.toByte(),
            'K'.code.toByte(),
            'E'.code.toByte(),
        )
        private val DATABASE_ARTIFACT_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")
        private val provisioningLock = Any()
    }
}

class DatabaseKeyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
