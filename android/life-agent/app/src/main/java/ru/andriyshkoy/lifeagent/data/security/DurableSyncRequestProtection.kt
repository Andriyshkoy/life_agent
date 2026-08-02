package ru.andriyshkoy.lifeagent.data.security

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.M2WireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.RevokeRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec

internal data class NewDurableRequestPersistence(
    val localCredentialEpochId: String,
    val accessGenerationUsed: Long,
    val attemptBudget: Int,
    val deadlineAtEpochMs: Long,
    val createdAtUtc: String,
) {
    init {
        require(accessGenerationUsed > 0)
        require(attemptBudget in 1..64)
        require(deadlineAtEpochMs > 0)
        Instant.parse(createdAtUtc)
    }

    override fun toString(): String = "NewDurableRequestPersistence(redacted=true)"
}

/** Serializes once and protects the exact bytes before their first send. */
internal class DurableSyncRequestProtector(
    context: Context,
    private val hmacKeyring: KeystoreRequestBodyHmacKeyring,
) {
    private val appContext = context.applicationContext

    fun protectNew(
        request: M2WireRequest,
        persistence: NewDurableRequestPersistence,
    ): SyncHttpRequestEntity {
        try {
            require(request.endpoint.durableExactReplay) {
                "Only durable exact-replay requests may be protected"
            }
            return WireRequestCodec.materialize(request).use { materialized ->
                materialized.useBody { exactBody ->
                val correlation = WireRequestCodec.decodeDurableCorrelation(
                    endpoint = materialized.endpoint,
                    bytes = exactBody,
                )
                require(
                    correlation.correlationId == materialized.correlationId &&
                        (
                            materialized.endpoint != M2Endpoint.SYNC_PUSH ||
                                materialized.idempotencyKey == correlation.correlationId
                            ) &&
                        (
                            materialized.endpoint == M2Endpoint.SYNC_PUSH ||
                                materialized.idempotencyKey == null
                            ) &&
                        (
                            correlation.credentialGeneration == null ||
                                correlation.credentialGeneration ==
                                persistence.accessGenerationUsed
                            ),
                ) {
                    "Durable request credential generation drifted"
                }
                val binding = DurableRequestBodyHmacBinding(
                    endpointId = materialized.endpoint.endpointId,
                    protocolVersion = CURRENT_PROTOCOL_VERSION,
                    localCredentialEpochId = persistence.localCredentialEpochId,
                    deviceId = correlation.deviceId,
                    keyEpoch = hmacKeyring.currentGeneration.toULong(),
                )
                val hmac = hmacKeyring.signNew(binding, exactBody)
                try {
                    buildEntity(
                        request = request,
                        correlationId = correlation.correlationId,
                        deviceId = correlation.deviceId,
                        exactBody = exactBody,
                        hmac = hmac,
                        persistence = persistence,
                    )
                } catch (error: Throwable) {
                    hmac.fill(0)
                    throw error
                }
                }
            }
        } finally {
            (request as? AutoCloseable)?.close()
        }
    }

    private fun buildEntity(
        request: M2WireRequest,
        correlationId: String,
        deviceId: String,
        exactBody: ByteArray,
        hmac: ByteArray,
        persistence: NewDurableRequestPersistence,
    ): SyncHttpRequestEntity {
        val isRevoke = request is RevokeRequest
        val envelope = if (isRevoke) {
            KeystoreAeadPayloadCipher(
                context = appContext,
                keyAlias = revokeAeadAlias(
                    localCredentialEpochId = persistence.localCredentialEpochId,
                    requestIdentity = correlationId,
                ),
                keyGeneration = CURRENT_REVOKE_AEAD_KEY_GENERATION,
                aadVersion = KeystoreAeadPayloadCipher.CURRENT_AAD_VERSION,
            ).seal(
                plaintext = exactBody,
                purpose = REVOKE_AEAD_PURPOSE,
                recordIdentity = correlationId,
            )
        } else {
            null
        }
        val rawBody = if (isRevoke) null else exactBody.copyOf()
        return try {
            SyncHttpRequestEntity(
                endpointId = request.endpoint.endpointId,
                requestIdentity = correlationId,
                protocolVersion = CURRENT_PROTOCOL_VERSION,
                credentialEpochId = persistence.localCredentialEpochId,
                deviceId = deviceId,
                idempotencyKey = if (request.endpoint == M2Endpoint.SYNC_PUSH) {
                    correlationId
                } else {
                    null
                },
                bodyStorageKind = if (isRevoke) {
                    SyncHttpRequestEntity.BODY_STORAGE_KEYSTORE_AEAD
                } else {
                    SyncHttpRequestEntity.BODY_STORAGE_RAW
                },
                rawRequestBody = rawBody,
                sealedBodyCiphertext = envelope?.ciphertext,
                sealedBodyNonce = envelope?.nonce,
                sealedBodyKeyAlias = envelope?.keyAlias,
                sealedBodyKeyGeneration = envelope?.keyGeneration,
                sealedBodyAadVersion = envelope?.aadVersion,
                requestBodyOctetCount = exactBody.size.toLong(),
                rawBodyHmac = hmac,
                hmacKeyGeneration = hmacKeyring.currentGeneration,
                state = "ready",
                attemptBudget = persistence.attemptBudget,
                deadlineAtEpochMs = persistence.deadlineAtEpochMs,
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                accessGenerationUsed = persistence.accessGenerationUsed,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = persistence.createdAtUtc,
                updatedAtUtc = persistence.createdAtUtc,
            ).also(::requireValidProtectedRequestMetadata)
        } catch (error: Throwable) {
            rawBody?.fill(0)
            envelope?.ciphertext?.fill(0)
            envelope?.nonce?.fill(0)
            throw error
        }
    }
}

