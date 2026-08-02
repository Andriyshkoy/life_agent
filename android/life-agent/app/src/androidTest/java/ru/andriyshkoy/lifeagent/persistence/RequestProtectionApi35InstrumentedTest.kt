package ru.andriyshkoy.lifeagent.persistence

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedRequestClaim
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestStore
import ru.andriyshkoy.lifeagent.data.local.db.RequestBodyFailure
import ru.andriyshkoy.lifeagent.data.security.DurableRequestBodyHmacBinding
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestProtector
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestVerifier
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.security.RequestBodyKeyUnavailableException
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.M2WireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class RequestProtectionApi35InstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<SyncM2PersistenceFixture>()
    private lateinit var testId: String
    private lateinit var hmacAlias: String
    private lateinit var markerRelativePath: String

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        hmacAlias = "life_agent_test_request_hmac_$testId"
        markerRelativePath = "crypto-tests/request-hmac-$testId.marker"
    }

    @After
    fun tearDown() {
        fixtures.asReversed().forEach { fixture ->
            runCatching(fixture::close)
        }
        fixtures.clear()
        deleteMarkerArtifacts()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(hmacAlias)) deleteEntry(hmacAlias)
        }
    }

    @Test
    fun hmacKeyIsNonExportableAndPersistsAcrossKeyringReopen() {
        val body = "api35-keystore-persistence".toByteArray(StandardCharsets.UTF_8)
        val binding = hmacBinding()
        val first = keyring()
        first.provisionCurrentKey(durableReferenceCount = 0)
        val tag = first.signNew(binding, body)

        try {
            val installed = KeyStore.getInstance(ANDROID_KEYSTORE).run {
                load(null)
                getKey(hmacAlias, null)
            }
            assertNull("Android Keystore HMAC material must not be exportable", installed.encoded)

            keyring().verifyExisting(binding, body, tag)
            assertTrue(markerFile().isFile)
        } finally {
            tag.fill(0)
            body.fill(0)
        }
    }

    @Test
    fun missingAliasVerificationNeverRecreatesIt() {
        val body = "api35-missing-alias".toByteArray(StandardCharsets.UTF_8)
        val binding = hmacBinding()
        val ring = keyring()
        ring.provisionCurrentKey(durableReferenceCount = 0)
        val tag = ring.signNew(binding, body)
        deleteKeystoreAlias()

        try {
            assertThrows(RequestBodyKeyUnavailableException::class.java) {
                keyring().verifyExisting(binding, body, tag)
            }
            assertFalse(keystoreContainsAlias())
            assertTrue("The durable marker must not be erased on key loss", markerFile().isFile)
        } finally {
            tag.fill(0)
            body.fill(0)
        }
    }

    @Test
    fun markerLossWithDurableReferenceFailsEvenWhenAliasSurvives() {
        val body = "api35-marker-loss".toByteArray(StandardCharsets.UTF_8)
        val binding = hmacBinding()
        val ring = keyring()
        ring.provisionCurrentKey(durableReferenceCount = 0)
        val tag = ring.signNew(binding, body)
        deleteMarkerArtifacts()

        try {
            assertTrue(keystoreContainsAlias())
            assertThrows(RequestBodyKeyUnavailableException::class.java) {
                keyring().provisionCurrentKey(durableReferenceCount = 1)
            }
            assertThrows(RequestBodyKeyUnavailableException::class.java) {
                keyring().verifyExisting(binding, body, tag)
            }
            assertTrue(
                "Provisioning failure must not replace the surviving alias",
                keystoreContainsAlias(),
            )
            assertFalse(markerFile().exists())
        } finally {
            tag.fill(0)
            body.fill(0)
        }
    }

    @Test
    fun markerProofRejectsAliasReplacementAndUnexpectedEntryType() {
        val body = "api35-alias-replacement".toByteArray(StandardCharsets.UTF_8)
        val binding = hmacBinding()
        val ring = keyring()
        ring.provisionCurrentKey(durableReferenceCount = 0)
        val tag = ring.signNew(binding, body)

        try {
            deleteKeystoreAlias()
            generateHmacAlias()
            assertThrows(RequestBodyKeyUnavailableException::class.java) {
                keyring().verifyExisting(binding, body, tag)
            }
            assertTrue(keystoreContainsAlias())

            deleteKeystoreAlias()
            deleteMarkerArtifacts()
            generateRsaAlias()
            assertThrows(RequestBodyKeyUnavailableException::class.java) {
                keyring().provisionCurrentKey(durableReferenceCount = 0)
            }
            val entry = KeyStore.getInstance(ANDROID_KEYSTORE).run {
                load(null)
                getEntry(hmacAlias, null)
            }
            assertTrue(entry is KeyStore.PrivateKeyEntry)
        } finally {
            tag.fill(0)
            body.fill(0)
        }
    }

    @Test
    fun protectedPullReopensWithExactBytesAndIsConsumableOnlyOnce() = runBlocking {
        val fixture = newIncrementalFixture("protected-pull-reopen")
        val request = PullRequest(
            requestId = UUID.randomUUID().toString(),
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            cursor = DEFAULT_CURSOR,
            pageSize = 100,
        )
        val exactBody = exactBody(request)
        val store = ProtectedSyncRequestStore(context, fixture.database, keyring())
        store.persistPull(request, persistence())
        val beforeReopen = requireNotNull(
            fixture.database.syncTransportDao().findRequest(
                "sync_pull",
                request.requestId,
            ),
        )
        val retainedTag = beforeReopen.rawBodyHmac.copyOf()

        try {
            assertArrayEquals(exactBody, beforeReopen.rawRequestBody)
            fixture.reopen()
            val reopened = requireNotNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_pull",
                    request.requestId,
                ),
            )
            assertArrayEquals(exactBody, reopened.rawRequestBody)
            assertArrayEquals(retainedTag, reopened.rawBodyHmac)
            assertClaimedExactOnce(
                store = ProtectedSyncRequestStore(context, fixture.database, keyring()),
                endpointId = "sync_pull",
                requestIdentity = request.requestId,
                exactBody = exactBody,
            )
        } finally {
            retainedTag.fill(0)
            exactBody.fill(0)
        }
    }

    @Test
    fun protectedBootstrapReopensWithExactBytesAndIsConsumableOnlyOnce() = runBlocking {
        val fixture = newBootstrapFixture("protected-bootstrap-reopen")
        val session = requireNotNull(
            fixture.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
        )
        val request = BootstrapRequest(
            requestId = UUID.randomUUID().toString(),
            bootstrapId = session.bootstrapId,
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            pageSize = 100,
            pageCursor = null,
        )
        val exactBody = exactBody(request)
        persistDirect(fixture, request, persistence())
        val beforeReopen = requireNotNull(
            fixture.database.syncTransportDao().findRequest(
                "sync_bootstrap",
                request.requestId,
            ),
        )
        val retainedTag = beforeReopen.rawBodyHmac.copyOf()

        try {
            assertArrayEquals(exactBody, beforeReopen.rawRequestBody)
            fixture.reopen()
            val reopened = requireNotNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_bootstrap",
                    request.requestId,
                ),
            )
            assertArrayEquals(exactBody, reopened.rawRequestBody)
            assertArrayEquals(retainedTag, reopened.rawBodyHmac)
            assertClaimedExactOnce(
                store = ProtectedSyncRequestStore(context, fixture.database, keyring()),
                endpointId = "sync_bootstrap",
                requestIdentity = request.requestId,
                exactBody = exactBody,
            )
        } finally {
            retainedTag.fill(0)
            exactBody.fill(0)
        }
    }

    @Test
    fun malformedHmacLengthsReachQueueHeadAndQuarantineWithoutAttempt() = runBlocking {
        listOf(0, 31, 33).forEach { malformedLength ->
            val sentinelCursor = cursorForSeed(malformedLength + 1)
            val fixture = newIncrementalFixture(
                label = "malformed-hmac-$malformedLength",
                cursor = sentinelCursor,
            )
            val malformedId = "10000000-0000-4000-8000-000000000001"
            val healthyId = "f0000000-0000-4000-8000-000000000001"
            val malformed = PullRequest(
                requestId = malformedId,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                cursor = sentinelCursor,
                pageSize = 100,
            )
            val healthy = PullRequest(
                requestId = healthyId,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                cursor = sentinelCursor,
                pageSize = 100,
            )
            val exactMalformedBody = exactBody(malformed)
            persistDirect(fixture, malformed, persistence())
            persistDirect(fixture, healthy, persistence())

            fixture.database.openHelper.writableDatabase.apply {
                execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
                execSQL(
                    """
                    UPDATE sync_http_request
                    SET raw_body_hmac = ?
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(ByteArray(malformedLength), malformedId),
                )
            }
            fixture.reopen()

            try {
                assertEquals(
                    listOf(malformedId),
                    fixture.database.syncTransportDao()
                        .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 1)
                        .map { it.requestIdentity },
                )
                val claim = ProtectedSyncRequestStore(context, fixture.database, keyring())
                    .verifyAndClaim(
                        endpointId = "sync_pull",
                        requestIdentity = malformedId,
                        attemptId = UUID.randomUUID().toString(),
                        attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                        updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                    )
                assertTrue(claim is ProtectedRequestClaim.IntegrityFailure)
                assertEquals(
                    RequestBodyFailure.METADATA_INVALID,
                    (claim as ProtectedRequestClaim.IntegrityFailure).failure,
                )

                val quarantined = requireNotNull(
                    fixture.database.syncTransportDao().findRequest(
                        "sync_pull",
                        malformedId,
                    ),
                )
                assertEquals("integrity_failure", quarantined.state)
                assertEquals("request_body_metadata_invalid", quarantined.terminalErrorCode)
                assertEquals(0, quarantined.attemptCount)
                assertNull(quarantined.activeAttemptId)
                assertEquals(32, quarantined.rawBodyHmac.size)
                assertTrue(quarantined.rawBodyHmac.all { it == 0.toByte() })
                assertArrayEquals(exactMalformedBody, quarantined.rawRequestBody)
                assertEquals(
                    "integrity_halted",
                    fixture.database.syncReplicaDao().findStreamState()?.phase,
                )
                assertEquals(
                    "request_body_metadata_invalid",
                    fixture.database.syncReplicaDao().findStreamState()?.integrityErrorCode,
                )
                assertTrue(
                    "A quarantined queue head must not remain hot",
                    fixture.database.syncTransportDao()
                        .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                        .isEmpty(),
                )
                val untouchedFollower = requireNotNull(
                    fixture.database.syncTransportDao().findRequest(
                        "sync_pull",
                        healthyId,
                    ),
                )
                assertEquals("ready", untouchedFollower.state)
                assertEquals(0, untouchedFollower.attemptCount)
            } finally {
                exactMalformedBody.fill(0)
            }
        }
    }

    @Test
    fun exactMetadataTamperQuarantinesBeforeAttemptAndPreservesBody() = runBlocking {
        val fixture = newIncrementalFixture("metadata-tamper")
        val request = PullRequest(
            requestId = UUID.randomUUID().toString(),
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            cursor = DEFAULT_CURSOR,
            pageSize = 100,
        )
        val exactBody = exactBody(request)
        val store = ProtectedSyncRequestStore(context, fixture.database, keyring())
        store.persistPull(request, persistence())
        val original = requireNotNull(
            fixture.database.syncTransportDao().findRequest(
                "sync_pull",
                request.requestId,
            ),
        )
        val originalHmac = original.rawBodyHmac.copyOf()

        try {
            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET protocol_version = '1.0.1'
                WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                """.trimIndent(),
                arrayOf(request.requestId),
            )
            fixture.reopen()
            assertEquals(
                listOf(request.requestId),
                fixture.database.syncTransportDao()
                    .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 1)
                    .map { it.requestIdentity },
            )

            val claim = ProtectedSyncRequestStore(context, fixture.database, keyring())
                .verifyAndClaim(
                    endpointId = "sync_pull",
                    requestIdentity = request.requestId,
                    attemptId = UUID.randomUUID().toString(),
                    attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                    updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                )
            assertTrue(claim is ProtectedRequestClaim.IntegrityFailure)
            assertEquals(
                RequestBodyFailure.METADATA_INVALID,
                (claim as ProtectedRequestClaim.IntegrityFailure).failure,
            )

            val quarantined = requireNotNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_pull",
                    request.requestId,
                ),
            )
            assertEquals("integrity_failure", quarantined.state)
            assertEquals("request_body_metadata_invalid", quarantined.terminalErrorCode)
            assertEquals(0, quarantined.attemptCount)
            assertNull(quarantined.activeAttemptId)
            assertNull(quarantined.leaseExpiresAtEpochMs)
            assertArrayEquals(exactBody, quarantined.rawRequestBody)
            assertArrayEquals(originalHmac, quarantined.rawBodyHmac)
            assertEquals(
                "request_body_metadata_invalid",
                fixture.database.syncReplicaDao().findStreamState()?.integrityErrorCode,
            )
            assertTrue(
                fixture.database.syncTransportDao()
                    .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                    .isEmpty(),
            )
        } finally {
            originalHmac.fill(0)
            exactBody.fill(0)
        }
    }

    @Test
    fun hmacValidBootstrapSessionMismatchIsQuarantinedBeforeClaim() = runBlocking {
        val fixture = newBootstrapFixture("bootstrap-session-mismatch")
        val active = requireNotNull(
            fixture.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
        )
        val request = BootstrapRequest(
            requestId = UUID.randomUUID().toString(),
            bootstrapId = UUID.randomUUID().toString(),
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            pageSize = 100,
            pageCursor = active.nextPageCursor,
        )
        persistDirect(fixture, request, persistence())
        val stored = requireNotNull(
            fixture.database.syncTransportDao().findRequest(
                "sync_bootstrap",
                request.requestId,
            ),
        )

        // This verifies the body/tag pair without consulting bootstrap state;
        // the protected store must then reject the signed membership mismatch.
        DurableSyncRequestVerifier(context, keyring()).loadVerified(stored).close()
        assertEquals(
            listOf(request.requestId),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 1)
                .map { it.requestIdentity },
        )

        val claim = ProtectedSyncRequestStore(context, fixture.database, keyring())
            .verifyAndClaim(
                endpointId = "sync_bootstrap",
                requestIdentity = request.requestId,
                attemptId = UUID.randomUUID().toString(),
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
        assertTrue(claim is ProtectedRequestClaim.IntegrityFailure)
        assertEquals(
            RequestBodyFailure.METADATA_INVALID,
            (claim as ProtectedRequestClaim.IntegrityFailure).failure,
        )
        val quarantined = requireNotNull(
            fixture.database.syncTransportDao().findRequest(
                "sync_bootstrap",
                request.requestId,
            ),
        )
        assertEquals("integrity_failure", quarantined.state)
        assertEquals(0, quarantined.attemptCount)
        assertEquals(
            "request_body_metadata_invalid",
            fixture.database.syncReplicaDao().findStreamState()?.integrityErrorCode,
        )
    }

    @Test
    fun requestProtectedAtGenerationNClaimsWithCurrentGenerationNPlusOne() = runBlocking {
        val fixture = newIncrementalFixture("generation-roll-forward")
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

        val claim = store.verifyAndClaim(
            endpointId = "sync_pull",
            requestIdentity = request.requestId,
            attemptId = UUID.randomUUID().toString(),
            attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
            leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        assertTrue(claim is ProtectedRequestClaim.Claimed)
        (claim as ProtectedRequestClaim.Claimed).request.close()
        val claimed = requireNotNull(
            fixture.database.syncTransportDao().findRequest(
                "sync_pull",
                request.requestId,
            ),
        )
        assertEquals("sending", claimed.state)
        assertEquals(1, claimed.attemptCount)
        assertEquals(2L, claimed.accessGenerationUsed)
        assertEquals(1, claimed.hmacKeyGeneration)
    }

    private fun hmacBinding() = DurableRequestBodyHmacBinding(
        endpointId = "sync_pull",
        protocolVersion = "1.0.0",
        localCredentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        deviceId = SyncM2PersistenceFixture.DEVICE_ID,
        keyEpoch = 1u,
    )

    private fun keyring() = KeystoreRequestBodyHmacKeyring(
        context = context,
        keyAlias = hmacAlias,
        markerRelativePath = markerRelativePath,
    )

    private fun persistence(
        accessGeneration: Long = 1,
    ) = NewDurableRequestPersistence(
        localCredentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        accessGenerationUsed = accessGeneration,
        attemptBudget = 8,
        deadlineAtEpochMs = SyncM2PersistenceFixture.DEADLINE_MS,
        createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
    )

    private suspend fun newIncrementalFixture(
        label: String,
        cursor: String = DEFAULT_CURSOR,
    ): SyncM2PersistenceFixture = newFixture(label).also { fixture ->
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState().copy(appliedCursor = cursor),
        )
    }

    private suspend fun newBootstrapFixture(label: String): SyncM2PersistenceFixture =
        newFixture(label).also { fixture ->
            fixture.seedIdentity(
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                personId = SyncM2PersistenceFixture.PERSON_ID,
            )
            fixture.installActiveAuth(bootstrapRequired = true)
            fixture.database.syncReplicaDao().insertStreamState(
                fixture.streamState(bootstrapRequired = true),
            )
            fixture.database.syncReplicaDao().insertBootstrapSession(
                fixture.bootstrapIntent().session,
            )
        }

    private fun newFixture(label: String): SyncM2PersistenceFixture =
        SyncM2PersistenceFixture(
            context = context,
            label = "$label-$testId",
        ).also(fixtures::add)

    private suspend fun persistDirect(
        fixture: SyncM2PersistenceFixture,
        request: M2WireRequest,
        persistence: NewDurableRequestPersistence,
    ) {
        val ring = keyring()
        ring.provisionCurrentKey(
            durableReferenceCount = fixture.database.syncTransportDao()
                .countRequestsReferencingHmacGeneration(1),
        )
        val protected = DurableSyncRequestProtector(context, ring)
            .protectNew(request, persistence)
        try {
            fixture.database.syncTransportDao().insertRequest(protected)
        } finally {
            protected.rawRequestBody?.fill(0)
            protected.sealedBodyCiphertext?.fill(0)
            protected.sealedBodyNonce?.fill(0)
            protected.rawBodyHmac.fill(0)
        }
    }

    private suspend fun assertClaimedExactOnce(
        store: ProtectedSyncRequestStore,
        endpointId: String,
        requestIdentity: String,
        exactBody: ByteArray,
    ) {
        val claim = store.verifyAndClaim(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
            attemptId = UUID.randomUUID().toString(),
            attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
            leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        assertTrue(claim is ProtectedRequestClaim.Claimed)
        val verified = (claim as ProtectedRequestClaim.Claimed).request
        val consumed = verified.consumeBody { body -> body.copyOf() }
        try {
            assertArrayEquals(exactBody, consumed)
            assertThrows(IllegalStateException::class.java) {
                verified.consumeBody { Unit }
            }
        } finally {
            consumed.fill(0)
            verified.close()
        }
    }

    private fun exactBody(request: M2WireRequest): ByteArray =
        WireRequestCodec.materialize(request).use { materialized ->
            materialized.copyBody()
        }

    private fun cursorForSeed(seed: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32) { index -> (seed + index).toByte() },
        )

    private fun markerFile() = File(context.noBackupFilesDir, markerRelativePath)

    private fun deleteMarkerArtifacts() {
        listOf("", ".bak", ".new").forEach { suffix ->
            File(markerFile().path + suffix).delete()
        }
    }

    private fun deleteKeystoreAlias() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(hmacAlias)) deleteEntry(hmacAlias)
        }
    }

    private fun keystoreContainsAlias(): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            containsAlias(hmacAlias)
        }

    private fun generateHmacAlias() {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        hmacAlias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setKeySize(256)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun generateRsaAlias() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        hmacAlias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build(),
                )
            }
            .generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_CURSOR =
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFI"
        private const val LEASE_MS = 60_000L
    }
}
