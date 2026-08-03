package ru.andriyshkoy.lifeagent.persistence

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedRequestClaim
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestStore
import ru.andriyshkoy.lifeagent.data.local.db.RequestBodyFailure
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.SyncPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.SyncRequestPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPageReceiptEntity
import ru.andriyshkoy.lifeagent.data.security.DurableSyncRequestProtector
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.security.NewDurableRequestPersistence
import ru.andriyshkoy.lifeagent.data.sync.wire.BootstrapRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.M2WireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.PullRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.RevokeRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class RequestProtectionStateRecoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<SyncM2PersistenceFixture>()
    private val additionalKeystoreAliases = mutableSetOf<String>()
    private lateinit var testId: String
    private lateinit var hmacAlias: String
    private lateinit var markerRelativePath: String

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        hmacAlias = "life_agent_test_state_recovery_hmac_$testId"
        markerRelativePath = "crypto-tests/state-recovery-hmac-$testId.marker"
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
    fun hmacValidOldFamilyRequestDefersAfterAuthAndStreamReplacement() = runBlocking {
        val fixture = newIncrementalFixture("old-family-replacement")
        val requestId = persistProtectedPull(fixture)
        val before = requireNotNull(
            fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
        )
        val retainedHmac = before.rawBodyHmac.copyOf()

        assertEquals(
            1,
            fixture.database.syncReplicaDao().deleteExactStream(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            ),
        )
        assertEquals(
            1,
            fixture.database.syncAuthDao().deleteExactFamily(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
            ),
        )
        fixture.installActiveAuth(credentialEpochId = REPLACEMENT_EPOCH_ID)
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(credentialEpochId = REPLACEMENT_EPOCH_ID),
        )

        try {
            val claim = protectedStore(fixture).verifyAndClaim(
                endpointId = SYNC_PULL,
                requestIdentity = requestId,
                attemptId = UUID.randomUUID().toString(),
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            assertTrue(claim === ProtectedRequestClaim.NotClaimed)

            val deferred = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            )
            assertEquals("ready", deferred.state)
            assertEquals(0, deferred.attemptCount)
            assertNull(deferred.activeAttemptId)
            assertNull(deferred.terminalAtUtc)
            assertNull(deferred.terminalErrorCode)
            assertArrayEquals(retainedHmac, deferred.rawBodyHmac)

            val replacementStream = requireNotNull(
                fixture.database.syncReplicaDao().findStreamState(),
            )
            assertEquals(REPLACEMENT_EPOCH_ID, replacementStream.credentialEpochId)
            assertEquals("incremental", replacementStream.phase)
            assertFalse(replacementStream.bootstrapRequired)
            assertNull(replacementStream.integrityErrorCode)
        } finally {
            retainedHmac.fill(0)
        }
    }

    @Test
    fun refreshInFlightDefersVerifiedRequestWithoutAttemptOrHalt() = runBlocking {
        val fixture = newIncrementalFixture("refresh-in-flight")
        val requestId = persistProtectedPull(fixture)
        fixture.database.syncAuthDao().claimRefreshAttempt(
            entity = fixture.refreshAttempt(),
            nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
        )
        assertEquals(
            "refresh_in_flight",
            fixture.database.syncAuthDao().findState()?.state,
        )

        val claim = protectedStore(fixture).verifyAndClaim(
            endpointId = SYNC_PULL,
            requestIdentity = requestId,
            attemptId = UUID.randomUUID().toString(),
            attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
            leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        assertTrue(claim === ProtectedRequestClaim.NotClaimed)

        val deferred = requireNotNull(
            fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
        )
        assertEquals("ready", deferred.state)
        assertEquals(0, deferred.attemptCount)
        assertNull(deferred.activeAttemptId)
        assertNull(deferred.terminalAtUtc)
        assertNull(deferred.terminalErrorCode)
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("incremental", stream.phase)
        assertNull(stream.integrityErrorCode)
    }

    @Test
    fun futureStoredGenerationQuarantinesBeforeAttemptAndHaltsExactStream() = runBlocking {
        val fixture = newIncrementalFixture("future-generation")
        val request = pullRequest()
        val requestId = request.requestId
        persistDirect(fixture, request, persistence(accessGeneration = 2))
        val retainedHmac = requireNotNull(
            fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
        ).rawBodyHmac.copyOf()

        try {
            assertMetadataFailure(
                fixture = fixture,
                endpointId = SYNC_PULL,
                requestIdentity = requestId,
            )
            val quarantined = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            )
            assertEquals(2L, quarantined.accessGenerationUsed)
            assertArrayEquals(retainedHmac, quarantined.rawBodyHmac)
        } finally {
            retainedHmac.fill(0)
        }
    }

    @Test
    fun futureRevokeGenerationQuarantinesRequestAndCurrentFamilyBeforeAttempt() = runBlocking {
        val fixture = newRevokeFixture("future-revoke-generation")
        val requestId = persistRevoke(fixture)
        val before = requireNotNull(
            fixture.database.syncTransportDao().findRequest(AUTH_REVOKE, requestId),
        )
        val retainedHmac = before.rawBodyHmac.copyOf()
        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_http_request
            SET access_generation_used = 2
            WHERE endpoint_id = 'auth_revoke' AND request_identity = ?
            """.trimIndent(),
            arrayOf(requestId),
        )
        fixture.reopen()

        try {
            val claim = protectedStore(fixture).verifyAndClaim(
                endpointId = AUTH_REVOKE,
                requestIdentity = requestId,
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
            val quarantined = assertRevokeRequestMetadataQuarantined(
                fixture = fixture,
                requestIdentity = requestId,
                expectedAttemptCount = 0,
                expectedTerminalAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            assertEquals(2L, quarantined.accessGenerationUsed)
            assertArrayEquals(retainedHmac, quarantined.rawBodyHmac)
            assertCurrentRevokeFamilyIntegrityFailure(fixture)
        } finally {
            retainedHmac.fill(0)
        }
    }

    @Test
    fun revokeMetadataRecoveryPrecedesExpiryForNullZeroAndFutureGeneration() = runBlocking {
        listOf<Long?>(null, 0, 2).forEachIndexed { index, storedGeneration ->
            val fixture = newRevokeFixture("revoke-generation-$index")
            val requestId = persistRevoke(fixture)
            val retainedHmac = requireNotNull(
                fixture.database.syncTransportDao().findRequest(AUTH_REVOKE, requestId),
            ).rawBodyHmac.copyOf()
            installExpiredExhaustedWaitingRefreshGeneration(
                fixture = fixture,
                endpointId = AUTH_REVOKE,
                requestIdentity = requestId,
                accessGenerationUsed = storedGeneration,
            )
            fixture.reopen()

            try {
                val recoveryCandidate = fixture.database.syncTransportDao()
                    .findOpenRequestsNeedingIntegrityRecovery(1)
                    .single()
                assertEquals(requestId, recoveryCandidate.requestIdentity)
                assertEquals(storedGeneration, recoveryCandidate.accessGenerationUsed)
                assertTrue(recoveryCandidate.hasCanonicalHmacStorage)
                assertEquals(
                    1,
                    SyncRequestPersistenceStore(fixture.database)
                        .reconcileExpiredOrExhaustedRequests(
                            nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                            terminalAtUtc = RECOVERY_UTC,
                        ),
                )
                val quarantined = assertRevokeRequestMetadataQuarantined(
                    fixture = fixture,
                    requestIdentity = requestId,
                    expectedAttemptCount = 8,
                    expectedTerminalAtUtc = RECOVERY_UTC,
                )
                assertEquals(storedGeneration?.takeIf { it > 0 }, quarantined.accessGenerationUsed)
                assertArrayEquals(retainedHmac, quarantined.rawBodyHmac)
                assertCurrentRevokeFamilyIntegrityFailure(fixture)
            } finally {
                retainedHmac.fill(0)
            }
        }
    }

    @Test
    fun corruptedOldRevokeRequestCannotQuarantineReplacementFamily() = runBlocking {
        val fixture = newRevokeFixture("replacement-family")
        val requestId = persistRevoke(fixture)
        installExpiredExhaustedWaitingRefreshGeneration(
            fixture = fixture,
            endpointId = AUTH_REVOKE,
            requestIdentity = requestId,
            accessGenerationUsed = null,
        )
        assertEquals(
            1,
            fixture.database.syncReplicaDao().deleteExactStream(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            ),
        )
        assertEquals(
            1,
            fixture.database.syncAuthDao().deleteExactFamily(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
            ),
        )
        fixture.database.openHelper.writableDatabase.execSQL(
            """
            UPDATE local_installation
            SET server_device_id = ?
            WHERE installation_id = ?
            """.trimIndent(),
            arrayOf(
                REPLACEMENT_DEVICE_ID,
                SyncM2PersistenceFixture.INSTALLATION_ID,
            ),
        )
        fixture.installActiveAuth(
            credentialEpochId = REPLACEMENT_EPOCH_ID,
            deviceId = REPLACEMENT_DEVICE_ID,
        )
        fixture.database.syncReplicaDao().insertStreamState(
            fixture.streamState(
                credentialEpochId = REPLACEMENT_EPOCH_ID,
                deviceId = REPLACEMENT_DEVICE_ID,
            ),
        )
        fixture.reopen()

        assertEquals(
            1,
            SyncRequestPersistenceStore(fixture.database)
                .reconcileExpiredOrExhaustedRequests(
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    terminalAtUtc = RECOVERY_UTC,
                ),
        )
        assertRevokeRequestMetadataQuarantined(
            fixture = fixture,
            requestIdentity = requestId,
            expectedAttemptCount = 8,
            expectedTerminalAtUtc = RECOVERY_UTC,
        )
        val replacement = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals(REPLACEMENT_EPOCH_ID, replacement.credentialEpochId)
        assertEquals(REPLACEMENT_DEVICE_ID, replacement.deviceId)
        assertEquals(1L, replacement.generation)
        assertEquals("active", replacement.state)
        assertNull(replacement.failureCode)
        assertTrue(replacement.refreshTokenCiphertext != null)
        val replacementStream = requireNotNull(
            fixture.database.syncReplicaDao().findStreamState(),
        )
        assertEquals(REPLACEMENT_EPOCH_ID, replacementStream.credentialEpochId)
        assertEquals(REPLACEMENT_DEVICE_ID, replacementStream.deviceId)
        assertEquals("incremental", replacementStream.phase)
        assertNull(replacementStream.integrityErrorCode)
    }

    @Test
    fun legacyInvalidRowsRemainRunnableAndContributeImmediateSchedule() = runBlocking {
        listOf<Pair<String, Int?>>(
            LEGACY_HMAC_GENERATION to null,
            LEGACY_TEXT_HMAC to 0,
            LEGACY_TEXT_HMAC to 31,
            LEGACY_TEXT_HMAC to 32,
            LEGACY_TEXT_HMAC to 33,
        ).forEach { (corruption, textOctetCount) ->
            val fixture = newIncrementalFixture(
                "discover-$corruption-${textOctetCount ?: "generation"}",
            )
            val requestId = persistProtectedPull(fixture)
            installReadyLegacyCorruption(
                fixture = fixture,
                requestIdentity = requestId,
                corruption = corruption,
                textOctetCount = textOctetCount,
            )
            fixture.reopen()

            val runnable = fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 1)
                .single()
            assertEquals(requestId, runnable.requestIdentity)
            if (corruption == LEGACY_HMAC_GENERATION) {
                assertEquals(0, runnable.hmacKeyGeneration)
            } else {
                val storage = readHmacStorage(fixture, requestId)
                assertEquals("text", storage.storageClass)
                assertEquals(textOctetCount, storage.octetCount)
            }
            assertEquals(
                SyncM2PersistenceFixture.NOW_MS,
                fixture.database.syncTransportDao()
                    .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
            )
        }
    }

    @Test
    fun interruptedAuthRecoveryPrioritizesInvalidWaitingRowsOverOrphanAndExpiry() =
        runBlocking {
            listOf(INVALID_WAITING_HMAC, INVALID_WAITING_ACCESS).forEach { corruption ->
                val fixture = newIncrementalFixture("auth-recovery-$corruption")
                val requestId = persistProtectedPull(fixture)
                if (corruption == INVALID_WAITING_HMAC) {
                    installExpiredExhaustedWaitingRefreshTextHmac(
                        fixture = fixture,
                        requestIdentity = requestId,
                        textOctetCount = 32,
                    )
                } else {
                    installExpiredExhaustedWaitingRefreshGeneration(
                        fixture = fixture,
                        endpointId = SYNC_PULL,
                        requestIdentity = requestId,
                        accessGenerationUsed = null,
                    )
                }
                fixture.reopen()

                val recovery = SyncAuthPersistenceStore(fixture.database)
                    .recoverInterruptedAuthFlows(updatedAtUtc = RECOVERY_UTC)
                assertEquals(1, recovery.recoveredCount)
                assertFalse(recovery.currentAuthorityChanged)
                val recovered = requireNotNull(
                    fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
                )
                assertEquals("integrity_failure", recovered.state)
                assertEquals("request_body_metadata_invalid", recovered.terminalErrorCode)
                assertEquals(RECOVERY_UTC, recovered.terminalAtUtc)
                assertEquals(8, recovered.attemptCount)
                assertNull(recovered.activeAttemptId)
                assertNull(recovered.leaseExpiresAtEpochMs)
                assertFalse(recovered.terminalErrorCode == "orphan_waiting_refresh")
                assertFalse(recovered.state == "terminal_local")
                val auth = requireNotNull(fixture.database.syncAuthDao().findState())
                assertEquals("active", auth.state)
                assertNull(auth.failureCode)
                val stream = requireNotNull(
                    fixture.database.syncReplicaDao().findStreamState(),
                )
                assertEquals("integrity_halted", stream.phase)
                assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
                assertEquals(
                    0,
                    SyncAuthPersistenceStore(fixture.database)
                        .recoverInterruptedAuthFlows(updatedAtUtc = RECOVERY_UTC)
                        .recoveredCount,
                )
            }
        }

    @Test
    fun interruptedAuthIntegrityRecoveryIsBoundedAndDoesNotHotLoop() = runBlocking {
        val fixture = newIncrementalFixture("bounded-integrity-recovery")
        fixture.database.withTransaction {
            repeat(MAX_INTEGRITY_RECOVERY_ROWS + 1) {
                fixture.database.syncTransportDao().insertRequest(
                    fixture.request(
                        endpointId = SYNC_PULL,
                        requestIdentity = UUID.randomUUID().toString(),
                    ),
                )
            }
        }
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            execSQL(
                """
                UPDATE sync_http_request
                SET hmac_key_generation = 0
                WHERE endpoint_id = 'sync_pull'
                """.trimIndent(),
            )
        }
        fixture.reopen()

        val authStore = SyncAuthPersistenceStore(fixture.database)
        assertEquals(
            MAX_INTEGRITY_RECOVERY_ROWS,
            authStore.recoverInterruptedAuthFlows(updatedAtUtc = RECOVERY_UTC)
                .recoveredCount,
        )
        assertEquals(
            1,
            fixture.database.syncTransportDao()
                .findOpenRequestsNeedingIntegrityRecovery(MAX_INTEGRITY_RECOVERY_ROWS)
                .size,
        )
        assertEquals(
            1,
            authStore.recoverInterruptedAuthFlows(updatedAtUtc = RECOVERY_UTC)
                .recoveredCount,
        )
        assertEquals(
            0,
            authStore.recoverInterruptedAuthFlows(updatedAtUtc = RECOVERY_UTC)
                .recoveredCount,
        )
        assertEquals(
            MAX_INTEGRITY_RECOVERY_ROWS + 1,
            countRequestsInState(fixture, "integrity_failure"),
        )
        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals("active", auth.state)
        assertNull(auth.failureCode)
    }

    @Test
    fun bootstrapParityDriftQuarantinesInBothViableDirections() = runBlocking {
        val incremental = newIncrementalFixture("auth-requires-bootstrap")
        val pullId = persistProtectedPull(incremental)
        assertEquals(
            1,
            incremental.database.syncAuthDao().setBootstrapRequired(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                bootstrapRequired = true,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
        assertEquals(
            listOf(pullId),
            incremental.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 1)
                .map { it.requestIdentity },
        )
        assertMetadataFailure(incremental, SYNC_PULL, pullId)
        assertTrue(
            requireNotNull(incremental.database.syncAuthDao().findState()).bootstrapRequired,
        )

        val bootstrap = newBootstrapFixture("stream-requires-bootstrap")
        val session = requireNotNull(
            bootstrap.database.syncReplicaDao().findBootstrapSessionWithActiveSlot(),
        )
        val bootstrapRequest = BootstrapRequest(
            requestId = UUID.randomUUID().toString(),
            bootstrapId = session.bootstrapId,
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            pageSize = 100,
            pageCursor = session.nextPageCursor,
        )
        val bootstrapRequestId = bootstrapRequest.requestId
        persistDirect(bootstrap, bootstrapRequest, persistence())
        assertEquals(
            1,
            bootstrap.database.syncAuthDao().setBootstrapRequired(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                bootstrapRequired = false,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
        assertEquals(
            listOf(bootstrapRequestId),
            bootstrap.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 1)
                .map { it.requestIdentity },
        )
        assertMetadataFailure(bootstrap, SYNC_BOOTSTRAP, bootstrapRequestId)
        assertFalse(
            requireNotNull(bootstrap.database.syncAuthDao().findState()).bootstrapRequired,
        )
    }

    @Test
    fun reconciliationPrioritizesEveryTextHmacShapeOverExpiryAndBudget() = runBlocking {
        listOf(0, 31, 32, 33).forEach { textOctetCount ->
            val fixture = newIncrementalFixture("text-hmac-$textOctetCount")
            val requestId = persistProtectedPull(fixture)
            installExpiredExhaustedWaitingRefreshTextHmac(
                fixture = fixture,
                requestIdentity = requestId,
                textOctetCount = textOctetCount,
            )
            fixture.reopen()

            val before = readHmacStorage(fixture, requestId)
            assertEquals("text", before.storageClass)
            assertEquals(textOctetCount, before.octetCount)
            val recoveryCandidate = fixture.database.syncTransportDao()
                .findOpenRequestsNeedingIntegrityRecovery(1)
                .single()
            assertEquals(requestId, recoveryCandidate.requestIdentity)
            assertEquals("text", recoveryCandidate.rawBodyHmacStorageClass)
            assertEquals(textOctetCount, recoveryCandidate.rawBodyHmacOctetCount)

            assertEquals(
                1,
                SyncRequestPersistenceStore(fixture.database)
                    .reconcileExpiredOrExhaustedRequests(
                        nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        terminalAtUtc = RECOVERY_UTC,
                    ),
            )

            val recovered = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            )
            assertEquals("integrity_failure", recovered.state)
            assertEquals("request_body_metadata_invalid", recovered.terminalErrorCode)
            assertEquals(RECOVERY_UTC, recovered.terminalAtUtc)
            assertEquals(recovered.attemptBudget, recovered.attemptCount)
            assertNull(recovered.nextAttemptAtEpochMs)
            assertNull(recovered.leaseExpiresAtEpochMs)
            assertNull(recovered.activeAttemptId)
            assertEquals(32, recovered.rawBodyHmac.size)
            assertTrue(recovered.rawBodyHmac.all { it == 0.toByte() })

            val after = readHmacStorage(fixture, requestId)
            assertEquals("blob", after.storageClass)
            assertEquals(32, after.octetCount)
            val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
            assertEquals("integrity_halted", stream.phase)
            assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
            assertEquals(
                0,
                SyncRequestPersistenceStore(fixture.database)
                    .reconcileExpiredOrExhaustedRequests(
                        nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        terminalAtUtc = RECOVERY_UTC,
                    ),
            )
        }
    }

    @Test
    fun reconciliationPrioritizesMalformedBlobOverExpiryAndBudget() = runBlocking {
        val fixture = newIncrementalFixture("blob-hmac-31")
        val requestId = persistProtectedPull(fixture)
        installExpiredExhaustedWaitingRefreshBlobHmac(
            fixture = fixture,
            requestIdentity = requestId,
            hmacOctetCount = 31,
        )
        fixture.reopen()

        val recoveryCandidate = fixture.database.syncTransportDao()
            .findOpenRequestsNeedingIntegrityRecovery(1)
            .single()
        assertEquals(requestId, recoveryCandidate.requestIdentity)
        assertEquals("blob", recoveryCandidate.rawBodyHmacStorageClass)
        assertEquals(31, recoveryCandidate.rawBodyHmacOctetCount)
        assertEquals("waiting_refresh", recoveryCandidate.state)
        assertEquals(8L, recoveryCandidate.attemptCount)

        assertEquals(
            1,
            SyncRequestPersistenceStore(fixture.database)
                .reconcileExpiredOrExhaustedRequests(
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    terminalAtUtc = RECOVERY_UTC,
                ),
        )
        val recovered = requireNotNull(
            fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
        )
        assertEquals("integrity_failure", recovered.state)
        assertEquals("request_body_metadata_invalid", recovered.terminalErrorCode)
        assertEquals(RECOVERY_UTC, recovered.terminalAtUtc)
        assertEquals(8, recovered.attemptCount)
        assertNull(recovered.nextAttemptAtEpochMs)
        assertNull(recovered.leaseExpiresAtEpochMs)
        assertNull(recovered.activeAttemptId)
        assertEquals(32, recovered.rawBodyHmac.size)
        assertTrue(recovered.rawBodyHmac.all { it == 0.toByte() })
        val storage = readHmacStorage(fixture, requestId)
        assertEquals("blob", storage.storageClass)
        assertEquals(32, storage.octetCount)
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
    }

    @Test
    fun historicalNonPositiveHmacGenerationIsRecoveredButNewCorruptionIsRejected() =
        runBlocking {
            listOf(0, -1).forEach { invalidGeneration ->
                val fixture = newIncrementalFixture("hmac-generation-$invalidGeneration")
                val requestId = persistProtectedPull(fixture)
                fixture.database.openHelper.writableDatabase.apply {
                    execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
                    execSQL(
                        """
                        UPDATE sync_http_request
                        SET hmac_key_generation = ?
                        WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                        """.trimIndent(),
                        arrayOf<Any?>(invalidGeneration, requestId),
                    )
                }
                fixture.reopen()

                val recoveryCandidate = fixture.database.syncTransportDao()
                    .findOpenRequestsNeedingIntegrityRecovery(1)
                    .single()
                assertEquals(requestId, recoveryCandidate.requestIdentity)
                assertEquals(invalidGeneration.toLong(), recoveryCandidate.hmacKeyGeneration)
                assertEquals(0L, recoveryCandidate.attemptCount)
                val failedAtUtc = if (invalidGeneration == 0) {
                    val claim = protectedStore(fixture).verifyAndClaim(
                        endpointId = SYNC_PULL,
                        requestIdentity = requestId,
                        attemptId = UUID.randomUUID().toString(),
                        attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        leaseExpiresAtEpochMs =
                            SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                        updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                    )
                    assertTrue(claim is ProtectedRequestClaim.IntegrityFailure)
                    assertEquals(
                        RequestBodyFailure.METADATA_INVALID,
                        (claim as ProtectedRequestClaim.IntegrityFailure).failure,
                    )
                    SyncM2PersistenceFixture.BASE_UTC
                } else {
                    assertEquals(
                        1,
                        SyncRequestPersistenceStore(fixture.database)
                            .reconcileExpiredOrExhaustedRequests(
                                nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                                terminalAtUtc = RECOVERY_UTC,
                            ),
                    )
                    RECOVERY_UTC
                }

                val recovered = requireNotNull(
                    fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
                )
                assertEquals("integrity_failure", recovered.state)
                assertEquals("request_body_metadata_invalid", recovered.terminalErrorCode)
                assertEquals(failedAtUtc, recovered.terminalAtUtc)
                assertEquals(0, recovered.attemptCount)
                assertEquals(1, recovered.hmacKeyGeneration)
                assertEquals(32, recovered.rawBodyHmac.size)
                assertTrue(recovered.rawBodyHmac.all { it == 0.toByte() })
                val rejectedWrite = runCatching {
                    fixture.database.openHelper.writableDatabase.execSQL(
                        """
                        UPDATE sync_http_request
                        SET hmac_key_generation = 0
                        WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                        """.trimIndent(),
                        arrayOf(requestId),
                    )
                }
                assertTrue("Runtime guard accepted an invalid HMAC generation", rejectedWrite.isFailure)
                assertEquals(
                    1,
                    fixture.database.syncTransportDao()
                        .findRequest(SYNC_PULL, requestId)
                        ?.hmacKeyGeneration,
                )
                val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
                assertEquals("integrity_halted", stream.phase)
                assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
            }
        }

    @Test
    fun unsupportedPositiveHmacGenerationQuarantinesBeforeClaimWithoutHotLoop() =
        runBlocking {
            val fixture = newIncrementalFixture("unsupported-positive-preclaim")
            val requestId = persistProtectedPull(fixture)
            fixture.database.openHelper.writableDatabase.apply {
                execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
                execSQL(
                    """
                    UPDATE sync_http_request
                    SET hmac_key_generation = 2
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(requestId),
                )
            }
            fixture.reopen()

            val candidate = fixture.database.syncTransportDao()
                .findOpenRequestsNeedingIntegrityRecovery(1)
                .single()
            assertEquals(requestId, candidate.requestIdentity)
            assertEquals(2L, candidate.hmacKeyGeneration)
            assertEquals("integer", candidate.hmacKeyGenerationStorageClass)
            assertEquals("2", candidate.hmacKeyGenerationQuoted)
            assertEquals(0L, candidate.attemptCount)

            assertMetadataFailure(fixture, SYNC_PULL, requestId)
            val recovered = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            )
            assertEquals(1, recovered.hmacKeyGeneration)
            assertEquals(0, recovered.attemptCount)
            assertEquals(32, recovered.rawBodyHmac.size)
            assertTrue(recovered.rawBodyHmac.all { it == 0.toByte() })
            assertEquals(
                0,
                SyncRequestPersistenceStore(fixture.database)
                    .recoverInvalidRequestMetadata(RECOVERY_UTC),
            )
            assertTrue(
                protectedStore(fixture).verifyAndClaim(
                    endpointId = SYNC_PULL,
                    requestIdentity = requestId,
                    attemptId = UUID.randomUUID().toString(),
                    attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                    updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                ) === ProtectedRequestClaim.NotClaimed,
            )
            val rejectedWrite = runCatching {
                fixture.database.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE sync_http_request
                    SET hmac_key_generation = 2
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(requestId),
                )
            }
            assertTrue(
                "Runtime guard accepted an unsupported HMAC generation",
                rejectedWrite.isFailure,
            )
        }

    @Test
    fun typeAwareGenerationAndAccessRecoveryIsOnePassAndKeepsHealthyFollower() =
        runBlocking {
            val fixture = newIncrementalFixture("type-aware-generation-access")
            val healthyRequestId = persistProtectedPull(fixture)
            val healthyBefore = requireNotNull(
                fixture.database.syncTransportDao()
                    .findRequest(SYNC_PULL, healthyRequestId),
            ).rawBodyHmac.copyOf()
            val hmacCases = listOf(
                sqliteCorruption("hmac-2", HMAC_GENERATION_COLUMN, 2L, "integer", "2", true),
                sqliteCorruption(
                    "hmac-int-overflow",
                    HMAC_GENERATION_COLUMN,
                    2_147_483_648L,
                    "integer",
                    "2147483648",
                    true,
                ),
                sqliteCorruption(
                    "hmac-uint-max",
                    HMAC_GENERATION_COLUMN,
                    4_294_967_295L,
                    "integer",
                    "4294967295",
                    true,
                ),
                sqliteCorruption(
                    "hmac-long-min",
                    HMAC_GENERATION_COLUMN,
                    Long.MIN_VALUE,
                    "integer",
                    Long.MIN_VALUE.toString(),
                    true,
                ),
                sqliteCorruption(
                    "hmac-long-max",
                    HMAC_GENERATION_COLUMN,
                    Long.MAX_VALUE,
                    "integer",
                    Long.MAX_VALUE.toString(),
                    true,
                ),
                sqliteCorruption(
                    "hmac-text",
                    HMAC_GENERATION_COLUMN,
                    "oops",
                    "text",
                    "'oops'",
                    true,
                ),
                sqliteCorruption(
                    "hmac-blob",
                    HMAC_GENERATION_COLUMN,
                    byteArrayOf(1),
                    "blob",
                    "X'01'",
                    true,
                ),
                sqliteCorruption(
                    "hmac-real",
                    HMAC_GENERATION_COLUMN,
                    1.5,
                    "real",
                    "1.5",
                    true,
                ),
            )
            val accessCases = listOf(
                sqliteCorruption(
                    "access-text",
                    ACCESS_GENERATION_COLUMN,
                    "oops",
                    "text",
                    "'oops'",
                    false,
                ),
                sqliteCorruption(
                    "access-blob",
                    ACCESS_GENERATION_COLUMN,
                    byteArrayOf(1),
                    "blob",
                    "X'01'",
                    false,
                ),
                sqliteCorruption(
                    "access-real",
                    ACCESS_GENERATION_COLUMN,
                    1.5,
                    "real",
                    "1.5",
                    false,
                ),
            )
            val cases = hmacCases + accessCases
            val originalHmacHex = mutableMapOf<String, String>()
            fixture.database.withTransaction {
                cases.forEach { case ->
                    val request = fixture.request(
                        endpointId = SYNC_PULL,
                        requestIdentity = case.requestIdentity,
                        credentialEpochId = HISTORICAL_EPOCH_ID,
                    )
                    originalHmacHex[case.requestIdentity] = request.rawBodyHmac.toHex()
                    fixture.database.syncTransportDao().insertRequest(request)
                }
            }
            fixture.database.openHelper.writableDatabase.apply {
                execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
                cases.forEach { case ->
                    execSQL(
                        """
                        UPDATE sync_http_request
                        SET ${case.column} = ?
                        WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                        """.trimIndent(),
                        arrayOf(case.value, case.requestIdentity),
                    )
                }
            }
            fixture.reopen()

            val candidates = fixture.database.syncTransportDao()
                .findOpenRequestsNeedingIntegrityRecovery(100)
                .associateBy { it.requestIdentity }
            assertEquals(cases.size, candidates.size)
            cases.forEach { case ->
                val candidate = requireNotNull(candidates[case.requestIdentity])
                val rawBefore = readRawRecoveryState(fixture, case.requestIdentity)
                assertTrue(candidate.hasCanonicalHmacStorage)
                assertTrue(candidate.hasCanonicalAttemptCount)
                assertEquals(0L, candidate.attemptCount)
                assertEquals("integer", candidate.attemptCountStorageClass)
                assertEquals("0", candidate.attemptCountQuoted)
                if (case.column == HMAC_GENERATION_COLUMN) {
                    assertFalse(candidate.hasCanonicalHmacKeyGeneration)
                    assertEquals(case.expectedStorageClass, candidate.hmacKeyGenerationStorageClass)
                    assertEquals(case.expectedQuotedValue, candidate.hmacKeyGenerationQuoted)
                    assertEquals(case.expectedStorageClass, rawBefore.hmacGenerationStorageClass)
                    assertEquals(case.expectedQuotedValue, rawBefore.hmacGenerationQuoted)
                    assertTrue(candidate.hasCanonicalAccessGeneration)
                    assertEquals(1L, candidate.accessGenerationUsed)
                } else {
                    assertTrue(candidate.hasCanonicalHmacKeyGeneration)
                    assertEquals(1L, candidate.hmacKeyGeneration)
                    assertFalse(candidate.hasCanonicalAccessGeneration)
                    assertEquals(case.expectedStorageClass, candidate.accessGenerationUsedStorageClass)
                    assertNull(candidate.accessGenerationUsed)
                    assertEquals(case.expectedStorageClass, rawBefore.accessGenerationStorageClass)
                    assertEquals(case.expectedQuotedValue, rawBefore.accessGenerationQuoted)
                }
            }

            val store = SyncRequestPersistenceStore(fixture.database)
            assertEquals(cases.size, store.recoverInvalidRequestMetadata(RECOVERY_UTC, 100))
            assertEquals(0, store.recoverInvalidRequestMetadata(RECOVERY_UTC, 100))
            cases.forEach { case ->
                val state = readRawRecoveryState(fixture, case.requestIdentity)
                assertNormalizedIntegrityFailure(state)
                if (!case.invalidatesHmac) {
                    assertEquals("null", state.accessGenerationStorageClass)
                    assertEquals("NULL", state.accessGenerationQuoted)
                    assertEquals(originalHmacHex[case.requestIdentity], state.rawBodyHmacHex)
                } else {
                    assertEquals("integer", state.accessGenerationStorageClass)
                    assertEquals("1", state.accessGenerationQuoted)
                    assertEquals(ZERO_HMAC_HEX, state.rawBodyHmacHex)
                }
            }
            val healthy = requireNotNull(
                fixture.database.syncTransportDao()
                    .findRequest(SYNC_PULL, healthyRequestId),
            )
            assertEquals("ready", healthy.state)
            assertEquals(0, healthy.attemptCount)
            assertEquals(1, healthy.hmacKeyGeneration)
            assertArrayEquals(healthyBefore, healthy.rawBodyHmac)
            assertEquals(
                listOf(healthyRequestId),
                fixture.database.syncTransportDao()
                    .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                    .map { it.requestIdentity },
            )
            val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
            assertEquals("incremental", stream.phase)
            assertNull(stream.integrityErrorCode)

            hmacCases.forEach { case ->
                val rejectedWrite = runCatching {
                    fixture.database.openHelper.writableDatabase.execSQL(
                        """
                        UPDATE sync_http_request
                        SET hmac_key_generation = ?
                        WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                        """.trimIndent(),
                        arrayOf(case.value, healthyRequestId),
                    )
                }
                assertTrue(
                    "Runtime guard accepted ${case.label}",
                    rejectedWrite.isFailure,
                )
            }
            assertArrayEquals(
                healthyBefore,
                requireNotNull(
                    fixture.database.syncTransportDao()
                        .findRequest(SYNC_PULL, healthyRequestId),
                ).rawBodyHmac,
            )
            healthyBefore.fill(0)
        }

    @Test
    fun compoundTypeAndRangeCorruptionNormalizesAtomicallyWithoutHotLoop() = runBlocking {
        val fixture = newIncrementalFixture("compound-type-range")
        val healthyRequestId = persistProtectedPull(fixture)
        val attemptCases = listOf(
            sqliteValue("attempt-int-overflow", 2_147_483_648L, "integer", "2147483648"),
            sqliteValue("attempt-text", "oops", "text", "'oops'"),
            sqliteValue("attempt-blob", byteArrayOf(1), "blob", "X'01'"),
            sqliteValue("attempt-real", 1.5, "real", "1.5"),
        )
        fixture.database.withTransaction {
            attemptCases.forEach { case ->
                fixture.database.syncTransportDao().insertRequest(
                    fixture.request(
                        endpointId = SYNC_PULL,
                        requestIdentity = case.requestIdentity,
                        credentialEpochId = HISTORICAL_EPOCH_ID,
                    ),
                )
            }
        }
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            attemptCases.forEach { case ->
                execSQL(
                    """
                    UPDATE sync_http_request
                    SET hmac_key_generation = CAST('oops' AS TEXT),
                        raw_body_hmac = X'01',
                        access_generation_used = CAST('oops' AS TEXT),
                        attempt_count = ?
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(case.value, case.requestIdentity),
                )
            }
        }
        fixture.reopen()

        val candidates = fixture.database.syncTransportDao()
            .findOpenRequestsNeedingIntegrityRecovery(100)
            .associateBy { it.requestIdentity }
        assertEquals(attemptCases.size, candidates.size)
        attemptCases.forEach { case ->
            val candidate = requireNotNull(candidates[case.requestIdentity])
            assertNull(candidate.hmacKeyGeneration)
            assertEquals("text", candidate.hmacKeyGenerationStorageClass)
            assertEquals("'oops'", candidate.hmacKeyGenerationQuoted)
            assertNull(candidate.accessGenerationUsed)
            assertEquals("text", candidate.accessGenerationUsedStorageClass)
            assertEquals("blob", candidate.rawBodyHmacStorageClass)
            assertEquals(1, candidate.rawBodyHmacOctetCount)
            assertEquals(case.expectedStorageClass, candidate.attemptCountStorageClass)
            assertEquals(case.expectedQuotedValue, candidate.attemptCountQuoted)
            if (case.expectedStorageClass == "integer") {
                assertEquals(2_147_483_648L, candidate.attemptCount)
            } else {
                assertNull(candidate.attemptCount)
            }
        }

        val store = SyncRequestPersistenceStore(fixture.database)
        assertEquals(
            attemptCases.size,
            store.recoverInvalidRequestMetadata(RECOVERY_UTC, 100),
        )
        assertEquals(0, store.recoverInvalidRequestMetadata(RECOVERY_UTC, 100))
        attemptCases.forEach { case ->
            val state = readRawRecoveryState(fixture, case.requestIdentity)
            assertNormalizedIntegrityFailure(state)
            assertEquals("null", state.accessGenerationStorageClass)
            assertEquals("NULL", state.accessGenerationQuoted)
            assertEquals("integer", state.attemptCountStorageClass)
            assertEquals("0", state.attemptCountQuoted)
            assertEquals(ZERO_HMAC_HEX, state.rawBodyHmacHex)
        }
        assertEquals(
            listOf(healthyRequestId),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10)
                .map { it.requestIdentity },
        )
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("incremental", stream.phase)
        assertNull(stream.integrityErrorCode)
    }

    @Test
    fun freshPullReducerPreflightRunsAfterStaleClassificationAndBeforeMutation() =
        runBlocking {
            val fixture = newIncrementalFixture("fresh-pull-preflight")
            val requestId = persistProtectedPull(fixture)
            val attemptId = UUID.randomUUID().toString()
            val claim = protectedStore(fixture).verifyAndClaim(
                endpointId = SYNC_PULL,
                requestIdentity = requestId,
                attemptId = attemptId,
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.NOW_MS + LEASE_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            assertTrue(claim is ProtectedRequestClaim.Claimed)
            (claim as ProtectedRequestClaim.Claimed).request.close()
            val retainedHmac = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            ).rawBodyHmac.copyOf()
            fixture.database.openHelper.writableDatabase.apply {
                execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
                execSQL(
                    """
                    UPDATE sync_http_request
                    SET hmac_key_generation = 2
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(requestId),
                )
            }
            fixture.reopen()

            val responseBody = "verified-empty-pull".toByteArray(StandardCharsets.UTF_8)
            val response = TerminalHttpResponsePersistence(
                endpointId = SYNC_PULL,
                requestIdentity = requestId,
                expectedAttemptId = attemptId,
                httpStatus = 200,
                exactResponseBody = responseBody,
                responseSha256 = sha256Hex(responseBody),
                terminalAtUtc = RECOVERY_UTC,
                terminalErrorCode = null,
            )
            val pageId = UUID.randomUUID().toString()
            val receipt = SyncPageReceiptEntity(
                pageId = pageId,
                endpointId = SYNC_PULL,
                requestIdentity = requestId,
                bootstrapId = null,
                pageIndex = 0,
                snapshotId = null,
                fromCursor = DEFAULT_CURSOR,
                nextCursor = NEXT_CURSOR,
                incrementalCursor = null,
                pageSha256 = sha256Hex("empty-page".toByteArray(StandardCharsets.UTF_8)),
                changeCount = 0,
                completeOrHasMore = false,
                state = "applied",
                firstServerSequence = null,
                lastServerSequence = null,
                receivedAtUtc = RECOVERY_UTC,
                appliedAtUtc = RECOVERY_UTC,
            )
            val store = SyncPersistenceStore(fixture.database)
            store.commitPullPage(
                response = response.copy(expectedAttemptId = UUID.randomUUID().toString()),
                receipt = receipt,
                changes = emptyList(),
            )
            val afterStale = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            )
            assertEquals("sending", afterStale.state)
            assertEquals(1, afterStale.attemptCount)
            assertEquals(attemptId, afterStale.activeAttemptId)
            assertEquals(2, afterStale.hmacKeyGeneration)
            assertArrayEquals(retainedHmac, afterStale.rawBodyHmac)
            assertNull(afterStale.terminalHttpStatus)
            assertNull(afterStale.exactResponseBody)
            assertNull(afterStale.responseSha256)
            assertNull(fixture.database.syncReplicaDao().findPageReceipt(pageId))
            val afterStaleStream = requireNotNull(
                fixture.database.syncReplicaDao().findStreamState(),
            )
            assertEquals("incremental", afterStaleStream.phase)
            assertEquals(DEFAULT_CURSOR, afterStaleStream.appliedCursor)
            assertNull(afterStaleStream.integrityErrorCode)

            store.commitPullPage(response, receipt, emptyList())
            val quarantined = requireNotNull(
                fixture.database.syncTransportDao().findRequest(SYNC_PULL, requestId),
            )
            assertEquals("integrity_failure", quarantined.state)
            assertEquals("request_body_metadata_invalid", quarantined.terminalErrorCode)
            assertEquals(RECOVERY_UTC, quarantined.terminalAtUtc)
            assertEquals(1, quarantined.attemptCount)
            assertEquals(1, quarantined.hmacKeyGeneration)
            assertNull(quarantined.activeAttemptId)
            assertNull(quarantined.leaseExpiresAtEpochMs)
            assertNull(quarantined.terminalHttpStatus)
            assertNull(quarantined.exactResponseBody)
            assertNull(quarantined.responseSha256)
            assertEquals(32, quarantined.rawBodyHmac.size)
            assertTrue(quarantined.rawBodyHmac.all { it == 0.toByte() })
            assertNull(fixture.database.syncReplicaDao().findPageReceipt(pageId))
            val halted = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
            assertEquals("integrity_halted", halted.phase)
            assertEquals("request_body_metadata_invalid", halted.integrityErrorCode)
            assertEquals(DEFAULT_CURSOR, halted.appliedCursor)
            assertEquals(
                0,
                SyncRequestPersistenceStore(fixture.database)
                    .recoverInvalidRequestMetadata(RECOVERY_UTC),
            )
            retainedHmac.fill(0)
            responseBody.fill(0)
        }

    private suspend fun assertMetadataFailure(
        fixture: SyncM2PersistenceFixture,
        endpointId: String,
        requestIdentity: String,
    ) {
        val claim = protectedStore(fixture).verifyAndClaim(
            endpointId = endpointId,
            requestIdentity = requestIdentity,
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
            fixture.database.syncTransportDao().findRequest(endpointId, requestIdentity),
        )
        assertEquals("integrity_failure", quarantined.state)
        assertEquals("request_body_metadata_invalid", quarantined.terminalErrorCode)
        assertEquals(0, quarantined.attemptCount)
        assertNull(quarantined.activeAttemptId)
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("integrity_halted", stream.phase)
        assertEquals("request_body_metadata_invalid", stream.integrityErrorCode)
    }

    private suspend fun assertRevokeRequestMetadataQuarantined(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
        expectedAttemptCount: Int,
        expectedTerminalAtUtc: String,
    ): SyncHttpRequestEntity {
        val request = requireNotNull(
            fixture.database.syncTransportDao().findRequest(AUTH_REVOKE, requestIdentity),
        )
        assertEquals("integrity_failure", request.state)
        assertEquals("request_body_metadata_invalid", request.terminalErrorCode)
        assertEquals(expectedTerminalAtUtc, request.terminalAtUtc)
        assertEquals(expectedAttemptCount, request.attemptCount)
        assertNull(request.nextAttemptAtEpochMs)
        assertNull(request.leaseExpiresAtEpochMs)
        assertNull(request.activeAttemptId)
        assertNull(request.terminalHttpStatus)
        return request
    }

    private suspend fun assertCurrentRevokeFamilyIntegrityFailure(
        fixture: SyncM2PersistenceFixture,
    ) {
        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals(SyncM2PersistenceFixture.EPOCH_ID, auth.credentialEpochId)
        assertEquals(SyncM2PersistenceFixture.DEVICE_ID, auth.deviceId)
        assertEquals(1L, auth.generation)
        assertEquals("integrity_failure", auth.state)
        assertEquals("request_body_metadata_invalid", auth.failureCode)
        assertNull(auth.refreshTokenCiphertext)
        assertNull(auth.refreshTokenNonce)
        assertNull(auth.refreshTokenKeyAlias)
        val stream = requireNotNull(fixture.database.syncReplicaDao().findStreamState())
        assertEquals("incremental", stream.phase)
        assertNull(stream.integrityErrorCode)
    }

    private suspend fun persistProtectedPull(fixture: SyncM2PersistenceFixture): String {
        val request = pullRequest()
        val requestId = request.requestId
        protectedStore(fixture).persistPull(request, persistence())
        return requestId
    }

    private suspend fun persistRevoke(fixture: SyncM2PersistenceFixture): String {
        val requestId = UUID.randomUUID().toString()
        protectedStore(fixture).beginRevoke(
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
        val stored = requireNotNull(
            fixture.database.syncTransportDao().findRequest(AUTH_REVOKE, requestId),
        )
        additionalKeystoreAliases += checkNotNull(stored.sealedBodyKeyAlias)
        assertEquals("revoke_pending", fixture.database.syncAuthDao().findState()?.state)
        return requestId
    }

    private fun pullRequest() = PullRequest(
        requestId = UUID.randomUUID().toString(),
        deviceId = SyncM2PersistenceFixture.DEVICE_ID,
        cursor = DEFAULT_CURSOR,
        pageSize = 100,
    )

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

    private fun installExpiredExhaustedWaitingRefreshTextHmac(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
        textOctetCount: Int,
    ) {
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            execSQL(
                """
                UPDATE sync_http_request
                SET raw_body_hmac = CAST(? AS TEXT),
                    state = 'waiting_refresh',
                    attempt_count = attempt_budget,
                    deadline_at_epoch_ms = ?,
                    next_attempt_at_epoch_ms = NULL,
                    lease_expires_at_epoch_ms = NULL,
                    active_attempt_id = NULL
                WHERE endpoint_id = 'sync_pull'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    "T".repeat(textOctetCount),
                    SyncM2PersistenceFixture.NOW_MS,
                    requestIdentity,
                ),
            )
        }
    }

    private fun installExpiredExhaustedWaitingRefreshBlobHmac(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
        hmacOctetCount: Int,
    ) {
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            execSQL(
                """
                UPDATE sync_http_request
                SET raw_body_hmac = ?,
                    state = 'waiting_refresh',
                    attempt_count = attempt_budget,
                    deadline_at_epoch_ms = ?,
                    next_attempt_at_epoch_ms = NULL,
                    lease_expires_at_epoch_ms = NULL,
                    active_attempt_id = NULL
                WHERE endpoint_id = 'sync_pull'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    ByteArray(hmacOctetCount) { 0x5a },
                    SyncM2PersistenceFixture.NOW_MS,
                    requestIdentity,
                ),
            )
        }
    }

    private fun installExpiredExhaustedWaitingRefreshGeneration(
        fixture: SyncM2PersistenceFixture,
        endpointId: String,
        requestIdentity: String,
        accessGenerationUsed: Long?,
    ) {
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            execSQL(
                """
                UPDATE sync_http_request
                SET access_generation_used = ?,
                    state = 'waiting_refresh',
                    attempt_count = attempt_budget,
                    deadline_at_epoch_ms = ?,
                    next_attempt_at_epoch_ms = NULL,
                    lease_expires_at_epoch_ms = NULL,
                    active_attempt_id = NULL
                WHERE endpoint_id = ? AND request_identity = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    accessGenerationUsed,
                    SyncM2PersistenceFixture.NOW_MS,
                    endpointId,
                    requestIdentity,
                ),
            )
        }
    }

    private fun installReadyLegacyCorruption(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
        corruption: String,
        textOctetCount: Int?,
    ) {
        fixture.database.openHelper.writableDatabase.apply {
            execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_update")
            when (corruption) {
                LEGACY_HMAC_GENERATION -> execSQL(
                    """
                    UPDATE sync_http_request
                    SET hmac_key_generation = 0
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(requestIdentity),
                )

                LEGACY_TEXT_HMAC -> execSQL(
                    """
                    UPDATE sync_http_request
                    SET raw_body_hmac = CAST(? AS TEXT)
                    WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                    """.trimIndent(),
                    arrayOf("T".repeat(checkNotNull(textOctetCount)), requestIdentity),
                )

                else -> error("Unknown legacy corruption fixture")
            }
        }
    }

    private fun sqliteCorruption(
        label: String,
        column: String,
        value: Any,
        expectedStorageClass: String,
        expectedQuotedValue: String,
        invalidatesHmac: Boolean,
    ) = SqliteMetadataCorruption(
        label = label,
        requestIdentity = UUID.randomUUID().toString(),
        column = column,
        value = value,
        expectedStorageClass = expectedStorageClass,
        expectedQuotedValue = expectedQuotedValue,
        invalidatesHmac = invalidatesHmac,
    )

    private fun sqliteValue(
        label: String,
        value: Any,
        expectedStorageClass: String,
        expectedQuotedValue: String,
    ) = SqliteValueCase(
        label = label,
        requestIdentity = UUID.randomUUID().toString(),
        value = value,
        expectedStorageClass = expectedStorageClass,
        expectedQuotedValue = expectedQuotedValue,
    )

    private fun readRawRecoveryState(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
    ): RawRecoveryState = fixture.database.openHelper.readableDatabase.query(
        """
        SELECT typeof(hmac_key_generation),
               quote(hmac_key_generation),
               typeof(access_generation_used),
               quote(access_generation_used),
               typeof(attempt_count),
               quote(attempt_count),
               typeof(raw_body_hmac),
               length(CAST(raw_body_hmac AS BLOB)),
               hex(CAST(raw_body_hmac AS BLOB)),
               state,
               terminal_error_code,
               terminal_at_utc,
               active_attempt_id,
               lease_expires_at_epoch_ms,
               next_attempt_at_epoch_ms
        FROM sync_http_request
        WHERE endpoint_id = 'sync_pull' AND request_identity = ?
        """.trimIndent(),
        arrayOf(requestIdentity),
    ).use { cursor ->
        check(cursor.moveToFirst())
        RawRecoveryState(
            hmacGenerationStorageClass = cursor.getString(0),
            hmacGenerationQuoted = cursor.getString(1),
            accessGenerationStorageClass = cursor.getString(2),
            accessGenerationQuoted = cursor.getString(3),
            attemptCountStorageClass = cursor.getString(4),
            attemptCountQuoted = cursor.getString(5),
            rawBodyHmacStorageClass = cursor.getString(6),
            rawBodyHmacOctetCount = cursor.getInt(7),
            rawBodyHmacHex = cursor.getString(8),
            state = cursor.getString(9),
            terminalErrorCode = cursor.getString(10),
            terminalAtUtc = cursor.getString(11),
            activeAttemptId = cursor.getString(12),
            leaseExpiresAtEpochMs = if (cursor.isNull(13)) null else cursor.getLong(13),
            nextAttemptAtEpochMs = if (cursor.isNull(14)) null else cursor.getLong(14),
        )
    }

    private fun assertNormalizedIntegrityFailure(state: RawRecoveryState) {
        assertEquals("integer", state.hmacGenerationStorageClass)
        assertEquals("1", state.hmacGenerationQuoted)
        assertEquals("integer", state.attemptCountStorageClass)
        assertEquals("0", state.attemptCountQuoted)
        assertEquals("blob", state.rawBodyHmacStorageClass)
        assertEquals(32, state.rawBodyHmacOctetCount)
        assertEquals("integrity_failure", state.state)
        assertEquals("request_body_metadata_invalid", state.terminalErrorCode)
        assertEquals(RECOVERY_UTC, state.terminalAtUtc)
        assertNull(state.activeAttemptId)
        assertNull(state.leaseExpiresAtEpochMs)
        assertNull(state.nextAttemptAtEpochMs)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte) }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex().lowercase()

    private fun countRequestsInState(
        fixture: SyncM2PersistenceFixture,
        state: String,
    ): Int = fixture.database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM sync_http_request WHERE state = ?",
        arrayOf(state),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun readHmacStorage(
        fixture: SyncM2PersistenceFixture,
        requestIdentity: String,
    ): HmacStorage = fixture.database.openHelper.readableDatabase.query(
        """
        SELECT typeof(raw_body_hmac), length(CAST(raw_body_hmac AS BLOB))
        FROM sync_http_request
        WHERE endpoint_id = 'sync_pull' AND request_identity = ?
        """.trimIndent(),
        arrayOf(requestIdentity),
    ).use { cursor ->
        check(cursor.moveToFirst())
        HmacStorage(
            storageClass = cursor.getString(0),
            octetCount = cursor.getInt(1),
        )
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

    private fun protectedStore(fixture: SyncM2PersistenceFixture) =
        ProtectedSyncRequestStore(context, fixture.database, keyring())

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

    private fun deleteMarkerArtifacts() {
        val marker = File(context.noBackupFilesDir, markerRelativePath)
        listOf("", ".bak", ".new").forEach { suffix ->
            File(marker.path + suffix).delete()
        }
    }

    private fun deleteKeystoreAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private data class HmacStorage(
        val storageClass: String,
        val octetCount: Int,
    )

    private data class SqliteMetadataCorruption(
        val label: String,
        val requestIdentity: String,
        val column: String,
        val value: Any,
        val expectedStorageClass: String,
        val expectedQuotedValue: String,
        val invalidatesHmac: Boolean,
    )

    private data class SqliteValueCase(
        val label: String,
        val requestIdentity: String,
        val value: Any,
        val expectedStorageClass: String,
        val expectedQuotedValue: String,
    )

    private data class RawRecoveryState(
        val hmacGenerationStorageClass: String,
        val hmacGenerationQuoted: String,
        val accessGenerationStorageClass: String,
        val accessGenerationQuoted: String,
        val attemptCountStorageClass: String,
        val attemptCountQuoted: String,
        val rawBodyHmacStorageClass: String,
        val rawBodyHmacOctetCount: Int,
        val rawBodyHmacHex: String,
        val state: String,
        val terminalErrorCode: String?,
        val terminalAtUtc: String?,
        val activeAttemptId: String?,
        val leaseExpiresAtEpochMs: Long?,
        val nextAttemptAtEpochMs: Long?,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AUTH_REVOKE = "auth_revoke"
        const val SYNC_PULL = "sync_pull"
        const val SYNC_BOOTSTRAP = "sync_bootstrap"
        const val HMAC_GENERATION_COLUMN = "hmac_key_generation"
        const val ACCESS_GENERATION_COLUMN = "access_generation_used"
        const val LEASE_MS = 60_000L
        const val HISTORICAL_EPOCH_ID = "b3000000-0000-4000-8000-000000000011"
        const val REPLACEMENT_EPOCH_ID = "b3000000-0000-4000-8000-000000000010"
        const val REPLACEMENT_DEVICE_ID = "b4000000-0000-4000-8000-000000000010"
        const val RECOVERY_UTC = "2030-01-01T00:00:01Z"
        const val VALID_REFRESH_TOKEN =
            "lar_RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRE"
        const val LEGACY_HMAC_GENERATION = "hmac_generation"
        const val LEGACY_TEXT_HMAC = "text_hmac"
        const val INVALID_WAITING_HMAC = "waiting_hmac"
        const val INVALID_WAITING_ACCESS = "waiting_access"
        const val MAX_INTEGRITY_RECOVERY_ROWS = 1_000
        val ZERO_HMAC_HEX: String = "00".repeat(32)
        val DEFAULT_CURSOR: String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32) { index -> (index + 1).toByte() },
        )
        val NEXT_CURSOR: String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32) { index -> (index + 33).toByte() },
        )
    }
}
