package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity

/** Shared append-only mutation primitives for all local life-event domains. */
@Dao
interface LifeEventMutationDao {
    @Query(
        """
        SELECT e.kind AS event_kind,
               h.current_revision_id AS head_revision_id,
               r.*
        FROM local_life_event AS e
        JOIN local_event_head AS h ON h.event_id = e.event_id
        JOIN local_event_revision AS r
          ON r.event_id = h.event_id AND r.revision_id = h.current_revision_id
        WHERE e.event_id = :eventId
        """,
    )
    suspend fun findCurrentRevision(eventId: String): CurrentRevisionRow?

    @Query("SELECT EXISTS(SELECT 1 FROM local_capture WHERE capture_id = :captureId)")
    suspend fun captureExists(captureId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM local_life_event WHERE event_id = :eventId)")
    suspend fun eventExists(eventId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM local_event_revision WHERE revision_id = :revisionId)")
    suspend fun revisionExists(revisionId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM local_event_revision WHERE operation_id = :operationId)")
    suspend fun operationExists(operationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCapture(entity: LocalCaptureEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(entity: LocalLifeEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: LocalEventRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParent(entity: LocalRevisionParentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHead(entity: LocalEventHeadEntity)

    @Query(
        """
        UPDATE local_event_head
        SET current_revision_id = :newRevisionId,
            updated_at_utc = :updatedAtUtc
        WHERE event_id = :eventId AND current_revision_id = :expectedRevisionId
        """,
    )
    suspend fun compareAndSetHead(
        eventId: String,
        expectedRevisionId: String,
        newRevisionId: String,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM local_capture) AS captures,
          (SELECT COUNT(*) FROM local_life_event) AS events,
          (SELECT COUNT(*) FROM local_event_revision) AS revisions,
          (SELECT COUNT(*) FROM local_revision_parent) AS parents,
          (SELECT COUNT(*) FROM local_event_head) AS heads
        """,
    )
    suspend fun tableCounts(): LocalTableCounts
}
