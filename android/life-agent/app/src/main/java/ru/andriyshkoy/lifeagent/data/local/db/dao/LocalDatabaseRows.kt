package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity

data class LocalIdentityRow(
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
)

data class CurrentRevisionRow(
    @ColumnInfo(name = "event_kind")
    val eventKind: String,
    @ColumnInfo(name = "head_revision_id")
    val headRevisionId: String,
    @Embedded
    val revision: LocalEventRevisionEntity,
)

data class RevisionContextRow(
    @Embedded
    val revision: LocalEventRevisionEntity,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
)

data class EventPointerRow(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
)

data class LocalTableCounts(
    @ColumnInfo(name = "captures")
    val captures: Int,
    @ColumnInfo(name = "events")
    val events: Int,
    @ColumnInfo(name = "revisions")
    val revisions: Int,
    @ColumnInfo(name = "parents")
    val parents: Int,
    @ColumnInfo(name = "heads")
    val heads: Int,
)
