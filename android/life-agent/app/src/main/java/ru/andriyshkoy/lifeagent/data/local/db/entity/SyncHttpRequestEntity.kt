package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Exact replay material for endpoints whose contract permits or requires it.
 *
 * The Authorization header is deliberately absent. A worker receives only the
 * composite record identity and loads/verifies these bytes inside the process.
 * Auth revoke is special: its body contains a refresh token and therefore only
 * its Android-Keystore-sealed ciphertext may be stored.
 */
@Entity(
    tableName = "sync_http_request",
    primaryKeys = ["endpoint_id", "request_identity"],
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["state", "next_attempt_at_epoch_ms"]),
        Index(value = ["credential_epoch_id", "state"]),
        Index(value = ["active_attempt_id"], unique = true),
    ],
)
data class SyncHttpRequestEntity(
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "protocol_version")
    val protocolVersion: String,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String?,
    @ColumnInfo(name = "body_storage_kind")
    val bodyStorageKind: String = BODY_STORAGE_RAW,
    @ColumnInfo(name = "raw_request_body", typeAffinity = ColumnInfo.BLOB)
    val rawRequestBody: ByteArray?,
    @ColumnInfo(name = "sealed_body_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val sealedBodyCiphertext: ByteArray? = null,
    @ColumnInfo(name = "sealed_body_nonce", typeAffinity = ColumnInfo.BLOB)
    val sealedBodyNonce: ByteArray? = null,
    @ColumnInfo(name = "sealed_body_key_alias")
    val sealedBodyKeyAlias: String? = null,
    @ColumnInfo(name = "sealed_body_key_generation")
    val sealedBodyKeyGeneration: Int? = null,
    @ColumnInfo(name = "sealed_body_aad_version")
    val sealedBodyAadVersion: Int? = null,
    @ColumnInfo(name = "request_body_octet_count")
    val requestBodyOctetCount: Long = rawRequestBody?.size?.toLong() ?: 0,
    @ColumnInfo(name = "raw_body_hmac", typeAffinity = ColumnInfo.BLOB)
    val rawBodyHmac: ByteArray,
    @ColumnInfo(name = "hmac_key_generation")
    val hmacKeyGeneration: Int,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "attempt_budget")
    val attemptBudget: Int,
    @ColumnInfo(name = "deadline_at_epoch_ms")
    val deadlineAtEpochMs: Long,
    @ColumnInfo(name = "next_attempt_at_epoch_ms")
    val nextAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "lease_expires_at_epoch_ms")
    val leaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "active_attempt_id")
    val activeAttemptId: String? = null,
    @ColumnInfo(name = "access_generation_used")
    val accessGenerationUsed: Long?,
    @ColumnInfo(name = "refresh_attempted", defaultValue = "0")
    val refreshAttempted: Boolean = false,
    @ColumnInfo(name = "original_retry_count", defaultValue = "0")
    val originalRetryCount: Int = 0,
    @ColumnInfo(name = "terminal_http_status")
    val terminalHttpStatus: Int?,
    @ColumnInfo(name = "exact_response_body", typeAffinity = ColumnInfo.BLOB)
    val exactResponseBody: ByteArray?,
    @ColumnInfo(name = "response_sha256")
    val responseSha256: String?,
    @ColumnInfo(name = "terminal_at_utc")
    val terminalAtUtc: String?,
    @ColumnInfo(name = "terminal_error_code")
    val terminalErrorCode: String?,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
) {
    init {
        require(
            endpointId in setOf(
                "auth_revoke",
                "sync_push",
                "sync_bootstrap",
                "sync_pull",
            ),
        ) {
            "Only replayable endpoint requests may be persisted"
        }
        require(requestBodyOctetCount > 0)
        val sealedParts = listOf(
            sealedBodyCiphertext,
            sealedBodyNonce,
            sealedBodyKeyAlias,
            sealedBodyKeyGeneration,
            sealedBodyAadVersion,
        )
        val sealedComplete = sealedParts.all { it != null }
        when (bodyStorageKind) {
            BODY_STORAGE_RAW -> {
                require(endpointId != "auth_revoke") {
                    "Auth revoke body must never be stored as raw Room bytes"
                }
                require(rawRequestBody != null && rawRequestBody.isNotEmpty())
                require(requestBodyOctetCount == rawRequestBody.size.toLong())
                require(sealedParts.all { it == null })
            }

            BODY_STORAGE_KEYSTORE_AEAD -> {
                require(endpointId == "auth_revoke")
                require(rawRequestBody == null)
                require(sealedComplete)
                require(checkNotNull(sealedBodyCiphertext).isNotEmpty())
                require(checkNotNull(sealedBodyNonce).isNotEmpty())
                require(checkNotNull(sealedBodyKeyAlias).isNotBlank())
                require(checkNotNull(sealedBodyKeyGeneration) > 0)
                require(checkNotNull(sealedBodyAadVersion) > 0)
            }

            else -> error("Unknown request body storage kind")
        }
        require(rawBodyHmac.isNotEmpty())
        require(hmacKeyGeneration > 0)
    }

    companion object {
        const val BODY_STORAGE_RAW = "raw"
        const val BODY_STORAGE_KEYSTORE_AEAD = "keystore_aead"
    }
}
