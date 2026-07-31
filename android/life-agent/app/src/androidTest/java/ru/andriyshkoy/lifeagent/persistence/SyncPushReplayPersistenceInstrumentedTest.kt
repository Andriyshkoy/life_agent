package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncAuthStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncHttpRequestEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncOutboxEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncPushBatchItemEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncServerChangeEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncStreamStateEntity
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(AndroidJUnit4::class)
class SyncPushReplayPersistenceInstrumentedTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var repository: RoomNotesRepository
    private var database: LifeAgentDatabase? = null

    @Before
    fun setUp() {
        databaseName = "sync-push-replay-${UUID.randomUUID()}.db"
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
            databaseName = databaseName,
        )
        repository = RoomNotesRepository(
            database = requireDatabase(),
            collectorVersion = "push-replay-instrumented-test",
        )
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(databaseName)
    }

    @Test
    fun exactAckReplayVerifiesEveryProjectionAndDriftHaltsWithoutRewrite() =
        runBlocking {
            seedStream()
            val outbox = createPendingOperation(seed = 10)
            val batch = seedClaimedBatch(
                batchId = uuid(100),
                attemptId = uuid(101),
                operations = listOf(outbox),
                responseBody = """{"kind":"ack"}""",
                terminalAtUtc = "2030-01-01T00:00:01Z",
            )
            val change = SyncServerChangeEntity(
                serverSequence = 1,
                operationId = outbox.operationId,
                operationContentSha256 =
                    requireNotNull(outbox.wireOperationContentSha256),
                resultCode = "applied",
                captureId = outbox.captureId,
                eventId = outbox.eventId,
                revisionId = outbox.revisionId,
                currentRevisionId = outbox.revisionId,
                committedAtUtc = batch.response.terminalAtUtc,
                firstEndpointId = "sync_push",
                firstRequestIdentity = batch.batchId,
                verifiedAtUtc = batch.response.terminalAtUtc,
            )
            val exact = PushAckPersistence(ordinal = 0, change = change)
            val store = SyncPersistenceStore(requireDatabase())

            store.commitPushResponse(batch.response, listOf(exact))
            store.commitPushResponse(batch.response, listOf(exact))
            val laterObservationTime = "2030-01-01T00:00:09Z"
            store.commitPushResponse(
                response = batch.response.copy(terminalAtUtc = laterObservationTime),
                results = listOf(
                    exact.copy(
                        change = change.copy(verifiedAtUtc = laterObservationTime),
                    ),
                ),
            )

            val retainedReceipt = requireNotNull(
                requireDatabase().syncReplicaDao().findServerChange(outbox.operationId),
            )
            assertEquals(change, retainedReceipt)
            assertEquals(
                batch.response.terminalAtUtc,
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_push", batch.batchId)
                    ?.terminalAtUtc,
            )

            val driftedResults = listOf(
                PushAckPersistence(
                    ordinal = 0,
                    change = change.copy(operationContentSha256 = "0".repeat(64)),
                ),
                PushAckPersistence(
                    ordinal = 0,
                    change = change.copy(currentRevisionId = uuid(199)),
                ),
                PushAckPersistence(
                    ordinal = 0,
                    change = change.copy(serverSequence = 2),
                ),
                PushAckPersistence(
                    ordinal = 0,
                    change = change.copy(resultCode = "conflict"),
                ),
                PushAckPersistence(
                    ordinal = 0,
                    change = change,
                    detailsJcs = """[{"code":"drift"}]"""
                        .toByteArray(StandardCharsets.UTF_8),
                ),
                PushErrorPersistence(
                    ordinal = 0,
                    operationId = outbox.operationId,
                    operationContentSha256 =
                        requireNotNull(outbox.wireOperationContentSha256),
                    errorCode = "invalid_parent",
                    retryable = false,
                    detailsJcs = EMPTY_FIELD_ERRORS,
                ),
            )
            driftedResults.forEach { drifted ->
                assertReplicaFailure {
                    store.commitPushResponse(batch.response, listOf(drifted))
                }
                assertEquals(
                    retainedReceipt,
                    requireDatabase()
                        .syncReplicaDao()
                        .findServerChange(outbox.operationId),
                )
            }

            val stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("integrity_halted", stream.phase)
            assertNotNull(stream.integrityErrorCode)
            val retainedOutbox = requireNotNull(
                requireDatabase().noteMutationDao().findOutbox(outbox.operationId),
            )
            assertEquals("acked", retainedOutbox.state)
            assertEquals(1L, retainedOutbox.serverSequence)
            assertEquals(batch.batchId, retainedOutbox.lastResultBatchId)

            // A genuinely identical replay remains a verified no-op even after
            // the stream has been halted by a later divergent interpretation.
            store.commitPushResponse(batch.response, listOf(exact))
        }

    @Test
    fun crossBatchAckReplayPreservesFirstReceiptProvenance() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 40)
        val firstBatch = seedClaimedBatch(
            batchId = uuid(300),
            attemptId = uuid(301),
            operations = listOf(outbox),
            responseBody = """{"kind":"ack-first"}""",
            terminalAtUtc = "2030-01-01T00:03:01Z",
        )
        val firstAck = ack(
            operation = outbox,
            batch = firstBatch,
            committedAtUtc = firstBatch.response.terminalAtUtc,
        )
        val store = SyncPersistenceStore(requireDatabase())
        store.commitPushResponse(firstBatch.response, listOf(firstAck))

        val replayBatch = seedUnclaimedBatch(
            batchId = uuid(302),
            attemptId = uuid(303),
            operations = listOf(outbox),
            responseBody = """{"kind":"ack-cross-batch-replay"}""",
            terminalAtUtc = "2030-01-01T00:04:01Z",
        )
        val replayedAck = firstAck.copy(
            change = firstAck.change.copy(
                firstRequestIdentity = replayBatch.batchId,
                verifiedAtUtc = replayBatch.response.terminalAtUtc,
            ),
        )
        store.commitPushResponse(replayBatch.response, listOf(replayedAck))
        store.commitPushResponse(replayBatch.response, listOf(replayedAck))

        val receipt = requireNotNull(
            requireDatabase().syncReplicaDao().findServerChange(outbox.operationId),
        )
        assertEquals(firstBatch.batchId, receipt.firstRequestIdentity)
        assertEquals(firstBatch.response.terminalAtUtc, receipt.verifiedAtUtc)
        val retainedOutbox = requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(outbox.operationId),
        )
        assertEquals("acked", retainedOutbox.state)
        assertEquals(firstBatch.batchId, retainedOutbox.lastResultBatchId)
    }

    @Test
    fun rootMissingParentHaltsStreamAndRollsBackTerminalReduction() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 50)
        val missingBatch = seedClaimedBatch(
            batchId = uuid(400),
            attemptId = uuid(401),
            operations = listOf(outbox),
            responseBody = """{"kind":"missing-parent"}""",
            terminalAtUtc = "2030-01-01T00:05:01Z",
        )
        val missingParent = PushErrorPersistence(
            ordinal = 0,
            operationId = outbox.operationId,
            operationContentSha256 =
                requireNotNull(outbox.wireOperationContentSha256),
            errorCode = "missing_parent",
            retryable = true,
            detailsJcs = EMPTY_FIELD_ERRORS,
        )
        val store = SyncPersistenceStore(requireDatabase())
        assertReplicaFailure {
            store.commitPushResponse(missingBatch.response, listOf(missingParent))
        }
        val retained = requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(outbox.operationId),
        )
        assertEquals("batched", retained.state)
        assertEquals(missingBatch.batchId, retained.activeBatchId)
        assertNull(retained.lastResultBatchId)
        val request = requireNotNull(
            requireDatabase().syncTransportDao().findRequest(
                "sync_push",
                missingBatch.batchId,
            ),
        )
        assertEquals("sending", request.state)
        assertEquals(missingBatch.response.expectedAttemptId, request.activeAttemptId)
        assertNull(request.exactResponseBody)
        assertNull(request.terminalErrorCode)
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("missing_parent_root_invalid", stream.integrityErrorCode)
    }

    @Test
    fun staleTakenOverAttemptCannotInstallOrReduceItsResponse() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 60)
        val staleBatch = seedClaimedBatch(
            batchId = uuid(500),
            attemptId = uuid(501),
            operations = listOf(outbox),
            responseBody = """{"kind":"late-attempt"}""",
            terminalAtUtc = "2030-01-01T00:07:01Z",
        )
        val winningAttemptId = uuid(502)
        assertEquals(
            1,
            requireDatabase().syncTransportDao().claimAttempt(
                endpointId = "sync_push",
                requestIdentity = staleBatch.batchId,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                accessGenerationUsed = 1,
                attemptId = winningAttemptId,
                attemptedAtEpochMs = 3,
                leaseExpiresAtEpochMs = 4,
                updatedAtUtc = "2030-01-01T00:07:02Z",
            ),
        )

        val store = SyncPersistenceStore(requireDatabase())
        store.commitPushResponse(
            response = staleBatch.response,
            results = emptyList(),
        )
        val afterLateAttempt = requireNotNull(
            requireDatabase().syncTransportDao().findRequest(
                "sync_push",
                staleBatch.batchId,
            ),
        )
        assertEquals("sending", afterLateAttempt.state)
        assertEquals(winningAttemptId, afterLateAttempt.activeAttemptId)
        assertNull(afterLateAttempt.exactResponseBody)
        assertEquals(
            "batched",
            requireDatabase()
                .noteMutationDao()
                .findOutbox(outbox.operationId)
                ?.state,
        )
        assertNull(
            requireDatabase().syncReplicaDao().findStreamState()?.integrityErrorCode,
        )

        val winningBytes = """{"kind":"winning-attempt"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val winningResponse = staleBatch.response.copy(
            expectedAttemptId = winningAttemptId,
            exactResponseBody = winningBytes,
            responseSha256 = sha256(winningBytes),
            terminalAtUtc = "2030-01-01T00:07:03Z",
        )
        val winningAck = ack(
            operation = outbox,
            batch = staleBatch,
            committedAtUtc = winningResponse.terminalAtUtc,
        )
        store.commitPushResponse(winningResponse, listOf(winningAck))
        assertEquals(
            "acked",
            requireDatabase()
                .noteMutationDao()
                .findOutbox(outbox.operationId)
                ?.state,
        )

        assertReplicaFailure {
            store.commitPushResponse(staleBatch.response, emptyList())
        }
        assertEquals(
            winningAck.change,
            requireDatabase().syncReplicaDao().findServerChange(outbox.operationId),
        )
        assertEquals(
            "integrity_halted",
            requireDatabase().syncReplicaDao().findStreamState()?.phase,
        )
    }

    @Test
    fun misboundBatchIdentityRollsBackAndHaltsOnlyCurrentStream() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 80)
        val misbound = batch(
            batchId = uuid(700),
            attemptId = uuid(701),
            operations = listOf(outbox),
            responseBody = """{"kind":"misbound-batch"}""",
            terminalAtUtc = "2030-01-01T00:09:01Z",
        )
        val foreign = batch(
            batchId = uuid(702),
            attemptId = uuid(703),
            operations = listOf(outbox),
            responseBody = """{"kind":"foreign-request"}""",
            terminalAtUtc = "2030-01-01T00:09:02Z",
        )
        val transport = requireDatabase().syncTransportDao()
        transport.insertRequest(misbound.request)
        transport.insertRequest(foreign.request)
        transport.insertBatch(
            misbound.batch.copy(requestIdentity = foreign.batchId),
        )
        transport.insertBatchItems(misbound.items)

        assertReplicaFailure {
            SyncPersistenceStore(requireDatabase()).commitPushResponse(
                response = misbound.response,
                results = emptyList(),
            )
        }

        val retainedRequest = requireNotNull(
            transport.findRequest("sync_push", misbound.batchId),
        )
        assertEquals("sending", retainedRequest.state)
        assertNull(retainedRequest.exactResponseBody)
        assertEquals(
            listOf(outbox.operationId),
            transport.findBatchItems(misbound.batchId).map { it.operationId },
        )
        val retainedOutbox = requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(outbox.operationId),
        )
        assertEquals("pending", retainedOutbox.state)
        assertNull(retainedOutbox.activeBatchId)
        val retainedHead = requireNotNull(
            requireDatabase().noteMutationDao().findEventPointer(outbox.eventId),
        )
        assertEquals(outbox.revisionId, retainedHead.currentRevisionId)
        assertNull(retainedHead.serverCurrentRevisionId)
        assertNull(retainedHead.serverObservedSequence)
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals(DEVICE_ID, stream.deviceId)
        assertEquals("integrity_halted", stream.phase)
        assertEquals("push_response_drift", stream.integrityErrorCode)
    }

    @Test
    fun oldCredentialPushAckAndErrorCannotTouchReplacementStream() = runBlocking {
        seedStream()
        val ackOutbox = createPendingOperation(seed = 110)
        val errorOutbox = createPendingOperation(seed = 120)
        val ackBatch = seedClaimedBatch(
            batchId = uuid(1_000),
            attemptId = uuid(1_001),
            operations = listOf(ackOutbox),
            responseBody = """{"kind":"late-old-credential-ack"}""",
            terminalAtUtc = "2030-01-01T00:12:01Z",
        )
        val errorBatch = seedClaimedBatch(
            batchId = uuid(1_002),
            attemptId = uuid(1_003),
            operations = listOf(errorOutbox),
            responseBody = """{"kind":"late-old-credential-error"}""",
            terminalAtUtc = "2030-01-01T00:12:02Z",
        )
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET credential_epoch_id = '$REPLACEMENT_CREDENTIAL_EPOCH_ID',
                device_id = '$REPLACEMENT_DEVICE_ID',
                updated_at_utc = '2030-01-01T00:12:00Z'
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val store = SyncPersistenceStore(requireDatabase())
        listOf<suspend () -> Unit>(
            {
                store.commitPushResponse(
                    response = ackBatch.response,
                    results = listOf(
                        ack(
                            operation = ackOutbox,
                            batch = ackBatch,
                            committedAtUtc = ackBatch.response.terminalAtUtc,
                        ),
                    ),
                )
            },
            {
                store.commitPushResponse(
                    response = errorBatch.response,
                    results = listOf(
                        PushErrorPersistence(
                            ordinal = 0,
                            operationId = null,
                            operationContentSha256 = null,
                            errorCode = "schema_invalid",
                            retryable = false,
                            detailsJcs = EMPTY_FIELD_ERRORS,
                        ),
                    ),
                )
            },
        ).forEach { callback ->
            val failure = runCatching { callback() }.exceptionOrNull()
            assertTrue(failure is ReplicaIntegrityException)
            assertEquals(
                "sync_request_binding_drift",
                (failure as ReplicaIntegrityException).errorCode,
            )
        }

        listOf(ackBatch, errorBatch).forEach { batch ->
            val request = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_push", batch.batchId),
            )
            assertEquals("sending", request.state)
            assertNull(request.exactResponseBody)
        }
        listOf(ackOutbox, errorOutbox).forEach { original ->
            val retained = requireNotNull(
                requireDatabase()
                    .noteMutationDao()
                    .findOutbox(original.operationId),
            )
            assertEquals("batched", retained.state)
            assertEquals(original.revisionId, retained.revisionId)
            assertNull(retained.lastResultCode)
            assertNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(original.operationId),
            )
            assertNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findRevision(original.revisionId)
                    ?.serverSequence,
            )
        }
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals(REPLACEMENT_CREDENTIAL_EPOCH_ID, stream.credentialEpochId)
        assertEquals(REPLACEMENT_DEVICE_ID, stream.deviceId)
        assertEquals("incremental", stream.phase)
        assertNull(stream.integrityErrorCode)
    }

    @Test
    fun newPushResponseCannotReduceAfterIntegrityHalt() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 130)
        val batch = seedClaimedBatch(
            batchId = uuid(1_100),
            attemptId = uuid(1_101),
            operations = listOf(outbox),
            responseBody = """{"kind":"push-after-halt"}""",
            terminalAtUtc = "2030-01-01T00:13:01Z",
        )
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET phase = 'integrity_halted',
                integrity_error_code = 'prior_integrity_failure',
                updated_at_utc = '2030-01-01T00:13:00Z'
            WHERE singleton_id = 1
            """.trimIndent(),
        )
        val failure = runCatching {
            SyncPersistenceStore(requireDatabase()).commitPushResponse(
                response = batch.response,
                results = listOf(
                    ack(
                        operation = outbox,
                        batch = batch,
                        committedAtUtc = batch.response.terminalAtUtc,
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is ReplicaIntegrityException)
        assertEquals(
            "sync_integrity_already_halted",
            (failure as ReplicaIntegrityException).errorCode,
        )

        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_push", batch.batchId),
        )
        assertEquals("sending", request.state)
        assertNull(request.exactResponseBody)
        val retained = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findOutbox(outbox.operationId),
        )
        assertEquals("batched", retained.state)
        assertNull(retained.lastResultCode)
        assertNull(
            requireDatabase()
                .syncReplicaDao()
                .findServerChange(outbox.operationId),
        )
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("prior_integrity_failure", stream.integrityErrorCode)
    }

    @Test
    fun integrityHaltAtomicallyBlocksEveryClaimablePushState() = runBlocking {
        seedStream()
        val claimableStates = listOf("ready", "retry_wait", "sending")
        val batches = claimableStates.mapIndexed { index, state ->
            val outbox = createPendingOperation(seed = 140L + index * 10L)
            val batch = seedClaimedBatch(
                batchId = uuid(1_200 + index * 10L),
                attemptId = uuid(1_201 + index * 10L),
                operations = listOf(outbox),
                responseBody = """{"kind":"claim-after-halt-$state"}""",
                terminalAtUtc = "2030-01-01T00:14:0${index + 1}Z",
            )
            when (state) {
                "ready" -> requireDatabase().openHelper.writableDatabase.execSQL(
                    """
                    UPDATE sync_http_request
                    SET state = 'ready',
                        attempt_count = 0,
                        next_attempt_at_epoch_ms = NULL,
                        last_attempt_at_epoch_ms = NULL,
                        lease_expires_at_epoch_ms = NULL,
                        active_attempt_id = NULL,
                        access_generation_used = NULL
                    WHERE endpoint_id = 'sync_push'
                      AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(batch.batchId),
                )

                "retry_wait" -> requireDatabase().openHelper.writableDatabase.execSQL(
                    """
                    UPDATE sync_http_request
                    SET state = 'retry_wait',
                        next_attempt_at_epoch_ms = 2,
                        lease_expires_at_epoch_ms = NULL,
                        active_attempt_id = NULL
                    WHERE endpoint_id = 'sync_push'
                      AND request_identity = ?
                    """.trimIndent(),
                    arrayOf(batch.batchId),
                )
            }
            batch
        }
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET phase = 'integrity_halted',
                integrity_error_code = 'prior_integrity_failure',
                updated_at_utc = '2030-01-01T00:14:10Z'
            WHERE singleton_id = 1
            """.trimIndent(),
        )

        batches.forEachIndexed { index, batch ->
            val before = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_push", batch.batchId),
            )
            assertEquals(
                0,
                requireDatabase().syncTransportDao().claimAttempt(
                    endpointId = "sync_push",
                    requestIdentity = batch.batchId,
                    credentialEpochId = CREDENTIAL_EPOCH_ID,
                    accessGenerationUsed = 2,
                    attemptId = uuid(1_300 + index.toLong()),
                    attemptedAtEpochMs = 3,
                    leaseExpiresAtEpochMs = 4,
                    updatedAtUtc = "2030-01-01T00:14:20Z",
                ),
            )
            val after = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_push", batch.batchId),
            )
            assertEquals(before.state, after.state)
            assertEquals(before.attemptCount, after.attemptCount)
            assertEquals(before.lastAttemptAtEpochMs, after.lastAttemptAtEpochMs)
            assertEquals(before.leaseExpiresAtEpochMs, after.leaseExpiresAtEpochMs)
            assertEquals(before.activeAttemptId, after.activeAttemptId)
            assertEquals(before.accessGenerationUsed, after.accessGenerationUsed)
            assertEquals(before.updatedAtUtc, after.updatedAtUtc)
        }
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("prior_integrity_failure", stream.integrityErrorCode)

        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_stream_state
            SET credential_epoch_id = ?,
                device_id = ?,
                phase = 'incremental',
                integrity_error_code = NULL,
                updated_at_utc = '2030-01-01T00:14:30Z'
            WHERE singleton_id = 1
            """.trimIndent(),
            arrayOf(REPLACEMENT_CREDENTIAL_EPOCH_ID, REPLACEMENT_DEVICE_ID),
        )
        val oldReadyBatch = batches.first()
        val beforeReplacementClaim = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_push", oldReadyBatch.batchId),
        )
        assertEquals(
            0,
            requireDatabase().syncTransportDao().claimAttempt(
                endpointId = "sync_push",
                requestIdentity = oldReadyBatch.batchId,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                accessGenerationUsed = 2,
                attemptId = uuid(1_400),
                attemptedAtEpochMs = 3,
                leaseExpiresAtEpochMs = 4,
                updatedAtUtc = "2030-01-01T00:14:40Z",
            ),
        )
        val afterReplacementClaim = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_push", oldReadyBatch.batchId),
        )
        assertEquals(beforeReplacementClaim.state, afterReplacementClaim.state)
        assertEquals(
            beforeReplacementClaim.attemptCount,
            afterReplacementClaim.attemptCount,
        )
        assertEquals(
            beforeReplacementClaim.activeAttemptId,
            afterReplacementClaim.activeAttemptId,
        )
    }

    @Test
    fun misprojectedBatchItemDigestRollsBackEarlyErrorAndHalts() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 90)
        val batch = seedClaimedBatch(
            batchId = uuid(800),
            attemptId = uuid(801),
            operations = listOf(outbox),
            responseBody = """{"kind":"early-error-misprojected-item"}""",
            terminalAtUtc = "2030-01-01T00:10:01Z",
        )
        requireDatabase().openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_push_batch_item
            SET wire_operation_content_sha256 = '${"f".repeat(64)}'
            WHERE batch_id = '${batch.batchId}' AND ordinal = 0
            """.trimIndent(),
        )
        val failure = runCatching {
            SyncPersistenceStore(requireDatabase()).commitPushResponse(
                response = batch.response,
                results = listOf(
                    PushErrorPersistence(
                        ordinal = 0,
                        operationId = null,
                        operationContentSha256 = null,
                        errorCode = "schema_invalid",
                        retryable = false,
                        detailsJcs = EMPTY_FIELD_ERRORS,
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is ReplicaIntegrityException)
        assertEquals(
            "push_batch_membership_drift",
            (failure as ReplicaIntegrityException).errorCode,
        )

        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_push", batch.batchId),
        )
        assertEquals("sending", request.state)
        assertNull(request.exactResponseBody)
        val retainedOutbox = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findOutbox(outbox.operationId),
        )
        assertEquals("batched", retainedOutbox.state)
        assertEquals(batch.batchId, retainedOutbox.activeBatchId)
        assertNull(retainedOutbox.lastResultCode)
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("push_batch_membership_drift", stream.integrityErrorCode)
    }

    @Test
    fun malformedConflictAckRollsBackAndHalts() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 100)
        val batch = seedClaimedBatch(
            batchId = uuid(900),
            attemptId = uuid(901),
            operations = listOf(outbox),
            responseBody = """{"kind":"malformed-conflict"}""",
            terminalAtUtc = "2030-01-01T00:11:01Z",
        )
        val malformed = ack(
            operation = outbox,
            batch = batch,
            committedAtUtc = batch.response.terminalAtUtc,
        ).copy(
            change = ack(
                operation = outbox,
                batch = batch,
                committedAtUtc = batch.response.terminalAtUtc,
            ).change.copy(resultCode = "conflict"),
        )
        val failure = runCatching {
            SyncPersistenceStore(requireDatabase()).commitPushResponse(
                response = batch.response,
                results = listOf(malformed),
            )
        }.exceptionOrNull()
        assertTrue(failure is ReplicaIntegrityException)
        assertEquals(
            "push_ack_status_drift",
            (failure as ReplicaIntegrityException).errorCode,
        )

        val request = requireNotNull(
            requireDatabase()
                .syncTransportDao()
                .findRequest("sync_push", batch.batchId),
        )
        assertEquals("sending", request.state)
        assertNull(request.exactResponseBody)
        val retainedOutbox = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findOutbox(outbox.operationId),
        )
        assertEquals("batched", retainedOutbox.state)
        assertEquals(batch.batchId, retainedOutbox.activeBatchId)
        assertNull(retainedOutbox.lastResultCode)
        assertNull(
            requireDatabase()
                .syncReplicaDao()
                .findServerChange(outbox.operationId),
        )
        val revision = requireNotNull(
            requireDatabase()
                .syncReplicaDao()
                .findRevision(outbox.revisionId),
        )
        assertNull(revision.serverSequence)
        val stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("push_ack_status_drift", stream.integrityErrorCode)
    }

    @Test
    fun crossEventConflictHeadConstraintFailsClosedWithoutRetryLoop() =
        runBlocking {
            seedStream()
            val first = createPendingOperation(seed = 190)
            val firstBatch = seedClaimedBatch(
                batchId = uuid(1_500),
                attemptId = uuid(1_501),
                operations = listOf(first),
                responseBody = """{"kind":"first-event-applied"}""",
                terminalAtUtc = "2030-01-01T00:15:01Z",
            )
            SyncPersistenceStore(requireDatabase()).commitPushResponse(
                response = firstBatch.response,
                results = listOf(
                    ack(
                        operation = first,
                        batch = firstBatch,
                        committedAtUtc = firstBatch.response.terminalAtUtc,
                    ),
                ),
            )

            val second = createPendingOperation(seed = 200)
            val secondBatch = seedClaimedBatch(
                batchId = uuid(1_510),
                attemptId = uuid(1_511),
                operations = listOf(second),
                responseBody = """{"kind":"cross-event-conflict"}""",
                terminalAtUtc = "2030-01-01T00:15:11Z",
            )
            val conflict = ack(
                operation = second,
                batch = secondBatch,
                committedAtUtc = secondBatch.response.terminalAtUtc,
            ).copy(
                change = ack(
                    operation = second,
                    batch = secondBatch,
                    committedAtUtc = secondBatch.response.terminalAtUtc,
                ).change.copy(
                    serverSequence = 2,
                    resultCode = "conflict",
                    currentRevisionId = first.revisionId,
                ),
            )
            val failure = runCatching {
                SyncPersistenceStore(requireDatabase()).commitPushResponse(
                    response = secondBatch.response,
                    results = listOf(conflict),
                )
            }.exceptionOrNull()
            assertTrue(failure is ReplicaIntegrityException)
            assertEquals(
                "push_reduction_failed",
                (failure as ReplicaIntegrityException).errorCode,
            )

            val request = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_push", secondBatch.batchId),
            )
            assertEquals("sending", request.state)
            assertNull(request.exactResponseBody)
            val retainedOutbox = requireNotNull(
                requireDatabase()
                    .noteMutationDao()
                    .findOutbox(second.operationId),
            )
            assertEquals("batched", retainedOutbox.state)
            assertEquals(secondBatch.batchId, retainedOutbox.activeBatchId)
            assertNull(retainedOutbox.lastResultCode)
            assertNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(second.operationId),
            )
            assertNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findRevision(second.revisionId)
                    ?.serverSequence,
            )
            val secondHead = requireNotNull(
                requireDatabase().syncReplicaDao().findEventHead(second.eventId),
            )
            assertNull(secondHead.serverCurrentRevisionId)
            assertNull(secondHead.serverObservedSequence)
            val stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("integrity_halted", stream.phase)
            assertEquals("push_reduction_failed", stream.integrityErrorCode)
            assertEquals(
                0,
                requireDatabase().syncTransportDao().claimAttempt(
                    endpointId = "sync_push",
                    requestIdentity = secondBatch.batchId,
                    credentialEpochId = CREDENTIAL_EPOCH_ID,
                    accessGenerationUsed = 2,
                    attemptId = uuid(1_512),
                    attemptedAtEpochMs = 3,
                    leaseExpiresAtEpochMs = 4,
                    updatedAtUtc = "2030-01-01T00:15:12Z",
                ),
            )
        }

    @Test
    fun earlyErrorNullIdentityReplaysAndWrongPresentIdentityHalts() = runBlocking {
        seedStream()
        val outbox = createPendingOperation(seed = 70)
        val batch = seedClaimedBatch(
            batchId = uuid(600),
            attemptId = uuid(601),
            operations = listOf(outbox),
            responseBody = """{"kind":"schema-invalid"}""",
            terminalAtUtc = "2030-01-01T00:08:01Z",
        )
        val exact = PushErrorPersistence(
            ordinal = 0,
            operationId = null,
            operationContentSha256 = null,
            errorCode = "schema_invalid",
            retryable = false,
            detailsJcs = EMPTY_FIELD_ERRORS,
        )
        val store = SyncPersistenceStore(requireDatabase())
        store.commitPushResponse(batch.response, listOf(exact))
        var stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("integrity_halted", stream.phase)
        assertEquals("schema_invalid", stream.integrityErrorCode)
        assertEquals(batch.response.terminalAtUtc, stream.updatedAtUtc)
        store.commitPushResponse(batch.response, listOf(exact))
        stream = requireNotNull(
            requireDatabase().syncReplicaDao().findStreamState(),
        )
        assertEquals("schema_invalid", stream.integrityErrorCode)
        assertEquals(batch.response.terminalAtUtc, stream.updatedAtUtc)

        listOf(
            exact.copy(operationId = uuid(699)),
            exact.copy(operationContentSha256 = "a".repeat(64)),
        ).forEach { drifted ->
            assertReplicaFailure {
                store.commitPushResponse(batch.response, listOf(drifted))
            }
        }
        val retained = requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(outbox.operationId),
        )
        assertEquals("failed", retained.state)
        assertEquals("schema_invalid", retained.lastResultCode)
        assertEquals(batch.batchId, retained.lastResultBatchId)
        assertEquals(
            "integrity_halted",
            requireDatabase().syncReplicaDao().findStreamState()?.phase,
        )
    }

    @Test
    fun terminalItemErrorCommitsMixedBatchThenHaltsAndReplaysExactly() =
        runBlocking {
            seedStream()
            val acknowledged = createPendingOperation(seed = 20)
            val target = createPendingOperation(seed = 30)
            val originalBatch = seedClaimedBatch(
                batchId = uuid(200),
                attemptId = uuid(201),
                operations = listOf(acknowledged, target),
                responseBody = """{"kind":"mixed-ack-invalid-parent"}""",
                terminalAtUtc = "2030-01-01T00:01:01Z",
            )
            val acknowledgedResult = ack(
                operation = acknowledged,
                batch = originalBatch,
                committedAtUtc = originalBatch.response.terminalAtUtc,
            )
            val originalInvalidParent = invalidParent(
                ordinal = 1,
                operation = target,
            )
            val store = SyncPersistenceStore(requireDatabase())
            store.commitPushResponse(
                originalBatch.response,
                listOf(acknowledgedResult, originalInvalidParent),
            )
            store.commitPushResponse(
                originalBatch.response,
                listOf(acknowledgedResult, originalInvalidParent),
            )

            val retainedAck = requireNotNull(
                requireDatabase()
                    .syncReplicaDao()
                    .findServerChange(acknowledged.operationId),
            )
            assertEquals("applied", retainedAck.resultCode)
            val acknowledgedOutbox = requireNotNull(
                requireDatabase()
                    .noteMutationDao()
                    .findOutbox(acknowledged.operationId),
            )
            assertEquals("acked", acknowledgedOutbox.state)
            assertEquals(originalBatch.batchId, acknowledgedOutbox.lastResultBatchId)

            val retained = requireNotNull(
                requireDatabase().noteMutationDao().findOutbox(target.operationId),
            )
            assertEquals("failed", retained.state)
            assertEquals(originalBatch.batchId, retained.lastResultBatchId)
            assertEquals("invalid_parent", retained.lastResultCode)
            assertEquals(false, retained.lastResultRetryable)
            assertTrue(
                requireNotNull(retained.lastResultDetailsJcs)
                    .contentEquals(EMPTY_FIELD_ERRORS),
            )
            assertNull(
                requireDatabase().syncReplicaDao().findServerChange(target.operationId),
            )
            var stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("integrity_halted", stream.phase)
            assertEquals("invalid_parent", stream.integrityErrorCode)
            assertEquals(originalBatch.response.terminalAtUtc, stream.updatedAtUtc)

            val movedBatch = seedUnclaimedBatch(
                batchId = uuid(202),
                attemptId = uuid(203),
                operations = listOf(target),
                responseBody = """{"kind":"invalid-parent-moved"}""",
                terminalAtUtc = "2030-01-01T00:02:01Z",
            )
            val movedInvalidParent = invalidParent(
                ordinal = 0,
                operation = target,
            )
            val failure = runCatching {
                store.commitPushResponse(
                    movedBatch.response,
                    listOf(movedInvalidParent),
                )
            }.exceptionOrNull()
            assertTrue(failure is ReplicaIntegrityException)
            assertEquals(
                "sync_integrity_already_halted",
                (failure as ReplicaIntegrityException).errorCode,
            )
            val movedRequest = requireNotNull(
                requireDatabase()
                    .syncTransportDao()
                    .findRequest("sync_push", movedBatch.batchId),
            )
            assertEquals("sending", movedRequest.state)
            assertNull(movedRequest.exactResponseBody)
            val afterRejectedMove = requireNotNull(
                requireDatabase().noteMutationDao().findOutbox(target.operationId),
            )
            assertEquals(originalBatch.batchId, afterRejectedMove.lastResultBatchId)
            assertEquals("invalid_parent", afterRejectedMove.lastResultCode)
            stream = requireNotNull(
                requireDatabase().syncReplicaDao().findStreamState(),
            )
            assertEquals("integrity_halted", stream.phase)
            assertEquals("invalid_parent", stream.integrityErrorCode)
        }

    private suspend fun seedStream() {
        val database = requireDatabase()
        if (database.identityDao().findIdentity() == null) {
            database.identityDao().insertInstallation(
                LocalInstallationEntity(
                    installationId = INSTALLATION_ID,
                    createdAtUtc = "2030-01-01T00:00:00Z",
                    serverDeviceId = DEVICE_ID,
                ),
            )
            database.identityDao().insertOwner(
                LocalOwnerEntity(
                    localOwnerId = LOCAL_OWNER_ID,
                    installationId = INSTALLATION_ID,
                    createdAtUtc = "2030-01-01T00:00:00Z",
                    serverPersonId = PERSON_ID,
                ),
            )
            database.identityDao().insertIdentityState(
                LocalIdentityStateEntity(
                    installationId = INSTALLATION_ID,
                    localOwnerId = LOCAL_OWNER_ID,
                    selectedAtUtc = "2030-01-01T00:00:00Z",
                ),
            )
        }
        if (database.syncAuthDao().findState() == null) {
            database.syncAuthDao().insertStateRow(
                SyncAuthStateEntity(
                    credentialEpochId = CREDENTIAL_EPOCH_ID,
                    installationId = INSTALLATION_ID,
                    localOwnerId = LOCAL_OWNER_ID,
                    deviceId = DEVICE_ID,
                    personId = PERSON_ID,
                    tokenType = "Bearer",
                    refreshTokenCiphertext = byteArrayOf(1),
                    refreshTokenNonce = byteArrayOf(2),
                    refreshTokenKeyAlias = "push-test-key",
                    refreshTokenKeyGeneration = 1,
                    refreshTokenAadVersion = 1,
                    accessExpiresAtUtc = "2031-01-01T00:00:00Z",
                    accessExpiresAtEpochMs =
                        Instant.parse("2031-01-01T00:00:00Z").toEpochMilli(),
                    refreshExpiresAtUtc = "2032-01-01T00:00:00Z",
                    refreshExpiresAtEpochMs =
                        Instant.parse("2032-01-01T00:00:00Z").toEpochMilli(),
                    familyExpiresAtUtc = "2033-01-01T00:00:00Z",
                    familyExpiresAtEpochMs =
                        Instant.parse("2033-01-01T00:00:00Z").toEpochMilli(),
                    generation = 1,
                    state = "active",
                    bootstrapRequired = false,
                    installedAtUtc = "2030-01-01T00:00:00Z",
                    updatedAtUtc = "2030-01-01T00:00:00Z",
                    failureCode = null,
                ),
            )
        }
        database.syncReplicaDao().insertStreamState(
            SyncStreamStateEntity(
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                deviceId = DEVICE_ID,
                phase = "incremental",
                bootstrapRequired = false,
                appliedCursor = "cursor-before-push",
                lastAppliedServerSequence = 0,
                highWatermarkHint = null,
                integrityErrorCode = null,
                updatedAtUtc = "2030-01-01T00:00:00Z",
            ),
        )
    }

    private suspend fun createPendingOperation(seed: Long): SyncOutboxEntity {
        val ids = MutationIds(
            operationId = UUID(0, seed),
            captureId = UUID(0, seed + 1),
            eventId = UUID(0, seed + 2),
            revisionId = UUID(0, seed + 3),
        )
        val recordedAt = OffsetDateTime.parse("2030-01-01T07:00:00+07:00")
            .plusMinutes(seed)
        repository.create(
            CreateNoteCommand(
                ids = ids,
                text = "Push replay operation $seed",
                effectiveTime = PointTimeResolver.resolveInstant(
                    recordedAt.toInstant(),
                    ZoneId.of("Asia/Novosibirsk"),
                ),
                recordedAt = recordedAt,
            ),
        )
        val pending = requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(ids.operationId.toString()),
        )
        val wireDigest = sha256("wire-${ids.operationId}")
        assertEquals(
            1,
            requireDatabase().outboxDao().installWireMaterial(
                localSequence = pending.localSequence,
                operationId = pending.operationId,
                protocolVersion = "1.0.0",
                materialJcs = "{}".toByteArray(StandardCharsets.UTF_8),
                contentSha256 = wireDigest,
                materializedAtUtc = recordedAt.toInstant().toString(),
            ),
        )
        return requireNotNull(
            requireDatabase().noteMutationDao().findOutbox(ids.operationId.toString()),
        )
    }

    private suspend fun seedClaimedBatch(
        batchId: String,
        attemptId: String,
        operations: List<SyncOutboxEntity>,
        responseBody: String,
        terminalAtUtc: String,
    ): SeededBatch {
        val seeded = batch(
            batchId = batchId,
            attemptId = attemptId,
            operations = operations,
            responseBody = responseBody,
            terminalAtUtc = terminalAtUtc,
        )
        requireDatabase().syncTransportDao().insertPushRequest(
            request = seeded.request.copy(
                state = "ready",
                attemptCount = 0,
                lastAttemptAtEpochMs = null,
                leaseExpiresAtEpochMs = null,
                activeAttemptId = null,
            ),
            batch = seeded.batch,
            items = seeded.items,
        )
        assertEquals(
            1,
            requireDatabase().syncTransportDao().claimAttempt(
                endpointId = "sync_push",
                requestIdentity = batchId,
                credentialEpochId = CREDENTIAL_EPOCH_ID,
                accessGenerationUsed = 1,
                attemptId = attemptId,
                attemptedAtEpochMs = 1,
                leaseExpiresAtEpochMs = 2,
                updatedAtUtc = "2030-01-01T00:00:00Z",
            ),
        )
        return seeded
    }

    private suspend fun seedUnclaimedBatch(
        batchId: String,
        attemptId: String,
        operations: List<SyncOutboxEntity>,
        responseBody: String,
        terminalAtUtc: String,
    ): SeededBatch {
        val seeded = batch(
            batchId = batchId,
            attemptId = attemptId,
            operations = operations,
            responseBody = responseBody,
            terminalAtUtc = terminalAtUtc,
        )
        val transport = requireDatabase().syncTransportDao()
        transport.insertRequest(seeded.request)
        transport.insertBatch(seeded.batch)
        transport.insertBatchItems(seeded.items)
        return seeded
    }

    private fun batch(
        batchId: String,
        attemptId: String,
        operations: List<SyncOutboxEntity>,
        responseBody: String,
        terminalAtUtc: String,
    ): SeededBatch {
        val requestBytes = """{"batch_id":"$batchId"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val request = SyncHttpRequestEntity(
            endpointId = "sync_push",
            requestIdentity = batchId,
            protocolVersion = "1.0.0",
            credentialEpochId = CREDENTIAL_EPOCH_ID,
            deviceId = DEVICE_ID,
            idempotencyKey = batchId,
            rawRequestBody = requestBytes,
            rawBodyHmac = ByteArray(32) { 4 },
            hmacKeyGeneration = 1,
            state = "sending",
            attemptCount = 1,
            attemptBudget = 8,
            deadlineAtEpochMs = Instant.parse("2030-01-01T01:00:00Z").toEpochMilli(),
            nextAttemptAtEpochMs = null,
            lastAttemptAtEpochMs = 1,
            leaseExpiresAtEpochMs = 2,
            activeAttemptId = attemptId,
            accessGenerationUsed = 1,
            terminalHttpStatus = null,
            exactResponseBody = null,
            responseSha256 = null,
            terminalAtUtc = null,
            terminalErrorCode = null,
            createdAtUtc = "2030-01-01T00:00:00Z",
            updatedAtUtc = "2030-01-01T00:00:00Z",
        )
        val batch = SyncPushBatchEntity(
            batchId = batchId,
            endpointId = "sync_push",
            requestIdentity = batchId,
            batchContentSha256 = sha256("batch-$batchId"),
            operationCount = operations.size,
            createdAtUtc = "2030-01-01T00:00:00Z",
        )
        val items = operations.mapIndexed { ordinal, operation ->
            SyncPushBatchItemEntity(
                batchId = batchId,
                ordinal = ordinal,
                localSequence = operation.localSequence,
                operationId = operation.operationId,
                wireOperationContentSha256 =
                    requireNotNull(operation.wireOperationContentSha256),
            )
        }
        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        val response = TerminalHttpResponsePersistence(
            endpointId = "sync_push",
            requestIdentity = batchId,
            expectedAttemptId = attemptId,
            httpStatus = 200,
            exactResponseBody = responseBytes,
            responseSha256 = sha256(responseBytes),
            terminalAtUtc = terminalAtUtc,
            terminalErrorCode = null,
        )
        return SeededBatch(
            batchId = batchId,
            request = request,
            batch = batch,
            items = items,
            response = response,
        )
    }

    private fun invalidParent(
        ordinal: Int,
        operation: SyncOutboxEntity,
    ) = PushErrorPersistence(
        ordinal = ordinal,
        operationId = operation.operationId,
        operationContentSha256 =
            requireNotNull(operation.wireOperationContentSha256),
        errorCode = "invalid_parent",
        retryable = false,
        detailsJcs = EMPTY_FIELD_ERRORS,
    )

    private fun ack(
        operation: SyncOutboxEntity,
        batch: SeededBatch,
        committedAtUtc: String,
    ) = PushAckPersistence(
        ordinal = 0,
        change = SyncServerChangeEntity(
            serverSequence = 1,
            operationId = operation.operationId,
            operationContentSha256 =
                requireNotNull(operation.wireOperationContentSha256),
            resultCode = "applied",
            captureId = operation.captureId,
            eventId = operation.eventId,
            revisionId = operation.revisionId,
            currentRevisionId = operation.revisionId,
            committedAtUtc = committedAtUtc,
            firstEndpointId = "sync_push",
            firstRequestIdentity = batch.batchId,
            verifiedAtUtc = committedAtUtc,
        ),
    )

    private suspend fun assertReplicaFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(
            "Expected ReplicaIntegrityException, received ${failure?.javaClass?.name}",
            failure is ReplicaIntegrityException,
        )
    }

    private fun requireDatabase(): LifeAgentDatabase =
        requireNotNull(database) { "Push replay test database is closed" }

    private fun sha256(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun uuid(value: Long): String = UUID(0, value).toString()

    private data class SeededBatch(
        val batchId: String,
        val request: SyncHttpRequestEntity,
        val batch: SyncPushBatchEntity,
        val items: List<SyncPushBatchItemEntity>,
        val response: TerminalHttpResponsePersistence,
    )

    private companion object {
        const val INSTALLATION_ID = "92000000-0000-4000-8000-000000000101"
        const val LOCAL_OWNER_ID = "93000000-0000-4000-8000-000000000101"
        const val PERSON_ID = "94000000-0000-4000-8000-000000000101"
        const val CREDENTIAL_EPOCH_ID = "90000000-0000-4000-8000-000000000101"
        const val DEVICE_ID = "91000000-0000-4000-8000-000000000101"
        const val REPLACEMENT_CREDENTIAL_EPOCH_ID =
            "90000000-0000-4000-8000-000000000102"
        const val REPLACEMENT_DEVICE_ID = "91000000-0000-4000-8000-000000000102"
        val EMPTY_FIELD_ERRORS = "[]".toByteArray(StandardCharsets.UTF_8)
    }
}
