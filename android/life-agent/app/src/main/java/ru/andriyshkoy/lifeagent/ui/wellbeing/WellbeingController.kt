package ru.andriyshkoy.lifeagent.ui.wellbeing

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface WellbeingController {
    val uiState: StateFlow<WellbeingUiState>

    fun dispatch(action: WellbeingAction)
}

class UnavailableWellbeingController(
    initialLastCommitted: LastWellbeingUiState = LastWellbeingUiState.Empty,
) : WellbeingController {
    private val mutableState = MutableStateFlow(
        WellbeingUiState(
            lastCommitted = initialLastCommitted,
            catalog = WellbeingCatalogUiState.Unavailable,
            persistenceAvailable = false,
        ),
    )
    override val uiState: StateFlow<WellbeingUiState> = mutableState.asStateFlow()

    override fun dispatch(action: WellbeingAction) {
        when (action) {
            WellbeingAction.StartCreate,
            is WellbeingAction.StartCorrection,
            WellbeingAction.RetryEditorLoad,
            -> showUnavailable()

            WellbeingAction.RetryLastCommitted,
            WellbeingAction.RetryCatalog,
            is WellbeingAction.SelectOption,
            is WellbeingAction.ClearDimension,
            is WellbeingAction.CommentChanged,
            WellbeingAction.OpenTimestampPicker,
            WellbeingAction.DismissTimestampPicker,
            is WellbeingAction.SelectTimestamp,
            is WellbeingAction.SelectOverlapOffset,
            WellbeingAction.Save,
            WellbeingAction.RetrySave,
            is WellbeingAction.RequestUndo,
            WellbeingAction.ConfirmUndo,
            WellbeingAction.ConfirmDiscard,
            -> Unit

            WellbeingAction.ExitRequested -> exitUnavailable()

            WellbeingAction.DismissDialog -> {
                mutableState.value = mutableState.value.copy(dialog = null)
            }

            WellbeingAction.CompletionConsumed -> {
                mutableState.value = mutableState.value.copy(completion = null)
            }
        }
    }

    private fun showUnavailable() {
        mutableState.value = mutableState.value.copy(
            editor = null,
            editorLoading = false,
            editorLoadError = "Зашифрованное хранилище недоступно",
        )
    }

    private fun exitUnavailable() {
        mutableState.value = mutableState.value.copy(
            editor = null,
            editorLoadError = null,
            dialog = null,
            completion = WellbeingCompletionUi(
                id = UUID.randomUUID(),
                kind = WellbeingCompletionKind.DraftDiscarded,
                message = null,
            ),
        )
    }
}
