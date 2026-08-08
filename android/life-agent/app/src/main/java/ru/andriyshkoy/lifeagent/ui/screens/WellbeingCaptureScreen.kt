package ru.andriyshkoy.lifeagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.andriyshkoy.lifeagent.ui.components.SelectablePill
import ru.andriyshkoy.lifeagent.ui.components.SectionTitle
import ru.andriyshkoy.lifeagent.ui.components.TimestampSelector
import ru.andriyshkoy.lifeagent.ui.time.formatUtcOffset
import ru.andriyshkoy.lifeagent.ui.wellbeing.WELLBEING_COMMENT_MAX_LENGTH
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingAction
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingCommentError
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingEditorMode
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingEditorUiState
import ru.andriyshkoy.lifeagent.ui.wellbeing.wellbeingCodePointCount

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WellbeingCaptureScreen(
    state: WellbeingEditorUiState,
    onAction: (WellbeingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onAction(WellbeingAction.ExitRequested) }

    CaptureScaffold(
        title = if (state.mode == WellbeingEditorMode.Create) {
            "Самочувствие"
        } else {
            "Исправить самочувствие"
        },
        saveLabel = when {
            state.isSubmitting -> "Сохраняем"
            state.retryAvailable -> "Повторить сохранение"
            state.mode == WellbeingEditorMode.Correct -> "Сохранить исправление"
            else -> "Записать самочувствие"
        },
        saveEnabled = state.canSave || state.retryAvailable,
        onBack = { onAction(WellbeingAction.ExitRequested) },
        onSave = {
            onAction(
                if (state.retryAvailable) WellbeingAction.RetrySave else WellbeingAction.Save,
            )
        },
        onCancel = { onAction(WellbeingAction.ExitRequested) },
        saving = state.isSubmitting,
        modifier = modifier.testTag(WELLBEING_CAPTURE_TAG),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                CaptureIntro(
                    icon = Icons.Rounded.SentimentSatisfied,
                    title = "Как ты себя чувствуешь?",
                    subtitle = "Отметь хотя бы один показатель. Остальные можно пропустить.",
                )
            }
            state.dimensions.forEach { dimension ->
                item(key = dimension.dimensionId) {
                    SectionTitle(
                        title = dimension.label,
                        action = if (dimension.selectedSnapshot != null) "Очистить" else null,
                        onAction = if (dimension.selectedSnapshot != null) {
                            {
                                onAction(
                                    WellbeingAction.ClearDimension(dimension.dimensionId),
                                )
                            }
                        } else {
                            null
                        },
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dimension.options.forEach { option ->
                            SelectablePill(
                                label = option.label,
                                selected = dimension.selectedOptionId == option.optionId,
                                onClick = {
                                    onAction(
                                        WellbeingAction.SelectOption(
                                            dimensionId = dimension.dimensionId,
                                            optionId = option.optionId,
                                        ),
                                    )
                                },
                                enabled = option.active &&
                                    !state.isSubmitting &&
                                    !state.retryAvailable,
                                modifier = Modifier.testTag(
                                    "$WELLBEING_OPTION_TAG_PREFIX${dimension.dimensionId}-${option.optionId}",
                                ),
                            )
                        }
                    }
                    if (!dimension.active) {
                        Text(
                            "Показатель архивирован. Сохранённое значение можно оставить или очистить.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.comment,
                    onValueChange = { onAction(WellbeingAction.CommentChanged(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 132.dp)
                        .testTag(WELLBEING_COMMENT_TAG),
                    label = { Text("Комментарий · необязательно") },
                    placeholder = { Text("Комментарий или контекст") },
                    supportingText = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            if (state.commentError == WellbeingCommentError.TooLong) {
                                Text("Комментарий длиннее $WELLBEING_COMMENT_MAX_LENGTH символов")
                            }
                            Text(
                                "${wellbeingCodePointCount(state.comment)} / " +
                                    WELLBEING_COMMENT_MAX_LENGTH,
                            )
                        }
                    },
                    isError = state.commentError != null,
                    enabled = !state.isSubmitting && !state.retryAvailable,
                    minLines = 3,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            item {
                TimestampSelector(
                    value = state.timestamp.displayValue(),
                    timezone = state.timestamp.timezonePreview(),
                    enabled = !state.isSubmitting && !state.retryAvailable,
                    error = state.timestamp.error,
                    onClick = { onAction(WellbeingAction.OpenTimestampPicker) },
                )
            }
            if (state.timestamp.overlapOffsetsSeconds.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Это время встречается дважды. Выбери смещение:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.timestamp.overlapOffsetsSeconds.forEach { offset ->
                            SelectablePill(
                                label = formatUtcOffset(offset),
                                selected = false,
                                onClick = {
                                    onAction(WellbeingAction.SelectOverlapOffset(offset))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (state.selectedCount == 0) {
                item {
                    Text(
                        "Для сохранения нужен хотя бы один явный выбор.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.retryAvailable) {
                item {
                    Text(
                        "Сохранение не подтверждено. Выбор и комментарий остались в форме — можно безопасно повторить.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
            if (state.formError != null && !state.retryAvailable) {
                item {
                    Text(
                        state.formError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
            if (!state.persistenceAvailable) {
                item {
                    Text(
                        "Зашифрованное хранилище недоступно. Сохранение отключено.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (state.timestamp.pickerVisible) {
        EventTimestampPicker(
            current = state.timestamp.choice,
            defaultTimezoneId = state.timestamp.defaultTimezoneId,
            onSelect = { onAction(WellbeingAction.SelectTimestamp(it)) },
            onDismiss = { onAction(WellbeingAction.DismissTimestampPicker) },
        )
    }
}

const val WELLBEING_CAPTURE_TAG = "wellbeing-capture"
const val WELLBEING_COMMENT_TAG = "wellbeing-comment"
const val WELLBEING_OPTION_TAG_PREFIX = "wellbeing-option-"
