package ru.andriyshkoy.lifeagent.ui.notes

import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import ru.andriyshkoy.lifeagent.notes.domain.NoteTextPolicy
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampUiState

const val NOTE_TEXT_MAX_LENGTH = NoteTextPolicy.MAX_CODE_POINTS

enum class NoteEditorMode {
    Create,
    Correct,
}

enum class NoteTextError {
    Empty,
    TooLong,
}

data class NoteEditorUiState(
    val mode: NoteEditorMode = NoteEditorMode.Create,
    val text: String = "",
    val timestamp: EventTimestampUiState = EventTimestampUiState(),
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
    val recordedAt: Instant = effectiveAt,
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

    data class SelectTimestamp(val choice: EventTimestampChoice) : NoteAction

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

fun noteTextCodePointCount(text: String): Int = text.codePointCount(0, text.length)
