package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity

data class LocalIdentityRow(
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "server_device_id")
    val serverDeviceId: String?,
    @ColumnInfo(name = "server_person_id")
    val serverPersonId: String?,
)

data class CurrentRevisionRow(
    @ColumnInfo(name = "event_kind")
    val eventKind: String,
    @ColumnInfo(name = "head_revision_id")
    val headRevisionId: String,
    @ColumnInfo(name = "server_current_revision_id")
    val serverCurrentRevisionId: String?,
    @Embedded
    val revision: LocalEventRevisionEntity,
    @ColumnInfo(name = "local_sequence")
    val localSequence: Long?,
)

data class RevisionContextRow(
    @Embedded
    val revision: LocalEventRevisionEntity,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "server_device_id")
    val serverDeviceId: String?,
    @ColumnInfo(name = "server_current_revision_id")
    val serverCurrentRevisionId: String?,
    @ColumnInfo(name = "local_sequence")
    val localSequence: Long?,
)

data class EventPointerRow(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "server_current_revision_id")
    val serverCurrentRevisionId: String?,
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
    @ColumnInfo(name = "outbox_operations")
    val outboxOperations: Int,
)

data class OperationLookupRow(
    @Embedded
    val outbox: SyncOutboxEntity,
    @Embedded(prefix = "revision_")
    val revision: LocalEventRevisionEntity,
)
