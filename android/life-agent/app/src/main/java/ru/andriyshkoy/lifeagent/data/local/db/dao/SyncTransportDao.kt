package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity

data class SyncRequestIntegrityRecoverySnapshot(
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "access_generation_used_integer_value")
    val accessGenerationUsed: Long?,
    @ColumnInfo(name = "access_generation_used_storage_class")
    val accessGenerationUsedStorageClass: String,
    @ColumnInfo(name = "hmac_key_generation_integer_value")
    val hmacKeyGeneration: Long?,
    @ColumnInfo(name = "hmac_key_generation_storage_class")
    val hmacKeyGenerationStorageClass: String,
    @ColumnInfo(name = "hmac_key_generation_quoted")
    val hmacKeyGenerationQuoted: String,
    @ColumnInfo(name = "raw_body_hmac_storage_class")
    val rawBodyHmacStorageClass: String,
    @ColumnInfo(name = "raw_body_hmac_hex")
    val rawBodyHmacHex: String,
    @ColumnInfo(name = "raw_body_hmac_octet_count")
    val rawBodyHmacOctetCount: Int,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "attempt_count_integer_value")
    val attemptCount: Long?,
    @ColumnInfo(name = "attempt_count_storage_class")
    val attemptCountStorageClass: String,
    @ColumnInfo(name = "attempt_count_quoted")
    val attemptCountQuoted: String,
    @ColumnInfo(name = "active_attempt_id")
    val activeAttemptId: String?,
    @ColumnInfo(name = "lease_expires_at_epoch_ms")
    val leaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "updated_at_utc")
    val updatedAtUtc: String,
) {
    val hasCanonicalHmacStorage: Boolean
        get() = rawBodyHmacStorageClass == "blob" &&
            rawBodyHmacOctetCount == REQUEST_BODY_HMAC_OCTETS

    val hasCanonicalHmacKeyGeneration: Boolean
        get() = hmacKeyGenerationStorageClass == "integer" &&
            hmacKeyGeneration == CURRENT_REQUEST_BODY_HMAC_GENERATION

    val hasCanonicalAccessGeneration: Boolean
        get() = accessGenerationUsedStorageClass == "integer" &&
            accessGenerationUsed != null &&
            accessGenerationUsed > 0

    val hasCanonicalAttemptCount: Boolean
        get() = attemptCountStorageClass == "integer" &&
            attemptCount != null &&
            attemptCount in 0..Int.MAX_VALUE.toLong()

    override fun toString(): String =
        "SyncRequestIntegrityRecoverySnapshot(endpoint=$endpointId,redacted=true)"

    private companion object {
        const val REQUEST_BODY_HMAC_OCTETS = 32
        const val CURRENT_REQUEST_BODY_HMAC_GENERATION = 1L
    }
}

data class SyncRequestKey(
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
) {
    override fun toString(): String = "SyncRequestKey(endpoint=$endpointId,redacted=true)"
}

/**
 * Body-blind dispatch discovery result.
 *
 * The projection intentionally contains only route, authority, and attempt
 * scheduling metadata. Durable request bytes remain owned by the protected
 * request boundary and are authenticated only when that boundary claims the
 * candidate.
 */
data class SyncRunnableRequestCandidate(
    @ColumnInfo(name = "endpoint_id")
    val endpointId: String,
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "access_generation_used")
    val accessGenerationUsed: Long,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "attempt_budget")
    val attemptBudget: Int,
    @ColumnInfo(name = "deadline_at_epoch_ms")
    val deadlineAtEpochMs: Long,
    @ColumnInfo(name = "next_attempt_at_epoch_ms")
    val nextAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long?,
    @ColumnInfo(name = "lease_expires_at_epoch_ms")
    val leaseExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "active_attempt_id")
    val activeAttemptId: String?,
    @ColumnInfo(name = "scheduled_at_epoch_ms")
    val scheduledAtEpochMs: Long,
    @ColumnInfo(name = "route_priority")
    val routePriority: Int,
) {
    override fun toString(): String =
        "SyncRunnableRequestCandidate(endpoint=$endpointId,state=$state,redacted=true)"
}

/**
 * Body-blind response-routing metadata projected without trusting SQLite's
 * dynamic storage classes.
 *
 * Every nullable value is produced by a guarded CASE expression. Callers must
 * require both [hasRoomSafeStorageClasses] and [hasRoomSafeEntityShape] before
 * hydrating the corresponding [SyncHttpRequestEntity] with SELECT *.
 */
data class SyncResponseRouteSnapshot(
    @ColumnInfo(name = "endpoint_id_safe")
    val endpointId: String?,
    @ColumnInfo(name = "request_identity_safe")
    val requestIdentity: String?,
    @ColumnInfo(name = "protocol_version_safe")
    val protocolVersion: String?,
    @ColumnInfo(name = "credential_epoch_id_safe")
    val credentialEpochId: String?,
    @ColumnInfo(name = "device_id_safe")
    val deviceId: String?,
    @ColumnInfo(name = "idempotency_key_safe")
    val idempotencyKey: String?,
    @ColumnInfo(name = "body_storage_kind_safe")
    val bodyStorageKind: String?,
    @ColumnInfo(name = "state_safe")
    val state: String?,
    @ColumnInfo(name = "active_attempt_id_safe")
    val activeAttemptId: String?,
    @ColumnInfo(name = "access_generation_used_safe")
    val accessGenerationUsed: Long?,
    @ColumnInfo(name = "attempt_count_safe")
    val attemptCount: Long?,
    @ColumnInfo(name = "has_room_safe_required_text_storage")
    val hasRoomSafeRequiredTextStorage: Boolean,
    @ColumnInfo(name = "has_room_safe_nullable_text_storage")
    val hasRoomSafeNullableTextStorage: Boolean,
    @ColumnInfo(name = "has_room_safe_required_integer_storage")
    val hasRoomSafeRequiredIntegerStorage: Boolean,
    @ColumnInfo(name = "has_room_safe_nullable_integer_storage")
    val hasRoomSafeNullableIntegerStorage: Boolean,
    @ColumnInfo(name = "has_room_safe_required_blob_storage")
    val hasRoomSafeRequiredBlobStorage: Boolean,
    @ColumnInfo(name = "has_room_safe_nullable_blob_storage")
    val hasRoomSafeNullableBlobStorage: Boolean,
    @ColumnInfo(name = "has_room_safe_entity_shape")
    val hasRoomSafeEntityShape: Boolean,
    @ColumnInfo(name = "has_fresh_response_metadata_shape")
    val hasFreshResponseMetadataShape: Boolean,
) {
    val hasRoomSafeStorageClasses: Boolean
        get() = hasRoomSafeRequiredTextStorage &&
            hasRoomSafeNullableTextStorage &&
            hasRoomSafeRequiredIntegerStorage &&
            hasRoomSafeNullableIntegerStorage &&
            hasRoomSafeRequiredBlobStorage &&
            hasRoomSafeNullableBlobStorage

    val canHydrateRequestEntity: Boolean
        get() = hasRoomSafeStorageClasses && hasRoomSafeEntityShape

    override fun toString(): String =
        "SyncResponseRouteSnapshot(endpoint=$endpointId,state=$state,redacted=true)"
}

