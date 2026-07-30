package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_catalog_version",
    foreignKeys = [
        ForeignKey(
            entity = LocalCatalogItemEntity::class,
            parentColumns = ["catalog_item_id"],
            childColumns = ["catalog_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["catalog_item_id", "catalog_version_id"], unique = true),
        Index(value = ["catalog_item_id", "version_no"], unique = true),
    ],
)
data class LocalCatalogVersionEntity(
    @PrimaryKey
    @ColumnInfo(name = "catalog_version_id")
    val catalogVersionId: String,
    @ColumnInfo(name = "catalog_item_id")
    val catalogItemId: String,
    @ColumnInfo(name = "version_no")
    val versionNo: Int,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String,
    @ColumnInfo(name = "payload_jcs", typeAffinity = ColumnInfo.BLOB)
    val payloadJcs: ByteArray,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
)
