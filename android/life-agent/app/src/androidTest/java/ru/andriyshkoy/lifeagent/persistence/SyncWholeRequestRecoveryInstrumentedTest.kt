package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
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
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.CredentialRecoveryAction
import ru.andriyshkoy.lifeagent.data.local.db.SyncAuthPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.SyncRequestPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(AndroidJUnit4::class)
class SyncWholeRequestRecoveryInstrumentedTest {
    private lateinit var fixture: SyncM2PersistenceFixture
    private lateinit var repository: RoomNotesRepository
    private lateinit var store: SyncRequestPersistenceStore

    @Before
    fun setUp() = runBlocking {
        fixture = SyncM2PersistenceFixture(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            label = "m2-whole-request",
        )
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
        repository = RoomNotesRepository(
            database = fixture.database,
            collectorVersion = "m2-whole-request-test",
        )
        store = SyncRequestPersistenceStore(fixture.database)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun bootstrapRequiredFreezesWholePushAndInvalidatesEveryOtherSyncState() =
        runBlocking {
            val outbox = createReadyOutbox()
            val operationBytes =
                requireNotNull(outbox.wireOperationMaterialJcs).copyOf()
            val operationSha = requireNotNull(outbox.wireOperationContentSha256)
            val localSequence = outbox.localSequence
            val push = insertAndClaimPush(outbox)
            val readyPull = insertSynthetic("sync_pull", "ready")
            val retryPull = insertSynthetic(
                endpointId = "sync_pull",
                state = "retry_wait",
                nextAttemptAtEpochMs = SyncM2PersistenceFixture.NOW_MS + 1_000,
            )
            val waitingPull = insertSynthetic(
                endpointId = "sync_pull",
                state = "waiting_refresh",
                refreshAttempted = true,
            )
            val oldBootstrapId = UUID.randomUUID().toString()
            val oldBootstrapRequest = fixture.request(
                endpointId = "sync_bootstrap",
                requestIdentity = UUID.randomUUID().toString(),
                state = "sending",
                attemptCount = 1,
                activeAttemptId = UUID.randomUUID().toString(),
                bootstrapId = oldBootstrapId,
                leaseExpiresAtEpochMs = SyncM2PersistenceFixture.DEADLINE_MS,
            )
            fixture.database.syncTransportDao().insertRequest(oldBootstrapRequest)
            val proposed = fixture.bootstrapIntent()
            val response = bootstrapRequiredResponse(push)

            assertTrue(store.commitPushBootstrapRequired(response, proposed))
            assertFalse(store.commitPushBootstrapRequired(response, proposed))

            val terminalPush = fixture.database.syncTransportDao()
                .findRequest("sync_push", push.batchId)
            assertEquals("terminal", terminalPush?.state)
            assertEquals(409, terminalPush?.terminalHttpStatus)
            assertEquals("bootstrap_required", terminalPush?.terminalErrorCode)
            val released = requireNotNull(
                fixture.database.noteMutationDao()
                    .findOutbox(outbox.operationId),
            )
            assertEquals("pending", released.state)
            assertNull(released.activeBatchId)
            assertArrayEquals(
                operationBytes,
                requireNotNull(released.wireOperationMaterialJcs),
            )
            assertEquals(operationSha, released.wireOperationContentSha256)
            assertEquals(localSequence, released.localSequence)

            listOf(readyPull, retryPull, waitingPull, oldBootstrapRequest).forEach {
                val invalidated = fixture.database.syncTransportDao()
                    .findRequest(it.endpointId, it.requestIdentity)
                assertEquals("terminal_local", invalidated?.state)
                assertNull(invalidated?.activeAttemptId)
            }
            assertEquals(
                "bootstrap_superseded",
                fixture.database.syncTransportDao()
                    .findRequest(
                        oldBootstrapRequest.endpointId,
                        oldBootstrapRequest.requestIdentity,
                    )
                    ?.terminalErrorCode,
            )
            assertEquals(
                "ready",
                fixture.database.syncTransportDao()
                    .findRequest(
                        proposed.firstRequest.endpointId,
                        proposed.firstRequest.requestIdentity,
                    )
                    ?.state,
            )
            assertEquals(
                proposed.session.bootstrapId,
                fixture.database.syncReplicaDao()
                    .findBootstrapSessionWithActiveSlot()
                    ?.bootstrapId,
            )
            val stream = requireNotNull(
                fixture.database.syncReplicaDao().findStreamState(),
            )
            assertEquals("bootstrap_required", stream.phase)
            assertTrue(stream.bootstrapRequired)
            assertTrue(
                requireNotNull(fixture.database.syncAuthDao().findState())
                    .bootstrapRequired,
            )
            assertEquals(
                listOf(proposed.firstRequest.requestIdentity),
                fixture.database.syncTransportDao()
                    .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 20)
                .map { it.requestIdentity },
            )
        }