/** Load-only verification boundary used immediately before an attempt claim. */
internal class DurableSyncRequestVerifier(
    context: Context,
    private val hmacKeyring: KeystoreRequestBodyHmacKeyring,
) {
    private val appContext = context.applicationContext

    fun loadVerified(request: SyncHttpRequestEntity): VerifiedDurableRequest {
        val endpoint = requireValidProtectedRequestMetadata(request)
        val binding = request.hmacBinding()
        return when (request.bodyStorageKind) {
            SyncHttpRequestEntity.BODY_STORAGE_RAW -> {
                val body = checkNotNull(request.rawRequestBody).copyOf()
                try {
                    verifyBody(request, endpoint, binding, body)
                    VerifiedDurableRequest(
                        endpoint = endpoint,
                        requestIdentity = request.requestIdentity,
                        idempotencyKey = request.idempotencyKey,
                        body = body,
                    )
                } catch (error: Throwable) {
                    body.fill(0)
                    throw error
                }
            }

            SyncHttpRequestEntity.BODY_STORAGE_KEYSTORE_AEAD -> {
                val envelope = KeystoreAeadEnvelope(
                    ciphertext = checkNotNull(request.sealedBodyCiphertext),
                    nonce = checkNotNull(request.sealedBodyNonce),
                    keyAlias = checkNotNull(request.sealedBodyKeyAlias),
                    keyGeneration = checkNotNull(request.sealedBodyKeyGeneration),
                    aadVersion = checkNotNull(request.sealedBodyAadVersion),
                    plaintextOctetCount = request.requestBodyOctetCount,
                )
                var retained: ByteArray? = null
                KeystoreAeadPayloadCipher(
                    context = appContext,
                    keyAlias = revokeAeadAlias(
                        localCredentialEpochId = request.credentialEpochId,
                        requestIdentity = request.requestIdentity,
                    ),
                    keyGeneration = CURRENT_REVOKE_AEAD_KEY_GENERATION,
                    aadVersion = KeystoreAeadPayloadCipher.CURRENT_AAD_VERSION,
                ).withAuthenticatedPlaintext(
                    envelope = envelope,
                    purpose = REVOKE_AEAD_PURPOSE,
                    recordIdentity = request.requestIdentity,
                ) { plaintext ->
                    verifyBody(request, endpoint, binding, plaintext)
                    retained = plaintext.copyOf()
                }
                VerifiedDurableRequest(
                    endpoint = endpoint,
                    requestIdentity = request.requestIdentity,
                    idempotencyKey = request.idempotencyKey,
                    body = checkNotNull(retained),
                )
            }

            else -> error("Unknown durable request storage kind")
        }
    }

    private fun verifyBody(
        request: SyncHttpRequestEntity,
        endpoint: M2Endpoint,
        binding: DurableRequestBodyHmacBinding,
        body: ByteArray,
    ) {
        if (body.size.toLong() != request.requestBodyOctetCount) {
            throw RequestBodyMetadataInvalidException()
        }
        hmacKeyring.verifyExisting(binding, body, request.rawBodyHmac)
        val correlation = try {
            WireRequestCodec.decodeDurableCorrelation(endpoint, body)
        } catch (error: Exception) {
            throw RequestBodyMetadataInvalidException(error)
        }
        if (
            correlation.correlationId != request.requestIdentity ||
            correlation.deviceId != request.deviceId ||
            (
                endpoint == M2Endpoint.AUTH_REVOKE &&
                    correlation.credentialGeneration != request.accessGenerationUsed
                ) ||
            (
                endpoint == M2Endpoint.SYNC_PUSH &&
                    request.idempotencyKey != request.requestIdentity
                ) ||
            (endpoint != M2Endpoint.SYNC_PUSH && request.idempotencyKey != null)
        ) {
            throw RequestBodyMetadataInvalidException()
        }
    }
}

