package ru.andriyshkoy.lifeagent.persistence

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.CredentialRecoveryAction
import ru.andriyshkoy.lifeagent.data.local.db.EnrollmentSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.RefreshSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.SyncRequestPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec

@RunWith(AndroidJUnit4::class)
class SyncAuthRecoveryInstrumentedTest {
    private lateinit var fixture: SyncM2PersistenceFixture
    private lateinit var store: SyncAuthPersistenceStore

    @Before
    fun setUp() {
        fixture = SyncM2PersistenceFixture(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            label = "m2-auth-recovery",
        )
        store = SyncAuthPersistenceStore(fixture.database)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun initialEnrollmentCommitsIdentityAuthStreamAndBootstrapAtomically() =
        runBlocking {
            fixture.seedIdentity()
            val attempt = fixture.enrollmentAttempt()
            store.beginEnrollment(attempt)
            val intent = fixture.bootstrapIntent(pageSize = 500)
            val auth = fixture.authState(bootstrapRequired = true)

            commitEnrollmentSuccessForTest(
                EnrollmentSuccessPersistence(
                    attemptRequestId = attempt.requestId,
                    authState = auth,
                    accessFingerprint = fixture.fingerprint(
                        credentialEpochId = auth.credentialEpochId,
                        generation = 1,
                        tokenKind = "access",
                        seed = 1,
                    ),
                    refreshFingerprint = fixture.fingerprint(
                        credentialEpochId = auth.credentialEpochId,
                        generation = 1,
                        tokenKind = "refresh",
                        seed = 2,
                    ),
                    streamState = fixture.streamState(bootstrapRequired = true),
                    bootstrapSession = intent.session,
                ),
                intent.firstRequest,
            )

            val identity = requireNotNull(fixture.database.identityDao().findIdentity())
            assertEquals(SyncM2PersistenceFixture.DEVICE_ID, identity.serverDeviceId)
            assertEquals(SyncM2PersistenceFixture.PERSON_ID, identity.serverPersonId)
            assertEquals("active", fixture.database.syncAuthDao().findState()?.state)
            assertEquals(
                "bootstrap_required",
                fixture.database.syncReplicaDao().findStreamState()?.phase,
            )
            assertNotNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(intent.session.bootstrapId),
            )
            assertEquals(
                "ready",
                fixture.database.syncTransportDao()
                    .findRequest(
                        "sync_bootstrap",
                        intent.firstRequest.requestIdentity,
                    )
                    ?.state,
            )
            assertEquals(
                "completed",
                fixture.database.syncAuthDao().findAttempt(attempt.requestId)?.state,
            )
        }

