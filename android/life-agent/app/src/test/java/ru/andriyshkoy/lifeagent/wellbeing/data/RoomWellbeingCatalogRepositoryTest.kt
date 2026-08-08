package ru.andriyshkoy.lifeagent.wellbeing.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.wellbeing.domain.ArchiveWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.StaleWellbeingCatalogVersionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.UpdateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogIdentityCollisionException
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOptionDraft

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomWellbeingCatalogRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: LifeAgentDatabase = LifeAgentDatabaseFactory.createInMemory(context)
    private val repository = RoomWellbeingCatalogRepository(database)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `seed is transactional idempotent deterministic and product approved`() = runTest {
        repository.ensureSeeded(instant(0))
        val first = repository.observeDimensions(includeArchived = true).first()
        repository.ensureSeeded(instant(60))
        val second = repository.observeDimensions(includeArchived = true).first()
        val exported = repository.exportSnapshot()

        assertEquals(first, second)
        assertEquals(
            listOf("Общее самочувствие", "Настроение", "Энергия", "Стресс"),
            first.map { it.label },
        )
        assertEquals(
            listOf("Очень плохое", "Плохое", "Нормальное", "Хорошее", "Отличное"),
            first[0].options.map { it.label },
        )
        assertEquals(
            listOf(
                "Очень неприятное",
                "Неприятное",
                "Нейтральное",
                "Приятное",
                "Очень приятное",
            ),
            first[1].options.map { it.label },
        )
        assertEquals(
            listOf(
                "Совсем нет сил",
                "Мало энергии",
                "Средне",
                "Много энергии",
                "Очень много энергии",
            ),
            first[2].options.map { it.label },
        )
        assertEquals(
            listOf("Нет", "Низкий", "Умеренный", "Высокий", "Очень высокий"),
            first[3].options.map { it.label },
        )
        assertEquals(4, exported.items.size)
        assertEquals(4, exported.versions.size)
        assertEquals(4, exported.heads.size)
        assertTrue(exported.items.all { it.localOwnerId == exported.items.first().localOwnerId })
    }

    @Test
    fun `edit appends aggregate version and versions only changed options`() = runTest {
        repository.ensureSeeded(instant(0))
        val original = repository.observeDimensions(includeArchived = true).first().first()
        val unchanged = original.options[0]
        val changed = original.options[1]
        val newOptionId = uuid(900)

        val updated = repository.update(
            UpdateWellbeingDimensionCommand(
                dimensionId = original.dimensionId,
                catalogVersionId = uuid(901),
                expectedCurrentVersionId = original.catalogVersionId,
                label = "Общее состояние",
                sortOrder = 15,
                active = true,
                options = original.options.map { option ->
                    WellbeingOptionDraft(
                        optionId = option.optionId,
                        label = if (option.optionId == changed.optionId) "Тяжело" else option.label,
                        sortOrder = option.sortOrder,
                        active = option.active,
                    )
                } + WellbeingOptionDraft(newOptionId, "По-разному", 60),
                createdAt = instant(60),
            ),
        )
        val export = repository.exportSnapshot()

        assertEquals(2, updated.version)
        assertEquals("Общее состояние", updated.label)
        assertEquals(unchanged.version, updated.options.single { it.optionId == unchanged.optionId }.version)
        assertEquals(changed.version + 1, updated.options.single { it.optionId == changed.optionId }.version)
        assertEquals(1, updated.options.single { it.optionId == newOptionId }.version)
        assertEquals(4, export.items.size)
        assertEquals(5, export.versions.size)
        assertEquals(4, export.heads.size)
        assertEquals(2, export.versions.count { it.catalogItemId == original.dimensionId })
    }

    @Test
    fun `archive appends a version and hides dimension from active observation`() = runTest {
        repository.ensureSeeded(instant(0))
        val original = repository.observeDimensions(includeArchived = true).first().first()
        val archived = repository.archive(
            ArchiveWellbeingDimensionCommand(
                dimensionId = original.dimensionId,
                catalogVersionId = uuid(910),
                expectedCurrentVersionId = original.catalogVersionId,
                archivedAt = instant(60),
            ),
        )

        assertFalse(archived.active)
        assertEquals(2, archived.version)
        assertEquals(3, repository.observeDimensions().first().size)
        assertEquals(4, repository.observeDimensions(includeArchived = true).first().size)
    }

    @Test
    fun `stale catalog update cannot move the head`() = runTest {
        repository.ensureSeeded(instant(0))
        val original = repository.observeDimensions(includeArchived = true).first().first()
        val command = original.updateCommand(uuid(920), instant(60), label = "Первое изменение")
        repository.update(command)

        assertThrows(StaleWellbeingCatalogVersionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.update(
                    original.updateCommand(uuid(921), instant(120), label = "Устаревшее"),
                )
            }
        }
        assertEquals("Первое изменение", repository.getDimension(original.dimensionId)?.label)
    }

    @Test
    fun `custom dimension create is replay safe by frozen identities`() = runTest {
        val command = CreateWellbeingDimensionCommand(
            dimensionId = uuid(930),
            catalogVersionId = uuid(931),
            label = "Фокус",
            sortOrder = 50,
            options = listOf(
                WellbeingOptionDraft(uuid(932), "Рассеянный", 10),
                WellbeingOptionDraft(uuid(933), "Собранный", 20),
            ),
            createdAt = instant(0),
        )

        val first = repository.create(command)
        val replay = repository.create(command)

        assertEquals(first, replay)
        assertEquals(1, repository.exportSnapshot().versions.size)
    }

    @Test
    fun `option identity cannot be reused by a different dimension on create or update`() = runTest {
        repository.ensureSeeded(instant(0))
        val dimensions = repository.observeDimensions(includeArchived = true).first()
        val overallOptionId = dimensions[0].options.first().optionId
        val beforeCreate = repository.exportSnapshot()

        assertThrows(WellbeingCatalogIdentityCollisionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.create(
                    CreateWellbeingDimensionCommand(
                        dimensionId = uuid(940),
                        catalogVersionId = uuid(941),
                        label = "Фокус",
                        sortOrder = 50,
                        options = listOf(
                            WellbeingOptionDraft(overallOptionId, "Собранный", 10),
                        ),
                        createdAt = instant(60),
                    ),
                )
            }
        }
        assertEquals(beforeCreate, repository.exportSnapshot())

        val mood = dimensions[1]
        assertThrows(WellbeingCatalogIdentityCollisionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.update(
                    UpdateWellbeingDimensionCommand(
                        dimensionId = mood.dimensionId,
                        catalogVersionId = uuid(942),
                        expectedCurrentVersionId = mood.catalogVersionId,
                        label = mood.label,
                        sortOrder = mood.sortOrder,
                        active = mood.active,
                        options = mood.options.map { option ->
                            WellbeingOptionDraft(
                                optionId = option.optionId,
                                label = option.label,
                                sortOrder = option.sortOrder,
                                active = option.active,
                            )
                        } + WellbeingOptionDraft(overallOptionId, "Чужой ID", 60),
                        createdAt = instant(120),
                    ),
                )
            }
        }
        assertEquals(beforeCreate, repository.exportSnapshot())
    }

    @Test
    fun `one catalog command cannot reuse IDs across local namespaces`() = runTest {
        assertThrows(WellbeingCatalogIdentityCollisionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.create(
                    CreateWellbeingDimensionCommand(
                        dimensionId = uuid(950),
                        catalogVersionId = uuid(951),
                        label = "Фокус",
                        sortOrder = 50,
                        options = listOf(WellbeingOptionDraft(uuid(950), "Собранный", 10)),
                        createdAt = instant(0),
                    ),
                )
            }
        }
        assertTrue(repository.exportSnapshot().items.isEmpty())

        repository.ensureSeeded(instant(0))
        val dimension = repository.observeDimensions(includeArchived = true).first().first()
        assertThrows(WellbeingCatalogIdentityCollisionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.update(
                    dimension.updateCommand(
                        versionId = dimension.options.first().optionId,
                        at = instant(60),
                        label = dimension.label,
                    ),
                )
            }
        }
        assertEquals(4, repository.exportSnapshot().versions.size)
    }

    private fun ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingDimension.updateCommand(
        versionId: UUID,
        at: Instant,
        label: String,
    ) = UpdateWellbeingDimensionCommand(
        dimensionId = dimensionId,
        catalogVersionId = versionId,
        expectedCurrentVersionId = catalogVersionId,
        label = label,
        sortOrder = sortOrder,
        active = active,
        options = options.map { option ->
            WellbeingOptionDraft(
                optionId = option.optionId,
                label = option.label,
                sortOrder = option.sortOrder,
                active = option.active,
            )
        },
        createdAt = at,
    )

    private fun instant(seconds: Long): Instant =
        Instant.parse("2026-07-27T06:12:00Z").plusSeconds(seconds)

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
