package ru.andriyshkoy.lifeagent.domain.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.export.CanonicalLifeAgentExportCodec
import ru.andriyshkoy.lifeagent.data.export.CanonicalLifeEventJson
import ru.andriyshkoy.lifeagent.data.export.CatalogExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.EventPointerExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.LifeAgentExportSnapshot
import ru.andriyshkoy.lifeagent.data.export.LifeAgentExportValidationException
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CorruptLocalNoteException
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomNotesExportIntegrationTest {
    private lateinit var database: LifeAgentDatabase
    private lateinit var repository: RoomNotesRepository
    private lateinit var codec: CanonicalLifeAgentExportCodec
    private lateinit var exportLifeAgent: ExportLifeAgentUseCase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = LifeAgentDatabaseFactory.createInMemory(context)
        repository = RoomNotesRepository(
            database = database,
            collectorVersion = "integration-test",
        )
        codec = CanonicalLifeAgentExportCodec()
        exportLifeAgent = ExportLifeAgentUseCase(
            source = LifeAgentExportSnapshotSource {
                val notes = repository.exportSnapshot()
                LifeAgentExportSnapshot(
                    catalogs = CatalogExportSnapshot.Empty,
                    events = notes.events.map { pointer ->
                        EventPointerExportSnapshot(
                            eventId = pointer.eventId.toString(),
                            currentRevisionId = pointer.currentRevisionId.toString(),
                        )
                    },
                    revisions = notes.revisions.map { revision ->
                        CanonicalLifeEventJson.fromJson(
                            revision.canonicalJson.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            },
            codec = codec,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomHistoryFlowsThroughAdapterAndCodecDeterministically() = runTest {
        val eventId = uuid(3)
        val created = repository.create(
            CreateNoteCommand(
                ids = ids(operation = 1, capture = 2, event = 3, revision = 4),
                text = "Synthetic initial note",
                effectiveTime = effectiveTime(minute = 0),
                recordedAt = recordedAt(minute = 0),
            ),
        ).persisted()
        val corrected = repository.correct(
            CorrectNoteCommand(
                ids = ids(operation = 5, capture = 6, event = 3, revision = 7),
                expectedCurrentRevisionId = created.note.revisionId,
                text = "Synthetic corrected note",
                effectiveTime = effectiveTime(minute = 1, millisecond = 123),
                recordedAt = recordedAt(minute = 1),
                reason = "synthetic_correction",
            ),
        ).persisted()
        val retracted = repository.retract(
            RetractNoteCommand(
                ids = ids(operation = 8, capture = 9, event = 3, revision = 10),
                expectedCurrentRevisionId = corrected.note.revisionId,
                recordedAt = recordedAt(minute = 2),
            ),
        ).persisted()

        val first = exportLifeAgent()
        val second = exportLifeAgent()
        val decoded = codec.decode(first)

        assertArrayEquals(first, second)
        assertArrayEquals(first, codec.canonicalize(first))
        assertEquals(eventId.toString(), decoded.events.single().eventId)
        assertEquals(
            retracted.note.revisionId.toString(),
            decoded.events.single().currentRevisionId,
        )
        assertEquals(3, decoded.revisions.size)

        val documents = decoded.revisions.map { revision ->
            Json.parseToJsonElement(revision.toByteArray().toString(Charsets.UTF_8))
                .jsonObject
        }
        assertEquals(
            listOf("active", "active", "retracted"),
            documents.map { it.getValue("record_status").jsonPrimitive.content },
        )
        assertEquals(
            listOf(0, 1, 1),
            documents.map {
                it.getValue("revision")
                    .jsonObject
                    .getValue("parents")
                    .jsonArray
                    .size
            },
        )
        assertEquals(
            "Synthetic corrected note",
            documents.last()
                .getValue("payload")
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            listOf(
                "2026-01-10T10:00:00.000",
                "2026-01-10T10:01:00.123",
                "2026-01-10T10:01:00.123",
            ),
            documents.map {
                it.getValue("time")
                    .jsonObject
                    .getValue("original_local_start")
                    .jsonPrimitive
                    .content
            },
        )
    }

    @Test
    fun headlessEventAndItsRevisionsFailClosed() = runTest {
        val eventId = uuid(23)
        repository.create(
            CreateNoteCommand(
                ids = ids(operation = 21, capture = 22, event = 23, revision = 24),
                text = "Synthetic headless note",
                effectiveTime = effectiveTime(),
                recordedAt = recordedAt(),
            ),
        ).persisted()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM local_event_head WHERE event_id = ?",
            arrayOf(eventId.toString()),
        )

        val failure = runCatching { repository.exportSnapshot() }.exceptionOrNull()

        assertTrue(failure is CorruptLocalNoteException)
        assertEquals(1, database.lifeEventMutationDao().tableCounts().revisions)
    }

    @Test
    fun corruptedNestedPayloadCannotBecomeVerifiedExport() = runTest {
        val revisionId = createSingleNote(seed = 31)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE local_event_revision SET payload_jcs = ? WHERE revision_id = ?",
            arrayOf(
                """{"extra":true,"text":"Synthetic corrupt payload"}"""
                    .toByteArray(Charsets.UTF_8),
                revisionId.toString(),
            ),
        )

        val failure = exportValidationFailure()

        assertTrue(
            failure.violations.any {
                it.contains("payload") && it.contains("unknown fields")
            },
        )
    }

    @Test
    fun corruptedTimeCannotBecomeVerifiedExport() = runTest {
        val revisionId = createSingleNote(seed = 41)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE local_event_revision SET timezone_id = ? WHERE revision_id = ?",
            arrayOf("Mars/Olympus_Mons", revisionId.toString()),
        )

        val failure = exportValidationFailure()

        assertTrue(
            failure.violations.any { it.contains("timezone_id") },
        )
    }

    @Test
    fun corruptedContentDigestCannotBecomeVerifiedExport() = runTest {
        val revisionId = createSingleNote(seed = 51)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE local_event_revision SET content_sha256 = ? WHERE revision_id = ?",
            arrayOf("0".repeat(64), revisionId.toString()),
        )

        val failure = exportValidationFailure()

        assertTrue(
            failure.violations.any {
                it.contains("content_sha256") &&
                    it.contains("canonical immutable revision content")
            },
        )
    }

    private suspend fun createSingleNote(seed: Int): UUID {
        val outcome = repository.create(
            CreateNoteCommand(
                ids = ids(
                    operation = seed,
                    capture = seed + 1,
                    event = seed + 2,
                    revision = seed + 3,
                ),
                text = "Synthetic corruption fixture",
                effectiveTime = effectiveTime(),
                recordedAt = recordedAt(),
            ),
        ).persisted()
        return outcome.note.revisionId
    }

    private suspend fun exportValidationFailure(): LifeAgentExportValidationException {
        val failure = runCatching { exportLifeAgent() }.exceptionOrNull()
        assertTrue(failure is LifeAgentExportValidationException)
        return failure as LifeAgentExportValidationException
    }

    private fun effectiveTime(
        minute: Long = 0,
        millisecond: Long = 0,
    ) =
        PointTimeResolver.resolveInstant(
            Instant.parse("2026-01-10T03:00:00Z")
                .plusSeconds(minute * 60)
                .plusMillis(millisecond),
            ZoneId.of("Asia/Novosibirsk"),
        )

    private fun recordedAt(minute: Long = 0): OffsetDateTime =
        OffsetDateTime.parse("2026-01-10T10:00:00+07:00")
            .plusMinutes(minute)

    private fun ids(
        operation: Int,
        capture: Int,
        event: Int,
        revision: Int,
    ): MutationIds = MutationIds(
        operationId = uuid(operation),
        captureId = uuid(capture),
        eventId = uuid(event),
        revisionId = uuid(revision),
    )

    private fun uuid(value: Int): UUID =
        UUID.fromString(
            "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}",
        )
}

private fun NoteMutationOutcome.persisted() =
    (this as NoteMutationOutcome.Persisted).receipt
