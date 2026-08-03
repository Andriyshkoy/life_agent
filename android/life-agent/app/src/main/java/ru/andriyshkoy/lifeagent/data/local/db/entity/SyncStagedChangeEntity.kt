package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sync_staged_change",
    primaryKeys = ["bootstrap_id", "server_sequence"],
    foreignKeys = [
        ForeignKey(
            entity = SyncBootstrapSessionEntity::class,
            parentColumns = ["bootstrap_id"],
            childColumns = ["bootstrap_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncPageReceiptEntity::class,
            parentColumns = ["page_id", "bootstrap_id"],
            childColumns = ["page_id", "bootstrap_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["page_id", "bootstrap_id"]),
        Index(value = ["bootstrap_id", "operation_id"], unique = true),
        Index(value = ["bootstrap_id", "capture_id"], unique = true),
        Index(value = ["bootstrap_id", "revision_id"], unique = true),
    ],
)
data class SyncStagedChangeEntity(
    @ColumnInfo(name = "bootstrap_id")
    val bootstrapId: String,
    @ColumnInfo(name = "server_sequence")
    val serverSequence: Long,
    @ColumnInfo(name = "page_id")
    val pageId: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "operation_content_sha256")
    val operationContentSha256: String,
    @ColumnInfo(name = "capture_id")
    val captureId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "result_code")
    val resultCode: String,
    @ColumnInfo(name = "committed_at_utc")
    val committedAtUtc: String,
    @ColumnInfo(name = "change_jcs", typeAffinity = ColumnInfo.BLOB)
    val changeJcs: ByteArray,
)
