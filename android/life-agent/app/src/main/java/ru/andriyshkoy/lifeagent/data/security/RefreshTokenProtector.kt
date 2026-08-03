package ru.andriyshkoy.lifeagent.data.security

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.requireCanonicalUuid
import ru.andriyshkoy.lifeagent.data.sync.wire.requireRefreshToken

/**
 * Durable refresh-token envelope with names that map one-to-one to
 * [SyncAuthStateEntity]. The plaintext length is fixed by the canonical M2
 * refresh-token grammar and therefore does not need another durable column.
 */
internal data class RefreshTokenEnvelope(
    val refreshTokenCiphertext: ByteArray,
    val refreshTokenNonce: ByteArray,
    val refreshTokenKeyAlias: String,
    val refreshTokenKeyGeneration: Int,
    val refreshTokenAadVersion: Int,
) {
    init {
        require(refreshTokenCiphertext.isNotEmpty()) {
            "Refresh-token envelope ciphertext is invalid"
        }
        require(refreshTokenNonce.isNotEmpty()) {
            "Refresh-token envelope nonce is invalid"
        }
        require(refreshTokenKeyAlias.isNotBlank()) {
            "Refresh-token envelope key metadata is invalid"
        }
        require(refreshTokenKeyGeneration > 0) {
            "Refresh-token envelope key metadata is invalid"
        }
        require(refreshTokenAadVersion > 0) {
            "Refresh-token envelope AAD metadata is invalid"
        }
    }

    override fun toString(): String = "RefreshTokenEnvelope(redacted=true)"

    companion object {
        fun from(authState: SyncAuthStateEntity): RefreshTokenEnvelope? {
            val ciphertext = authState.refreshTokenCiphertext ?: return null
            return RefreshTokenEnvelope(
                refreshTokenCiphertext = ciphertext,
                refreshTokenNonce = checkNotNull(authState.refreshTokenNonce),
                refreshTokenKeyAlias = checkNotNull(authState.refreshTokenKeyAlias),
                refreshTokenKeyGeneration =
                    checkNotNull(authState.refreshTokenKeyGeneration),
                refreshTokenAadVersion = checkNotNull(authState.refreshTokenAadVersion),
            )
        }
    }
}

