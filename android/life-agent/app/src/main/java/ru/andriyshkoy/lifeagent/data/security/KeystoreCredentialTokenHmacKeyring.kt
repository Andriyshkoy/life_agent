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
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Token kind is used only to validate the canonical wire prefix. */
internal enum class CredentialTokenKind(
    internal val prefixThirdByte: Byte,
) {
    ACCESS('a'.code.toByte()),
    REFRESH('r'.code.toByte()),
}

/**
 * Android-local credential token fingerprint primitive.
 *
 * Access and refresh tokens intentionally share one key and one domain. The
 * kind-specific `laa_`/`lar_` prefix is validated but excluded from the MAC,
 * so reuse of the same 32-byte random payload across token kinds produces the
 * same fingerprint and is rejected by the durable database uniqueness guard.
 * This local collision fingerprint is deliberately unrelated to the server's
 * separately domain-keyed credential lookup hashes.
 */
internal object CredentialTokenFingerprintHmac {
    const val DIGEST_SIZE_BYTES = 32

    fun compute(
        key: SecretKey,
        kind: CredentialTokenKind,
        canonicalTokenBytes: ByteArray,
    ): ByteArray {
        val tokenCopy = canonicalTokenBytes.copyOf()
        var randomPayload: ByteArray? = null
        var macFrame: ByteArray? = null
        try {
            val payload = decodeCanonicalRandomPayload(kind, tokenCopy)
            randomPayload = payload
            val frame = ByteArray(
                TOKEN_FINGERPRINT_DOMAIN_BYTES.size + 1 + payload.size,
            ).also { frame ->
                TOKEN_FINGERPRINT_DOMAIN_BYTES.copyInto(frame)
                frame[TOKEN_FINGERPRINT_DOMAIN_BYTES.size] = 0
                payload.copyInto(
                    frame,
                    destinationOffset = TOKEN_FINGERPRINT_DOMAIN_BYTES.size + 1,
                )
            }
            macFrame = frame
            val fingerprint = Mac.getInstance(HMAC_ALGORITHM).run {
                init(key)
                doFinal(frame)
            }
            if (fingerprint.size != DIGEST_SIZE_BYTES) {
                fingerprint.fill(0)
                throw IllegalStateException("Credential token HMAC output length is invalid")
            }
            return fingerprint
        } finally {
            macFrame?.fill(0)
            randomPayload?.fill(0)
            tokenCopy.fill(0)
        }
    }

    fun verify(
        key: SecretKey,
        kind: CredentialTokenKind,
        canonicalTokenBytes: ByteArray,
        expectedFingerprint: ByteArray,
    ): Boolean {
        val expectedCopy = expectedFingerprint.copyOf()
        var calculated: ByteArray? = null
        return try {
            val actual = compute(key, kind, canonicalTokenBytes)
            calculated = actual
            expectedCopy.size == DIGEST_SIZE_BYTES &&
                MessageDigest.isEqual(expectedCopy, actual)
        } finally {
            expectedCopy.fill(0)
            calculated?.fill(0)
        }
    }

    private fun decodeCanonicalRandomPayload(
        kind: CredentialTokenKind,
        token: ByteArray,
    ): ByteArray {
        if (
            token.size != CANONICAL_TOKEN_SIZE_BYTES ||
            token[0] != 'l'.code.toByte() ||
            token[1] != 'a'.code.toByte() ||
            token[2] != kind.prefixThirdByte ||
            token[3] != '_'.code.toByte()
        ) {
            throw CredentialTokenFormatException()
        }

        for (index in TOKEN_PREFIX_SIZE_BYTES until token.size) {
            if (base64UrlValue(token[index]) < 0) {
                throw CredentialTokenFormatException()
            }
        }
        // Exactly 32 input bytes leave two unused bits in the 43rd base64url
        // character. Rejecting them before decode closes alternate encodings.
        if (base64UrlValue(token.last()) and 0x03 != 0) {
            throw CredentialTokenFormatException()
        }

        val encodedPayload = token.copyOfRange(TOKEN_PREFIX_SIZE_BYTES, token.size)
        val decodedPayload = try {
            Base64.getUrlDecoder().decode(encodedPayload)
        } catch (_: IllegalArgumentException) {
            encodedPayload.fill(0)
            throw CredentialTokenFormatException()
        }
        var canonicalEncoding: ByteArray? = null
        var accepted = false
        try {
            val canonical = Base64.getUrlEncoder().withoutPadding().encode(decodedPayload)
            canonicalEncoding = canonical
            if (
                decodedPayload.size != RANDOM_PAYLOAD_SIZE_BYTES ||
                canonical.size != ENCODED_PAYLOAD_SIZE_BYTES ||
                !MessageDigest.isEqual(encodedPayload, canonical)
            ) {
                throw CredentialTokenFormatException()
            }
            accepted = true
            return decodedPayload
        } finally {
            encodedPayload.fill(0)
            canonicalEncoding?.fill(0)
            if (!accepted) decodedPayload.fill(0)
        }
    }

