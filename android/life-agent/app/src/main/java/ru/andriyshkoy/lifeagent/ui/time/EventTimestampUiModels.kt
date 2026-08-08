package ru.andriyshkoy.lifeagent.ui.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.core.time.TemporalPrecision

sealed interface EventTimestampChoice {
    data object Now : EventTimestampChoice

    data object FifteenMinutesAgo : EventTimestampChoice

    data object OneHourAgo : EventTimestampChoice

    data class Custom(
        val localDateTime: LocalDateTime,
        val zoneId: String,
        val preferredOffsetSeconds: Int? = null,
    ) : EventTimestampChoice
}

sealed interface EventTimestampResolution {
    data class Valid(
        val value: ResolvedPointTime,
        val sourceExpression: String,
    ) : EventTimestampResolution

    data class Gap(
        val localDateTime: LocalDateTime,
        val zoneId: String,
    ) : EventTimestampResolution

    data class Overlap(
        val localDateTime: LocalDateTime,
        val zoneId: String,
        val offsets: List<ZoneOffset>,
    ) : EventTimestampResolution

    data class InvalidZone(val zoneId: String) : EventTimestampResolution
}

data class EventTimestampUiState(
    val choice: EventTimestampChoice = EventTimestampChoice.Now,
    val defaultTimezoneId: String = ZoneId.systemDefault().id,
    val pickerVisible: Boolean = false,
    val error: String? = null,
    val overlapOffsetsSeconds: List<Int> = emptyList(),
) {
    fun displayValue(locale: Locale = Locale.getDefault()): String = when (val value = choice) {
        EventTimestampChoice.Now -> "Сейчас"
        EventTimestampChoice.FifteenMinutesAgo -> "15 минут назад"
        EventTimestampChoice.OneHourAgo -> "1 час назад"
        is EventTimestampChoice.Custom -> value.localDateTime.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale),
        )
    }

    fun timezonePreview(): String = when (val value = choice) {
        EventTimestampChoice.Now,
        EventTimestampChoice.FifteenMinutesAgo,
        EventTimestampChoice.OneHourAgo,
        -> defaultTimezoneId

        is EventTimestampChoice.Custom -> value.zoneId
    }
}

fun resolveEventTimestamp(
    choice: EventTimestampChoice,
    now: Instant,
    defaultZoneId: ZoneId,
): EventTimestampResolution {
    val (instant, expression) = when (choice) {
        EventTimestampChoice.Now -> now to "now"
        EventTimestampChoice.FifteenMinutesAgo ->
            now.minusSeconds(15 * 60L) to "15_minutes_ago"

        EventTimestampChoice.OneHourAgo -> now.minusSeconds(60 * 60L) to "1_hour_ago"
        is EventTimestampChoice.Custom -> null to "chosen_local_datetime"
    }

    if (instant != null) {
        return EventTimestampResolution.Valid(
            value = PointTimeResolver.resolveInstant(
                instant = instant,
                timezoneId = defaultZoneId,
                precision = TemporalPrecision.EXACT,
            ),
            sourceExpression = expression,
        )
    }

    choice as EventTimestampChoice.Custom
    val zone = try {
        ZoneId.of(choice.zoneId)
    } catch (_: Exception) {
        return EventTimestampResolution.InvalidZone(choice.zoneId)
    }
    val offsets = zone.rules.getValidOffsets(choice.localDateTime)
    if (offsets.isEmpty()) {
        return EventTimestampResolution.Gap(choice.localDateTime, zone.id)
    }
    if (offsets.size > 1 && choice.preferredOffsetSeconds == null) {
        return EventTimestampResolution.Overlap(choice.localDateTime, zone.id, offsets)
    }
    val offset = offsets.firstOrNull { it.totalSeconds == choice.preferredOffsetSeconds }
        ?: offsets.singleOrNull()
        ?: return EventTimestampResolution.Overlap(choice.localDateTime, zone.id, offsets)
    return EventTimestampResolution.Valid(
        value = PointTimeResolver.resolveChosen(
            local = choice.localDateTime,
            timezoneId = zone,
            preferredOffset = offset,
            precision = TemporalPrecision.MINUTE,
        ),
        sourceExpression = expression,
    )
}

fun EventTimestampResolution.errorMessage(): String? = when (this) {
    is EventTimestampResolution.Valid -> null
    is EventTimestampResolution.Gap ->
        "Такого местного времени нет из-за смены часового пояса"

    is EventTimestampResolution.Overlap -> "Уточни смещение времени"
    is EventTimestampResolution.InvalidZone -> "Неизвестный часовой пояс"
}

fun formatUtcOffset(offsetSeconds: Int): String {
    val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
    return if (offset.totalSeconds == 0) "UTC" else "UTC${offset.id}"
}
