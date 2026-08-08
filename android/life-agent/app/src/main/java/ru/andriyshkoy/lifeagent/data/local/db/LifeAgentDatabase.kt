package ru.andriyshkoy.lifeagent.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ru.andriyshkoy.lifeagent.data.local.db.dao.IdentityDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.LifeEventMutationDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.NoteQueryDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.WellbeingCatalogDao
import ru.andriyshkoy.lifeagent.data.local.db.dao.WellbeingQueryDao
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
    ],
    version = 7,
    exportSchema = true,
)
abstract class LifeAgentDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao

    abstract fun lifeEventMutationDao(): LifeEventMutationDao

    abstract fun noteQueryDao(): NoteQueryDao

    abstract fun wellbeingQueryDao(): WellbeingQueryDao

    abstract fun wellbeingCatalogDao(): WellbeingCatalogDao

    companion object {
        const val NAME = "life-agent.db"
        const val VERSION = 7
    }
}
