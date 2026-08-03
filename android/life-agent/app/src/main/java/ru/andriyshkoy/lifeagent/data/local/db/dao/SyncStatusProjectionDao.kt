package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Content-free synchronization status intended for user-interface projection.
 *
 * The row deliberately excludes request identities, credential identifiers,
 * cursor values, response bodies, payloads, and error details.
 */
internal data class SyncStatusProjectionRow(
    @ColumnInfo(name = "auth_state")
    val authState: String?,
    @ColumnInfo(name = "latest_enrollment_attempt_state")
    val latestEnrollmentAttemptState: String?,
    @ColumnInfo(name = "pending_count")
    val pendingCount: Int,
    @ColumnInfo(name = "auth_bootstrap_required")
    val authBootstrapRequired: Boolean,
    @ColumnInfo(name = "stream_phase")
    val streamPhase: String?,
    @ColumnInfo(name = "stream_bootstrap_required")
    val streamBootstrapRequired: Boolean,
    @ColumnInfo(name = "active_bootstrap_state")
    val activeBootstrapState: String?,
    @ColumnInfo(name = "integrity_halted")
    val integrityHalted: Boolean,
    @ColumnInfo(name = "last_server_confirmation_at_utc")
    val lastServerConfirmationAtUtc: String?,
)

@Dao
internal interface SyncStatusProjectionDao {
    /**
     * Emits one atomic, bodyless snapshot whenever its source tables change.
     * Timestamps are ordered through SQLite's time value, with text only as a
     * deterministic tie-breaker.
     */
    @Query(
        """
        SELECT
          (
            SELECT state
            FROM sync_auth_state
            WHERE singleton_id = 1
          ) AS auth_state,
          (
            SELECT state
            FROM sync_auth_attempt
            WHERE endpoint_id = 'auth_enroll'
            ORDER BY
              julianday(updated_at_utc) DESC,
              updated_at_utc DESC,
              julianday(created_at_utc) DESC,
              created_at_utc DESC,
              request_id DESC
            LIMIT 1
          ) AS latest_enrollment_attempt_state,
          (
            SELECT COUNT(*)
            FROM sync_outbox
            WHERE state IN ('pending', 'batched', 'waiting_parent')
          ) AS pending_count,
          COALESCE(
            (
              SELECT bootstrap_required
              FROM sync_auth_state
              WHERE singleton_id = 1
            ),
            0
          ) AS auth_bootstrap_required,
          (
            SELECT phase
            FROM sync_stream_state
            WHERE singleton_id = 1
          ) AS stream_phase,
          COALESCE(
            (
              SELECT bootstrap_required
              FROM sync_stream_state
              WHERE singleton_id = 1
            ),
            0
          ) AS stream_bootstrap_required,
          (
            SELECT state
            FROM sync_bootstrap_session
            WHERE active_slot = 1
            LIMIT 1
          ) AS active_bootstrap_state,
          CASE
            WHEN EXISTS(
              SELECT 1
              FROM sync_auth_state
              WHERE singleton_id = 1
                AND state = 'integrity_failure'
            ) OR EXISTS(
              SELECT 1
              FROM sync_stream_state
              WHERE singleton_id = 1
                AND integrity_error_code IS NOT NULL
            ) THEN 1
            ELSE 0
          END AS integrity_halted,
          (
            SELECT server_confirmation_at_utc
            FROM (
              SELECT installed_at_utc AS server_confirmation_at_utc
              FROM sync_auth_state
              WHERE singleton_id = 1
                AND state IN ('active', 'refresh_in_flight', 'revoke_pending')
              UNION ALL
              SELECT updated_at_utc AS server_confirmation_at_utc
              FROM sync_auth_attempt
              WHERE endpoint_id IN ('auth_enroll', 'auth_refresh')
                AND state = 'completed'
              UNION ALL
              SELECT terminal_at_utc AS server_confirmation_at_utc
              FROM sync_http_request
              WHERE state = 'terminal'
                AND terminal_http_status BETWEEN 200 AND 299
                AND terminal_error_code IS NULL
                AND terminal_at_utc IS NOT NULL
            )
            WHERE server_confirmation_at_utc IS NOT NULL
            ORDER BY
              julianday(server_confirmation_at_utc) DESC,
              server_confirmation_at_utc DESC
            LIMIT 1
          ) AS last_server_confirmation_at_utc
        """,
    )
    fun observeProjection(): Flow<SyncStatusProjectionRow>
}
