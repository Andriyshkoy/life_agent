package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.local.db.PushAckPersistence
import ru.andriyshkoy.lifeagent.data.local.db.PushErrorPersistence
import ru.andriyshkoy.lifeagent.data.local.db.ReplicaIntegrityException
import ru.andriyshkoy.lifeagent.data.local.db.SyncPersistenceStore
import ru.andriyshkoy.lifeagent.data.local.db.TerminalHttpResponsePersistence
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthAttemptEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthTokenFingerprintEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity
import ru.andriyshkoy.lifeagent.data.security.DatabaseKeyManager
import ru.andriyshkoy.lifeagent.data.security.KeystoreAeadEnvelope
import ru.andriyshkoy.lifeagent.data.security.KeystoreAeadPayloadCipher
import ru.andriyshkoy.lifeagent.data.security.SqlCipherKey
import ru.andriyshkoy.lifeagent.data.security.SqlCipherOpenHelperFactoryProvider
import ru.andriyshkoy.lifeagent.data.security.SqlCipherRuntime
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.IdempotencyConflictException
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationDisposition
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand

@RunWith(AndroidJUnit4::class)
class EncryptedNotesPersistenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets
    private lateinit var testId: String
    private lateinit var keyAlias: String
    private lateinit var sensitivePayloadKeyAlias: String
    private lateinit var databaseName: String
    private lateinit var envelopeRelativePath: String
    private lateinit var sqlCipherKey: SqlCipherKey
    private var database: LifeAgentDatabase? = null
    private lateinit var repository: RoomNotesRepository

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        keyAlias = "life_agent_persistence_test_$testId"
        sensitivePayloadKeyAlias = "life_agent_sensitive_payload_test_$testId"
        databaseName = "life-agent-persistence-$testId.db"
        envelopeRelativePath = "persistence-tests/$testId/room-dek-v1"
        SqlCipherRuntime.initialize()
        openStore()
    }

    @After
    fun tearDown() {
        closeStore()
        context.deleteDatabase(databaseName)
        envelopeFile().let { envelope ->
            envelope.delete()
            File("${envelope.path}.bak").delete()
            envelope.parentFile?.delete()
        }
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            listOf(keyAlias, sensitivePayloadKeyAlias).forEach { alias ->
                if (containsAlias(alias)) {
                    deleteEntry(alias)
                }
            }
        }
    }

    @Test
    fun lifecycleIsAppendOnlyIdempotentAndDurableAcrossReopen() = runBlocking {
        val create = CreateNoteCommand(
            ids = mutationIds(1, 2, EVENT_ID, 4),
            text = "Synthetic M1 note — ёж",
            effectiveTime = effectiveTime(0),
            recordedAt = recordedAt(1),
        )
        val created = persisted(repository.create(create))
        assertEquals(NoteMutationDisposition.COMMITTED, created.disposition)

        val createReplay = persisted(repository.create(create))
        assertEquals(NoteMutationDisposition.REPLAYED, createReplay.disposition)
        assertEquals(created.localSequence, createReplay.localSequence)
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)

        try {
            repository.create(create.copy(text = "Same operation, changed content"))
            throw AssertionError("A changed replay must fail closed")
        } catch (_: IdempotencyConflictException) {
            // Expected: one operation ID can only represent one immutable command.
        }
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)

        val correct = CorrectNoteCommand(
            ids = mutationIds(5, 6, EVENT_ID, 7),
            expectedCurrentRevisionId = create.ids.revisionId,
            text = "Synthetic corrected note",
            effectiveTime = effectiveTime(2),
            recordedAt = recordedAt(3),
            reason = "synthetic_fixture_correction",
        )
        val corrected = persisted(repository.correct(correct))
        assertEquals(NoteMutationDisposition.COMMITTED, corrected.disposition)
        assertEquals(2, corrected.note.revisionNo)
        assertEquals("Synthetic corrected note", corrected.note.text)

        val correctReplay = persisted(repository.correct(correct))
        assertEquals(NoteMutationDisposition.REPLAYED, correctReplay.disposition)
        assertEquals(corrected.localSequence, correctReplay.localSequence)
        assertCounts(captures = 2, revisions = 2, parents = 1, outbox = 2)
        assertParent(
            childRevisionId = correct.ids.revisionId,
            parentRevisionId = create.ids.revisionId,
        )

        val retract = RetractNoteCommand(
            ids = mutationIds(8, 9, EVENT_ID, 10),
            expectedCurrentRevisionId = correct.ids.revisionId,
            recordedAt = recordedAt(4),
        )
        val retracted = persisted(repository.retract(retract))
        assertEquals(NoteMutationDisposition.COMMITTED, retracted.disposition)
        assertEquals(NoteRecordStatus.RETRACTED, retracted.note.status)
        assertEquals("Synthetic corrected note", retracted.note.text)

        val retractReplay = persisted(repository.retract(retract))
        assertEquals(NoteMutationDisposition.REPLAYED, retractReplay.disposition)
        assertEquals(retracted.localSequence, retractReplay.localSequence)
        assertCounts(captures = 3, revisions = 3, parents = 2, outbox = 3)
        assertParent(
            childRevisionId = retract.ids.revisionId,
            parentRevisionId = correct.ids.revisionId,
        )

        closeStore()
        openStore()

        val reopened = requireNotNull(repository.getByEventId(EVENT_ID))
        assertEquals(retract.ids.revisionId, reopened.revisionId)
        assertEquals(3, reopened.revisionNo)
        assertEquals(NoteRecordStatus.RETRACTED, reopened.status)
        assertEquals("Synthetic corrected note", reopened.text)

        val pending = requireDatabase().outboxDao().pending(limit = 10)
        assertEquals(listOf(1L, 2L, 3L), pending.map { it.localSequence })
        assertEquals(
            listOf(
                create.ids.operationId.toString(),
                correct.ids.operationId.toString(),
                retract.ids.operationId.toString(),
            ),
            pending.map { it.operationId },
        )

        val export = repository.exportSnapshot()
        assertEquals(retract.ids.revisionId, export.events.single().currentRevisionId)
        assertEquals(listOf(1, 2, 3), export.revisions.map { it.revisionNo })
        assertEquals(
            listOf(
                NoteRecordStatus.ACTIVE,
                NoteRecordStatus.ACTIVE,
                NoteRecordStatus.RETRACTED,
            ),
            export.revisions.map { it.status },
        )

        val replayAfterReopen = persisted(repository.retract(retract))
        assertEquals(NoteMutationDisposition.REPLAYED, replayAfterReopen.disposition)
        assertEquals(retracted.localSequence, replayAfterReopen.localSequence)

        val redundantRetraction = repository.retract(
            RetractNoteCommand(
                ids = mutationIds(11, 12, EVENT_ID, 13),
                expectedCurrentRevisionId = retract.ids.revisionId,
                recordedAt = recordedAt(5),
            ),
        )
        assertTrue(redundantRetraction is NoteMutationOutcome.AlreadyRetracted)
        assertCounts(captures = 3, revisions = 3, parents = 2, outbox = 3)
    }

    @Test
    fun concurrentRetriesCommitExactlyOneOutboxOperation() = runBlocking {
        val command = CreateNoteCommand(
            ids = mutationIds(21, 22, CONCURRENT_EVENT_ID, 24),
            text = "Synthetic concurrent retry",
            effectiveTime = effectiveTime(10),
            recordedAt = recordedAt(11),
        )

        val receipts = coroutineScope {
            (0 until CONCURRENT_RETRY_COUNT)
                .map {
                    async(Dispatchers.Default) {
                        persisted(repository.create(command))
                    }
                }
                .awaitAll()
        }

        assertEquals(
            1,
            receipts.count {
                it.disposition == NoteMutationDisposition.COMMITTED
            },
        )
        assertEquals(
            CONCURRENT_RETRY_COUNT - 1,
            receipts.count {
                it.disposition == NoteMutationDisposition.REPLAYED
            },
        )
        assertEquals(1, receipts.map { it.localSequence }.distinct().size)
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)
    }

    @Test
    fun productionRoomNoteAndOutboxMarkersStayEncryptedAcrossReopen() = runBlocking {
        val ids = MutationIds(
            operationId = PRODUCTION_OUTBOX_SENTINEL_ID,
            captureId = PRODUCTION_CAPTURE_ID,
            eventId = PRODUCTION_SCAN_EVENT_ID,
            revisionId = PRODUCTION_REVISION_ID,
        )
        val command = CreateNoteCommand(
            ids = ids,
            text = PRODUCTION_NOTE_SENTINEL,
            effectiveTime = effectiveTime(15),
            recordedAt = recordedAt(16),
        )

        val committed = persisted(repository.create(command))
        assertEquals(NoteMutationDisposition.COMMITTED, committed.disposition)
        assertEquals(
            PRODUCTION_NOTE_SENTINEL,
            requireNotNull(repository.getByEventId(PRODUCTION_SCAN_EVENT_ID)).text,
        )
        assertEquals(
            PRODUCTION_OUTBOX_SENTINEL_ID.toString(),
            requireDatabase().outboxDao().pending(limit = 1).single().operationId,
        )
        assertAllProductionArtifactsExist()
        assertProductionMarkersAreEncrypted()

        closeStore()
        assertProductionMarkersAreEncrypted()
        openStore()

        assertEquals(
            PRODUCTION_NOTE_SENTINEL,
            requireNotNull(repository.getByEventId(PRODUCTION_SCAN_EVENT_ID)).text,
        )
        assertEquals(
            PRODUCTION_OUTBOX_SENTINEL_ID.toString(),
            requireDatabase().outboxDao().pending(limit = 1).single().operationId,
        )
        assertAllProductionArtifactsExist()
        assertProductionMarkersAreEncrypted()
    }

    @Test
    fun exactSyncRequestBatchReceiptAndRemoteHeadSurviveReopen() = runBlocking {
        val ids = mutationIds(41, 42, DURABLE_SYNC_EVENT_ID, 44)
        val recordedAt = recordedAt(30)
        persisted(
            repository.create(
                CreateNoteCommand(
                    ids = ids,
                    text = "Synthetic durable M2 request",
                    effectiveTime = effectiveTime(30),
                    recordedAt = recordedAt,
                ),
            ),
        )
        val outbox = requireDatabase().outboxDao().pending(limit = 1).single()
        val wireMaterial = """{"client_sequence":${outbox.localSequence}}"""
            .toByteArray(StandardCharsets.UTF_8)
        val wireHash = "d".repeat(64)
        assertEquals(
            1,
            requireDatabase().outboxDao().installWireMaterial(
                localSequence = outbox.localSequence,
                operationId = outbox.operationId,
                protocolVersion = "1.0.0",
                materialJcs = wireMaterial,
                contentSha256 = wireHash,
                materializedAtUtc = recordedAt.toInstant().toString(),
            ),
        )

        val endpointId = "sync_push"
        val batchId = UUID(0, 45).toString()
        val credentialEpochId = UUID(0, 46).toString()
        val deviceId = UUID(0, 47).toString()
        seedSyncStream(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            updatedAtUtc = recordedAt.toInstant().toString(),
        )
        val exactRequest = """{"synthetic":"exact request bytes"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val requestHmac = ByteArray(32) { index -> (index + 1).toByte() }
        val exactResponse = """{"synthetic":"exact response bytes"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val responseHash = "e".repeat(64)
        val transportDao = requireDatabase().syncTransportDao()
        val pushAttemptId = UUID(0, 49).toString()
        transportDao.insertPushRequest(
            request = SyncHttpRequestEntity(
                endpointId = endpointId,
                requestIdentity = batchId,
                protocolVersion = "1.0.0",
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                idempotencyKey = batchId,
                rawRequestBody = exactRequest,
                rawBodyHmac = requestHmac,
                hmacKeyGeneration = 1,
                state = "ready",
                attemptBudget = 8,
                deadlineAtEpochMs =
                    recordedAt.toInstant().plusSeconds(1_800).toEpochMilli(),
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                accessGenerationUsed = 1,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = recordedAt.toInstant().toString(),
                updatedAtUtc = recordedAt.toInstant().toString(),
            ),
            batch = SyncPushBatchEntity(
                batchId = batchId,
                endpointId = endpointId,
                requestIdentity = batchId,
                batchContentSha256 = "f".repeat(64),
                operationCount = 1,
                createdAtUtc = recordedAt.toInstant().toString(),
            ),
            items = listOf(
                SyncPushBatchItemEntity(
                    batchId = batchId,
                    ordinal = 0,
                    localSequence = outbox.localSequence,
                    operationId = outbox.operationId,
                    wireOperationContentSha256 = wireHash,
                ),
            ),
        )
        assertEquals(
            0,
            transportDao.claimAttempt(
                endpointId = endpointId,
                requestIdentity = batchId,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = UUID(0, 50).toString(),
                attemptedAtEpochMs =
                    recordedAt.toInstant().plusSeconds(1_800).toEpochMilli(),
                leaseExpiresAtEpochMs =
                    recordedAt.toInstant().plusSeconds(1_801).toEpochMilli(),
                updatedAtUtc = recordedAt.toInstant().toString(),
            ),
        )
        assertEquals(
            0,
            requireNotNull(transportDao.findRequest(endpointId, batchId)).attemptCount,
        )
        assertEquals(
            1,
            transportDao.claimAttempt(
                endpointId = endpointId,
                requestIdentity = batchId,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = pushAttemptId,
                attemptedAtEpochMs = recordedAt.toInstant().toEpochMilli(),
                leaseExpiresAtEpochMs =
                    recordedAt.toInstant().plusSeconds(60).toEpochMilli(),
                updatedAtUtc = recordedAt.toInstant().toString(),
            ),
        )

        val unknownRemoteHead = UUID(0, 48).toString()
        val committedAt = recordedAt.toInstant().plusSeconds(1).toString()
        val response = TerminalHttpResponsePersistence(
            endpointId = endpointId,
            requestIdentity = batchId,
            expectedAttemptId = pushAttemptId,
            httpStatus = 200,
            exactResponseBody = exactResponse,
            responseSha256 = responseHash,
            terminalAtUtc = committedAt,
            terminalErrorCode = null,
        )
        val change = SyncServerChangeEntity(
                serverSequence = 1,
                operationId = ids.operationId.toString(),
                operationContentSha256 = wireHash,
                resultCode = "conflict",
                captureId = ids.captureId.toString(),
                eventId = ids.eventId.toString(),
                revisionId = ids.revisionId.toString(),
                currentRevisionId = unknownRemoteHead,
                committedAtUtc = committedAt,
                firstEndpointId = endpointId,
                firstRequestIdentity = batchId,
                verifiedAtUtc = committedAt,
        )
        val persistenceStore = SyncPersistenceStore(requireDatabase())

        val rejectedChange = change.copy(operationContentSha256 = "0".repeat(64))
        assertTrue(
            "A mismatched ACK must fail the reducer",
            runCatching {
                persistenceStore.commitPushResponse(
                    response = response,
                    results = listOf(
                        PushAckPersistence(
                            ordinal = 0,
                            change = rejectedChange,
                        ),
                    ),
                )
            }.isFailure,
        )
        assertEquals(
            "sending",
            requireNotNull(transportDao.findRequest(endpointId, batchId)).state,
        )
        assertNull(transportDao.findRequest(endpointId, batchId)?.exactResponseBody)
        assertNull(
            requireDatabase()
                .syncReplicaDao()
                .findServerChange(ids.operationId.toString()),
        )
        assertEquals(
            "batched",
            requireNotNull(
                requireDatabase()
                    .noteMutationDao()
                    .findOutbox(ids.operationId.toString()),
            ).state,
        )
        assertEquals(
            "integrity_halted",
            requireDatabase().syncReplicaDao().findStreamState()?.phase,
        )
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET phase = 'incremental',
                integrity_error_code = NULL,
                updated_at_utc = '${recordedAt.toInstant()}'
            WHERE singleton_id = 1
            """.trimIndent(),
        )

        persistenceStore.commitPushResponse(
            response = response,
            results = listOf(PushAckPersistence(ordinal = 0, change = change)),
        )
        // A byte-identical replay is an insert-or-verify no-op.
        persistenceStore.commitPushResponse(
            response = response,
            results = listOf(PushAckPersistence(ordinal = 0, change = change)),
        )

        closeStore()
        openStore()

        val persistedRequest = requireNotNull(
            requireDatabase().syncTransportDao().findRequest(endpointId, batchId),
        )
        assertArrayEquals(exactRequest, persistedRequest.rawRequestBody)
        assertArrayEquals(requestHmac, persistedRequest.rawBodyHmac)
        assertArrayEquals(exactResponse, persistedRequest.exactResponseBody)
        assertEquals(responseHash, persistedRequest.responseSha256)
        assertEquals(
            ids.operationId.toString(),
            requireDatabase()
                .syncTransportDao()
                .findBatchItems(batchId)
                .single()
                .operationId,
        )
        val persistedChange = requireNotNull(
            requireDatabase()
                .syncReplicaDao()
                .findServerChange(ids.operationId.toString()),
        )
        assertEquals(unknownRemoteHead, persistedChange.currentRevisionId)
        val persistedOutbox = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findOutbox(ids.operationId.toString()),
        )
        assertEquals("conflict", persistedOutbox.lastResultCode)
        assertEquals(unknownRemoteHead, persistedOutbox.lastResultCurrentRevisionId)
        assertEquals(
            "authenticated_ingress",
            queryString(
                requireDatabase().openHelper.writableDatabase,
                """
                SELECT persistence_state FROM local_capture
                WHERE capture_id = '${ids.captureId}'
                """.trimIndent(),
            ),
        )
        val eventPointer = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findEventPointer(ids.eventId.toString()),
        )
        assertEquals(
            "Conflict ACK must not move the local working head",
            ids.revisionId.toString(),
            eventPointer.currentRevisionId,
        )
        assertEquals(unknownRemoteHead, eventPointer.serverCurrentRevisionId)
        assertEquals(1L, eventPointer.serverObservedSequence)
        assertDatabaseArtifactsDoNotContain(
            exactRequest,
            exactResponse,
            requestHmac,
        )
        requireDatabase().openHelper.writableDatabase
            .query("PRAGMA foreign_key_check")
            .use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
    }

    @Test
    fun deadlineBoundaryExhaustsWithoutConsumingAnotherAttempt() = runBlocking {
        persisted(
            repository.create(
                CreateNoteCommand(
                    ids = mutationIds(171, 172, UUID(0, 173), 174),
                    text = "Synthetic deadline identity",
                    effectiveTime = effectiveTime(55),
                    recordedAt = recordedAt(55),
                ),
            ),
        )
        val transportDao = requireDatabase().syncTransportDao()
        val requestIdentity = UUID(0, 60).toString()
        val credentialEpochId = UUID(0, 61).toString()
        val deviceId = UUID(0, 62).toString()
        val deadline = 10_000L
        seedSyncStream(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            updatedAtUtc = Instant.EPOCH.toString(),
        )
        transportDao.insertRequest(
            SyncHttpRequestEntity(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                protocolVersion = "1.0.0",
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                idempotencyKey = null,
                rawRequestBody = "{}".toByteArray(StandardCharsets.UTF_8),
                rawBodyHmac = ByteArray(32) { 1 },
                hmacKeyGeneration = 1,
                state = "ready",
                attemptBudget = 8,
                deadlineAtEpochMs = deadline,
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                accessGenerationUsed = 1,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = "2026-01-15T03:00:00Z",
                updatedAtUtc = "2026-01-15T03:00:00Z",
            ),
        )

        assertEquals(
            0,
            transportDao.claimAttempt(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = UUID(0, 63).toString(),
                attemptedAtEpochMs = deadline,
                leaseExpiresAtEpochMs = deadline,
                updatedAtUtc = "2026-01-15T03:00:01Z",
            ),
        )
        assertEquals(
            0,
            requireNotNull(
                transportDao.findRequest("sync_pull", requestIdentity),
            ).attemptCount,
        )
        assertEquals(
            1,
            transportDao.markRetryBudgetExhausted(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                nowEpochMs = deadline,
                terminalAtUtc = "2026-01-15T03:00:01Z",
            ),
        )

        closeStore()
        openStore()
        val exhausted = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_pull", requestIdentity),
        )
        assertEquals("terminal_local", exhausted.state)
        assertEquals(0, exhausted.attemptCount)
        assertEquals("retry_budget_exhausted", exhausted.terminalErrorCode)
    }

    @Test
    fun expiredLeaseTakeoverRejectsLateRetryAndTerminalTransitions() = runBlocking {
        val transportDao = requireDatabase().syncTransportDao()
        val requestIdentity = UUID(0, 111).toString()
        val credentialEpochId = UUID(0, 112).toString()
        val attemptA = UUID(0, 113).toString()
        val attemptB = UUID(0, 114).toString()
        val deviceId = UUID(0, 115).toString()
        seedSyncStream(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            updatedAtUtc = Instant.EPOCH.toString(),
        )
        transportDao.insertRequest(
            SyncHttpRequestEntity(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                protocolVersion = "1.0.0",
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                idempotencyKey = null,
                rawRequestBody = "{}".toByteArray(StandardCharsets.UTF_8),
                rawBodyHmac = ByteArray(32) { 7 },
                hmacKeyGeneration = 1,
                state = "ready",
                attemptBudget = 8,
                deadlineAtEpochMs = 10_000,
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                accessGenerationUsed = 1,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = Instant.EPOCH.toString(),
                updatedAtUtc = Instant.EPOCH.toString(),
            ),
        )
        assertEquals(
            0,
            transportDao.claimAttempt(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = UUID(0, 116).toString(),
                attemptedAtEpochMs = 1_000,
                leaseExpiresAtEpochMs = 1_000,
                updatedAtUtc = Instant.EPOCH.toString(),
            ),
        )
        assertEquals(
            1,
            transportDao.claimAttempt(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = attemptA,
                attemptedAtEpochMs = 1_000,
                leaseExpiresAtEpochMs = 2_000,
                updatedAtUtc = Instant.EPOCH.toString(),
            ),
        )
        assertEquals(
            1,
            transportDao.claimAttempt(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = attemptB,
                attemptedAtEpochMs = 2_000,
                leaseExpiresAtEpochMs = 3_000,
                updatedAtUtc = Instant.EPOCH.toString(),
            ),
        )
        assertEquals(
            0,
            transportDao.scheduleRetry(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                expectedAttemptId = attemptA,
                nextAttemptAtEpochMs = 4_000,
                lastErrorCode = "late_worker",
                updatedAtUtc = Instant.EPOCH.toString(),
            ),
        )
        assertEquals(
            0,
            transportDao.storeTerminalResponse(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                expectedAttemptId = attemptA,
                httpStatus = 200,
                exactResponseBody = "{}".toByteArray(StandardCharsets.UTF_8),
                responseSha256 = "1".repeat(64),
                terminalAtUtc = Instant.EPOCH.toString(),
                terminalErrorCode = null,
            ),
        )
        assertEquals(
            1,
            transportDao.scheduleRetry(
                endpointId = "sync_pull",
                requestIdentity = requestIdentity,
                expectedAttemptId = attemptB,
                nextAttemptAtEpochMs = 4_000,
                lastErrorCode = "current_worker",
                updatedAtUtc = Instant.EPOCH.toString(),
            ),
        )
        val persisted = requireNotNull(
            transportDao.findRequest("sync_pull", requestIdentity),
        )
        assertEquals("retry_wait", persisted.state)
        assertEquals(2, persisted.attemptCount)
        assertNull(persisted.activeAttemptId)
    }

    @Test
    fun rootMissingParentHaltsTheExactStreamWithoutReducingOutbox() = runBlocking {
        val ids = mutationIds(71, 72, RETRY_BATCH_EVENT_ID, 74)
        val recordedAt = recordedAt(50)
        persisted(
            repository.create(
                CreateNoteCommand(
                    ids = ids,
                    text = "Synthetic missing-parent batch retry",
                    effectiveTime = effectiveTime(50),
                    recordedAt = recordedAt,
                ),
            ),
        )
        val outbox = requireDatabase().outboxDao().pending(limit = 1).single()
        val wireHash = "7".repeat(64)
        assertEquals(
            1,
            requireDatabase().outboxDao().installWireMaterial(
                localSequence = outbox.localSequence,
                operationId = outbox.operationId,
                protocolVersion = "1.0.0",
                materialJcs = "{}".toByteArray(StandardCharsets.UTF_8),
                contentSha256 = wireHash,
                materializedAtUtc = recordedAt.toInstant().toString(),
            ),
        )

        val transportDao = requireDatabase().syncTransportDao()
        val batchA = UUID(0, 75).toString()
        val credentialEpochId = UUID(0, 77).toString()
        val deviceId = UUID(0, 78).toString()
        val batchAAttemptId = UUID(0, 79).toString()
        seedSyncStream(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            updatedAtUtc = recordedAt.toInstant().toString(),
        )

        fun request(batchId: String) = SyncHttpRequestEntity(
            endpointId = "sync_push",
            requestIdentity = batchId,
            protocolVersion = "1.0.0",
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            idempotencyKey = batchId,
            rawRequestBody =
                """{"batch_id":"$batchId"}""".toByteArray(StandardCharsets.UTF_8),
            rawBodyHmac = ByteArray(32) { 2 },
            hmacKeyGeneration = 1,
            state = "ready",
            attemptBudget = 8,
            deadlineAtEpochMs =
                recordedAt.toInstant().plusSeconds(1_800).toEpochMilli(),
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            leaseExpiresAtEpochMs = null,
            accessGenerationUsed = 1,
            terminalHttpStatus = null,
            exactResponseBody = null,
            responseSha256 = null,
            terminalAtUtc = null,
            terminalErrorCode = null,
            createdAtUtc = recordedAt.toInstant().toString(),
            updatedAtUtc = recordedAt.toInstant().toString(),
        )

        suspend fun insertBatch(batchId: String) {
            transportDao.insertPushRequest(
                request = request(batchId),
                batch = SyncPushBatchEntity(
                    batchId = batchId,
                    endpointId = "sync_push",
                    requestIdentity = batchId,
                    batchContentSha256 = "8".repeat(64),
                    operationCount = 1,
                    createdAtUtc = recordedAt.toInstant().toString(),
                ),
                items = listOf(
                    SyncPushBatchItemEntity(
                        batchId = batchId,
                        ordinal = 0,
                        localSequence = outbox.localSequence,
                        operationId = outbox.operationId,
                        wireOperationContentSha256 = wireHash,
                    ),
                ),
            )
        }

        insertBatch(batchA)
        assertEquals(
            1,
            transportDao.claimAttempt(
                endpointId = "sync_push",
                requestIdentity = batchA,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = batchAAttemptId,
                attemptedAtEpochMs = recordedAt.toInstant().toEpochMilli(),
                leaseExpiresAtEpochMs =
                    recordedAt.toInstant().plusSeconds(60).toEpochMilli(),
                updatedAtUtc = recordedAt.toInstant().toString(),
            ),
        )
        val responseA = TerminalHttpResponsePersistence(
            endpointId = "sync_push",
            requestIdentity = batchA,
            expectedAttemptId = batchAAttemptId,
            httpStatus = 200,
            exactResponseBody =
                """{"result":"missing_parent"}""".toByteArray(StandardCharsets.UTF_8),
            responseSha256 = "9".repeat(64),
            terminalAtUtc = recordedAt.toInstant().plusSeconds(1).toString(),
            terminalErrorCode = null,
        )
        val resultA = PushErrorPersistence(
            ordinal = 0,
            operationId = outbox.operationId,
            operationContentSha256 = wireHash,
            errorCode = "missing_parent",
            retryable = true,
            detailsJcs = "[]".toByteArray(StandardCharsets.UTF_8),
        )
        val persistenceStore = SyncPersistenceStore(requireDatabase())
        assertTrue(
            runCatching {
                persistenceStore.commitPushResponse(responseA, listOf(resultA))
            }.exceptionOrNull() is ReplicaIntegrityException,
        )
        val retained = requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(outbox.operationId),
        )
        assertEquals("batched", retained.state)
        assertEquals(batchA, retained.activeBatchId)
        assertNull(retained.lastResultBatchId)
        val halted = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", halted.phase)
        assertEquals("missing_parent_root_invalid", halted.integrityErrorCode)
    }

    @Test
    fun sealedRevokeBodyRetriesAcrossReopenAndKeyLossQuarantines() = runBlocking {
        val requestIdentity = UUID(0, 81).toString()
        val credentialEpochId = UUID(0, 82).toString()
        val deviceId = UUID(0, 83).toString()
        persisted(
            repository.create(
                CreateNoteCommand(
                    ids = mutationIds(161, 162, UUID(0, 163), 164),
                    text = "Synthetic revoke identity",
                    effectiveTime = effectiveTime(50),
                    recordedAt = recordedAt(50),
                ),
            ),
        )
        seedSyncStream(
            credentialEpochId = credentialEpochId,
            deviceId = deviceId,
            updatedAtUtc = "2026-01-15T03:00:00Z",
        )
        assertEquals(
            1,
            requireDatabase().syncAuthDao().claimRevokeFamily(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                generation = 1,
                nowEpochMs = 1_000,
                updatedAtUtc = "2026-01-15T03:00:00Z",
            ),
        )
        val exactBody =
            """{"refresh_token":"$REVOKE_REFRESH_TOKEN_SENTINEL","request_id":"$requestIdentity"}"""
                .toByteArray(StandardCharsets.UTF_8)
        val hmacKey = SecretKeySpec(ByteArray(32) { 4 }, "HmacSHA256")
        val expectedHmac = Mac.getInstance("HmacSHA256").run {
            init(hmacKey)
            doFinal(exactBody)
        }
        val payloadCipher = KeystoreAeadPayloadCipher(
            context = context,
            keyAlias = sensitivePayloadKeyAlias,
            keyGeneration = 1,
        )
        val envelope = payloadCipher.seal(
            plaintext = exactBody,
            purpose = "auth_revoke_request",
            recordIdentity = requestIdentity,
        )
        requireDatabase().syncTransportDao().insertRequest(
            SyncHttpRequestEntity(
                endpointId = "auth_revoke",
                requestIdentity = requestIdentity,
                protocolVersion = "1.0.0",
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                idempotencyKey = null,
                bodyStorageKind = SyncHttpRequestEntity.BODY_STORAGE_KEYSTORE_AEAD,
                rawRequestBody = null,
                sealedBodyCiphertext = envelope.ciphertext,
                sealedBodyNonce = envelope.nonce,
                sealedBodyKeyAlias = envelope.keyAlias,
                sealedBodyKeyGeneration = envelope.keyGeneration,
                sealedBodyAadVersion = envelope.aadVersion,
                requestBodyOctetCount = envelope.plaintextOctetCount,
                rawBodyHmac = expectedHmac,
                hmacKeyGeneration = 1,
                state = "ready",
                attemptBudget = 8,
                deadlineAtEpochMs = 10_000,
                nextAttemptAtEpochMs = null,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                accessGenerationUsed = 1,
                terminalHttpStatus = null,
                exactResponseBody = null,
                responseSha256 = null,
                terminalAtUtc = null,
                terminalErrorCode = null,
                createdAtUtc = "2026-01-15T03:00:00Z",
                updatedAtUtc = "2026-01-15T03:00:00Z",
            ),
        )
        val transportDao = requireDatabase().syncTransportDao()
        val revokeAttemptOne = UUID(0, 84).toString()
        val revokeAttemptTwo = UUID(0, 85).toString()
        assertEquals(
            "Generic Bearer request claim must reject auth revoke",
            0,
            transportDao.claimAttempt(
                endpointId = "auth_revoke",
                requestIdentity = requestIdentity,
                credentialEpochId = credentialEpochId,
                accessGenerationUsed = 1,
                attemptId = UUID(0, 86).toString(),
                attemptedAtEpochMs = 1_000,
                leaseExpiresAtEpochMs = 2_000,
                updatedAtUtc = "2026-01-15T03:00:01Z",
            ),
        )
        assertEquals(
            1,
            transportDao.claimRevokeAttempt(
                requestIdentity = requestIdentity,
                attemptId = revokeAttemptOne,
                attemptedAtEpochMs = 1_000,
                leaseExpiresAtEpochMs = 2_000,
                updatedAtUtc = "2026-01-15T03:00:01Z",
            ),
        )
        assertEquals(
            1,
            transportDao.scheduleRetry(
                endpointId = "auth_revoke",
                requestIdentity = requestIdentity,
                expectedAttemptId = revokeAttemptOne,
                nextAttemptAtEpochMs = 3_000,
                lastErrorCode = "transport_ambiguous",
                updatedAtUtc = "2026-01-15T03:00:02Z",
            ),
        )
        assertDatabaseArtifactsDoNotContain(
            exactBody,
            REVOKE_REFRESH_TOKEN_SENTINEL.toByteArray(StandardCharsets.UTF_8),
        )

        closeStore()
        openStore()
        val persisted = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("auth_revoke", requestIdentity),
        )
        assertNull(persisted.rawRequestBody)
        assertFalse(
            checkNotNull(persisted.sealedBodyCiphertext)
                .containsSubsequence(
                    REVOKE_REFRESH_TOKEN_SENTINEL.toByteArray(StandardCharsets.UTF_8),
                ),
        )
        val persistedEnvelope = KeystoreAeadEnvelope(
            ciphertext = checkNotNull(persisted.sealedBodyCiphertext),
            nonce = checkNotNull(persisted.sealedBodyNonce),
            keyAlias = checkNotNull(persisted.sealedBodyKeyAlias),
            keyGeneration = checkNotNull(persisted.sealedBodyKeyGeneration),
            aadVersion = checkNotNull(persisted.sealedBodyAadVersion),
            plaintextOctetCount = persisted.requestBodyOctetCount,
        )
        assertTrue(
            payloadCipher.withAuthenticatedPlaintext(
                envelope = persistedEnvelope,
                purpose = "auth_revoke_request",
                recordIdentity = requestIdentity,
            ) { plaintext ->
                plaintext.contentEquals(exactBody)
            },
        )
        assertEquals(
            1,
            requireDatabase().syncTransportDao().claimRevokeAttempt(
                requestIdentity = requestIdentity,
                attemptId = revokeAttemptTwo,
                attemptedAtEpochMs = 3_000,
                leaseExpiresAtEpochMs = 4_000,
                updatedAtUtc = "2026-01-15T03:00:03Z",
            ),
        )
        val reclaimed = requireNotNull(
            requireDatabase().syncTransportDao().findRequest(
                endpointId = "auth_revoke",
                requestIdentity = requestIdentity,
            ),
        )
        assertEquals("sending", reclaimed.state)
        assertEquals(2, reclaimed.attemptCount)
        assertEquals(revokeAttemptTwo, reclaimed.activeAttemptId)
        assertEquals(3_000L, reclaimed.lastAttemptAtEpochMs)
        assertEquals(4_000L, reclaimed.leaseExpiresAtEpochMs)
        assertNull(reclaimed.nextAttemptAtEpochMs)
        assertTrue(
            requireNotNull(
                requireDatabase().syncTransportDao().findResponseRouteSnapshot(
                    endpointId = "auth_revoke",
                    requestIdentity = requestIdentity,
                    expectedAttemptId = revokeAttemptTwo,
                ),
            ).hasFreshResponseMetadataShape,
        )

        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            deleteEntry(sensitivePayloadKeyAlias)
        }
        assertTrue(
            runCatching {
                payloadCipher.withAuthenticatedPlaintext(
                    envelope = persistedEnvelope,
                    purpose = "auth_revoke_request",
                    recordIdentity = requestIdentity,
                ) { Unit }
            }.isFailure,
        )
        requireDatabase().syncTransportDao().quarantineSealedRevokeRequest(
            requestIdentity = requestIdentity,
            expectedKeyAlias = sensitivePayloadKeyAlias,
            expectedKeyGeneration = 1,
            expectedAadVersion = 1,
            expectedAttemptId = revokeAttemptTwo,
            quarantinedAtUtc = "2026-01-15T03:00:04Z",
            failureCode = "sealed_body_key_unavailable",
        )
        closeStore()
        openStore()
        val quarantined = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("auth_revoke", requestIdentity),
        )
        assertEquals("integrity_failure", quarantined.state)
        assertEquals("sealed_body_key_unavailable", quarantined.terminalErrorCode)
        assertDatabaseArtifactsDoNotContain(
            exactBody,
            REVOKE_REFRESH_TOKEN_SENTINEL.toByteArray(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun refreshClaimRejectsExactExpiryBoundaryBeforeDispatch() = runBlocking {
        persisted(
            repository.create(
                CreateNoteCommand(
                    ids = mutationIds(91, 92, AUTH_EXPIRY_EVENT_ID, 94),
                    text = "Synthetic auth expiry identity",
                    effectiveTime = effectiveTime(60),
                    recordedAt = recordedAt(60),
                ),
            ),
        )
        val identity = requireNotNull(requireDatabase().identityDao().findIdentity())
        val credentialEpochId = UUID(0, 95).toString()
        val deviceId = UUID(0, 96).toString()
        val accessExpiry = 5_000L
        val refreshExpiry = 10_000L
        val familyExpiry = 20_000L
        val authDao = requireDatabase().syncAuthDao()
        requireDatabase().identityDao().bindCurrentServerIdentity(
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = deviceId,
            personId = UUID(0, 97).toString(),
        )
        authDao.installEnrollment(
            state = SyncAuthStateEntity(
                credentialEpochId = credentialEpochId,
                installationId = identity.installationId,
                localOwnerId = identity.localOwnerId,
                deviceId = deviceId,
                personId = UUID(0, 97).toString(),
                tokenType = "Bearer",
                refreshTokenCiphertext = byteArrayOf(1, 2, 3),
                refreshTokenNonce = ByteArray(12) { 4 },
                refreshTokenKeyAlias = "synthetic_refresh_key",
                refreshTokenKeyGeneration = 1,
                refreshTokenAadVersion = 1,
                accessExpiresAtUtc = Instant.ofEpochMilli(accessExpiry).toString(),
                accessExpiresAtEpochMs = accessExpiry,
                refreshExpiresAtUtc = Instant.ofEpochMilli(refreshExpiry).toString(),
                refreshExpiresAtEpochMs = refreshExpiry,
                familyExpiresAtUtc = Instant.ofEpochMilli(familyExpiry).toString(),
                familyExpiresAtEpochMs = familyExpiry,
                generation = 1,
                state = "active",
                bootstrapRequired = true,
                installedAtUtc = Instant.EPOCH.toString(),
                updatedAtUtc = Instant.EPOCH.toString(),
                failureCode = null,
            ),
            accessFingerprint = SyncAuthTokenFingerprintEntity(
                credentialEpochId = credentialEpochId,
                generation = 1,
                tokenKind = "access",
                tokenHmac = ByteArray(32) { 5 },
                hmacKeyGeneration = 1,
                createdAtUtc = Instant.EPOCH.toString(),
            ),
            refreshFingerprint = SyncAuthTokenFingerprintEntity(
                credentialEpochId = credentialEpochId,
                generation = 1,
                tokenKind = "refresh",
                tokenHmac = ByteArray(32) { 6 },
                hmacKeyGeneration = 1,
                createdAtUtc = Instant.EPOCH.toString(),
            ),
        )

        fun attempt(requestId: String) = SyncAuthAttemptEntity(
            requestId = requestId,
            endpointId = "auth_refresh",
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            credentialEpochId = credentialEpochId,
            expectedDeviceId = deviceId,
            expectedGeneration = 1,
            state = "dispatching",
            createdAtUtc = Instant.EPOCH.toString(),
            updatedAtUtc = Instant.EPOCH.toString(),
            lastErrorCode = null,
        )

        val boundaryRequest = UUID(0, 98).toString()
        assertTrue(
            runCatching {
                authDao.claimRefreshAttempt(
                    entity = attempt(boundaryRequest),
                    nowEpochMs = refreshExpiry,
                )
            }.isFailure,
        )
        assertNull(authDao.findAttempt(boundaryRequest))
        assertEquals("active", requireNotNull(authDao.findState()).state)

        val beforeBoundaryRequest = UUID(0, 99).toString()
        authDao.claimRefreshAttempt(
            entity = attempt(beforeBoundaryRequest),
            nowEpochMs = refreshExpiry - 1,
        )
        assertEquals(
            "dispatching",
            requireNotNull(authDao.findAttempt(beforeBoundaryRequest)).state,
        )
        assertEquals("refresh_in_flight", requireNotNull(authDao.findState()).state)
    }

    @Test
    fun reopenReplacesStaleRuntimeGuardAndRejectsPartialEnvelopes() = runBlocking {
        persisted(
            repository.create(
                CreateNoteCommand(
                    ids = mutationIds(121, 122, RUNTIME_GUARD_EVENT_ID, 124),
                    text = "Synthetic runtime guard identity",
                    effectiveTime = effectiveTime(70),
                    recordedAt = recordedAt(70),
                ),
            ),
        )
        val identity = requireNotNull(requireDatabase().identityDao().findIdentity())
        val sqlite = requireDatabase().openHelper.writableDatabase
        sqlite.execSQL("DROP TRIGGER IF EXISTS guard_sync_http_request_body_insert")
        sqlite.execSQL(
            """
            CREATE TRIGGER guard_sync_http_request_body_insert
            BEFORE INSERT ON sync_http_request
            WHEN 0
            BEGIN
                SELECT RAISE(ABORT, 'stale no-op trigger');
            END
            """.trimIndent(),
        )

        closeStore()
        openStore()
        assertRuntimeStorageGuards(
            db = requireDatabase().openHelper.writableDatabase,
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
        )
    }

    @Test
    fun lateOutboxConstraintFailureRollsBackTheWholeMutation() = runBlocking {
        val command = CreateNoteCommand(
            ids = mutationIds(31, 32, ATOMIC_EVENT_ID, 34),
            text = "Synthetic forced rollback",
            effectiveTime = effectiveTime(20),
            recordedAt = recordedAt(21),
        )
        val sqlite = requireDatabase().openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER reject_synthetic_outbox
            BEFORE INSERT ON sync_outbox
            WHEN NEW.operation_id = '${command.ids.operationId}'
            BEGIN
                SELECT RAISE(ABORT, 'forced late outbox failure');
            END
            """.trimIndent(),
        )

        val failure = try {
            repository.create(command)
            null
        } catch (error: Exception) {
            error
        }
        assertTrue("The late outbox write must fail", failure != null)

        val counts = requireDatabase().noteMutationDao().tableCounts()
        assertEquals(0, counts.captures)
        assertEquals(0, counts.events)
        assertEquals(0, counts.revisions)
        assertEquals(0, counts.parents)
        assertEquals(0, counts.heads)
        assertEquals(0, counts.outboxOperations)
        assertNull(requireDatabase().identityDao().findIdentity())
        assertEquals(0, queryInt(sqlite, "SELECT COUNT(*) FROM local_installation"))
        assertEquals(0, queryInt(sqlite, "SELECT COUNT(*) FROM local_owner"))
        assertNull(repository.getByEventId(ATOMIC_EVENT_ID))

        sqlite.execSQL("DROP TRIGGER reject_synthetic_outbox")
        val retry = persisted(repository.create(command))
        assertEquals(NoteMutationDisposition.COMMITTED, retry.disposition)
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)
    }

    @Test
    fun encryptedVersionOneDatabaseMigratesWithHistoryAndOutboxIntact() = runBlocking {
        closeStore()
        context.deleteDatabase(databaseName)
        sqlCipherKey = keyManager().openSqlCipherKey()

        val legacyHelper = createVersionOneOpenHelper()
        legacyHelper.writableDatabase
        legacyHelper.close()

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.isFile)
        assertFalse(
            databaseFile.readBytes()
                .take(SQLITE_PLAINTEXT_HEADER.size)
                .toByteArray()
                .contentEquals(SQLITE_PLAINTEXT_HEADER),
        )

        sqlCipherKey.close()
        sqlCipherKey = keyManager().openSqlCipherKey()
        openRoomWithCurrentKey()

        val migrated = requireDatabase().openHelper.writableDatabase
        assertEquals(
            LifeAgentDatabase.VERSION,
            queryInt(migrated, "PRAGMA user_version"),
        )
        assertEquals(2, queryInt(migrated, "SELECT COUNT(*) FROM local_capture"))
        assertEquals(1, queryInt(migrated, "SELECT COUNT(*) FROM local_life_event"))
        assertEquals(2, queryInt(migrated, "SELECT COUNT(*) FROM local_event_revision"))
        assertEquals(1, queryInt(migrated, "SELECT COUNT(*) FROM local_revision_parent"))
        assertEquals(1, queryInt(migrated, "SELECT COUNT(*) FROM local_event_head"))
        assertEquals(2, queryInt(migrated, "SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(
            1,
            queryInt(migrated, "SELECT COUNT(*) FROM local_identity_state"),
        )
        assertEquals(0, queryInt(migrated, "SELECT COUNT(*) FROM sync_auth_state"))
        assertEquals(0, queryInt(migrated, "SELECT COUNT(*) FROM sync_http_request"))

        val current = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findCurrentRevision(MIGRATION_EVENT_ID.toString()),
        )
        assertEquals(MIGRATION_REVISION_TWO.toString(), current.headRevisionId)
        assertEquals(2, current.revision.revisionNo)

        val parent = requireDatabase()
            .noteQueryDao()
            .findParents(MIGRATION_REVISION_TWO.toString())
            .single()
        assertEquals(MIGRATION_REVISION_ONE.toString(), parent.parentRevisionId)
        assertEquals("supersedes", parent.relation)

        val pending = requireDatabase().outboxDao().pending(limit = 10)
        assertEquals(listOf(1L, 2L), pending.map { it.localSequence })
        assertTrue(
            "Legacy rows must remain explicitly un-fingerprinted after migration",
            pending.all { it.commandFingerprintSha256.isEmpty() },
        )
        assertTrue(
            "Unsupported legacy operations must never be mistaken for M2 wire material",
            pending.all { it.wireState == "blocked_legacy_schema" },
        )
        assertTrue(pending.all { it.wireOperationMaterialJcs == null })
        assertTrue(pending.all { it.wireOperationContentSha256 == null })

        migrated.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Migrated fixture must satisfy every foreign key", cursor.moveToFirst())
        }

        val createReplay = persisted(
            repository.create(
                CreateNoteCommand(
                    ids = MutationIds(
                        operationId = MIGRATION_OPERATION_ONE,
                        captureId = MIGRATION_CAPTURE_ONE,
                        eventId = MIGRATION_EVENT_ID,
                        revisionId = MIGRATION_REVISION_ONE,
                    ),
                    text = "Synthetic legacy note",
                    effectiveTime = effectiveTime(0),
                    recordedAt = recordedAt(0),
                ),
            ),
        )
        assertEquals(NoteMutationDisposition.REPLAYED, createReplay.disposition)

        val correctionReplay = persisted(
            repository.correct(
                CorrectNoteCommand(
                    ids = MutationIds(
                        operationId = MIGRATION_OPERATION_TWO,
                        captureId = MIGRATION_CAPTURE_TWO,
                        eventId = MIGRATION_EVENT_ID,
                        revisionId = MIGRATION_REVISION_TWO,
                    ),
                    expectedCurrentRevisionId = MIGRATION_REVISION_ONE,
                    text = "Synthetic legacy corrected note",
                    effectiveTime = effectiveTime(1),
                    recordedAt = recordedAt(1),
                ),
            ),
        )
        assertEquals(NoteMutationDisposition.REPLAYED, correctionReplay.disposition)

        val upgradedPending = requireDatabase().outboxDao().pending(limit = 10)
        assertTrue(
            "An exact replay must lazily install real command fingerprints",
            upgradedPending.all { it.commandFingerprintSha256.isNotBlank() },
        )
        try {
            repository.create(
                CreateNoteCommand(
                    ids = MutationIds(
                        operationId = MIGRATION_OPERATION_ONE,
                        captureId = MIGRATION_CAPTURE_ONE,
                        eventId = MIGRATION_EVENT_ID,
                        revisionId = MIGRATION_REVISION_ONE,
                    ),
                    text = "Changed legacy replay",
                    effectiveTime = effectiveTime(0),
                    recordedAt = recordedAt(0),
                ),
            )
            throw AssertionError("A changed legacy replay must fail closed")
        } catch (_: IdempotencyConflictException) {
            // Expected after the real command fingerprint has been reconstructed.
        }
    }

    @Test
    fun encryptedVersionTwoDatabaseMigratesWithoutReinterpretingLegacyBytes() =
        runBlocking {
            closeStore()
            context.deleteDatabase(databaseName)
            sqlCipherKey = keyManager().openSqlCipherKey()

            val legacyHelper = createVersionTwoOpenHelper()
            val legacy = legacyHelper.writableDatabase
            legacy.execSQL(
                """
                UPDATE sync_outbox
                SET schema_version = '4.0.0'
                WHERE local_sequence = 2
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                UPDATE local_capture
                SET schema_version = '4.0.0'
                WHERE capture_id = ?
                """.trimIndent(),
                arrayOf(MIGRATION_CAPTURE_TWO.toString()),
            )
            legacy.execSQL(
                """
                UPDATE local_event_revision
                SET schema_version = '4.0.0'
                WHERE revision_id = ?
                """.trimIndent(),
                arrayOf(MIGRATION_REVISION_TWO.toString()),
            )
            legacy.execSQL(
                "UPDATE sqlite_sequence SET seq = 25 WHERE name = 'sync_outbox'",
            )
            legacyHelper.close()

            sqlCipherKey.close()
            sqlCipherKey = keyManager().openSqlCipherKey()
            openRoomWithCurrentKey()

            val migrated = requireDatabase().openHelper.writableDatabase
            assertEquals(
                LifeAgentDatabase.VERSION,
                queryInt(migrated, "PRAGMA user_version"),
            )
            val currentIdentity = requireNotNull(
                requireDatabase().identityDao().findIdentity(),
            )
            assertEquals(
                MIGRATION_INSTALLATION_ID.toString(),
                currentIdentity.installationId,
            )
            assertEquals(MIGRATION_OWNER_ID.toString(), currentIdentity.localOwnerId)

            val pending = requireDatabase().outboxDao().pending(limit = 10)
            assertEquals(2, pending.size)
            assertArrayEquals(
                legacyOperationBytes(1),
                pending[0].legacyOperationJcs,
            )
            assertArrayEquals(
                legacyOperationBytes(2),
                pending[1].legacyOperationJcs,
            )
            assertEquals(
                listOf(LEGACY_OPERATION_HASH_ONE, LEGACY_OPERATION_HASH_TWO),
                pending.map { it.legacyOperationContentSha256 },
            )
            assertEquals(
                listOf("blocked_legacy_schema", "needs_materialization"),
                pending.map { it.wireState },
            )
            assertTrue(pending.all { it.wireOperationMaterialJcs == null })
            assertTrue(pending.all { it.wireOperationContentSha256 == null })
            assertEquals(
                25,
                queryInt(
                    migrated,
                    "SELECT seq FROM sqlite_sequence WHERE name = 'sync_outbox'",
                ),
            )

            val postMigration = persisted(
                repository.create(
                    CreateNoteCommand(
                        ids = mutationIds(51, 52, MIGRATION_NEXT_EVENT_ID, 54),
                        text = "Synthetic post-migration sequence check",
                        effectiveTime = effectiveTime(40),
                        recordedAt = recordedAt(40),
                    ),
                ),
            )
            assertEquals(26L, postMigration.localSequence)

            val unknownRemoteHead = UUID(0, 999).toString()
            migrated.execSQL(
                """
                UPDATE local_event_head
                SET server_current_revision_id = ?,
                    server_observed_sequence = 999
                WHERE event_id = ?
                """.trimIndent(),
                arrayOf(unknownRemoteHead, MIGRATION_EVENT_ID.toString()),
            )
            assertEquals(
                unknownRemoteHead,
                queryString(
                    migrated,
                    """
                    SELECT server_current_revision_id
                    FROM local_event_head
                    WHERE event_id = '${MIGRATION_EVENT_ID}'
                    """.trimIndent(),
                ),
            )
            migrated.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(
                    "A scalar remote conflict head must not require a local revision",
                    cursor.moveToFirst(),
                )
            }
            assertRuntimeStorageGuards(
                db = migrated,
                installationId = currentIdentity.installationId,
                localOwnerId = currentIdentity.localOwnerId,
            )
        }

    @Test
    fun emptyVersionTwoDatabaseMigratesAndStartsOutboxSequenceAtOne() = runBlocking {
        closeStore()
        context.deleteDatabase(databaseName)
        sqlCipherKey = keyManager().openSqlCipherKey()

        val emptyLegacyHelper = createEmptyVersionTwoOpenHelper()
        emptyLegacyHelper.writableDatabase
        emptyLegacyHelper.close()

        sqlCipherKey.close()
        sqlCipherKey = keyManager().openSqlCipherKey()
        openRoomWithCurrentKey()

        val migrated = requireDatabase().openHelper.writableDatabase
        assertEquals(LifeAgentDatabase.VERSION, queryInt(migrated, "PRAGMA user_version"))
        assertEquals(0, queryInt(migrated, "SELECT COUNT(*) FROM local_owner"))
        assertEquals(0, queryInt(migrated, "SELECT COUNT(*) FROM local_capture"))
        assertEquals(0, queryInt(migrated, "SELECT COUNT(*) FROM sync_outbox"))
        assertNull(requireDatabase().identityDao().findIdentity())
        assertEquals(
            4,
            queryInt(
                migrated,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'guard_sync_%'",
            ),
        )
        migrated.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        assertEquals("ok", queryString(migrated, "PRAGMA integrity_check"))

        val first = persisted(
            repository.create(
                CreateNoteCommand(
                    ids = mutationIds(141, 142, EMPTY_MIGRATION_EVENT_ID, 144),
                    text = "Synthetic first post-empty-migration operation",
                    effectiveTime = effectiveTime(80),
                    recordedAt = recordedAt(80),
                ),
            ),
        )
        assertEquals(1L, first.localSequence)
    }

    @Test
    fun lateVersionTwoMigrationFailureRollsBackEveryCoreTableChange() {
        closeStore()
        context.deleteDatabase(databaseName)
        sqlCipherKey = keyManager().openSqlCipherKey()

        val legacyHelper = createVersionTwoOpenHelper()
        legacyHelper.writableDatabase.execSQL(
            "CREATE TABLE sync_auth_state (`unexpected` INTEGER NOT NULL)",
        )
        legacyHelper.close()

        val failure = runCatching {
            openRoomWithCurrentKey()
            requireDatabase().openHelper.writableDatabase
        }.exceptionOrNull()
        assertTrue("The deliberately malformed late table must fail migration", failure != null)
        database?.close()
        database = null

        val verifier = createVersionTwoOpenHelper()
        val rolledBack = verifier.writableDatabase
        assertEquals(2, queryInt(rolledBack, "PRAGMA user_version"))
        assertEquals(2, queryInt(rolledBack, "SELECT COUNT(*) FROM sync_outbox"))
        assertEquals(
            0,
            queryInt(
                rolledBack,
                """
                SELECT COUNT(*)
                FROM pragma_table_info('sync_outbox')
                WHERE name = 'wire_state'
                """.trimIndent(),
            ),
        )
        assertArrayEquals(
            legacyOperationBytes(1),
            queryBlob(
                rolledBack,
                "SELECT operation_jcs FROM sync_outbox WHERE local_sequence = 1",
            ),
        )
        verifier.close()
    }

    private suspend fun seedSyncStream(
        credentialEpochId: String,
        deviceId: String,
        updatedAtUtc: String,
    ) {
        val database = requireDatabase()
        if (database.identityDao().findIdentity() == null) {
            database.identityDao().insertInstallation(
                LocalInstallationEntity(
                    installationId = SYNTHETIC_INSTALLATION_ID.toString(),
                    createdAtUtc = updatedAtUtc,
                    serverDeviceId = null,
                ),
            )
            database.identityDao().insertOwner(
                LocalOwnerEntity(
                    localOwnerId = SYNTHETIC_LOCAL_OWNER_ID.toString(),
                    installationId = SYNTHETIC_INSTALLATION_ID.toString(),
                    createdAtUtc = updatedAtUtc,
                    serverPersonId = null,
                ),
            )
            database.identityDao().insertIdentityState(
                LocalIdentityStateEntity(
                    installationId = SYNTHETIC_INSTALLATION_ID.toString(),
                    localOwnerId = SYNTHETIC_LOCAL_OWNER_ID.toString(),
                    selectedAtUtc = updatedAtUtc,
                ),
            )
        }
        val identity = requireNotNull(database.identityDao().findIdentity())
        database.identityDao().bindCurrentServerIdentity(
            installationId = identity.installationId,
            localOwnerId = identity.localOwnerId,
            deviceId = deviceId,
            personId = SYNTHETIC_PERSON_ID.toString(),
        )
        if (database.syncAuthDao().findState() == null) {
            database.syncAuthDao().installEnrollment(
                state = SyncAuthStateEntity(
                    credentialEpochId = credentialEpochId,
                    installationId = identity.installationId,
                    localOwnerId = identity.localOwnerId,
                    deviceId = deviceId,
                    personId = SYNTHETIC_PERSON_ID.toString(),
                    tokenType = "Bearer",
                    refreshTokenCiphertext = byteArrayOf(1, 2, 3),
                    refreshTokenNonce = ByteArray(12) { 4 },
                    refreshTokenKeyAlias = "synthetic-sync-refresh-key",
                    refreshTokenKeyGeneration = 1,
                    refreshTokenAadVersion = 1,
                    accessExpiresAtUtc = "2098-01-01T00:00:00Z",
                    accessExpiresAtEpochMs =
                        Instant.parse("2098-01-01T00:00:00Z").toEpochMilli(),
                    refreshExpiresAtUtc = "2099-01-01T00:00:00Z",
                    refreshExpiresAtEpochMs =
                        Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
                    familyExpiresAtUtc = "2100-01-01T00:00:00Z",
                    familyExpiresAtEpochMs =
                        Instant.parse("2100-01-01T00:00:00Z").toEpochMilli(),
                    generation = 1,
                    state = "active",
                    bootstrapRequired = false,
                    installedAtUtc = updatedAtUtc,
                    updatedAtUtc = updatedAtUtc,
                    failureCode = null,
                ),
                accessFingerprint = SyncAuthTokenFingerprintEntity(
                    credentialEpochId = credentialEpochId,
                    generation = 1,
                    tokenKind = "access",
                    tokenHmac = ByteArray(32) { 5 },
                    hmacKeyGeneration = 1,
                    createdAtUtc = updatedAtUtc,
                ),
                refreshFingerprint = SyncAuthTokenFingerprintEntity(
                    credentialEpochId = credentialEpochId,
                    generation = 1,
                    tokenKind = "refresh",
                    tokenHmac = ByteArray(32) { 6 },
                    hmacKeyGeneration = 1,
                    createdAtUtc = updatedAtUtc,
                ),
            )
        }
        database.syncReplicaDao().insertStreamState(
            SyncStreamStateEntity(
                credentialEpochId = credentialEpochId,
                deviceId = deviceId,
                phase = "incremental",
                bootstrapRequired = false,
                appliedCursor = "instrumented-sync-cursor",
                lastAppliedServerSequence = 0,
                highWatermarkHint = null,
                integrityErrorCode = null,
                updatedAtUtc = updatedAtUtc,
            ),
        )
    }

    private fun openStore() {
        sqlCipherKey = keyManager().openSqlCipherKey()
        openRoomWithCurrentKey()
    }

    private fun openRoomWithCurrentKey() {
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = SqlCipherOpenHelperFactoryProvider.create(sqlCipherKey),
            databaseName = databaseName,
        )
        repository = RoomNotesRepository(
            database = requireDatabase(),
            collectorVersion = "m1-instrumented-test",
        )
    }

    private fun createVersionOneOpenHelper(): SupportSQLiteOpenHelper {
        return createLegacyOpenHelper(
            version = 1,
            schemaAsset = VERSION_ONE_SCHEMA_ASSET,
        )
    }

    private fun createVersionTwoOpenHelper(): SupportSQLiteOpenHelper =
        createLegacyOpenHelper(
            version = 2,
            schemaAsset = VERSION_TWO_SCHEMA_ASSET,
        )

    private fun createEmptyVersionTwoOpenHelper(): SupportSQLiteOpenHelper =
        createLegacyOpenHelper(
            version = 2,
            schemaAsset = VERSION_TWO_SCHEMA_ASSET,
            seedFixture = false,
        )

    private fun createLegacyOpenHelper(
        version: Int,
        schemaAsset: String,
        seedFixture: Boolean = true,
    ): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createLegacySchema(db, schemaAsset)
                if (seedFixture) {
                    seedVersionOneFixture(db)
                }
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                throw AssertionError("Unexpected legacy helper upgrade $oldVersion->$newVersion")
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
        return SqlCipherOpenHelperFactoryProvider
            .create(sqlCipherKey)
            .create(configuration)
    }

    private fun createLegacySchema(
        db: SupportSQLiteDatabase,
        schemaAsset: String,
    ) {
        val schemaDocument = testAssets
            .open(schemaAsset)
            .bufferedReader()
            .use { it.readText() }
        val entities = JSONObject(schemaDocument)
            .getJSONObject("database")
            .getJSONArray("entities")

        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            db.execSQL(expandTableName(entity.getString("createSql"), tableName))

            val indices = entity.getJSONArray("indices")
            for (indexIndex in 0 until indices.length()) {
                db.execSQL(
                    expandTableName(
                        indices.getJSONObject(indexIndex).getString("createSql"),
                        tableName,
                    ),
                )
            }
        }
    }

    private fun seedVersionOneFixture(db: SupportSQLiteDatabase) {
        val createdAt = "2026-01-15T03:00:00Z"
        db.execSQL(
            """
            INSERT INTO local_installation(
                installation_id, created_at_utc, server_device_id
            ) VALUES(?, ?, NULL)
            """.trimIndent(),
            arrayOf(MIGRATION_INSTALLATION_ID.toString(), createdAt),
        )
        db.execSQL(
            """
            INSERT INTO local_owner(
                local_owner_id, installation_id, created_at_utc, server_person_id
            ) VALUES(?, ?, ?, NULL)
            """.trimIndent(),
            arrayOf(
                MIGRATION_OWNER_ID.toString(),
                MIGRATION_INSTALLATION_ID.toString(),
                createdAt,
            ),
        )

        insertLegacyCapture(
            db = db,
            captureId = MIGRATION_CAPTURE_ONE,
            operationId = MIGRATION_OPERATION_ONE,
            minuteOffset = 0,
            text = "Synthetic legacy note",
        )
        insertLegacyCapture(
            db = db,
            captureId = MIGRATION_CAPTURE_TWO,
            operationId = MIGRATION_OPERATION_TWO,
            minuteOffset = 1,
            text = "Synthetic legacy corrected note",
        )
        db.execSQL(
            """
            INSERT INTO local_life_event(
                event_id, local_owner_id, kind, created_at_utc
            ) VALUES(?, ?, 'note', ?)
            """.trimIndent(),
            arrayOf(
                MIGRATION_EVENT_ID.toString(),
                MIGRATION_OWNER_ID.toString(),
                createdAt,
            ),
        )
        insertLegacyRevision(
            db = db,
            revisionId = MIGRATION_REVISION_ONE,
            captureId = MIGRATION_CAPTURE_ONE,
            operationId = MIGRATION_OPERATION_ONE,
            revisionNo = 1,
            minuteOffset = 0,
            text = "Synthetic legacy note",
        )
        insertLegacyRevision(
            db = db,
            revisionId = MIGRATION_REVISION_TWO,
            captureId = MIGRATION_CAPTURE_TWO,
            operationId = MIGRATION_OPERATION_TWO,
            revisionNo = 2,
            minuteOffset = 1,
            text = "Synthetic legacy corrected note",
        )
        db.execSQL(
            """
            INSERT INTO local_revision_parent(
                event_id, child_revision_id, parent_revision_id, relation
            ) VALUES(?, ?, ?, 'supersedes')
            """.trimIndent(),
            arrayOf(
                MIGRATION_EVENT_ID.toString(),
                MIGRATION_REVISION_TWO.toString(),
                MIGRATION_REVISION_ONE.toString(),
            ),
        )
        insertLegacyOutbox(
            db = db,
            localSequence = 1,
            operationId = MIGRATION_OPERATION_ONE,
            captureId = MIGRATION_CAPTURE_ONE,
            revisionId = MIGRATION_REVISION_ONE,
            baseRevisionId = null,
            minuteOffset = 0,
            operationHash = LEGACY_OPERATION_HASH_ONE,
        )
        insertLegacyOutbox(
            db = db,
            localSequence = 2,
            operationId = MIGRATION_OPERATION_TWO,
            captureId = MIGRATION_CAPTURE_TWO,
            revisionId = MIGRATION_REVISION_TWO,
            baseRevisionId = MIGRATION_REVISION_ONE,
            minuteOffset = 1,
            operationHash = LEGACY_OPERATION_HASH_TWO,
        )
        db.execSQL(
            """
            INSERT INTO local_event_head(
                event_id, current_revision_id, server_current_revision_id, updated_at_utc
            ) VALUES(?, ?, NULL, ?)
            """.trimIndent(),
            arrayOf(
                MIGRATION_EVENT_ID.toString(),
                MIGRATION_REVISION_TWO.toString(),
                "2026-01-15T03:01:00Z",
            ),
        )
    }

    private fun insertLegacyCapture(
        db: SupportSQLiteDatabase,
        captureId: UUID,
        operationId: UUID,
        minuteOffset: Long,
        text: String,
    ) {
        val recorded = recordedAt(minuteOffset)
        val content = """{"text":"$text"}""".toByteArray(StandardCharsets.UTF_8)
        db.execSQL(
            """
            INSERT INTO local_capture(
                capture_id, operation_id, installation_id, local_owner_id,
                schema_version, persistence_state, source_channel,
                recorded_at_rfc3339, recorded_at_epoch_ms, timezone_id,
                utc_offset_minutes, origin_user_entered, collector_name,
                collector_version, content_jcs, content_sha256, byte_size
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                captureId.toString(),
                operationId.toString(),
                MIGRATION_INSTALLATION_ID.toString(),
                MIGRATION_OWNER_ID.toString(),
                "life_capture.v1",
                "local_pending",
                "android_manual",
                recorded.toString(),
                recorded.toInstant().toEpochMilli(),
                TEST_ZONE.id,
                recorded.offset.totalSeconds / 60,
                1,
                "life-agent-android",
                "legacy-migration-fixture",
                content,
                LEGACY_CONTENT_HASH,
                content.size,
            ),
        )
    }

    private fun insertLegacyRevision(
        db: SupportSQLiteDatabase,
        revisionId: UUID,
        captureId: UUID,
        operationId: UUID,
        revisionNo: Int,
        minuteOffset: Long,
        text: String,
    ) {
        val recorded = recordedAt(minuteOffset)
        val effective = effectiveTime(minuteOffset)
        db.execSQL(
            """
            INSERT INTO local_event_revision(
                revision_id, event_id, capture_id, operation_id, revision_no,
                schema_version, assertion_status, record_status,
                verification_status, source_channel, recorded_at_rfc3339,
                origin_user_entered, collector_name, collector_version,
                effective_start_utc, effective_start_epoch_ms,
                original_local_start, timezone_id, start_offset_seconds,
                temporal_precision, local_date, payload_jcs, evidence_jcs,
                quality_flags_jcs, created_at_rfc3339, content_sha256, actor
            ) VALUES(
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            arrayOf(
                revisionId.toString(),
                MIGRATION_EVENT_ID.toString(),
                captureId.toString(),
                operationId.toString(),
                revisionNo,
                "life_event.note.v1",
                "observed",
                "active",
                "user_confirmed",
                "android_manual",
                recorded.toString(),
                1,
                "life-agent-android",
                "legacy-migration-fixture",
                effective.effectiveAt.toString(),
                effective.effectiveAt.toEpochMilli(),
                effective.originalLocal.toString(),
                effective.timezoneId.id,
                effective.offset.totalSeconds,
                effective.precision.storageValue,
                effective.localDate.toString(),
                """{"text":"$text"}""".toByteArray(StandardCharsets.UTF_8),
                "{}".toByteArray(StandardCharsets.UTF_8),
                "[]".toByteArray(StandardCharsets.UTF_8),
                recorded.toString(),
                LEGACY_CONTENT_HASH,
                "user",
            ),
        )
    }

    private fun insertLegacyOutbox(
        db: SupportSQLiteDatabase,
        localSequence: Long,
        operationId: UUID,
        captureId: UUID,
        revisionId: UUID,
        baseRevisionId: UUID?,
        minuteOffset: Long,
        operationHash: String,
    ) {
        val createdAt = recordedAt(minuteOffset)
        db.execSQL(
            """
            INSERT INTO sync_outbox(
                local_sequence, operation_id, capture_id, installation_id,
                local_owner_id, operation_kind, event_id, revision_id,
                base_revision_id, schema_version, operation_jcs,
                operation_content_sha256, created_at_utc, created_at_epoch_ms,
                state, attempt_count
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                localSequence,
                operationId.toString(),
                captureId.toString(),
                MIGRATION_INSTALLATION_ID.toString(),
                MIGRATION_OWNER_ID.toString(),
                "append_event_revision",
                MIGRATION_EVENT_ID.toString(),
                revisionId.toString(),
                baseRevisionId?.toString(),
                "life_event.note.v1",
                """{"operation":"synthetic-$localSequence"}"""
                    .toByteArray(StandardCharsets.UTF_8),
                operationHash,
                createdAt.toInstant().toString(),
                createdAt.toInstant().toEpochMilli(),
                "pending",
                0,
            ),
        )
    }

    private fun legacyOperationBytes(localSequence: Long): ByteArray =
        """{"operation":"synthetic-$localSequence"}"""
            .toByteArray(StandardCharsets.UTF_8)

    private fun expandTableName(sql: String, tableName: String): String =
        sql.replace("\${TABLE_NAME}", tableName)

    private fun queryInt(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Query returned no rows: $sql" }
            cursor.getInt(0)
        }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Query returned no rows: $sql" }
            cursor.getString(0)
        }

    private fun queryBlob(db: SupportSQLiteDatabase, sql: String): ByteArray =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Query returned no rows: $sql" }
            cursor.getBlob(0)
        }

    private fun assertAllProductionArtifactsExist() {
        productionDatabaseArtifacts(includeMissing = true).forEach { artifact ->
            assertTrue("Expected encrypted artifact ${artifact.name}", artifact.isFile)
        }
    }

    private fun assertProductionMarkersAreEncrypted() {
        val markerEncodings = listOf(
            PRODUCTION_NOTE_SENTINEL,
            PRODUCTION_OUTBOX_SENTINEL_ID.toString(),
        ).flatMap { marker ->
            listOf(
                marker.toByteArray(StandardCharsets.UTF_8),
                marker.toByteArray(StandardCharsets.UTF_16LE),
                marker.toByteArray(StandardCharsets.UTF_16BE),
            )
        }
        productionDatabaseArtifacts(includeMissing = false).forEach { artifact ->
            val contents = artifact.readBytes()
            markerEncodings.forEach { plaintext ->
                assertFalse(
                    "Production plaintext marker found in ${artifact.name}",
                    contents.containsSubsequence(plaintext),
                )
            }
        }
    }

    private fun assertDatabaseArtifactsDoNotContain(vararg plaintextMarkers: ByteArray) {
        productionDatabaseArtifacts(includeMissing = false).forEach { artifact ->
            val contents = artifact.readBytes()
            plaintextMarkers.forEach { plaintext ->
                assertFalse(
                    "Sensitive plaintext marker found in ${artifact.name}",
                    contents.containsSubsequence(plaintext),
                )
            }
        }
    }

    private fun productionDatabaseArtifacts(includeMissing: Boolean): List<File> {
        val databaseFile = context.getDatabasePath(databaseName)
        val artifacts = DATABASE_ARTIFACT_SUFFIXES.map { suffix ->
            File(databaseFile.path + suffix)
        }
        return if (includeMissing) artifacts else artifacts.filter(File::isFile)
    }

    private fun assertRuntimeStorageGuards(
        db: SupportSQLiteDatabase,
        installationId: String,
        localOwnerId: String,
    ) {
        assertEquals(
            4,
            queryInt(
                db,
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'trigger'
                  AND name IN (
                    'guard_sync_http_request_body_insert',
                    'guard_sync_http_request_body_update',
                    'guard_sync_auth_envelope_insert',
                    'guard_sync_auth_envelope_update'
                  )
                """.trimIndent(),
            ),
        )
        val requestId = UUID(0, 131).toString()
        val invalidRequestInsert = runCatching {
            db.execSQL(
                """
                INSERT INTO sync_http_request(
                    endpoint_id, request_identity, protocol_version,
                    credential_epoch_id, device_id, body_storage_kind,
                    raw_request_body, sealed_body_ciphertext, sealed_body_nonce,
                    sealed_body_key_alias, sealed_body_key_generation,
                    sealed_body_aad_version, request_body_octet_count,
                    raw_body_hmac, hmac_key_generation, access_generation_used,
                    state, attempt_budget,
                    deadline_at_epoch_ms, created_at_utc, updated_at_utc
                ) VALUES(
                    'auth_revoke', ?, '1.0.0', ?, ?, 'keystore_aead',
                    NULL, X'01', NULL, 'synthetic_key', 1, 1, 2,
                    zeroblob(32), 1, 1, 'ready', 8, 10000, ?, ?
                )
                """.trimIndent(),
                arrayOf(
                    requestId,
                    UUID(0, 132).toString(),
                    UUID(0, 133).toString(),
                    Instant.EPOCH.toString(),
                    Instant.EPOCH.toString(),
                ),
            )
        }
        assertTrue("Partial sealed request INSERT must fail", invalidRequestInsert.isFailure)

        db.execSQL(
            """
            INSERT INTO sync_http_request(
                endpoint_id, request_identity, protocol_version,
                credential_epoch_id, device_id, body_storage_kind,
                raw_request_body, request_body_octet_count, raw_body_hmac,
                hmac_key_generation, access_generation_used, state, attempt_budget,
                deadline_at_epoch_ms, created_at_utc, updated_at_utc
            ) VALUES(
                'sync_pull', ?, '1.0.0', ?, ?, 'raw', X'7B7D', 2,
                zeroblob(32), 1, 1, 'ready', 8, 10000, ?, ?
            )
            """.trimIndent(),
            arrayOf(
                requestId,
                UUID(0, 132).toString(),
                UUID(0, 133).toString(),
                Instant.EPOCH.toString(),
                Instant.EPOCH.toString(),
            ),
        )
        val invalidRequestUpdate = runCatching {
            db.execSQL(
                """
                UPDATE sync_http_request
                SET endpoint_id = 'auth_revoke',
                    body_storage_kind = 'keystore_aead',
                    raw_request_body = NULL,
                    sealed_body_ciphertext = X'01',
                    sealed_body_nonce = NULL,
                    sealed_body_key_alias = 'synthetic_key',
                    sealed_body_key_generation = 1,
                    sealed_body_aad_version = 1
                WHERE endpoint_id = 'sync_pull' AND request_identity = ?
                """.trimIndent(),
                arrayOf(requestId),
            )
        }
        assertTrue("Partial sealed request UPDATE must fail", invalidRequestUpdate.isFailure)

        val authInsertSql = """
            INSERT INTO sync_auth_state(
                singleton_id, credential_epoch_id, installation_id,
                local_owner_id, device_id, person_id, token_type,
                refresh_token_ciphertext, refresh_token_nonce,
                refresh_token_key_alias, refresh_token_key_generation,
                refresh_token_aad_version, access_expires_at_utc,
                access_expires_at_epoch_ms, refresh_expires_at_utc,
                refresh_expires_at_epoch_ms, family_expires_at_utc,
                family_expires_at_epoch_ms, generation, state,
                bootstrap_required, installed_at_utc, updated_at_utc
            ) VALUES(
                1, ?, ?, ?, ?, ?, 'Bearer', X'01', %s,
                'synthetic_refresh_key', 1, 1, ?, 5000, ?, 10000, ?,
                20000, 1, 'active', 1, ?, ?
            )
        """.trimIndent()
        val authArgs = arrayOf(
            UUID(0, 134).toString(),
            installationId,
            localOwnerId,
            UUID(0, 135).toString(),
            UUID(0, 136).toString(),
            Instant.ofEpochMilli(5_000).toString(),
            Instant.ofEpochMilli(10_000).toString(),
            Instant.ofEpochMilli(20_000).toString(),
            Instant.EPOCH.toString(),
            Instant.EPOCH.toString(),
        )
        assertTrue(
            "Partial refresh envelope INSERT must fail",
            runCatching {
                db.execSQL(authInsertSql.format("NULL"), authArgs)
            }.isFailure,
        )
        db.execSQL(authInsertSql.format("X'0102030405060708090A0B0C'"), authArgs)
        db.execSQL(
            "UPDATE sync_auth_state SET state = 'revoke_pending' WHERE singleton_id = 1",
        )
        db.execSQL(
            "UPDATE sync_auth_state SET state = 'active' WHERE singleton_id = 1",
        )
        assertTrue(
            "Revoked auth must not retain a refresh envelope",
            runCatching {
                db.execSQL(
                    "UPDATE sync_auth_state SET state = 'revoked' WHERE singleton_id = 1",
                )
            }.isFailure,
        )
        assertTrue(
            "Unknown auth states must fail the runtime guard",
            runCatching {
                db.execSQL(
                    "UPDATE sync_auth_state SET state = 'synthetic_unknown' WHERE singleton_id = 1",
                )
            }.isFailure,
        )
        assertTrue(
            "Partial refresh envelope UPDATE must fail",
            runCatching {
                db.execSQL(
                    "UPDATE sync_auth_state SET refresh_token_nonce = NULL WHERE singleton_id = 1",
                )
            }.isFailure,
        )
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private fun closeStore() {
        database?.close()
        database = null
        if (::sqlCipherKey.isInitialized) {
            sqlCipherKey.close()
        }
    }

    private fun keyManager() = DatabaseKeyManager(
        context = context,
        keyAlias = keyAlias,
        databaseName = databaseName,
        envelopeRelativePath = envelopeRelativePath,
    )

    private fun envelopeFile() = File(context.noBackupFilesDir, envelopeRelativePath)

    private fun requireDatabase(): LifeAgentDatabase =
        requireNotNull(database) { "Test database is closed" }

    private suspend fun assertCounts(
        captures: Int,
        revisions: Int,
        parents: Int,
        outbox: Int,
    ) {
        val counts = requireDatabase().noteMutationDao().tableCounts()
        assertEquals(captures, counts.captures)
        assertEquals(1, counts.events)
        assertEquals(revisions, counts.revisions)
        assertEquals(parents, counts.parents)
        assertEquals(1, counts.heads)
        assertEquals(outbox, counts.outboxOperations)
    }

    private suspend fun assertParent(
        childRevisionId: UUID,
        parentRevisionId: UUID,
    ) {
        val parent = requireDatabase()
            .noteQueryDao()
            .findParents(childRevisionId.toString())
            .single()
        assertEquals(EVENT_ID.toString(), parent.eventId)
        assertEquals(parentRevisionId.toString(), parent.parentRevisionId)
        assertEquals("supersedes", parent.relation)
    }

    private fun persisted(outcome: NoteMutationOutcome): NoteMutationReceipt =
        (outcome as? NoteMutationOutcome.Persisted)?.receipt
            ?: throw AssertionError("Expected a persisted note mutation, got $outcome")

    private fun mutationIds(
        operation: Long,
        capture: Long,
        event: UUID,
        revision: Long,
    ) = MutationIds(
        operationId = UUID(0, operation),
        captureId = UUID(0, capture),
        eventId = event,
        revisionId = UUID(0, revision),
    )

    private fun effectiveTime(minuteOffset: Long) =
        PointTimeResolver.resolveInstant(
            instant = BASE_INSTANT.plusSeconds(minuteOffset * 60),
            timezoneId = TEST_ZONE,
        )

    private fun recordedAt(minuteOffset: Long): OffsetDateTime =
        BASE_INSTANT
            .plusSeconds(minuteOffset * 60)
            .atZone(TEST_ZONE)
            .toOffsetDateTime()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CONCURRENT_RETRY_COUNT = 24
        const val VERSION_ONE_SCHEMA_ASSET =
            "ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase/1.json"
        const val VERSION_TWO_SCHEMA_ASSET =
            "ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase/2.json"
        const val PRODUCTION_NOTE_SENTINEL =
            "LIFE_AGENT_PRODUCTION_ROOM_OUTBOX_SENTINEL_9F24A8C1_ёж"
        const val REVOKE_REFRESH_TOKEN_SENTINEL =
            "LIFE_AGENT_M2_REFRESH_TOKEN_MUST_NEVER_REACH_ROOM_PLAINTEXT"
        val SQLITE_PLAINTEXT_HEADER: ByteArray =
            "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
        val DATABASE_ARTIFACT_SUFFIXES: List<String> = listOf("", "-wal", "-shm")
        val LEGACY_CONTENT_HASH: String = "a".repeat(64)
        val LEGACY_OPERATION_HASH_ONE: String = "b".repeat(64)
        val LEGACY_OPERATION_HASH_TWO: String = "c".repeat(64)
        val BASE_INSTANT: Instant = Instant.parse("2026-01-15T03:00:00Z")
        val TEST_ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")
        val EVENT_ID: UUID = UUID(0, 3)
        val CONCURRENT_EVENT_ID: UUID = UUID(0, 23)
        val ATOMIC_EVENT_ID: UUID = UUID(0, 33)
        val DURABLE_SYNC_EVENT_ID: UUID = UUID(0, 43)
        val MIGRATION_NEXT_EVENT_ID: UUID = UUID(0, 53)
        val RETRY_BATCH_EVENT_ID: UUID = UUID(0, 73)
        val AUTH_EXPIRY_EVENT_ID: UUID = UUID(0, 93)
        val RUNTIME_GUARD_EVENT_ID: UUID = UUID(0, 123)
        val EMPTY_MIGRATION_EVENT_ID: UUID = UUID(0, 143)
        val SYNTHETIC_INSTALLATION_ID: UUID = UUID(0, 9_999)
        val SYNTHETIC_LOCAL_OWNER_ID: UUID = UUID(0, 10_000)
        val SYNTHETIC_PERSON_ID: UUID = UUID(0, 10_001)
        val PRODUCTION_OUTBOX_SENTINEL_ID: UUID =
            UUID.fromString("9f24a8c1-91d5-4aeb-bc27-75f9e3390bd1")
        val PRODUCTION_CAPTURE_ID: UUID =
            UUID.fromString("0ea5bc77-b2d8-46ef-b247-5bf0dfa8553f")
        val PRODUCTION_SCAN_EVENT_ID: UUID =
            UUID.fromString("2779908e-dc69-4237-8ecf-5c16d57284c9")
        val PRODUCTION_REVISION_ID: UUID =
            UUID.fromString("f1c935ee-b624-476f-b7a8-3404096b9e68")
        val MIGRATION_INSTALLATION_ID: UUID = UUID(0, 101)
        val MIGRATION_OWNER_ID: UUID = UUID(0, 102)
        val MIGRATION_OPERATION_ONE: UUID = UUID(0, 103)
        val MIGRATION_CAPTURE_ONE: UUID = UUID(0, 104)
        val MIGRATION_EVENT_ID: UUID = UUID(0, 105)
        val MIGRATION_REVISION_ONE: UUID = UUID(0, 106)
        val MIGRATION_OPERATION_TWO: UUID = UUID(0, 107)
        val MIGRATION_CAPTURE_TWO: UUID = UUID(0, 108)
        val MIGRATION_REVISION_TWO: UUID = UUID(0, 109)
    }
}
