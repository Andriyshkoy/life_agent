package ru.andriyshkoy.lifeagent.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.time.Instant
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthAttemptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity

@Dao
interface SyncAuthDao {
    @Query("SELECT * FROM sync_auth_state WHERE singleton_id = 1")
    suspend fun findState(): SyncAuthStateEntity?

    @Query("SELECT * FROM sync_auth_attempt WHERE request_id = :requestId")
    suspend fun findAttempt(requestId: String): SyncAuthAttemptEntity?

    @Query(
        """
        SELECT * FROM sync_auth_attempt
        WHERE endpoint_id = :endpointId AND state = :state
        ORDER BY created_at_utc, request_id
        """,
    )
    suspend fun findAttempts(
        endpointId: String,
        state: String,
    ): List<SyncAuthAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStateRow(entity: SyncAuthStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(entity: SyncAuthAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTokenFingerprint(entity: SyncAuthTokenFingerprintEntity)

    @Transaction
    suspend fun installEnrollment(
        state: SyncAuthStateEntity,
        accessFingerprint: SyncAuthTokenFingerprintEntity,
        refreshFingerprint: SyncAuthTokenFingerprintEntity,
    ) {
        require(state.state == "active")
        require(state.generation == 1L)
        require(
            accessFingerprint.credentialEpochId == state.credentialEpochId &&
                refreshFingerprint.credentialEpochId == state.credentialEpochId &&
                accessFingerprint.generation == state.generation &&
                refreshFingerprint.generation == state.generation &&
                accessFingerprint.tokenKind == "access" &&
                refreshFingerprint.tokenKind == "refresh",
        ) {
            "Enrollment token fingerprints do not bind the credential family"
        }
        insertTokenFingerprint(accessFingerprint)
        insertTokenFingerprint(refreshFingerprint)
        insertStateRow(state)
    }

    @Query(
        """
        UPDATE sync_auth_attempt
        SET state = :newState,
            updated_at_utc = :updatedAtUtc,
            last_error_code = :lastErrorCode
        WHERE request_id = :requestId AND state = :expectedState
        """,
    )
    suspend fun compareAndSetAttemptState(
        requestId: String,
        expectedState: String,
        newState: String,
        updatedAtUtc: String,
        lastErrorCode: String?,
    ): Int

    @Query(
        """
        UPDATE sync_auth_state
        SET state = 'refresh_in_flight',
            updated_at_utc = :updatedAtUtc,
            failure_code = NULL
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND generation = :generation
          AND installation_id = :installationId
          AND local_owner_id = :localOwnerId
          AND state = 'active'
          AND refresh_expires_at_epoch_ms > :nowEpochMs
          AND family_expires_at_epoch_ms > :nowEpochMs
          AND EXISTS (
            SELECT 1
            FROM local_identity_state AS identity
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = sync_auth_state.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = sync_auth_state.person_id
            WHERE identity.singleton_id = 1
              AND identity.installation_id =
                  sync_auth_state.installation_id
              AND identity.local_owner_id =
                  sync_auth_state.local_owner_id
          )
          AND NOT EXISTS (
            SELECT 1
            FROM sync_auth_attempt AS enrollment
            WHERE enrollment.endpoint_id = 'auth_enroll'
              AND enrollment.state = 'dispatching'
              AND enrollment.installation_id =
                  sync_auth_state.installation_id
              AND enrollment.local_owner_id =
                  sync_auth_state.local_owner_id
              AND (
                enrollment.credential_epoch_id IS NULL
                OR (
                  enrollment.credential_epoch_id =
                      sync_auth_state.credential_epoch_id
                  AND enrollment.expected_device_id =
                      sync_auth_state.device_id
                  AND enrollment.expected_generation =
                      sync_auth_state.generation
                )
              )
          )
        """,
    )
    suspend fun claimRefreshFamily(
        credentialEpochId: String,
        deviceId: String,
        generation: Long,
        installationId: String,
        localOwnerId: String,
        nowEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Transaction
    suspend fun claimRefreshAttempt(
        entity: SyncAuthAttemptEntity,
        nowEpochMs: Long,
    ) {
        check(entity.endpointId == "auth_refresh")
        check(entity.state == "dispatching")
        val deviceId = checkNotNull(entity.expectedDeviceId)
        val generation = checkNotNull(entity.expectedGeneration)
        val credentialEpochId = checkNotNull(entity.credentialEpochId)
        check(
            claimRefreshFamily(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = generation,
                installationId = entity.installationId,
                localOwnerId = entity.localOwnerId,
                nowEpochMs = nowEpochMs,
                updatedAtUtc = entity.updatedAtUtc,
            ) == 1,
        ) {
            "Credential family is not eligible for refresh"
        }
        insertAttempt(entity)
    }

    @Query(
        """
        UPDATE sync_auth_state
        SET refresh_token_ciphertext = :refreshTokenCiphertext,
            refresh_token_nonce = :refreshTokenNonce,
            refresh_token_key_alias = :refreshTokenKeyAlias,
            refresh_token_key_generation = :refreshTokenKeyGeneration,
            refresh_token_aad_version = :refreshTokenAadVersion,
            access_expires_at_utc = :accessExpiresAtUtc,
            access_expires_at_epoch_ms = :accessExpiresAtEpochMs,
            refresh_expires_at_utc = :refreshExpiresAtUtc,
            refresh_expires_at_epoch_ms = :refreshExpiresAtEpochMs,
            generation = :nextGeneration,
            state = 'active',
            updated_at_utc = :updatedAtUtc,
            failure_code = NULL
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND generation = :expectedGeneration
          AND family_expires_at_utc = :familyExpiresAtUtc
          AND family_expires_at_epoch_ms = :familyExpiresAtEpochMs
          AND state = 'refresh_in_flight'
        """,
    )
    suspend fun compareAndInstallRefreshSuccess(
        credentialEpochId: String,
        deviceId: String,
        expectedGeneration: Long,
        nextGeneration: Long,
        refreshTokenCiphertext: ByteArray,
        refreshTokenNonce: ByteArray,
        refreshTokenKeyAlias: String,
        refreshTokenKeyGeneration: Int,
        refreshTokenAadVersion: Int,
        accessExpiresAtUtc: String,
        accessExpiresAtEpochMs: Long,
        refreshExpiresAtUtc: String,
        refreshExpiresAtEpochMs: Long,
        familyExpiresAtUtc: String,
        familyExpiresAtEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Transaction
    suspend fun installRefreshSuccess(
        requestId: String,
        credentialEpochId: String,
        deviceId: String,
        expectedGeneration: Long,
        nextGeneration: Long,
        refreshTokenCiphertext: ByteArray,
        refreshTokenNonce: ByteArray,
        refreshTokenKeyAlias: String,
        refreshTokenKeyGeneration: Int,
        refreshTokenAadVersion: Int,
        accessExpiresAtUtc: String,
        accessExpiresAtEpochMs: Long,
        refreshExpiresAtUtc: String,
        refreshExpiresAtEpochMs: Long,
        familyExpiresAtUtc: String,
        familyExpiresAtEpochMs: Long,
        updatedAtUtc: String,
        accessFingerprint: SyncAuthTokenFingerprintEntity,
        refreshFingerprint: SyncAuthTokenFingerprintEntity,
    ) {
        check(nextGeneration == expectedGeneration + 1)
        require(refreshTokenCiphertext.isNotEmpty())
        require(refreshTokenNonce.isNotEmpty())
        require(refreshTokenKeyAlias.isNotBlank())
        require(refreshTokenKeyGeneration > 0)
        require(refreshTokenAadVersion > 0)
        require(accessExpiresAtEpochMs > 0)
        require(refreshExpiresAtEpochMs > accessExpiresAtEpochMs)
        require(familyExpiresAtEpochMs >= refreshExpiresAtEpochMs)
        require(Instant.parse(accessExpiresAtUtc).toEpochMilli() == accessExpiresAtEpochMs)
        require(Instant.parse(refreshExpiresAtUtc).toEpochMilli() == refreshExpiresAtEpochMs)
        require(Instant.parse(familyExpiresAtUtc).toEpochMilli() == familyExpiresAtEpochMs)
        val attempt = checkNotNull(findAttempt(requestId)) {
            "Refresh attempt is missing"
        }
        check(
            attempt.endpointId == "auth_refresh" &&
                attempt.credentialEpochId == credentialEpochId &&
                attempt.expectedDeviceId == deviceId &&
                attempt.expectedGeneration == expectedGeneration &&
                attempt.state == "dispatching",
        ) {
            "Refresh response does not bind the dispatching attempt"
        }
        check(
            accessFingerprint.credentialEpochId == credentialEpochId &&
                refreshFingerprint.credentialEpochId == credentialEpochId &&
                accessFingerprint.generation == nextGeneration &&
                refreshFingerprint.generation == nextGeneration &&
                accessFingerprint.tokenKind == "access" &&
                refreshFingerprint.tokenKind == "refresh",
        ) {
            "Successor token fingerprints do not bind the new generation"
        }
        insertTokenFingerprint(accessFingerprint)
        insertTokenFingerprint(refreshFingerprint)
        check(
            compareAndInstallRefreshSuccess(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                expectedGeneration = expectedGeneration,
                nextGeneration = nextGeneration,
                refreshTokenCiphertext = refreshTokenCiphertext,
                refreshTokenNonce = refreshTokenNonce,
                refreshTokenKeyAlias = refreshTokenKeyAlias,
                refreshTokenKeyGeneration = refreshTokenKeyGeneration,
                refreshTokenAadVersion = refreshTokenAadVersion,
                accessExpiresAtUtc = accessExpiresAtUtc,
                accessExpiresAtEpochMs = accessExpiresAtEpochMs,
                refreshExpiresAtUtc = refreshExpiresAtUtc,
                refreshExpiresAtEpochMs = refreshExpiresAtEpochMs,
                familyExpiresAtUtc = familyExpiresAtUtc,
                familyExpiresAtEpochMs = familyExpiresAtEpochMs,
                updatedAtUtc = updatedAtUtc,
            ) == 1,
        ) {
            "Refresh response lost its credential-family CAS"
        }
        check(
            compareAndSetAttemptState(
                requestId = requestId,
                expectedState = "dispatching",
                newState = "completed",
                updatedAtUtc = updatedAtUtc,
                lastErrorCode = null,
            ) == 1,
        ) {
            "Refresh attempt is no longer dispatching"
        }
    }

    @Query(
        """
        UPDATE sync_auth_state
        SET state = :newState,
            refresh_token_ciphertext = NULL,
            refresh_token_nonce = NULL,
            refresh_token_key_alias = NULL,
            refresh_token_key_generation = NULL,
            refresh_token_aad_version = NULL,
            updated_at_utc = :updatedAtUtc,
            failure_code = :failureCode
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND generation = :generation
          AND state = :expectedState
        """,
    )
    suspend fun quarantine(
        credentialEpochId: String,
        generation: Long,
        expectedState: String,
        newState: String,
        updatedAtUtc: String,
        failureCode: String,
    ): Int

    @Query(
        """
        UPDATE sync_auth_state
        SET state = 'expired',
            refresh_token_ciphertext = NULL,
            refresh_token_nonce = NULL,
            refresh_token_key_alias = NULL,
            refresh_token_key_generation = NULL,
            refresh_token_aad_version = NULL,
            updated_at_utc = :updatedAtUtc,
            failure_code = 'refresh_expired'
        WHERE singleton_id = 1
          AND state IN ('active', 'refresh_in_flight')
          AND (
            refresh_expires_at_epoch_ms <= :nowEpochMs
            OR family_expires_at_epoch_ms <= :nowEpochMs
          )
        """,
    )
    suspend fun purgeExpiredRefreshEnvelope(
        nowEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_auth_state
        SET state = 'integrity_failure',
            refresh_token_ciphertext = NULL,
            refresh_token_nonce = NULL,
            refresh_token_key_alias = NULL,
            refresh_token_key_generation = NULL,
            refresh_token_aad_version = NULL,
            updated_at_utc = :updatedAtUtc,
            failure_code = :failureCode
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND generation = :generation
          AND refresh_token_key_alias = :expectedKeyAlias
          AND refresh_token_key_generation = :expectedKeyGeneration
          AND refresh_token_aad_version = :expectedAadVersion
          AND state IN ('active', 'refresh_in_flight')
        """,
    )
    suspend fun quarantineRefreshEnvelopeRow(
        credentialEpochId: String,
        generation: Long,
        expectedKeyAlias: String,
        expectedKeyGeneration: Int,
        expectedAadVersion: Int,
        updatedAtUtc: String,
        failureCode: String,
    ): Int

    @Transaction
    suspend fun quarantineRefreshEnvelope(
        credentialEpochId: String,
        generation: Long,
        expectedKeyAlias: String,
        expectedKeyGeneration: Int,
        expectedAadVersion: Int,
        updatedAtUtc: String,
        failureCode: String,
    ) {
        require(
            failureCode in setOf(
                "refresh_key_unavailable",
                "refresh_decryption_failed",
                "refresh_envelope_invalid",
            ),
        )
        check(
            quarantineRefreshEnvelopeRow(
                credentialEpochId = credentialEpochId,
                generation = generation,
                expectedKeyAlias = expectedKeyAlias,
                expectedKeyGeneration = expectedKeyGeneration,
                expectedAadVersion = expectedAadVersion,
                updatedAtUtc = updatedAtUtc,
                failureCode = failureCode,
            ) == 1,
        ) {
            "Refresh envelope quarantine lost its credential-family CAS"
        }
    }

    @Query(
        """
        UPDATE sync_auth_state
        SET state = 'revoke_pending',
            updated_at_utc = :updatedAtUtc,
            failure_code = NULL
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND generation = :generation
          AND state = 'active'
          AND refresh_expires_at_epoch_ms > :nowEpochMs
          AND family_expires_at_epoch_ms > :nowEpochMs
          AND EXISTS (
            SELECT 1
            FROM local_identity_state AS identity
            JOIN local_installation AS installation
              ON installation.installation_id = identity.installation_id
             AND installation.server_device_id = sync_auth_state.device_id
            JOIN local_owner AS owner
              ON owner.installation_id = identity.installation_id
             AND owner.local_owner_id = identity.local_owner_id
             AND owner.server_person_id = sync_auth_state.person_id
            WHERE identity.singleton_id = 1
              AND identity.installation_id =
                  sync_auth_state.installation_id
              AND identity.local_owner_id =
                  sync_auth_state.local_owner_id
          )
          AND NOT EXISTS (
            SELECT 1
            FROM sync_auth_attempt AS enrollment
            WHERE enrollment.endpoint_id = 'auth_enroll'
              AND enrollment.state = 'dispatching'
              AND enrollment.installation_id =
                  sync_auth_state.installation_id
              AND enrollment.local_owner_id =
                  sync_auth_state.local_owner_id
              AND (
                enrollment.credential_epoch_id IS NULL
                OR (
                  enrollment.credential_epoch_id =
                      sync_auth_state.credential_epoch_id
                  AND enrollment.expected_device_id =
                      sync_auth_state.device_id
                  AND enrollment.expected_generation =
                      sync_auth_state.generation
                )
              )
          )
        """,
    )
    suspend fun claimRevokeFamily(
        credentialEpochId: String,
        deviceId: String,
        generation: Long,
        nowEpochMs: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_auth_state
        SET bootstrap_required = :bootstrapRequired,
            updated_at_utc = :updatedAtUtc
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND state IN ('active', 'refresh_in_flight')
        """,
    )
    suspend fun setBootstrapRequired(
        credentialEpochId: String,
        deviceId: String,
        bootstrapRequired: Boolean,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        UPDATE sync_auth_state
        SET state = 'revoked',
            refresh_token_ciphertext = NULL,
            refresh_token_nonce = NULL,
            refresh_token_key_alias = NULL,
            refresh_token_key_generation = NULL,
            refresh_token_aad_version = NULL,
            updated_at_utc = :updatedAtUtc,
            failure_code = NULL
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND generation = :generation
          AND state = 'revoke_pending'
        """,
    )
    suspend fun markExactFamilyRevoked(
        credentialEpochId: String,
        deviceId: String,
        generation: Long,
        updatedAtUtc: String,
    ): Int

    @Query(
        """
        DELETE FROM sync_auth_state
        WHERE singleton_id = 1
          AND credential_epoch_id = :credentialEpochId
          AND device_id = :deviceId
          AND generation = :generation
        """,
    )
    suspend fun deleteExactFamily(
        credentialEpochId: String,
        deviceId: String,
        generation: Long,
    ): Int

    @Query(
        """
        DELETE FROM sync_auth_state
        WHERE singleton_id = 1 AND credential_epoch_id = :credentialEpochId
        """,
    )
    suspend fun deleteIfEpoch(credentialEpochId: String): Int
}
