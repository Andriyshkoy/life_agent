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

    @Query(
        """
        SELECT COUNT(*) FROM sync_outbox
        WHERE state IN ('pending', 'batched', 'waiting_parent')
        """,
    )
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE state = 'pending' AND wire_state = 'needs_materialization'
        ORDER BY local_sequence
        LIMIT :limit
        """,
    )
    suspend fun awaitingWireMaterialization(limit: Int): List<SyncOutboxEntity>

    @Query(
        """
        UPDATE sync_outbox
        SET wire_state = 'ready',
            wire_protocol_version = :protocolVersion,
            wire_operation_material_jcs = :materialJcs,
            wire_operation_content_sha256 = :contentSha256,
            wire_materialized_at_utc = :materializedAtUtc
        WHERE local_sequence = :localSequence
          AND operation_id = :operationId
          AND state = 'pending'
          AND wire_state = 'needs_materialization'
          AND wire_operation_material_jcs IS NULL
          AND wire_operation_content_sha256 IS NULL
        """,
    )
    suspend fun installWireMaterial(
        localSequence: Long,
        operationId: String,
        protocolVersion: String,
        materialJcs: ByteArray,
        contentSha256: String,
        materializedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_outbox
        SET state = :state,
            active_batch_id = NULL,
            last_result_batch_id = :batchId,
            last_result_code = :resultCode,
            last_result_retryable = :retryable,
            last_result_current_revision_id = :currentRevisionId,
            last_result_details_jcs = :detailsJcs,
            server_sequence = :serverSequence,
            acked_at_utc = :ackedAtUtc,
            last_error_code = :errorCode
        WHERE operation_id = :operationId
          AND wire_operation_content_sha256 = :wireContentSha256
          AND state = 'batched'
          AND active_batch_id = :batchId
          AND (last_result_code IS NULL OR last_result_retryable = 1)
          AND EXISTS (
            SELECT 1
            FROM sync_push_batch_item AS i
            JOIN sync_push_batch AS b ON b.batch_id = i.batch_id
            JOIN sync_http_request AS q
              ON q.endpoint_id = b.endpoint_id
             AND q.request_identity = b.request_identity
            WHERE i.batch_id = :batchId
              AND i.operation_id = sync_outbox.operation_id
              AND i.wire_operation_content_sha256 =
                  sync_outbox.wire_operation_content_sha256
              AND q.state = 'terminal'
          )
        """,
    )
    suspend fun recordResult(
        operationId: String,
        wireContentSha256: String,
        state: String,
        batchId: String,
        resultCode: String,
        retryable: Boolean?,
        currentRevisionId: String?,
        detailsJcs: ByteArray?,
        serverSequence: Long?,
        ackedAtUtc: String?,
        errorCode: String?,
    ): Int

    /**
     * Correlates item errors by the durable physical ordinal. The protocol may
     * omit operation identifiers from malformed-item errors; the locally
     * persisted batch membership remains authoritative.
     */
    @Query(
        """
        UPDATE sync_outbox
        SET state = :state,
            active_batch_id = NULL,
            last_result_batch_id = :batchId,
            last_result_code = :resultCode,
            last_result_retryable = :retryable,
            last_result_current_revision_id = NULL,
            last_result_details_jcs = :detailsJcs,
            server_sequence = NULL,
            acked_at_utc = NULL,
            last_error_code = :errorCode
        WHERE state = 'batched'
          AND active_batch_id = :batchId
          AND (last_result_code IS NULL OR last_result_retryable = 1)
          AND EXISTS (
            SELECT 1
            FROM sync_push_batch_item AS i
            JOIN sync_push_batch AS b ON b.batch_id = i.batch_id
            JOIN sync_http_request AS q
              ON q.endpoint_id = b.endpoint_id
             AND q.request_identity = b.request_identity
            WHERE i.batch_id = :batchId
              AND i.ordinal = :ordinal
              AND i.local_sequence = sync_outbox.local_sequence
              AND i.operation_id = sync_outbox.operation_id
              AND i.wire_operation_content_sha256 =
                  sync_outbox.wire_operation_content_sha256
              AND q.state = 'terminal'
          )
        """,
    )
    suspend fun recordErrorByBatchOrdinal(
        batchId: String,
        ordinal: Int,
        state: String,
        resultCode: String,
        retryable: Boolean,
        detailsJcs: ByteArray?,
        errorCode: String,
    ): Int
}
