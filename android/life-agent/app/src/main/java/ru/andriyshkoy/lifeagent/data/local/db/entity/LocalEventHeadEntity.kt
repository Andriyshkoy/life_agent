package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_event_head",
    foreignKeys = [
        ForeignKey(
            entity = LocalLifeEventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalEventRevisionEntity::class,
            parentColumns = ["event_id", "revision_id"],
            childColumns = ["event_id", "current_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["event_id", "current_revision_id"]),
        Index(value = ["current_revision_id"], unique = true),
    ],
)
data class LocalEventHeadEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
)
