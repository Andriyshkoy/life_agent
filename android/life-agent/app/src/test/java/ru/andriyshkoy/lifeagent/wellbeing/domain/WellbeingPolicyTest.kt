package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WellbeingPolicyTest {
    @Test
    fun `payload requires an explicit unique dimension selection`() {
        assertThrows(InvalidWellbeingException::class.java) {
            WellbeingPolicy.normalizePayload(emptyList(), null)
        }
        assertThrows(InvalidWellbeingException::class.java) {
            WellbeingPolicy.normalizePayload(
                listOf(value(1, 2), value(1, 3)),
                null,
            )
        }
    }

    @Test
    fun `comment is normalized without inventing a value`() {
        val blank = WellbeingPolicy.normalizePayload(listOf(value(1, 2)), "  \n ")
        val present = WellbeingPolicy.normalizePayload(
            listOf(value(1, 2)),
            "  после прогулки стало легче  ",
        )

        assertNull(blank.comment)
        assertEquals("после прогулки стало легче", present.comment)
    }

    @Test
    fun `comment and labels use code point bounds`() {
        assertThrows(InvalidWellbeingException::class.java) {
            WellbeingPolicy.normalizePayload(
                listOf(value(1, 2)),
                "🙂".repeat(WellbeingPolicy.MAX_COMMENT_CODE_POINTS + 1),
            )
        }
        assertThrows(InvalidWellbeingException::class.java) {
            WellbeingPolicy.normalizePayload(
                listOf(
                    value(1, 2).copy(
                        dimensionLabel = "🙂".repeat(WellbeingPolicy.MAX_LABEL_CODE_POINTS + 1),
                    ),
                ),
                null,
            )
        }
    }

    @Test
    fun `active catalog needs active uniquely named options`() {
        assertThrows(InvalidWellbeingCatalogException::class.java) {
            WellbeingPolicy.validateOptionDrafts(
                listOf(option(1, "Один", active = false)),
                dimensionActive = true,
            )
        }
        assertThrows(InvalidWellbeingCatalogException::class.java) {
            WellbeingPolicy.validateOptionDrafts(
                listOf(option(1, "Один"), option(2, " один ")),
                dimensionActive = true,
            )
        }
    }

    @Test
    fun `catalog labels use the export compatible sixty four code point bound`() {
        assertEquals(
            "🙂".repeat(WellbeingPolicy.MAX_LABEL_CODE_POINTS),
            WellbeingPolicy.normalizeCatalogLabel(
                "🙂".repeat(WellbeingPolicy.MAX_LABEL_CODE_POINTS),
                "Option label",
            ),
        )
        assertThrows(InvalidWellbeingCatalogException::class.java) {
            WellbeingPolicy.normalizeCatalogLabel(
                "🙂".repeat(WellbeingPolicy.MAX_LABEL_CODE_POINTS + 1),
                "Option label",
            )
        }
    }

    private fun value(dimension: Int, option: Int) = WellbeingValueSnapshot(
        dimensionId = uuid(dimension),
        dimensionVersion = 1,
        dimensionLabel = "Состояние",
        optionId = uuid(option),
        optionVersion = 1,
        optionLabel = "Нормально",
        optionSortOrder = 10,
    )

    private fun option(id: Int, label: String, active: Boolean = true) =
        WellbeingOptionDraft(
            optionId = uuid(id),
            label = label,
            sortOrder = id,
            active = active,
        )

    private fun uuid(value: Int): UUID =
        UUID.fromString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
