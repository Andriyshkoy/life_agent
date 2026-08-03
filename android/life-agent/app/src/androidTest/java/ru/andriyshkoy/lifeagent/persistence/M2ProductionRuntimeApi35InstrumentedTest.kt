package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.ProductionProtectedActionablePushConstructionPort
import ru.andriyshkoy.lifeagent.data.local.db.ProductionProtectedDurableDispatchPort
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchClaimBoundary
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchResponseBoundary
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableDispatchResult
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDurableExactExchange
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedDispatchRequestClaim
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedResponseDisposition
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestConstructionSettings
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningFacade
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningOutcome
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestStore
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncResponseStore
import ru.andriyshkoy.lifeagent.data.local.db.RefreshSuccessPersistence
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore
import ru.andriyshkoy.lifeagent.data.security.KeystoreRequestBodyHmacKeyring
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenKey
import ru.andriyshkoy.lifeagent.data.sync.runtime.AccessTokenVault
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncRequestKind
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsNetworkFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsNetworkFailureKind
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsProtocolFailure
import ru.andriyshkoy.lifeagent.data.sync.transport.ExactHttpsRawResponse
import ru.andriyshkoy.lifeagent.data.sync.wire.StrictJson
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonArray
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonBoolean
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonInteger
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonObject
import ru.andriyshkoy.lifeagent.data.sync.wire.WireJsonString
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class M2ProductionRuntimeApi35InstrumentedTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: SyncM2PersistenceFixture
    private lateinit var keyAlias: String
    private lateinit var markerRelativePath: String
    private val vault = AccessTokenVault()

    @Before
    fun setUp() {
        val testId = UUID.randomUUID().toString()
        keyAlias = "life_agent_test_runtime_hmac_$testId"
        markerRelativePath = "crypto-tests/runtime-hmac-$testId.marker"
        fixture = SyncM2PersistenceFixture(context, "m2-production-runtime")
    }

    @After
    fun tearDown() {
        vault.close()
        runCatching(fixture::close)
        deleteMarkerArtifacts()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    @Test
    fun localNoteTrusted401AndRestartReleaseOneProtectedPushAtGenerationNPlusOne() =
        runBlocking {
            fixture.seedIdentity(
                deviceId = SyncM2PersistenceFixture.DEVICE_ID,
                personId = SyncM2PersistenceFixture.PERSON_ID,
            )
            fixture.installActiveAuth()
            fixture.seedIncrementalStream()
            val noteIds = noteIds()
            RoomNotesRepository(
                database = fixture.database,
                collectorVersion = "m2-runtime-api35-test",
            ).create(
                CreateNoteCommand(
                    ids = noteIds,
                    text = "Synthetic protected runtime note",
                    effectiveTime = PointTimeResolver.resolveInstant(
                        RECORDED_AT.toInstant(),
                        ZoneId.of("Asia/Novosibirsk"),
                    ),
                    recordedAt = RECORDED_AT,
                ),
            )

            val keyring = keyring()
            val planner = planner(keyring)
            val planned = planner.planAndConstruct(PLAN_AT.toString())
            assertTrue(planned is ProtectedSyncRequestPlanningOutcome.Created)
            planned as ProtectedSyncRequestPlanningOutcome.Created
            assertEquals(DurableSyncRequestKind.PUSH, planned.kind)
            assertEquals("sync_push", planned.request.endpointId)
            val batchId = planned.request.requestIdentity

            val materialized = requireNotNull(
                fixture.database.noteMutationDao().findOutbox(noteIds.operationId.toString()),
            )
            assertEquals("ready", materialized.wireState)
            assertNotNull(materialized.wireOperationMaterialJcs)
            assertNotNull(materialized.wireOperationContentSha256)
            assertEquals(1, fixture.database.syncTransportDao().findBatchItems(batchId).size)
            assertEquals("ready", request(batchId).state)

            fixture.reopen()
            val reopenedKeyring = keyring()
            val tokenKey = AccessTokenKey(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                accessGeneration = 1,
            )
            vault.replace(tokenKey, WipeableSecret.ascii(VALID_ACCESS_TOKEN))
            val attemptedAtMs = ATTEMPTED_AT.toEpochMilli()
            val candidate = fixture.database.syncTransportDao()
                .findRunnableRequestCandidates(attemptedAtMs, 10)
                .single { it.requestIdentity == batchId }
            val requests = ProtectedSyncRequestStore(
                context = context,
                database = fixture.database,
                keyring = reopenedKeyring,
            )
            val responses = ProtectedSyncResponseStore(
                context = context,
                database = fixture.database,
                bootstrapIntents = planner(reopenedKeyring).protectedBootstrapIntents,
                keyring = reopenedKeyring,
            )
            val unauthorizedBody = credentialUnavailableBody(
                requestIdentity = batchId,
                serverTime = COMPLETED_AT.toString(),
            )
            val dispatch = ProductionProtectedDurableDispatchPort(
                exchangeProvider = {
                    ProtectedDurableExactExchange { claim, bearer ->
                        assertNotNull(bearer)
                        ExactHttpsRawResponse(
                            claim = claim,
                            httpStatus = 401,
                            retryAfterSeconds = null,
                            body = unauthorizedBody.copyOf(),
                        )
                    }
                },
                claims = ProtectedDurableDispatchClaimBoundary {
                        bodyFreeCandidate,
                        attemptId,
                        claimedAtEpochMs,
                        leaseExpiresAtEpochMs,
                        claimedAtUtc,
                    ->
                    requests.verifyAndClaimForDispatch(
                        endpointId = bodyFreeCandidate.endpointId,
                        requestIdentity = bodyFreeCandidate.requestIdentity,
                        attemptId = attemptId,
                        attemptedAtEpochMs = claimedAtEpochMs,
                        leaseExpiresAtEpochMs = leaseExpiresAtEpochMs,
                        updatedAtUtc = claimedAtUtc,
                        accessTokenVault = vault,
                    )
                },
                responses = object : ProtectedDurableDispatchResponseBoundary {
                    override suspend fun reduce(
                        outcome: ExactHttpsNetworkFailure,
                        terminalAtUtc: String,
                    ): ProtectedResponseDisposition =
                        error("Synthetic trusted 401 became a network failure")

                    override suspend fun reduce(
                        outcome: ExactHttpsProtocolFailure,
                        terminalAtUtc: String,
                    ): ProtectedResponseDisposition =
                        error("Synthetic trusted 401 became a protocol failure")

                    override suspend fun reduce(
                        outcome: ExactHttpsRawResponse,
                        terminalAtUtc: String,
                    ): ProtectedResponseDisposition =
                        responses.reduceRawResponse(outcome, terminalAtUtc)
                },
                accessTokenVault = vault,
                completionClock = Clock.fixed(COMPLETED_AT, ZoneOffset.UTC),
            )
            try {
                assertEquals(
                    ProtectedDurableDispatchResult.PROGRESSED,
                    dispatch.dispatch(
                        candidate = candidate,
                        attemptId = ATTEMPT_ID,
                        attemptedAtUtc = ATTEMPTED_AT.toString(),
                        leaseExpiresAtEpochMs = LEASE_EXPIRES_AT.toEpochMilli(),
                    ),
                )
            } finally {
                unauthorizedBody.fill(0)
            }

            assertNull(vault.claim(tokenKey))
            val waiting = request(batchId)
            assertEquals("waiting_refresh", waiting.state)
            assertEquals(1L, waiting.accessGenerationUsed)
            assertNull(waiting.activeAttemptId)
            assertEquals("credential_recovery_pending", waiting.terminalErrorCode)

            fixture.reopen()
            assertEquals("waiting_refresh", request(batchId).state)
            val refreshAttempt = fixture.refreshAttempt()
            fixture.database.syncAuthDao().claimRefreshAttempt(
                entity = refreshAttempt,
                nowEpochMs = REFRESH_CLAIMED_AT.toEpochMilli(),
            )
            SyncAuthPersistenceStore(fixture.database).commitRefreshSuccess(
                refreshSuccess(
                    requestId = refreshAttempt.requestId,
                    committedAt = REFRESH_COMMITTED_AT,
                ),
            )

            val auth = requireNotNull(fixture.database.syncAuthDao().findState())
            assertEquals("active", auth.state)
            assertEquals(2L, auth.generation)
            val released = request(batchId)
            assertEquals("retry_wait", released.state)
            assertEquals(2L, released.accessGenerationUsed)
            assertEquals(1, released.originalRetryCount)
            assertNull(released.activeAttemptId)
            assertEquals(
                REFRESH_COMMITTED_AT.toEpochMilli(),
                released.nextAttemptAtEpochMs,
            )
            assertTrue(
                fixture.database.syncTransportDao()
                    .findWaitingRefreshAuthoritySnapshots()
                    .isEmpty(),
            )
            assertEquals(1, fixture.database.syncTransportDao().findBatchItems(batchId).size)
            assertEquals(
                "ready",
                fixture.database.noteMutationDao()
                    .findOutbox(noteIds.operationId.toString())
                    ?.wireState,
            )

            val successorTokenKey = AccessTokenKey(
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                accessGeneration = 2,
            )
            vault.replace(successorTokenKey, WipeableSecret.ascii(VALID_ACCESS_TOKEN))
            val retryRequests = ProtectedSyncRequestStore(
                context = context,
                database = fixture.database,
                keyring = reopenedKeyring,
            )
            val retryClaimResult = retryRequests.verifyAndClaimForDispatch(
                endpointId = "sync_push",
                requestIdentity = batchId,
                attemptId = RETRY_ATTEMPT_ID,
                attemptedAtEpochMs = REFRESH_COMMITTED_AT.toEpochMilli(),
                leaseExpiresAtEpochMs = RETRY_LEASE_EXPIRES_AT.toEpochMilli(),
                updatedAtUtc = REFRESH_COMMITTED_AT.toString(),
                accessTokenVault = vault,
            )
            assertTrue(retryClaimResult is ProtectedDispatchRequestClaim.Claimed)
            val retryClaim =
                retryClaimResult as ProtectedDispatchRequestClaim.Claimed
            try {
                val sending = request(batchId)
                assertEquals("sending", sending.state)
                assertEquals(2, sending.attemptCount)
                assertEquals(2L, sending.accessGenerationUsed)
                assertEquals(RETRY_ATTEMPT_ID, sending.activeAttemptId)
                assertEquals(
                    REFRESH_COMMITTED_AT.toEpochMilli(),
                    sending.lastAttemptAtEpochMs,
                )
                assertEquals(
                    RETRY_LEASE_EXPIRES_AT.toEpochMilli(),
                    sending.leaseExpiresAtEpochMs,
                )
                assertNull(sending.nextAttemptAtEpochMs)
                assertTrue(
                    requireNotNull(
                        fixture.database.syncTransportDao().findResponseRouteSnapshot(
                            endpointId = "sync_push",
                            requestIdentity = batchId,
                            expectedAttemptId = RETRY_ATTEMPT_ID,
                        ),
                    ).hasFreshResponseMetadataShape,
                )

                val retryResponses = ProtectedSyncResponseStore(
                    context = context,
                    database = fixture.database,
                    bootstrapIntents = planner(reopenedKeyring).protectedBootstrapIntents,
                    keyring = reopenedKeyring,
                )
                assertEquals(
                    ProtectedResponseDisposition.RETRY_SCHEDULED,
                    retryResponses.reduceRetryableFailure(
                        ExactHttpsNetworkFailure(
                            claim = retryClaim.requestClaim,
                            kind = ExactHttpsNetworkFailureKind.TIMEOUT,
                            httpStatus = null,
                        ),
                        RETRY_FAILED_AT.toString(),
                    ),
                )
            } finally {
                retryClaim.close()
            }

            val retryScheduled = request(batchId)
            assertEquals("retry_wait", retryScheduled.state)
            assertEquals("transport_timeout", retryScheduled.terminalErrorCode)
            assertEquals(
                RETRY_FAILED_AT.plusSeconds(2).toEpochMilli(),
                retryScheduled.nextAttemptAtEpochMs,
            )
            assertNull(retryScheduled.activeAttemptId)
            assertNull(retryScheduled.leaseExpiresAtEpochMs)
            assertEquals(
                "incremental",
                fixture.database.syncReplicaDao().findStreamState()?.phase,
            )
        }

    private fun planner(keyring: KeystoreRequestBodyHmacKeyring) =
        ProtectedSyncRequestPlanningFacade(
            context = context,
            database = fixture.database,
            settings = ProtectedSyncRequestConstructionSettings(
                pageSize = 100,
                attemptBudget = 8,
                requestLifetimeMillis = 600_000,
            ),
            actionablePushes = ProductionProtectedActionablePushConstructionPort(
                fixture.database,
            ),
            keyring = keyring,
        )

    private fun keyring() = KeystoreRequestBodyHmacKeyring(
        context = context,
        keyAlias = keyAlias,
        markerRelativePath = markerRelativePath,
    )

    private suspend fun request(batchId: String) = requireNotNull(
        fixture.database.syncTransportDao().findRequest("sync_push", batchId),
    )

    private fun refreshSuccess(
        requestId: String,
        committedAt: Instant,
    ) = RefreshSuccessPersistence(
        attemptRequestId = requestId,
        credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
        deviceId = SyncM2PersistenceFixture.DEVICE_ID,
        expectedGeneration = 1,
        successorGeneration = 2,
        refreshTokenCiphertext = byteArrayOf(5, 6, 7),
        refreshTokenNonce = ByteArray(12) { 8 },
        refreshTokenKeyAlias = "fixture-refresh-key-2",
        refreshTokenKeyGeneration = 2,
        refreshTokenAadVersion = 1,
        accessExpiresAtUtc = Instant.ofEpochMilli(
            SyncM2PersistenceFixture.ACCESS_EXPIRY_MS,
        ).toString(),
        accessExpiresAtEpochMs = SyncM2PersistenceFixture.ACCESS_EXPIRY_MS,
        refreshExpiresAtUtc = Instant.ofEpochMilli(
            SyncM2PersistenceFixture.REFRESH_EXPIRY_MS,
        ).toString(),
        refreshExpiresAtEpochMs = SyncM2PersistenceFixture.REFRESH_EXPIRY_MS,
        familyExpiresAtUtc = Instant.ofEpochMilli(
            SyncM2PersistenceFixture.FAMILY_EXPIRY_MS,
        ).toString(),
        familyExpiresAtEpochMs = SyncM2PersistenceFixture.FAMILY_EXPIRY_MS,
        committedAtUtc = committedAt.toString(),
        committedAtEpochMs = committedAt.toEpochMilli(),
        accessFingerprint = fixture.fingerprint(
            credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
            generation = 2,
            tokenKind = "access",
            seed = 22,
        ),
        refreshFingerprint = fixture.fingerprint(
            credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
            generation = 2,
            tokenKind = "refresh",
            seed = 23,
        ),
    )

    private fun credentialUnavailableBody(
        requestIdentity: String,
        serverTime: String,
    ): ByteArray = StrictJson.canonicalBytes(
        WireJsonObject(
            mapOf(
                "error_code" to WireJsonString("credential_unavailable"),
                "field_errors" to WireJsonArray(emptyList()),
                "http_status" to WireJsonInteger(401),
                "message_type" to WireJsonString("api_error"),
                "protocol_version" to WireJsonString("1.0.0"),
                "request_id" to WireJsonString(requestIdentity),
                "retryable" to WireJsonBoolean(false),
                "server_time" to WireJsonString(serverTime),
            ),
        ),
    )

    private fun noteIds(): MutationIds = MutationIds(
        operationId = uuid("operation"),
        captureId = uuid("capture"),
        eventId = uuid("event"),
        revisionId = uuid("revision"),
    )

    private fun uuid(label: String): UUID = UUID.nameUUIDFromBytes(
        "m2-runtime-api35-$label".toByteArray(StandardCharsets.US_ASCII),
    )

    private fun deleteMarkerArtifacts() {
        val marker = File(context.noBackupFilesDir, markerRelativePath)
        listOf("", ".bak", ".new").forEach { suffix ->
            File(marker.path + suffix).delete()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ATTEMPT_ID = "m2-runtime-api35-attempt"
        const val RETRY_ATTEMPT_ID = "m2-runtime-api35-retry-attempt"
        const val VALID_ACCESS_TOKEN =
            "laa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val RECORDED_AT: OffsetDateTime =
            OffsetDateTime.parse("2030-01-01T07:00:00+07:00")
        val PLAN_AT: Instant = Instant.parse("2030-01-01T00:01:00Z")
        val ATTEMPTED_AT: Instant = Instant.parse("2030-01-01T00:01:01Z")
        val LEASE_EXPIRES_AT: Instant = Instant.parse("2030-01-01T00:02:01Z")
        val COMPLETED_AT: Instant = Instant.parse("2030-01-01T00:01:02Z")
        val REFRESH_CLAIMED_AT: Instant = Instant.parse("2030-01-01T00:01:03Z")
        val REFRESH_COMMITTED_AT: Instant = Instant.parse("2030-01-01T00:01:04Z")
        val RETRY_FAILED_AT: Instant = Instant.parse("2030-01-01T00:01:05Z")
        val RETRY_LEASE_EXPIRES_AT: Instant = Instant.parse("2030-01-01T00:02:04Z")
    }
}