@Dao
interface SyncTransportDao {
    @Query(
        """
        SELECT
          CASE WHEN typeof(endpoint_id) = 'text'
            THEN endpoint_id ELSE NULL END AS endpoint_id_safe,
          CASE WHEN typeof(request_identity) = 'text'
            THEN request_identity ELSE NULL END AS request_identity_safe,
          CASE WHEN typeof(protocol_version) = 'text'
            THEN protocol_version ELSE NULL END AS protocol_version_safe,
          CASE WHEN typeof(credential_epoch_id) = 'text'
            THEN credential_epoch_id ELSE NULL END AS credential_epoch_id_safe,
          CASE WHEN typeof(device_id) = 'text'
            THEN device_id ELSE NULL END AS device_id_safe,
          CASE WHEN typeof(idempotency_key) = 'text'
            THEN idempotency_key ELSE NULL END AS idempotency_key_safe,
          CASE WHEN typeof(body_storage_kind) = 'text'
            THEN body_storage_kind ELSE NULL END AS body_storage_kind_safe,
          CASE WHEN typeof(state) = 'text'
            THEN state ELSE NULL END AS state_safe,
          CASE WHEN typeof(active_attempt_id) = 'text'
            THEN active_attempt_id ELSE NULL END AS active_attempt_id_safe,
          CASE WHEN typeof(access_generation_used) = 'integer'
            THEN access_generation_used ELSE NULL END AS access_generation_used_safe,
          CASE WHEN typeof(attempt_count) = 'integer'
            THEN attempt_count ELSE NULL END AS attempt_count_safe,
          CASE WHEN
            typeof(endpoint_id) = 'text'
            AND typeof(request_identity) = 'text'
            AND typeof(protocol_version) = 'text'
            AND typeof(credential_epoch_id) = 'text'
            AND typeof(device_id) = 'text'
            AND typeof(body_storage_kind) = 'text'
            AND typeof(state) = 'text'
            AND typeof(created_at_utc) = 'text'
            AND typeof(updated_at_utc) = 'text'
            THEN 1 ELSE 0
          END AS has_room_safe_required_text_storage,
          CASE WHEN
            typeof(idempotency_key) IN ('null', 'text')
            AND typeof(sealed_body_key_alias) IN ('null', 'text')
            AND typeof(active_attempt_id) IN ('null', 'text')
            AND typeof(response_sha256) IN ('null', 'text')
            AND typeof(terminal_at_utc) IN ('null', 'text')
            AND typeof(terminal_error_code) IN ('null', 'text')
            THEN 1 ELSE 0
          END AS has_room_safe_nullable_text_storage,
          CASE WHEN
            typeof(request_body_octet_count) = 'integer'
            AND typeof(hmac_key_generation) = 'integer'
            AND typeof(attempt_count) = 'integer'
            AND typeof(attempt_budget) = 'integer'
            AND typeof(deadline_at_epoch_ms) = 'integer'
            AND typeof(refresh_attempted) = 'integer'
            AND typeof(original_retry_count) = 'integer'
            THEN 1 ELSE 0
          END AS has_room_safe_required_integer_storage,
          CASE WHEN
            typeof(sealed_body_key_generation) IN ('null', 'integer')
            AND typeof(sealed_body_aad_version) IN ('null', 'integer')
            AND typeof(next_attempt_at_epoch_ms) IN ('null', 'integer')
            AND typeof(last_attempt_at_epoch_ms) IN ('null', 'integer')
            AND typeof(lease_expires_at_epoch_ms) IN ('null', 'integer')
            AND typeof(access_generation_used) IN ('null', 'integer')
            AND typeof(terminal_http_status) IN ('null', 'integer')
            THEN 1 ELSE 0
          END AS has_room_safe_nullable_integer_storage,
          CASE WHEN typeof(raw_body_hmac) = 'blob'
            THEN 1 ELSE 0
          END AS has_room_safe_required_blob_storage,
          CASE WHEN
            typeof(raw_request_body) IN ('null', 'blob')
            AND typeof(sealed_body_ciphertext) IN ('null', 'blob')
            AND typeof(sealed_body_nonce) IN ('null', 'blob')
            AND typeof(exact_response_body) IN ('null', 'blob')
            THEN 1 ELSE 0
          END AS has_room_safe_nullable_blob_storage,
          CASE WHEN
            typeof(endpoint_id) = 'text'
            AND endpoint_id IN (
              'auth_revoke',
              'sync_push',
              'sync_bootstrap',
              'sync_pull'
            )
            AND typeof(request_body_octet_count) = 'integer'
            AND request_body_octet_count > 0
            AND typeof(raw_body_hmac) = 'blob'
            AND length(raw_body_hmac) = 32
            AND typeof(hmac_key_generation) = 'integer'
            AND hmac_key_generation = 1
            AND (
              (
                typeof(body_storage_kind) = 'text'
                AND body_storage_kind = 'raw'
                AND endpoint_id != 'auth_revoke'
                AND typeof(raw_request_body) = 'blob'
                AND length(raw_request_body) > 0
                AND request_body_octet_count = length(raw_request_body)
                AND typeof(sealed_body_ciphertext) = 'null'
                AND typeof(sealed_body_nonce) = 'null'
                AND typeof(sealed_body_key_alias) = 'null'
                AND typeof(sealed_body_key_generation) = 'null'
                AND typeof(sealed_body_aad_version) = 'null'
              )
              OR
              (
                typeof(body_storage_kind) = 'text'
                AND body_storage_kind = 'keystore_aead'
                AND endpoint_id = 'auth_revoke'
                AND typeof(raw_request_body) = 'null'
                AND typeof(sealed_body_ciphertext) = 'blob'
                AND length(sealed_body_ciphertext) > 0
                AND typeof(sealed_body_nonce) = 'blob'
                AND length(sealed_body_nonce) > 0
                AND typeof(sealed_body_key_alias) = 'text'
                AND length(trim(sealed_body_key_alias)) > 0
                AND typeof(sealed_body_key_generation) = 'integer'
                AND sealed_body_key_generation > 0
                AND sealed_body_key_generation <= 2147483647
                AND typeof(sealed_body_aad_version) = 'integer'
                AND sealed_body_aad_version > 0
                AND sealed_body_aad_version <= 2147483647
              )
            )
            THEN 1 ELSE 0
          END AS has_room_safe_entity_shape,
          CASE WHEN
            typeof(state) = 'text'
            AND state = 'sending'
            AND typeof(attempt_count) = 'integer'
            AND typeof(attempt_budget) = 'integer'
            AND attempt_count >= 1
            AND attempt_budget > 0
            AND attempt_count <= attempt_budget
            AND attempt_count <= 2147483647
            AND attempt_budget <= 2147483647
            AND typeof(deadline_at_epoch_ms) = 'integer'
            AND deadline_at_epoch_ms > 0
            AND typeof(next_attempt_at_epoch_ms) = 'null'
            AND typeof(last_attempt_at_epoch_ms) = 'integer'
            AND last_attempt_at_epoch_ms > 0
            AND typeof(lease_expires_at_epoch_ms) = 'integer'
            AND lease_expires_at_epoch_ms > last_attempt_at_epoch_ms
            AND lease_expires_at_epoch_ms <= deadline_at_epoch_ms
            AND typeof(access_generation_used) = 'integer'
            AND access_generation_used > 0
            AND typeof(refresh_attempted) = 'integer'
            AND refresh_attempted IN (0, 1)
            AND typeof(original_retry_count) = 'integer'
            AND original_retry_count IN (0, 1)
            AND typeof(active_attempt_id) = 'text'
            AND length(trim(active_attempt_id)) > 0
            AND typeof(terminal_http_status) = 'null'
            AND typeof(exact_response_body) = 'null'
            AND typeof(response_sha256) = 'null'
            AND typeof(terminal_at_utc) = 'null'
            THEN 1 ELSE 0
          END AS has_fresh_response_metadata_shape
        FROM sync_http_request
        WHERE (
            typeof(active_attempt_id) = 'text'
            AND active_attempt_id = :expectedAttemptId
          )
          OR (
            typeof(endpoint_id) = 'text'
            AND endpoint_id = :endpointId
            AND typeof(request_identity) = 'text'
            AND request_identity = :requestIdentity
          )
        ORDER BY CASE
          WHEN typeof(active_attempt_id) = 'text'
           AND active_attempt_id = :expectedAttemptId
            THEN 0
          ELSE 1
        END
        LIMIT 1
        """,
    )
    suspend fun findResponseRouteSnapshot(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
    ): SyncResponseRouteSnapshot?

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

    @Query(
        """
        SELECT batch.batch_id
        FROM sync_push_batch AS batch
        JOIN sync_http_request AS request
          ON request.endpoint_id = batch.endpoint_id
         AND request.request_identity = batch.request_identity
        WHERE request.credential_epoch_id = :credentialEpochId
          AND request.device_id = :deviceId
          AND request.state IN (
            'ready',
            'retry_wait',
            'sending',
            'waiting_refresh'
          )
        ORDER BY batch.created_at_utc, batch.batch_id
        """,
    )
    suspend fun findOpenPushBatchIds(
        credentialEpochId: String,
        deviceId: String,
    ): List<String>

    @Query(
        """
        SELECT * FROM sync_http_request
        WHERE endpoint_id = 'sync_bootstrap'
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state IN (
            'ready',
            'retry_wait',
            'sending',
            'waiting_refresh'
          )
        ORDER BY created_at_utc, request_identity
        """,
    )
    suspend fun findOpenBootstrapRequests(
        credentialEpochId: String,
        deviceId: String,
    ): List<SyncHttpRequestEntity>

