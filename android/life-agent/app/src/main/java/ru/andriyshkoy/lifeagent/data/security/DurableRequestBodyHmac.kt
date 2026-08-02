package ru.andriyshkoy.lifeagent.data.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Local-only namespace bound to one installed credential family.
 *
 * The fields and framing order are part of the frozen HTTP API contract. Do
 * not add request IDs, package names, storage metadata, or other local values.
 */
internal data class DurableRequestBodyHmacBinding(
    val endpointId: String,
    val protocolVersion: String,
    val localCredentialEpochId: String,
    val deviceId: String,
    val keyEpoch: ULong,
) {
    init {
        requireCanonicalAscii(REQUEST_BODY_HMAC_DOMAIN, "domain")
        requireCanonicalAscii(endpointId, "endpoint_id")
        requireCanonicalAscii(protocolVersion, "protocol_version")
        requireCanonicalAscii(localCredentialEpochId, "local_credential_epoch_id")
        requireCanonicalAscii(deviceId, "device_id")
        require(endpointId in DURABLE_REQUEST_ENDPOINT_IDS) {
            "Endpoint is not eligible for durable retry"
        }
        require(protocolVersion == CURRENT_HTTP_PROTOCOL_VERSION) {
            "Unsupported durable request protocol version"
        }
        requireCanonicalUuid(localCredentialEpochId, "local_credential_epoch_id")
        requireCanonicalUuid(deviceId, "device_id")
        require(keyEpoch > 0u) { "HMAC key epoch must be positive" }
    }

    override fun toString(): String =
        "DurableRequestBodyHmacBinding(endpoint=$endpointId,redacted=true)"
}

/** Exact, streaming implementation of the frozen uint64-BE framing. */
internal object DurableRequestBodyHmac {
    const val DIGEST_SIZE_BYTES = 32

    fun compute(
        key: SecretKey,
        binding: DurableRequestBodyHmacBinding,
        exactRawBody: ByteArray,
    ): ByteArray {
        require(exactRawBody.isNotEmpty()) { "Durable request body must not be empty" }
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(key)
        updateAsciiComponent(mac, REQUEST_BODY_HMAC_DOMAIN)
        updateAsciiComponent(mac, binding.endpointId)
        updateAsciiComponent(mac, binding.protocolVersion)
        updateAsciiComponent(mac, binding.localCredentialEpochId)
        updateAsciiComponent(mac, binding.deviceId)

        val keyEpochBytes = uint64BigEndian(binding.keyEpoch)
        try {
            updateComponent(mac, keyEpochBytes)
        } finally {
            keyEpochBytes.fill(0)
        }
        updateComponent(mac, exactRawBody)
        return mac.doFinal()
    }

    fun verify(
        key: SecretKey,
        binding: DurableRequestBodyHmacBinding,
        exactRawBody: ByteArray,
        expectedHmac: ByteArray,
    ): Boolean {
        if (expectedHmac.size != DIGEST_SIZE_BYTES) return false
        val computed = compute(key, binding, exactRawBody)
        return try {
            MessageDigest.isEqual(computed, expectedHmac)
        } finally {
            computed.fill(0)
        }
    }

    private fun updateAsciiComponent(mac: Mac, value: String) {
        requireCanonicalAscii(value, "HMAC frame component")
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        try {
            updateComponent(mac, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun updateComponent(mac: Mac, value: ByteArray) {
        val length = uint64BigEndian(value.size.toULong())
        try {
            mac.update(length)
            mac.update(value)
        } finally {
            length.fill(0)
        }
    }

    private fun uint64BigEndian(value: ULong): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.toLong()).array()
}

internal const val REQUEST_BODY_HMAC_DOMAIN =
    "life-agent/android-http-retry-body/v1"
internal const val CURRENT_HMAC_KEY_GENERATION = 1

private const val HMAC_SHA_256 = "HmacSHA256"
private const val CURRENT_HTTP_PROTOCOL_VERSION = "1.0.0"
private val DURABLE_REQUEST_ENDPOINT_IDS = setOf(
    "auth_revoke",
    "sync_push",
    "sync_bootstrap",
    "sync_pull",
)

private fun requireCanonicalAscii(value: String, field: String) {
    require(value.isNotEmpty()) { "$field must not be empty" }
    require(value.length <= MAX_ASCII_COMPONENT_OCTETS) { "$field is too long" }
    require(value.all { it.code in 0x21..0x7e }) { "$field must be canonical ASCII" }
}

private fun requireCanonicalUuid(value: String, field: String) {
    require(CANONICAL_UUID_PATTERN.matches(value)) {
        "$field must be a canonical lowercase RFC 4122 UUID"
    }
}

private const val MAX_ASCII_COMPONENT_OCTETS = 256
private val CANONICAL_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