    private fun base64UrlValue(byte: Byte): Int {
        val value = byte.toInt() and 0xff
        return when (value) {
            in 'A'.code..'Z'.code -> value - 'A'.code
            in 'a'.code..'z'.code -> value - 'a'.code + 26
            in '0'.code..'9'.code -> value - '0'.code + 52
            '-'.code -> 62
            '_'.code -> 63
            else -> -1
        }
    }

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val TOKEN_PREFIX_SIZE_BYTES = 4
    private const val ENCODED_PAYLOAD_SIZE_BYTES = 43
    private const val RANDOM_PAYLOAD_SIZE_BYTES = 32
    private const val CANONICAL_TOKEN_SIZE_BYTES =
        TOKEN_PREFIX_SIZE_BYTES + ENCODED_PAYLOAD_SIZE_BYTES
    private const val TOKEN_FINGERPRINT_DOMAIN =
        "life-agent/android-credential-token-fingerprint/v1"
    private val TOKEN_FINGERPRINT_DOMAIN_BYTES =
        TOKEN_FINGERPRINT_DOMAIN.toByteArray(StandardCharsets.US_ASCII)
}

/**
 * Generation-one Android Keystore keyring for local credential fingerprints.
 *
 * Provisioning is explicit. Fingerprinting and verification are load-only and
 * never generate a missing key. The authenticated no-backup marker prevents a
 * later provisioning call from silently replacing a key referenced by Room.
 */
