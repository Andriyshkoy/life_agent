package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_stream_state",
    indices = [
        Index(value = ["credential_epoch_id", "device_id"], unique = true),
        Index(value = ["phase"]),
    ],
)
data class SyncStreamStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = CURRENT_ID,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "phase")
    val phase: String,
    @ColumnInfo(name = "bootstrap_required")
    val bootstrapRequired: Boolean,
    @ColumnInfo(name = "applied_cursor")
    val appliedCursor: String?,
    @ColumnInfo(name = "last_applied_server_sequence", defaultValue = "0")
    val lastAppliedServerSequence: Long = 0,
    @ColumnInfo(name = "high_watermark_hint")
    val highWatermarkHint: String?,
    @ColumnInfo(name = "integrity_error_code")
    val integrityErrorCode: String?,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
) {
    companion object {
        const val CURRENT_ID = 1
    }
}