    @Query(
        """
        SELECT endpoint_id, request_identity
        FROM sync_http_request
        WHERE endpoint_id = 'sync_bootstrap'
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state IN (
            'ready',
            'retry_wait',
            'sending',
            'waiting_refresh'
          )
        ORDER BY created_at_utc, request_identity
        """,
    )
    suspend fun findOpenBootstrapRequestKeys(
        credentialEpochId: String,
        deviceId: String,
    ): List<SyncRequestKey>

    @Query(
        """
        SELECT COUNT(*)
        FROM sync_http_request
        WHERE state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
        """,
    )
    suspend fun countOpenRequestRows(): Long

    @Query(
        """
        SELECT COUNT(*) FROM sync_http_request
        WHERE endpoint_id = 'sync_pull'
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
        """,
    )
    suspend fun countOpenPullRequests(
        credentialEpochId: String,
        deviceId: String,
    ): Long

    @Query(
        """
        SELECT * FROM sync_bootstrap_session
        WHERE credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state = 'staging'
          AND active_slot = 1
        ORDER BY created_at_utc, bootstrap_id
        """,
    )
    suspend fun findActiveBootstrapSessions(
        credentialEpochId: String,
        deviceId: String,
    ): List<SyncBootstrapSessionEntity>

    @Query(
        """
        SELECT
          request.endpoint_id,
          request.request_identity,
          request.credential_epoch_id,
          request.device_id,
          request.access_generation_used,
          request.state,
          request.attempt_count,
          request.attempt_budget,
          request.deadline_at_epoch_ms,
          request.next_attempt_at_epoch_ms,
          request.last_attempt_at_epoch_ms,
          request.lease_expires_at_epoch_ms,
          request.active_attempt_id,
          CASE request.state
            WHEN 'sending' THEN request.lease_expires_at_epoch_ms
            WHEN 'retry_wait' THEN request.next_attempt_at_epoch_ms
            ELSE :nowEpochMs
          END AS scheduled_at_epoch_ms,
          CASE request.endpoint_id
            WHEN 'auth_revoke' THEN 0
            WHEN 'sync_bootstrap' THEN 1
            WHEN 'sync_push' THEN 2
            WHEN 'sync_pull' THEN 3
            ELSE 4
          END AS route_priority
        FROM sync_http_request AS request
        WHERE (
            (
              request.state = 'ready'
              AND request.next_attempt_at_epoch_ms IS NULL
            )
            OR (
              request.state = 'retry_wait'
              AND request.next_attempt_at_epoch_ms IS NOT NULL
              AND request.next_attempt_at_epoch_ms <= :nowEpochMs
            )
            OR (
              request.state = 'sending'
              AND request.active_attempt_id IS NOT NULL
              AND request.lease_expires_at_epoch_ms IS NOT NULL
              AND request.lease_expires_at_epoch_ms <= :nowEpochMs
            )
          )
          AND request.attempt_count < request.attempt_budget
          AND request.deadline_at_epoch_ms > :nowEpochMs
          AND (
            (
              request.endpoint_id = 'auth_revoke'
              AND EXISTS (
                SELECT 1
                FROM sync_auth_state AS auth
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id = identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE auth.singleton_id = 1
                  AND auth.credential_epoch_id = request.credential_epoch_id
                  AND auth.device_id = request.device_id
                  AND auth.generation = request.access_generation_used
                  AND auth.state = 'revoke_pending'
              )
            )
            OR (
              request.endpoint_id IN (
                'sync_push',
                'sync_bootstrap',
                'sync_pull'
              )
              AND EXISTS (
                SELECT 1
                FROM sync_stream_state AS stream
                JOIN sync_auth_state AS auth
                  ON auth.singleton_id = 1
                 AND auth.credential_epoch_id = stream.credential_epoch_id
                 AND auth.device_id = stream.device_id
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id = identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE stream.singleton_id = 1
                  AND stream.credential_epoch_id = request.credential_epoch_id
                  AND stream.device_id = request.device_id
                  AND stream.integrity_error_code IS NULL
                  AND stream.phase != 'integrity_halted'
                  AND auth.state = 'active'
                  AND auth.access_expires_at_epoch_ms > :nowEpochMs
                  AND auth.family_expires_at_epoch_ms > :nowEpochMs
                  AND NOT EXISTS (
                    SELECT 1
                    FROM sync_auth_attempt AS enrollment
                    WHERE enrollment.endpoint_id = 'auth_enroll'
                      AND enrollment.state = 'dispatching'
                      AND enrollment.installation_id = identity.installation_id
                      AND enrollment.local_owner_id = identity.local_owner_id
                      AND (
                        enrollment.credential_epoch_id IS NULL
                        OR (
                          enrollment.credential_epoch_id = auth.credential_epoch_id
                          AND enrollment.expected_device_id = auth.device_id
                          AND enrollment.expected_generation = auth.generation
                        )
                      )
                  )
                  AND (
                    (
                      request.endpoint_id = 'sync_bootstrap'
                      AND stream.phase = 'bootstrap_required'
                      AND stream.bootstrap_required = 1
                      AND EXISTS (
                        SELECT 1
                        FROM sync_bootstrap_session AS session
                        WHERE session.credential_epoch_id = stream.credential_epoch_id
                          AND session.device_id = stream.device_id
                          AND session.state = 'staging'
                          AND session.active_slot = 1
                      )
                    )
                    OR (
                      request.endpoint_id = 'sync_push'
                      AND stream.phase = 'incremental'
                      AND stream.bootstrap_required = 0
                    )
                    OR (
                      request.endpoint_id = 'sync_pull'
                      AND stream.phase IN ('incremental', 'pulling')
                      AND stream.bootstrap_required = 0
                    )
                  )
              )
            )
          )
        ORDER BY
          route_priority,
          scheduled_at_epoch_ms,
          request.created_at_utc,
          request.request_identity
        LIMIT :limit
        """,
    )
    suspend fun findRunnableRequestCandidateRows(
        nowEpochMs: Long,
        limit: Int,
    ): List<SyncRunnableRequestCandidate>

    @Transaction
    suspend fun findRunnableRequestCandidates(
        nowEpochMs: Long,
        limit: Int,
    ): List<SyncRunnableRequestCandidate> {
        require(limit > 0)
        return findRunnableRequestCandidateRows(nowEpochMs, limit)
    }

    @Query(
        """
        SELECT MIN(
          CASE request.state
            WHEN 'retry_wait' THEN request.next_attempt_at_epoch_ms
            ELSE :nowEpochMs
          END
        )
        FROM sync_http_request AS request
        WHERE (
            (
              request.state = 'ready'
              AND request.next_attempt_at_epoch_ms IS NULL
            )
            OR (
              request.state = 'retry_wait'
              AND request.next_attempt_at_epoch_ms IS NOT NULL
            )
          )
          AND request.attempt_count < request.attempt_budget
          AND request.deadline_at_epoch_ms > :nowEpochMs
          AND (
            (
              request.endpoint_id = 'auth_revoke'
              AND EXISTS (
                SELECT 1
                FROM sync_auth_state AS auth
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id = identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE auth.singleton_id = 1
                  AND auth.credential_epoch_id = request.credential_epoch_id
                  AND auth.device_id = request.device_id
                  AND auth.generation = request.access_generation_used
                  AND auth.state = 'revoke_pending'
              )
            )
            OR (
              request.endpoint_id IN (
                'sync_push',
                'sync_bootstrap',
                'sync_pull'
              )
              AND EXISTS (
                SELECT 1
                FROM sync_stream_state AS stream
                JOIN sync_auth_state AS auth
                  ON auth.singleton_id = 1
                 AND auth.credential_epoch_id = stream.credential_epoch_id
                 AND auth.device_id = stream.device_id
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id = identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE stream.singleton_id = 1
                  AND stream.credential_epoch_id = request.credential_epoch_id
                  AND stream.device_id = request.device_id
                  AND stream.integrity_error_code IS NULL
                  AND stream.phase != 'integrity_halted'
                  AND auth.state = 'active'
                  AND auth.access_expires_at_epoch_ms > :nowEpochMs
                  AND auth.family_expires_at_epoch_ms > :nowEpochMs
                  AND NOT EXISTS (
                    SELECT 1
                    FROM sync_auth_attempt AS enrollment
                    WHERE enrollment.endpoint_id = 'auth_enroll'
                      AND enrollment.state = 'dispatching'
                      AND enrollment.installation_id = identity.installation_id
                      AND enrollment.local_owner_id = identity.local_owner_id
                      AND (
                        enrollment.credential_epoch_id IS NULL
                        OR (
                          enrollment.credential_epoch_id = auth.credential_epoch_id
                          AND enrollment.expected_device_id = auth.device_id
                          AND enrollment.expected_generation = auth.generation
                        )
                      )
                  )
                  AND (
                    (
                      request.endpoint_id = 'sync_bootstrap'
                      AND stream.phase = 'bootstrap_required'
                      AND stream.bootstrap_required = 1
                      AND EXISTS (
                        SELECT 1
                        FROM sync_bootstrap_session AS session
                        WHERE session.credential_epoch_id = stream.credential_epoch_id
                          AND session.device_id = stream.device_id
                          AND session.state = 'staging'
                          AND session.active_slot = 1
                      )
                    )
                    OR (
                      request.endpoint_id = 'sync_push'
                      AND stream.phase = 'incremental'
                      AND stream.bootstrap_required = 0
                    )
                    OR (
                      request.endpoint_id = 'sync_pull'
                      AND stream.phase IN ('incremental', 'pulling')
                      AND stream.bootstrap_required = 0
                    )
                  )
              )
            )
          )
        """,
    )
    suspend fun findEarliestDispatchCandidateAtEpochMs(nowEpochMs: Long): Long?

