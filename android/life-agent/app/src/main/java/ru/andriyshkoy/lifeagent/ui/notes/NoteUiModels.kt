package ru.andriyshkoy.lifeagent.ui.notes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.core.time.ResolvedPointTime
import ru.andriyshkoy.lifeagent.core.time.TemporalPrecision
import ru.andriyshkoy.lifeagent.notes.domain.NoteTextPolicy

const val NOTE_TEXT_MAX_LENGTH = NoteTextPolicy.MAX_CODE_POINTS

enum class NoteEditorMode {
    Create,
    Correct,
}

sealed interface NoteTimestampChoice {
    data object Now : NoteTimestampChoice

    data object FifteenMinutesAgo : NoteTimestampChoice

    data object OneHourAgo : NoteTimestampChoice

    data class Custom(
        val localDateTime: LocalDateTime,
        val zoneId: String,
        val preferredOffsetSeconds: Int? = null,
    ) : NoteTimestampChoice
}

sealed interface NoteTimestampResolution {
    data class Valid(
        val value: ResolvedPointTime,
        val sourceExpression: String,
    ) : NoteTimestampResolution

    data class Gap(
        val localDateTime: LocalDateTime,
        val zoneId: String,
    ) : NoteTimestampResolution

    data class Overlap(
        val localDateTime: LocalDateTime,
        val zoneId: String,
        val offsets: List<ZoneOffset>,
    ) : NoteTimestampResolution

    data class InvalidZone(val zoneId: String) : NoteTimestampResolution
}

data class NoteTimestampUiState(
    val choice: NoteTimestampChoice = NoteTimestampChoice.Now,
    val defaultTimezoneId: String = ZoneId.systemDefault().id,
    val pickerVisible: Boolean = false,
    val error: String? = null,
    val overlapOffsetsSeconds: List<Int> = emptyList(),
) {
    fun displayValue(locale: Locale = Locale.getDefault()): String = when (val value = choice) {
        NoteTimestampChoice.Now -> "Сейчас"
        NoteTimestampChoice.FifteenMinutesAgo -> "15 минут назад"
        NoteTimestampChoice.OneHourAgo -> "1 час назад"
        is NoteTimestampChoice.Custom -> value.localDateTime.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale),
        )
    }

    fun timezonePreview(): String = when (val value = choice) {
        NoteTimestampChoice.Now,
        NoteTimestampChoice.FifteenMinutesAgo,
        NoteTimestampChoice.OneHourAgo,
        -> defaultTimezoneId

        is NoteTimestampChoice.Custom -> value.zoneId
    }
}

enum class NoteTextError {
    Empty,
    TooLong,
}

data class NoteEditorUiState(
    val mode: NoteEditorMode = NoteEditorMode.Create,
    val text: String = "",
    val timestamp: NoteTimestampUiState = NoteTimestampUiState(),
    val textError: NoteTextError? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
    val retryAvailable: Boolean = false,
    val persistenceAvailable: Boolean = true,
) {
    val canSave: Boolean
        get() = text.isNotBlank() &&
            noteTextCodePointCount(text) <= NOTE_TEXT_MAX_LENGTH &&
            timestamp.error == null &&
            timestamp.overlapOffsetsSeconds.isEmpty() &&
            !isSubmitting
}

enum class NoteRecordStatusUi {
    Active,
    Retracted,
}

data class NoteSummaryUi(
    val eventId: UUID,
    val revisionId: UUID,
    val text: String,
    val effectiveAt: Instant,
    val originalLocalDateTime: LocalDateTime,
    val timezoneId: String,
    val offsetSeconds: Int,
    val status: NoteRecordStatusUi,
) {
    fun timestampLabel(locale: Locale = Locale.getDefault()): String =
        originalLocalDateTime.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale),
        )
}

sealed interface LastNoteUiState {
    data object Loading : LastNoteUiState

    data object Empty : LastNoteUiState

    data class Available(val note: NoteSummaryUi) : LastNoteUiState

    data object Failed : LastNoteUiState
}

