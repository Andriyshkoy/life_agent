package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity

@Dao
interface IdentityDao {
    @Query(
        """
        SELECT i.installation_id, o.local_owner_id
        FROM local_identity_state AS s
        JOIN local_owner AS o
          ON o.local_owner_id = s.local_owner_id
         AND o.installation_id = s.installation_id
        JOIN local_installation AS i ON i.installation_id = o.installation_id
        WHERE s.singleton_id = 1
        """,
    )
    suspend fun findIdentity(): LocalIdentityRow?

    @Query("SELECT COUNT(*) FROM local_owner")
    suspend fun ownerCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallation(entity: LocalInstallationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOwner(entity: LocalOwnerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIdentityState(entity: LocalIdentityStateEntity)
}
