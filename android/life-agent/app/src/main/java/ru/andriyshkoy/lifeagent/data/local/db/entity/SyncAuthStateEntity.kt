package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Installed credential family. Absence of this singleton means unenrolled.
 *
 * Access-token bytes are deliberately absent and remain process-memory only.
 * The refresh token is persisted only as a separate Android Keystore AEAD
 * envelope. Its nullable components are cleared together when the family is
 * expired or quarantined, while the epoch/generation guard remains durable.
 */
@Entity(
    tableName = "sync_auth_state",
    foreignKeys = [
        ForeignKey(
            entity = LocalOwnerEntity::class,
            parentColumns = ["local_owner_id", "installation_id"],
            childColumns = ["local_owner_id", "installation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["credential_epoch_id"], unique = true),
        Index(value = ["local_owner_id", "installation_id"]),
        Index(value = ["device_id"], unique = true),
        Index(value = ["state"]),
    ],
)
data class SyncAuthStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = CURRENT_ID,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "person_id")
    val personId: String,
    @ColumnInfo(name = "token_type")
    val tokenType: String,
    @ColumnInfo(name = "refresh_token_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val refreshTokenCiphertext: ByteArray?,
    @ColumnInfo(name = "refresh_token_nonce", typeAffinity = ColumnInfo.BLOB)
    val refreshTokenNonce: ByteArray?,
    @ColumnInfo(name = "refresh_token_key_alias")
    val refreshTokenKeyAlias: String?,
    @ColumnInfo(name = "refresh_token_key_generation")
    val refreshTokenKeyGeneration: Int?,
    @ColumnInfo(name = "refresh_token_aad_version")
    val refreshTokenAadVersion: Int?,
    @ColumnInfo(name = "access_expires_at_utc")
    val accessExpiresAtUtc: String,
    @ColumnInfo(name = "access_expires_at_epoch_ms")
    val accessExpiresAtEpochMs: Long,
    @ColumnInfo(name = "refresh_expires_at_utc")
    val refreshExpiresAtUtc: String,
    @ColumnInfo(name = "refresh_expires_at_epoch_ms")
    val refreshExpiresAtEpochMs: Long,
    @ColumnInfo(name = "family_expires_at_utc")
    val familyExpiresAtUtc: String,
    @ColumnInfo(name = "family_expires_at_epoch_ms")
    val familyExpiresAtEpochMs: Long,
    @ColumnInfo(name = "generation")
    val generation: Long,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "bootstrap_required")
    val bootstrapRequired: Boolean,
    @ColumnInfo(name = "installed_at_utc")
    val installedAtUtc: String,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
) {
    init {
        require(
            state in setOf(
                "active",
                "refresh_in_flight",
                "revoke_pending",
                "quarantined",
                "expired",
                "revoked",
                "integrity_failure",
            ),
        ) {
            "Unknown credential-family state"
        }
        require(tokenType == "Bearer")
        require(generation > 0)
        require(credentialEpochId.isNotBlank())
        require(installationId.isNotBlank())
        require(localOwnerId.isNotBlank())
        require(deviceId.isNotBlank())
        require(personId.isNotBlank())
        val envelopeParts = listOf(
            refreshTokenCiphertext,
            refreshTokenNonce,
            refreshTokenKeyAlias,
            refreshTokenKeyGeneration,
            refreshTokenAadVersion,
        )
        val envelopePresent = envelopeParts.all { it != null }
        require(envelopePresent || envelopeParts.all { it == null }) {
            "Refresh-token AEAD envelope must be complete or absent"
        }
        if (envelopePresent) {
            require(checkNotNull(refreshTokenCiphertext).isNotEmpty())
            require(checkNotNull(refreshTokenNonce).isNotEmpty())
            require(checkNotNull(refreshTokenKeyAlias).isNotBlank())
            require(checkNotNull(refreshTokenKeyGeneration) > 0)
            require(checkNotNull(refreshTokenAadVersion) > 0)
        }
        if (state in setOf("active", "refresh_in_flight", "revoke_pending")) {
            require(envelopePresent) {
                "A usable credential family requires a refresh-token envelope"
            }
        } else {
            require(!envelopePresent) {
                "An unusable credential family must not retain a refresh-token envelope"
            }
        }
        require(accessExpiresAtEpochMs > 0)
        require(refreshExpiresAtEpochMs > accessExpiresAtEpochMs)
        require(familyExpiresAtEpochMs >= refreshExpiresAtEpochMs)
        require(Instant.parse(accessExpiresAtUtc).toEpochMilli() == accessExpiresAtEpochMs)
        require(Instant.parse(refreshExpiresAtUtc).toEpochMilli() == refreshExpiresAtEpochMs)
        require(Instant.parse(familyExpiresAtUtc).toEpochMilli() == familyExpiresAtEpochMs)
    }

    companion object {
        const val CURRENT_ID = 1
    }
}
