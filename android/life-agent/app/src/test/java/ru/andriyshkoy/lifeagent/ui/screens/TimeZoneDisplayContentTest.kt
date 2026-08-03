package ru.andriyshkoy.lifeagent.ui.screens

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeZoneDisplayContentTest {
    @Test
    fun fixedClockAndZoneProduceDeterministicDeviceTime() {
        val content = resolveTimeZoneDisplayContent(
            clock = Clock.fixed(
                Instant.parse("2026-07-29T03:00:00Z"),
                ZoneOffset.UTC,
            ),
            zoneId = ZoneId.of("Asia/Novosibirsk"),
            locale = Locale.forLanguageTag("ru"),
        )

        assertEquals("Asia/Novosibirsk", content.zoneId)
        assertEquals("UTC+07:00", content.offsetLabel)
        assertEquals("29 июля 2026, 10:00", content.localTimeLabel)
    }

    @Test
    fun nonWholeHourOffsetIsPreserved() {
        val content = resolveTimeZoneDisplayContent(
            clock = Clock.fixed(
                Instant.parse("2026-07-29T03:00:00Z"),
                ZoneOffset.UTC,
            ),
            zoneId = ZoneId.of("Asia/Kathmandu"),
            locale = Locale.forLanguageTag("ru"),
        )

        assertEquals("UTC+05:45", content.offsetLabel)
        assertEquals("29 июля 2026, 08:45", content.localTimeLabel)
    }
}
