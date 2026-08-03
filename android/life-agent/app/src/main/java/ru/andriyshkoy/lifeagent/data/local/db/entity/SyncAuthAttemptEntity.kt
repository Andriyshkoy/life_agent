package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Content-free marker for non-replayable enrollment and refresh attempts.
 *
 * Enrollment codes, refresh tokens, and serialized request bodies must never be
 * written to this table.
 */
@Entity(
    tableName = "sync_auth_attempt",
    foreignKeys = [
        ForeignKey(
            entity = LocalOwnerEntity::class,
            parentColumns = ["local_owner_id", "installation_id"],
            childColumns = ["local_owner_id", "installation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["local_owner_id", "installation_id"]),
        Index(value = ["endpoint_id", "state"]),
        Index(value = ["credential_epoch_id", "state"]),
    ],
)
data class SyncAuthAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String?,
    @ColumnInfo(name = "expected_device_id")
    val expectedDeviceId: String?,
    @ColumnInfo(name = "expected_generation")
    val expectedGeneration: Long?,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)
