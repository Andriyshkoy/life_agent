package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_catalog_head",
    foreignKeys = [
        ForeignKey(
            entity = LocalCatalogItemEntity::class,
            parentColumns = ["catalog_item_id"],
            childColumns = ["catalog_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocalCatalogVersionEntity::class,
            parentColumns = ["catalog_item_id", "catalog_version_id"],
            childColumns = ["catalog_item_id", "current_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["catalog_item_id", "current_version_id"]),
        Index(value = ["current_version_id"], unique = true),
    ],
)
data class LocalCatalogHeadEntity(
    @PrimaryKey
    @ColumnInfo(name = "catalog_item_id")
    val catalogItemId: String,
    @ColumnInfo(name = "current_version_id")
    val currentVersionId: String,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
)
