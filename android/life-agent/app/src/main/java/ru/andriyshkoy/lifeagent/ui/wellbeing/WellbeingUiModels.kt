package ru.andriyshkoy.lifeagent.ui.wellbeing

import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampUiState
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingPolicy
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

const val WELLBEING_COMMENT_MAX_LENGTH = WellbeingPolicy.MAX_COMMENT_CODE_POINTS

enum class WellbeingEditorMode {
    Create,
    Correct,
}

data class WellbeingOptionUi(
    val optionId: UUID,
    val label: String,
    val sortOrder: Int,
    val active: Boolean = true,
)

data class WellbeingDimensionUi(
    val dimensionId: UUID,
    val label: String,
    val sortOrder: Int,
    val options: List<WellbeingOptionUi>,
    val selectedSnapshot: WellbeingValueSnapshot? = null,
    val active: Boolean = true,
) {
    val selectedOptionId: UUID?
        get() = selectedSnapshot?.optionId
}

enum class WellbeingCommentError {
    TooLong,
}

data class WellbeingEditorUiState(
    val mode: WellbeingEditorMode = WellbeingEditorMode.Create,
    val dimensions: List<WellbeingDimensionUi> = emptyList(),
    val comment: String = "",
    val timestamp: EventTimestampUiState = EventTimestampUiState(),
    val commentError: WellbeingCommentError? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
    val retryAvailable: Boolean = false,
    val persistenceAvailable: Boolean = true,
    val isDirty: Boolean = false,
) {
    val selectedCount: Int
        get() = dimensions.count { it.selectedSnapshot != null }

    val canSave: Boolean
        get() = selectedCount > 0 &&
            wellbeingCodePointCount(comment) <= WELLBEING_COMMENT_MAX_LENGTH &&
            timestamp.error == null &&
            timestamp.overlapOffsetsSeconds.isEmpty() &&
            !isSubmitting &&
            persistenceAvailable
}

enum class WellbeingRecordStatusUi {
    Active,
    Retracted,
}

data class WellbeingSummaryUi(
    val eventId: UUID,
    val revisionId: UUID,
    val values: List<WellbeingValueSnapshot>,
    val comment: String?,
    val effectiveAt: Instant,
    val recordedAt: Instant,
    val originalLocalDateTime: LocalDateTime,
    val timezoneId: String,
    val offsetSeconds: Int,
    val status: WellbeingRecordStatusUi,
) {
    fun timestampLabel(locale: Locale = Locale.getDefault()): String =
        originalLocalDateTime.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale),
        )
}

sealed interface LastWellbeingUiState {
    data object Loading : LastWellbeingUiState

    data object Empty : LastWellbeingUiState

    data class Available(val wellbeing: WellbeingSummaryUi) : LastWellbeingUiState

    data object Failed : LastWellbeingUiState
}

sealed interface WellbeingCatalogUiState {
    data object Loading : WellbeingCatalogUiState

    data class Available(val dimensions: List<WellbeingDimensionUi>) : WellbeingCatalogUiState

    data object Failed : WellbeingCatalogUiState

    data object Unavailable : WellbeingCatalogUiState
}

sealed interface WellbeingDialogUi {
    data object DiscardDraft : WellbeingDialogUi

    data class ConfirmUndo(val wellbeing: WellbeingSummaryUi) : WellbeingDialogUi
}

enum class WellbeingCompletionKind {
    Created,
    Corrected,
    Retracted,
    DraftDiscarded,
}

data class WellbeingCompletionUi(
    val id: UUID,
    val kind: WellbeingCompletionKind,
    val message: String?,
)

data class WellbeingUiState(
    val editor: WellbeingEditorUiState? = null,
    val editorLoading: Boolean = false,
    val editorLoadError: String? = null,
    val lastCommitted: LastWellbeingUiState = LastWellbeingUiState.Loading,
    val catalog: WellbeingCatalogUiState = WellbeingCatalogUiState.Loading,
    val dialog: WellbeingDialogUi? = null,
    val completion: WellbeingCompletionUi? = null,
    val persistenceAvailable: Boolean = true,
    val mutationInProgress: Boolean = false,
    val undoRetryAvailable: Boolean = false,
)

sealed interface WellbeingAction {
    data object StartCreate : WellbeingAction

    data class StartCorrection(val eventId: UUID) : WellbeingAction

    data object RetryEditorLoad : WellbeingAction

    data object RetryLastCommitted : WellbeingAction

    data object RetryCatalog : WellbeingAction

    data class SelectOption(
        val dimensionId: UUID,
        val optionId: UUID,
    ) : WellbeingAction

    data class ClearDimension(val dimensionId: UUID) : WellbeingAction

    data class CommentChanged(val value: String) : WellbeingAction

    data object OpenTimestampPicker : WellbeingAction

    data object DismissTimestampPicker : WellbeingAction

    data class SelectTimestamp(val choice: EventTimestampChoice) : WellbeingAction

    data class SelectOverlapOffset(val offsetSeconds: Int) : WellbeingAction

    data object Save : WellbeingAction

    data object RetrySave : WellbeingAction

    data object ExitRequested : WellbeingAction

    data object ConfirmDiscard : WellbeingAction

    data class RequestUndo(val eventId: UUID) : WellbeingAction

    data object ConfirmUndo : WellbeingAction

    data object DismissDialog : WellbeingAction

    data object CompletionConsumed : WellbeingAction
}

fun wellbeingCodePointCount(text: String): Int = text.codePointCount(0, text.length)
