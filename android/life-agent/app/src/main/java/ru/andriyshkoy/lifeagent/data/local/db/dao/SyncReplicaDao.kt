package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalCaptureEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventHeadEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalEventRevisionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalLifeEventEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalRevisionParentEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStagedChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity

@Dao
interface SyncReplicaDao {
    @Query("SELECT * FROM sync_stream_state WHERE singleton_id = 1")
    suspend fun findStreamState(): SyncStreamStateEntity?

    @Query("SELECT * FROM sync_bootstrap_session WHERE bootstrap_id = :bootstrapId")
    suspend fun findBootstrapSession(bootstrapId: String): SyncBootstrapSessionEntity?

    @Query("SELECT * FROM sync_bootstrap_session WHERE active_slot = 1")
    suspend fun findBootstrapSessionWithActiveSlot(): SyncBootstrapSessionEntity?

    @Query("SELECT * FROM sync_page_receipt WHERE page_id = :pageId")
    suspend fun findPageReceipt(pageId: String): SyncPageReceiptEntity?

    @Query(
        """
        SELECT * FROM sync_page_receipt
        WHERE endpoint_id = :endpointId AND request_identity = :requestIdentity
        """,
    )
    suspend fun findPageReceiptByRequest(
        endpointId: String,
        requestIdentity: String,
    ): SyncPageReceiptEntity?

    @Query(
        """
        SELECT * FROM sync_staged_change
        WHERE bootstrap_id = :bootstrapId
        ORDER BY server_sequence
        """,
    )
    suspend fun findStagedChanges(bootstrapId: String): List<SyncStagedChangeEntity>

    @Query(
        """
        SELECT * FROM sync_staged_change
        WHERE bootstrap_id = :bootstrapId AND page_id = :pageId
        ORDER BY server_sequence
        """,
    )
    suspend fun findStagedPageChanges(
        bootstrapId: String,
        pageId: String,
    ): List<SyncStagedChangeEntity>

    @Query("SELECT * FROM sync_server_change WHERE operation_id = :operationId")
    suspend fun findServerChange(operationId: String): SyncServerChangeEntity?

    @Query("SELECT * FROM sync_server_change WHERE server_sequence = :serverSequence")
    suspend fun findServerChangeBySequence(serverSequence: Long): SyncServerChangeEntity?

