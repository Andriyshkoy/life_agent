package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncAuthDispatchBindingsApi35Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = LifeAgentDatabaseFactory.createInMemory(context)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `concurrent enrollment creates one identity and one content-free attempt`() = runTest {
        val store = SyncAuthPersistenceStore(database)
        val results = listOf(ENROLL_REQUEST_1, ENROLL_REQUEST_2).map { requestId ->
            async(Dispatchers.IO) {
                store.beginEnrollmentAttempt(
                    requestId = requestId,
                    createdAt = NOW,
                    hmacKeyGeneration = HMAC_GENERATION,
                )
            }
        }.awaitAll()

        assertEquals(1, results.count { it != null })
        val binding = requireNotNull(results.single { it != null })
        assertTrue(binding.toString().contains("redacted=true"))
        assertNull(binding.predecessorCredentialEpochId)
        assertNull(binding.expectedStableDeviceId)
        assertEquals(0L, binding.credentialFingerprintReferenceCount)
        assertNotNull(database.identityDao().findIdentity())
        assertEquals(1, tableCount("local_installation"))
        assertEquals(1, tableCount("local_owner"))
        assertEquals(1, tableCount("local_identity_state"))
        assertEquals(1, tableCount("sync_auth_attempt"))
        assertEquals(
            "dispatching",
            database.syncAuthDao().findAttempt(binding.requestId)?.state,
        )
    }

    @Test
    fun `duplicate enrollment request is a zero-write outcome`() = runTest {
        val store = SyncAuthPersistenceStore(database)
        val first = store.beginEnrollmentAttempt(
            requestId = ENROLL_REQUEST_1,
            createdAt = NOW,
            hmacKeyGeneration = HMAC_GENERATION,
        )
        val countsBefore = authAndIdentityCounts()

        val duplicate = store.beginEnrollmentAttempt(
            requestId = ENROLL_REQUEST_1,
            createdAt = NOW.plusSeconds(30),
            hmacKeyGeneration = HMAC_GENERATION,
        )

        assertNotNull(first)
        assertNull(duplicate)
        assertEquals(countsBefore, authAndIdentityCounts())
        assertEquals(NOW.toString(), database.syncAuthDao().findAttempt(ENROLL_REQUEST_1)?.updatedAtUtc)
    }

    @Test
    fun `restart snapshot claims exact refresh family once and owns envelope copies`() = runTest {
        val installed = seedActiveFamily()
        val restartedStore = SyncAuthPersistenceStore(database)
        val observed = requireNotNull(restartedStore.readAccessRecoveryBinding())

        assertEquals(EPOCH_ID, observed.credentialEpochId)
        assertEquals(DEVICE_ID, observed.deviceId)
        assertEquals(1L, observed.generation)
        assertEquals("AccessRecoveryBinding(redacted=true)", observed.toString())

        val claimed = requireNotNull(
            restartedStore.beginRefreshAttempt(
                requestId = REFRESH_REQUEST_1,
                expected = observed,
                now = NOW,
                hmacKeyGeneration = HMAC_GENERATION,
            ),
        )
        val ciphertext = claimed.copyRefreshTokenCiphertext()
        val nonce = claimed.copyRefreshTokenNonce()
        try {
            assertTrue(installed.refreshTokenCiphertext!!.contentEquals(ciphertext))
            assertTrue(installed.refreshTokenNonce!!.contentEquals(nonce))
            assertEquals(1L, claimed.durableRefreshTokenReferenceCount)
            assertEquals(2L, claimed.credentialFingerprintReferenceCount)
            assertEquals("RefreshAttemptBinding(redacted=true)", claimed.toString())
        } finally {
            ciphertext.fill(0)
            nonce.fill(0)
            claimed.close()
        }
        assertThrows(IllegalStateException::class.java) {
            claimed.copyRefreshTokenCiphertext()
        }

        val current = requireNotNull(database.syncAuthDao().findState())
        val attempt = requireNotNull(database.syncAuthDao().findAttempt(REFRESH_REQUEST_1))
        assertEquals("refresh_in_flight", current.state)
        assertEquals(EPOCH_ID, attempt.credentialEpochId)
        assertEquals(DEVICE_ID, attempt.expectedDeviceId)
        assertEquals(1L, attempt.expectedGeneration)

        val duplicate = restartedStore.beginRefreshAttempt(
            requestId = REFRESH_REQUEST_1,
            expected = observed,
            now = NOW.plusSeconds(1),
            hmacKeyGeneration = HMAC_GENERATION,
        )
        assertNull(duplicate)
        assertEquals(1, tableCount("sync_auth_attempt"))
    }

    @Test
    fun `concurrent refresh allows one exact claim and one attempt`() = runTest {
        seedActiveFamily()
        val store = SyncAuthPersistenceStore(database)
        val observed = requireNotNull(store.readAccessRecoveryBinding())

        val results = listOf(REFRESH_REQUEST_1, REFRESH_REQUEST_2).map { requestId ->
            async(Dispatchers.IO) {
                store.beginRefreshAttempt(
                    requestId = requestId,
                    expected = observed,
                    now = NOW,
                    hmacKeyGeneration = HMAC_GENERATION,
                )
            }
        }.awaitAll()

        assertEquals(1, results.count { it != null })
        results.filterNotNull().forEach(AutoCloseable::close)
        assertEquals(1, tableCount("sync_auth_attempt"))
        assertEquals("refresh_in_flight", database.syncAuthDao().findState()?.state)
    }

    @Test
    fun `expired or stale refresh binding performs no writes`() = runTest {
        seedActiveFamily()
        val store = SyncAuthPersistenceStore(database)
        val observed = requireNotNull(store.readAccessRecoveryBinding())
        val countsBefore = authCounts()

        val expired = store.beginRefreshAttempt(
            requestId = REFRESH_REQUEST_1,
            expected = observed,
            now = Instant.ofEpochMilli(observed.familyExpiresAtEpochMs),
            hmacKeyGeneration = HMAC_GENERATION,
        )
        val stale = store.beginRefreshAttempt(
            requestId = REFRESH_REQUEST_2,
            expected = AccessRecoveryBinding(
                credentialEpochId = observed.credentialEpochId,
                installationId = observed.installationId,
                localOwnerId = observed.localOwnerId,
                deviceId = observed.deviceId,
                personId = observed.personId,
                generation = observed.generation + 1,
                state = observed.state,
                accessExpiresAtEpochMs = observed.accessExpiresAtEpochMs,
                refreshExpiresAtEpochMs = observed.refreshExpiresAtEpochMs,
                familyExpiresAtUtc = observed.familyExpiresAtUtc,
                familyExpiresAtEpochMs = observed.familyExpiresAtEpochMs,
                bootstrapRequired = observed.bootstrapRequired,
            ),
            now = NOW,
            hmacKeyGeneration = HMAC_GENERATION,
        )

        assertNull(expired)
        assertNull(stale)
        assertEquals(countsBefore, authCounts())
        assertEquals("active", database.syncAuthDao().findState()?.state)
    }

    @Test
    fun `missing durable fingerprint proof rolls back refresh claim`() = runTest {
        seedActiveFamily(includeFingerprints = false)
        val store = SyncAuthPersistenceStore(database)
        val observed = requireNotNull(store.readAccessRecoveryBinding())
        val countsBefore = authCounts()

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.beginRefreshAttempt(
                    requestId = REFRESH_REQUEST_1,
                    expected = observed,
                    now = NOW,
                    hmacKeyGeneration = HMAC_GENERATION,
                )
            }
        }

        assertEquals(countsBefore, authCounts())
        assertEquals("active", database.syncAuthDao().findState()?.state)
    }

    private suspend fun seedActiveFamily(
        includeFingerprints: Boolean = true,
    ): SyncAuthStateEntity {
        val identity = database.withTransaction {
            LocalIdentityStore(database).ensureIdentityInCurrentTransaction(NOW)
        }
        database.identityDao().bindCurrentServerIdentity(
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = DEVICE_ID,
            personId = PERSON_ID,
        )
        val state = SyncAuthStateEntity(
            credentialEpochId = EPOCH_ID,
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = DEVICE_ID,
            personId = PERSON_ID,
            tokenType = "Bearer",
            refreshTokenCiphertext = byteArrayOf(1, 2, 3, 4),
            refreshTokenNonce = byteArrayOf(5, 6, 7),
            refreshTokenKeyAlias = "life_agent_refresh_test",
            refreshTokenKeyGeneration = 1,
            refreshTokenAadVersion = 1,
            accessExpiresAtUtc = ACCESS_EXPIRES.toString(),
            accessExpiresAtEpochMs = ACCESS_EXPIRES.toEpochMilli(),
            refreshExpiresAtUtc = REFRESH_EXPIRES.toString(),
            refreshExpiresAtEpochMs = REFRESH_EXPIRES.toEpochMilli(),
            familyExpiresAtUtc = FAMILY_EXPIRES.toString(),
            familyExpiresAtEpochMs = FAMILY_EXPIRES.toEpochMilli(),
            generation = 1,
            state = "active",
            bootstrapRequired = false,
            installedAtUtc = NOW.toString(),
            updatedAtUtc = NOW.toString(),
            failureCode = null,
        )
        database.withTransaction {
            if (includeFingerprints) {
                database.syncAuthDao().insertTokenFingerprint(
                    fingerprint("access", 11),
                )
                database.syncAuthDao().insertTokenFingerprint(
                    fingerprint("refresh", 22),
                )
            }
            database.syncAuthDao().insertStateRow(state)
        }
        return state
    }

    private fun fingerprint(kind: String, seed: Int) =
        SyncAuthTokenFingerprintEntity(
            credentialEpochId = EPOCH_ID,
            generation = 1,
            tokenKind = kind,
            tokenHmac = ByteArray(32) { seed.toByte() },
            hmacKeyGeneration = HMAC_GENERATION,
            createdAtUtc = NOW.toString(),
        )

    private fun authAndIdentityCounts() = listOf(
        tableCount("local_installation"),
        tableCount("local_owner"),
        tableCount("local_identity_state"),
        tableCount("sync_auth_state"),
        tableCount("sync_auth_attempt"),
        tableCount("sync_auth_token_fingerprint"),
    )

    private fun authCounts() = listOf(
        tableCount("sync_auth_state"),
        tableCount("sync_auth_attempt"),
        tableCount("sync_auth_token_fingerprint"),
    )

    private fun tableCount(tableName: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $tableName")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private companion object {
        const val HMAC_GENERATION = 1
        const val ENROLL_REQUEST_1 = "00000000-0000-4000-8000-000000001001"
        const val ENROLL_REQUEST_2 = "00000000-0000-4000-8000-000000001002"
        const val REFRESH_REQUEST_1 = "00000000-0000-4000-8000-000000001101"
        const val REFRESH_REQUEST_2 = "00000000-0000-4000-8000-000000001102"
        const val EPOCH_ID = "00000000-0000-4000-8000-000000001201"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000001301"
        const val PERSON_ID = "00000000-0000-4000-8000-000000001401"
        val NOW: Instant = Instant.parse("2026-08-03T08:00:00Z")
        val ACCESS_EXPIRES: Instant = NOW.plusSeconds(3_600)
        val REFRESH_EXPIRES: Instant = NOW.plusSeconds(86_400)
        val FAMILY_EXPIRES: Instant = NOW.plusSeconds(172_800)
    }
}