    @Query(
        """
        SELECT MIN(
          CASE
            WHEN lease_expires_at_epoch_ms <= :nowEpochMs THEN :nowEpochMs
            ELSE lease_expires_at_epoch_ms
          END
        )
        FROM sync_http_request
        WHERE state = 'sending'
          AND lease_expires_at_epoch_ms IS NOT NULL
        """,
    )
    suspend fun findEarliestSendingRecoveryAtEpochMs(nowEpochMs: Long): Long?

    @Transaction
    suspend fun findEarliestRequestWakeAtEpochMs(nowEpochMs: Long): Long? =
        listOfNotNull(
            findEarliestDispatchCandidateAtEpochMs(nowEpochMs),
            findEarliestSendingRecoveryAtEpochMs(nowEpochMs),
            findEarliestTerminalizationAtEpochMs(nowEpochMs),
        ).minOrNull()

    // Legacy entity hydration retained for persistence and recovery tests.
    @Query(
        """
        SELECT request.* FROM sync_http_request AS request
        WHERE (
            (
                request.state = 'ready'
                AND request.next_attempt_at_epoch_ms IS NULL
            )
            OR (
                request.state = 'retry_wait'
                AND request.next_attempt_at_epoch_ms IS NOT NULL
                AND request.next_attempt_at_epoch_ms <= :nowEpochMs
            )
            OR (
                request.state = 'sending'
                AND request.lease_expires_at_epoch_ms IS NOT NULL
                AND request.lease_expires_at_epoch_ms <= :nowEpochMs
            )
        )
          AND request.attempt_count < request.attempt_budget
          AND request.deadline_at_epoch_ms > :nowEpochMs
          AND (
            (
              request.endpoint_id = 'auth_revoke'
              AND EXISTS (
                SELECT 1
                FROM sync_auth_state AS auth
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id =
                      identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE auth.singleton_id = 1
                  AND auth.credential_epoch_id =
                      request.credential_epoch_id
                  AND auth.device_id = request.device_id
                  AND auth.generation = request.access_generation_used
                  AND auth.state = 'revoke_pending'
              )
            )
            OR (
              request.endpoint_id IN (
                'sync_push',
                'sync_bootstrap',
                'sync_pull'
              )
              AND EXISTS (
                SELECT 1
                FROM sync_stream_state AS stream
                JOIN sync_auth_state AS auth
                  ON auth.singleton_id = 1
                 AND auth.credential_epoch_id =
                     stream.credential_epoch_id
                 AND auth.device_id = stream.device_id
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id =
                      identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE stream.singleton_id = 1
                  AND stream.credential_epoch_id =
                      request.credential_epoch_id
                  AND stream.device_id = request.device_id
                  AND stream.integrity_error_code IS NULL
                  AND stream.phase != 'integrity_halted'
                  AND auth.state = 'active'
                  AND auth.access_expires_at_epoch_ms > :nowEpochMs
                  AND auth.family_expires_at_epoch_ms > :nowEpochMs
                  AND NOT EXISTS (
                    SELECT 1
                    FROM sync_auth_attempt AS enrollment
                    WHERE enrollment.endpoint_id = 'auth_enroll'
                      AND enrollment.state = 'dispatching'
                      AND enrollment.installation_id =
                          identity.installation_id
                      AND enrollment.local_owner_id =
                          identity.local_owner_id
                      AND (
                        enrollment.credential_epoch_id IS NULL
                        OR (
                          enrollment.credential_epoch_id =
                              auth.credential_epoch_id
                          AND enrollment.expected_device_id =
                              auth.device_id
                          AND enrollment.expected_generation =
                              auth.generation
                        )
                      )
                  )
                  AND (
                    (
                      request.endpoint_id = 'sync_bootstrap'
                      AND stream.phase = 'bootstrap_required'
                      AND stream.bootstrap_required = 1
                      AND EXISTS (
                        SELECT 1
                        FROM sync_bootstrap_session AS session
                        WHERE session.credential_epoch_id =
                            stream.credential_epoch_id
                          AND session.device_id = stream.device_id
                          AND session.state = 'staging'
                          AND session.active_slot = 1
                      )
                    )
                    OR (
                      request.endpoint_id = 'sync_push'
                      AND stream.phase = 'incremental'
                      AND stream.bootstrap_required = 0
                    )
                    OR (
                      request.endpoint_id = 'sync_pull'
                      AND stream.phase IN ('incremental', 'pulling')
                      AND stream.bootstrap_required = 0
                    )
                  )
              )
            )
          )
        ORDER BY
          CASE request.state
            WHEN 'sending' THEN request.lease_expires_at_epoch_ms
            WHEN 'retry_wait' THEN request.next_attempt_at_epoch_ms
            ELSE :nowEpochMs
          END,
          request.created_at_utc,
          request.endpoint_id,
          request.request_identity
        """,
    )
    suspend fun findRunnableRequestRows(
        nowEpochMs: Long,
    ): List<SyncHttpRequestEntity>

    @Transaction
    suspend fun findRunnableRequests(
        nowEpochMs: Long,
        limit: Int,
    ): List<SyncHttpRequestEntity> {
        require(limit > 0)
        // Discovery must never parse or trust durable body bytes. The protected
        // dispatch boundary authenticates and binds them immediately before its
        // attempt-claim CAS, and quarantines malformed rows there.
        return findRunnableRequestRows(nowEpochMs).take(limit)
    }