    @Query(
        """
        SELECT * FROM sync_server_change
        WHERE event_id = :eventId
        ORDER BY server_sequence DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestServerChangeForEvent(
        eventId: String,
    ): SyncServerChangeEntity?

    @Query("SELECT * FROM sync_server_change WHERE capture_id = :captureId")
    suspend fun findServerChangeByCapture(captureId: String): SyncServerChangeEntity?

    @Query("SELECT * FROM sync_server_change WHERE revision_id = :revisionId")
    suspend fun findServerChangeByRevision(revisionId: String): SyncServerChangeEntity?

    @Query(
        """
        SELECT * FROM sync_server_change
        WHERE server_sequence <= :inclusiveServerSequence
        ORDER BY server_sequence
        """,
    )
    suspend fun findServerChangesThrough(
        inclusiveServerSequence: Long,
    ): List<SyncServerChangeEntity>

    @Query(
        """
        SELECT * FROM sync_server_change
        WHERE server_sequence > :exclusiveServerSequence
          AND server_sequence <= :inclusiveServerSequence
        ORDER BY server_sequence
        """,
    )
    suspend fun findServerChangesInRange(
        exclusiveServerSequence: Long,
        inclusiveServerSequence: Long,
    ): List<SyncServerChangeEntity>

    @Query("SELECT * FROM sync_server_change ORDER BY server_sequence")
    suspend fun findAllServerChanges(): List<SyncServerChangeEntity>

    @Query("SELECT * FROM local_installation WHERE installation_id = :installationId")
    suspend fun findInstallation(installationId: String): LocalInstallationEntity?

    @Query("SELECT * FROM local_installation WHERE server_device_id = :deviceId")
    suspend fun findInstallationByDevice(deviceId: String): LocalInstallationEntity?

    @Query(
        """
        SELECT * FROM local_owner
        WHERE local_owner_id = :localOwnerId
        """,
    )
    suspend fun findOwner(localOwnerId: String): LocalOwnerEntity?

    @Query("SELECT * FROM local_owner WHERE installation_id = :installationId")
    suspend fun findOwnerByInstallation(installationId: String): LocalOwnerEntity?

    @Query("SELECT * FROM local_capture WHERE capture_id = :captureId")
    suspend fun findCapture(captureId: String): LocalCaptureEntity?

    @Query("SELECT * FROM local_capture WHERE operation_id = :operationId")
    suspend fun findCaptureByOperation(operationId: String): LocalCaptureEntity?

    @Query("SELECT * FROM local_life_event WHERE event_id = :eventId")
    suspend fun findEvent(eventId: String): LocalLifeEventEntity?

    @Query("SELECT * FROM local_event_revision WHERE revision_id = :revisionId")
    suspend fun findRevision(revisionId: String): LocalEventRevisionEntity?

    @Query(
        """
        SELECT * FROM local_event_revision
        WHERE event_id = :eventId AND revision_no = 1
        """,
    )
    suspend fun findEventRoot(eventId: String): LocalEventRevisionEntity?

    @Query("SELECT * FROM local_event_revision WHERE operation_id = :operationId")
    suspend fun findRevisionByOperation(operationId: String): LocalEventRevisionEntity?

    @Query("SELECT * FROM local_event_revision WHERE server_sequence = :serverSequence")
    suspend fun findRevisionByServerSequence(
        serverSequence: Long,
    ): LocalEventRevisionEntity?

    @Query("SELECT * FROM local_event_head WHERE event_id = :eventId")
    suspend fun findEventHead(eventId: String): LocalEventHeadEntity?

    @Query(
        """
        SELECT * FROM local_revision_parent
        WHERE child_revision_id = :childRevisionId
        ORDER BY parent_revision_id
        """,
    )
    suspend fun findRevisionParents(
        childRevisionId: String,
    ): List<LocalRevisionParentEntity>

    @Query(
        """
        SELECT EXISTS(
          SELECT 1 FROM sync_outbox
          WHERE event_id = :eventId
            AND revision_id = :revisionId
            AND state <> 'acked'
        )
        """,
    )
    suspend fun hasNonAckedOutboxRevision(
        eventId: String,
        revisionId: String,
    ): Boolean

    @Query(
        """
        SELECT EXISTS(
          SELECT 1 FROM sync_http_request
          WHERE endpoint_id = :endpointId
            AND request_identity = :requestIdentity
            AND state = 'terminal'
        )
        """,
    )
    suspend fun terminalRequestExists(
        endpointId: String,
        requestIdentity: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStreamState(entity: SyncStreamStateEntity)

    @Query(
        """
        DELETE FROM sync_stream_state
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
        """,
    )
    suspend fun deleteExactStream(
        credentialEpochId: String,
        deviceId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBootstrapSession(entity: SyncBootstrapSessionEntity)

    @Query(
        """
        UPDATE sync_bootstrap_session
        SET state = 'superseded',
            active_slot = NULL,
            updated_at_utc = :updatedAtUtc
        WHERE active_slot = 1
          AND state = 'staging'
          AND bootstrap_id = :bootstrapId
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
        """,
    )
    suspend fun supersedeActiveBootstrapSession(
        bootstrapId: String,
        credentialEpochId: String,
        deviceId: String,
        updatedAtUtc: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPageReceipt(entity: SyncPageReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStagedChanges(entities: List<SyncStagedChangeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertServerChange(entity: SyncServerChangeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallation(entity: LocalInstallationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOwner(entity: LocalOwnerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCapture(entity: LocalCaptureEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(entity: LocalLifeEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: LocalEventRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParent(entity: LocalRevisionParentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEventHead(entity: LocalEventHeadEntity)

    @Query(
        """
        UPDATE sync_bootstrap_session
        SET snapshot_id = :snapshotId,
            next_page_cursor = :nextPageCursor,
            candidate_incremental_cursor = :incrementalCursor,
            next_page_index = next_page_index + 1,
            last_staged_server_sequence =
                COALESCE(:lastServerSequence, last_staged_server_sequence),
            staged_page_count = staged_page_count + 1,
            staged_body_bytes = staged_body_bytes + :responseBodyBytes,
            updated_at_utc = :updatedAtUtc
        WHERE bootstrap_id = :bootstrapId
          AND state = 'staging'
          AND active_slot = 1
          AND next_page_index = :expectedPageIndex
          AND next_page_cursor IS :expectedPageCursor
          AND (snapshot_id IS NULL OR snapshot_id = :snapshotId)
          AND (
            candidate_incremental_cursor IS NULL
            OR candidate_incremental_cursor = :incrementalCursor
          )
          AND (
            (:firstServerSequence IS NULL AND :lastServerSequence IS NULL)
            OR (
              :firstServerSequence IS NOT NULL
              AND :lastServerSequence IS NOT NULL
              AND :firstServerSequence <= :lastServerSequence
              AND (
                last_staged_server_sequence IS NULL
                OR :firstServerSequence > last_staged_server_sequence
              )
            )
          )
        """,
    )
    suspend fun advanceBootstrapSession(
        bootstrapId: String,
        expectedPageIndex: Int,
        expectedPageCursor: String?,
        snapshotId: String,
        nextPageCursor: String?,
        incrementalCursor: String,
        firstServerSequence: Long?,
        lastServerSequence: Long?,
        responseBodyBytes: Long,
        updatedAtUtc: String,
    ): Int

    @Transaction
    suspend fun stageBootstrapPage(
        receipt: SyncPageReceiptEntity,
        changes: List<SyncStagedChangeEntity>,
        responseBodyBytes: Long,
    ) {
        val bootstrapId = checkNotNull(receipt.bootstrapId)
        val snapshotId = checkNotNull(receipt.snapshotId)
        val incrementalCursor = checkNotNull(receipt.incrementalCursor)
        require(receipt.state == "staged")
        require(receipt.endpointId == "sync_bootstrap")
        require(responseBodyBytes >= 0)
        require(terminalRequestExists(receipt.endpointId, receipt.requestIdentity)) {
            "Bootstrap page may only be staged from a terminal verified request"
        }
        require(receipt.changeCount == changes.size)
        require(
            changes.all {
                it.bootstrapId == bootstrapId && it.pageId == receipt.pageId
            },
        )
        val sequences = changes.map { it.serverSequence }
        require(sequences.zipWithNext().all { (previous, next) -> previous < next }) {
            "Bootstrap page sequences must be strictly increasing"
        }
        if (changes.isEmpty()) {
            require(receipt.firstServerSequence == null)
            require(receipt.lastServerSequence == null)
        } else {
            require(receipt.firstServerSequence == sequences.first())
            require(receipt.lastServerSequence == sequences.last())
        }
        insertPageReceipt(receipt)
        if (changes.isNotEmpty()) {
            insertStagedChanges(changes)
        }
        check(
            advanceBootstrapSession(
                bootstrapId = bootstrapId,
                expectedPageIndex = receipt.pageIndex,
                expectedPageCursor = receipt.fromCursor,
                snapshotId = snapshotId,
                nextPageCursor = receipt.nextCursor,
                incrementalCursor = incrementalCursor,
                firstServerSequence = receipt.firstServerSequence,
                lastServerSequence = receipt.lastServerSequence,
                responseBodyBytes = responseBodyBytes,
                updatedAtUtc = receipt.receivedAtUtc,
            ) == 1,
        ) {
            "Bootstrap page does not continue the active staging session"
        }
    }

    @Query(
        """
        UPDATE sync_stream_state
        SET applied_cursor = :nextCursor,
            last_applied_server_sequence = :lastServerSequence,
            phase = :nextPhase,
            updated_at_utc = :updatedAtUtc
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND applied_cursor IS :expectedCursor
          AND last_applied_server_sequence = :expectedServerSequence
          AND bootstrap_required = 0
          AND phase IN ('incremental', 'pulling')
          AND integrity_error_code IS NULL
        """,
    )
    suspend fun compareAndAdvanceCursor(
        credentialEpochId: String,
        deviceId: String,
        expectedCursor: String?,
        expectedServerSequence: Long,
        nextCursor: String,
        lastServerSequence: Long,
        nextPhase: String,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_stream_state
        SET phase = 'incremental',
            bootstrap_required = 0,
            applied_cursor = :incrementalCursor,
            last_applied_server_sequence = :lastServerSequence,
            high_watermark_hint = NULL,
            updated_at_utc = :updatedAtUtc
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND integrity_error_code IS NULL
        """,
    )
    suspend fun promoteBootstrapCursor(
        credentialEpochId: String,
        deviceId: String,
        incrementalCursor: String,
        lastServerSequence: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_bootstrap_session
        SET state = 'complete',
            active_slot = NULL,
            updated_at_utc = :updatedAtUtc
        WHERE bootstrap_id = :bootstrapId
          AND state = 'staging'
          AND active_slot = 1
          AND next_page_cursor IS NULL
          AND candidate_incremental_cursor = :incrementalCursor
        """,
    )
    suspend fun markBootstrapComplete(
        bootstrapId: String,
        incrementalCursor: String,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_page_receipt
        SET state = 'applied',
            applied_at_utc = :appliedAtUtc
        WHERE bootstrap_id = :bootstrapId
          AND state = 'staged'
        """,
    )
    suspend fun markBootstrapReceiptsApplied(
        bootstrapId: String,
        appliedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_stream_state
        SET phase = 'bootstrap_required',
            bootstrap_required = 1,
            updated_at_utc = :updatedAtUtc
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND integrity_error_code IS NULL
        """,
    )
    suspend fun requireBootstrap(
        credentialEpochId: String,
        deviceId: String,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_stream_state
        SET phase = 'integrity_halted',
            integrity_error_code = :errorCode,
            updated_at_utc = :updatedAtUtc
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND (
            integrity_error_code IS NULL
            OR integrity_error_code = :errorCode
          )
        """,
    )
    suspend fun markIntegrityHalted(
        credentialEpochId: String,
        deviceId: String,
        errorCode: String,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE local_event_head
        SET current_revision_id =
              CASE
                WHEN EXISTS(
                  SELECT 1
                  FROM sync_outbox AS o
                  WHERE o.revision_id = local_event_head.current_revision_id
                    AND o.state <> 'acked'
                )
                THEN current_revision_id
                ELSE :serverCurrentRevisionId
              END,
            server_current_revision_id = :serverCurrentRevisionId,
            server_observed_sequence = :serverObservedSequence,
            updated_at_utc = :updatedAtUtc
        WHERE event_id = :eventId
          AND (
            server_observed_sequence IS NULL
            OR server_observed_sequence < :serverObservedSequence
            OR (
              server_observed_sequence = :serverObservedSequence
              AND server_current_revision_id = :serverCurrentRevisionId
            )
          )
        """,
    )
    suspend fun installObservedServerHead(
        eventId: String,
        serverCurrentRevisionId: String,
        serverObservedSequence: Long,
        updatedAtUtc: String,
    ): Int

    @Query("SELECT COUNT(*) FROM sync_staged_change WHERE bootstrap_id = :bootstrapId")
    suspend fun countStagedBootstrapChanges(bootstrapId: String): Int

    @Query("DELETE FROM sync_staged_change WHERE bootstrap_id = :bootstrapId")
    suspend fun deleteStagedBootstrapChanges(bootstrapId: String): Int

    @Query(
        """
        DELETE FROM sync_page_receipt
        WHERE bootstrap_id = :bootstrapId AND state = 'staged'
        """,
    )
    suspend fun deleteStagedBootstrapReceipts(bootstrapId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_page_receipt
        WHERE bootstrap_id = :bootstrapId AND state = 'staged'
        """,
    )
    suspend fun countStagedBootstrapReceipts(bootstrapId: String): Int

    @Query(
        """
        UPDATE sync_bootstrap_session
        SET state = 'expired',
            active_slot = NULL,
            updated_at_utc = :updatedAtUtc
        WHERE bootstrap_id = :bootstrapId
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state = 'staging'
          AND active_slot = 1
        """,
    )
    suspend fun markBootstrapExpired(
        bootstrapId: String,
        credentialEpochId: String,
        deviceId: String,
        updatedAtUtc: String,
    ): Int
}
