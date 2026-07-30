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
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.andriyshkoy.lifeagent.BuildConfig
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
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteRecordStatusUi
import ru.andriyshkoy.lifeagent.ui.notes.NoteSummaryUi
import ru.andriyshkoy.lifeagent.ui.notes.formatUtcOffset
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentThemeValues
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

@Composable
fun AddScreen(
    expanded: Boolean,
    onNavigate: (DemoRoute) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemUTC(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    lastNote: LastNoteUiState = LastNoteUiState.Empty,
    persistenceAvailable: Boolean = false,
    onStartNote: () -> Unit = { onNavigate(DemoRoute.CaptureNote) },
    onCorrectNote: (NoteSummaryUi) -> Unit = {},
    onUndoNote: (NoteSummaryUi) -> Unit = {},
    onRetryLastNote: () -> Unit = {},
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
                    AddHeader(clock = clock, zoneId = zoneId)
                    Spacer(Modifier.height(28.dp))
                    SectionTitle("Что записать?")
                    Spacer(Modifier.height(14.dp))
                    QuickActionGrid(onNavigate, onStartNote)
                }
                Column(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Spacer(Modifier.height(82.dp))
                    LocalStateCard(persistenceAvailable)
                    RecentActivityCard(
                        lastNote = lastNote,
                        onCorrectNote = onCorrectNote,
                        onUndoNote = onUndoNote,
                        onRetryLastNote = onRetryLastNote,
                    )
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
                item { AddHeader(clock = clock, zoneId = zoneId) }
                item {
                    SectionTitle("Что записать?")
                    Spacer(Modifier.height(14.dp))
                    QuickActionGrid(onNavigate, onStartNote)
                }
                item { LocalStateCard(persistenceAvailable) }
                item {
                    RecentActivityCard(
                        lastNote = lastNote,
                        onCorrectNote = onCorrectNote,
                        onUndoNote = onUndoNote,
                        onRetryLastNote = onRetryLastNote,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddHeader(
    clock: Clock,
    zoneId: ZoneId,
) {
    val content = resolveAddHeaderContent(clock = clock, zoneId = zoneId)
    Column {
        Text(
            text = content.dateLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = content.greeting,
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

internal data class AddHeaderContent(
    val dateLabel: String,
    val greeting: String,
)

internal fun resolveAddHeaderContent(
    clock: Clock,
    zoneId: ZoneId,
    locale: Locale = Locale.forLanguageTag("ru"),
): AddHeaderContent {
    val localNow = clock.instant().atZone(zoneId)
    val greeting = when (localNow.hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
    return AddHeaderContent(
        dateLabel = localNow.format(
            DateTimeFormatter.ofPattern("d MMMM · EEEE", locale),
        ),
        greeting = greeting,
    )
}

@Composable
private fun QuickActionGrid(
    onNavigate: (DemoRoute) -> Unit,
    onStartNote: () -> Unit,
) {
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
                onClick = onStartNote,
                modifier = Modifier.weight(1f).heightIn(min = 154.dp),
            )
        }
    }
}

@Composable
private fun LocalStateCard(persistenceAvailable: Boolean) {
    StatusCard(
        title = if (persistenceAvailable) {
            "Заметки сохраняются на устройстве"
        } else {
            "Зашифрованное хранилище недоступно"
        },
        subtitle = if (persistenceAvailable) {
            "Синхронизация пока не настроена"
        } else {
            "Чтение и запись заметок отключены"
        },
        icon = Icons.Rounded.CloudOff,
    )
}

@Composable
private fun RecentActivityCard(
    lastNote: LastNoteUiState,
    onCorrectNote: (NoteSummaryUi) -> Unit,
    onUndoNote: (NoteSummaryUi) -> Unit,
    onRetryLastNote: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {
            SectionTitle("Последнее действие")
            Spacer(Modifier.height(16.dp))
            when (lastNote) {
                LastNoteUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }

                LastNoteUiState.Empty -> {
                    Text(
                        "Пока нет сохранённых заметок",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LastNoteUiState.Failed -> {
                    Text(
                        "Не удалось прочитать последнее действие",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(
                        text = "Повторить",
                        onClick = onRetryLastNote,
                    )
                }

                is LastNoteUiState.Available -> {
                    val note = lastNote.note
                    Text(
                        if (note.status == NoteRecordStatusUi.Active) {
                            note.text
                        } else {
                            "Заметка отменена"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Заметка · ${note.timestampLabel()} · ${note.timezoneId} · " +
                            formatUtcOffset(note.offsetSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (note.status == NoteRecordStatusUi.Active) {
                            "Сохранено на устройстве · синхронизация не настроена"
                        } else {
                            "Отмена сохранена на устройстве"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (note.status == NoteRecordStatusUi.Active) {
                        Spacer(Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryActionButton(
                                text = "Исправить",
                                onClick = { onCorrectNote(note) },
                            )
                            OutlinedButton(
                                onClick = { onUndoNote(note) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(
                                    "Отменить запись",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
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
                subtitle = "Предпросмотр будущего быстрого ввода.",
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Изменения справочников пока не сохраняются.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
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
                text = "Предзаполненные карточки здесь — только демонстрационные.",
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
    onExportNotes: () -> Unit = {},
    notesPersistenceAvailable: Boolean = false,
    zoneId: ZoneId = ZoneId.systemDefault(),
    appVersion: String = BuildConfig.VERSION_NAME,
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
                    subtitle = "Интеграция запланирована на M4",
                    onClick = { onNavigate(DemoRoute.HealthConnect) },
                )
            }
        }
        item {
            SettingsCard(title = "Данные", contentPadding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = "Экспорт",
                    subtitle = if (notesPersistenceAvailable) {
                        "Заметки · JSON без шифрования"
                    } else {
                        "Хранилище недоступно"
                    },
                    onClick = onExportNotes,
                    enabled = notesPersistenceAvailable,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 62.dp),
                )
                SettingsRow(
                    icon = Icons.Rounded.Security,
                    title = "Приватность",
                    subtitle = "Как хранятся локальные данные",
                    onClick = { onNavigate(DemoRoute.Privacy) },
                )
            }
        }
        item {
            SettingsCard(title = "Система", contentPadding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Schedule,
                    title = "Часовой пояс",
                    subtitle = zoneId.id,
                    onClick = { onNavigate(DemoRoute.TimeZone) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 62.dp),
                )
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = "Диагностика",
                    subtitle = "Версия и состояние локального хранилища",
                    onClick = { onNavigate(DemoRoute.Diagnostics) },
                )
            }
        }
        item {
            SettingsCard(title = "О приложении", contentPadding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = "Life Agent",
                    subtitle = "Версия $appVersion",
                    onClick = null,
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
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
) {
    val interactive = onClick != null
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
        trailingContent = if (interactive && enabled) {
            {
                Icon(Icons.Rounded.ArrowOutward, contentDescription = "Открыть")
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .alpha(if (interactive && !enabled) 0.5f else 1f)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .semantics {
                            if (!enabled) disabled()
                        }
                        .clickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                },
            ),
    )
}

@Composable
fun TimeZoneScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemUTC(),
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val content = resolveTimeZoneDisplayContent(clock = clock, zoneId = zoneId)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DetailTopBar("Часовой пояс", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    title = content.zoneId,
                    subtitle = "Текущий пояс устройства · ${content.offsetLabel}",
                    icon = Icons.Rounded.Schedule,
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Schedule,
                    title = "Текущее локальное время",
                    text = content.localTimeLabel,
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Description,
                    title = "Время события сохраняется с контекстом",
                    text = "Каждое событие хранит точный момент (instant), исходные " +
                        "локальные дату и время, ZoneId и UTC-смещение.",
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Tune,
                    title = "Изменение часового пояса",
                    text = "Life Agent читает часовой пояс устройства. Изменить его можно " +
                        "в системных настройках Android — отдельного переключателя в M1 нет.",
                )
            }
        }
    }
}

internal data class TimeZoneDisplayContent(
    val zoneId: String,
    val offsetLabel: String,
    val localTimeLabel: String,
)

internal fun resolveTimeZoneDisplayContent(
    clock: Clock,
    zoneId: ZoneId,
    locale: Locale = Locale.forLanguageTag("ru"),
): TimeZoneDisplayContent {
    val localNow = clock.instant().atZone(zoneId)
    return TimeZoneDisplayContent(
        zoneId = zoneId.id,
        offsetLabel = formatUtcOffset(localNow.offset.totalSeconds),
        localTimeLabel = localNow.format(
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", locale),
        ),
    )
}

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    encryptedStorageAvailable: Boolean,
    appVersion: String = BuildConfig.VERSION_NAME,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DetailTopBar("Диагностика", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    title = if (encryptedStorageAvailable) {
                        "Зашифрованное хранилище доступно"
                    } else {
                        "Зашифрованное хранилище недоступно"
                    },
                    subtitle = "Синхронизация отсутствует в M1",
                    icon = Icons.Rounded.Security,
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Info,
                    title = "Версия приложения",
                    text = appVersion,
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Security,
                    title = "Локальное хранилище",
                    text = if (encryptedStorageAvailable) {
                        "Зашифрованная база заметок открыта и доступна приложению."
                    } else {
                        "Зашифрованная база заметок не открыта: чтение и сохранение недоступны."
                    },
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Sync,
                    title = "Синхронизация M1",
                    text = "Серверная синхронизация отсутствует; приложение работает локально.",
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Description,
                    title = "Содержимое диагностики",
                    text = "Экран показывает только версию и доступность хранилища. " +
                        "ID, логи и значения здоровья здесь не отображаются.",
                )
            }
        }
    }
}

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { DetailTopBar("Приватность", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    title = "Данные остаются локально",
                    subtitle = "В M1 серверная синхронизация отсутствует",
                    icon = Icons.Rounded.Security,
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Security,
                    title = "Шифрование на устройстве",
                    text = "Локальная база и outbox защищены SQLCipher. " +
                        "Ключ шифрования базы обёрнут ключом Android Keystore.",
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.CloudOff,
                    title = "Резервные копии",
                    text = "База, outbox и ключи исключены из Android Auto Backup " +
                        "и переноса данных на другое устройство.",
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Sync,
                    title = "Синхронизация",
                    text = "Серверная синхронизация в M1 отсутствует. " +
                        "Локальные заметки не отправляются на сервер.",
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.Description,
                    title = "Экспорт",
                    text = "Экспорт создаёт JSON без шифрования. Файл сохраняется " +
                        "в выбранном пользователем месте и дальше хранится под его контролем.",
                )
            }
            item {
                InformationCard(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Удаление данных",
                    text = "Отдельной кнопки удаления внутри Life Agent в M1 нет. " +
                        "Очистка данных приложения в Android или удаление приложения удаляет " +
                        "локальную базу из закрытого хранилища приложения. Экспортированные " +
                        "JSON-файлы без шифрования нужно удалить отдельно в выбранном месте.",
                )
            }
        }
    }
}

@Composable
private fun InformationCard(
    icon: ImageVector,
    title: String,
    text: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(5.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
                        readOnly = true,
                        enabled = false,
                        singleLine = true,
                    )
                }
            }
        }
        PrimaryActionButton(
            text = "Закрыть предпросмотр",
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
                    label = { Text("Одноразовый код · M2") },
                    supportingText = { Text("Подключение появится в M2") },
                    readOnly = true,
                    enabled = false,
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
                    title = "Предпросмотр Health Connect",
                    subtitle = "Подключение появится на M4; сейчас данные не читаются",
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
                            checked = true,
                            onCheckedChange = null,
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 62.dp),
                        )
                        PermissionRow(
                            icon = Icons.Rounded.MonitorHeart,
                            title = "Пульс",
                            subtitle = "Обычные измерения пульса",
                            checked = true,
                            onCheckedChange = null,
                        )
                    }
                }
            }
            item {
                PrimaryActionButton(
                    text = "Выдать доступ",
                    onClick = {},
                    enabled = false,
                    icon = Icons.Rounded.HealthAndSafety,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Подключение Health Connect появится на M4; сейчас данные не читаются.",
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
    onCheckedChange: ((Boolean) -> Unit)?,
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
