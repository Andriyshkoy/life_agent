package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stable identity for an offline catalog entry.
 *
 * M1 intentionally exposes no CRUD DAO. Later catalog edits append immutable
 * [LocalCatalogVersionEntity] rows and advance [LocalCatalogHeadEntity].
 */
@Entity(
    tableName = "local_catalog_item",
    foreignKeys = [
        ForeignKey(
            entity = LocalOwnerEntity::class,
            parentColumns = ["local_owner_id"],
            childColumns = ["local_owner_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["local_owner_id", "catalog_kind"]),
    ],
)
data class LocalCatalogItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "catalog_item_id")
    val catalogItemId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "catalog_kind")
    val catalogKind: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
)
