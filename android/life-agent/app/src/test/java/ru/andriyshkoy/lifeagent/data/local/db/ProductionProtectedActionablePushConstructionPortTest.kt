package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec
import ru.andriyshkoy.lifeagent.data.sync.wire.constantTimeHexEquals
import ru.andriyshkoy.lifeagent.data.sync.wire.sha256Hex
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductionProtectedActionablePushConstructionPortTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: LifeAgentDatabase
    private lateinit var repository: RoomNotesRepository

    @Before
    fun setUp() {
        database = LifeAgentDatabaseFactory.createInMemory(context)
        repository = RoomNotesRepository(
            database = database,
            collectorVersion = "push-materializer-test",
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `pending create and correction become one canonical ordered batch`() = runTest {
        val created = createNote(1, 2, 3, 4, "Synthetic first note")
        val correctionIds = ids(5, 6, 3, 7)
        repository.correct(
            CorrectNoteCommand(
                ids = correctionIds,
                expectedCurrentRevisionId = created.revisionId,
                text = "Synthetic corrected note",
                effectiveTime = effectiveTime(),
                recordedAt = RECORDED_AT.plusMinutes(1),
                reason = "Synthetic correction",
            ),
        )
        val port = port(BATCH_ONE)

        val construction = database.withTransaction {
            port.build(authority())
        }

        val body = WireRequestCodec.materialize(construction.request).use { it.copyBody() }
        try {
            val evidence = WireRequestCodec.decodeDurablePushEvidence(body)
            assertEquals(BATCH_ONE.toString(), evidence.batchId)
            assertEquals(DEVICE_ID, evidence.deviceId)
            assertEquals(listOf(0, 1), evidence.items.map { it.ordinal })
            assertEquals(listOf(1L, 2L), evidence.items.map { it.clientSequence })
            assertEquals(evidence.batchContentSha256, construction.batch.batchContentSha256)
            assertEquals(
                evidence.items.map { it.operationId },
                construction.items.map { it.operationId },
            )
        } finally {
            body.fill(0)
        }

        val first = requireNotNull(
            database.noteMutationDao().findOutbox(created.operationId.toString()),
        )
        val second = requireNotNull(
            database.noteMutationDao().findOutbox(correctionIds.operationId.toString()),
        )
        listOf(first, second).forEachIndexed { ordinal, outbox ->
            val material = requireNotNull(outbox.wireOperationMaterialJcs)
            try {
                assertEquals("ready", outbox.wireState)
                assertEquals("1.0.0", outbox.wireProtocolVersion)
                assertTrue(
                    constantTimeHexEquals(
                        requireNotNull(outbox.wireOperationContentSha256),
                        sha256Hex(material),
                    ),
                )
                assertEquals(
                    outbox.wireOperationContentSha256,
                    construction.request.operations[ordinal].operationContentSha256,
                )
            } finally {
                material.fill(0)
            }
        }
        assertNull(construction.request.operations.first().expectedCurrentRevisionId)
        assertEquals(
            created.revisionId.toString(),
            construction.request.operations[1].expectedCurrentRevisionId,
        )
        assertFalse(construction.toString().contains("Synthetic"))
        assertFalse(port.toString().contains("Synthetic"))
    }

    @Test
    fun `ready material is immutable and replayed into a new batch without drift`() = runTest {
        val created = createNote(11, 12, 13, 14, "Synthetic replay note")
        val port = port(BATCH_ONE, BATCH_TWO)

        val first = database.withTransaction { port.build(authority()) }
        val retained = requireNotNull(
            database.noteMutationDao()
                .findOutbox(created.operationId.toString())
                ?.wireOperationMaterialJcs,
        ).copyOf()
        val second = database.withTransaction { port.build(authority()) }
        val replayed = requireNotNull(
            database.noteMutationDao()
                .findOutbox(created.operationId.toString())
                ?.wireOperationMaterialJcs,
        )
        try {
            assertTrue(retained.contentEquals(replayed))
            assertEquals(
                first.request.operations.single().operationContentSha256,
                second.request.operations.single().operationContentSha256,
            )
            assertEquals(BATCH_ONE.toString(), first.request.batchId)
            assertEquals(BATCH_TWO.toString(), second.request.batchId)
        } finally {
            retained.fill(0)
            replayed.fill(0)
        }
    }

    @Test
    fun `retained material conflict fails closed before a replacement batch exists`() = runTest {
        val created = createNote(21, 22, 23, 24, "Synthetic conflict note")
        val port = port(BATCH_ONE, BATCH_TWO)
        database.withTransaction { port.build(authority()) }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_outbox SET wire_operation_material_jcs = ? WHERE operation_id = ?",
            arrayOf("{}".encodeToByteArray(), created.operationId.toString()),
        )

        val failure = runCatching {
            database.withTransaction { port.build(authority()) }
        }.exceptionOrNull()

        assertTrue(failure is PushWireMaterializationException)
        assertNull(
            database.syncTransportDao().findRequest(
                endpointId = "sync_push",
                requestIdentity = BATCH_TWO.toString(),
            ),
        )
    }

    @Test
    fun `later corruption rolls back earlier materialization in the same transaction`() = runTest {
        val first = createNote(31, 32, 33, 34, "Synthetic rollback first")
        val second = createNote(35, 36, 37, 38, "Synthetic rollback second")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE local_capture SET content_sha256 = ? WHERE capture_id = ?",
            arrayOf("0".repeat(64), second.captureId.toString()),
        )
        val port = port(BATCH_ONE)

        val failure = runCatching {
            database.withTransaction { port.build(authority()) }
        }.exceptionOrNull()

        assertNotNull(failure)
        listOf(first, second).forEach { mutationIds ->
            val outbox = requireNotNull(
                database.noteMutationDao().findOutbox(mutationIds.operationId.toString()),
            )
            assertEquals("needs_materialization", outbox.wireState)
            assertNull(outbox.wireOperationMaterialJcs)
            assertNull(outbox.wireOperationContentSha256)
            assertNull(outbox.wireMaterializedAtUtc)
        }
    }

    @Test
    fun `cancellation before materialization leaves no partial wire state`() = runTest {
        val created = createNote(41, 42, 43, 44, "Synthetic cancelled note")
        val port = port(BATCH_ONE)

        val job = launch {
            database.withTransaction {
                currentCoroutineContext().cancel()
                port.build(authority())
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        val outbox = requireNotNull(
            database.noteMutationDao().findOutbox(created.operationId.toString()),
        )
        assertEquals("needs_materialization", outbox.wireState)
        assertNull(outbox.wireOperationMaterialJcs)
        assertNull(outbox.wireOperationContentSha256)
    }

    private suspend fun createNote(
        operation: Int,
        capture: Int,
        event: Int,
        revision: Int,
        text: String,
    ): MutationIds = ids(operation, capture, event, revision).also { mutationIds ->
        repository.create(
            CreateNoteCommand(
                ids = mutationIds,
                text = text,
                effectiveTime = effectiveTime(),
                recordedAt = RECORDED_AT.plusMinutes(operation.toLong()),
            ),
        )
    }

    private fun port(vararg batchIds: UUID) =
        ProductionProtectedActionablePushConstructionPort(
            database = database,
            uuidGenerator = SequenceUuidGenerator(batchIds.toList()),
        )

    private fun authority() = ProtectedActionablePushAuthority(
        credentialEpochId = CREDENTIAL_EPOCH_ID,
        deviceId = DEVICE_ID,
        accessGeneration = 1,
        createdAtUtc = CREATED_AT,
        attemptBudget = 8,
        deadlineAtEpochMs = Instant.parse(CREATED_AT).plusSeconds(300).toEpochMilli(),
    )

    private fun effectiveTime() = PointTimeResolver.resolveInstant(
        RECORDED_AT.toInstant(),
        ZoneId.of("Asia/Novosibirsk"),
    )

    private fun ids(operation: Int, capture: Int, event: Int, revision: Int) =
        MutationIds(uuid(operation), uuid(capture), uuid(event), uuid(revision))

    private fun uuid(value: Int): UUID = UUID.fromString(
        "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}",
    )

    private class SequenceUuidGenerator(
        values: List<UUID>,
    ) : UuidGenerator {
        private val iterator = values.iterator()

        override fun next(): UUID = iterator.next()
    }

    private companion object {
        const val CREATED_AT = "2030-01-01T00:00:00Z"
        const val CREDENTIAL_EPOCH_ID = "90000000-0000-4000-8000-000000000001"
        const val DEVICE_ID = "90000000-0000-4000-8000-000000000002"
        val RECORDED_AT: OffsetDateTime = OffsetDateTime.parse("2030-01-01T07:00:00+07:00")
        val BATCH_ONE: UUID = UUID.fromString("90000000-0000-4000-8000-000000000003")
        val BATCH_TWO: UUID = UUID.fromString("90000000-0000-4000-8000-000000000004")
    }
}