sealed interface NoteDialogUi {
    data object DiscardDraft : NoteDialogUi

    data class ConfirmUndo(val note: NoteSummaryUi) : NoteDialogUi
}

enum class NoteCompletionKind {
    Created,
    Corrected,
    Retracted,
    DraftDiscarded,
    PreviewOnly,
}

data class NoteCompletionUi(
    val id: UUID,
    val kind: NoteCompletionKind,
    val message: String?,
)

data class NotesUiState(
    val editor: NoteEditorUiState? = null,
    val editorLoading: Boolean = false,
    val lastCommitted: LastNoteUiState = LastNoteUiState.Loading,
    val dialog: NoteDialogUi? = null,
    val completion: NoteCompletionUi? = null,
    val persistenceAvailable: Boolean = true,
    val mutationInProgress: Boolean = false,
    val undoRetryAvailable: Boolean = false,
)

sealed interface NoteAction {
    data object StartCreate : NoteAction

    data class StartCorrection(val eventId: UUID) : NoteAction

    data object RetryLastCommitted : NoteAction

    data class TextChanged(val value: String) : NoteAction

    data object OpenTimestampPicker : NoteAction

    data object DismissTimestampPicker : NoteAction

    data class SelectTimestamp(val choice: NoteTimestampChoice) : NoteAction

    data class SelectOverlapOffset(val offsetSeconds: Int) : NoteAction

    data object Save : NoteAction

    data object RetrySave : NoteAction

    data object ExitRequested : NoteAction

    data object ConfirmDiscard : NoteAction

    data class RequestUndo(val eventId: UUID) : NoteAction

    data object ConfirmUndo : NoteAction

    data object DismissDialog : NoteAction

    data object CompletionConsumed : NoteAction
}

fun resolveNoteTimestamp(
    choice: NoteTimestampChoice,
    now: Instant,
    defaultZoneId: ZoneId,
): NoteTimestampResolution {
    val (instant, expression) = when (choice) {
        NoteTimestampChoice.Now -> now to "now"
        NoteTimestampChoice.FifteenMinutesAgo -> now.minusSeconds(15 * 60L) to "15_minutes_ago"
        NoteTimestampChoice.OneHourAgo -> now.minusSeconds(60 * 60L) to "1_hour_ago"
        is NoteTimestampChoice.Custom -> null to "chosen_local_datetime"
    }

    if (instant != null) {
        return NoteTimestampResolution.Valid(
            value = PointTimeResolver.resolveInstant(
                instant = instant,
                timezoneId = defaultZoneId,
                precision = TemporalPrecision.EXACT,
            ),
            sourceExpression = expression,
        )
    }

    choice as NoteTimestampChoice.Custom
    val zone = try {
        ZoneId.of(choice.zoneId)
    } catch (_: Exception) {
        return NoteTimestampResolution.InvalidZone(choice.zoneId)
    }
    val offsets = zone.rules.getValidOffsets(choice.localDateTime)
    if (offsets.isEmpty()) {
        return NoteTimestampResolution.Gap(choice.localDateTime, zone.id)
    }
    if (offsets.size > 1 && choice.preferredOffsetSeconds == null) {
        return NoteTimestampResolution.Overlap(choice.localDateTime, zone.id, offsets)
    }
    val offset = offsets.firstOrNull { it.totalSeconds == choice.preferredOffsetSeconds }
        ?: offsets.singleOrNull()
        ?: return NoteTimestampResolution.Overlap(choice.localDateTime, zone.id, offsets)
    return NoteTimestampResolution.Valid(
        value = PointTimeResolver.resolveChosen(
            local = choice.localDateTime,
            timezoneId = zone,
            preferredOffset = offset,
            precision = TemporalPrecision.MINUTE,
        ),
        sourceExpression = expression,
    )
}

fun formatUtcOffset(offsetSeconds: Int): String {
    val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
    return if (offset.totalSeconds == 0) "UTC" else "UTC${offset.id}"
}

fun noteTextCodePointCount(text: String): Int = text.codePointCount(0, text.length)