    @Query(
        """
        SELECT request.*
        FROM sync_http_request AS request
        WHERE (
            (
              request.state = 'ready'
              AND request.next_attempt_at_epoch_ms IS NULL
            )
            OR (
              request.state = 'retry_wait'
              AND request.next_attempt_at_epoch_ms IS NOT NULL
            )
            OR (
              request.state = 'sending'
              AND request.lease_expires_at_epoch_ms IS NOT NULL
            )
          )
          AND request.attempt_count < request.attempt_budget
          AND request.deadline_at_epoch_ms > :nowEpochMs
          AND (
            (
              request.endpoint_id = 'auth_revoke'
              AND EXISTS (
                SELECT 1
                FROM sync_auth_state AS auth
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id =
                      identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE auth.singleton_id = 1
                  AND auth.credential_epoch_id =
                      request.credential_epoch_id
                  AND auth.device_id = request.device_id
                  AND auth.generation = request.access_generation_used
                  AND auth.state = 'revoke_pending'
              )
            )
            OR (
              request.endpoint_id IN (
                'sync_push',
                'sync_bootstrap',
                'sync_pull'
              )
              AND EXISTS (
                SELECT 1
                FROM sync_stream_state AS stream
                JOIN sync_auth_state AS auth
                  ON auth.singleton_id = 1
                 AND auth.credential_epoch_id =
                     stream.credential_epoch_id
                 AND auth.device_id = stream.device_id
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id =
                      identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                WHERE stream.singleton_id = 1
                  AND stream.credential_epoch_id =
                      request.credential_epoch_id
                  AND stream.device_id = request.device_id
                  AND stream.integrity_error_code IS NULL
                  AND stream.phase != 'integrity_halted'
                  AND auth.state = 'active'
                  AND auth.access_expires_at_epoch_ms > :nowEpochMs
                  AND auth.family_expires_at_epoch_ms > :nowEpochMs
                  AND NOT EXISTS (
                    SELECT 1
                    FROM sync_auth_attempt AS enrollment
                    WHERE enrollment.endpoint_id = 'auth_enroll'
                      AND enrollment.state = 'dispatching'
                      AND enrollment.installation_id =
                          identity.installation_id
                      AND enrollment.local_owner_id =
                          identity.local_owner_id
                      AND (
                        enrollment.credential_epoch_id IS NULL
                        OR (
                          enrollment.credential_epoch_id =
                              auth.credential_epoch_id
                          AND enrollment.expected_device_id =
                              auth.device_id
                          AND enrollment.expected_generation =
                              auth.generation
                        )
                      )
                  )
                  AND (
                    (
                      request.endpoint_id = 'sync_bootstrap'
                      AND stream.phase = 'bootstrap_required'
                      AND stream.bootstrap_required = 1
                      AND EXISTS (
                        SELECT 1
                        FROM sync_bootstrap_session AS session
                        WHERE session.credential_epoch_id =
                            stream.credential_epoch_id
                          AND session.device_id = stream.device_id
                          AND session.state = 'staging'
                          AND session.active_slot = 1
                      )
                    )
                    OR (
                      request.endpoint_id = 'sync_push'
                      AND stream.phase = 'incremental'
                      AND stream.bootstrap_required = 0
                    )
                    OR (
                      request.endpoint_id = 'sync_pull'
                      AND stream.phase IN ('incremental', 'pulling')
                      AND stream.bootstrap_required = 0
                    )
                  )
              )
            )
          )
        ORDER BY
          CASE request.state
            WHEN 'sending' THEN request.lease_expires_at_epoch_ms
            WHEN 'retry_wait' THEN request.next_attempt_at_epoch_ms
            ELSE :nowEpochMs
          END,
          request.created_at_utc,
          request.endpoint_id,
          request.request_identity
        """,
    )
    suspend fun findPotentiallyRunnableRequestRows(
        nowEpochMs: Long,
    ): List<SyncHttpRequestEntity>

    @Transaction
    suspend fun findEarliestRunnableAtEpochMs(nowEpochMs: Long): Long? {
        val terminalizationAt =
            findEarliestTerminalizationAtEpochMs(nowEpochMs)
        val request = findPotentiallyRunnableRequestRows(nowEpochMs).firstOrNull()
        val scheduledAt = when (request?.state) {
            "sending" -> request.leaseExpiresAtEpochMs
            "retry_wait" -> request.nextAttemptAtEpochMs
            null -> null
            else -> nowEpochMs
        }
        return listOfNotNull(scheduledAt, terminalizationAt).minOrNull()
    }

    @Query(
        """
        SELECT * FROM sync_http_request
        WHERE state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
          AND (
            attempt_count >= attempt_budget
            OR deadline_at_epoch_ms <= :nowEpochMs
          )
          AND (
            state != 'sending'
            OR lease_expires_at_epoch_ms IS NULL
            OR lease_expires_at_epoch_ms <= :nowEpochMs
          )
        ORDER BY deadline_at_epoch_ms, created_at_utc, endpoint_id, request_identity
        LIMIT :limit
        """,
    )
    suspend fun findRequestsNeedingLocalTerminalization(
        nowEpochMs: Long,
        limit: Int,
    ): List<SyncHttpRequestEntity>

    @Query(
        """
        SELECT endpoint_id,
               request_identity,
               credential_epoch_id,
               device_id,
               CASE WHEN typeof(access_generation_used) = 'integer'
                 THEN access_generation_used
                 ELSE NULL
               END AS access_generation_used_integer_value,
               typeof(access_generation_used) AS access_generation_used_storage_class,
               CASE WHEN typeof(hmac_key_generation) = 'integer'
                 THEN hmac_key_generation
                 ELSE NULL
               END AS hmac_key_generation_integer_value,
               typeof(hmac_key_generation) AS hmac_key_generation_storage_class,
               quote(hmac_key_generation) AS hmac_key_generation_quoted,
               typeof(raw_body_hmac) AS raw_body_hmac_storage_class,
               hex(CAST(raw_body_hmac AS BLOB)) AS raw_body_hmac_hex,
               length(CAST(raw_body_hmac AS BLOB)) AS raw_body_hmac_octet_count,
               state,
               CASE WHEN typeof(attempt_count) = 'integer'
                 THEN attempt_count
                 ELSE NULL
               END AS attempt_count_integer_value,
               typeof(attempt_count) AS attempt_count_storage_class,
               quote(attempt_count) AS attempt_count_quoted,
               active_attempt_id,
               lease_expires_at_epoch_ms,
               updated_at_utc
        FROM sync_http_request
        WHERE state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
          AND (
            typeof(raw_body_hmac) != 'blob'
            OR length(CAST(raw_body_hmac AS BLOB)) != 32
            OR typeof(hmac_key_generation) != 'integer'
            OR hmac_key_generation != 1
            OR typeof(access_generation_used) != 'integer'
            OR access_generation_used IS NULL
            OR access_generation_used <= 0
            OR typeof(attempt_count) != 'integer'
            OR attempt_count < 0
            OR attempt_count > 2147483647
            OR (
              endpoint_id = 'auth_revoke'
              AND EXISTS (
                SELECT 1
                FROM sync_auth_state AS current_auth
                WHERE current_auth.singleton_id = 1
                  AND current_auth.credential_epoch_id =
                      sync_http_request.credential_epoch_id
                  AND current_auth.device_id = sync_http_request.device_id
                  AND current_auth.state = 'revoke_pending'
                  AND sync_http_request.access_generation_used !=
                      current_auth.generation
              )
            )
          )
        ORDER BY created_at_utc, endpoint_id, request_identity
        LIMIT :limit
        """,
    )
    suspend fun findOpenRequestsNeedingIntegrityRecovery(
        limit: Int,
    ): List<SyncRequestIntegrityRecoverySnapshot>

    @Query(
        """
        SELECT endpoint_id,
               request_identity,
               credential_epoch_id,
               device_id,
               CASE WHEN typeof(access_generation_used) = 'integer'
                 THEN access_generation_used
                 ELSE NULL
               END AS access_generation_used_integer_value,
               typeof(access_generation_used) AS access_generation_used_storage_class,
               CASE WHEN typeof(hmac_key_generation) = 'integer'
                 THEN hmac_key_generation
                 ELSE NULL
               END AS hmac_key_generation_integer_value,
               typeof(hmac_key_generation) AS hmac_key_generation_storage_class,
               quote(hmac_key_generation) AS hmac_key_generation_quoted,
               typeof(raw_body_hmac) AS raw_body_hmac_storage_class,
               hex(CAST(raw_body_hmac AS BLOB)) AS raw_body_hmac_hex,
               length(CAST(raw_body_hmac AS BLOB)) AS raw_body_hmac_octet_count,
               state,
               CASE WHEN typeof(attempt_count) = 'integer'
                 THEN attempt_count
                 ELSE NULL
               END AS attempt_count_integer_value,
               typeof(attempt_count) AS attempt_count_storage_class,
               quote(attempt_count) AS attempt_count_quoted,
               active_attempt_id,
               lease_expires_at_epoch_ms,
               updated_at_utc
        FROM sync_http_request
        WHERE endpoint_id = :endpointId
          AND request_identity = :requestIdentity
        """,
    )
    suspend fun findRequestIntegrityRecoverySnapshot(
        endpointId: String,
        requestIdentity: String,
    ): SyncRequestIntegrityRecoverySnapshot?

    @Query(
        """
        SELECT MIN(
          CASE
            WHEN state = 'sending'
             AND lease_expires_at_epoch_ms IS NOT NULL
             AND lease_expires_at_epoch_ms > :nowEpochMs
              THEN lease_expires_at_epoch_ms
            WHEN attempt_count >= attempt_budget
              THEN :nowEpochMs
            ELSE deadline_at_epoch_ms
          END
        )
        FROM sync_http_request
        WHERE state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
        """,
    )
    suspend fun findEarliestTerminalizationAtEpochMs(nowEpochMs: Long): Long?

