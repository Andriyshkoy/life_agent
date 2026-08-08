package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "local_revision_parent",
    primaryKeys = ["child_revision_id", "parent_revision_id"],
    foreignKeys = [
        ForeignKey(
            entity = LocalEventRevisionEntity::class,
            parentColumns = ["event_id", "revision_id"],
            childColumns = ["event_id", "child_revision_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalEventRevisionEntity::class,
            parentColumns = ["event_id", "revision_id"],
            childColumns = ["event_id", "parent_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["child_revision_id"], unique = true),
        Index(value = ["event_id", "child_revision_id"]),
        Index(value = ["event_id", "parent_revision_id"]),
    ],
)
data class LocalRevisionParentEntity(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "child_revision_id")
    val childRevisionId: String,
    @ColumnInfo(name = "parent_revision_id")
    val parentRevisionId: String,
    @ColumnInfo(name = "relation")
    val relation: String,
)
