package ru.andriyshkoy.lifeagent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalDining
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import ru.andriyshkoy.lifeagent.ui.CatalogKind
import ru.andriyshkoy.lifeagent.ui.DemoCatalogItem
import ru.andriyshkoy.lifeagent.ui.DemoContent
import ru.andriyshkoy.lifeagent.ui.DemoRoute
import ru.andriyshkoy.lifeagent.ui.components.DetailTopBar
import ru.andriyshkoy.lifeagent.ui.components.PrimaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.QuickActionCard
import ru.andriyshkoy.lifeagent.ui.components.ScreenTitle
import ru.andriyshkoy.lifeagent.ui.components.SecondaryActionButton
import ru.andriyshkoy.lifeagent.ui.components.SectionTitle
import ru.andriyshkoy.lifeagent.ui.components.SelectablePill
import ru.andriyshkoy.lifeagent.ui.components.StatusCard
import ru.andriyshkoy.lifeagent.ui.components.TimelineItem
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentThemeValues
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

@Composable
fun AddScreen(
    expanded: Boolean,
    onNavigate: (DemoRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (expanded) {
            Row(
                modifier = Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(Modifier.weight(1.25f)) {
                    AddHeader()
                    Spacer(Modifier.height(28.dp))
                    SectionTitle("Что записать?")
                    Spacer(Modifier.height(14.dp))
                    QuickActionGrid(onNavigate)
                }
                Column(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Spacer(Modifier.height(82.dp))
                    LocalStateCard()
                    RecentActivityCard()
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 24.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item { AddHeader() }
                item {
                    SectionTitle("Что записать?")
                    Spacer(Modifier.height(14.dp))
                    QuickActionGrid(onNavigate)
                }
                item { LocalStateCard() }
                item { RecentActivityCard() }
            }
        }
    }
}

@Composable
private fun AddHeader() {
    Column {
        Text(
            text = "29 июля · среда",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Доброе утро",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Зафиксируй то, что важно сейчас.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickActionGrid(onNavigate: (DemoRoute) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickActionCard(
                title = "Питание",
                subtitle = "Блюдо или продукт",
                icon = Icons.Rounded.Restaurant,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { onNavigate(DemoRoute.CaptureFood) },
                modifier = Modifier.weight(1f).heightIn(min = 154.dp),
            )
            QuickActionCard(
                title = "Самочувствие",
                subtitle = "Состояние и энергия",
                icon = Icons.Rounded.SentimentSatisfied,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = { onNavigate(DemoRoute.CaptureWellbeing) },
                modifier = Modifier.weight(1f).heightIn(min = 154.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickActionCard(
                title = "Приём",
                subtitle = "Лекарство или БАД",
                icon = Icons.Rounded.Medication,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { onNavigate(DemoRoute.CaptureMedication) },
                modifier = Modifier.weight(1f).heightIn(min = 154.dp),
            )
            QuickActionCard(
                title = "Заметка",
                subtitle = "Свободный текст",
                icon = Icons.Rounded.EditNote,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onNavigate(DemoRoute.CaptureNote) },
                modifier = Modifier.weight(1f).heightIn(min = 154.dp),
            )
        }
    }
}

@Composable
private fun LocalStateCard() {
    StatusCard(
        title = "Всё сохранено на устройстве",
        subtitle = "Синхронизация пока не настроена",
        icon = Icons.Rounded.CloudOff,
    )
}

@Composable
private fun RecentActivityCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {
            SectionTitle("Последнее действие")
            Spacer(Modifier.height(16.dp))
            TimelineItem(
                title = "Спокойно",
                subtitle = "Самочувствие · комментария нет",
                time = "09:18",
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Это демонстрационные данные",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CatalogsScreen(
    onNavigate: (DemoRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenTitle(
                title = "Справочники",
                subtitle = "Настрой быстрый ввод под свои привычки.",
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            CatalogOverviewCard(
                kind = CatalogKind.Food,
                icon = Icons.Rounded.LocalDining,
                onClick = { onNavigate(DemoRoute.CatalogFood) },
            )
        }
        item {
            CatalogOverviewCard(
                kind = CatalogKind.Wellbeing,
                icon = Icons.Rounded.SentimentSatisfied,
                onClick = { onNavigate(DemoRoute.CatalogWellbeing) },
            )
        }
        item {
            CatalogOverviewCard(
                kind = CatalogKind.Medication,
                icon = Icons.Rounded.Medication,
                onClick = { onNavigate(DemoRoute.CatalogMedication) },
            )
        }
        item {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Изменение карточки не меняет уже сохранённые события.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogOverviewCard(
    kind: CatalogKind,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(icon, null, Modifier.padding(11.dp).size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    kind.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    kind.countLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                Icons.Rounded.ArrowOutward,
                contentDescription = "Открыть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (DemoRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ScreenTitle(
                title = "Настройки",
                subtitle = "Внешний вид, данные и подключения.",
            )
        }
        item {
            SettingsCard(title = "Оформление") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        SelectablePill(
                            label = mode.label,
                            selected = mode == themeMode,
                            onClick = { onThemeModeChange(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        item {
            SettingsCard(title = "Подключения", contentPadding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Sync,
                    title = "Синхронизация",
                    subtitle = "Не настроена · локальная работа доступна",
                    onClick = { onNavigate(DemoRoute.SyncSetup) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 62.dp),
                )
                SettingsRow(
                    icon = Icons.Rounded.MonitorHeart,
                    title = "Health Connect",
                    subtitle = "Доступ будет запрошен отдельно",
                    onClick = { onNavigate(DemoRoute.HealthConnect) },
                )
            }
        }
        item {
            SettingsCard(title = "Данные", contentPadding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = "Экспорт",
                    subtitle = "Получить копию своих данных",
                    onClick = {},
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 62.dp),
                )
                SettingsRow(
                    icon = Icons.Rounded.Security,
                    title = "Приватность",
                    subtitle = "Разрешения и удаление данных",
                    onClick = {},
                )
            }
        }
        item {
            SettingsCard(title = "О приложении", contentPadding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = "Life Agent",
                    subtitle = "Интерактивный UI-прототип · 0.1.0",
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp),
            )
            Spacer(Modifier.height(12.dp))
            Column(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.Rounded.ArrowOutward, contentDescription = "Открыть")
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogListScreen(
    kind: CatalogKind,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var search by remember { mutableStateOf("") }
    var editorVisible by remember { mutableStateOf(false) }
    val source = when (kind) {
        CatalogKind.Food -> DemoContent.foodItems
        CatalogKind.Wellbeing -> DemoContent.wellbeingItems
        CatalogKind.Medication -> DemoContent.medicationItems
    }
    val filtered = source.filter { it.title.contains(search, ignoreCase = true) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DetailTopBar(kind.title, onBack) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editorVisible = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Добавить")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    kind.countLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(filtered) { item ->
                CatalogItemCard(item, onClick = { editorVisible = true })
            }
        }
    }

    if (editorVisible) {
        ModalBottomSheet(
            onDismissRequest = { editorVisible = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            CatalogEditorSheet(
                kind = kind,
                onSave = { editorVisible = false },
            )
        }
    }
}

@Composable
private fun CatalogItemCard(item: DemoCatalogItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (item.active) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.badge != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        item.badge,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Rounded.ArrowOutward, contentDescription = "Изменить")
            }
        }
    }
}

@Composable
private fun CatalogEditorSheet(kind: CatalogKind, onSave: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    val spacing = LifeAgentThemeValues.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Новая карточка",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            when (kind) {
                CatalogKind.Food -> "Продукт или готовое блюдо"
                CatalogKind.Wellbeing -> "Вариант самочувствия"
                CatalogKind.Medication -> "Лекарство или добавка"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        OutlinedTextField(
            value = detail,
            onValueChange = { detail = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    when (kind) {
                        CatalogKind.Food -> "Обычная порция, г"
                        CatalogKind.Wellbeing -> "Короткое описание"
                        CatalogKind.Medication -> "Дозировка"
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (kind == CatalogKind.Food) {
                    KeyboardType.Decimal
                } else {
                    KeyboardType.Text
                },
            ),
            shape = MaterialTheme.shapes.medium,
        )
        if (kind == CatalogKind.Food) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Ккал", "Белки", "Жиры", "Углеводы").forEach { label ->
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.weight(1f),
                        label = { Text(label) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
            }
        }
        PrimaryActionButton(
            text = "Сохранить карточку",
            onClick = onSave,
            enabled = title.isNotBlank(),
        )
    }
}

