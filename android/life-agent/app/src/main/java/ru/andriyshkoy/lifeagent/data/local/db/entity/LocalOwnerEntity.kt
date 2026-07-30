package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_owner",
    foreignKeys = [
        ForeignKey(
            entity = LocalInstallationEntity::class,
            parentColumns = ["installation_id"],
            childColumns = ["installation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["installation_id"], unique = true),
        Index(value = ["local_owner_id", "installation_id"], unique = true),
        Index(value = ["server_person_id"], unique = true),
    ],
)
data class LocalOwnerEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
    @ColumnInfo(name = "server_person_id")
    val serverPersonId: String? = null,
)
