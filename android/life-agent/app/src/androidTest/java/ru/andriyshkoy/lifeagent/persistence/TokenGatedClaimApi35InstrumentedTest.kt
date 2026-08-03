package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDispatchRequestClaim
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestStore
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenKey
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenVault
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class TokenGatedClaimApi35InstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: SyncM2PersistenceFixture
    private lateinit var hmacAlias: String
    private lateinit var markerRelativePath: String

    @Before
    fun setUp() {
        val testId = UUID.randomUUID().toString()
        hmacAlias = "life_agent_test_token_gate_$testId"
        markerRelativePath = "crypto-tests/token-gate-$testId.marker"
        fixture = SyncM2PersistenceFixture(context, "token-gate-$testId")
    }

    @After
    fun tearDown() {
        runCatching(fixture::close)
        listOf("", ".bak", ".new").forEach { suffix ->
            File(markerFile().path + suffix).delete()
        }
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(hmacAlias)) deleteEntry(hmacAlias)
        }
    }

    @Test
    fun onlyExactCurrentGenerationTokenCanConsumeAnAttempt() = runBlocking {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth(generation = 1)
        fixture.seedIncrementalStream()
        val request = PullRequest(
            requestId = UUID.randomUUID().toString(),
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            cursor = DEFAULT_CURSOR,
            pageSize = 100,
        )
        val store = ProtectedSyncRequestStore(context, fixture.database, keyring())
        store.persistPull(request, persistence(accessGeneration = 1))
        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_auth_state
            SET generation = 2, updated_at_utc = ?
            WHERE singleton_id = 1 AND generation = 1
            """.trimIndent(),
            arrayOf(SyncM2PersistenceFixture.BASE_UTC),
        )

        val vault = AccessTokenVault()
        val previousGenerationToken = WipeableSecret.ascii(PREVIOUS_GENERATION_TOKEN)
        val currentGenerationToken = WipeableSecret.ascii(CURRENT_GENERATION_TOKEN)
        try {
            vault.replace(
                AccessTokenKey(SyncM2PersistenceFixture.EPOCH_ID, 1),
                previousGenerationToken,
            )

            val withoutExactToken = store.verifyAndClaimForDispatch(
                endpointId = "sync_pull",
                requestIdentity = request.requestId,
                attemptId = UUID.randomUUID().toString(),
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                accessTokenVault = vault,
            )

            assertTrue(withoutExactToken is ProtectedDispatchRequestClaim.NotClaimed)
            val untouched = requireNotNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_pull",
                    request.requestId,
                ),
            )
            assertEquals("ready", untouched.state)
            assertEquals(0, untouched.attemptCount)
            assertNull(untouched.activeAttemptId)
            assertNull(untouched.leaseExpiresAtEpochMs)

            vault.replace(
                AccessTokenKey(SyncM2PersistenceFixture.EPOCH_ID, 2),
                currentGenerationToken,
            )
            val exactAttemptId = UUID.randomUUID().toString()
            val withExactToken = store.verifyAndClaimForDispatch(
                endpointId = "sync_pull",
                requestIdentity = request.requestId,
                attemptId = exactAttemptId,
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                accessTokenVault = vault,
            )

            assertTrue(withExactToken is ProtectedDispatchRequestClaim.Claimed)
            val claimed = withExactToken as ProtectedDispatchRequestClaim.Claimed
            val ownedToken = requireNotNull(claimed.accessTokenClaim)
            assertEquals(2L, claimed.requestClaim.accessGenerationUsed)
            assertEquals(
                AccessTokenKey(SyncM2PersistenceFixture.EPOCH_ID, 2),
                ownedToken.key,
            )
            ownedToken.bearerAccessToken.useBytes { bytes ->
                assertEquals(
                    CURRENT_GENERATION_TOKEN,
                    bytes.toString(StandardCharsets.US_ASCII),
                )
            }
            val diagnostics = claimed.toString()
            assertEquals(
                "ProtectedDispatchRequestClaim.Claimed(redacted=true)",
                diagnostics,
            )
            assertFalse(diagnostics.contains(request.requestId))
            assertFalse(diagnostics.contains(CURRENT_GENERATION_TOKEN))

            val persistedClaim = requireNotNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_pull",
                    request.requestId,
                ),
            )
            assertEquals("sending", persistedClaim.state)
            assertEquals(1, persistedClaim.attemptCount)
            assertEquals(exactAttemptId, persistedClaim.activeAttemptId)
            assertEquals(2L, persistedClaim.accessGenerationUsed)

            val wrongAuthority = requireNotNull(
                vault.claim(AccessTokenKey(SyncM2PersistenceFixture.EPOCH_ID, 1)),
            )
            try {
                val mismatch = assertThrows(IllegalArgumentException::class.java) {
                    ProtectedDispatchRequestClaim.Claimed(
                        requestClaim = claimed.requestClaim,
                        accessTokenClaim = wrongAuthority,
                    )
                }
                assertFalse(mismatch.message.orEmpty().contains(request.requestId))
                assertFalse(
                    mismatch.message.orEmpty().contains(SyncM2PersistenceFixture.EPOCH_ID),
                )
                assertFalse(mismatch.message.orEmpty().contains(PREVIOUS_GENERATION_TOKEN))
            } finally {
                wrongAuthority.close()
            }

            claimed.close()
            claimed.close()
            assertThrows(IllegalStateException::class.java) {
                ownedToken.bearerAccessToken.copyBytes()
            }
            assertThrows(IllegalStateException::class.java) {
                claimed.requestClaim.request.consumeBody { Unit }
            }
        } finally {
            vault.close()
        }
    }

    private fun keyring() = KeystoreRequestBodyHmacKeyring(
        context = context,
        keyAlias = hmacAlias,
        markerRelativePath = markerRelativePath,
    )

    private fun persistence(accessGeneration: Long) = NewDurableRequestPersistence(
        localCredentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        accessGenerationUsed = accessGeneration,
        attemptBudget = 8,
        deadlineAtEpochMs = SyncM2PersistenceFixture.DEADLINE_MS,
        createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
    )

    private fun markerFile() = File(context.noBackupFilesDir, markerRelativePath)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_CURSOR = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFI"
        const val LEASE_MS = 60_000L
        const val PREVIOUS_GENERATION_TOKEN =
            "laa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CURRENT_GENERATION_TOKEN =
            "laa_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBQ"
    }
}
