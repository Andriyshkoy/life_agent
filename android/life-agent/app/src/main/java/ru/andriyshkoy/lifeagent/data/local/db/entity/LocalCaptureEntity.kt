package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_capture",
    foreignKeys = [
        ForeignKey(
            entity = LocalOwnerEntity::class,
            parentColumns = ["local_owner_id", "installation_id"],
            childColumns = ["local_owner_id", "installation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["operation_id"], unique = true),
        Index(value = ["capture_id", "operation_id"], unique = true),
        Index(
            value = [
                "capture_id",
                "operation_id",
                "local_owner_id",
                "installation_id",
            ],
            unique = true,
        ),
        Index(value = ["local_owner_id", "installation_id"]),
        Index(value = ["recorded_at_epoch_ms"]),
    ],
)
data class LocalCaptureEntity(
    @PrimaryKey
    @ColumnInfo(name = "capture_id")
    val captureId: String,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String,
    @ColumnInfo(name = "persistence_state")
    val persistenceState: String,
    @ColumnInfo(name = "source_channel")
    val sourceChannel: String,
    @ColumnInfo(name = "recorded_at_rfc3339")
    val recordedAtRfc3339: String,
    @ColumnInfo(name = "recorded_at_epoch_ms")
    val recordedAtEpochMs: Long,
    @ColumnInfo(name = "timezone_id")
    val timezoneId: String,
    @ColumnInfo(name = "utc_offset_minutes")
    val utcOffsetMinutes: Int,
    @ColumnInfo(name = "origin_provider")
    val originProvider: String?,
    @ColumnInfo(name = "origin_app")
    val originApp: String?,
    @ColumnInfo(name = "origin_device")
    val originDevice: String?,
    @ColumnInfo(name = "origin_source_record_id")
    val originSourceRecordId: String?,
    @ColumnInfo(name = "origin_source_record_version")
    val originSourceRecordVersion: String?,
    @ColumnInfo(name = "origin_user_entered")
    val originUserEntered: Boolean,
    @ColumnInfo(name = "collector_name")
    val collectorName: String,
    @ColumnInfo(name = "collector_version")
    val collectorVersion: String,
    @ColumnInfo(name = "content_jcs", typeAffinity = ColumnInfo.BLOB)
    val contentJcs: ByteArray,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
)
