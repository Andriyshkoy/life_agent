package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_push_batch",
    foreignKeys = [
        ForeignKey(
            entity = SyncHttpRequestEntity::class,
            parentColumns = ["endpoint_id", "request_identity"],
            childColumns = ["endpoint_id", "request_identity"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["endpoint_id", "request_identity"], unique = true),
    ],
)
data class SyncPushBatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "batch_content_sha256")
    val batchContentSha256: String,
    @ColumnInfo(name = "operation_count")
    val operationCount: Int,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
)
