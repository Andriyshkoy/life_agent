package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_installation",
    indices = [
        Index(value = ["server_device_id"], unique = true),
    ],
)
data class LocalInstallationEntity(
    @PrimaryKey
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
    @ColumnInfo(name = "server_device_id")
    val serverDeviceId: String? = null,
)