/** Android-Keystore protection boundary for the durable refresh token. */
internal class RefreshTokenProtector(
    context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Takes ownership of [ownedRefreshToken] and closes it on every path.
     * [durableReferenceCount] is scoped to this exact credential epoch. Only a
     * zero-reference first seal may provision its key and authenticated
     * no-backup marker; successor generations are load-only with respect to key
     * identity.
     */
    fun seal(
        credentialEpochId: String,
        deviceId: String,
        generation: Long,
        durableReferenceCount: Long,
        ownedRefreshToken: WipeableSecret,
    ): RefreshTokenEnvelope = try {
        requireBinding(credentialEpochId, deviceId, generation)
        require(durableReferenceCount >= 0) {
            "Durable refresh-token reference count is invalid"
        }
        requireRefreshToken(ownedRefreshToken)

        synchronized(keyProvisioningLock) {
            val epochCipher = cipher(credentialEpochId)
            ensureEpochKey(
                credentialEpochId = credentialEpochId,
                durableReferenceCount = durableReferenceCount,
                cipher = epochCipher,
            )
            ownedRefreshToken.useBytes { plaintext ->
                val sealed = epochCipher.seal(
                    plaintext = plaintext,
                    purpose = REFRESH_TOKEN_AEAD_PURPOSE,
                    recordIdentity = aadRecordIdentity(
                        credentialEpochId = credentialEpochId,
                        deviceId = deviceId,
                        generation = generation,
                    ),
                )
                RefreshTokenEnvelope(
                    refreshTokenCiphertext = sealed.ciphertext,
                    refreshTokenNonce = sealed.nonce,
                    refreshTokenKeyAlias = sealed.keyAlias,
                    refreshTokenKeyGeneration = sealed.keyGeneration,
                    refreshTokenAadVersion = sealed.aadVersion,
                )
            }
        }
    } finally {
        ownedRefreshToken.close()
    }

    /**
     * Authenticates an existing envelope without provisioning or replacing an
     * absent Keystore alias. The caller owns and must close the returned secret.
     */
    fun open(
        credentialEpochId: String,
        deviceId: String,
        generation: Long,
        envelope: RefreshTokenEnvelope,
    ): WipeableSecret {
        requireBinding(credentialEpochId, deviceId, generation)
        requireExpectedEnvelopeMetadata(credentialEpochId, envelope)
        val sealed = KeystoreAeadEnvelope(
            ciphertext = envelope.refreshTokenCiphertext,
            nonce = envelope.refreshTokenNonce,
            keyAlias = envelope.refreshTokenKeyAlias,
            keyGeneration = envelope.refreshTokenKeyGeneration,
            aadVersion = envelope.refreshTokenAadVersion,
            plaintextOctetCount = CANONICAL_REFRESH_TOKEN_OCTET_COUNT,
        )
        return synchronized(keyProvisioningLock) {
            val epochCipher = cipher(credentialEpochId)
            validateExistingMarker(credentialEpochId, epochCipher)
            epochCipher.withAuthenticatedPlaintext(
                envelope = sealed,
                purpose = REFRESH_TOKEN_AEAD_PURPOSE,
                recordIdentity = aadRecordIdentity(
                    credentialEpochId = credentialEpochId,
                    deviceId = deviceId,
                    generation = generation,
                ),
            ) { plaintext ->
                WipeableSecret.copyOf(plaintext).also { secret ->
                    try {
                        requireRefreshToken(secret)
                    } catch (error: Throwable) {
                        secret.close()
                        throw error
                    }
                }
            }
        }
    }

    override fun toString(): String = "RefreshTokenProtector(redacted=true)"

    private fun cipher(credentialEpochId: String): KeystoreAeadPayloadCipher =
        KeystoreAeadPayloadCipher(
            context = appContext,
            keyAlias = refreshTokenAeadAlias(credentialEpochId),
            keyGeneration = CURRENT_REFRESH_TOKEN_KEY_GENERATION,
            aadVersion = KeystoreAeadPayloadCipher.CURRENT_AAD_VERSION,
        )

    private fun ensureEpochKey(
        credentialEpochId: String,
        durableReferenceCount: Long,
        cipher: KeystoreAeadPayloadCipher,
    ) {
        if (markerExists(credentialEpochId)) {
            validateExistingMarker(credentialEpochId, cipher)
            return
        }
        if (durableReferenceCount != 0L) {
            throw refreshTokenKeyUnavailable(
                "Durable refresh-token envelope exists without a proven key",
            )
        }
        provisionMarker(credentialEpochId, cipher)
    }

    private fun provisionMarker(
        credentialEpochId: String,
        cipher: KeystoreAeadPayloadCipher,
    ) {
        val challenge = markerChallengeBytes()
        val envelope = try {
            cipher.seal(
                plaintext = challenge,
                purpose = REFRESH_TOKEN_MARKER_PURPOSE,
                recordIdentity = markerRecordIdentity(credentialEpochId),
            )
        } finally {
            challenge.fill(0)
        }
        try {
            writeMarker(credentialEpochId, envelope)
        } finally {
            envelope.ciphertext.fill(0)
            envelope.nonce.fill(0)
        }
    }

    private fun validateExistingMarker(
        credentialEpochId: String,
        cipher: KeystoreAeadPayloadCipher,
    ) {
        if (!markerExists(credentialEpochId)) {
            throw refreshTokenKeyUnavailable(
                "Durable refresh-token key marker is unavailable",
            )
        }
        val envelope = readMarker(credentialEpochId)
        val expected = markerChallengeBytes()
        try {
            cipher.withAuthenticatedPlaintext(
                envelope = envelope,
                purpose = REFRESH_TOKEN_MARKER_PURPOSE,
                recordIdentity = markerRecordIdentity(credentialEpochId),
            ) { plaintext ->
                if (!MessageDigest.isEqual(expected, plaintext)) {
                    throw refreshTokenKeyUnavailable(
                        "Durable refresh-token key marker is invalid",
                    )
                }
            }
        } finally {
            expected.fill(0)
            envelope.ciphertext.fill(0)
            envelope.nonce.fill(0)
        }
    }

    private fun markerExists(credentialEpochId: String): Boolean {
        val marker = markerFile(credentialEpochId)
        return marker.isFile || File(marker.path + ATOMIC_BACKUP_SUFFIX).isFile
    }

    private fun markerFile(credentialEpochId: String): File =
        File(appContext.noBackupFilesDir, refreshTokenMarkerRelativePath(credentialEpochId))

    private fun readMarker(credentialEpochId: String): KeystoreAeadEnvelope {
        val bytes = try {
            AtomicFile(markerFile(credentialEpochId)).readFully()
        } catch (error: Exception) {
            throw refreshTokenKeyUnavailable(
                "Durable refresh-token key marker cannot be read",
                error,
            )
        }
        try {
            if (bytes.size !in MIN_MARKER_FILE_BYTES..MAX_MARKER_FILE_BYTES) {
                throw refreshTokenKeyUnavailable(
                    "Durable refresh-token key marker is invalid",
                )
            }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt()
                val aliasBytes = input.readBoundedBytes(MAX_MARKER_ALIAS_BYTES)
                val alias = try {
                    aliasBytes.toString(StandardCharsets.US_ASCII)
                } finally {
                    aliasBytes.fill(0)
                }
                val keyGeneration = input.readInt()
                val aadVersion = input.readInt()
                val plaintextOctetCount = input.readLong()
                var nonce: ByteArray? = null
                var ciphertext: ByteArray? = null
                try {
                    nonce = input.readBoundedBytes(MAX_MARKER_NONCE_BYTES)
                    ciphertext = input.readBoundedBytes(MAX_MARKER_CIPHERTEXT_BYTES)
                    if (
                        version != MARKER_FILE_VERSION ||
                        input.available() != 0 ||
                        alias != refreshTokenAeadAlias(credentialEpochId) ||
                        keyGeneration != CURRENT_REFRESH_TOKEN_KEY_GENERATION ||
                        aadVersion != KeystoreAeadPayloadCipher.CURRENT_AAD_VERSION ||
                        plaintextOctetCount != REFRESH_TOKEN_MARKER_CHALLENGE.length.toLong()
                    ) {
                        throw refreshTokenKeyUnavailable(
                            "Durable refresh-token key marker metadata is invalid",
                        )
                    }
                    val retainedNonce = checkNotNull(nonce)
                    val retainedCiphertext = checkNotNull(ciphertext)
                    KeystoreAeadEnvelope(
                        ciphertext = retainedCiphertext,
                        nonce = retainedNonce,
                        keyAlias = alias,
                        keyGeneration = keyGeneration,
                        aadVersion = aadVersion,
                        plaintextOctetCount = plaintextOctetCount,
                    ).also {
                        nonce = null
                        ciphertext = null
                    }
                } finally {
                    nonce?.fill(0)
                    ciphertext?.fill(0)
                }
            }
        } catch (error: SensitivePayloadIntegrityException) {
            throw error
        } catch (error: Exception) {
            throw refreshTokenKeyUnavailable(
                "Durable refresh-token key marker is invalid",
                error,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeMarker(
        credentialEpochId: String,
        envelope: KeystoreAeadEnvelope,
    ) {
        val marker = markerFile(credentialEpochId)
        marker.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw refreshTokenKeyUnavailable(
                    "Durable refresh-token key marker directory cannot be created",
                )
            }
        }
        val bytes = markerBytes(envelope)
        val atomicMarker = AtomicFile(marker)
        val stream = try {
            atomicMarker.startWrite()
        } catch (error: Exception) {
            bytes.fill(0)
            throw refreshTokenKeyUnavailable(
                "Durable refresh-token key marker cannot be written",
                error,
            )
        }
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicMarker.finishWrite(stream)
        } catch (error: Exception) {
            atomicMarker.failWrite(stream)
            throw refreshTokenKeyUnavailable(
                "Durable refresh-token key marker write did not complete",
                error,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun markerBytes(envelope: KeystoreAeadEnvelope): ByteArray =
        framedBytes {
            writeInt(MARKER_FILE_VERSION)
            writeLengthPrefixed(envelope.keyAlias)
            writeInt(envelope.keyGeneration)
            writeInt(envelope.aadVersion)
            writeLong(envelope.plaintextOctetCount)
            writeLengthPrefixed(envelope.nonce)
            writeLengthPrefixed(envelope.ciphertext)
        }
}

/** Stable, non-identifying alias for one credential epoch. */
internal fun refreshTokenAeadAlias(credentialEpochId: String): String {
    requireCanonicalUuid(credentialEpochId)
    val namespace = framedBytes {
        writeLengthPrefixed(REFRESH_TOKEN_ALIAS_DOMAIN)
        writeLengthPrefixed(credentialEpochId)
    }
    val digest = try {
        MessageDigest.getInstance("SHA-256").digest(namespace)
    } finally {
        namespace.fill(0)
    }
    return try {
        REFRESH_TOKEN_ALIAS_PREFIX + digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    } finally {
        digest.fill(0)
    }
}

internal fun refreshTokenMarkerRelativePath(credentialEpochId: String): String =
    "crypto/refresh-token/${refreshTokenAeadAlias(credentialEpochId)}.provisioned"

private fun requireBinding(
    credentialEpochId: String,
    deviceId: String,
    generation: Long,
) {
    requireCanonicalUuid(credentialEpochId)
    requireCanonicalUuid(deviceId)
    require(generation > 0) { "Refresh-token generation is invalid" }
}

private fun aadRecordIdentity(
    credentialEpochId: String,
    deviceId: String,
    generation: Long,
): String {
    val frame = framedBytes {
        writeLengthPrefixed(REFRESH_TOKEN_AAD_BINDING_DOMAIN)
        writeLengthPrefixed(credentialEpochId)
        writeLengthPrefixed(deviceId)
        writeLong(generation)
    }
    return try {
        Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    } finally {
        frame.fill(0)
    }
}

private fun markerRecordIdentity(credentialEpochId: String): String {
    val frame = framedBytes {
        writeLengthPrefixed(REFRESH_TOKEN_MARKER_RECORD_DOMAIN)
        writeLengthPrefixed(credentialEpochId)
    }
    return try {
        Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
    } finally {
        frame.fill(0)
    }
}

private inline fun framedBytes(
    writeFrame: DataOutputStream.() -> Unit,
): ByteArray = ByteArrayOutputStream().use { output ->
    DataOutputStream(output).use { framed -> framed.writeFrame() }
    output.toByteArray()
}

private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val bytes = value.toByteArray(StandardCharsets.US_ASCII)
    try {
        writeInt(bytes.size)
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun DataOutputStream.writeLengthPrefixed(value: ByteArray) {
    writeInt(value.size)
    write(value)
}

private fun DataInputStream.readBoundedBytes(maximum: Int): ByteArray {
    val size = readInt()
    if (size !in 1..maximum || size > available()) {
        throw refreshTokenKeyUnavailable(
            "Durable refresh-token key marker field is invalid",
        )
    }
    val bytes = ByteArray(size)
    return try {
        readFully(bytes)
        bytes
    } catch (error: Throwable) {
        bytes.fill(0)
        throw error
    }
}

private fun markerChallengeBytes(): ByteArray =
    REFRESH_TOKEN_MARKER_CHALLENGE.toByteArray(StandardCharsets.US_ASCII)

private fun requireExpectedEnvelopeMetadata(
    credentialEpochId: String,
    envelope: RefreshTokenEnvelope,
) {
    if (
        envelope.refreshTokenKeyAlias != refreshTokenAeadAlias(credentialEpochId) ||
        envelope.refreshTokenKeyGeneration != CURRENT_REFRESH_TOKEN_KEY_GENERATION ||
        envelope.refreshTokenAadVersion != KeystoreAeadPayloadCipher.CURRENT_AAD_VERSION ||
        envelope.refreshTokenNonce.size !in MIN_GCM_NONCE_BYTES..MAX_GCM_NONCE_BYTES ||
        envelope.refreshTokenCiphertext.isEmpty()
    ) {
        throw SensitivePayloadIntegrityException(
            "Refresh-token envelope metadata is invalid",
            failure = SensitivePayloadIntegrityFailure.METADATA_INVALID,
        )
    }
}

private fun refreshTokenKeyUnavailable(
    message: String,
    cause: Throwable? = null,
): SensitivePayloadIntegrityException = SensitivePayloadIntegrityException(
    message = message,
    cause = cause,
    failure = SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
)

private const val CURRENT_REFRESH_TOKEN_KEY_GENERATION = 1
private const val CANONICAL_REFRESH_TOKEN_OCTET_COUNT = 47L
private const val REFRESH_TOKEN_AEAD_PURPOSE = "auth_refresh_token"
private const val REFRESH_TOKEN_MARKER_PURPOSE = "auth_refresh_token_key_marker"
private const val REFRESH_TOKEN_ALIAS_DOMAIN = "life-agent/refresh-token-aead-alias/v1"
private const val REFRESH_TOKEN_AAD_BINDING_DOMAIN = "life-agent/refresh-token-aad-binding/v1"
private const val REFRESH_TOKEN_MARKER_RECORD_DOMAIN =
    "life-agent/refresh-token-key-marker-record/v1"
private const val REFRESH_TOKEN_MARKER_CHALLENGE =
    "life-agent/refresh-token-key-marker-challenge/v1"
private const val REFRESH_TOKEN_ALIAS_PREFIX = "life_agent_refresh_token_aead_v1_"
private const val MARKER_FILE_VERSION = 1
private const val MIN_MARKER_FILE_BYTES = 64
private const val MAX_MARKER_FILE_BYTES = 4_096
private const val MAX_MARKER_ALIAS_BYTES = 256
private const val MAX_MARKER_NONCE_BYTES = 32
private const val MAX_MARKER_CIPHERTEXT_BYTES = 512
private const val MIN_GCM_NONCE_BYTES = 12
private const val MAX_GCM_NONCE_BYTES = 32
private const val ATOMIC_BACKUP_SUFFIX = ".bak"
private val keyProvisioningLock = Any()