    @Test
    fun interruptedRefreshReleasesExactPushWaiterOutboxAfterReopen() =
        runBlocking {
            val outbox = createReadyOutbox()
            val push = insertAndClaimPush(outbox)
            val refresh = fixture.refreshAttempt()
            fixture.database.syncAuthDao().claimRefreshAttempt(
                entity = refresh,
                nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 100,
            )
            val authStore = SyncAuthPersistenceStore(fixture.database)
            assertEquals(
                CredentialRecoveryAction.WAITING_FOR_REFRESH,
                authStore.handleTrustedSyncUnauthorized(
                    endpointId = "sync_push",
                    requestIdentity = push.batchId,
                    expectedAttemptId = push.attemptId,
                    failedAccessGeneration = 1,
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS + 200,
                    nextAttemptAtEpochMs =
                        SyncM2PersistenceFixture.NOW_MS + 1_000,
                    updatedAtUtc = "2030-01-01T00:00:00.200Z",
                ),
            )
            fixture.reopen()

            assertEquals(
                1,
                SyncAuthPersistenceStore(fixture.database)
                    .recoverInterruptedAuthFlows(
                        updatedAtUtc = "2030-01-01T00:00:00.300Z",
                    ),
            )
            val terminal = fixture.database.syncTransportDao()
                .findRequest("sync_push", push.batchId)
            assertEquals("terminal_local", terminal?.state)
            assertEquals("refresh_interrupted", terminal?.terminalErrorCode)
            val released = fixture.database.noteMutationDao()
                .findOutbox(outbox.operationId)
            assertEquals("pending", released?.state)
            assertNull(released?.activeBatchId)
        }

    @Test
    fun bootstrapIntentCollisionRollsBackResponseBatchAndAllFlags() = runBlocking {
        val outbox = createReadyOutbox()
        val push = insertAndClaimPush(outbox)
        val foreignSession = SyncBootstrapSessionEntity(
            bootstrapId = UUID.randomUUID().toString(),
            credentialEpochId = "c3000000-0000-4000-8000-000000000001",
            deviceId = "c4000000-0000-4000-8000-000000000001",
            state = "staging",
            activeSlot = 1,
            snapshotId = null,
            nextPageCursor = null,
            candidateIncrementalCursor = null,
            nextPageIndex = 0,
            lastStagedServerSequence = null,
            stagedPageCount = 0,
            stagedBodyBytes = 0,
            createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
        )
        fixture.database.syncReplicaDao().insertBootstrapSession(foreignSession)
        val proposed = fixture.bootstrapIntent()

        assertTrue(
            runCatching {
                store.commitPushBootstrapRequired(
                    response = bootstrapRequiredResponse(push),
                    proposedIntent = proposed,
                )
            }.isFailure,
        )

        val request = fixture.database.syncTransportDao()
            .findRequest("sync_push", push.batchId)
        assertEquals("sending", request?.state)
        assertNull(request?.terminalHttpStatus)
        assertEquals(push.attemptId, request?.activeAttemptId)
        val retained = fixture.database.noteMutationDao()
            .findOutbox(outbox.operationId)
        assertEquals("batched", retained?.state)
        assertEquals(push.batchId, retained?.activeBatchId)
        val stream = requireNotNull(
            fixture.database.syncReplicaDao().findStreamState(),
        )
        assertEquals("incremental", stream.phase)
        assertFalse(stream.bootstrapRequired)
        assertFalse(
            requireNotNull(fixture.database.syncAuthDao().findState())
                .bootstrapRequired,
        )
        assertEquals(
            foreignSession.bootstrapId,
            fixture.database.syncReplicaDao()
                .findBootstrapSessionWithActiveSlot()
                ?.bootstrapId,
        )
        assertNull(
            fixture.database.syncReplicaDao()
                .findBootstrapSession(proposed.session.bootstrapId),
        )
        assertNull(
            fixture.database.syncTransportDao().findRequest(
                proposed.firstRequest.endpointId,
                proposed.firstRequest.requestIdentity,
            ),
        )
    }

