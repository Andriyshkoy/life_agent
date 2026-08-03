package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Selects the one identity used for new local writes.
 *
 * Historical installations and owners may coexist after bootstrap or device
 * replacement. They never implicitly become current merely because they exist.
 */
@Entity(
    tableName = "local_identity_state",
    foreignKeys = [
        ForeignKey(
            entity = LocalOwnerEntity::class,
            parentColumns = ["local_owner_id", "installation_id"],
            childColumns = ["local_owner_id", "installation_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["local_owner_id", "installation_id"]),
        Index(value = ["installation_id", "local_owner_id"], unique = true),
    ],
)
data class LocalIdentityStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = CURRENT_ID,
    @ColumnInfo(name = "installation_id")
    val installationId: String,
    @ColumnInfo(name = "local_owner_id")
    val localOwnerId: String,
    @ColumnInfo(name = "selected_at_utc")
    val selectedAtUtc: String,
) {
    companion object {
        const val CURRENT_ID = 1
    }
}
