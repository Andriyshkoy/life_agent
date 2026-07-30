package ru.andriyshkoy.lifeagent.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ru.andriyshkoy.lifeagent.data.local.db.dao.IdentityDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.NoteMutationDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.NoteQueryDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.OutboxDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncAuthDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncReplicaDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.SyncTransportDao
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogVersionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthAttemptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStagedChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity

@Database(
    entities = [
        LocalInstallationEntity::class,
        LocalOwnerEntity::class,
        LocalIdentityStateEntity::class,
        LocalCatalogItemEntity::class,
        LocalCatalogVersionEntity::class,
        LocalCatalogHeadEntity::class,
        LocalCaptureEntity::class,
        LocalLifeEventEntity::class,
        LocalEventRevisionEntity::class,
        LocalRevisionParentEntity::class,
        LocalEventHeadEntity::class,
        SyncOutboxEntity::class,
        SyncAuthStateEntity::class,
        SyncAuthAttemptEntity::class,
        SyncAuthTokenFingerprintEntity::class,
        SyncHttpRequestEntity::class,
        SyncPushBatchEntity::class,
        SyncPushBatchItemEntity::class,
        SyncServerChangeEntity::class,
        SyncStreamStateEntity::class,
        SyncBootstrapSessionEntity::class,
        SyncPageReceiptEntity::class,
        SyncStagedChangeEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LifeAgentDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao

    abstract fun noteMutationDao(): NoteMutationDao

    abstract fun noteQueryDao(): NoteQueryDao

    abstract fun outboxDao(): OutboxDao

    abstract fun syncAuthDao(): SyncAuthDao

    abstract fun syncTransportDao(): SyncTransportDao

    abstract fun syncReplicaDao(): SyncReplicaDao

    companion object {
        const val NAME = "life-agent.db"
        const val VERSION = 3
    }
}
