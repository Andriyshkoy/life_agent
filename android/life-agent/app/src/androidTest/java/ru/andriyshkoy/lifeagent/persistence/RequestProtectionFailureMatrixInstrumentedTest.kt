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
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.security.DurableRequestBodyHmacBinding
import ru.andriyshkoy.lifeagent.data.security.KeystoreAeadPayloadCipher
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityException
import ru.andriyshkoy.lifeagent.data.security.SensitivePayloadIntegrityFailure
import ru.andriyshkoy.lifeagent.data.security.VerifiedDurableRequest
import ru.andriyshkoy.lifeagent.data.security.revokeAeadAlias
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.RevokeRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class RequestProtectionFailureMatrixInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<SyncM2PersistenceFixture>()
    private val additionalKeystoreAliases = mutableSetOf<String>()
    private lateinit var testId: String
    private lateinit var hmacAlias: String
    private lateinit var markerRelativePath: String

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        hmacAlias = "life_agent_test_failure_matrix_hmac_$testId"
        markerRelativePath = "crypto-tests/failure-matrix-hmac-$testId.marker"
    }

    @After
    fun tearDown() {
        fixtures.asReversed().forEach { fixture ->
            runCatching(fixture::close)
        }
        fixtures.clear()
        deleteMarkerArtifacts()
        (additionalKeystoreAliases + hmacAlias).forEach(::deleteKeystoreAlias)
        additionalKeystoreAliases.clear()
    }

    @Test
    fun concurrentFirstProvisionAcrossKeyringInstancesInstallsOneUsableKey() = runBlocking {
        val binding = hmacBinding()
        val body = "concurrent-first-provision".toByteArray(StandardCharsets.UTF_8)
        val barrier = CyclicBarrier(2)
        val rings = listOf(keyring(), keyring())
        val tags = try {
            coroutineScope {
                rings.map { ring ->
                    async(Dispatchers.Default) {
                        barrier.await(10, TimeUnit.SECONDS)
                        ring.provisionCurrentKey(durableReferenceCount = 0)
                        ring.signNew(binding, body)
                    }
                }.awaitAll()
            }
        } catch (error: Throwable) {
            body.fill(0)
            throw error
        }

        try {
            assertEquals(2, tags.size)
            assertArrayEquals(tags[0], tags[1])
            keyring().verifyExisting(binding, body, tags[0])
            keyring().verifyExisting(binding, body, tags[1])
            assertTrue(markerFile().isFile)
            assertTrue(keystoreContainsAlias(hmacAlias))
        } finally {
            tags.forEach { tag -> tag.fill(0) }
            body.fill(0)
        }
    }

    @Test
    fun pullHmacMismatchQuarantinesBeforeAttemptOrBodyDispatch() = runBlocking {
        val fixture = newIncrementalFixture("pull-hmac-mismatch")
        val requestId = UUID.randomUUID().toString()
        ProtectedSyncRequestStore(context, fixture.database, keyring()).persistPull(
            request = pullRequest(requestId),
            persistence = persistence(),
        )
        val original = requireNotNull(
            fixture.database.syncTransportDao().findRequest("sync_pull", requestId),
        )
        val exactBody = checkNotNull(original.rawRequestBody).copyOf()
        val tamperedTag = original.rawBodyHmac.copyOf().also { tag ->
            tag[0] = (tag[0].toInt() xor 0x01).toByte()
        }
        try {
            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET raw_body_hmac = ?
                WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                """.trimIndent(),
                arrayOf(tamperedTag, requestId),
            )
            fixture.reopen()

            val quarantined = claimIntegrityFailure(
                fixture = fixture,
                endpointId = "sync_pull",
                requestIdentity = requestId,
                expectedFailure = RequestBodyFailure.HMAC_MISMATCH,
            )
            assertArrayEquals(exactBody, quarantined.rawRequestBody)
            assertSyncIntegrityState(
                fixture = fixture,
                expectedCode = "request_body_hmac_mismatch",
            )
        } finally {
            exactBody.fill(0)
            tamperedTag.fill(0)
        }
    }

    @Test
    fun missingHmacKeyQuarantinesBeforeAttemptOrBodyDispatch() = runBlocking {
        val fixture = newIncrementalFixture("pull-hmac-key-loss")
        val requestId = UUID.randomUUID().toString()
        ProtectedSyncRequestStore(context, fixture.database, keyring()).persistPull(
            request = pullRequest(requestId),
            persistence = persistence(),
        )
        assertTrue(markerFile().isFile)
        deleteKeystoreAlias(hmacAlias)
        fixture.reopen()

        claimIntegrityFailure(
            fixture = fixture,
            endpointId = "sync_pull",
            requestIdentity = requestId,
            expectedFailure = RequestBodyFailure.KEY_UNAVAILABLE,
        )
        assertFalse("Verification must never recreate the missing key", keystoreContainsAlias(hmacAlias))
        assertTrue("Key-loss evidence marker must be retained", markerFile().isFile)
        assertSyncIntegrityState(
            fixture = fixture,
            expectedCode = "request_body_key_unavailable",
        )
    }

    @Test
    fun revokeCiphertextTamperIsExactAeadAuthenticationFailure() = runBlocking {
        val fixture = newRevokeFixture("revoke-aead-auth-failure")
        val requestId = UUID.randomUUID().toString()
        persistRevoke(fixture, requestId)
        val stored = requireNotNull(
            fixture.database.syncTransportDao().findRequest("auth_revoke", requestId),
        )
        rememberAeadAlias(stored)
        val tamperedCiphertext = checkNotNull(stored.sealedBodyCiphertext).copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        try {
            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET sealed_body_ciphertext = ?
                WHERE endpoint_id = 'auth_revoke' AND request_identity = ?
                """.trimIndent(),
                arrayOf(tamperedCiphertext, requestId),
            )
            fixture.reopen()

            claimIntegrityFailure(
                fixture = fixture,
                endpointId = "auth_revoke",
                requestIdentity = requestId,
                expectedFailure = RequestBodyFailure.AEAD_AUTH_FAILED,
            )
            assertRevokeIntegrityState(
                fixture = fixture,
                expectedCode = "request_body_aead_authentication_failed",
            )
        } finally {
            tamperedCiphertext.fill(0)
        }
    }

    @Test
    fun revokeAeadMetadataDriftIsExactMetadataFailure() = runBlocking {
        val fixture = newRevokeFixture("revoke-aead-metadata-failure")
        val requestId = UUID.randomUUID().toString()
        persistRevoke(fixture, requestId)
        val stored = requireNotNull(
            fixture.database.syncTransportDao().findRequest("auth_revoke", requestId),
        )
        rememberAeadAlias(stored)
        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET sealed_body_aad_version = 2
            WHERE endpoint_id = 'auth_revoke' AND request_identity = ?
            """.trimIndent(),
            arrayOf(requestId),
        )
        fixture.reopen()

        claimIntegrityFailure(
            fixture = fixture,
            endpointId = "auth_revoke",
            requestIdentity = requestId,
            expectedFailure = RequestBodyFailure.METADATA_INVALID,
        )
        assertRevokeIntegrityState(
            fixture = fixture,
            expectedCode = "request_body_metadata_invalid",
        )
    }

    @Test
    fun missingRevokeAeadKeyIsExactKeyUnavailableFailure() = runBlocking {
        val fixture = newRevokeFixture("revoke-aead-key-loss")
        val requestId = UUID.randomUUID().toString()
        persistRevoke(fixture, requestId)
        val stored = requireNotNull(
            fixture.database.syncTransportDao().findRequest("auth_revoke", requestId),
        )
        val aeadAlias = checkNotNull(stored.sealedBodyKeyAlias)
        additionalKeystoreAliases += aeadAlias
        deleteKeystoreAlias(aeadAlias)
        fixture.reopen()

        claimIntegrityFailure(
            fixture = fixture,
            endpointId = "auth_revoke",
            requestIdentity = requestId,
            expectedFailure = RequestBodyFailure.KEY_UNAVAILABLE,
        )
        assertFalse("Retry verification must not recreate an AEAD key", keystoreContainsAlias(aeadAlias))
        assertRevokeIntegrityState(
            fixture = fixture,
            expectedCode = "request_body_key_unavailable",
        )
    }

    @Test
    fun wrongTypeRevokeAeadAliasIsNotOverwrittenAndCreationRollsBack() = runBlocking {
        val fixture = newRevokeFixture("revoke-aead-wrong-entry-type")
        val requestId = UUID.randomUUID().toString()
        val aeadAlias = revokeAeadAlias(
            localCredentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
            requestIdentity = requestId,
        )
        additionalKeystoreAliases += aeadAlias
        generateRsaAlias(aeadAlias)
        val entryBefore = KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            getEntry(aeadAlias, null)
        }
        assertTrue(entryBefore is KeyStore.PrivateKeyEntry)

        val failure = runCatching {
            ProtectedSyncRequestStore(context, fixture.database, keyring()).beginRevoke(
                request = RevokeRequest(
                    requestId = requestId,
                    deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                    generation = 1,
                    refreshToken = WipeableSecret.ascii(VALID_REFRESH_TOKEN),
                ),
                persistence = persistence(),
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
        }.exceptionOrNull()

        assertTrue(failure is SensitivePayloadIntegrityException)
        assertEquals(
            SensitivePayloadIntegrityFailure.KEY_UNAVAILABLE,
            (failure as SensitivePayloadIntegrityException).failure,
        )
        assertNull(
            fixture.database.syncTransportDao().findRequest("auth_revoke", requestId),
        )
        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals("active", auth.state)
        assertNull(auth.failureCode)
        assertTrue(auth.refreshTokenCiphertext != null)
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("incremental", stream.phase)
        assertNull(stream.integrityErrorCode)

        val entryAfter = KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            getEntry(aeadAlias, null)
        }
        assertTrue("Existing RSA entry must not be overwritten", entryAfter is KeyStore.PrivateKeyEntry)
        assertArrayEquals(
            (entryBefore as KeyStore.PrivateKeyEntry).certificate.publicKey.encoded,
            (entryAfter as KeyStore.PrivateKeyEntry).certificate.publicKey.encoded,
        )
    }

    @Test
    fun authenticatedAndVerifiedBodyBuffersAreWipedOnSuccessAndCallbackFailure() {
        assertAeadCallbackBuffersAreWiped()
        assertVerifiedRequestBuffersAreWiped()
    }

    private fun assertAeadCallbackBuffersAreWiped() {
        val alias = "life_agent_test_failure_matrix_aead_$testId"
        additionalKeystoreAliases += alias
        val cipher = KeystoreAeadPayloadCipher(
            context = context,
            keyAlias = alias,
            keyGeneration = 1,
        )
        val plaintext = "temporary-authenticated-plaintext".toByteArray(StandardCharsets.UTF_8)
        val identity = UUID.randomUUID().toString()
        val envelope = cipher.seal(
            plaintext = plaintext,
            purpose = "failure_matrix",
            recordIdentity = identity,
        )
        var successBuffer: ByteArray? = null
        var throwingBuffer: ByteArray? = null
        try {
            assertEquals(
                plaintext.size,
                cipher.withAuthenticatedPlaintext(
                    envelope = envelope,
                    purpose = "failure_matrix",
                    recordIdentity = identity,
                ) { exposed ->
                    successBuffer = exposed
                    assertArrayEquals(plaintext, exposed)
                    exposed.size
                },
            )
            assertAllZero(checkNotNull(successBuffer))

            assertThrows(CallbackFailure::class.java) {
                cipher.withAuthenticatedPlaintext(
                    envelope = envelope,
                    purpose = "failure_matrix",
                    recordIdentity = identity,
                ) { exposed ->
                    throwingBuffer = exposed
                    throw CallbackFailure()
                }
            }
            assertAllZero(checkNotNull(throwingBuffer))
        } finally {
            plaintext.fill(0)
            envelope.ciphertext.fill(0)
            envelope.nonce.fill(0)
        }
    }

    private fun assertVerifiedRequestBuffersAreWiped() {
        val successBody = "verified-success-body".toByteArray(StandardCharsets.UTF_8)
        val success = VerifiedDurableRequest(
            endpoint = M2Endpoint.SYNC_PULL,
            requestIdentity = UUID.randomUUID().toString(),
            idempotencyKey = null,
            body = successBody,
        )
        var successBuffer: ByteArray? = null
        assertEquals(
            successBody.size,
            success.consumeBody { exposed ->
                successBuffer = exposed
                exposed.size
            },
        )
        assertAllZero(checkNotNull(successBuffer))
        assertAllZero(successBody)
        assertThrows(IllegalStateException::class.java) {
            success.consumeBody { Unit }
        }
        success.close()

        val throwingBody = "verified-throwing-body".toByteArray(StandardCharsets.UTF_8)
        val throwing = VerifiedDurableRequest(
            endpoint = M2Endpoint.SYNC_PULL,
            requestIdentity = UUID.randomUUID().toString(),
            idempotencyKey = null,
            body = throwingBody,
        )
        var throwingBuffer: ByteArray? = null
        assertThrows(CallbackFailure::class.java) {
            throwing.consumeBody { exposed ->
                throwingBuffer = exposed
                throw CallbackFailure()
            }
        }
        assertAllZero(checkNotNull(throwingBuffer))
        assertAllZero(throwingBody)
        assertThrows(IllegalStateException::class.java) {
            throwing.consumeBody { Unit }
        }
        throwing.close()
    }

    private suspend fun claimIntegrityFailure(
        fixture: SyncM2PersistenceFixture,
        endpointId: String,
        requestIdentity: String,
        expectedFailure: RequestBodyFailure,
    ): SyncHttpRequestEntity {
        val claim = ProtectedSyncRequestStore(context, fixture.database, keyring())
            .verifyAndClaim(
                endpointId = endpointId,
                requestIdentity = requestIdentity,
                attemptId = UUID.randomUUID().toString(),
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
        assertTrue(claim is ProtectedRequestClaim.IntegrityFailure)
        assertFalse(claim is ProtectedRequestClaim.Claimed)
        assertEquals(
            expectedFailure,
            (claim as ProtectedRequestClaim.IntegrityFailure).failure,
        )
        val row = requireNotNull(
            fixture.database.syncTransportDao().findRequest(endpointId, requestIdentity),
        )
        assertEquals("integrity_failure", row.state)
        assertEquals(expectedFailure.storageCode, row.terminalErrorCode)
        assertEquals(0, row.attemptCount)
        assertNull(row.activeAttemptId)
        assertNull(row.lastAttemptAtEpochMs)
        assertNull(row.leaseExpiresAtEpochMs)
        assertNull(row.nextAttemptAtEpochMs)
        assertNull(row.terminalHttpStatus)
        assertNull(row.exactResponseBody)
        return row
    }

    private suspend fun assertSyncIntegrityState(
        fixture: SyncM2PersistenceFixture,
        expectedCode: String,
    ) {
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals(expectedCode, stream.integrityErrorCode)
        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals("active", auth.state)
        assertNull(auth.failureCode)
        assertEquals(1L, auth.generation)
    }

    private suspend fun assertRevokeIntegrityState(
        fixture: SyncM2PersistenceFixture,
        expectedCode: String,
    ) {
        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals("integrity_failure", auth.state)
        assertEquals(expectedCode, auth.failureCode)
        assertEquals(SyncM2PersistenceFixture.EPOCH_ID, auth.credentialEpochId)
        assertEquals(SyncM2PersistenceFixture.DEVICE_ID, auth.deviceId)
        assertEquals(1L, auth.generation)
        assertNull(auth.refreshTokenCiphertext)
        assertNull(auth.refreshTokenNonce)
        assertNull(auth.refreshTokenKeyAlias)
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("incremental", stream.phase)
        assertNull(stream.integrityErrorCode)
    }

    private suspend fun newIncrementalFixture(label: String): SyncM2PersistenceFixture =
        newFixture(label).also { fixture ->
            fixture.seedIdentity(
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                personId = SyncM2PersistenceFixture.PERSON_ID,
            )
            fixture.installActiveAuth()
            fixture.database.syncReplicaDao().insertStreamState(
                fixture.streamState().copy(appliedCursor = DEFAULT_CURSOR),
            )
        }

    private suspend fun newRevokeFixture(label: String): SyncM2PersistenceFixture =
        newFixture(label).also { fixture ->
            fixture.seedIdentity(
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                personId = SyncM2PersistenceFixture.PERSON_ID,
            )
            fixture.installActiveAuth()
            fixture.seedIncrementalStream()
        }

    private fun newFixture(label: String): SyncM2PersistenceFixture =
        SyncM2PersistenceFixture(
            context = context,
            label = "$label-$testId",
        ).also(fixtures::add)

    private suspend fun persistRevoke(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
    ) {
        ProtectedSyncRequestStore(context, fixture.database, keyring()).beginRevoke(
            request = RevokeRequest(
                requestId = requestIdentity,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
                refreshToken = WipeableSecret.ascii(VALID_REFRESH_TOKEN),
            ),
            persistence = persistence(),
            nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        assertEquals("revoke_pending", fixture.database.syncAuthDao().findState()?.state)
    }

    private fun pullRequest(requestIdentity: String) = PullRequest(
        requestId = requestIdentity,
        deviceId = SyncM2PersistenceFixture.DEVICE_ID,
        cursor = DEFAULT_CURSOR,
        pageSize = 100,
    )

    private fun persistence() = NewDurableRequestPersistence(
        localCredentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        accessGenerationUsed = 1,
        attemptBudget = 8,
        deadlineAtEpochMs = SyncM2PersistenceFixture.DEADLINE_MS,
        createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
    )

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

    private fun rememberAeadAlias(request: SyncHttpRequestEntity) {
        additionalKeystoreAliases += checkNotNull(request.sealedBodyKeyAlias)
    }

    private fun markerFile() = File(context.noBackupFilesDir, markerRelativePath)

    private fun deleteMarkerArtifacts() {
        listOf("", ".bak", ".new").forEach { suffix ->
            File(markerFile().path + suffix).delete()
        }
    }

    private fun deleteKeystoreAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private fun keystoreContainsAlias(alias: String): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).run {
            load(null)
            containsAlias(alias)
        }

    private fun generateRsaAlias(alias: String) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build(),
                )
            }
            .generateKeyPair()
    }

    private fun assertAllZero(bytes: ByteArray) {
        assertTrue("Sensitive callback buffer was not wiped", bytes.all { it == 0.toByte() })
    }

    private class CallbackFailure : RuntimeException()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LEASE_MS = 60_000L
        private const val DEFAULT_CURSOR =
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFI"
        private const val VALID_REFRESH_TOKEN =
            "lar_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRE"
    }
}
