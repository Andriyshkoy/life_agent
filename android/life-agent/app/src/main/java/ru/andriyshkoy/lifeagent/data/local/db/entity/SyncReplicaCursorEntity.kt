package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Opaque cursor reservation scoped to one server-issued bootstrap lineage.
 *
 * Cursor values are intentionally never diagnostic output. The composite key
 * rejects reuse inside one lineage while allowing a replacement bootstrap to
 * receive the same opaque value without colliding with historical state.
 */
@Entity(
    tableName = "sync_replica_cursor",
    primaryKeys = ["lineage_id", "cursor_value"],
    foreignKeys = [
        ForeignKey(
            entity = SyncBootstrapSessionEntity::class,
            parentColumns = ["bootstrap_id"],
            childColumns = ["lineage_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["lineage_id", "role"]),
    ],
)
data class SyncReplicaCursorEntity(
    @ColumnInfo(name = "lineage_id")
    val lineageId: String,
    @ColumnInfo(name = "cursor_value")
    val cursorValue: String,
    @ColumnInfo(name = "role")
    val role: String,
) {
    init {
        require(lineageId.isNotBlank())
        require(cursorValue.isNotBlank())
        require(role == ROLE_BOOTSTRAP_PAGE || role == ROLE_INCREMENTAL) {
            "Unknown replica cursor role"
        }
    }

    override fun toString(): String =
        "SyncReplicaCursorEntity(role=$role,redacted=true)"

    companion object {
        const val ROLE_BOOTSTRAP_PAGE = "bootstrap_page"
        const val ROLE_INCREMENTAL = "incremental"
    }
}
