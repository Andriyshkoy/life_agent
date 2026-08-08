package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_installation")
data class LocalInstallationEntity(
    @PrimaryKey
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
)
