package ru.andriyshkoy.lifeagent.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Generation-one keyring for durable HTTP request-body fingerprints.
 *
 * Provisioning is an explicit first-use operation. Signing and verification
 * are load-only: a missing alias is never recreated. A no-backup marker also
 * prevents a later provisioning call from silently replacing a lost key that
 * may still be referenced by durable Room rows.
 *
 * There is intentionally no deletion or rotation API in this gate. Generation
 * one is retained for the lifetime of all durable requests that reference it.
 */
internal class KeystoreRequestBodyHmacKeyring(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    markerRelativePath: String = DEFAULT_MARKER_RELATIVE_PATH,
) {
    private val markerFile = File(context.applicationContext.noBackupFilesDir, markerRelativePath)
    private val atomicMarker = AtomicFile(markerFile)

    init {
        require(keyAlias.isNotBlank() && keyAlias.all { it.code in 0x21..0x7e })
        require(markerRelativePath.isNotBlank())
    }

    val currentGeneration: Int
        get() = CURRENT_HMAC_KEY_GENERATION

    /** Explicit create path. Safe to call repeatedly while the key exists. */
    fun provisionCurrentKey(durableReferenceCount: Long): Unit = synchronized(provisioningLock) {
        require(durableReferenceCount >= 0)
        val keyStore = loadKeyStore()
        val markerExists = markerExists()
        if (!markerExists && durableReferenceCount != 0L) {
            throw RequestBodyKeyUnavailableException(
                "Durable requests exist without a proven HMAC key",
            )
        }

        val installed = loadKeyOrNull(keyStore)
        if (installed != null) {
            requireExpectedKey(installed)
            if (markerExists) {
                readAndValidateMarker(installed)
            } else {
                writeMarker(installed)
            }
            return@synchronized
        }
        if (markerExists) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC key is unavailable",
            )
        }

        val generated = try {
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                ANDROID_KEYSTORE,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setKeySize(KEY_SIZE_BITS)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
                generateKey()
            }
        } catch (error: Exception) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC key cannot be created",
                error,
            )
        }
        requireExpectedKey(generated)
        writeMarker(generated)
    }

    /** Load-only creation-time signing; callers must provision explicitly. */
    fun signNew(
        binding: DurableRequestBodyHmacBinding,
        exactRawBody: ByteArray,
    ): ByteArray {
        requireCurrentGeneration(binding)
        val key = loadExistingKey()
        return try {
            DurableRequestBodyHmac.compute(key, binding, exactRawBody)
        } catch (error: Exception) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC key cannot sign",
                error,
            )
        }
    }

    /** Load-only retry verification. This method never invokes a generator. */
    fun verifyExisting(
        binding: DurableRequestBodyHmacBinding,
        exactRawBody: ByteArray,
        expectedHmac: ByteArray,
    ) {
        requireCurrentGeneration(binding)
        val verified = try {
            DurableRequestBodyHmac.verify(
                key = loadExistingKey(),
                binding = binding,
                exactRawBody = exactRawBody,
                expectedHmac = expectedHmac,
            )
        } catch (error: RequestBodyKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC key cannot verify",
                error,
            )
        }
        if (!verified) {
            throw RequestBodyHmacMismatchException()
        }
    }

    private fun requireCurrentGeneration(binding: DurableRequestBodyHmacBinding) {
        require(binding.keyEpoch == CURRENT_HMAC_KEY_GENERATION.toULong()) {
            "Unsupported durable request HMAC key generation"
        }
    }

    private fun loadExistingKey(): SecretKey = try {
        if (!markerExists()) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC marker is unavailable",
            )
        }
        val key = loadKeyOrNull(loadKeyStore())
            ?: throw RequestBodyKeyUnavailableException(
                "Durable request HMAC key is unavailable",
            )
        requireExpectedKey(key)
        readAndValidateMarker(key)
        key
    } catch (error: RequestBodyKeyUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw RequestBodyKeyUnavailableException(
            "Durable request HMAC key cannot be loaded",
            error,
        )
    }

    private fun loadKeyOrNull(keyStore: KeyStore): SecretKey? {
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw RequestBodyKeyUnavailableException(
                "Durable request HMAC alias has an unexpected entry type",
            )
    }

    private fun requireExpectedKey(key: SecretKey) {
        if (!key.algorithm.equals(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ignoreCase = true)) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC alias has an unexpected key type",
            )
        }
        try {
            val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (
                info.keySize != KEY_SIZE_BITS ||
                info.purposes !=
                (KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY) ||
                info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) ||
                info.isUserAuthenticationRequired
            ) {
                throw RequestBodyKeyUnavailableException(
                    "Durable request HMAC key policy is invalid",
                )
            }
            Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
                init(key)
                doFinal().fill(0)
            }
        } catch (error: Exception) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC key is unusable",
                error,
            )
        }
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (error: Exception) {
        throw RequestBodyKeyUnavailableException(
            "Android Keystore is unavailable",
            error,
        )
    }

    private fun markerExists(): Boolean =
        markerFile.isFile || File(markerFile.path + ATOMIC_BACKUP_SUFFIX).isFile

    private fun readAndValidateMarker(key: SecretKey) {
        val bytes = try {
            atomicMarker.readFully()
        } catch (error: Exception) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC marker cannot be read",
                error,
            )
        }
        val header = markerHeaderBytes()
        try {
            if (bytes.size != header.size + DurableRequestBodyHmac.DIGEST_SIZE_BYTES) {
                throw RequestBodyKeyUnavailableException(
                    "Durable request HMAC marker is invalid",
                )
            }
            val retainedHeader = bytes.copyOfRange(0, header.size)
            val retainedProof = bytes.copyOfRange(header.size, bytes.size)
            val calculatedProof = markerProof(key, header)
            try {
                if (
                    !retainedHeader.contentEquals(header) ||
                    !MessageDigest.isEqual(retainedProof, calculatedProof)
                ) {
                    throw RequestBodyKeyUnavailableException(
                        "Durable request HMAC marker authentication failed",
                    )
                }
            } finally {
                retainedHeader.fill(0)
                retainedProof.fill(0)
                calculatedProof.fill(0)
            }
        } finally {
            header.fill(0)
            bytes.fill(0)
        }
    }

    private fun writeMarker(key: SecretKey) {
        markerFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw RequestBodyKeyUnavailableException(
                    "Durable request HMAC marker directory cannot be created",
                )
            }
        }
        val header = markerHeaderBytes()
        val proof = markerProof(key, header)
        val bytes = ByteArray(header.size + proof.size).also {
            header.copyInto(it)
            proof.copyInto(it, destinationOffset = header.size)
        }
        header.fill(0)
        proof.fill(0)
        val stream = try {
            atomicMarker.startWrite()
        } catch (error: Exception) {
            bytes.fill(0)
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC marker cannot be written",
                error,
            )
        }
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicMarker.finishWrite(stream)
        } catch (error: Exception) {
            atomicMarker.failWrite(stream)
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC marker write did not complete",
                error,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun markerHeaderBytes(): ByteArray =
        "$MARKER_PREFIX|$CURRENT_HMAC_KEY_GENERATION|$keyAlias|"
            .toByteArray(StandardCharsets.US_ASCII)

    private fun markerProof(key: SecretKey, header: ByteArray): ByteArray =
        try {
            Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
                init(key)
                update(MARKER_CHALLENGE_BYTES)
                doFinal(header)
            }
        } catch (error: Exception) {
            throw RequestBodyKeyUnavailableException(
                "Durable request HMAC marker cannot be authenticated",
                error,
            )
        }

    companion object {
        const val DEFAULT_KEY_ALIAS = "life_agent_http_retry_body_hmac_v1"
        const val DEFAULT_MARKER_RELATIVE_PATH =
            "crypto/http-retry-body-hmac-v1.provisioned"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        private const val MARKER_PREFIX = "life-agent-http-retry-body-hmac"
        private val MARKER_CHALLENGE_BYTES =
            "life-agent/http-retry-body-hmac-marker/v1"
                .toByteArray(StandardCharsets.US_ASCII)
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private val provisioningLock = Any()
    }
}

internal class RequestBodyKeyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class RequestBodyHmacMismatchException :
    IllegalStateException("Durable request body HMAC mismatch")
