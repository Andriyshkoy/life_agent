package ru.andriyshkoy.lifeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Clock
import java.time.ZoneId
import ru.andriyshkoy.lifeagent.BuildConfig
import ru.andriyshkoy.lifeagent.ui.screens.AddScreen
import ru.andriyshkoy.lifeagent.ui.screens.CatalogListScreen
import ru.andriyshkoy.lifeagent.ui.screens.CatalogsScreen
import ru.andriyshkoy.lifeagent.ui.screens.DiagnosticsScreen
import ru.andriyshkoy.lifeagent.ui.screens.FoodCaptureScreen
import ru.andriyshkoy.lifeagent.ui.screens.HealthConnectScreen
import ru.andriyshkoy.lifeagent.ui.screens.MedicationCaptureScreen
import ru.andriyshkoy.lifeagent.ui.screens.NoteCaptureScreen
import ru.andriyshkoy.lifeagent.ui.screens.PrivacyScreen
import ru.andriyshkoy.lifeagent.ui.screens.SettingsScreen
import ru.andriyshkoy.lifeagent.ui.screens.TimeZoneScreen
import ru.andriyshkoy.lifeagent.ui.screens.WellbeingCaptureScreen
import ru.andriyshkoy.lifeagent.ui.notes.NoteAction
import ru.andriyshkoy.lifeagent.ui.notes.NoteDialogUi
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NotesController
import ru.andriyshkoy.lifeagent.ui.notes.NotesUiState
import ru.andriyshkoy.lifeagent.ui.notes.PreviewNotesController
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode
import ru.andriyshkoy.lifeagent.ui.theme.resolveDarkTheme

private data class DestinationVisual(
    val destination: TopLevelDestination,
    val icon: ImageVector,
)

private val destinationVisuals = listOf(
    DestinationVisual(TopLevelDestination.Add, Icons.Rounded.AddCircle),
    DestinationVisual(TopLevelDestination.Catalogs, Icons.Rounded.Tune),
    DestinationVisual(TopLevelDestination.Settings, Icons.Rounded.Settings),
)

private val CaptureSnackbarBottomClearance = 80.dp

data class AppSnackbarMessage(
    val id: String,
    val text: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val withDismissAction: Boolean = true,
)