@Composable
fun SyncSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DetailTopBar("Синхронизация", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(30.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Локальная работа уже доступна",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Подключение сервера можно пропустить и настроить позже.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Одноразовый код подключения") },
                    supportingText = { Text("Код создаётся на личном сервере") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            item {
                PrimaryActionButton(
                    text = "Подключить",
                    onClick = {},
                    enabled = false,
                    icon = Icons.Rounded.Sync,
                )
                Spacer(Modifier.height(10.dp))
                SecondaryActionButton("Настроить позже", onBack)
            }
        }
    }
}

@Composable
fun HealthConnectScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sleepEnabled by remember { mutableStateOf(true) }
    var heartRateEnabled by remember { mutableStateOf(true) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DetailTopBar("Health Connect", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusCard(
                    title = "Доступ ещё не выдан",
                    subtitle = "Ручной ввод продолжит работать без Health Connect",
                    icon = Icons.Rounded.HealthAndSafety,
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        PermissionRow(
                            icon = Icons.Rounded.MonitorHeart,
                            title = "Сон",
                            subtitle = "Сессии сна без догадок о фазах",
                            checked = sleepEnabled,
                            onCheckedChange = { sleepEnabled = it },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 62.dp),
                        )
                        PermissionRow(
                            icon = Icons.Rounded.MonitorHeart,
                            title = "Пульс",
                            subtitle = "Обычные измерения пульса",
                            checked = heartRateEnabled,
                            onCheckedChange = { heartRateEnabled = it },
                        )
                    }
                }
            }
            item {
                PrimaryActionButton(
                    text = "Выдать доступ",
                    onClick = {},
                    icon = Icons.Rounded.HealthAndSafety,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Life Agent запрашивает только выбранные типы и ничего не записывает в Health Connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
