package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.PushAckPersistence
import ru.andriyshkoy.lifeagent.data.local.db.PushErrorPersistence
import ru.andriyshkoy.lifeagent.data.local.db.SyncPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(AndroidJUnit4::class)
class SyncWaitingParentRecoveryInstrumentedTest {
    private lateinit var fixture: SyncM2PersistenceFixture
    private lateinit var repository: RoomNotesRepository
    private lateinit var persistenceStore: SyncPersistenceStore

    @Before
    fun setUp() = runBlocking {
        fixture = SyncM2PersistenceFixture(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            label = "m2-waiting-parent",
        )
        fixture.seedIdentity(
            deviceId = SyncM2PersistenceFixture.DEVICE_ID,
            personId = SyncM2PersistenceFixture.PERSON_ID,
        )
        fixture.installActiveAuth()
        fixture.seedIncrementalStream()
        repository = RoomNotesRepository(
            database = fixture.database,
            collectorVersion = "m2-waiting-parent-test",
        )
        persistenceStore = SyncPersistenceStore(fixture.database)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun waitingParentBecomesActionableOnlyAfterExactParentReceiptAndBatchRechecks() =
        runBlocking {
            val (root, correction) = createRootAndCorrection()
            val wiredRoot = installWire(root, seed = 1)
            val wiredCorrection = installWire(correction, seed = 2)

            val missingBatch = insertAndClaimBatch(wiredCorrection)
            val missingResponse = response(
                batchId = missingBatch.batchId,
                attemptId = missingBatch.attemptId,
                body = """{"result":"missing_parent"}""",
                terminalOffsetMs = 100,
            )
            val missingResult = PushErrorPersistence(
                ordinal = 0,
                operationId = wiredCorrection.operationId,
                operationContentSha256 =
                    requireNotNull(wiredCorrection.wireOperationContentSha256),
                errorCode = "missing_parent",
                retryable = true,
                detailsJcs = "[]".toByteArray(StandardCharsets.UTF_8),
            )
            persistenceStore.commitPushResponse(
                response = missingResponse,
                results = listOf(missingResult),
            )
            val waiting = requireNotNull(
                fixture.database.noteMutationDao().findOutbox(correction.operationId),
            )
            assertEquals("waiting_parent", waiting.state)
            assertTrue(
                fixture.database.outboxDao().actionableForBatch(10)
                    .none { it.operationId == correction.operationId },
            )

            val rootBatch = insertAndClaimBatch(wiredRoot)
            val rootResponse = response(
                batchId = rootBatch.batchId,
                attemptId = rootBatch.attemptId,
                body = """{"result":"applied"}""",
                terminalOffsetMs = 200,
            )
            val rootReceipt = SyncServerChangeEntity(
                serverSequence = 1,
                operationId = wiredRoot.operationId,
                operationContentSha256 =
                    requireNotNull(wiredRoot.wireOperationContentSha256),
                resultCode = "applied",
                captureId = wiredRoot.captureId,
                eventId = wiredRoot.eventId,
                revisionId = wiredRoot.revisionId,
                currentRevisionId = wiredRoot.revisionId,
                committedAtUtc = rootResponse.terminalAtUtc,
                firstEndpointId = "sync_push",
                firstRequestIdentity = rootBatch.batchId,
                verifiedAtUtc = rootResponse.terminalAtUtc,
            )
            persistenceStore.commitPushResponse(
                response = rootResponse,
                results = listOf(PushAckPersistence(ordinal = 0, change = rootReceipt)),
            )
            fixture.reopen()
            persistenceStore = SyncPersistenceStore(fixture.database)

            assertEquals(
                listOf(correction.operationId),
                fixture.database.outboxDao().actionableForBatch(10)
                    .map { it.operationId },
            )
            val retainedReceipt = requireNotNull(
                fixture.database.syncReplicaDao()
                    .findServerChange(wiredRoot.operationId),
            )
            fixture.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sync_server_change WHERE operation_id = ?",
                arrayOf(root.operationId),
            )
            val rejectedBatchId = UUID.randomUUID().toString()
            assertTrue(
                runCatching {
                    insertBatch(
                        outbox = waiting,
                        batchId = rejectedBatchId,
                    )
                }.isFailure,
            )
            assertNull(
                fixture.database.syncTransportDao()
                    .findRequest("sync_push", rejectedBatchId),
            )
            assertNull(fixture.database.syncTransportDao().findBatch(rejectedBatchId))
            assertEquals(
                "waiting_parent",
                fixture.database.noteMutationDao()
                    .findOutbox(correction.operationId)
                    ?.state,
            )

            fixture.database.syncReplicaDao().insertServerChange(retainedReceipt)
            val recoveredBatchId = UUID.randomUUID().toString()
            insertBatch(
                outbox = waiting,
                batchId = recoveredBatchId,
            )
            val batched = requireNotNull(
                fixture.database.noteMutationDao().findOutbox(correction.operationId),
            )
            assertEquals("batched", batched.state)
            assertEquals(recoveredBatchId, batched.activeBatchId)

            persistenceStore.commitPushResponse(
                response = missingResponse,
                results = listOf(missingResult),
            )
            val afterLateReplay = requireNotNull(
                fixture.database.noteMutationDao().findOutbox(correction.operationId),
            )
            assertEquals("batched", afterLateReplay.state)
            assertEquals(recoveredBatchId, afterLateReplay.activeBatchId)
            assertEquals(
                "terminal",
                fixture.database.syncTransportDao()
                    .findRequest("sync_push", missingBatch.batchId)
                    ?.state,
            )
        }

