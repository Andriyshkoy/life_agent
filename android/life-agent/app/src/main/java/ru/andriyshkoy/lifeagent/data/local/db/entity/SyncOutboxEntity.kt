package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = LocalCaptureEntity::class,
            parentColumns = [
                "capture_id",
                "operation_id",
                "local_owner_id",
                "installation_id",
            ],
            childColumns = [
                "capture_id",
                "operation_id",
                "local_owner_id",
                "installation_id",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LocalEventRevisionEntity::class,
            parentColumns = [
                "event_id",
                "revision_id",
                "capture_id",
                "operation_id",
            ],
            childColumns = [
                "event_id",
                "revision_id",
                "capture_id",
                "operation_id",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LocalEventRevisionEntity::class,
            parentColumns = ["event_id", "revision_id"],
            childColumns = ["event_id", "base_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["operation_id"], unique = true),
        Index(value = ["capture_id"]),
        Index(value = ["local_owner_id", "installation_id"]),
        Index(value = ["event_id", "revision_id"]),
        Index(value = ["event_id", "base_revision_id"]),
        Index(value = ["revision_id"], unique = true),
        Index(value = ["state", "next_attempt_at_epoch_ms"]),
        Index(value = ["local_sequence", "operation_id"], unique = true),
        Index(value = ["wire_state", "local_sequence"]),
        Index(value = ["active_batch_id"]),
        Index(
            value = [
                "capture_id",
                "operation_id",
                "local_owner_id",
                "installation_id",
            ],
        ),
        Index(
            value = [
                "event_id",
                "revision_id",
                "capture_id",
                "operation_id",
            ],
        ),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_sequence")
    val localSequence: Long = 0,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "capture_id")
    val captureId: String,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "operation_kind")
    val operationKind: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "base_revision_id")
    val baseRevisionId: String?,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String,
    @ColumnInfo(name = "operation_jcs", typeAffinity = ColumnInfo.BLOB)
    val legacyOperationJcs: ByteArray,
    @ColumnInfo(name = "operation_content_sha256")
    val legacyOperationContentSha256: String,
    @ColumnInfo(name = "command_fingerprint_sha256", defaultValue = "''")
    val commandFingerprintSha256: String,
    @ColumnInfo(name = "wire_state", defaultValue = "'needs_materialization'")
    val wireState: String = "needs_materialization",
    @ColumnInfo(name = "wire_protocol_version")
    val wireProtocolVersion: String? = null,
    @ColumnInfo(name = "wire_operation_material_jcs", typeAffinity = ColumnInfo.BLOB)
    val wireOperationMaterialJcs: ByteArray? = null,
    @ColumnInfo(name = "wire_operation_content_sha256")
    val wireOperationContentSha256: String? = null,
    @ColumnInfo(name = "wire_materialized_at_utc")
    val wireMaterializedAtUtc: String? = null,
    @ColumnInfo(name = "active_batch_id")
    val activeBatchId: String? = null,
    @ColumnInfo(name = "last_result_batch_id")
    val lastResultBatchId: String? = null,
    @ColumnInfo(name = "last_result_code")
    val lastResultCode: String? = null,
    @ColumnInfo(name = "last_result_retryable")
    val lastResultRetryable: Boolean? = null,
    @ColumnInfo(name = "last_result_current_revision_id")
    val lastResultCurrentRevisionId: String? = null,
    @ColumnInfo(name = "last_result_details_jcs", typeAffinity = ColumnInfo.BLOB)
    val lastResultDetailsJcs: ByteArray? = null,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at_epoch_ms")
    val nextAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "server_sequence")
    val serverSequence: Long?,
    @ColumnInfo(name = "acked_at_utc")
    val ackedAtUtc: String?,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)
