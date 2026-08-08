package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCatalogVersionEntity

@Dao
interface WellbeingCatalogDao {
    @Query(
        """
        SELECT i.*,
               v.catalog_version_id AS version_catalog_version_id,
               v.catalog_item_id AS version_catalog_item_id,
               v.version_no AS version_version_no,
               v.schema_version AS version_schema_version,
               v.payload_jcs AS version_payload_jcs,
               v.content_sha256 AS version_content_sha256,
               v.created_at_utc AS version_created_at_utc,
               h.catalog_item_id AS head_catalog_item_id,
               h.current_version_id AS head_current_version_id,
               h.updated_at_utc AS head_updated_at_utc
        FROM local_catalog_item AS i
        JOIN local_catalog_head AS h ON h.catalog_item_id = i.catalog_item_id
        JOIN local_catalog_version AS v
          ON v.catalog_item_id = i.catalog_item_id
         AND v.catalog_version_id = h.current_version_id
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_kind = 'wellbeing_dimension'
        ORDER BY i.catalog_item_id
        """,
    )
    fun observeCurrent(): Flow<List<CurrentWellbeingCatalogRow>>

    @Query(
        """
        SELECT i.*,
               v.catalog_version_id AS version_catalog_version_id,
               v.catalog_item_id AS version_catalog_item_id,
               v.version_no AS version_version_no,
               v.schema_version AS version_schema_version,
               v.payload_jcs AS version_payload_jcs,
               v.content_sha256 AS version_content_sha256,
               v.created_at_utc AS version_created_at_utc,
               h.catalog_item_id AS head_catalog_item_id,
               h.current_version_id AS head_current_version_id,
               h.updated_at_utc AS head_updated_at_utc
        FROM local_catalog_item AS i
        JOIN local_catalog_head AS h ON h.catalog_item_id = i.catalog_item_id
        JOIN local_catalog_version AS v
          ON v.catalog_item_id = i.catalog_item_id
         AND v.catalog_version_id = h.current_version_id
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_item_id = :dimensionId
          AND i.catalog_kind = 'wellbeing_dimension'
        """,
    )
    suspend fun findCurrent(dimensionId: String): CurrentWellbeingCatalogRow?

    @Query(
        """
        SELECT i.*,
               v.catalog_version_id AS version_catalog_version_id,
               v.catalog_item_id AS version_catalog_item_id,
               v.version_no AS version_version_no,
               v.schema_version AS version_schema_version,
               v.payload_jcs AS version_payload_jcs,
               v.content_sha256 AS version_content_sha256,
               v.created_at_utc AS version_created_at_utc
        FROM local_catalog_item AS i
        JOIN local_catalog_version AS v ON v.catalog_item_id = i.catalog_item_id
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_item_id = :dimensionId
          AND v.catalog_version_id = :versionId
          AND i.catalog_kind = 'wellbeing_dimension'
        """,
    )
    suspend fun findVersion(
        dimensionId: String,
        versionId: String,
    ): WellbeingCatalogVersionContextRow?

    @Query(
        """
        SELECT i.*,
               v.catalog_version_id AS version_catalog_version_id,
               v.catalog_item_id AS version_catalog_item_id,
               v.version_no AS version_version_no,
               v.schema_version AS version_schema_version,
               v.payload_jcs AS version_payload_jcs,
               v.content_sha256 AS version_content_sha256,
               v.created_at_utc AS version_created_at_utc
        FROM local_catalog_item AS i
        JOIN local_catalog_version AS v ON v.catalog_item_id = i.catalog_item_id
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_kind = 'wellbeing_dimension'
        ORDER BY i.catalog_item_id, v.version_no, v.catalog_version_id
        """,
    )
    suspend fun findAllVersionContexts(): List<WellbeingCatalogVersionContextRow>

    @Query(
        """
        SELECT COUNT(*)
        FROM local_catalog_item AS i
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_kind = 'wellbeing_dimension'
        """,
    )
    suspend fun countDimensions(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM local_catalog_item WHERE catalog_item_id = :itemId)")
    suspend fun itemExists(itemId: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM local_catalog_version WHERE catalog_version_id = :versionId)",
    )
    suspend fun versionExists(versionId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(entity: LocalCatalogItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(entity: LocalCatalogVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHead(entity: LocalCatalogHeadEntity)

    @Query(
        """
        UPDATE local_catalog_head
        SET current_version_id = :newVersionId,
            updated_at_utc = :updatedAtUtc
        WHERE catalog_item_id = :dimensionId
          AND current_version_id = :expectedVersionId
        """,
    )
    suspend fun compareAndSetHead(
        dimensionId: String,
        expectedVersionId: String,
        newVersionId: String,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        SELECT i.*
        FROM local_catalog_item AS i
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_kind = 'wellbeing_dimension'
        ORDER BY i.catalog_item_id
        """,
    )
    suspend fun findAllItems(): List<LocalCatalogItemEntity>

    @Query(
        """
        SELECT v.*
        FROM local_catalog_version AS v
        JOIN local_catalog_item AS i ON i.catalog_item_id = v.catalog_item_id
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_kind = 'wellbeing_dimension'
        ORDER BY v.catalog_item_id, v.version_no, v.catalog_version_id
        """,
    )
    suspend fun findAllVersions(): List<LocalCatalogVersionEntity>

    @Query(
        """
        SELECT h.*
        FROM local_catalog_head AS h
        JOIN local_catalog_item AS i ON i.catalog_item_id = h.catalog_item_id
        JOIN local_identity_state AS s ON s.local_owner_id = i.local_owner_id
        WHERE i.catalog_kind = 'wellbeing_dimension'
        ORDER BY h.catalog_item_id
        """,
    )
    suspend fun findAllHeads(): List<LocalCatalogHeadEntity>
}
