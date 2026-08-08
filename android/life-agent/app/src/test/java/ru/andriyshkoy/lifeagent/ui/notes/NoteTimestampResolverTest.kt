package ru.andriyshkoy.lifeagent.ui.notes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.core.time.TemporalPrecision
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampResolution
import ru.andriyshkoy.lifeagent.ui.time.resolveEventTimestamp

class EventTimestampResolverTest {
    @Test
    fun nowAndRelativeChoicesResolveFromTheSameSuppliedInstant() {
        val now = Instant.parse("2026-07-29T03:00:00Z")
        val zone = ZoneId.of("Asia/Novosibirsk")

        val current = resolveEventTimestamp(EventTimestampChoice.Now, now, zone)
            as EventTimestampResolution.Valid
        val fifteenMinutesAgo = resolveEventTimestamp(
            EventTimestampChoice.FifteenMinutesAgo,
            now,
            zone,
        ) as EventTimestampResolution.Valid
        val oneHourAgo = resolveEventTimestamp(
            EventTimestampChoice.OneHourAgo,
            now,
            zone,
        ) as EventTimestampResolution.Valid

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
        val choice = EventTimestampChoice.Custom(
            localDateTime = LocalDateTime.of(2026, 3, 29, 2, 30),
            zoneId = "Europe/Berlin",
        )

        val result = resolveEventTimestamp(
            choice = choice,
            now = Instant.EPOCH,
            defaultZoneId = ZoneId.of("UTC"),
        )

        assertTrue(result is EventTimestampResolution.Gap)
    }

    @Test
    fun ambiguousLocalTimeRequiresAndThenUsesExplicitOffset() {
        val local = LocalDateTime.of(2026, 10, 25, 2, 30)
        val unresolved = resolveEventTimestamp(
            choice = EventTimestampChoice.Custom(local, "Europe/Berlin"),
            now = Instant.EPOCH,
            defaultZoneId = ZoneId.of("UTC"),
        )

        assertTrue(unresolved is EventTimestampResolution.Overlap)
        val offsets = (unresolved as EventTimestampResolution.Overlap).offsets

        val resolved = resolveEventTimestamp(
            choice = EventTimestampChoice.Custom(
                localDateTime = local,
                zoneId = "Europe/Berlin",
                preferredOffsetSeconds = offsets.last().totalSeconds,
            ),
            now = Instant.EPOCH,
            defaultZoneId = ZoneId.of("UTC"),
        ) as EventTimestampResolution.Valid

        assertEquals(offsets.last(), resolved.value.offset)
        assertEquals(local, resolved.value.originalLocal)
        assertEquals(TemporalPrecision.MINUTE, resolved.value.precision)
    }
}