    @Test
    fun initialEnrollmentRejectsBootstrapPageSizeAboveContractMaximum() =
        runBlocking {
            fixture.seedIdentity()
            val attempt = fixture.enrollmentAttempt()
            store.beginEnrollment(attempt)
            val intent = fixture.bootstrapIntent(pageSize = 501)
            val auth = fixture.authState(bootstrapRequired = true)

            assertTrue(
                runCatching {
                    commitEnrollmentSuccessForTest(
                        EnrollmentSuccessPersistence(
                            attemptRequestId = attempt.requestId,
                            authState = auth,
                            accessFingerprint = fixture.fingerprint(
                                credentialEpochId = auth.credentialEpochId,
                                generation = 1,
                                tokenKind = "access",
                                seed = 3,
                            ),
                            refreshFingerprint = fixture.fingerprint(
                                credentialEpochId = auth.credentialEpochId,
                                generation = 1,
                                tokenKind = "refresh",
                                seed = 4,
                            ),
                            streamState =
                                fixture.streamState(bootstrapRequired = true),
                            bootstrapSession = intent.session,
                        ),
                        intent.firstRequest,
                    )
                }.isFailure,
            )

            val identity = requireNotNull(
                fixture.database.identityDao().findIdentity(),
            )
            assertNull(identity.serverDeviceId)
            assertNull(identity.serverPersonId)
            assertNull(fixture.database.syncAuthDao().findState())
            assertNull(fixture.database.syncReplicaDao().findStreamState())
            assertNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(intent.session.bootstrapId),
            )
            assertNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_bootstrap",
                    intent.firstRequest.requestIdentity,
                ),
            )
            assertEquals(
                "dispatching",
                fixture.database.syncAuthDao().findAttempt(attempt.requestId)?.state,
            )
        }

    @Test
    fun lateEnrollmentInsertFailureRollsBackEveryEarlierProjection() = runBlocking {
        fixture.seedIdentity()
        val attempt = fixture.enrollmentAttempt()
        store.beginEnrollment(attempt)
        val intent = fixture.bootstrapIntent()
        fixture.database.syncTransportDao().insertRequest(intent.firstRequest)
        val auth = fixture.authState(bootstrapRequired = true)

        assertTrue(
            runCatching {
                commitEnrollmentSuccessForTest(
                    EnrollmentSuccessPersistence(
                        attemptRequestId = attempt.requestId,
                        authState = auth,
                        accessFingerprint = fixture.fingerprint(
                            credentialEpochId = auth.credentialEpochId,
                            generation = 1,
                            tokenKind = "access",
                            seed = 3,
                        ),
                        refreshFingerprint = fixture.fingerprint(
                            credentialEpochId = auth.credentialEpochId,
                            generation = 1,
                            tokenKind = "refresh",
                            seed = 4,
                        ),
                        streamState = fixture.streamState(bootstrapRequired = true),
                        bootstrapSession = intent.session,
                    ),
                    intent.firstRequest,
                )
            }.isFailure,
        )

        val identity = requireNotNull(fixture.database.identityDao().findIdentity())
        assertNull(identity.serverDeviceId)
        assertNull(identity.serverPersonId)
        assertNull(fixture.database.syncAuthDao().findState())
        assertNull(fixture.database.syncReplicaDao().findStreamState())
        assertNull(
            fixture.database.syncReplicaDao()
                .findBootstrapSession(intent.session.bootstrapId),
        )
        assertEquals(
            "dispatching",
            fixture.database.syncAuthDao().findAttempt(attempt.requestId)?.state,
        )
        assertEquals(
            "ready",
            fixture.database.syncTransportDao()
                .findRequest(
                    "sync_bootstrap",
                    intent.firstRequest.requestIdentity,
                )
                ?.state,
        )
    }

    @Test
    fun lateReplacementEnrollmentCannotOverwriteAChangedCredentialFamily() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val attempt = fixture.enrollmentAttempt(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
            )
            store.beginEnrollment(attempt)

            assertEquals(
                1,
                fixture.database.syncAuthDao().deleteExactFamily(
                    credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                    deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                    generation = 1,
                ),
            )
            val replacementEpoch = "b3000000-0000-4000-8000-000000000002"
            fixture.installActiveAuth(credentialEpochId = replacementEpoch)

            val lateEpoch = "c3000000-0000-4000-8000-000000000002"
            val lateIntent = fixture.bootstrapIntent(
                credentialEpochId = lateEpoch,
            )
            val lateAuth = fixture.authState(
                credentialEpochId = lateEpoch,
                bootstrapRequired = true,
            )
            assertTrue(
                runCatching {
                    commitEnrollmentSuccessForTest(
                        EnrollmentSuccessPersistence(
                            attemptRequestId = attempt.requestId,
                            authState = lateAuth,
                            accessFingerprint = fixture.fingerprint(
                                credentialEpochId = lateEpoch,
                                generation = 1,
                                tokenKind = "access",
                                seed = 11,
                            ),
                            refreshFingerprint = fixture.fingerprint(
                                credentialEpochId = lateEpoch,
                                generation = 1,
                                tokenKind = "refresh",
                                seed = 12,
                            ),
                            streamState = fixture.streamState(
                                credentialEpochId = lateEpoch,
                                bootstrapRequired = true,
                            ),
                            bootstrapSession = lateIntent.session,
                        ),
                        lateIntent.firstRequest,
                    )
                }.isFailure,
            )

            assertEquals(
                replacementEpoch,
                fixture.database.syncAuthDao().findState()?.credentialEpochId,
            )
            assertEquals(
                SyncM2PersistenceFixture.EPOCH_ID,
                fixture.database.syncReplicaDao()
                    .findStreamState()
                    ?.credentialEpochId,
            )
            assertNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(lateIntent.session.bootstrapId),
            )
            assertNull(
                fixture.database.syncTransportDao().findRequest(
                    "sync_bootstrap",
                    lateIntent.firstRequest.requestIdentity,
                ),
            )
            assertEquals(
                "dispatching",
                fixture.database.syncAuthDao()
                    .findAttempt(attempt.requestId)
                    ?.state,
            )
        }

    @Test
    fun refreshSuccessReleasesOnlyLiveExactWaitersAndExpiresTheRest() = runBlocking {
        seedCurrentIncrementalFamily()
        val liveRequestId = UUID.randomUUID().toString()
        val expiredRequestId = UUID.randomUUID().toString()
        prepareSendingRequest(liveRequestId, SyncM2PersistenceFixture.DEADLINE_MS)
        prepareSendingRequest(
            expiredRequestId,
            SyncM2PersistenceFixture.NOW_MS + 500,
        )
        val refreshAttempt = fixture.refreshAttempt()
        fixture.database.syncAuthDao().claimRefreshAttempt(
            entity = refreshAttempt,
            nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 100,
        )
        listOf(liveRequestId, expiredRequestId).forEach { requestId ->
            val retryAt = if (requestId == expiredRequestId) {
                SyncM2PersistenceFixture.NOW_MS + 300
            } else {
                SyncM2PersistenceFixture.NOW_MS + 1_000
            }
            assertEquals(
                CredentialRecoveryAction.WAITING_FOR_REFRESH,
                store.handleTrustedSyncUnauthorized(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    expectedAttemptId = attemptId(requestId),
                    failedAccessGeneration = 1,
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 200,
                    nextAttemptAtEpochMs = retryAt,
                    updatedAtUtc = instant(SyncM2PersistenceFixture.NOW_MS + 200),
                ),
            )
        }
        val committedAtMs = SyncM2PersistenceFixture.NOW_MS + 1_000
        store.commitRefreshSuccess(
            refreshSuccess(
                requestId = refreshAttempt.requestId,
                committedAtMs = committedAtMs,
            ),
        )

        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals("active", auth.state)
        assertEquals(2L, auth.generation)
        assertEquals(
            "completed",
            fixture.database.syncAuthDao().findAttempt(refreshAttempt.requestId)?.state,
        )
        val live = fixture.database.syncTransportDao()
            .findRequest("sync_pull", liveRequestId)
        assertEquals("retry_wait", live?.state)
        assertEquals(2L, live?.accessGenerationUsed)
        assertEquals(1, live?.originalRetryCount)
        val expired = fixture.database.syncTransportDao()
            .findRequest("sync_pull", expiredRequestId)
        assertEquals("terminal_local", expired?.state)
        assertEquals("credential_recovery_expired", expired?.terminalErrorCode)
    }

    @Test
    fun interruptedRefreshQuarantinesFamilyAndEveryExactWaiter() = runBlocking {
        seedCurrentIncrementalFamily()
        val requestId = UUID.randomUUID().toString()
        prepareSendingRequest(requestId, SyncM2PersistenceFixture.DEADLINE_MS)
        val refreshAttempt = fixture.refreshAttempt()
        fixture.database.syncAuthDao().claimRefreshAttempt(
            entity = refreshAttempt,
            nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 100,
        )
        assertEquals(
            CredentialRecoveryAction.WAITING_FOR_REFRESH,
            store.handleTrustedSyncUnauthorized(
                endpointId = "sync_pull",
                requestIdentity = requestId,
                expectedAttemptId = attemptId(requestId),
                failedAccessGeneration = 1,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 200,
                nextAttemptAtEpochMs = SyncM2PersistenceFixture.NOW_MS + 1_000,
                updatedAtUtc = instant(SyncM2PersistenceFixture.NOW_MS + 200),
            ),
        )
        fixture.reopen()
        store = SyncAuthPersistenceStore(fixture.database)

        val recovery = store.recoverInterruptedAuthFlows(
            updatedAtUtc = instant(SyncM2PersistenceFixture.NOW_MS + 300),
        )
        assertEquals(1, recovery.recoveredCount)
        assertTrue(recovery.currentAuthorityChanged)
        val auth = requireNotNull(fixture.database.syncAuthDao().findState())
        assertEquals("quarantined", auth.state)
        assertNull(auth.refreshTokenCiphertext)
        assertEquals(
            "outcome_unknown",
            fixture.database.syncAuthDao().findAttempt(refreshAttempt.requestId)?.state,
        )
        val request = fixture.database.syncTransportDao()
            .findRequest("sync_pull", requestId)
        assertEquals("terminal_local", request?.state)
        assertEquals("refresh_interrupted", request?.terminalErrorCode)
        assertEquals(
            emptyList<Any>(),
            fixture.database.syncTransportDao()
                .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS + 300, 10),
        )
    }

    @Test
    fun restartQuarantinesWaiterPersistedBeforeRefreshClaim() = runBlocking {
        seedCurrentIncrementalFamily()
        val requestId = UUID.randomUUID().toString()
        prepareSendingRequest(requestId, SyncM2PersistenceFixture.DEADLINE_MS)
        assertEquals(
            CredentialRecoveryAction.WAITING_FOR_REFRESH,
            store.handleTrustedSyncUnauthorized(
                endpointId = "sync_pull",
                requestIdentity = requestId,
                expectedAttemptId = attemptId(requestId),
                failedAccessGeneration = 1,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 200,
                nextAttemptAtEpochMs = SyncM2PersistenceFixture.NOW_MS + 1_000,
                updatedAtUtc = instant(SyncM2PersistenceFixture.NOW_MS + 200),
            ),
        )
        assertEquals("active", fixture.database.syncAuthDao().findState()?.state)
        fixture.reopen()
        store = SyncAuthPersistenceStore(fixture.database)
        val recovery = store.recoverInterruptedAuthFlows(
            updatedAtUtc = instant(SyncM2PersistenceFixture.NOW_MS + 300),
        )
        assertEquals(1, recovery.recoveredCount)
        assertTrue(recovery.currentAuthorityChanged)
        assertEquals(
            "quarantined",
            fixture.database.syncAuthDao().findState()?.state,
        )
        val request = fixture.database.syncTransportDao()
            .findRequest("sync_pull", requestId)
        assertEquals("terminal_local", request?.state)
        assertEquals("orphan_waiting_refresh", request?.terminalErrorCode)
    }

    @Test
    fun refreshAndRevokeCompeteForOneCurrentFamilyWinner() = runBlocking {
        seedCurrentIncrementalFamily()
        val revoke = fixture.sealedRevokeRequest()
        val refresh = fixture.refreshAttempt()

        val outcomes = coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    runCatching {
                        beginRevokeForTest(
                            request = revoke,
                            nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                        )
                    }.isSuccess
                },
                async(Dispatchers.Default) {
                    runCatching {
                        fixture.database.syncAuthDao().claimRefreshAttempt(
                            entity = refresh,
                            nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        )
                    }.isSuccess
                },
            ).awaitAll()
        }

        assertEquals(1, outcomes.count { it })
        assertTrue(
            fixture.database.syncAuthDao().findState()?.state in
                setOf("revoke_pending", "refresh_in_flight"),
        )
    }

    @Test
    fun concurrentRefreshClaimsHaveExactlyOneFamilyWinner() = runBlocking {
        seedCurrentIncrementalFamily()
        val attempts = listOf(
            fixture.refreshAttempt(),
            fixture.refreshAttempt(),
        )

        val winners = coroutineScope {
            attempts.map { attempt ->
                async(Dispatchers.Default) {
                    runCatching {
                        fixture.database.syncAuthDao().claimRefreshAttempt(
                            entity = attempt,
                            nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                        )
                    }.isSuccess
                }
            }.awaitAll()
        }

        assertEquals(1, winners.count { it })
        assertEquals(
            1,
            attempts.count {
                fixture.database.syncAuthDao().findAttempt(it.requestId) != null
            },
        )
        assertEquals(
            "refresh_in_flight",
            fixture.database.syncAuthDao().findState()?.state,
        )
    }

    @Test
    fun refreshClaimRejectsAttemptFromAnotherLocalIdentity() = runBlocking {
        seedCurrentIncrementalFamily()
        val attempt = fixture.refreshAttempt().copy(
            installationId = "e1000000-0000-4000-8000-000000000001",
        )

        assertTrue(
            runCatching {
                fixture.database.syncAuthDao().claimRefreshAttempt(
                    entity = attempt,
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                )
            }.isFailure,
        )
        assertNull(
            fixture.database.syncAuthDao().findAttempt(attempt.requestId),
        )
        assertEquals(
            "active",
            fixture.database.syncAuthDao().findState()?.state,
        )
    }

    @Test
    fun deterministicRefreshFailureFailsAttemptFamilyAndExactWaiterAtomically() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val requestId = UUID.randomUUID().toString()
            prepareSendingRequest(requestId, SyncM2PersistenceFixture.DEADLINE_MS)
            val refresh = fixture.refreshAttempt()
            fixture.database.syncAuthDao().claimRefreshAttempt(
                entity = refresh,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 100,
            )
            assertEquals(
                CredentialRecoveryAction.WAITING_FOR_REFRESH,
                store.handleTrustedSyncUnauthorized(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    expectedAttemptId = attemptId(requestId),
                    failedAccessGeneration = 1,
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 200,
                    nextAttemptAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 1_000,
                    updatedAtUtc = instant(
                        SyncM2PersistenceFixture.NOW_MS + 200,
                    ),
                ),
            )

            store.commitRefreshTerminalFailure(
                requestId = refresh.requestId,
                updatedAtUtc = instant(
                    SyncM2PersistenceFixture.NOW_MS + 300,
                ),
                failureCode = "refresh_rejected",
            )

            assertEquals(
                "failed",
                fixture.database.syncAuthDao()
                    .findAttempt(refresh.requestId)
                    ?.state,
            )
            val auth = requireNotNull(fixture.database.syncAuthDao().findState())
            assertEquals("quarantined", auth.state)
            assertEquals("refresh_rejected", auth.failureCode)
            assertNull(auth.refreshTokenCiphertext)
            val request = fixture.database.syncTransportDao()
                .findRequest("sync_pull", requestId)
            assertEquals("terminal_local", request?.state)
            assertEquals("refresh_rejected", request?.terminalErrorCode)
        }

    @Test
    fun second401AfterAnotherGenerationAdvanceTerminalizesWithoutRollback() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val requestId = UUID.randomUUID().toString()
            prepareSendingRequest(requestId, SyncM2PersistenceFixture.DEADLINE_MS)
            val refresh = fixture.refreshAttempt()
            fixture.database.syncAuthDao().claimRefreshAttempt(
                entity = refresh,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 50,
            )
            store.commitRefreshSuccess(
                refreshSuccess(
                    requestId = refresh.requestId,
                    committedAtMs = SyncM2PersistenceFixture.NOW_MS + 100,
                ),
            )

            val firstRetryAt = SyncM2PersistenceFixture.NOW_MS + 1_000
            assertEquals(
                CredentialRecoveryAction.RETRY_WITH_INSTALLED_GENERATION,
                store.handleTrustedSyncUnauthorized(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    expectedAttemptId = attemptId(requestId),
                    failedAccessGeneration = 1,
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 200,
                    nextAttemptAtEpochMs = firstRetryAt,
                    updatedAtUtc = instant(
                        SyncM2PersistenceFixture.NOW_MS + 200,
                    ),
                ),
            )
            val scheduled = requireNotNull(
                fixture.database.syncTransportDao()
                    .findRequest("sync_pull", requestId),
            )
            assertEquals("retry_wait", scheduled.state)
            assertEquals(2L, scheduled.accessGenerationUsed)
            assertEquals(1, scheduled.originalRetryCount)

            val secondAttempt = UUID.randomUUID().toString()
            assertEquals(
                1,
                fixture.database.syncTransportDao().claimAttempt(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                    accessGenerationUsed = 2,
                    attemptId = secondAttempt,
                    attemptedAtEpochMs = firstRetryAt,
                    leaseExpiresAtEpochMs = firstRetryAt + 60_000,
                    updatedAtUtc = instant(firstRetryAt),
                ),
            )
            val secondRefresh = fixture.refreshAttempt(generation = 2)
            fixture.database.syncAuthDao().claimRefreshAttempt(
                entity = secondRefresh,
                nowEpochMs = firstRetryAt + 10,
            )
            store.commitRefreshSuccess(
                refreshSuccess(
                    requestId = secondRefresh.requestId,
                    committedAtMs = firstRetryAt + 50,
                    expectedGeneration = 2,
                ),
            )
            assertEquals(
                3L,
                fixture.database.syncAuthDao().findState()?.generation,
            )
            assertEquals(
                CredentialRecoveryAction.QUARANTINED,
                store.handleTrustedSyncUnauthorized(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    expectedAttemptId = secondAttempt,
                    failedAccessGeneration = 2,
                    nowEpochMs = firstRetryAt + 100,
                    nextAttemptAtEpochMs = firstRetryAt + 1_000,
                    updatedAtUtc = instant(firstRetryAt + 100),
                ),
            )
            val terminal = fixture.database.syncTransportDao()
                .findRequest("sync_pull", requestId)
            assertEquals("terminal_local", terminal?.state)
            assertEquals(
                "credential_recovery_exhausted",
                terminal?.terminalErrorCode,
            )
            assertEquals(
                "quarantined",
                fixture.database.syncAuthDao().findState()?.state,
            )
        }

    @Test
    fun futureGeneration401TerminalizesRequestAndQuarantinesInstalledFamily() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val requestId = UUID.randomUUID().toString()
            val activeAttempt = attemptId(requestId)
            fixture.database.syncTransportDao().insertRequest(
                fixture.request(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    state = "sending",
                    accessGenerationUsed = 2,
                    attemptCount = 1,
                    activeAttemptId = activeAttempt,
                    leaseExpiresAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 60_000,
                ),
            )

            assertEquals(
                CredentialRecoveryAction.QUARANTINED,
                store.handleTrustedSyncUnauthorized(
                    endpointId = "sync_pull",
                    requestIdentity = requestId,
                    expectedAttemptId = activeAttempt,
                    failedAccessGeneration = 2,
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 100,
                    nextAttemptAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 1_000,
                    updatedAtUtc = instant(
                        SyncM2PersistenceFixture.NOW_MS + 100,
                    ),
                ),
            )
            val terminal = fixture.database.syncTransportDao()
                .findRequest("sync_pull", requestId)
            assertEquals("terminal_local", terminal?.state)
            assertEquals(
                "future_credential_generation",
                terminal?.terminalErrorCode,
            )
            val auth = requireNotNull(fixture.database.syncAuthDao().findState())
            assertEquals("quarantined", auth.state)
            assertEquals("future_credential_generation", auth.failureCode)
        }

    @Test
    fun terminalRevokeTombstoneCannotClearAReplacementFamilyOnLateReplay() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val revoke = fixture.sealedRevokeRequest()
            beginRevokeForTest(
                request = revoke,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            val attempt = UUID.randomUUID().toString()
            assertEquals(
                1,
                fixture.database.syncTransportDao().claimRevokeAttempt(
                    requestIdentity = revoke.requestIdentity,
                    attemptId = attempt,
                    attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    leaseExpiresAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 60_000,
                    updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                ),
            )
            val body = "{}".toByteArray(StandardCharsets.UTF_8)
            val response = TerminalHttpResponsePersistence(
                endpointId = "auth_revoke",
                requestIdentity = revoke.requestIdentity,
                expectedAttemptId = attempt,
                httpStatus = 200,
                exactResponseBody = body,
                responseSha256 = sha256Hex(body),
                terminalAtUtc = instant(SyncM2PersistenceFixture.NOW_MS + 100),
                terminalErrorCode = null,
            )
            assertTrue(store.commitRevokeTerminal(response))
            val tombstone = requireNotNull(fixture.database.syncAuthDao().findState())
            assertEquals("revoked", tombstone.state)
            assertNull(tombstone.refreshTokenCiphertext)

            assertEquals(
                1,
                fixture.database.syncAuthDao().deleteExactFamily(
                    credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                    deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                    generation = 1,
                ),
            )
            val replacementEpoch = "b3000000-0000-4000-8000-000000000001"
            fixture.installActiveAuth(
                credentialEpochId = replacementEpoch,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            )

            assertTrue(store.commitRevokeTerminal(response))
            val replacement = requireNotNull(fixture.database.syncAuthDao().findState())
            assertEquals(replacementEpoch, replacement.credentialEpochId)
            assertEquals("active", replacement.state)
        }

    @Test
    fun staleRevokeIntegrityCallbackCannotQuarantineCurrentLeaseOwner() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val revoke = fixture.sealedRevokeRequest()
            beginRevokeForTest(
                request = revoke,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            val staleAttempt = UUID.randomUUID().toString()
            val currentAttempt = UUID.randomUUID().toString()
            assertEquals(
                1,
                fixture.database.syncTransportDao().claimRevokeAttempt(
                    requestIdentity = revoke.requestIdentity,
                    attemptId = staleAttempt,
                    attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    leaseExpiresAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 100,
                    updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                ),
            )
            assertEquals(
                1,
                fixture.database.syncTransportDao().claimRevokeAttempt(
                    requestIdentity = revoke.requestIdentity,
                    attemptId = currentAttempt,
                    attemptedAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 100,
                    leaseExpiresAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 200,
                    updatedAtUtc = instant(
                        SyncM2PersistenceFixture.NOW_MS + 100,
                    ),
                ),
            )

            assertFalse(
                store.quarantineRevokeIntegrity(
                    requestIdentity = revoke.requestIdentity,
                    expectedKeyAlias = "fixture-revoke-key",
                    expectedKeyGeneration = 1,
                    expectedAadVersion = 1,
                    expectedAttemptId = staleAttempt,
                    updatedAtUtc = instant(
                        SyncM2PersistenceFixture.NOW_MS + 150,
                    ),
                    failureCode = "sealed_body_hmac_mismatch",
                ),
            )
            assertEquals(
                "revoke_pending",
                fixture.database.syncAuthDao().findState()?.state,
            )
            assertEquals(
                currentAttempt,
                fixture.database.syncTransportDao()
                    .findRequest("auth_revoke", revoke.requestIdentity)
                    ?.activeAttemptId,
            )
            assertTrue(
                store.quarantineRevokeIntegrity(
                    requestIdentity = revoke.requestIdentity,
                    expectedKeyAlias = "fixture-revoke-key",
                    expectedKeyGeneration = 1,
                    expectedAadVersion = 1,
                    expectedAttemptId = currentAttempt,
                    updatedAtUtc = instant(
                        SyncM2PersistenceFixture.NOW_MS + 160,
                    ),
                    failureCode = "sealed_body_hmac_mismatch",
                ),
            )
            assertEquals(
                "integrity_failure",
                fixture.database.syncAuthDao().findState()?.state,
            )
        }

    @Test
    fun exhaustedRevokeQuarantinesFamilyAndAllowsReplacementEnrollmentAfterReopen() =
        runBlocking {
            seedCurrentIncrementalFamily()
            val revoke = fixture.sealedRevokeRequest()
            beginRevokeForTest(
                request = revoke,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            )
            assertEquals(
                1,
                fixture.database.syncTransportDao().claimRevokeAttempt(
                    requestIdentity = revoke.requestIdentity,
                    attemptId = UUID.randomUUID().toString(),
                    attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    leaseExpiresAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 100,
                    updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                ),
            )
            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET attempt_count = attempt_budget,
                    lease_expires_at_epoch_ms = ?
                WHERE endpoint_id = 'auth_revoke'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    SyncM2PersistenceFixture.NOW_MS + 100,
                    revoke.requestIdentity,
                ),
            )
            fixture.reopen()
            store = SyncAuthPersistenceStore(fixture.database)

            assertEquals(
                1,
                SyncRequestPersistenceStore(fixture.database)
                    .reconcileExpiredOrExhaustedRequests(
                        nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 100,
                        terminalAtUtc = instant(
                            SyncM2PersistenceFixture.NOW_MS + 100,
                        ),
                    ),
            )
            val terminal = fixture.database.syncTransportDao()
                .findRequest("auth_revoke", revoke.requestIdentity)
            assertEquals("terminal_local", terminal?.state)
            assertEquals("retry_budget_exhausted", terminal?.terminalErrorCode)
            val auth = requireNotNull(fixture.database.syncAuthDao().findState())
            assertEquals("quarantined", auth.state)
            assertEquals("revoke_retry_exhausted", auth.failureCode)
            assertNull(auth.refreshTokenCiphertext)

            val replacement = fixture.enrollmentAttempt(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                generation = 1,
            )
            store.beginEnrollment(replacement)
            assertEquals(
                "dispatching",
                fixture.database.syncAuthDao()
                    .findAttempt(replacement.requestId)
                    ?.state,
            )
        }

    /** Explicit lower-store fixture; production creation goes through ProtectedSyncRequestStore. */
    private suspend fun commitEnrollmentSuccessForTest(
        bundle: EnrollmentSuccessPersistence,
        bootstrapRequest: SyncHttpRequestEntity,
    ) {
        val body = checkNotNull(bootstrapRequest.rawRequestBody).copyOf()
        val evidence = try {
            WireRequestCodec.decodeDurableBootstrapEvidence(body)
        } finally {
            body.fill(0)
        }
        check(
            bootstrapRequest.endpointId == "sync_bootstrap" &&
                bootstrapRequest.credentialEpochId == bundle.authState.credentialEpochId &&
                bootstrapRequest.deviceId == bundle.authState.deviceId &&
                bootstrapRequest.accessGenerationUsed == bundle.authState.generation &&
                evidence.requestId == bootstrapRequest.requestIdentity &&
                evidence.bootstrapId == bundle.bootstrapSession.bootstrapId &&
                evidence.deviceId == bundle.bootstrapSession.deviceId &&
                evidence.pageCursor == null,
        )
        fixture.database.withTransaction {
            store.commitEnrollmentSuccessState(bundle)
            fixture.database.syncTransportDao().insertRequest(bootstrapRequest)
        }
    }

    /** Explicit lower-store fixture; production creation goes through ProtectedSyncRequestStore. */
    private suspend fun beginRevokeForTest(
        request: SyncHttpRequestEntity,
        nowEpochMs: Long,
        updatedAtUtc: String,
    ) {
        check(request.endpointId == "auth_revoke")
        val generation = checkNotNull(request.accessGenerationUsed)
        fixture.database.withTransaction {
            check(
                fixture.database.syncAuthDao().claimRevokeFamily(
                    credentialEpochId = request.credentialEpochId,
                    deviceId = request.deviceId,
                    generation = generation,
                    nowEpochMs = nowEpochMs,
                    updatedAtUtc = updatedAtUtc,
                ) == 1,
            )
            fixture.database.syncTransportDao().insertRequest(request)
        }
    }

    private suspend fun seedCurrentIncrementalFamily() {
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
    }

    private suspend fun prepareSendingRequest(
        requestId: String,
        deadlineAtEpochMs: Long,
    ) {
        fixture.database.syncTransportDao().insertRequest(
            fixture.request(
                endpointId = "sync_pull",
                requestIdentity = requestId,
                deadlineAtEpochMs = deadlineAtEpochMs,
            ),
        )
        assertEquals(
            1,
            fixture.database.syncTransportDao().claimAttempt(
                endpointId = "sync_pull",
                requestIdentity = requestId,
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                accessGenerationUsed = 1,
                attemptId = attemptId(requestId),
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs =
                    minOf(
                        SyncM2PersistenceFixture.NOW_MS + 100,
                        deadlineAtEpochMs,
                    ),
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
    }

    private fun refreshSuccess(
        requestId: String,
        committedAtMs: Long,
        expectedGeneration: Long = 1,
    ) = RefreshSuccessPersistence(
        attemptRequestId = requestId,
        credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        deviceId = SyncM2PersistenceFixture.DEVICE_ID,
        expectedGeneration = expectedGeneration,
        successorGeneration = expectedGeneration + 1,
        refreshTokenCiphertext = byteArrayOf(5, 6, 7),
        refreshTokenNonce = ByteArray(12) { 8 },
        refreshTokenKeyAlias = "fixture-refresh-key-${expectedGeneration + 1}",
        refreshTokenKeyGeneration = (expectedGeneration + 1).toInt(),
        refreshTokenAadVersion = 1,
        accessExpiresAtUtc = instant(SyncM2PersistenceFixture.ACCESS_EXPIRY_MS),
        accessExpiresAtEpochMs = SyncM2PersistenceFixture.ACCESS_EXPIRY_MS,
        refreshExpiresAtUtc = instant(SyncM2PersistenceFixture.REFRESH_EXPIRY_MS),
        refreshExpiresAtEpochMs = SyncM2PersistenceFixture.REFRESH_EXPIRY_MS,
        familyExpiresAtUtc = instant(SyncM2PersistenceFixture.FAMILY_EXPIRY_MS),
        familyExpiresAtEpochMs = SyncM2PersistenceFixture.FAMILY_EXPIRY_MS,
        committedAtUtc = instant(committedAtMs),
        committedAtEpochMs = committedAtMs,
        accessFingerprint = fixture.fingerprint(
            credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
            generation = expectedGeneration + 1,
            tokenKind = "access",
            seed = (expectedGeneration * 2 + 20).toByte(),
        ),
        refreshFingerprint = fixture.fingerprint(
            credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
            generation = expectedGeneration + 1,
            tokenKind = "refresh",
            seed = (expectedGeneration * 2 + 21).toByte(),
        ),
    )

    private fun attemptId(requestId: String): String = "attempt-$requestId"

    private fun instant(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).toString()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }
}