    @Test
    fun bootstrapIntentRejectsStringPageSizeBeforeAnyWholeRequestMutation() =
        runBlocking {
            val outbox = createReadyOutbox()
            val push = insertAndClaimPush(outbox)
            val proposed = fixture.bootstrapIntent()
            val request = proposed.firstRequest
            val malformedBody =
                """
                {"protocol_version":"1.0.0","message_type":"bootstrap_request","request_id":"${request.requestIdentity}","bootstrap_id":"${proposed.session.bootstrapId}","device_id":"${proposed.session.deviceId}","page_size":"100","page_cursor":null}
                """.trimIndent().encodeToByteArray()
            val malformed = proposed.copy(
                firstRequest = request.copy(
                    rawRequestBody = malformedBody,
                    requestBodyOctetCount = malformedBody.size.toLong(),
                    rawBodyHmac = ByteArray(32) { 9 },
                ),
            )

            assertTrue(
                runCatching {
                    store.commitPushBootstrapRequired(
                        response = bootstrapRequiredResponse(push),
                        proposedIntent = malformed,
                    )
                }.isFailure,
            )
            val retainedRequest = fixture.database.syncTransportDao()
                .findRequest("sync_push", push.batchId)
            assertEquals("sending", retainedRequest?.state)
            assertEquals(push.attemptId, retainedRequest?.activeAttemptId)
            val retainedOutbox = fixture.database.noteMutationDao()
                .findOutbox(outbox.operationId)
            assertEquals("batched", retainedOutbox?.state)
            assertEquals(push.batchId, retainedOutbox?.activeBatchId)
            assertFalse(
                requireNotNull(fixture.database.syncAuthDao().findState())
                    .bootstrapRequired,
            )
            assertEquals(
                "incremental",
                fixture.database.syncReplicaDao().findStreamState()?.phase,
            )
            assertNull(
                fixture.database.syncReplicaDao()
                    .findBootstrapSession(proposed.session.bootstrapId),
            )
        }

