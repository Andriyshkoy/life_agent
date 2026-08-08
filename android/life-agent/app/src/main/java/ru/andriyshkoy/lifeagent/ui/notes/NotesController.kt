package ru.andriyshkoy.lifeagent.ui.notes

import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampResolution
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampUiState
import ru.andriyshkoy.lifeagent.ui.time.errorMessage
import ru.andriyshkoy.lifeagent.ui.time.resolveEventTimestamp

interface NotesController {
    val uiState: StateFlow<NotesUiState>

    fun dispatch(action: NoteAction)
}

class PreviewNotesController(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val defaultZoneId: ZoneId = ZoneId.systemDefault(),
    initialLastCommitted: LastNoteUiState = LastNoteUiState.Empty,
) : NotesController {
    private val mutableState = MutableStateFlow(
        NotesUiState(
            lastCommitted = initialLastCommitted,
            persistenceAvailable = false,
        ),
    )
    override val uiState: StateFlow<NotesUiState> = mutableState.asStateFlow()

    override fun dispatch(action: NoteAction) {
        when (action) {
            NoteAction.StartCreate -> {
                if (mutableState.value.editor == null) {
                    mutableState.value = mutableState.value.copy(
                        editor = NoteEditorUiState(
                            timestamp = EventTimestampUiState(
                                defaultTimezoneId = defaultZoneId.id,
                            ),
                            persistenceAvailable = false,
                        ),
                        editorLoading = false,
                    )
                }
            }

            is NoteAction.StartCorrection -> Unit
            NoteAction.RetryLastCommitted -> Unit
            is NoteAction.TextChanged -> updateEditor {
                it.copy(
                    text = action.value,
                    textError = when {
                        noteTextCodePointCount(action.value) > NOTE_TEXT_MAX_LENGTH ->
                            NoteTextError.TooLong
                        action.value.isNotEmpty() -> null
                        else -> it.textError
                    },
                    formError = null,
                    retryAvailable = false,
                )
            }

            NoteAction.OpenTimestampPicker -> updateEditor {
                it.copy(timestamp = it.timestamp.copy(pickerVisible = true))
            }

            NoteAction.DismissTimestampPicker -> updateEditor {
                it.copy(timestamp = it.timestamp.copy(pickerVisible = false))
            }

            is NoteAction.SelectTimestamp -> selectTimestamp(action.choice)
            is NoteAction.SelectOverlapOffset -> selectOverlapOffset(action.offsetSeconds)
            NoteAction.Save,
            NoteAction.RetrySave,
            -> previewSave()

            NoteAction.ExitRequested -> requestExit()
            NoteAction.ConfirmDiscard -> discardDraft()
            is NoteAction.RequestUndo,
            NoteAction.ConfirmUndo,
            -> Unit

            NoteAction.DismissDialog -> {
                mutableState.value = mutableState.value.copy(dialog = null)
            }

            NoteAction.CompletionConsumed -> {
                mutableState.value = mutableState.value.copy(completion = null)
            }
        }
    }

    private fun selectTimestamp(choice: EventTimestampChoice) {
        val resolved = resolveEventTimestamp(choice, clock.instant(), defaultZoneId)
        updateEditor { editor ->
            editor.copy(
                timestamp = editor.timestamp.copy(
                    choice = choice,
                    defaultTimezoneId = defaultZoneId.id,
                    pickerVisible = false,
                    error = resolved.errorMessage(),
                    overlapOffsetsSeconds = (resolved as? EventTimestampResolution.Overlap)
                        ?.offsets
                        ?.map { it.totalSeconds }
                        .orEmpty(),
                ),
                retryAvailable = false,
            )
        }
    }

    private fun selectOverlapOffset(offsetSeconds: Int) {
        val editor = mutableState.value.editor ?: return
        val choice = editor.timestamp.choice as? EventTimestampChoice.Custom ?: return
        selectTimestamp(choice.copy(preferredOffsetSeconds = offsetSeconds))
    }

    private fun previewSave() {
        val editor = mutableState.value.editor ?: return
        val textError = when {
            editor.text.isBlank() -> NoteTextError.Empty
            noteTextCodePointCount(editor.text) > NOTE_TEXT_MAX_LENGTH -> NoteTextError.TooLong
            else -> null
        }
        if (textError != null || editor.timestamp.error != null) {
            mutableState.value = mutableState.value.copy(
                editor = editor.copy(textError = textError),
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            editor = null,
            completion = NoteCompletionUi(
                id = UUID.randomUUID(),
                kind = NoteCompletionKind.PreviewOnly,
                message = "Предпросмотр заметки: данные не сохранены",
            ),
        )
    }

    private fun requestExit() {
        val editor = mutableState.value.editor ?: return
        if (editor.text.isBlank()) {
            discardDraft()
        } else {
            mutableState.value = mutableState.value.copy(dialog = NoteDialogUi.DiscardDraft)
        }
    }

    private fun discardDraft() {
        mutableState.value = mutableState.value.copy(
            editor = null,
            dialog = null,
            completion = NoteCompletionUi(
                id = UUID.randomUUID(),
                kind = NoteCompletionKind.DraftDiscarded,
                message = null,
            ),
        )
    }

    private fun updateEditor(transform: (NoteEditorUiState) -> NoteEditorUiState) {
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(editor = transform(editor))
    }
}
