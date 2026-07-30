package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity

@Dao
interface IdentityDao {
    @Query(
        """
        SELECT i.installation_id, o.local_owner_id, i.server_device_id, o.server_person_id
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

    @Query(
        """
        UPDATE local_installation
        SET server_device_id = :deviceId
        WHERE installation_id = :installationId
          AND (server_device_id IS NULL OR server_device_id = :deviceId)
        """,
    )
    suspend fun bindInstallationToDevice(
        installationId: String,
        deviceId: String,
    ): Int

    @Query(
        """
        UPDATE local_owner
        SET server_person_id = :personId
        WHERE local_owner_id = :localOwnerId
          AND installation_id = :installationId
          AND (server_person_id IS NULL OR server_person_id = :personId)
        """,
    )
    suspend fun bindOwnerToPerson(
        installationId: String,
        localOwnerId: String,
        personId: String,
    ): Int

    @Transaction
    suspend fun bindCurrentServerIdentity(
        installationId: String,
        localOwnerId: String,
        deviceId: String,
        personId: String,
    ) {
        val current = checkNotNull(findIdentity()) {
            "Current local identity is missing"
        }
        check(
            current.installationId == installationId &&
                current.localOwnerId == localOwnerId,
        ) {
            "Enrollment response does not match current local identity"
        }
        check(bindInstallationToDevice(installationId, deviceId) == 1) {
            "Installation is already bound to another device"
        }
        check(bindOwnerToPerson(installationId, localOwnerId, personId) == 1) {
            "Owner is already bound to another person"
        }
    }

    @Query(
        """
        UPDATE local_identity_state
        SET installation_id = :newInstallationId,
            local_owner_id = :newLocalOwnerId,
            selected_at_utc = :selectedAtUtc
        WHERE singleton_id = 1
          AND installation_id = :expectedInstallationId
          AND local_owner_id = :expectedLocalOwnerId
        """,
    )
    suspend fun compareAndSelectIdentity(
        expectedInstallationId: String,
        expectedLocalOwnerId: String,
        newInstallationId: String,
        newLocalOwnerId: String,
        selectedAtUtc: String,
    ): Int
}