    @Test
    fun restartReconcilesLastAttemptPushAndReleasesItsOutboxAtomically() =
        runBlocking {
            val outbox = createReadyOutbox()
            val push = insertAndClaimPush(outbox)
            fixture.database.openHelper.writableDatabase.execSQL(
                """
                UPDATE sync_http_request
                SET attempt_count = attempt_budget,
                    lease_expires_at_epoch_ms = ?
                WHERE endpoint_id = 'sync_push'
                  AND request_identity = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    SyncM2PersistenceFixture.NOW_MS,
                    push.batchId,
                ),
            )
            fixture.reopen()
            store = SyncRequestPersistenceStore(fixture.database)

            assertEquals(
                emptyList<Any>(),
                fixture.database.syncTransportDao()
                    .findRunnableRequests(SyncM2PersistenceFixture.NOW_MS, 10),
            )
            assertEquals(
                SyncM2PersistenceFixture.NOW_MS,
                fixture.database.syncTransportDao()
                    .findEarliestRunnableAtEpochMs(SyncM2PersistenceFixture.NOW_MS),
            )
            assertEquals(
                1,
                store.reconcileExpiredOrExhaustedRequests(
                    nowEpochMs = SyncM2PersistenceFixture.NOW_MS,
                    terminalAtUtc = SyncM2PersistenceFixture.BASE_UTC,
                ),
            )
            val terminal = fixture.database.syncTransportDao()
                .findRequest("sync_push", push.batchId)
            assertEquals("terminal_local", terminal?.state)
            assertEquals("retry_budget_exhausted", terminal?.terminalErrorCode)
            val released = fixture.database.noteMutationDao()
                .findOutbox(outbox.operationId)
            assertEquals("pending", released?.state)
            assertNull(released?.activeBatchId)
        }

    private suspend fun createReadyOutbox(): SyncOutboxEntity {
        val ids = MutationIds(
            operationId = UUID.randomUUID(),
            captureId = UUID.randomUUID(),
            eventId = UUID.randomUUID(),
            revisionId = UUID.randomUUID(),
        )
        repository.create(
            CreateNoteCommand(
                ids = ids,
                text = "Whole-request bootstrap recovery",
                effectiveTime = PointTimeResolver.resolveInstant(
                    RECORDED_AT.toInstant(),
                    TEST_ZONE,
                ),
                recordedAt = RECORDED_AT,
            ),
        )
        val outbox = requireNotNull(
            fixture.database.noteMutationDao()
                .findOutbox(ids.operationId.toString()),
        )
        val material = """{"operation_id":"${outbox.operationId}"}"""
            .toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            1,
            fixture.database.outboxDao().installWireMaterial(
                localSequence = outbox.localSequence,
                operationId = outbox.operationId,
                protocolVersion = "1.0.0",
                materialJcs = material,
                contentSha256 = sha256Hex(material),
                materializedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
        return requireNotNull(
            fixture.database.noteMutationDao().findOutbox(outbox.operationId),
        )
    }

    private suspend fun insertAndClaimPush(outbox: SyncOutboxEntity): ClaimedPush {
        val batchId = UUID.randomUUID().toString()
        fixture.database.syncTransportDao().insertPushRequest(
            request = fixture.request(
                endpointId = "sync_push",
                requestIdentity = batchId,
            ),
            batch = SyncPushBatchEntity(
                batchId = batchId,
                endpointId = "sync_push",
                requestIdentity = batchId,
                batchContentSha256 = "d".repeat(64),
                operationCount = 1,
                createdAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
            items = listOf(
                SyncPushBatchItemEntity(
                    batchId = batchId,
                    ordinal = 0,
                    localSequence = outbox.localSequence,
                    operationId = outbox.operationId,
                    wireOperationContentSha256 =
                        requireNotNull(outbox.wireOperationContentSha256),
                ),
            ),
        )
        val attemptId = UUID.randomUUID().toString()
        assertEquals(
            1,
            fixture.database.syncTransportDao().claimAttempt(
                endpointId = "sync_push",
                requestIdentity = batchId,
                credentialEpochId = SyncM2PersistenceFixture.EPOCH_ID,
                accessGenerationUsed = 1,
                attemptId = attemptId,
                attemptedAtEpochMs = SyncM2PersistenceFixture.NOW_MS,
                leaseExpiresAtEpochMs =
                    SyncM2PersistenceFixture.NOW_MS + 60_000,
                updatedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
        return ClaimedPush(batchId, attemptId)
    }

    private suspend fun insertSynthetic(
        endpointId: String,
        state: String,
        nextAttemptAtEpochMs: Long? = null,
        refreshAttempted: Boolean = false,
    ) = fixture.request(
        endpointId = endpointId,
        requestIdentity = UUID.randomUUID().toString(),
        state = state,
        attemptCount = if (state == "ready") 0 else 1,
        nextAttemptAtEpochMs = nextAttemptAtEpochMs,
    ).copy(
        refreshAttempted = refreshAttempted,
    ).also {
        fixture.database.syncTransportDao().insertRequest(it)
    }

    private fun bootstrapRequiredResponse(
        push: ClaimedPush,
    ): TerminalHttpResponsePersistence {
        val body = """{"error":"bootstrap_required"}"""
            .toByteArray(StandardCharsets.UTF_8)
        return TerminalHttpResponsePersistence(
            endpointId = "sync_push",
            requestIdentity = push.batchId,
            expectedAttemptId = push.attemptId,
            httpStatus = 409,
            exactResponseBody = body,
            responseSha256 = sha256Hex(body),
            terminalAtUtc = "2030-01-01T00:00:01Z",
            terminalErrorCode = "bootstrap_required",
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    private data class ClaimedPush(
        val batchId: String,
        val attemptId: String,
    )

    private companion object {
        val TEST_ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")
        val RECORDED_AT: OffsetDateTime =
            OffsetDateTime.parse("2030-01-01T07:00:00+07:00")
    }
}
