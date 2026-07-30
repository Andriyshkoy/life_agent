package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_life_event",
    foreignKeys = [
        ForeignKey(
            entity = LocalOwnerEntity::class,
            parentColumns = ["local_owner_id"],
            childColumns = ["local_owner_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["local_owner_id", "kind"]),
    ],
)
data class LocalLifeEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
)
