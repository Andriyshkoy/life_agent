package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity

@Dao
interface OutboxDao {
    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE state = 'pending'
        ORDER BY local_sequence
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>
}