internal class VerifiedDurableRequest internal constructor(
    val endpoint: M2Endpoint,
    val requestIdentity: String,
    val idempotencyKey: String?,
    body: ByteArray,
) : AutoCloseable {
    private var bodyStorage: ByteArray? = body

    val bodySize: Int
        get() = checkNotNull(bodyStorage) { "Verified request is closed" }.size

    internal inline fun <T> inspectBody(block: (ByteArray) -> T): T {
        val copy = checkNotNull(bodyStorage) { "Verified request is closed" }.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    inline fun <T> consumeBody(block: (ByteArray) -> T): T {
        val owned = checkNotNull(bodyStorage) { "Verified request is closed" }
        bodyStorage = null
        return try {
            block(owned)
        } finally {
            owned.fill(0)
        }
    }

    override fun close() {
        bodyStorage?.fill(0)
        bodyStorage = null
    }

    override fun toString(): String =
        "VerifiedDurableRequest(endpoint=${endpoint.endpointId},bodySize=${bodyStorage?.size ?: 0},redacted=true)"
}

internal fun requireValidProtectedRequestMetadata(
    request: SyncHttpRequestEntity,
): M2Endpoint {
    val endpoint = M2Endpoint.fromId(request.endpointId)
        ?.takeIf { it.durableExactReplay }
        ?: throw RequestBodyMetadataInvalidException()
    if (
        !CANONICAL_UUID_PATTERN.matches(request.requestIdentity) ||
        request.protocolVersion != CURRENT_PROTOCOL_VERSION ||
        request.hmacKeyGeneration != CURRENT_HMAC_KEY_GENERATION ||
        request.rawBodyHmac.size != DurableRequestBodyHmac.DIGEST_SIZE_BYTES ||
        request.requestBodyOctetCount !in 1L..endpoint.requestMaxBytes.toLong() ||
        request.accessGenerationUsed == null ||
        request.accessGenerationUsed <= 0 ||
        (endpoint == M2Endpoint.SYNC_PUSH && request.idempotencyKey != request.requestIdentity) ||
        (endpoint != M2Endpoint.SYNC_PUSH && request.idempotencyKey != null)
    ) {
        throw RequestBodyMetadataInvalidException()
    }
    try {
        request.hmacBinding()
    } catch (error: IllegalArgumentException) {
        throw RequestBodyMetadataInvalidException(error)
    }
    when (request.bodyStorageKind) {
        SyncHttpRequestEntity.BODY_STORAGE_RAW -> {
            val body = request.rawRequestBody
            if (
                endpoint == M2Endpoint.AUTH_REVOKE ||
                body == null ||
                body.size.toLong() != request.requestBodyOctetCount ||
                request.sealedBodyCiphertext != null ||
                request.sealedBodyNonce != null ||
                request.sealedBodyKeyAlias != null ||
                request.sealedBodyKeyGeneration != null ||
                request.sealedBodyAadVersion != null
            ) {
                throw RequestBodyMetadataInvalidException()
            }
        }

        SyncHttpRequestEntity.BODY_STORAGE_KEYSTORE_AEAD -> {
            val ciphertext = request.sealedBodyCiphertext
            if (
                endpoint != M2Endpoint.AUTH_REVOKE ||
                request.rawRequestBody != null ||
                request.sealedBodyKeyAlias != revokeAeadAlias(
                    request.credentialEpochId,
                    request.requestIdentity,
                ) ||
                request.sealedBodyKeyGeneration != CURRENT_REVOKE_AEAD_KEY_GENERATION ||
                request.sealedBodyAadVersion != KeystoreAeadPayloadCipher.CURRENT_AAD_VERSION ||
                request.sealedBodyNonce?.size != GCM_NONCE_SIZE_BYTES ||
                ciphertext == null ||
                ciphertext.size.toLong() != request.requestBodyOctetCount + GCM_TAG_SIZE_BYTES
            ) {
                throw RequestBodyMetadataInvalidException()
            }
        }

        else -> throw RequestBodyMetadataInvalidException()
    }
    return endpoint
}

private fun SyncHttpRequestEntity.hmacBinding() = DurableRequestBodyHmacBinding(
    endpointId = endpointId,
    protocolVersion = protocolVersion,
    localCredentialEpochId = credentialEpochId,
    deviceId = deviceId,
    keyEpoch = hmacKeyGeneration.toULong(),
)

internal fun revokeAeadAlias(
    localCredentialEpochId: String,
    requestIdentity: String,
): String {
    require(
        CANONICAL_UUID_PATTERN.matches(localCredentialEpochId) &&
            CANONICAL_UUID_PATTERN.matches(requestIdentity),
    ) {
        "Revoke AEAD identity must use canonical UUIDs"
    }
    val namespace =
        "$REVOKE_ALIAS_DOMAIN|$localCredentialEpochId|$requestIdentity"
            .toByteArray(StandardCharsets.US_ASCII)
    val digest = try {
        MessageDigest.getInstance("SHA-256").digest(namespace)
    } finally {
        namespace.fill(0)
    }
    return try {
        REVOKE_ALIAS_PREFIX + digest.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    } finally {
        digest.fill(0)
    }
}

internal class RequestBodyMetadataInvalidException(
    cause: Throwable? = null,
) : IllegalStateException("Durable request body metadata is invalid", cause)

private const val CURRENT_PROTOCOL_VERSION = "1.0.0"
private const val CURRENT_REVOKE_AEAD_KEY_GENERATION = 1
private const val REVOKE_AEAD_PURPOSE = "auth_revoke_request"
private const val REVOKE_ALIAS_DOMAIN = "life-agent/revoke-request-aead-alias/v1"
private const val REVOKE_ALIAS_PREFIX = "life_agent_revoke_request_aead_v1_"
private const val GCM_NONCE_SIZE_BYTES = 12
private const val GCM_TAG_SIZE_BYTES = 16L
private val CANONICAL_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
