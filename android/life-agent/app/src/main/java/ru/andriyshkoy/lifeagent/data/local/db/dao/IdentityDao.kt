package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity

@Dao
interface IdentityDao {
    @Query(
        """
        SELECT i.installation_id, o.local_owner_id, i.server_device_id, o.server_person_id
        FROM local_owner AS o
        JOIN local_installation AS i ON i.installation_id = o.installation_id
        LIMIT 1
        """,
    )
    suspend fun findIdentity(): LocalIdentityRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallation(entity: LocalInstallationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOwner(entity: LocalOwnerEntity)
}
