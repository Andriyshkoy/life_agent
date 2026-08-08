package ru.andriyshkoy.lifeagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import ru.andriyshkoy.lifeagent.ui.components.SecondaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.SelectablePill
import ru.andriyshkoy.lifeagent.ui.time.EventTimestampChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventTimestampPicker(
    current: EventTimestampChoice,
    defaultTimezoneId: String,
    onSelect: (EventTimestampChoice) -> Unit,
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
                    selected = current == EventTimestampChoice.Now,
                    onClick = { onSelect(EventTimestampChoice.Now) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectablePill(
                    label = "15 минут назад",
                    selected = current == EventTimestampChoice.FifteenMinutesAgo,
                    onClick = { onSelect(EventTimestampChoice.FifteenMinutesAgo) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectablePill(
                    label = "1 час назад",
                    selected = current == EventTimestampChoice.OneHourAgo,
                    onClick = { onSelect(EventTimestampChoice.OneHourAgo) },
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
                        (current as? EventTimestampChoice.Custom)?.zoneId
                            ?: defaultTimezoneId
                    }",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDatePicker) {
        val initialDate = (current as? EventTimestampChoice.Custom)?.localDateTime?.toLocalDate()
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
        val initialTime = (current as? EventTimestampChoice.Custom)?.localDateTime?.toLocalTime()
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
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = selectedDate ?: LocalDate.now(ZoneId.of(defaultTimezoneId))
                        onSelect(
                            EventTimestampChoice.Custom(
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
