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
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity

@Dao
interface NoteMutationDao {
    @Query(
        """
        SELECT * FROM local_capture
        WHERE capture_id = :captureId AND operation_id = :operationId
        """,
    )
    suspend fun findCapture(
        captureId: String,
        operationId: String,
    ): LocalCaptureEntity?

    @Query("SELECT * FROM sync_outbox WHERE operation_id = :operationId")
    suspend fun findOutbox(operationId: String): SyncOutboxEntity?

    @Query(
        """
        UPDATE sync_outbox
        SET command_fingerprint_sha256 = :fingerprint
        WHERE operation_id = :operationId
          AND command_fingerprint_sha256 = ''
        """,
    )
    suspend fun setLegacyCommandFingerprint(
        operationId: String,
        fingerprint: String,
    ): Int

    @Query(
        """
        SELECT e.kind AS event_kind,
               h.current_revision_id AS head_revision_id,
               h.server_current_revision_id,
               r.*,
               o.local_sequence
        FROM local_life_event AS e
        JOIN local_event_head AS h ON h.event_id = e.event_id
        JOIN local_event_revision AS r
          ON r.event_id = h.event_id AND r.revision_id = h.current_revision_id
        LEFT JOIN sync_outbox AS o ON o.revision_id = r.revision_id
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(entity: SyncOutboxEntity): Long

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
        UPDATE local_event_revision
        SET server_received_at = :serverReceivedAt,
            server_sequence = :serverSequence
        WHERE event_id = :eventId
          AND revision_id = :revisionId
          AND capture_id = :captureId
          AND operation_id = :operationId
          AND (
            (server_received_at IS NULL AND server_sequence IS NULL)
            OR (
              server_received_at = :serverReceivedAt
              AND server_sequence = :serverSequence
            )
          )
        """,
    )
    suspend fun attachServerMetadata(
        eventId: String,
        revisionId: String,
        captureId: String,
        operationId: String,
        serverReceivedAt: String,
        serverSequence: Long,
    ): Int

    @Query(
        """
        UPDATE local_capture
        SET persistence_state = 'authenticated_ingress'
        WHERE capture_id = :captureId
          AND operation_id = :operationId
          AND local_owner_id = :localOwnerId
          AND installation_id = :installationId
          AND persistence_state IN ('local_pending', 'authenticated_ingress')
        """,
    )
    suspend fun promoteCaptureToAuthenticatedIngress(
        captureId: String,
        operationId: String,
        localOwnerId: String,
        installationId: String,
    ): Int

    @Query(
        """
        UPDATE local_event_head
        SET server_current_revision_id = :serverCurrentRevisionId,
            server_observed_sequence = :serverObservedSequence,
            updated_at_utc = :updatedAtUtc
        WHERE event_id = :eventId
          AND (
            server_observed_sequence IS NULL
            OR server_observed_sequence < :serverObservedSequence
          )
        """,
    )
    suspend fun recordNewerRemoteHead(
        eventId: String,
        serverCurrentRevisionId: String,
        serverObservedSequence: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        SELECT event_id, current_revision_id, server_current_revision_id,
               server_observed_sequence
        FROM local_event_head
        WHERE event_id = :eventId
        """,
    )
    suspend fun findEventPointer(eventId: String): EventPointerRow?

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM local_capture) AS captures,
          (SELECT COUNT(*) FROM local_life_event) AS events,
          (SELECT COUNT(*) FROM local_event_revision) AS revisions,
          (SELECT COUNT(*) FROM local_revision_parent) AS parents,
          (SELECT COUNT(*) FROM local_event_head) AS heads,
          (SELECT COUNT(*) FROM sync_outbox) AS outbox_operations
        """,
    )
    suspend fun tableCounts(): LocalTableCounts
}