    private suspend fun createRootAndCorrection(): Pair<SyncOutboxEntity, SyncOutboxEntity> {
        val eventId = UUID.randomUUID()
        val rootIds = MutationIds(
            operationId = UUID.randomUUID(),
            captureId = UUID.randomUUID(),
            eventId = eventId,
            revisionId = UUID.randomUUID(),
        )
        repository.create(
            CreateNoteCommand(
                ids = rootIds,
                text = "Root awaiting server receipt",
                effectiveTime = PointTimeResolver.resolveInstant(
                    BASE_RECORDED_AT.toInstant(),
                    TEST_ZONE,
                ),
                recordedAt = BASE_RECORDED_AT,
            ),
        )
        val correctionIds = MutationIds(
            operationId = UUID.randomUUID(),
            captureId = UUID.randomUUID(),
            eventId = eventId,
            revisionId = UUID.randomUUID(),
        )
        repository.correct(
            CorrectNoteCommand(
                ids = correctionIds,
                expectedCurrentRevisionId = rootIds.revisionId,
                text = "Correction waiting for its parent receipt",
                effectiveTime = PointTimeResolver.resolveInstant(
                    BASE_RECORDED_AT.plusMinutes(1).toInstant(),
                    TEST_ZONE,
                ),
                recordedAt = BASE_RECORDED_AT.plusMinutes(1),
                reason = "m2_waiting_parent_test",
            ),
        )
        return Pair(
            requireNotNull(
                fixture.database.noteMutationDao()
                    .findOutbox(rootIds.operationId.toString()),
            ),
            requireNotNull(
                fixture.database.noteMutationDao()
                    .findOutbox(correctionIds.operationId.toString()),
            ),
        )
    }

    private suspend fun installWire(
        outbox: SyncOutboxEntity,
        seed: Int,
    ): SyncOutboxEntity {
        val body = """{"operation":"${outbox.operationId}","seed":$seed}"""
            .toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            1,
            fixture.database.outboxDao().installWireMaterial(
                localSequence = outbox.localSequence,
                operationId = outbox.operationId,
                protocolVersion = "1.0.0",
                materialJcs = body,
                contentSha256 = sha256Hex(body),
                materializedAtUtc = SyncM2PersistenceFixture.BASE_UTC,
            ),
        )
        return requireNotNull(
            fixture.database.noteMutationDao().findOutbox(outbox.operationId),
        )
    }

    private suspend fun insertAndClaimBatch(
        original: SyncOutboxEntity,
    ): ClaimedBatch {
        val outbox = requireNotNull(
            fixture.database.noteMutationDao().findOutbox(original.operationId),
        )
        val batchId = UUID.randomUUID().toString()
        insertBatch(outbox, batchId)
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
        return ClaimedBatch(batchId, attemptId)
    }

    private suspend fun insertBatch(
        outbox: SyncOutboxEntity,
        batchId: String,
    ) {
        fixture.database.syncTransportDao().insertPushRequest(
            request = fixture.request(
                endpointId = "sync_push",
                requestIdentity = batchId,
            ),
            batch = SyncPushBatchEntity(
                batchId = batchId,
                endpointId = "sync_push",
                requestIdentity = batchId,
                batchContentSha256 = "b".repeat(64),
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
    }

    private fun response(
        batchId: String,
        attemptId: String,
        body: String,
        terminalOffsetMs: Long,
    ): TerminalHttpResponsePersistence {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return TerminalHttpResponsePersistence(
            endpointId = "sync_push",
            requestIdentity = batchId,
            expectedAttemptId = attemptId,
            httpStatus = 200,
            exactResponseBody = bytes,
            responseSha256 = sha256Hex(bytes),
            terminalAtUtc = Instant.ofEpochMilli(
                SyncM2PersistenceFixture.NOW_MS + terminalOffsetMs,
            ).toString(),
            terminalErrorCode = null,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    private data class ClaimedBatch(
        val batchId: String,
        val attemptId: String,
    )

    private companion object {
        val TEST_ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")
        val BASE_RECORDED_AT: OffsetDateTime =
            OffsetDateTime.parse("2030-01-01T07:00:00+07:00")
    }
}
