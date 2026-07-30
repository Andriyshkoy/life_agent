package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity

@Dao
interface SyncTransportDao {
    @Query(
        """
        SELECT * FROM sync_http_request
        WHERE endpoint_id = :endpointId AND request_identity = :requestIdentity
        """,
    )
    suspend fun findRequest(
        endpointId: String,
        requestIdentity: String,
    ): SyncHttpRequestEntity?

    @Query("SELECT * FROM sync_push_batch WHERE batch_id = :batchId")
    suspend fun findBatch(batchId: String): SyncPushBatchEntity?

    @Query(
        """
        SELECT * FROM sync_push_batch_item
        WHERE batch_id = :batchId
        ORDER BY ordinal
        """,
    )
    suspend fun findBatchItems(batchId: String): List<SyncPushBatchItemEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(entity: SyncHttpRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(entity: SyncPushBatchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatchItems(entities: List<SyncPushBatchItemEntity>)

    @Query(
        """
        UPDATE sync_outbox
        SET state = 'batched',
            active_batch_id = :batchId
        WHERE local_sequence = :localSequence
          AND operation_id = :operationId
          AND wire_operation_content_sha256 = :wireContentSha256
          AND wire_state = 'ready'
          AND active_batch_id IS NULL
          AND (
            state = 'pending'
            OR (
                state = 'waiting_parent'
                AND last_result_code = 'missing_parent'
                AND last_result_retryable = 1
            )
          )
        """,
    )
    suspend fun claimOutboxForBatch(
        batchId: String,
        localSequence: Long,
        operationId: String,
        wireContentSha256: String,
    ): Int

    @Transaction
    suspend fun insertPushRequest(
        request: SyncHttpRequestEntity,
        batch: SyncPushBatchEntity,
        items: List<SyncPushBatchItemEntity>,
    ) {
        require(items.size in 1..100) {
            "A durable push batch must contain between 1 and 100 operations"
        }
        require(request.endpointId == "sync_push")
        require(request.requestIdentity == batch.batchId)
        require(request.idempotencyKey == batch.batchId)
        require(batch.endpointId == request.endpointId)
        require(batch.requestIdentity == request.requestIdentity)
        require(batch.operationCount == items.size)
        require(items.map { it.ordinal } == items.indices.toList())
        require(items.all { it.batchId == batch.batchId })
        require(
            items.zipWithNext().all { (previous, next) ->
                previous.localSequence < next.localSequence
            },
        ) {
            "Push client sequences must be strictly increasing in ordinal order"
        }
        require(items.map { it.operationId }.distinct().size == items.size)
        insertRequest(request)
        insertBatch(batch)
        items.forEach { item ->
            check(
                claimOutboxForBatch(
                    batchId = batch.batchId,
                    localSequence = item.localSequence,
                    operationId = item.operationId,
                    wireContentSha256 = item.wireOperationContentSha256,
                ) == 1,
            ) {
                "Outbox operation is not eligible for this durable batch"
            }
        }
        insertBatchItems(items)
    }

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'sending',
            attempt_count = attempt_count + 1,
            last_attempt_at_epoch_ms = :attemptedAtEpochMs,
            lease_expires_at_epoch_ms = :leaseExpiresAtEpochMs,
            active_attempt_id = :attemptId,
            access_generation_used = :accessGenerationUsed,
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id = :endpointId
          AND endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND request_identity = :requestIdentity
          AND credential_epoch_id = :credentialEpochId
          AND EXISTS (
            SELECT 1
            FROM sync_stream_state
            WHERE singleton_id = 1
              AND credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND device_id = sync_http_request.device_id
              AND phase != 'integrity_halted'
              AND integrity_error_code IS NULL
          )
          AND length(trim(:attemptId)) > 0
          AND :accessGenerationUsed > 0
          AND (
            access_generation_used IS NULL
            OR access_generation_used <= :accessGenerationUsed
          )
          AND (
            state IN ('ready', 'retry_wait')
            OR (
                state = 'sending'
                AND lease_expires_at_epoch_ms IS NOT NULL
                AND lease_expires_at_epoch_ms <= :attemptedAtEpochMs
            )
          )
          AND attempt_count < attempt_budget
          AND (
            next_attempt_at_epoch_ms IS NULL
            OR next_attempt_at_epoch_ms <= :attemptedAtEpochMs
          )
          AND :attemptedAtEpochMs < deadline_at_epoch_ms
          AND :leaseExpiresAtEpochMs > :attemptedAtEpochMs
          AND :leaseExpiresAtEpochMs <= deadline_at_epoch_ms
        """,
    )
    suspend fun claimAttempt(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        accessGenerationUsed: Long,
        attemptId: String,
        attemptedAtEpochMs: Long,
        leaseExpiresAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'sending',
            attempt_count = attempt_count + 1,
            last_attempt_at_epoch_ms = :attemptedAtEpochMs,
            lease_expires_at_epoch_ms = :leaseExpiresAtEpochMs,
            active_attempt_id = :attemptId,
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id = 'auth_revoke'
          AND request_identity = :requestIdentity
          AND body_storage_kind = 'keystore_aead'
          AND raw_request_body IS NULL
          AND length(trim(:attemptId)) > 0
          AND (
            state IN ('ready', 'retry_wait')
            OR (
                state = 'sending'
                AND lease_expires_at_epoch_ms IS NOT NULL
                AND lease_expires_at_epoch_ms <= :attemptedAtEpochMs
            )
          )
          AND attempt_count < attempt_budget
          AND (
            next_attempt_at_epoch_ms IS NULL
            OR next_attempt_at_epoch_ms <= :attemptedAtEpochMs
          )
          AND :attemptedAtEpochMs < deadline_at_epoch_ms
          AND :leaseExpiresAtEpochMs > :attemptedAtEpochMs
          AND :leaseExpiresAtEpochMs <= deadline_at_epoch_ms
        """,
    )
    suspend fun claimRevokeAttempt(
        requestIdentity: String,
        attemptId: String,
        attemptedAtEpochMs: Long,
        leaseExpiresAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'integrity_failure',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :quarantinedAtUtc,
            terminal_error_code = :failureCode,
            updated_at_utc = :quarantinedAtUtc
        WHERE endpoint_id = 'auth_revoke'
          AND request_identity = :requestIdentity
          AND body_storage_kind = 'keystore_aead'
          AND sealed_body_key_alias = :expectedKeyAlias
          AND sealed_body_key_generation = :expectedKeyGeneration
          AND sealed_body_aad_version = :expectedAadVersion
          AND state IN ('ready', 'retry_wait', 'sending')
          AND active_attempt_id = :expectedAttemptId
        """,
    )
    suspend fun quarantineSealedRevokeRequestRow(
        requestIdentity: String,
        expectedKeyAlias: String,
        expectedKeyGeneration: Int,
        expectedAadVersion: Int,
        expectedAttemptId: String,
        quarantinedAtUtc: String,
        failureCode: String,
    ): Int

    @Transaction
    suspend fun quarantineSealedRevokeRequest(
        requestIdentity: String,
        expectedKeyAlias: String,
        expectedKeyGeneration: Int,
        expectedAadVersion: Int,
        expectedAttemptId: String,
        quarantinedAtUtc: String,
        failureCode: String,
    ) {
        require(
            failureCode in setOf(
                "sealed_body_key_unavailable",
                "sealed_body_authentication_failed",
                "sealed_body_hmac_mismatch",
                "sealed_body_metadata_invalid",
            ),
        )
        check(
            quarantineSealedRevokeRequestRow(
                requestIdentity = requestIdentity,
                expectedKeyAlias = expectedKeyAlias,
                expectedKeyGeneration = expectedKeyGeneration,
                expectedAadVersion = expectedAadVersion,
                expectedAttemptId = expectedAttemptId,
                quarantinedAtUtc = quarantinedAtUtc,
                failureCode = failureCode,
            ) == 1,
        ) {
            "Sealed revoke quarantine lost its request CAS"
        }
    }

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal',
            terminal_http_status = :httpStatus,
            exact_response_body = :exactResponseBody,
            response_sha256 = :responseSha256,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = :terminalErrorCode,
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id = :endpointId
          AND request_identity = :requestIdentity
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND exact_response_body IS NULL
        """,
    )
    suspend fun storeTerminalResponse(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
        httpStatus: Int,
        exactResponseBody: ByteArray,
        responseSha256: String,
        terminalAtUtc: String,
        terminalErrorCode: String?,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = 'bootstrap_superseded',
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id = 'sync_bootstrap'
          AND request_identity = :requestIdentity
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND terminal_http_status IS NULL
          AND exact_response_body IS NULL
          AND response_sha256 IS NULL
          AND terminal_at_utc IS NULL
        """,
    )
    suspend fun markSupersededBootstrapRequest(
        requestIdentity: String,
        credentialEpochId: String,
        deviceId: String,
        expectedAttemptId: String,
        terminalAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'retry_wait',
            next_attempt_at_epoch_ms = :nextAttemptAtEpochMs,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_error_code = :lastErrorCode,
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id = :endpointId
          AND request_identity = :requestIdentity
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND attempt_count < attempt_budget
          AND :nextAttemptAtEpochMs <= deadline_at_epoch_ms
        """,
    )
    suspend fun scheduleRetry(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
        nextAttemptAtEpochMs: Long,
        lastErrorCode: String?,
        updatedAtUtc: String,
    ): Int

    /**
     * Persists the one allowed credential recovery for a request that received
     * a trusted 401 using the currently installed access generation.
     *
     * Multiple requests may wait for the same family refresh, while
     * SyncAuthDao owns the single-flight family CAS.
     */
    @Query(
        """
        UPDATE sync_http_request
        SET state = 'waiting_refresh',
            refresh_attempted = 1,
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_error_code = 'credential_recovery_pending',
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id = :endpointId
          AND endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND request_identity = :requestIdentity
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND credential_epoch_id = :credentialEpochId
          AND access_generation_used = :failedAccessGeneration
          AND refresh_attempted = 0
          AND original_retry_count = 0
          AND attempt_count < attempt_budget
          AND :nowEpochMs < deadline_at_epoch_ms
        """,
    )
    suspend fun waitForCredentialRefresh(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        nowEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    /**
     * A request sent with a stale access generation can use the already
     * installed successor without initiating another refresh.
     */
    @Query(
        """
        UPDATE sync_http_request
        SET state = 'retry_wait',
            access_generation_used = :installedGeneration,
            original_retry_count = original_retry_count + 1,
            next_attempt_at_epoch_ms = :nextAttemptAtEpochMs,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_error_code = 'credential_generation_advanced',
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id = :endpointId
          AND endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND request_identity = :requestIdentity
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND credential_epoch_id = :credentialEpochId
          AND access_generation_used = :failedAccessGeneration
          AND :installedGeneration > :failedAccessGeneration
          AND original_retry_count = 0
          AND attempt_count < attempt_budget
          AND :nextAttemptAtEpochMs < deadline_at_epoch_ms
        """,
    )
    suspend fun scheduleExactRetryWithInstalledGeneration(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        installedGeneration: Long,
        expectedAttemptId: String,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    /**
     * Releases a request waiting on the single family refresh. The exact body
     * and request identity remain immutable; only Authorization generation
     * changes for the one allowed original-request retry.
     */
    @Query(
        """
        UPDATE sync_http_request
        SET state = 'retry_wait',
            access_generation_used = :successorGeneration,
            original_retry_count = 1,
            next_attempt_at_epoch_ms = :nextAttemptAtEpochMs,
            terminal_error_code = 'credential_recovery_complete',
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id = :endpointId
          AND endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND request_identity = :requestIdentity
          AND state = 'waiting_refresh'
          AND credential_epoch_id = :credentialEpochId
          AND access_generation_used = :failedAccessGeneration
          AND refresh_attempted = 1
          AND original_retry_count = 0
          AND :successorGeneration = :failedAccessGeneration + 1
          AND attempt_count < attempt_budget
          AND :nextAttemptAtEpochMs < deadline_at_epoch_ms
        """,
    )
    suspend fun scheduleExactRetryAfterRefresh(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        successorGeneration: Long,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = 'credential_recovery_exhausted',
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id = :endpointId
          AND endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND request_identity = :requestIdentity
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND credential_epoch_id = :credentialEpochId
          AND access_generation_used = :failedAccessGeneration
          AND original_retry_count = 1
        """,
    )
    suspend fun markCredentialRecoveryExhausted(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = 'retry_budget_exhausted',
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id = :endpointId
          AND request_identity = :requestIdentity
          AND state IN ('ready', 'retry_wait', 'sending')
          AND (
            attempt_count >= attempt_budget
            OR deadline_at_epoch_ms <= :nowEpochMs
          )
          AND (
            state != 'sending'
            OR lease_expires_at_epoch_ms IS NULL
            OR lease_expires_at_epoch_ms <= :nowEpochMs
          )
        """,
    )
    suspend fun markRetryBudgetExhausted(
        endpointId: String,
        requestIdentity: String,
        nowEpochMs: Long,
        terminalAtUtc: String,
    ): Int
}
