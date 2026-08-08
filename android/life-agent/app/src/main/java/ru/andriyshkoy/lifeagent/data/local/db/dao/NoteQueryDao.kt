package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity

@Dao
interface NoteQueryDao {
    @Query(
        """
        SELECT e.kind AS event_kind,
               h.current_revision_id AS head_revision_id,
               r.*
        FROM local_event_revision AS r
        JOIN local_life_event AS e ON e.event_id = r.event_id
        JOIN local_event_head AS h
          ON h.event_id = e.event_id AND h.current_revision_id = r.revision_id
        JOIN local_capture AS c
          ON c.capture_id = r.capture_id AND c.operation_id = r.operation_id
        WHERE e.kind = 'note'
        ORDER BY c.recorded_at_epoch_ms DESC, r.revision_id DESC
        LIMIT 1
        """,
    )
    fun observeLastCommitted(): Flow<CurrentRevisionRow?>

    @Query(
        """
        SELECT e.kind AS event_kind,
               h.current_revision_id AS head_revision_id,
               r.*
        FROM local_life_event AS e
        JOIN local_event_head AS h ON h.event_id = e.event_id
        JOIN local_event_revision AS r
          ON r.event_id = h.event_id AND r.revision_id = h.current_revision_id
        WHERE e.event_id = :eventId AND e.kind = 'note'
        """,
    )
    suspend fun findCurrentNote(eventId: String): CurrentRevisionRow?

    @Query(
        """
        SELECT r.*,
               c.installation_id,
               c.local_owner_id
        FROM local_event_revision AS r
        JOIN local_capture AS c
          ON c.capture_id = r.capture_id AND c.operation_id = r.operation_id
        JOIN local_life_event AS e ON e.event_id = r.event_id
        WHERE r.operation_id = :operationId AND e.kind = 'note'
        """,
    )
    suspend fun findByOperationId(operationId: String): RevisionContextRow?

    @Query(
        """
        SELECT r.*,
               c.installation_id,
               c.local_owner_id
        FROM local_event_revision AS r
        JOIN local_capture AS c
          ON c.capture_id = r.capture_id AND c.operation_id = r.operation_id
        JOIN local_life_event AS e ON e.event_id = r.event_id
        WHERE e.kind = 'note'
        ORDER BY r.event_id, r.revision_no, r.revision_id
        """,
    )
    suspend fun findAllRevisionContexts(): List<RevisionContextRow>

    @Query(
        """
        SELECT h.event_id, h.current_revision_id
        FROM local_event_head AS h
        JOIN local_life_event AS e ON e.event_id = h.event_id
        WHERE e.kind = 'note'
        ORDER BY h.event_id
        """,
    )
    suspend fun findEventPointers(): List<EventPointerRow>

    @Query(
        """
        SELECT event_id
        FROM local_life_event
        WHERE kind = 'note'
        ORDER BY event_id
        """,
    )
    suspend fun findAllNoteEventIds(): List<String>

    @Query(
        """
        SELECT * FROM local_revision_parent
        WHERE child_revision_id = :revisionId
        ORDER BY parent_revision_id
        """,
    )
    suspend fun findParents(revisionId: String): List<LocalRevisionParentEntity>
}
