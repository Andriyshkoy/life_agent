package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_page_receipt",
    foreignKeys = [
        ForeignKey(
            entity = SyncHttpRequestEntity::class,
            parentColumns = ["endpoint_id", "request_identity"],
            childColumns = ["endpoint_id", "request_identity"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SyncBootstrapSessionEntity::class,
            parentColumns = ["bootstrap_id"],
            childColumns = ["bootstrap_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["endpoint_id", "request_identity"], unique = true),
        Index(value = ["bootstrap_id", "page_index"], unique = true),
        Index(value = ["page_id", "bootstrap_id"], unique = true),
        Index(value = ["state"]),
    ],
)
data class SyncPageReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "bootstrap_id")
    val bootstrapId: String?,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String?,
    @ColumnInfo(name = "from_cursor")
    val fromCursor: String?,
    @ColumnInfo(name = "next_cursor")
    val nextCursor: String?,
    @ColumnInfo(name = "incremental_cursor")
    val incrementalCursor: String?,
    @ColumnInfo(name = "page_sha256")
    val pageSha256: String,
    @ColumnInfo(name = "change_count")
    val changeCount: Int,
    @ColumnInfo(name = "complete_or_has_more")
    val completeOrHasMore: Boolean,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "first_server_sequence")
    val firstServerSequence: Long?,
    @ColumnInfo(name = "last_server_sequence")
    val lastServerSequence: Long?,
    @ColumnInfo(name = "received_at_utc")
    val receivedAtUtc: String,
    @ColumnInfo(name = "applied_at_utc")
    val appliedAtUtc: String?,
)