internal class KeystoreCredentialTokenHmacKeyring(
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
        get() = CURRENT_KEY_GENERATION

    /** Explicit create path. Safe to repeat only while continuity is proven. */
    fun provisionCurrentKey(durableReferenceCount: Long): Unit = synchronized(provisioningLock) {
        require(durableReferenceCount >= 0)
        val keyStore = loadKeyStore()
        val markerExists = markerExists()
        if (!markerExists && durableReferenceCount != 0L) {
            throw CredentialTokenKeyUnavailableException(
                "Durable credential fingerprints exist without a proven HMAC key",
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
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC key is unavailable",
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
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC key cannot be created",
                error,
            )
        }
        requireExpectedKey(generated)
        writeMarker(generated)
    }

    /** Returns a caller-owned 32-byte fingerprint. The token remains caller-owned. */
    fun fingerprintAccess(canonicalTokenBytes: ByteArray): ByteArray =
        fingerprint(CredentialTokenKind.ACCESS, canonicalTokenBytes)

    /** Returns a caller-owned 32-byte fingerprint. The token remains caller-owned. */
    fun fingerprintRefresh(canonicalTokenBytes: ByteArray): ByteArray =
        fingerprint(CredentialTokenKind.REFRESH, canonicalTokenBytes)

    fun verifyAccessExisting(
        canonicalTokenBytes: ByteArray,
        expectedFingerprint: ByteArray,
    ) = verify(CredentialTokenKind.ACCESS, canonicalTokenBytes, expectedFingerprint)

    fun verifyRefreshExisting(
        canonicalTokenBytes: ByteArray,
        expectedFingerprint: ByteArray,
    ) = verify(CredentialTokenKind.REFRESH, canonicalTokenBytes, expectedFingerprint)

    private fun fingerprint(
        kind: CredentialTokenKind,
        canonicalTokenBytes: ByteArray,
    ): ByteArray = try {
        CredentialTokenFingerprintHmac.compute(
            key = loadExistingKey(),
            kind = kind,
            canonicalTokenBytes = canonicalTokenBytes,
        )
    } catch (error: CredentialTokenFormatException) {
        throw error
    } catch (error: CredentialTokenKeyUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw CredentialTokenKeyUnavailableException(
            "Credential fingerprint HMAC key cannot fingerprint",
            error,
        )
    }

    private fun verify(
        kind: CredentialTokenKind,
        canonicalTokenBytes: ByteArray,
        expectedFingerprint: ByteArray,
    ) {
        val verified = try {
            CredentialTokenFingerprintHmac.verify(
                key = loadExistingKey(),
                kind = kind,
                canonicalTokenBytes = canonicalTokenBytes,
                expectedFingerprint = expectedFingerprint,
            )
        } catch (error: CredentialTokenFormatException) {
            throw error
        } catch (error: CredentialTokenKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC key cannot verify",
                error,
            )
        }
        if (!verified) throw CredentialTokenFingerprintMismatchException()
    }

    private fun loadExistingKey(): SecretKey = try {
        if (!markerExists()) {
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC marker is unavailable",
            )
        }
        val key = loadKeyOrNull(loadKeyStore())
            ?: throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC key is unavailable",
            )
        requireExpectedKey(key)
        readAndValidateMarker(key)
        key
    } catch (error: CredentialTokenKeyUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw CredentialTokenKeyUnavailableException(
            "Credential fingerprint HMAC key cannot be loaded",
            error,
        )
    }

    private fun loadKeyOrNull(keyStore: KeyStore): SecretKey? {
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC alias has an unexpected entry type",
            )
    }

    private fun requireExpectedKey(key: SecretKey) {
        if (!key.algorithm.equals(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ignoreCase = true)) {
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC alias has an unexpected key type",
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
                throw CredentialTokenKeyUnavailableException(
                    "Credential fingerprint HMAC key policy is invalid",
                )
            }
            Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
                init(key)
                doFinal().fill(0)
            }
        } catch (error: Exception) {
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC key is unusable",
                error,
            )
        }
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (error: Exception) {
        throw CredentialTokenKeyUnavailableException(
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
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC marker cannot be read",
                error,
            )
        }
        val header = markerHeaderBytes()
        try {
            if (bytes.size != header.size + CredentialTokenFingerprintHmac.DIGEST_SIZE_BYTES) {
                throw CredentialTokenKeyUnavailableException(
                    "Credential fingerprint HMAC marker is invalid",
                )
            }
            val retainedHeader = bytes.copyOfRange(0, header.size)
            val retainedProof = bytes.copyOfRange(header.size, bytes.size)
            var calculatedProof: ByteArray? = null
            try {
                val proof = markerProof(key, header)
                calculatedProof = proof
                if (
                    !retainedHeader.contentEquals(header) ||
                    !MessageDigest.isEqual(retainedProof, proof)
                ) {
                    throw CredentialTokenKeyUnavailableException(
                        "Credential fingerprint HMAC marker authentication failed",
                    )
                }
            } finally {
                retainedHeader.fill(0)
                retainedProof.fill(0)
                calculatedProof?.fill(0)
            }
        } finally {
            header.fill(0)
            bytes.fill(0)
        }
    }

    private fun writeMarker(key: SecretKey) {
        markerFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw CredentialTokenKeyUnavailableException(
                    "Credential fingerprint HMAC marker directory cannot be created",
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
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC marker cannot be written",
                error,
            )
        }
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicMarker.finishWrite(stream)
        } catch (error: Exception) {
            atomicMarker.failWrite(stream)
            throw CredentialTokenKeyUnavailableException(
                "Credential fingerprint HMAC marker write did not complete",
                error,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun markerHeaderBytes(): ByteArray =
        "$MARKER_PREFIX|$CURRENT_KEY_GENERATION|$keyAlias|"
            .toByteArray(StandardCharsets.US_ASCII)

    private fun markerProof(key: SecretKey, header: ByteArray): ByteArray = try {
        Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            update(MARKER_CHALLENGE_BYTES)
            doFinal(header)
        }
    } catch (error: Exception) {
        throw CredentialTokenKeyUnavailableException(
            "Credential fingerprint HMAC marker cannot be authenticated",
            error,
        )
    }

    override fun toString(): String = "KeystoreCredentialTokenHmacKeyring(redacted=true)"

    companion object {
        const val DEFAULT_KEY_ALIAS =
            "life_agent_android_credential_token_fingerprint_hmac_v1"
        const val DEFAULT_MARKER_RELATIVE_PATH =
            "crypto/android-credential-token-fingerprint-hmac-v1.provisioned"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CURRENT_KEY_GENERATION = 1
        private const val KEY_SIZE_BITS = 256
        private const val MARKER_PREFIX =
            "life-agent-android-credential-token-fingerprint-hmac"
        private val MARKER_CHALLENGE_BYTES =
            "life-agent/android-credential-token-fingerprint-marker/v1"
                .toByteArray(StandardCharsets.US_ASCII)
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private val provisioningLock = Any()
    }
}

internal class CredentialTokenFormatException :
    IllegalArgumentException("Credential token is not canonical")

internal class CredentialTokenKeyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class CredentialTokenFingerprintMismatchException :
    IllegalStateException("Credential token fingerprint mismatch")
