package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_event_revision",
    foreignKeys = [
        ForeignKey(
            entity = LocalLifeEventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalCaptureEntity::class,
            parentColumns = ["capture_id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_id", "revision_id"], unique = true),
        Index(value = ["capture_id"]),
        Index(value = ["operation_id"], unique = true),
        Index(value = ["event_id", "revision_no"]),
        Index(value = ["local_date", "effective_start_epoch_ms"]),
    ],
)
data class LocalEventRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "capture_id")
    val captureId: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "revision_no")
    val revisionNo: Int,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String,
    @ColumnInfo(name = "assertion_status")
    val assertionStatus: String,
    @ColumnInfo(name = "lifecycle")
    val lifecycle: String?,
    @ColumnInfo(name = "record_status")
    val recordStatus: String,
    @ColumnInfo(name = "verification_status")
    val verificationStatus: String,
    @ColumnInfo(name = "source_channel")
    val sourceChannel: String,
    @ColumnInfo(name = "source_record_id")
    val sourceRecordId: String?,
    @ColumnInfo(name = "source_record_version")
    val sourceRecordVersion: String?,
    @ColumnInfo(name = "source_modified_at")
    val sourceModifiedAt: String?,
    @ColumnInfo(name = "recorded_at_rfc3339")
    val recordedAtRfc3339: String,
    @ColumnInfo(name = "origin_provider")
    val originProvider: String?,
    @ColumnInfo(name = "origin_app")
    val originApp: String?,
    @ColumnInfo(name = "origin_device")
    val originDevice: String?,
    @ColumnInfo(name = "origin_user_entered")
    val originUserEntered: Boolean,
    @ColumnInfo(name = "collector_name")
    val collectorName: String,
    @ColumnInfo(name = "collector_version")
    val collectorVersion: String,
    @ColumnInfo(name = "effective_start_utc")
    val effectiveStartUtc: String,
    @ColumnInfo(name = "effective_start_epoch_ms")
    val effectiveStartEpochMs: Long,
    @ColumnInfo(name = "effective_end_utc")
    val effectiveEndUtc: String?,
    @ColumnInfo(name = "effective_end_epoch_ms")
    val effectiveEndEpochMs: Long?,
    @ColumnInfo(name = "original_local_start")
    val originalLocalStart: String,
    @ColumnInfo(name = "original_local_end")
    val originalLocalEnd: String?,
    @ColumnInfo(name = "timezone_id")
    val timezoneId: String,
    @ColumnInfo(name = "start_offset_seconds")
    val startOffsetSeconds: Int,
    @ColumnInfo(name = "end_offset_seconds")
    val endOffsetSeconds: Int?,
    @ColumnInfo(name = "temporal_precision")
    val temporalPrecision: String,
    @ColumnInfo(name = "local_date")
    val localDate: String,
    @ColumnInfo(name = "source_expression")
    val sourceExpression: String?,
    @ColumnInfo(name = "payload_jcs", typeAffinity = ColumnInfo.BLOB)
    val payloadJcs: ByteArray,
    @ColumnInfo(name = "evidence_jcs", typeAffinity = ColumnInfo.BLOB)
    val evidenceJcs: ByteArray,
    @ColumnInfo(name = "quality_flags_jcs", typeAffinity = ColumnInfo.BLOB)
    val qualityFlagsJcs: ByteArray,
    @ColumnInfo(name = "created_at_rfc3339")
    val createdAtRfc3339: String,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "actor")
    val actor: String,
    @ColumnInfo(name = "correction_reason")
    val correctionReason: String?,
    @ColumnInfo(name = "server_received_at")
    val serverReceivedAt: String?,
    @ColumnInfo(name = "server_sequence")
    val serverSequence: Long?,
)
