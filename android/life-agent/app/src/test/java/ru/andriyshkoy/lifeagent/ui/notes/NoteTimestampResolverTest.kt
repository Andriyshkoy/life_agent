package ru.andriyshkoy.lifeagent.ui.notes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.time.TemporalPrecision

class NoteTimestampResolverTest {
    @Test
    fun nowAndRelativeChoicesResolveFromTheSameSuppliedInstant() {
        val now = Instant.parse("2026-07-29T03:00:00Z")
        val zone = ZoneId.of("Asia/Novosibirsk")

        val current = resolveNoteTimestamp(NoteTimestampChoice.Now, now, zone)
            as NoteTimestampResolution.Valid
        val fifteenMinutesAgo = resolveNoteTimestamp(
            NoteTimestampChoice.FifteenMinutesAgo,
            now,
            zone,
        ) as NoteTimestampResolution.Valid
        val oneHourAgo = resolveNoteTimestamp(
            NoteTimestampChoice.OneHourAgo,
            now,
            zone,
        ) as NoteTimestampResolution.Valid

        assertEquals(now, current.value.effectiveAt)
        assertEquals(now.minusSeconds(15 * 60L), fifteenMinutesAgo.value.effectiveAt)
        assertEquals(now.minusSeconds(60 * 60L), oneHourAgo.value.effectiveAt)
        assertEquals("Asia/Novosibirsk", current.value.timezoneId.id)
        assertEquals(TemporalPrecision.EXACT, current.value.precision)
        assertEquals(TemporalPrecision.EXACT, fifteenMinutesAgo.value.precision)
        assertEquals(TemporalPrecision.EXACT, oneHourAgo.value.precision)
    }

    @Test
    fun nonexistentLocalTimeIsRejected() {
        val choice = NoteTimestampChoice.Custom(
            localDateTime = LocalDateTime.of(2026, 3, 29, 2, 30),
            zoneId = "Europe/Berlin",
        )

        val result = resolveNoteTimestamp(
            choice = choice,
            now = Instant.EPOCH,
            defaultZoneId = ZoneId.of("UTC"),
        )

        assertTrue(result is NoteTimestampResolution.Gap)
    }

    @Test
    fun ambiguousLocalTimeRequiresAndThenUsesExplicitOffset() {
        val local = LocalDateTime.of(2026, 10, 25, 2, 30)
        val unresolved = resolveNoteTimestamp(
            choice = NoteTimestampChoice.Custom(local, "Europe/Berlin"),
            now = Instant.EPOCH,
            defaultZoneId = ZoneId.of("UTC"),
        )

        assertTrue(unresolved is NoteTimestampResolution.Overlap)
        val offsets = (unresolved as NoteTimestampResolution.Overlap).offsets

        val resolved = resolveNoteTimestamp(
            choice = NoteTimestampChoice.Custom(
                localDateTime = local,
                zoneId = "Europe/Berlin",
                preferredOffsetSeconds = offsets.last().totalSeconds,
            ),
            now = Instant.EPOCH,
            defaultZoneId = ZoneId.of("UTC"),
        ) as NoteTimestampResolution.Valid

        assertEquals(offsets.last(), resolved.value.offset)
        assertEquals(local, resolved.value.originalLocal)
        assertEquals(TemporalPrecision.MINUTE, resolved.value.precision)
    }
}
