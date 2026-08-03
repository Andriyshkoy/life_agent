package ru.andriyshkoy.lifeagent.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class TemporalPrecision(
    val storageValue: String,
) {
    EXACT("exact"),
    MINUTE("minute"),
    HOUR("hour"),
    PART_OF_DAY("part_of_day"),
    DATE("date"),
    APPROXIMATE("approximate"),
    UNKNOWN("unknown"),
}

data class ResolvedPointTime(
    val effectiveAt: Instant,
    val originalLocal: LocalDateTime,
    val timezoneId: ZoneId,
    val offset: ZoneOffset,
    val precision: TemporalPrecision,
    val localDate: LocalDate,
) {
    init {
        require(originalLocal.toInstant(offset) == effectiveAt) {
            "UTC, local time, and offset must identify the same instant"
        }
        require(timezoneId.rules.getValidOffsets(originalLocal).contains(offset)) {
            "Offset is not valid for local time in $timezoneId"
        }
        require(localDate == originalLocal.toLocalDate()) {
            "Local date must match original local time"
        }
        require(
            precision == TemporalPrecision.EXACT ||
                precision == TemporalPrecision.MINUTE ||
                precision == TemporalPrecision.HOUR,
        ) {
            "A point timestamp requires exact, minute, or hour precision"
        }
    }

    fun toOffsetDateTime(): OffsetDateTime = OffsetDateTime.of(originalLocal, offset)
}

class InvalidLocalTimeException(
    message: String,
) : IllegalArgumentException(message)

object PointTimeResolver {
    fun resolveInstant(
        instant: Instant,
        timezoneId: ZoneId,
        precision: TemporalPrecision = TemporalPrecision.MINUTE,
    ): ResolvedPointTime {
        val zoned = instant.atZone(timezoneId)
        return ResolvedPointTime(
            effectiveAt = instant,
            originalLocal = zoned.toLocalDateTime(),
            timezoneId = timezoneId,
            offset = zoned.offset,
            precision = precision,
            localDate = zoned.toLocalDate(),
        )
    }

    fun resolveChosen(
        local: LocalDateTime,
        timezoneId: ZoneId,
        preferredOffset: ZoneOffset? = null,
        precision: TemporalPrecision = TemporalPrecision.MINUTE,
    ): ResolvedPointTime {
        val validOffsets = timezoneId.rules.getValidOffsets(local)
        val offset = when {
            validOffsets.isEmpty() -> throw InvalidLocalTimeException(
                "$local does not exist in $timezoneId because of an offset transition",
            )

            validOffsets.size == 1 -> validOffsets.single()
            preferredOffset != null && preferredOffset in validOffsets -> preferredOffset
            else -> throw InvalidLocalTimeException(
                "$local is ambiguous in $timezoneId; an explicit offset is required",
            )
        }
        return ResolvedPointTime(
            effectiveAt = local.toInstant(offset),
            originalLocal = local,
            timezoneId = timezoneId,
            offset = offset,
            precision = precision,
            localDate = local.toLocalDate(),
        )
    }
}
