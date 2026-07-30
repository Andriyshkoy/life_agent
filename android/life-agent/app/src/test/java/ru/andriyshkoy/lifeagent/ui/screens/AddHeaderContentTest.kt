package ru.andriyshkoy.lifeagent.ui.screens

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AddHeaderContentTest {
    @Test
    fun dateAndGreetingUseInjectedInstantZoneAndRussianLocale() {
        val content = resolveAddHeaderContent(
            clock = Clock.fixed(
                Instant.parse("2026-07-29T03:00:00Z"),
                ZoneOffset.UTC,
            ),
            zoneId = ZoneId.of("Asia/Novosibirsk"),
            locale = Locale.forLanguageTag("ru"),
        )

        assertEquals("29 июля · среда", content.dateLabel)
        assertEquals("Доброе утро", content.greeting)
    }

    @Test
    fun greetingChangesAtLocalDayPartBoundaries() {
        val expectedByHour = mapOf(
            4 to "Доброй ночи",
            5 to "Доброе утро",
            11 to "Доброе утро",
            12 to "Добрый день",
            17 to "Добрый день",
            18 to "Добрый вечер",
            22 to "Добрый вечер",
            23 to "Доброй ночи",
        )

        expectedByHour.forEach { (hour, expected) ->
            val content = resolveAddHeaderContent(
                clock = Clock.fixed(
                    Instant.parse("2026-07-29T${hour.toString().padStart(2, '0')}:00:00Z"),
                    ZoneOffset.UTC,
                ),
                zoneId = ZoneId.of("UTC"),
            )

            assertEquals("hour=$hour", expected, content.greeting)
        }
    }
}
