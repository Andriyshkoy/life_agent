package ru.andriyshkoy.lifeagent.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class KeystoreAeadEnvelope(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyAlias: String,
    val keyGeneration: Int,
    val aadVersion: Int,
    val plaintextOctetCount: Long,
)

/**
 * Seals credential-bearing durable payloads with a key that never leaves
 * Android Keystore. Decryption exposes one temporary in-memory buffer only
 * inside [withVerifiedPlaintext] and wipes it before returning.
 */
class KeystoreAeadPayloadCipher(
    context: Context,
    private val keyAlias: String,
    private val keyGeneration: Int,
    private val aadVersion: Int = CURRENT_AAD_VERSION,
) {
    private val appContext = context.applicationContext

    init {
        require(keyAlias.isNotBlank())
        require(keyGeneration > 0)
        require(aadVersion > 0)
    }

    fun seal(
        plaintext: ByteArray,
        purpose: String,
        recordIdentity: String,
    ): KeystoreAeadEnvelope {
        require(plaintext.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(purpose, recordIdentity))
        return KeystoreAeadEnvelope(
            ciphertext = cipher.doFinal(plaintext),
            nonce = cipher.iv.copyOf(),
            keyAlias = keyAlias,
            keyGeneration = keyGeneration,
            aadVersion = aadVersion,
            plaintextOctetCount = plaintext.size.toLong(),
        )
    }

    fun <T> withVerifiedPlaintext(
        envelope: KeystoreAeadEnvelope,
        purpose: String,
        recordIdentity: String,
        expectedHmac: ByteArray,
        hmacKey: SecretKey,
        block: (ByteArray) -> T,
    ): T {
        validateEnvelope(envelope)
        val key = loadKey()
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_SIZE_BITS, envelope.nonce),
            )
            cipher.updateAAD(aad(purpose, recordIdentity))
            cipher.doFinal(envelope.ciphertext)
        } catch (error: AEADBadTagException) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload authentication failed",
                error,
            )
        } catch (error: SensitivePayloadIntegrityException) {
            throw error
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload cannot be decrypted",
                error,
            )
        }

        var computedHmac: ByteArray? = null
        try {
            if (plaintext.size.toLong() != envelope.plaintextOctetCount) {
                throw SensitivePayloadIntegrityException(
                    "Sealed payload length differs from durable metadata",
                )
            }
            computedHmac = Mac.getInstance(HMAC_ALGORITHM).run {
                init(hmacKey)
                doFinal(plaintext)
            }
            if (!MessageDigest.isEqual(computedHmac, expectedHmac)) {
                throw SensitivePayloadIntegrityException(
                    "Sealed payload HMAC verification failed",
                )
            }
            return block(plaintext)
        } finally {
            computedHmac?.fill(0)
            plaintext.fill(0)
        }
    }

    private fun validateEnvelope(envelope: KeystoreAeadEnvelope) {
        require(envelope.keyAlias == keyAlias)
        require(envelope.keyGeneration == keyGeneration)
        require(envelope.aadVersion == aadVersion)
        require(envelope.ciphertext.isNotEmpty())
        require(envelope.nonce.size in MIN_NONCE_SIZE_BYTES..MAX_NONCE_SIZE_BYTES)
        require(envelope.plaintextOctetCount > 0)
    }

    private fun aad(
        purpose: String,
        recordIdentity: String,
    ): ByteArray {
        require(purpose.isNotBlank())
        require(recordIdentity.isNotBlank())
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeLengthPrefixed(AAD_PREFIX)
                data.writeInt(aadVersion)
                data.writeLengthPrefixed(appContext.packageName)
                data.writeLengthPrefixed(purpose)
                data.writeLengthPrefixed(recordIdentity)
                data.writeLengthPrefixed(keyAlias)
                data.writeInt(keyGeneration)
            }
            output.toByteArray()
        }
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun loadKeyStore(): KeyStore =
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Android Keystore is unavailable",
                error,
            )
        }

    private fun loadKey(): SecretKey =
        try {
            loadKeyStore().getKey(keyAlias, null) as? SecretKey
                ?: throw SensitivePayloadIntegrityException(
                    "Sealed payload key is unavailable",
                )
        } catch (error: SensitivePayloadIntegrityException) {
            throw error
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload key cannot be loaded",
                error,
            )
        }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return try {
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(KEY_SIZE_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload key cannot be created",
                error,
            )
        }
    }

    companion object {
        const val CURRENT_AAD_VERSION = 1

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_SIZE_BITS = 128
        private const val MIN_NONCE_SIZE_BYTES = 12
        private const val MAX_NONCE_SIZE_BYTES = 32
        private const val AAD_PREFIX = "life-agent-sensitive-payload"
    }
}

class SensitivePayloadIntegrityException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
