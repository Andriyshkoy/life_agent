package ru.andriyshkoy.lifeagent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.andriyshkoy.lifeagent.ui.MealType
import ru.andriyshkoy.lifeagent.ui.WellbeingOption
import ru.andriyshkoy.lifeagent.ui.components.DetailTopBar
import ru.andriyshkoy.lifeagent.ui.components.MetricPill
import ru.andriyshkoy.lifeagent.ui.components.PrimaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.SectionTitle
import ru.andriyshkoy.lifeagent.ui.components.SelectablePill
import ru.andriyshkoy.lifeagent.ui.components.TimestampSelector

@Composable
internal fun CaptureScaffold(
    title: String,
    saveLabel: String,
    saveEnabled: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    saving: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            DetailTopBar(
                title = title,
                onBack = onBack,
                trailingAction = onCancel,
                trailingActionLabel = if (onCancel == null) null else "Отмена",
                actionsEnabled = !saving,
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                PrimaryActionButton(
                    text = saveLabel,
                    onClick = onSave,
                    enabled = saveEnabled,
                    loading = saving,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun FoodCaptureScreen(
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mealType by remember { mutableStateOf(MealType.Breakfast) }
    var search by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf("Овсянка с ягодами") }
    var amount by remember { mutableStateOf("350") }

    CaptureScaffold(
        title = "Добавить питание",
        saveLabel = "Записать питание",
        saveEnabled = selectedFood.isNotBlank() && amount.toDoubleOrNull() != null,
        onBack = onBack,
        onSave = onSave,
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
                    icon = Icons.Rounded.Restaurant,
                    title = "Что съел?",
                    subtitle = "Выбери готовое блюдо или продукт из своего справочника.",
                )
            }
            item {
                SectionTitle("Приём пищи")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealType.entries.chunked(2).forEach { rowTypes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowTypes.forEach { type ->
                                SelectablePill(
                                    label = type.label,
                                    selected = mealType == type,
                                    onClick = { mealType = type },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Найти продукт или блюдо") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            item {
                SectionTitle("Недавнее")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Овсянка с ягодами" to "Готовое блюдо · 350 г",
                        "Греческий йогурт" to "Продукт · 100 г",
                        "Омлет" to "Готовое блюдо · 1 порция",
                    ).forEach { (title, subtitle) ->
                        SelectionCard(
                            title = title,
                            subtitle = subtitle,
                            selected = selectedFood == title,
                            onClick = { selectedFood = title },
                        )
                    }
                }
            }
            item {
                SectionTitle("Количество")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Количество") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                    OutlinedTextField(
                        value = "г",
                        onValueChange = {},
                        modifier = Modifier.width(104.dp),
                        label = { Text("Единица · preview") },
                        readOnly = true,
                        enabled = false,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
            item {
                SectionTitle("Снимок КБЖУ")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricPill("ккал", "412", Modifier.weight(1f))
                    MetricPill("белки", "18 г", Modifier.weight(1f))
                    MetricPill("жиры", "12 г", Modifier.weight(1f))
                    MetricPill("углев.", "58 г", Modifier.weight(1f))
                }
            }
            item { TimestampSelector() }
            item {
                HelperText("Предпросмотр M1: питание и КБЖУ пока не сохраняются.")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WellbeingCaptureScreen(
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(WellbeingOption.Calm) }
    var energy by remember { mutableIntStateOf(3) }
    var comment by remember { mutableStateOf("") }

    CaptureScaffold(
        title = "Самочувствие",
        saveLabel = "Записать самочувствие",
        saveEnabled = true,
        onBack = onBack,
        onSave = onSave,
        modifier = modifier,
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
                    title = "Как ты сейчас?",
                    subtitle = "Выбери ближайшее состояние. Правильного ответа нет.",
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WellbeingOption.entries.forEach { option ->
                        SelectablePill(
                            label = option.label,
                            selected = selected == option,
                            onClick = { selected = option },
                        )
                    }
                }
            }
            item {
                SectionTitle("Энергия")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..5).forEach { value ->
                        SelectablePill(
                            label = value.toString(),
                            selected = energy == value,
                            onClick = { energy = value },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Совсем нет сил",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Много энергии",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    label = { Text("Комментарий · необязательно") },
                    placeholder = { Text("Что повлияло на состояние?") },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            item { TimestampSelector() }
            item {
                HelperText("Предпросмотр M1: самочувствие пока не сохраняется.")
            }
        }
    }
}

@Composable
fun MedicationCaptureScreen(
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedItem by remember { mutableStateOf("Витамин D") }
    var selectedDose by remember { mutableStateOf("1 капсула") }
    var comment by remember { mutableStateOf("") }

    CaptureScaffold(
        title = "Отметить приём",
        saveLabel = "Записать приём",
        saveEnabled = selectedItem.isNotBlank() && selectedDose.isNotBlank(),
        onBack = onBack,
        onSave = onSave,
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
                    icon = Icons.Rounded.Medication,
                    title = "Что принял?",
                    subtitle = "Life Agent фиксирует факт, но не назначает дозировку.",
                )
            }
            item {
                SectionTitle("Выбери из списка")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Витамин D" to "Добавка · утро",
                        "Магний" to "Добавка · вечер",
                        "Омега-3" to "Добавка",
                    ).forEach { (title, subtitle) ->
                        SelectionCard(
                            title = title,
                            subtitle = subtitle,
                            selected = selectedItem == title,
                            onClick = { selectedItem = title },
                        )
                    }
                }
            }
            item {
                SectionTitle("Доза")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1 капсула", "2 капсулы", "Другая").forEach { dose ->
                        SelectablePill(
                            label = dose,
                            selected = selectedDose == dose,
                            onClick = { selectedDose = dose },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Комментарий · необязательно") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            item { TimestampSelector() }
            item {
                HelperText("Предпросмотр M1: приём пока не сохраняется.")
            }
        }
    }
}

@Composable
internal fun CaptureIntro(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(14.dp).size(28.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