@Composable
fun LifeAgentApp(
    initialRoute: DemoRoute = DemoRoute.Add,
    initialThemeMode: ThemeMode = ThemeMode.System,
    forceExpanded: Boolean? = null,
    clock: Clock = Clock.systemUTC(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    appVersion: String = BuildConfig.VERSION_NAME,
    notesController: NotesController? = null,
    fallbackLastCommitted: LastNoteUiState = LastNoteUiState.Empty,
    onExportNotes: () -> Unit = {},
    externalMessage: AppSnackbarMessage? = null,
    onExternalMessageConsumed: (String) -> Unit = {},
    onExternalMessageAction: (String) -> Unit = {},
    showFirstRun: Boolean = false,
    onContinueLocally: () -> Unit = {},
) {
    var routeName by rememberSaveable { mutableStateOf(initialRoute.name) }
    var themeModeName by rememberSaveable { mutableStateOf(initialThemeMode.name) }

    val route = DemoRoute.entries.firstOrNull { it.name == routeName } ?: DemoRoute.Add
    val themeMode = ThemeMode.valueOf(themeModeName)
    val darkTheme = resolveDarkTheme(themeMode)
    val fallbackNotesController = remember(clock, zoneId, fallbackLastCommitted) {
        PreviewNotesController(
            clock = clock,
            defaultZoneId = zoneId,
            initialLastCommitted = fallbackLastCommitted,
        )
    }
    val activeNotesController = notesController ?: fallbackNotesController
    val notesState by activeNotesController.uiState.collectAsStateWithLifecycle()

    LifeAgentTheme(darkTheme = darkTheme) {
        LifeAgentAppContent(
            route = route,
            themeMode = themeMode,
            onThemeModeChange = { themeModeName = it.name },
            onNavigate = { routeName = it.name },
            forceExpanded = forceExpanded,
            clock = clock,
            zoneId = zoneId,
            appVersion = appVersion,
            notesState = notesState,
            onNoteAction = activeNotesController::dispatch,
            onExportNotes = onExportNotes,
            externalMessage = externalMessage,
            onExternalMessageConsumed = onExternalMessageConsumed,
            onExternalMessageAction = onExternalMessageAction,
            showFirstRun = showFirstRun,
            onContinueLocally = onContinueLocally,
        )
    }
}

@Composable
private fun LifeAgentAppContent(
    route: DemoRoute,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (DemoRoute) -> Unit,
    forceExpanded: Boolean?,
    clock: Clock,
    zoneId: ZoneId,
    appVersion: String,
    notesState: NotesUiState,
    onNoteAction: (NoteAction) -> Unit,
    onExportNotes: () -> Unit,
    externalMessage: AppSnackbarMessage?,
    onExternalMessageConsumed: (String) -> Unit,
    onExternalMessageAction: (String) -> Unit,
    showFirstRun: Boolean,
    onContinueLocally: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var localMessage by remember { mutableStateOf<AppSnackbarMessage?>(null) }

    LaunchedEffect(localMessage?.id) {
        val message = localMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            withDismissAction = message.withDismissAction,
            duration = message.duration,
        )
        localMessage = null
    }

    LaunchedEffect(externalMessage?.id) {
        val message = externalMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            withDismissAction = message.withDismissAction,
            duration = message.duration,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            onExternalMessageAction(message.id)
        }
        onExternalMessageConsumed(message.id)
    }

    LaunchedEffect(notesState.completion?.id) {
        val completion = notesState.completion ?: return@LaunchedEffect
        onNavigate(DemoRoute.Add)
        if (completion.message != null) {
            localMessage = AppSnackbarMessage(
                id = completion.id.toString(),
                text = completion.message,
            )
        }
        onNoteAction(NoteAction.CompletionConsumed)
    }

    LaunchedEffect(route, notesState.editor, notesState.editorLoading) {
        if (
            route == DemoRoute.CaptureNote &&
            notesState.editor == null &&
            !notesState.editorLoading &&
            notesState.completion == null
        ) {
            onNoteAction(NoteAction.StartCreate)
        }
    }

    val onBack = { onNavigate(route.backTarget()) }

    if (
        route == DemoRoute.CaptureNote &&
        notesState.editor == null
    ) {
        BackHandler { onNoteAction(NoteAction.ExitRequested) }
    } else if (route.topLevelDestination() == null && route != DemoRoute.CaptureNote) {
        BackHandler(onBack = onBack)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = forceExpanded ?: (maxWidth >= 720.dp)
        val currentTopLevel = route.topLevelDestination()

        if (expanded && currentTopLevel != null) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(
                    selected = currentTopLevel,
                    onSelect = { onNavigate(it.asRoute()) },
                )
                Scaffold(
                    snackbarHost = {
                        AppSnackbarHost(
                            hostState = snackbarHostState,
                            route = route,
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.weight(1f),
                ) { padding ->
                    AppScreen(
                        route = route,
                        expanded = true,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onNavigate = onNavigate,
                        onBack = onBack,
                        clock = clock,
                        zoneId = zoneId,
                        appVersion = appVersion,
                        onPreviewSave = { label ->
                            onNavigate(DemoRoute.Add)
                            localMessage = AppSnackbarMessage(
                                id = "preview-$label-${System.nanoTime()}",
                                text = "$label пока работает как предпросмотр — данные не сохранены",
                            )
                        },
                        notesState = notesState,
                        onNoteAction = onNoteAction,
                        onExportNotes = onExportNotes,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (currentTopLevel != null) {
                        AppNavigationBar(
                            selected = currentTopLevel,
                            onSelect = { onNavigate(it.asRoute()) },
                        )
                    }
                },
                snackbarHost = {
                    AppSnackbarHost(
                        hostState = snackbarHostState,
                        route = route,
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                AppScreen(
                    route = route,
                    expanded = expanded,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onNavigate = onNavigate,
                    onBack = onBack,
                    clock = clock,
                    zoneId = zoneId,
                    appVersion = appVersion,
                    onPreviewSave = { label ->
                        onNavigate(DemoRoute.Add)
                        localMessage = AppSnackbarMessage(
                            id = "preview-$label-${System.nanoTime()}",
                            text = "$label пока работает как предпросмотр — данные не сохранены",
                        )
                    },
                    notesState = notesState,
                    onNoteAction = onNoteAction,
                    onExportNotes = onExportNotes,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    when (val dialog = notesState.dialog) {
        NoteDialogUi.DiscardDraft -> {
            AlertDialog(
                onDismissRequest = { onNoteAction(NoteAction.DismissDialog) },
                title = { Text("Закрыть без сохранения?") },
                text = { Text("Несохранённый текст заметки будет удалён.") },
                confirmButton = {
                    TextButton(onClick = { onNoteAction(NoteAction.ConfirmDiscard) }) {
                        Text("Удалить черновик", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onNoteAction(NoteAction.DismissDialog) }) {
                        Text("Продолжить редактирование")
                    }
                },
            )
        }

        is NoteDialogUi.ConfirmUndo -> {
            AlertDialog(
                onDismissRequest = { onNoteAction(NoteAction.DismissDialog) },
                title = { Text("Отменить запись заметки?") },
                text = {
                    Text(
                        if (notesState.undoRetryAvailable) {
                            "Отмена не подтверждена как сохранённая. Повтор использует ту же операцию."
                        } else {
                            "Будет создана отменяющая revision. Исходная запись останется в локальной истории."
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onNoteAction(NoteAction.ConfirmUndo) },
                        enabled = !notesState.mutationInProgress,
                    ) {
                        Text(
                            if (notesState.undoRetryAvailable) {
                                "Повторить"
                            } else {
                                "Отменить запись"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onNoteAction(NoteAction.DismissDialog) }) {
                        Text("Оставить")
                    }
                },
            )
        }

        null -> Unit
    }

    if (showFirstRun) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Локальная работа готова") },
            text = {
                Text(
                    "Заметки сохраняются в зашифрованной базе на этом устройстве. " +
                        "Все записи остаются на устройстве. Незашифрованный файл создаётся " +
                        "только по явной команде экспорта.\n\n" +
                        "Часовой пояс событий: ${zoneId.id}",
                )
            },
            confirmButton = {
                TextButton(onClick = onContinueLocally) {
                    Text("Продолжить локально")
                }
            },
        )
    }
}

@Composable
private fun AppSnackbarHost(
    hostState: SnackbarHostState,
    route: DemoRoute,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = if (route.isCaptureRoute()) {
            Modifier
                .imePadding()
                .padding(bottom = CaptureSnackbarBottomClearance)
        } else {
            Modifier
        },
    )
}

@Composable
private fun AppScreen(
    route: DemoRoute,
    expanded: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (DemoRoute) -> Unit,
    onBack: () -> Unit,
    clock: Clock,
    zoneId: ZoneId,
    appVersion: String,
    onPreviewSave: (String) -> Unit,
    notesState: NotesUiState,
    onNoteAction: (NoteAction) -> Unit,
    onExportNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        DemoRoute.Add -> AddScreen(
            expanded = expanded,
            onNavigate = onNavigate,
            modifier = modifier,
            clock = clock,
            zoneId = zoneId,
            lastNote = notesState.lastCommitted,
            persistenceAvailable = notesState.persistenceAvailable,
            onStartNote = {
                onNoteAction(NoteAction.StartCreate)
                onNavigate(DemoRoute.CaptureNote)
            },
            onCorrectNote = {
                onNoteAction(NoteAction.StartCorrection(it.eventId))
                onNavigate(DemoRoute.CaptureNote)
            },
            onUndoNote = {
                onNoteAction(NoteAction.RequestUndo(it.eventId))
            },
            onRetryLastNote = {
                onNoteAction(NoteAction.RetryLastCommitted)
            },
        )

        DemoRoute.Catalogs -> CatalogsScreen(
            onNavigate = onNavigate,
            modifier = modifier,
        )

        DemoRoute.Settings -> SettingsScreen(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onNavigate = onNavigate,
            modifier = modifier,
            onExportNotes = onExportNotes,
            notesPersistenceAvailable = notesState.persistenceAvailable,
            zoneId = zoneId,
            appVersion = appVersion,
        )

        DemoRoute.CaptureFood -> FoodCaptureScreen(
            onBack,
            { onPreviewSave("Питание") },
            modifier,
        )
        DemoRoute.CaptureWellbeing -> WellbeingCaptureScreen(
            onBack,
            { onPreviewSave("Самочувствие") },
            modifier,
        )

        DemoRoute.CaptureMedication -> MedicationCaptureScreen(
            onBack,
            { onPreviewSave("Приём") },
            modifier,
        )

        DemoRoute.CaptureNote -> {
            val editor = notesState.editor
            if (editor == null) {
                androidx.compose.foundation.layout.Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                NoteCaptureScreen(
                    state = editor,
                    onAction = onNoteAction,
                    modifier = modifier,
                )
            }
        }
        DemoRoute.CatalogFood -> CatalogListScreen(CatalogKind.Food, onBack, modifier)
        DemoRoute.CatalogWellbeing -> CatalogListScreen(
            CatalogKind.Wellbeing,
            onBack,
            modifier,
        )

        DemoRoute.CatalogMedication -> CatalogListScreen(
            CatalogKind.Medication,
            onBack,
            modifier,
        )

        DemoRoute.HealthConnect -> HealthConnectScreen(onBack, modifier)
        DemoRoute.TimeZone -> TimeZoneScreen(
            onBack = onBack,
            modifier = modifier,
            clock = clock,
            zoneId = zoneId,
        )
        DemoRoute.Diagnostics -> DiagnosticsScreen(
            onBack = onBack,
            encryptedStorageAvailable = notesState.persistenceAvailable,
            appVersion = appVersion,
            modifier = modifier,
        )
        DemoRoute.Privacy -> PrivacyScreen(onBack, modifier)
    }
}

@Composable
private fun AppNavigationBar(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        destinationVisuals.forEach { item ->
            NavigationBarItem(
                selected = item.destination == selected,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.destination.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Icon(
                imageVector = Icons.Rounded.AddCircle,
                contentDescription = "Life Agent",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        },
    ) {
        destinationVisuals.forEach { item ->
            NavigationRailItem(
                selected = item.destination == selected,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.destination.label) },
            )
        }
    }
}

private fun TopLevelDestination.asRoute(): DemoRoute = when (this) {
    TopLevelDestination.Add -> DemoRoute.Add
    TopLevelDestination.Catalogs -> DemoRoute.Catalogs
    TopLevelDestination.Settings -> DemoRoute.Settings
}
