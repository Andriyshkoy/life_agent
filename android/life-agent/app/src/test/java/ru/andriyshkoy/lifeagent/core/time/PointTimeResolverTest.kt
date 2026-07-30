package ru.andriyshkoy.lifeagent.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class PointTimeResolverTest {
    @Test
    fun `instant keeps UTC local timezone and offset consistent`() {
        val resolved = PointTimeResolver.resolveInstant(
            instant = Instant.parse("2026-07-27T06:12:00Z"),
            timezoneId = ZoneId.of("Asia/Novosibirsk"),
        )

        assertEquals(LocalDateTime.parse("2026-07-27T13:12:00"), resolved.originalLocal)
        assertEquals(ZoneOffset.ofHours(7), resolved.offset)
        assertEquals("2026-07-27", resolved.localDate.toString())
    }

    @Test
    fun `nonexistent DST local time is rejected`() {
        assertThrows(InvalidLocalTimeException::class.java) {
            PointTimeResolver.resolveChosen(
                local = LocalDateTime.parse("2026-03-29T02:30:00"),
                timezoneId = ZoneId.of("Europe/Berlin"),
            )
        }
    }

    @Test
    fun `ambiguous DST local time requires and preserves explicit offset`() {
        val local = LocalDateTime.parse("2026-10-25T02:30:00")
        val zone = ZoneId.of("Europe/Berlin")

        assertThrows(InvalidLocalTimeException::class.java) {
            PointTimeResolver.resolveChosen(local, zone)
        }

        val resolved = PointTimeResolver.resolveChosen(
            local = local,
            timezoneId = zone,
            preferredOffset = ZoneOffset.ofHours(1),
        )
        assertEquals(ZoneOffset.ofHours(1), resolved.offset)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), resolved.effectiveAt)
    }
}
