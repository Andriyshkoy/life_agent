package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import ru.andriyshkoy.lifeagent.data.local.db.BootstrapIntentPersistence
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthAttemptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity

internal class SyncM2PersistenceFixture(
    private val context: Context,
    private val label: String,
) {
    val databaseName: String = "$label-${UUID.randomUUID()}.db"
    var database: LifeAgentDatabase = openDatabase()
        private set

    suspend fun seedIdentity(
        installationId: String = INSTALLATION_ID,
        localOwnerId: String = LOCAL_OWNER_ID,
        deviceId: String? = null,
        personId: String? = null,
    ) {
        database.identityDao().insertInstallation(
            LocalInstallationEntity(
                installationId = installationId,
                createdAtUtc = BASE_UTC,
                serverDeviceId = deviceId,
            ),
        )
        database.identityDao().insertOwner(
            LocalOwnerEntity(
                localOwnerId = localOwnerId,
                installationId = installationId,
                createdAtUtc = BASE_UTC,
                serverPersonId = personId,
            ),
        )
        database.identityDao().insertIdentityState(
            LocalIdentityStateEntity(
                installationId = installationId,
                localOwnerId = localOwnerId,
                selectedAtUtc = BASE_UTC,
            ),
        )
    }

    suspend fun bindIdentity(
        deviceId: String = DEVICE_ID,
        personId: String = PERSON_ID,
    ) {
        database.identityDao().bindCurrentServerIdentity(
            installationId = INSTALLATION_ID,
            localOwnerId = LOCAL_OWNER_ID,
            deviceId = deviceId,
            personId = personId,
        )
    }

    suspend fun installActiveAuth(
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        personId: String = PERSON_ID,
        generation: Long = 1,
        bootstrapRequired: Boolean = false,
        accessExpiresAtEpochMs: Long = ACCESS_EXPIRY_MS,
        refreshExpiresAtEpochMs: Long = REFRESH_EXPIRY_MS,
        familyExpiresAtEpochMs: Long = FAMILY_EXPIRY_MS,
    ) {
        database.syncAuthDao().insertStateRow(
            authState(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                personId = personId,
                generation = generation,
                bootstrapRequired = bootstrapRequired,
                accessExpiresAtEpochMs = accessExpiresAtEpochMs,
                refreshExpiresAtEpochMs = refreshExpiresAtEpochMs,
                familyExpiresAtEpochMs = familyExpiresAtEpochMs,
            ),
        )
    }

    fun authState(
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        personId: String = PERSON_ID,
        generation: Long = 1,
        state: String = "active",
        bootstrapRequired: Boolean = false,
        accessExpiresAtEpochMs: Long = ACCESS_EXPIRY_MS,
        refreshExpiresAtEpochMs: Long = REFRESH_EXPIRY_MS,
        familyExpiresAtEpochMs: Long = FAMILY_EXPIRY_MS,
        updatedAtUtc: String = BASE_UTC,
    ): SyncAuthStateEntity {
        val usable = state in setOf("active", "refresh_in_flight", "revoke_pending")
        return SyncAuthStateEntity(
            credentialEpochId = credentialEpochId,
            installationId = INSTALLATION_ID,
            localOwnerId = LOCAL_OWNER_ID,
            deviceId = deviceId,
            personId = personId,
            tokenType = "Bearer",
            refreshTokenCiphertext = if (usable) byteArrayOf(1, 2, 3) else null,
            refreshTokenNonce = if (usable) ByteArray(12) { 4 } else null,
            refreshTokenKeyAlias = if (usable) "fixture-refresh-key" else null,
            refreshTokenKeyGeneration = if (usable) 1 else null,
            refreshTokenAadVersion = if (usable) 1 else null,
            accessExpiresAtUtc = Instant.ofEpochMilli(accessExpiresAtEpochMs).toString(),
            accessExpiresAtEpochMs = accessExpiresAtEpochMs,
            refreshExpiresAtUtc = Instant.ofEpochMilli(refreshExpiresAtEpochMs).toString(),
            refreshExpiresAtEpochMs = refreshExpiresAtEpochMs,
            familyExpiresAtUtc = Instant.ofEpochMilli(familyExpiresAtEpochMs).toString(),
            familyExpiresAtEpochMs = familyExpiresAtEpochMs,
            generation = generation,
            state = state,
            bootstrapRequired = bootstrapRequired,
            installedAtUtc = BASE_UTC,
            updatedAtUtc = updatedAtUtc,
            failureCode = null,
        )
    }

    suspend fun seedIncrementalStream(
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
    ) {
        database.syncReplicaDao().insertStreamState(
            streamState(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
            ),
        )
    }

    fun streamState(
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        bootstrapRequired: Boolean = false,
    ) = SyncStreamStateEntity(
        credentialEpochId = credentialEpochId,
        deviceId = deviceId,
        phase = if (bootstrapRequired) "bootstrap_required" else "incremental",
        bootstrapRequired = bootstrapRequired,
        appliedCursor = if (bootstrapRequired) null else "fixture-cursor",
        lastAppliedServerSequence = 0,
        highWatermarkHint = null,
        integrityErrorCode = null,
        updatedAtUtc = BASE_UTC,
    )

    fun request(
        endpointId: String,
        requestIdentity: String = UUID.randomUUID().toString(),
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        state: String = "ready",
        accessGenerationUsed: Long? = 1,
        attemptCount: Int = 0,
        activeAttemptId: String? = null,
        bootstrapId: String? = null,
        deadlineAtEpochMs: Long = DEADLINE_MS,
        nextAttemptAtEpochMs: Long? = null,
        leaseExpiresAtEpochMs: Long? = null,
        originalRetryCount: Int = 0,
        pageSize: Int = 100,
    ): SyncHttpRequestEntity {
        val rawBody = if (endpointId == "sync_bootstrap") {
            requireNotNull(bootstrapId)
            """
            {"protocol_version":"1.0.0","message_type":"bootstrap_request","request_id":"$requestIdentity","bootstrap_id":"$bootstrapId","device_id":"$deviceId","page_size":$pageSize,"page_cursor":null}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        } else {
            """{"request_id":"$requestIdentity"}"""
                .toByteArray(StandardCharsets.UTF_8)
        }
        return SyncHttpRequestEntity(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            protocolVersion = "1.0.0",
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            idempotencyKey = if (endpointId == "sync_push") requestIdentity else null,
            rawRequestBody = rawBody,
            rawBodyHmac = sha256(rawBody),
            hmacKeyGeneration = 1,
            state = state,
            attemptCount = attemptCount,
            attemptBudget = 8,
            deadlineAtEpochMs = deadlineAtEpochMs,
            nextAttemptAtEpochMs = nextAttemptAtEpochMs,
            lastAttemptAtEpochMs = if (attemptCount > 0) NOW_MS else null,
            leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
            activeAttemptId = activeAttemptId,
            accessGenerationUsed = accessGenerationUsed,
            originalRetryCount = originalRetryCount,
            terminalHttpStatus = null,
            exactResponseBody = null,
            responseSha256 = null,
            terminalAtUtc = null,
            terminalErrorCode = null,
            createdAtUtc = BASE_UTC,
            updatedAtUtc = BASE_UTC,
        )
    }

    fun sealedRevokeRequest(
        requestIdentity: String = UUID.randomUUID().toString(),
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        generation: Long = 1,
    ): SyncHttpRequestEntity {
        val ciphertext = byteArrayOf(9, 8, 7)
        return SyncHttpRequestEntity(
            endpointId = "auth_revoke",
            requestIdentity = requestIdentity,
            protocolVersion = "1.0.0",
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            idempotencyKey = null,
            bodyStorageKind = SyncHttpRequestEntity.BODY_STORAGE_KEYSTORE_AEAD,
            rawRequestBody = null,
            sealedBodyCiphertext = ciphertext,
            sealedBodyNonce = ByteArray(12) { 6 },
            sealedBodyKeyAlias = "fixture-revoke-key",
            sealedBodyKeyGeneration = 1,
            sealedBodyAadVersion = 1,
            requestBodyOctetCount = 64,
            rawBodyHmac = sha256(ciphertext),
            hmacKeyGeneration = 1,
            state = "ready",
            attemptBudget = 8,
            deadlineAtEpochMs = DEADLINE_MS,
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            leaseExpiresAtEpochMs = null,
            accessGenerationUsed = generation,
            terminalHttpStatus = null,
            exactResponseBody = null,
            responseSha256 = null,
            terminalAtUtc = null,
            terminalErrorCode = null,
            createdAtUtc = BASE_UTC,
            updatedAtUtc = BASE_UTC,
        )
    }

    fun enrollmentAttempt(
        requestId: String = UUID.randomUUID().toString(),
        credentialEpochId: String? = null,
        deviceId: String? = null,
        generation: Long? = null,
    ) = SyncAuthAttemptEntity(
        requestId = requestId,
        endpointId = "auth_enroll",
        installationId = INSTALLATION_ID,
        localOwnerId = LOCAL_OWNER_ID,
        credentialEpochId = credentialEpochId,
        expectedDeviceId = deviceId,
        expectedGeneration = generation,
        state = "dispatching",
        createdAtUtc = BASE_UTC,
        updatedAtUtc = BASE_UTC,
        lastErrorCode = null,
    )

    fun refreshAttempt(
        requestId: String = UUID.randomUUID().toString(),
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        generation: Long = 1,
    ) = SyncAuthAttemptEntity(
        requestId = requestId,
        endpointId = "auth_refresh",
        installationId = INSTALLATION_ID,
        localOwnerId = LOCAL_OWNER_ID,
        credentialEpochId = credentialEpochId,
        expectedDeviceId = deviceId,
        expectedGeneration = generation,
        state = "dispatching",
        createdAtUtc = BASE_UTC,
        updatedAtUtc = BASE_UTC,
        lastErrorCode = null,
    )

    fun fingerprint(
        credentialEpochId: String,
        generation: Long,
        tokenKind: String,
        seed: Byte,
    ) = SyncAuthTokenFingerprintEntity(
        credentialEpochId = credentialEpochId,
        generation = generation,
        tokenKind = tokenKind,
        tokenHmac = ByteArray(32) { seed },
        hmacKeyGeneration = 1,
        createdAtUtc = BASE_UTC,
    )

    fun bootstrapIntent(
        credentialEpochId: String = EPOCH_ID,
        deviceId: String = DEVICE_ID,
        generation: Long = 1,
        bootstrapId: String = UUID.randomUUID().toString(),
        requestId: String = UUID.randomUUID().toString(),
        pageSize: Int = 100,
    ) = BootstrapIntentPersistence(
        session = SyncBootstrapSessionEntity(
            bootstrapId = bootstrapId,
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            state = "staging",
            activeSlot = 1,
            snapshotId = null,
            nextPageCursor = null,
            candidateIncrementalCursor = null,
            nextPageIndex = 0,
            lastStagedServerSequence = null,
            stagedPageCount = 0,
            stagedBodyBytes = 0,
            createdAtUtc = BASE_UTC,
            updatedAtUtc = BASE_UTC,
        ),
        firstRequest = request(
            endpointId = "sync_bootstrap",
            requestIdentity = requestId,
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            accessGenerationUsed = generation,
            bootstrapId = bootstrapId,
            pageSize = pageSize,
        ),
    )

    fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    fun reopen() {
        database.close()
        database = openDatabase()
    }

    private fun openDatabase(): LifeAgentDatabase =
        LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
            databaseName = databaseName,
        )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val INSTALLATION_ID = "a1000000-0000-4000-8000-000000000001"
        const val LOCAL_OWNER_ID = "a2000000-0000-4000-8000-000000000001"
        const val EPOCH_ID = "a3000000-0000-4000-8000-000000000001"
        const val DEVICE_ID = "a4000000-0000-4000-8000-000000000001"
        const val PERSON_ID = "a5000000-0000-4000-8000-000000000001"
        const val BASE_UTC = "2030-01-01T00:00:00Z"
        val NOW_MS: Long = Instant.parse(BASE_UTC).toEpochMilli()
        val ACCESS_EXPIRY_MS: Long =
            Instant.parse("2030-02-01T00:00:00Z").toEpochMilli()
        val REFRESH_EXPIRY_MS: Long =
            Instant.parse("2030-03-01T00:00:00Z").toEpochMilli()
        val FAMILY_EXPIRY_MS: Long =
            Instant.parse("2030-04-01T00:00:00Z").toEpochMilli()
        val DEADLINE_MS: Long =
            Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
    }
}
