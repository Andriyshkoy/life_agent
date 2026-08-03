package ru.andriyshkoy.lifeagent.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

internal data class KeystoreAeadEnvelope(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyAlias: String,
    val keyGeneration: Int,
    val aadVersion: Int,
    val plaintextOctetCount: Long,
) {
    override fun toString(): String = "KeystoreAeadEnvelope(redacted=true)"
}

/**
 * Seals credential-bearing durable payloads with a key that never leaves
 * Android Keystore. The decrypted byte buffer is scoped to
 * [withAuthenticatedPlaintext] and wiped before returning. Downstream parsing
 * allocations are kept bounded and short-lived, but immutable JVM strings
 * created by parsers cannot be wiped. The caller remains responsible for the
 * separately domain-framed durable-body HMAC after AEAD authentication.
 */
internal class KeystoreAeadPayloadCipher(
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
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad(purpose, recordIdentity))
            KeystoreAeadEnvelope(
                ciphertext = cipher.doFinal(plaintext),
                nonce = cipher.iv.copyOf(),
                keyAlias = keyAlias,
                keyGeneration = keyGeneration,
                aadVersion = aadVersion,
                plaintextOctetCount = plaintext.size.toLong(),
            )
        } catch (error: SensitivePayloadIntegrityException) {
            throw error
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload key cannot encrypt",
                error,
                SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
        }
    }

    fun <T> withAuthenticatedPlaintext(
        envelope: KeystoreAeadEnvelope,
        purpose: String,
        recordIdentity: String,
        block: (ByteArray) -> T,
    ): T {
        validateEnvelope(envelope)
        val cipher = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadKey(),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, envelope.nonce),
            )
            cipher.updateAAD(aad(purpose, recordIdentity))
            cipher
        } catch (error: SensitivePayloadIntegrityException) {
            throw error
        } catch (error: Exception) {
            if (error.isKeyInfrastructureFailure()) {
                throw SensitivePayloadIntegrityException(
                    "Sealed payload key cannot initialize decryption",
                    error,
                    SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
                )
            }
            throw error
        }
        val plaintext = try {
            cipher.doFinal(envelope.ciphertext)
        } catch (error: AEADBadTagException) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload authentication failed",
                error,
                SensitivePayloadIntegrityFailure.AEAD_AUTH_FAILED,
            )
        } catch (error: Exception) {
            if (error.isKeyInfrastructureFailure()) {
                throw SensitivePayloadIntegrityException(
                    "Sealed payload key failed during decryption",
                    error,
                    SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
                )
            }
            // Unknown provider failures are not proof of ciphertext tampering;
            // leave them unclassified so callers do not persist a false
            // integrity verdict.
            throw error
        }

        try {
            if (plaintext.size.toLong() != envelope.plaintextOctetCount) {
                throw SensitivePayloadIntegrityException(
                    "Sealed payload length differs from durable metadata",
                    failure = SensitivePayloadIntegrityFailure.METADATA_INVALID,
                )
            }
            return block(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateEnvelope(envelope: KeystoreAeadEnvelope) {
        if (
            envelope.keyAlias != keyAlias ||
            envelope.keyGeneration != keyGeneration ||
            envelope.aadVersion != aadVersion ||
            envelope.ciphertext.isEmpty() ||
            envelope.nonce.size !in MIN_NONCE_SIZE_BYTES..MAX_NONCE_SIZE_BYTES ||
            envelope.plaintextOctetCount <= 0
        ) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload metadata is invalid",
                failure = SensitivePayloadIntegrityFailure.METADATA_INVALID,
            )
        }
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
                SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
        }

    private fun loadKey(): SecretKey = try {
        loadKeyStore().let(::loadKeyOrNull)
            ?: throw SensitivePayloadIntegrityException(
                "Sealed payload key is unavailable",
                failure = SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
    } catch (error: SensitivePayloadIntegrityException) {
        throw error
    } catch (error: Exception) {
        throw SensitivePayloadIntegrityException(
            "Sealed payload key cannot be loaded",
            error,
            SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
        )
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyProvisioningLock) {
        val keyStore = loadKeyStore()
        loadKeyOrNull(keyStore)?.let { return@synchronized it }
        try {
            // Reload inside the provisioning lock before generation so two
            // concurrent first seals cannot race different keys under one alias.
            val reloaded = loadKeyStore()
            loadKeyOrNull(reloaded)?.let {
                return@synchronized it
            }
            val generated = KeyGenerator.getInstance(
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
            requireExpectedKey(generated)
            generated
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload key cannot be created",
                error,
                SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
        }
    }

    private fun loadKeyOrNull(keyStore: KeyStore): SecretKey? {
        if (!keyStore.containsAlias(keyAlias)) return null
        val key = keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw SensitivePayloadIntegrityException(
                "Sealed payload alias has an unexpected entry type",
                failure = SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
        if (!key.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload alias has an unexpected key type",
                failure = SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
        }
        requireExpectedKey(key)
        return key
    }

    private fun requireExpectedKey(key: SecretKey) {
        try {
            val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (
                info.keySize != KEY_SIZE_BITS ||
                info.purposes !=
                (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ||
                info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM) ||
                info.encryptionPaddings.toSet() !=
                setOf(KeyProperties.ENCRYPTION_PADDING_NONE) ||
                info.isUserAuthenticationRequired
            ) {
                throw SensitivePayloadIntegrityException(
                    "Sealed payload key policy is invalid",
                    failure = SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
                )
            }
        } catch (error: SensitivePayloadIntegrityException) {
            throw error
        } catch (error: Exception) {
            throw SensitivePayloadIntegrityException(
                "Sealed payload key policy cannot be inspected",
                error,
                SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            )
        }
    }

    companion object {
        const val CURRENT_AAD_VERSION = 1

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_SIZE_BITS = 128
        private const val MIN_NONCE_SIZE_BYTES = 12
        private const val MAX_NONCE_SIZE_BYTES = 32
        private const val AAD_PREFIX = "life-agent-sensitive-payload"
        private val keyProvisioningLock = Any()
    }
}

internal enum class SensitivePayloadIntegrityFailure {
    KEY_UNAVAILABLE,
    METADATA_INVALID,
    AEAD_AUTH_FAILED,
}

internal class SensitivePayloadIntegrityException(
    message: String,
    cause: Throwable? = null,
    val failure: SensitivePayloadIntegrityFailure,
) : IllegalStateException(message, cause)

private fun Throwable.isKeyInfrastructureFailure(): Boolean =
    generateSequence(this) { it.cause }
        .any { cause ->
            cause is InvalidKeyException ||
                cause is UnrecoverableKeyException ||
                cause is ProviderException
        }
