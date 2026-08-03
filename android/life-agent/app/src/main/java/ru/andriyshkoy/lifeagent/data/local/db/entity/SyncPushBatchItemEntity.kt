package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sync_push_batch_item",
    primaryKeys = ["batch_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = SyncPushBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncOutboxEntity::class,
            parentColumns = ["local_sequence", "operation_id"],
            childColumns = ["local_sequence", "operation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["batch_id", "local_sequence"], unique = true),
        Index(value = ["batch_id", "operation_id"], unique = true),
        Index(value = ["local_sequence", "operation_id"]),
    ],
)
data class SyncPushBatchItemEntity(
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "local_sequence")
    val localSequence: Long,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "wire_operation_content_sha256")
    val wireOperationContentSha256: String,
)
