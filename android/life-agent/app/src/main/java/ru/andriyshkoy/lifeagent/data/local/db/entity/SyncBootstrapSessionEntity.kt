package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_bootstrap_session",
    indices = [
        Index(value = ["state"]),
        Index(value = ["credential_epoch_id", "device_id", "state"]),
        Index(value = ["snapshot_id"]),
        Index(value = ["active_slot"], unique = true),
    ],
)
data class SyncBootstrapSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "bootstrap_id")
    val bootstrapId: String,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "active_slot")
    val activeSlot: Int?,
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String?,
    @ColumnInfo(name = "next_page_cursor")
    val nextPageCursor: String?,
    @ColumnInfo(name = "candidate_incremental_cursor")
    val candidateIncrementalCursor: String?,
    @ColumnInfo(name = "next_page_index")
    val nextPageIndex: Int,
    @ColumnInfo(name = "last_staged_server_sequence")
    val lastStagedServerSequence: Long?,
    @ColumnInfo(name = "staged_page_count", defaultValue = "0")
    val stagedPageCount: Int = 0,
    @ColumnInfo(name = "staged_body_bytes", defaultValue = "0")
    val stagedBodyBytes: Long = 0,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
)
