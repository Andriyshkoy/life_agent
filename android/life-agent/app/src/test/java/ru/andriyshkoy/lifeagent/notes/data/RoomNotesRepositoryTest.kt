package ru.andriyshkoy.lifeagent.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.IdempotencyConflictException
import ru.andriyshkoy.lifeagent.notes.domain.LocalIdentityCollisionException
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationDisposition
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomNotesRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: LifeAgentDatabase = LifeAgentDatabaseFactory.createInMemory(context)
    private val repository = RoomNotesRepository(
        database = database,
        collectorVersion = "test",
    )

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `create and replay commit one event revision and outbox operation`() = runTest {
        val command = createCommand(ids(1, 2, 3, 4), "первая заметка")

        val committed = repository.create(command).persistedReceipt()
        val replayed = repository.create(command).persistedReceipt()
        val counts = database.noteMutationDao().tableCounts()

        assertEquals(NoteMutationDisposition.COMMITTED, committed.disposition)
        assertEquals(NoteMutationDisposition.REPLAYED, replayed.disposition)
        assertEquals(committed.localSequence, replayed.localSequence)
        assertEquals(1, counts.captures)
        assertEquals(1, counts.events)
        assertEquals(1, counts.revisions)
        assertEquals(1, counts.heads)
        assertEquals(1, counts.outboxOperations)
        assertEquals("первая заметка", repository.observeLastCommitted().first()?.text)
    }

    @Test
    fun `concurrent replay still commits exactly once`() = runTest {
        val command = createCommand(ids(11, 12, 13, 14), "одна операция")

        val outcomes = coroutineScope {
            List(20) {
                async { repository.create(command).persistedReceipt() }
            }.awaitAll()
        }

        assertEquals(1, outcomes.map { it.localSequence }.distinct().size)
        assertEquals(1, database.noteMutationDao().tableCounts().outboxOperations)
        assertEquals(1, outcomes.count { !it.replayed })
    }

    @Test
    fun `correction and retraction retain history and move explicit pointer`() = runTest {
        val created = repository.create(
            createCommand(ids(21, 22, 23, 24), "до исправления"),
        ).persistedReceipt()
        val corrected = repository.correct(
            CorrectNoteCommand(
                ids = ids(25, 26, 23, 27),
                expectedCurrentRevisionId = created.note.revisionId,
                text = "после исправления",
                effectiveTime = effectiveTime(),
                recordedAt = recordedAt().plusMinutes(1),
            ),
        ).persistedReceipt()
        val retracted = repository.retract(
            RetractNoteCommand(
                ids = ids(28, 29, 23, 30),
                expectedCurrentRevisionId = corrected.note.revisionId,
                recordedAt = recordedAt().plusMinutes(2),
            ),
        ).persistedReceipt()

        val current = repository.getByEventId(uuid(23))
        val counts = database.noteMutationDao().tableCounts()
        val exported = repository.exportSnapshot()

        assertEquals(NoteRecordStatus.RETRACTED, current?.status)
        assertEquals(retracted.note.revisionId, current?.revisionId)
        assertEquals("после исправления", current?.text)
        assertEquals(3, counts.revisions)
        assertEquals(2, counts.parents)
        assertEquals(3, counts.outboxOperations)
        assertEquals(1, exported.events.size)
        assertEquals(3, exported.revisions.size)
        assertEquals(retracted.note.revisionId, exported.events.single().currentRevisionId)
        exported.revisions.forEach { revision ->
            val document = Json.parseToJsonElement(revision.canonicalJson).jsonObject
            assertEquals(18, document.keys.size)
            assertNotNull(document["payload"])
            assertNotNull(document["revision"])
            assertNotNull(document["server"])
        }
    }

    @Test
    fun `second retraction with a new operation is a no-op`() = runTest {
        val created = repository.create(
            createCommand(ids(31, 32, 33, 34), "заметка"),
        ).persistedReceipt()
        repository.retract(
            RetractNoteCommand(
                ids = ids(35, 36, 33, 37),
                expectedCurrentRevisionId = created.note.revisionId,
                recordedAt = recordedAt().plusMinutes(1),
            ),
        )

        val outcome = repository.retract(
            RetractNoteCommand(
                ids = ids(38, 39, 33, 40),
                expectedCurrentRevisionId = created.note.revisionId,
                recordedAt = recordedAt().plusMinutes(2),
            ),
        )

        assertTrue(outcome is NoteMutationOutcome.AlreadyRetracted)
        assertEquals(2, database.noteMutationDao().tableCounts().revisions)
    }

    @Test
    fun `event identity collision leaves all table counts unchanged`() = runTest {
        repository.create(createCommand(ids(41, 42, 43, 44), "первая"))
        val before = database.noteMutationDao().tableCounts()

        org.junit.Assert.assertThrows(LocalIdentityCollisionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.create(createCommand(ids(45, 46, 43, 47), "коллизия"))
            }
        }

        assertEquals(before, database.noteMutationDao().tableCounts())
    }

    @Test
    fun `legacy blank fingerprints accept exact replays and reject changed commands`() = runTest {
        val create = createCommand(ids(51, 52, 53, 54), "исходная")
        repository.create(create)
        val correct = CorrectNoteCommand(
            ids = ids(55, 56, 53, 57),
            expectedCurrentRevisionId = create.ids.revisionId,
            text = "исправленная",
            effectiveTime = effectiveTime(),
            recordedAt = recordedAt().plusMinutes(1),
            reason = "исправление",
        )
        repository.correct(correct)
        val retract = RetractNoteCommand(
            ids = ids(58, 59, 53, 60),
            expectedCurrentRevisionId = correct.ids.revisionId,
            recordedAt = recordedAt().plusMinutes(2),
        )
        repository.retract(retract)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_outbox SET command_fingerprint_sha256 = ''",
        )

        assertEquals(
            NoteMutationDisposition.REPLAYED,
            repository.create(create).persistedReceipt().disposition,
        )
        assertEquals(
            NoteMutationDisposition.REPLAYED,
            repository.correct(correct).persistedReceipt().disposition,
        )
        assertEquals(
            NoteMutationDisposition.REPLAYED,
            repository.retract(retract).persistedReceipt().disposition,
        )
        listOf(create.ids, correct.ids, retract.ids).forEach { mutationIds ->
            assertTrue(
                database.noteMutationDao()
                    .findOutbox(mutationIds.operationId.toString())
                    ?.commandFingerprintSha256
                    ?.isNotBlank() == true,
            )
        }

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE sync_outbox
            SET command_fingerprint_sha256 = ''
            WHERE operation_id = ?
            """.trimIndent(),
            arrayOf(create.ids.operationId.toString()),
        )
        val changedReplay = runCatching {
            repository.create(create.copy(text = "изменённая"))
        }.exceptionOrNull()

        assertTrue(changedReplay is IdempotencyConflictException)
        assertEquals(
            "",
            database.noteMutationDao()
                .findOutbox(create.ids.operationId.toString())
                ?.commandFingerprintSha256,
        )
        assertEquals(3, database.noteMutationDao().tableCounts().outboxOperations)
    }

    private fun createCommand(ids: MutationIds, text: String) = CreateNoteCommand(
        ids = ids,
        text = text,
        effectiveTime = effectiveTime(),
        recordedAt = recordedAt(),
    )

    private fun effectiveTime() = PointTimeResolver.resolveInstant(
        Instant.parse("2026-07-27T06:12:00Z"),
        ZoneId.of("Asia/Novosibirsk"),
    )

    private fun recordedAt(): OffsetDateTime =
        OffsetDateTime.parse("2026-07-27T13:12:00+07:00")

    private fun ids(operation: Int, capture: Int, event: Int, revision: Int) =
        MutationIds(uuid(operation), uuid(capture), uuid(event), uuid(revision))

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}

private fun NoteMutationOutcome.persistedReceipt() =
    (this as NoteMutationOutcome.Persisted).receipt
