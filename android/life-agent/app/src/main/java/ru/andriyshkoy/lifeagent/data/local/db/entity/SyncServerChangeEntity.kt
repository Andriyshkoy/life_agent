package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable terminal receipt and server change registry.
 *
 * [currentRevisionId] intentionally has no local FK: a conflict ACK may name a
 * remote head that a later pull has not materialized yet.
 */
@Entity(
    tableName = "sync_server_change",
    foreignKeys = [
        ForeignKey(
            entity = LocalEventRevisionEntity::class,
            parentColumns = [
                "event_id",
                "revision_id",
                "capture_id",
                "operation_id",
                "server_sequence",
            ],
            childColumns = [
                "event_id",
                "revision_id",
                "capture_id",
                "operation_id",
                "server_sequence",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SyncHttpRequestEntity::class,
            parentColumns = ["endpoint_id", "request_identity"],
            childColumns = ["first_endpoint_id", "first_request_identity"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["operation_id"], unique = true),
        Index(value = ["capture_id"], unique = true),
        Index(value = ["revision_id"], unique = true),
        Index(
            value = [
                "event_id",
                "revision_id",
                "capture_id",
                "operation_id",
                "server_sequence",
            ],
        ),
        Index(value = ["first_endpoint_id", "first_request_identity"]),
    ],
)
data class SyncServerChangeEntity(
    @PrimaryKey
    @ColumnInfo(name = "server_sequence")
    val serverSequence: Long,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "operation_content_sha256")
    val operationContentSha256: String,
    @ColumnInfo(name = "result_code")
    val resultCode: String,
    @ColumnInfo(name = "capture_id")
    val captureId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "committed_at_utc")
    val committedAtUtc: String,
    @ColumnInfo(name = "first_endpoint_id")
    val firstEndpointId: String,
    @ColumnInfo(name = "first_request_identity")
    val firstRequestIdentity: String,
    @ColumnInfo(name = "verified_at_utc")
    val verifiedAtUtc: String,
)
