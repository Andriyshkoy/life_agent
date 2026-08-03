package ru.andriyshkoy.lifeagent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import ru.andriyshkoy.lifeagent.ui.components.SecondaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.SelectablePill
import ru.andriyshkoy.lifeagent.ui.components.TimestampSelector
import ru.andriyshkoy.lifeagent.ui.notes.NOTE_TEXT_MAX_LENGTH
import ru.andriyshkoy.lifeagent.ui.notes.NoteAction
import ru.andriyshkoy.lifeagent.ui.notes.NoteEditorMode
import ru.andriyshkoy.lifeagent.ui.notes.NoteEditorUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteTextError
import ru.andriyshkoy.lifeagent.ui.notes.NoteTimestampChoice
import ru.andriyshkoy.lifeagent.ui.notes.formatUtcOffset
import ru.andriyshkoy.lifeagent.ui.notes.noteTextCodePointCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCaptureScreen(
    state: NoteEditorUiState,
    onAction: (NoteAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onAction(NoteAction.ExitRequested) }

    CaptureScaffold(
        title = if (state.mode == NoteEditorMode.Create) {
            "Новая заметка"
        } else {
            "Исправить заметку"
        },
        saveLabel = when {
            state.isSubmitting -> "Сохраняем"
            state.retryAvailable -> "Повторить сохранение"
            state.mode == NoteEditorMode.Correct -> "Сохранить исправление"
            else -> "Сохранить заметку"
        },
        saveEnabled = state.canSave || state.retryAvailable,
        onBack = { onAction(NoteAction.ExitRequested) },
        onSave = {
            onAction(if (state.retryAvailable) NoteAction.RetrySave else NoteAction.Save)
        },
        onCancel = { onAction(NoteAction.ExitRequested) },
        saving = state.isSubmitting,
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                CaptureIntro(
                    icon = Icons.Rounded.EditNote,
                    title = if (state.mode == NoteEditorMode.Create) {
                        "Что происходит?"
                    } else {
                        "Что нужно уточнить?"
                    },
                    subtitle = "Короткая мысль, факт или важный контекст дня.",
                )
            }
            item {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = { onAction(NoteAction.TextChanged(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                    placeholder = {
                        Text("Например: после прогулки стало заметно легче сосредоточиться")
                    },
                    label = { Text("Текст заметки") },
                    supportingText = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            val error = state.textError.message()
                            if (error != null) {
                                Text(error)
                            }
                            Text(
                                "${noteTextCodePointCount(state.text)} / $NOTE_TEXT_MAX_LENGTH",
                            )
                        }
                    },
                    isError = state.textError != null,
                    enabled = !state.isSubmitting && !state.retryAvailable,
                    shape = MaterialTheme.shapes.medium,
                    minLines = 7,
                    maxLines = 20,
                )
            }
            item {
                TimestampSelector(
                    value = state.timestamp.displayValue(),
                    timezone = state.timestamp.timezonePreview(),
                    enabled = !state.isSubmitting && !state.retryAvailable,
                    error = state.timestamp.error,
                    onClick = { onAction(NoteAction.OpenTimestampPicker) },
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
                                onClick = { onAction(NoteAction.SelectOverlapOffset(offset)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (state.retryAvailable) {
                item {
                    Text(
                        "Заметка не подтверждена как сохранённая. Текст остался в форме — можно повторить.",
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
                        "Режим предпросмотра: эта форма не записывает данные на устройство.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                HelperText("Заголовок не нужен — заметка сохранится как единое событие.")
            }
        }
    }

    if (state.timestamp.pickerVisible) {
        NoteTimestampPicker(
            current = state.timestamp.choice,
            defaultTimezoneId = state.timestamp.defaultTimezoneId,
            onSelect = { onAction(NoteAction.SelectTimestamp(it)) },
            onDismiss = { onAction(NoteAction.DismissTimestampPicker) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteTimestampPicker(
    current: NoteTimestampChoice,
    defaultTimezoneId: String,
    onSelect: (NoteTimestampChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var showQuickSheet by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

    if (showQuickSheet) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Время события", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(2.dp))
                SelectablePill(
                    label = "Сейчас",
                    selected = current == NoteTimestampChoice.Now,
                    onClick = { onSelect(NoteTimestampChoice.Now) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectablePill(
                    label = "15 минут назад",
                    selected = current == NoteTimestampChoice.FifteenMinutesAgo,
                    onClick = { onSelect(NoteTimestampChoice.FifteenMinutesAgo) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectablePill(
                    label = "1 час назад",
                    selected = current == NoteTimestampChoice.OneHourAgo,
                    onClick = { onSelect(NoteTimestampChoice.OneHourAgo) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryActionButton(
                    text = "Выбрать дату и время",
                    onClick = {
                        showQuickSheet = false
                        showDatePicker = true
                    },
                )
                Text(
                    "Часовой пояс: ${
                        (current as? NoteTimestampChoice.Custom)?.zoneId
                            ?: defaultTimezoneId
                    }",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDatePicker) {
        val initialDate = (current as? NoteTimestampChoice.Custom)?.localDateTime?.toLocalDate()
            ?: LocalDate.now(ZoneId.of(defaultTimezoneId))
        val initialDateMillis = initialDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
                onDismiss()
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis ?: return@TextButton
                        selectedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        showDatePicker = false
                        showTimePicker = true
                    },
                ) {
                    Text("Далее")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        onDismiss()
                    },
                ) {
                    Text("Отмена")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val initialTime = (current as? NoteTimestampChoice.Custom)?.localDateTime?.toLocalTime()
            ?: LocalTime.now(ZoneId.of(defaultTimezoneId))
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = {
                showTimePicker = false
                onDismiss()
            },
            title = { Text("Выбери время") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = selectedDate ?: LocalDate.now(ZoneId.of(defaultTimezoneId))
                        onSelect(
                            NoteTimestampChoice.Custom(
                                localDateTime = LocalDateTime.of(
                                    date,
                                    LocalTime.of(timePickerState.hour, timePickerState.minute),
                                ),
                                zoneId = defaultTimezoneId,
                            ),
                        )
                        showTimePicker = false
                    },
                ) {
                    Text("Готово")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        onDismiss()
                    },
                ) {
                    Text("Отмена")
                }
            },
        )
    }
}

private fun NoteTextError?.message(): String? = when (this) {
    NoteTextError.Empty -> "Введи текст заметки"
    NoteTextError.TooLong -> "Заметка длиннее $NOTE_TEXT_MAX_LENGTH символов"
    null -> null
}
