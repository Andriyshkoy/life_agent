package ru.andriyshkoy.lifeagent.ui.screens

import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteRecordStatusUi
import ru.andriyshkoy.lifeagent.ui.notes.NoteSummaryUi
import ru.andriyshkoy.lifeagent.ui.wellbeing.LastWellbeingUiState
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingRecordStatusUi
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingSummaryUi

class RecentActivityResolverTest {
    @Test
    fun newestRecordedFactWinsRegardlessOfEffectiveTime() {
        val note = note(
            effectiveAt = Instant.parse("2026-07-29T08:00:00Z"),
            recordedAt = Instant.parse("2026-07-29T09:00:00Z"),
        )
        val wellbeing = wellbeing(
            effectiveAt = Instant.parse("2026-07-29T07:00:00Z"),
            recordedAt = Instant.parse("2026-07-29T10:00:00Z"),
        )

        assertEquals(
            RecentActivityState.Wellbeing(wellbeing),
            resolveRecentActivity(
                LastNoteUiState.Available(note),
                LastWellbeingUiState.Available(wellbeing),
            ),
        )
    }

    @Test
    fun noteWinsRecordedAtTieDeterministically() {
        val recordedAt = Instant.parse("2026-07-29T10:00:00Z")
        val note = note(recordedAt = recordedAt)
        val wellbeing = wellbeing(recordedAt = recordedAt)

        assertEquals(
            RecentActivityState.Note(note),
            resolveRecentActivity(
                LastNoteUiState.Available(note),
                LastWellbeingUiState.Available(wellbeing),
            ),
        )
    }

    private fun note(
        effectiveAt: Instant = Instant.parse("2026-07-29T08:00:00Z"),
        recordedAt: Instant,
    ) = NoteSummaryUi(
        eventId = UUID.randomUUID(),
        revisionId = UUID.randomUUID(),
        text = "Заметка",
        effectiveAt = effectiveAt,
        recordedAt = recordedAt,
        originalLocalDateTime = LocalDateTime.of(2026, 7, 29, 15, 0),
        timezoneId = "Asia/Novosibirsk",
        offsetSeconds = 7 * 60 * 60,
        status = NoteRecordStatusUi.Active,
    )

    private fun wellbeing(
        effectiveAt: Instant = Instant.parse("2026-07-29T08:00:00Z"),
        recordedAt: Instant,
    ) = WellbeingSummaryUi(
        eventId = UUID.randomUUID(),
        revisionId = UUID.randomUUID(),
        values = emptyList(),
        comment = "Самочувствие",
        effectiveAt = effectiveAt,
        recordedAt = recordedAt,
        originalLocalDateTime = LocalDateTime.of(2026, 7, 29, 15, 0),
        timezoneId = "Asia/Novosibirsk",
        offsetSeconds = 7 * 60 * 60,
        status = WellbeingRecordStatusUi.Active,
    )
}