    @Query(
        """
        SELECT * FROM sync_http_request
        WHERE state = 'waiting_refresh'
        ORDER BY credential_epoch_id, access_generation_used, created_at_utc
        """,
    )
    suspend fun findWaitingRefreshRequests(): List<SyncHttpRequestEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(entity: SyncHttpRequestEntity)

    @Query(
        """
        SELECT COUNT(*) FROM sync_http_request
        WHERE hmac_key_generation = :keyGeneration
        """,
    )
    suspend fun countRequestsReferencingHmacGeneration(keyGeneration: Int): Long

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'integrity_failure',
            hmac_key_generation = 1,
            raw_body_hmac = CASE
              WHEN typeof(hmac_key_generation) = 'integer'
                   AND hmac_key_generation = 1
                   AND typeof(raw_body_hmac) = 'blob'
                   AND length(raw_body_hmac) = 32
                THEN raw_body_hmac
              WHEN :failureCode = 'request_body_metadata_invalid' THEN zeroblob(32)
              ELSE raw_body_hmac
            END,
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :failedAtUtc,
            terminal_error_code = :failureCode,
            updated_at_utc = :failedAtUtc
        WHERE endpoint_id = :endpointId
          AND request_identity = :requestIdentity
          AND hmac_key_generation = :expectedHmacKeyGeneration
          AND raw_body_hmac = :expectedHmac
          AND state = :expectedState
          AND attempt_count = :expectedAttemptCount
          AND active_attempt_id IS :expectedActiveAttemptId
          AND lease_expires_at_epoch_ms IS :expectedLeaseExpiresAtEpochMs
          AND updated_at_utc = :expectedUpdatedAtUtc
        """,
    )
    suspend fun quarantineRequestBodyBeforeClaim(
        endpointId: String,
        requestIdentity: String,
        expectedHmacKeyGeneration: Int,
        expectedHmac: ByteArray,
        expectedState: String,
        expectedAttemptCount: Int,
        expectedActiveAttemptId: String?,
        expectedLeaseExpiresAtEpochMs: Long?,
        expectedUpdatedAtUtc: String,
        failedAtUtc: String,
        failureCode: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'integrity_failure',
            access_generation_used = CASE
              WHEN typeof(access_generation_used) = 'integer'
                   AND access_generation_used > 0
                THEN access_generation_used
              ELSE NULL
            END,
            attempt_count = CASE
              WHEN typeof(attempt_count) = 'integer'
                   AND attempt_count BETWEEN 0 AND 2147483647
                THEN attempt_count
              ELSE 0
            END,
            hmac_key_generation = 1,
            raw_body_hmac = CASE
              WHEN typeof(hmac_key_generation) = 'integer'
                   AND hmac_key_generation = 1
                   AND typeof(raw_body_hmac) = 'blob'
                   AND length(raw_body_hmac) = 32
                THEN raw_body_hmac
              ELSE zeroblob(32)
            END,
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :failedAtUtc,
            terminal_error_code = :failureCode,
            updated_at_utc = :failedAtUtc
        WHERE endpoint_id = :endpointId
          AND request_identity = :requestIdentity
          AND typeof(hmac_key_generation) = :expectedHmacKeyGenerationStorageClass
          AND quote(hmac_key_generation) = :expectedHmacKeyGenerationQuoted
          AND typeof(raw_body_hmac) = :expectedHmacStorageClass
          AND hex(CAST(raw_body_hmac AS BLOB)) = :expectedHmacHex
          AND state = :expectedState
          AND typeof(attempt_count) = :expectedAttemptCountStorageClass
          AND quote(attempt_count) = :expectedAttemptCountQuoted
          AND active_attempt_id IS :expectedActiveAttemptId
          AND lease_expires_at_epoch_ms IS :expectedLeaseExpiresAtEpochMs
          AND updated_at_utc = :expectedUpdatedAtUtc
          AND :failureCode = 'request_body_metadata_invalid'
        """,
    )
    suspend fun quarantineRequestIntegrityMetadata(
        endpointId: String,
        requestIdentity: String,
        expectedHmacKeyGenerationStorageClass: String,
        expectedHmacKeyGenerationQuoted: String,
        expectedHmacStorageClass: String,
        expectedHmacHex: String,
        expectedState: String,
        expectedAttemptCountStorageClass: String,
        expectedAttemptCountQuoted: String,
        expectedActiveAttemptId: String?,
        expectedLeaseExpiresAtEpochMs: Long?,
        expectedUpdatedAtUtc: String,
        failedAtUtc: String,
        failureCode: String,
    ): Int

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
                AND base_revision_id IS NOT NULL
                AND last_result_batch_id IS NOT NULL
                AND last_result_code = 'missing_parent'
                AND last_result_retryable = 1
                AND last_result_current_revision_id IS NULL
                AND server_sequence IS NULL
                AND acked_at_utc IS NULL
                AND last_error_code = 'missing_parent'
                AND EXISTS (
                  SELECT 1
                  FROM sync_server_change AS parent
                  WHERE parent.event_id = sync_outbox.event_id
                    AND parent.revision_id =
                        sync_outbox.base_revision_id
                )
            )
          )
          AND EXISTS (
            SELECT 1
            FROM sync_http_request AS request
            JOIN sync_stream_state AS stream
              ON stream.singleton_id = 1
             AND stream.credential_epoch_id =
                 request.credential_epoch_id
             AND stream.device_id = request.device_id
            JOIN sync_auth_state AS auth
              ON auth.singleton_id = 1
             AND auth.credential_epoch_id =
                 request.credential_epoch_id
             AND auth.device_id = request.device_id
             AND auth.generation = request.access_generation_used
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE request.endpoint_id = 'sync_push'
              AND request.request_identity = :batchId
              AND request.idempotency_key = :batchId
              AND request.state = 'ready'
              AND auth.state = 'active'
              AND auth.bootstrap_required = 0
              AND stream.phase = 'incremental'
              AND stream.bootstrap_required = 0
              AND stream.integrity_error_code IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM sync_auth_attempt AS enrollment
                WHERE enrollment.endpoint_id = 'auth_enroll'
                  AND enrollment.state = 'dispatching'
                  AND enrollment.installation_id =
                      identity.installation_id
                  AND enrollment.local_owner_id =
                      identity.local_owner_id
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
        require(request.state == "ready")
        require(checkNotNull(request.accessGenerationUsed) > 0)
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
            FROM sync_stream_state AS stream
            JOIN sync_auth_state AS auth
              ON auth.singleton_id = 1
             AND auth.credential_epoch_id = stream.credential_epoch_id
             AND auth.device_id = stream.device_id
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE stream.singleton_id = 1
              AND stream.credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND stream.device_id = sync_http_request.device_id
              AND stream.phase != 'integrity_halted'
              AND stream.integrity_error_code IS NULL
              AND auth.state = 'active'
              AND auth.generation = :accessGenerationUsed
              AND auth.bootstrap_required = stream.bootstrap_required
              AND auth.access_expires_at_epoch_ms > :attemptedAtEpochMs
              AND auth.family_expires_at_epoch_ms > :attemptedAtEpochMs
              AND NOT EXISTS (
                SELECT 1
                FROM sync_auth_attempt AS enrollment
                WHERE enrollment.endpoint_id = 'auth_enroll'
                  AND enrollment.state = 'dispatching'
                  AND enrollment.installation_id = identity.installation_id
                  AND enrollment.local_owner_id = identity.local_owner_id
                  AND (
                    enrollment.credential_epoch_id IS NULL
                    OR (
                      enrollment.credential_epoch_id =
                          auth.credential_epoch_id
                      AND enrollment.expected_device_id = auth.device_id
                      AND enrollment.expected_generation = auth.generation
                    )
                  )
              )
              AND (
                (
                  sync_http_request.endpoint_id = 'sync_bootstrap'
                  AND stream.phase = 'bootstrap_required'
                  AND stream.bootstrap_required = 1
                  AND EXISTS (
                    SELECT 1
                    FROM sync_bootstrap_session AS session
                    WHERE session.credential_epoch_id =
                        stream.credential_epoch_id
                      AND session.device_id = stream.device_id
                      AND session.state = 'staging'
                      AND session.active_slot = 1
                  )
                )
                OR (
                  sync_http_request.endpoint_id = 'sync_push'
                  AND stream.phase = 'incremental'
                  AND stream.bootstrap_required = 0
                )
                OR (
                  sync_http_request.endpoint_id = 'sync_pull'
                  AND stream.phase IN ('incremental', 'pulling')
                  AND stream.bootstrap_required = 0
                )
              )
          )
          AND length(trim(:attemptId)) > 0
          AND :accessGenerationUsed > 0
          AND (
            access_generation_used IS NULL
            OR access_generation_used <= :accessGenerationUsed
          )
          AND (
            (
              state = 'ready'
              AND next_attempt_at_epoch_ms IS NULL
            )
            OR (
              state = 'retry_wait'
              AND next_attempt_at_epoch_ms IS NOT NULL
              AND next_attempt_at_epoch_ms <= :attemptedAtEpochMs
            )
            OR (
                state = 'sending'
                AND lease_expires_at_epoch_ms IS NOT NULL
                AND lease_expires_at_epoch_ms <= :attemptedAtEpochMs
            )
          )
          AND attempt_count < attempt_budget
          AND :attemptedAtEpochMs < deadline_at_epoch_ms
          AND :leaseExpiresAtEpochMs > :attemptedAtEpochMs
          AND :leaseExpiresAtEpochMs <= deadline_at_epoch_ms
        """,
    )
    suspend fun claimAttemptRow(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        accessGenerationUsed: Long,
        attemptId: String,
        attemptedAtEpochMs: Long,
        leaseExpiresAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Transaction
    suspend fun claimAttempt(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        accessGenerationUsed: Long,
        attemptId: String,
        attemptedAtEpochMs: Long,
        leaseExpiresAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int = claimAttemptRow(
        endpointId = endpointId,
        requestIdentity = requestIdentity,
        credentialEpochId = credentialEpochId,
        accessGenerationUsed = accessGenerationUsed,
        attemptId = attemptId,
        attemptedAtEpochMs = attemptedAtEpochMs,
        leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
        updatedAtUtc = updatedAtUtc,
    )

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
          AND access_generation_used IS NOT NULL
          AND EXISTS (
            SELECT 1
            FROM sync_auth_state AS auth
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE auth.singleton_id = 1
              AND auth.credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND auth.device_id = sync_http_request.device_id
              AND auth.generation =
                  sync_http_request.access_generation_used
              AND auth.state = 'revoke_pending'
          )
          AND length(trim(:attemptId)) > 0
          AND (
            (
              state = 'ready'
              AND next_attempt_at_epoch_ms IS NULL
            )
            OR (
              state = 'retry_wait'
              AND next_attempt_at_epoch_ms IS NOT NULL
              AND next_attempt_at_epoch_ms <= :attemptedAtEpochMs
            )
            OR (
                state = 'sending'
                AND lease_expires_at_epoch_ms IS NOT NULL
                AND lease_expires_at_epoch_ms <= :attemptedAtEpochMs
            )
          )
          AND attempt_count < attempt_budget
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
        SELECT EXISTS(
          SELECT 1
          FROM sync_http_request
          WHERE endpoint_id = :endpointId
            AND request_identity = :requestIdentity
            AND typeof(state) = 'text'
            AND state = 'terminal'
            AND typeof(terminal_http_status) = 'integer'
            AND terminal_http_status = :httpStatus
            AND typeof(exact_response_body) = 'blob'
            AND exact_response_body = :exactResponseBody
            AND typeof(response_sha256) = 'text'
            AND response_sha256 = :responseSha256
            AND typeof(terminal_at_utc) = 'text'
            AND typeof(terminal_error_code) IN ('null', 'text')
        )
        """,
    )
    suspend fun matchesExactTerminalResponse(
        endpointId: String,
        requestIdentity: String,
        httpStatus: Int,
        exactResponseBody: ByteArray,
        responseSha256: String,
    ): Boolean

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'integrity_failure',
            terminal_http_status = NULL,
            exact_response_body = NULL,
            response_sha256 = NULL,
            terminal_at_utc = :failedAtUtc,
            terminal_error_code = :failureCode,
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            updated_at_utc = :failedAtUtc
        WHERE typeof(endpoint_id) = 'text'
          AND endpoint_id = :endpointId
          AND typeof(request_identity) = 'text'
          AND request_identity = :requestIdentity
          AND typeof(state) = 'text'
          AND state = 'sending'
          AND typeof(active_attempt_id) = 'text'
          AND active_attempt_id = :expectedAttemptId
          AND (
            (
              typeof(credential_epoch_id) = 'text'
              AND credential_epoch_id = :credentialEpochId
            )
            OR typeof(credential_epoch_id) != 'text'
          )
          AND (
            (
              typeof(access_generation_used) = 'integer'
              AND access_generation_used = :accessGenerationUsed
            )
            OR typeof(access_generation_used) != 'integer'
            OR access_generation_used IS NULL
          )
        """,
    )
    suspend fun quarantineFreshResponseMetadata(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        accessGenerationUsed: Long,
        expectedAttemptId: String,
        failedAtUtc: String,
        failureCode: String,
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
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = 'bootstrap_superseded',
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id = 'sync_bootstrap'
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND (
            :retainedRequestIdentity IS NULL
            OR request_identity != :retainedRequestIdentity
          )
          AND state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
          AND terminal_http_status IS NULL
          AND exact_response_body IS NULL
          AND response_sha256 IS NULL
          AND terminal_at_utc IS NULL
        """,
    )
    suspend fun invalidateSupersededBootstrapRequests(
        credentialEpochId: String,
        deviceId: String,
        retainedRequestIdentity: String?,
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
            terminal_error_code = 'sync_superseded_by_bootstrap',
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND (
            endpoint_id != 'sync_bootstrap'
            OR :retainedBootstrapRequestIdentity IS NULL
            OR request_identity != :retainedBootstrapRequestIdentity
          )
          AND state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
          AND terminal_http_status IS NULL
          AND exact_response_body IS NULL
          AND response_sha256 IS NULL
          AND terminal_at_utc IS NULL
        """,
    )
    suspend fun invalidateSupersededSyncRequests(
        credentialEpochId: String,
        deviceId: String,
        retainedBootstrapRequestIdentity: String?,
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
          AND :nextAttemptAtEpochMs < deadline_at_epoch_ms
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

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = :failureCode,
            updated_at_utc = :terminalAtUtc
        WHERE typeof(endpoint_id) = 'text'
          AND endpoint_id = :endpointId
          AND typeof(request_identity) = 'text'
          AND request_identity = :requestIdentity
          AND typeof(state) = 'text'
          AND state = 'sending'
          AND typeof(active_attempt_id) = 'text'
          AND active_attempt_id = :expectedAttemptId
          AND typeof(attempt_count) = 'integer'
          AND typeof(attempt_budget) = 'integer'
          AND typeof(deadline_at_epoch_ms) = 'integer'
          AND terminal_http_status IS NULL
          AND exact_response_body IS NULL
          AND response_sha256 IS NULL
          AND terminal_at_utc IS NULL
          AND (
            attempt_count >= attempt_budget
            OR :proposedNextAttemptAtEpochMs >= deadline_at_epoch_ms
          )
        """,
    )
    suspend fun terminalizeCompletedRetryFailure(
        endpointId: String,
        requestIdentity: String,
        expectedAttemptId: String,
        proposedNextAttemptAtEpochMs: Long,
        terminalAtUtc: String,
        failureCode: String,
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
          AND EXISTS (
            SELECT 1
            FROM sync_auth_state AS auth
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE auth.singleton_id = 1
              AND auth.credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND auth.device_id = sync_http_request.device_id
              AND auth.generation = :failedAccessGeneration
              AND auth.state IN ('active', 'refresh_in_flight')
              AND auth.refresh_expires_at_epoch_ms > :nowEpochMs
              AND auth.family_expires_at_epoch_ms > :nowEpochMs
          )
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
          AND EXISTS (
            SELECT 1
            FROM sync_auth_state AS auth
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE auth.singleton_id = 1
              AND auth.credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND auth.device_id = sync_http_request.device_id
              AND auth.generation = :installedGeneration
              AND auth.state = 'active'
              AND auth.access_expires_at_epoch_ms > :nowEpochMs
              AND auth.family_expires_at_epoch_ms > :nowEpochMs
          )
        """,
    )
    suspend fun scheduleExactRetryWithInstalledGeneration(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        installedGeneration: Long,
        expectedAttemptId: String,
        nowEpochMs: Long,
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
          AND EXISTS (
            SELECT 1
            FROM sync_auth_state AS auth
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE auth.singleton_id = 1
              AND auth.credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND auth.device_id = sync_http_request.device_id
              AND auth.generation = :successorGeneration
              AND auth.state = 'active'
              AND auth.access_expires_at_epoch_ms > :nowEpochMs
              AND auth.family_expires_at_epoch_ms > :nowEpochMs
          )
        """,
    )
    suspend fun scheduleExactRetryAfterRefresh(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        successorGeneration: Long,
        nowEpochMs: Long,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'retry_wait',
            access_generation_used = :successorGeneration,
            original_retry_count = 1,
            next_attempt_at_epoch_ms = :nextAttemptAtEpochMs,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_error_code = 'credential_recovery_complete',
            updated_at_utc = :updatedAtUtc
        WHERE endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND state = 'waiting_refresh'
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND access_generation_used = :failedAccessGeneration
          AND refresh_attempted = 1
          AND original_retry_count = 0
          AND :successorGeneration = :failedAccessGeneration + 1
          AND attempt_count < attempt_budget
          AND :nextAttemptAtEpochMs < deadline_at_epoch_ms
          AND EXISTS (
            SELECT 1
            FROM sync_auth_state AS auth
            JOIN local_identity_state AS identity
              ON identity.singleton_id = 1
             AND identity.installation_id = auth.installation_id
             AND identity.local_owner_id = auth.local_owner_id
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = auth.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = auth.person_id
            WHERE auth.singleton_id = 1
              AND auth.credential_epoch_id =
                  sync_http_request.credential_epoch_id
              AND auth.device_id = sync_http_request.device_id
              AND auth.generation = :successorGeneration
              AND auth.state = 'active'
              AND auth.access_expires_at_epoch_ms > :nextAttemptAtEpochMs
              AND auth.family_expires_at_epoch_ms > :nextAttemptAtEpochMs
          )
        """,
    )
    suspend fun releaseExactWaitingRefreshRequests(
        credentialEpochId: String,
        deviceId: String,
        failedAccessGeneration: Long,
        successorGeneration: Long,
        nextAttemptAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        SELECT batch.batch_id
        FROM sync_push_batch AS batch
        JOIN sync_http_request AS request
          ON request.endpoint_id = batch.endpoint_id
         AND request.request_identity = batch.request_identity
        WHERE request.endpoint_id = 'sync_push'
          AND request.state = 'waiting_refresh'
          AND request.credential_epoch_id = :credentialEpochId
          AND request.device_id = :deviceId
          AND request.access_generation_used = :failedAccessGeneration
          AND request.refresh_attempted = 1
        ORDER BY batch.created_at_utc, batch.batch_id
        """,
    )
    suspend fun findWaitingRefreshPushBatchIds(
        credentialEpochId: String,
        deviceId: String,
        failedAccessGeneration: Long,
    ): List<String>

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = :failureCode,
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND state = 'waiting_refresh'
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND access_generation_used = :failedAccessGeneration
          AND refresh_attempted = 1
        """,
    )
    suspend fun failExactWaitingRefreshRequestsRow(
        credentialEpochId: String,
        deviceId: String,
        failedAccessGeneration: Long,
        terminalAtUtc: String,
        failureCode: String,
    ): Int

    @Transaction
    suspend fun failExactWaitingRefreshRequests(
        credentialEpochId: String,
        deviceId: String,
        failedAccessGeneration: Long,
        terminalAtUtc: String,
        failureCode: String,
    ): Int {
        val pushBatchIds = findWaitingRefreshPushBatchIds(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            failedAccessGeneration = failedAccessGeneration,
        )
        val terminalized = failExactWaitingRefreshRequestsRow(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            failedAccessGeneration = failedAccessGeneration,
            terminalAtUtc = terminalAtUtc,
            failureCode = failureCode,
        )
        pushBatchIds.forEach { batchId ->
            val expected = findBatchItems(batchId).size
            check(expected > 0)
            check(releasePushBatchForBootstrap(batchId) == expected) {
                "Terminal refresh failure stranded a push batch"
            }
        }
        return terminalized
    }

    @Query(
        """
        UPDATE sync_outbox
        SET state = CASE
              WHEN last_result_code = 'missing_parent'
               AND last_result_retryable = 1
               AND last_result_batch_id IS NOT NULL
               AND last_result_current_revision_id IS NULL
               AND server_sequence IS NULL
               AND acked_at_utc IS NULL
               AND last_error_code = 'missing_parent'
               AND base_revision_id IS NOT NULL
              THEN 'waiting_parent'
              ELSE 'pending'
            END,
            active_batch_id = NULL
        WHERE state = 'batched'
          AND active_batch_id = :batchId
          AND EXISTS (
            SELECT 1
            FROM sync_push_batch_item AS item
            WHERE item.batch_id = :batchId
              AND item.local_sequence = sync_outbox.local_sequence
              AND item.operation_id = sync_outbox.operation_id
              AND item.wire_operation_content_sha256 =
                  sync_outbox.wire_operation_content_sha256
          )
        """,
    )
    suspend fun releasePushBatchForBootstrap(batchId: String): Int

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
    suspend fun markCredentialRecoveryExhaustedRow(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
    ): Int

    @Transaction
    suspend fun markCredentialRecoveryExhausted(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
    ): Int {
        val terminalized = markCredentialRecoveryExhaustedRow(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            credentialEpochId = credentialEpochId,
            failedAccessGeneration = failedAccessGeneration,
            expectedAttemptId = expectedAttemptId,
            terminalAtUtc = terminalAtUtc,
        )
        if (terminalized == 1 && endpointId == "sync_push") {
            releaseTerminalPushBatch(requestIdentity)
        }
        return terminalized
    }

    @Query(
        """
        UPDATE sync_http_request
        SET state = 'terminal_local',
            next_attempt_at_epoch_ms = NULL,
            lease_expires_at_epoch_ms = NULL,
            active_attempt_id = NULL,
            terminal_at_utc = :terminalAtUtc,
            terminal_error_code = :failureCode,
            updated_at_utc = :terminalAtUtc
        WHERE endpoint_id = :endpointId
          AND endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull')
          AND request_identity = :requestIdentity
          AND state = 'sending'
          AND active_attempt_id = :expectedAttemptId
          AND credential_epoch_id = :credentialEpochId
          AND access_generation_used = :failedAccessGeneration
        """,
    )
    suspend fun markCredentialFailureTerminalRow(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
        failureCode: String,
    ): Int

    @Transaction
    suspend fun markCredentialFailureTerminal(
        endpointId: String,
        requestIdentity: String,
        credentialEpochId: String,
        failedAccessGeneration: Long,
        expectedAttemptId: String,
        terminalAtUtc: String,
        failureCode: String,
    ): Int {
        val terminalized = markCredentialFailureTerminalRow(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            credentialEpochId = credentialEpochId,
            failedAccessGeneration = failedAccessGeneration,
            expectedAttemptId = expectedAttemptId,
            terminalAtUtc = terminalAtUtc,
            failureCode = failureCode,
        )
        if (terminalized == 1 && endpointId == "sync_push") {
            releaseTerminalPushBatch(requestIdentity)
        }
        return terminalized
    }

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
          AND state IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
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
    suspend fun markRetryBudgetExhaustedRow(
        endpointId: String,
        requestIdentity: String,
        nowEpochMs: Long,
        terminalAtUtc: String,
    ): Int

    @Transaction
    suspend fun markRetryBudgetExhausted(
        endpointId: String,
        requestIdentity: String,
        nowEpochMs: Long,
        terminalAtUtc: String,
    ): Int {
        val terminalized = markRetryBudgetExhaustedRow(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            nowEpochMs = nowEpochMs,
            terminalAtUtc = terminalAtUtc,
        )
        if (terminalized == 1 && endpointId == "sync_push") {
            releaseTerminalPushBatch(requestIdentity)
        }
        return terminalized
    }

    suspend fun releaseTerminalPushBatch(batchId: String) {
        val expected = findBatchItems(batchId).size
        check(expected > 0)
        check(releasePushBatchForBootstrap(batchId) == expected) {
            "Terminal local push stranded its outbox batch"
        }
    }
}
