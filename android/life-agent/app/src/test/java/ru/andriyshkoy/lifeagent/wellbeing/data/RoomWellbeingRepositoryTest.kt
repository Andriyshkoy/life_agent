package ru.andriyshkoy.lifeagent.wellbeing.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorrectWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CorruptLocalWellbeingException
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.InvalidWellbeingException
import ru.andriyshkoy.lifeagent.wellbeing.domain.RetractWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.UpdateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingIdempotencyConflictException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationDisposition
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationOutcome
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOptionDraft
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomWellbeingRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: LifeAgentDatabase = LifeAgentDatabaseFactory.createInMemory(context)
    private val catalog = RoomWellbeingCatalogRepository(database)
    private val repository = RoomWellbeingRepository(
        database = database,
        collectorVersion = "test",
    )

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `create and exact replay commit one explicit selection`() = runTest {
        val dimension = seededDimensions().first()
        val command = createCommand(
            ids = ids(1, 2, 3, 4),
            values = listOf(dimension.snapshot(dimension.options[2].optionId)),
            comment = "  после прогулки  ",
        )

        val committed = repository.create(command).persistedReceipt()
        val replayed = repository.create(command).persistedReceipt()
        val observed = repository.observeLastCommitted().first()
        val counts = database.lifeEventMutationDao().tableCounts()

        assertEquals(WellbeingMutationDisposition.COMMITTED, committed.disposition)
        assertEquals(WellbeingMutationDisposition.REPLAYED, replayed.disposition)
        assertEquals(committed.wellbeing, replayed.wellbeing)
        assertEquals("после прогулки", committed.wellbeing.payload.comment)
        assertEquals(recordedAt(), observed?.recordedAt)
        assertEquals(command.values, observed?.payload?.values)
        assertEquals(1, counts.captures)
        assertEquals(1, counts.events)
        assertEquals(1, counts.revisions)
        assertEquals(0, counts.parents)
        assertEquals(1, counts.heads)
    }

    @Test
    fun `concurrent exact replay commits one wellbeing revision`() = runTest {
        val dimension = seededDimensions().first()
        val command = createCommand(
            ids(5, 6, 7, 8),
            listOf(dimension.snapshot(dimension.options.first().optionId)),
        )

        val outcomes = coroutineScope {
            List(20) {
                async { repository.create(command).persistedReceipt() }
            }.awaitAll()
        }

        assertEquals(1, outcomes.map { it.wellbeing }.distinct().size)
        assertEquals(1, outcomes.count { !it.replayed })
        assertEquals(1, database.lifeEventMutationDao().tableCounts().revisions)
    }

    @Test
    fun `operation replay with changed immutable command fails without new rows`() = runTest {
        val dimension = seededDimensions().first()
        val command = createCommand(
            ids(9, 10, 21, 22),
            listOf(dimension.snapshot(dimension.options.first().optionId)),
            comment = "исходный",
        )
        repository.create(command)
        val before = database.lifeEventMutationDao().tableCounts()

        assertThrows(WellbeingIdempotencyConflictException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.create(command.copy(comment = "изменённый"))
            }
        }

        assertEquals(before, database.lifeEventMutationDao().tableCounts())
        assertEquals("исходный", repository.getByEventId(command.ids.eventId)?.payload?.comment)
    }

    @Test
    fun `correction and retraction retain canonical history and move the pointer`() = runTest {
        val dimensions = seededDimensions()
        val created = repository.create(
            createCommand(
                ids(11, 12, 13, 14),
                listOf(dimensions[0].snapshot(dimensions[0].options[1].optionId)),
            ),
        ).persistedReceipt()
        val corrected = repository.correct(
            CorrectWellbeingCommand(
                ids = ids(15, 16, 13, 17),
                expectedCurrentRevisionId = created.wellbeing.revisionId,
                values = listOf(dimensions[1].snapshot(dimensions[1].options[3].optionId)),
                comment = "вечером",
                effectiveTime = effectiveTime(),
                recordedAt = recordedAt().plusMinutes(1),
                reason = "уточнение",
            ),
        ).persistedReceipt()
        assertEquals(1, corrected.wellbeing.payload.values.size)
        assertEquals(
            dimensions[1].dimensionId,
            corrected.wellbeing.payload.values.single().dimensionId,
        )
        assertTrue(
            corrected.wellbeing.payload.values.none {
                it.dimensionId == dimensions[0].dimensionId
            },
        )
        val retracted = repository.retract(
            RetractWellbeingCommand(
                ids = ids(18, 19, 13, 20),
                expectedCurrentRevisionId = corrected.wellbeing.revisionId,
                recordedAt = recordedAt().plusMinutes(2),
            ),
        ).persistedReceipt()

        val current = repository.getByEventId(uuid(13))
        val exported = repository.exportSnapshot()
        val counts = database.lifeEventMutationDao().tableCounts()

        assertEquals(WellbeingRecordStatus.RETRACTED, current?.status)
        assertEquals(retracted.wellbeing.revisionId, current?.revisionId)
        assertEquals(corrected.wellbeing.payload, current?.payload)
        assertEquals(3, counts.revisions)
        assertEquals(2, counts.parents)
        assertEquals(retracted.wellbeing.revisionId, exported.events.single().currentRevisionId)
        assertEquals(listOf(1, 2, 3), exported.revisions.map { it.revisionNo })
        assertTrue(exported.revisions.all { it.contentSha256.length == 64 })
        assertTrue(exported.revisions.all { it.canonicalJson.contains("\"kind\":\"wellbeing\"") })
    }

    @Test
    fun `create rejects forged foreign and inactive catalog snapshots without writes`() = runTest {
        val dimensions = seededDimensions()
        val overall = dimensions[0]
        val mood = dimensions[1]
        val valid = overall.snapshot(overall.options[2].optionId)
        val forged = valid.copy(optionLabel = "Подменено")
        val foreign = valid.copy(
            optionId = mood.options[0].optionId,
            optionVersion = mood.options[0].version,
            optionLabel = mood.options[0].label,
            optionSortOrder = mood.options[0].sortOrder,
        )

        assertInvalidCreate(ids(31, 32, 33, 34), forged)
        assertInvalidCreate(ids(35, 36, 37, 38), foreign)

        val updated = catalog.update(
            overall.updateCommand(
                catalogVersionId = uuid(39),
                createdAt = instant(60),
                options = overall.options.map { option ->
                    option.toDraft(active = option.optionId != valid.optionId)
                },
            ),
        )
        val inactive = updated.snapshot(valid.optionId)
        assertInvalidCreate(ids(40, 41, 42, 43), inactive)

        val counts = database.lifeEventMutationDao().tableCounts()
        assertEquals(0, counts.captures)
        assertEquals(0, counts.events)
        assertEquals(0, counts.revisions)
        assertEquals(0, counts.heads)
    }

    @Test
    fun `correction retains exact archived snapshot but rejects a forged historical value`() = runTest {
        val overall = seededDimensions().first()
        val selected = overall.snapshot(overall.options[1].optionId)
        val created = repository.create(
            createCommand(ids(51, 52, 53, 54), listOf(selected)),
        ).persistedReceipt()
        val currentCatalog = catalog.update(
            overall.updateCommand(
                catalogVersionId = uuid(55),
                createdAt = instant(60),
                options = overall.options.map { option ->
                    option.toDraft(
                        label = if (option.optionId == selected.optionId) {
                            "Больше не используется"
                        } else {
                            option.label
                        },
                        active = option.optionId != selected.optionId,
                    )
                },
            ),
        )
        val retained = repository.correct(
            correctCommand(
                ids = ids(56, 57, 53, 58),
                expected = created.wellbeing.revisionId,
                values = listOf(selected),
                comment = "только комментарий",
            ),
        ).persistedReceipt()
        val beforeForgery = database.lifeEventMutationDao().tableCounts()

        assertThrows(InvalidWellbeingException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.correct(
                    correctCommand(
                        ids = ids(59, 60, 53, 61),
                        expected = retained.wellbeing.revisionId,
                        values = listOf(selected.copy(optionLabel = "Подделка")),
                    ),
                )
            }
        }
        assertEquals(beforeForgery, database.lifeEventMutationDao().tableCounts())

        val replacement = currentCatalog.options.first { it.active }.let { option ->
            currentCatalog.snapshot(option.optionId)
        }
        val replaced = repository.correct(
            correctCommand(
                ids = ids(62, 63, 53, 64),
                expected = retained.wellbeing.revisionId,
                values = listOf(replacement),
            ),
        ).persistedReceipt()
        assertEquals(listOf(replacement), replaced.wellbeing.payload.values)
    }

    @Test
    fun `corrupt current catalog digest cannot authorize a selection`() = runTest {
        val overall = seededDimensions().first()
        val selection = overall.snapshot(overall.options.first().optionId)
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE local_catalog_version
            SET content_sha256 = ?
            WHERE catalog_version_id = ?
            """.trimIndent(),
            arrayOf("0".repeat(64), overall.catalogVersionId.toString()),
        )
        val before = database.lifeEventMutationDao().tableCounts()

        assertThrows(CorruptLocalWellbeingException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.create(
                    createCommand(ids(71, 72, 73, 74), listOf(selection)),
                )
            }
        }

        assertEquals(before, database.lifeEventMutationDao().tableCounts())
        assertEquals(null, repository.getByEventId(uuid(73)))
    }

    private suspend fun seededDimensions(): List<WellbeingDimension> {
        catalog.ensureSeeded(instant(0))
        return catalog.observeDimensions(includeArchived = true).first()
    }

    private fun assertInvalidCreate(ids: MutationIds, value: WellbeingValueSnapshot) {
        assertThrows(InvalidWellbeingException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.create(createCommand(ids, listOf(value)))
            }
        }
    }

    private fun createCommand(
        ids: MutationIds,
        values: List<WellbeingValueSnapshot>,
        comment: String? = null,
    ) = CreateWellbeingCommand(
        ids = ids,
        values = values,
        comment = comment,
        effectiveTime = effectiveTime(),
        recordedAt = recordedAt(),
    )

    private fun correctCommand(
        ids: MutationIds,
        expected: UUID,
        values: List<WellbeingValueSnapshot>,
        comment: String? = null,
    ) = CorrectWellbeingCommand(
        ids = ids,
        expectedCurrentRevisionId = expected,
        values = values,
        comment = comment,
        effectiveTime = effectiveTime(),
        recordedAt = recordedAt().plusMinutes(1),
    )

    private fun WellbeingDimension.updateCommand(
        catalogVersionId: UUID,
        createdAt: Instant,
        options: List<WellbeingOptionDraft>,
    ) = UpdateWellbeingDimensionCommand(
        dimensionId = dimensionId,
        catalogVersionId = catalogVersionId,
        expectedCurrentVersionId = this.catalogVersionId,
        label = label,
        sortOrder = sortOrder,
        active = active,
        options = options,
        createdAt = createdAt,
    )

    private fun ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOption.toDraft(
        label: String = this.label,
        active: Boolean = this.active,
    ) = WellbeingOptionDraft(
        optionId = optionId,
        label = label,
        sortOrder = sortOrder,
        active = active,
    )

    private fun effectiveTime() = PointTimeResolver.resolveInstant(
        Instant.parse("2026-07-27T06:12:00Z"),
        ZoneId.of("Asia/Novosibirsk"),
    )

    private fun recordedAt(): OffsetDateTime =
        OffsetDateTime.parse("2026-07-27T13:12:00+07:00")

    private fun instant(seconds: Long): Instant =
        Instant.parse("2026-07-27T06:12:00Z").plusSeconds(seconds)

    private fun ids(operation: Int, capture: Int, event: Int, revision: Int) =
        MutationIds(uuid(operation), uuid(capture), uuid(event), uuid(revision))

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}

private fun WellbeingMutationOutcome.persistedReceipt() =
    (this as WellbeingMutationOutcome.Persisted).receipt
