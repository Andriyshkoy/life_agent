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
import ru.andriyshkoy.lifeagent.ui.screens.WellbeingCatalogScreen
import ru.andriyshkoy.lifeagent.ui.screens.WellbeingLoadErrorScreen
import ru.andriyshkoy.lifeagent.ui.notes.NoteAction
import ru.andriyshkoy.lifeagent.ui.notes.NoteDialogUi
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NotesController
import ru.andriyshkoy.lifeagent.ui.notes.NotesUiState
import ru.andriyshkoy.lifeagent.ui.notes.PreviewNotesController
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode
import ru.andriyshkoy.lifeagent.ui.theme.resolveDarkTheme
import ru.andriyshkoy.lifeagent.ui.wellbeing.LastWellbeingUiState
import ru.andriyshkoy.lifeagent.ui.wellbeing.UnavailableWellbeingController
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingAction
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingController
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingDialogUi
import ru.andriyshkoy.lifeagent.ui.wellbeing.WellbeingUiState

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
    wellbeingController: WellbeingController? = null,
    fallbackLastWellbeing: LastWellbeingUiState = LastWellbeingUiState.Empty,
    onExportLifeAgent: () -> Unit = {},
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
    val fallbackWellbeingController = remember(fallbackLastWellbeing) {
        UnavailableWellbeingController(
            initialLastCommitted = fallbackLastWellbeing,
        )
    }
    val activeWellbeingController = wellbeingController ?: fallbackWellbeingController
    val wellbeingState by activeWellbeingController.uiState.collectAsStateWithLifecycle()

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
            wellbeingState = wellbeingState,
            onWellbeingAction = activeWellbeingController::dispatch,
            onExportLifeAgent = onExportLifeAgent,
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
    wellbeingState: WellbeingUiState,
    onWellbeingAction: (WellbeingAction) -> Unit,
    onExportLifeAgent: () -> Unit,
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

    LaunchedEffect(wellbeingState.completion?.id) {
        val completion = wellbeingState.completion ?: return@LaunchedEffect
        onNavigate(DemoRoute.Add)
        if (completion.message != null) {
            localMessage = AppSnackbarMessage(
                id = completion.id.toString(),
                text = completion.message,
            )
        }
        onWellbeingAction(WellbeingAction.CompletionConsumed)
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

    LaunchedEffect(
        route,
        wellbeingState.editor,
        wellbeingState.editorLoading,
        wellbeingState.editorLoadError,
    ) {
        if (
            route == DemoRoute.CaptureWellbeing &&
            wellbeingState.editor == null &&
            !wellbeingState.editorLoading &&
            wellbeingState.editorLoadError == null &&
            wellbeingState.completion == null
        ) {
            onWellbeingAction(WellbeingAction.StartCreate)
        }
    }

    val onBack = { onNavigate(route.backTarget()) }

    if (
        route == DemoRoute.CaptureNote &&
        notesState.editor == null
    ) {
        BackHandler { onNoteAction(NoteAction.ExitRequested) }
    } else if (
        route == DemoRoute.CaptureWellbeing &&
        wellbeingState.editor == null
    ) {
        BackHandler { onWellbeingAction(WellbeingAction.ExitRequested) }
    } else if (
        route.topLevelDestination() == null &&
        route != DemoRoute.CaptureNote &&
        route != DemoRoute.CaptureWellbeing
    ) {
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
                        wellbeingState = wellbeingState,
                        onWellbeingAction = onWellbeingAction,
                        onExportLifeAgent = onExportLifeAgent,
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
                    wellbeingState = wellbeingState,
                    onWellbeingAction = onWellbeingAction,
                    onExportLifeAgent = onExportLifeAgent,
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

    when (val dialog = wellbeingState.dialog) {
        WellbeingDialogUi.DiscardDraft -> {
            AlertDialog(
                onDismissRequest = {
                    onWellbeingAction(WellbeingAction.DismissDialog)
                },
                title = { Text("Закрыть без сохранения?") },
                text = {
                    Text("Выбранные значения и комментарий будут удалены.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onWellbeingAction(WellbeingAction.ConfirmDiscard)
                        },
                    ) {
                        Text("Удалить черновик", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onWellbeingAction(WellbeingAction.DismissDialog)
                        },
                    ) {
                        Text("Продолжить редактирование")
                    }
                },
            )
        }

        is WellbeingDialogUi.ConfirmUndo -> {
            AlertDialog(
                onDismissRequest = {
                    onWellbeingAction(WellbeingAction.DismissDialog)
                },
                title = { Text("Отменить запись самочувствия?") },
                text = {
                    Text(
                        if (wellbeingState.undoRetryAvailable) {
                            "Отмена не подтверждена как сохранённая. Повтор использует ту же операцию."
                        } else {
                            "Запись останется в локальной истории, но перестанет быть текущим активным фактом."
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onWellbeingAction(WellbeingAction.ConfirmUndo)
                        },
                        enabled = !wellbeingState.mutationInProgress,
                    ) {
                        Text(
                            if (wellbeingState.undoRetryAvailable) {
                                "Повторить"
                            } else {
                                "Отменить запись"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onWellbeingAction(WellbeingAction.DismissDialog)
                        },
                    ) {
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
                    "Данные сохраняются в зашифрованной базе на этом устройстве. " +
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
    wellbeingState: WellbeingUiState,
    onWellbeingAction: (WellbeingAction) -> Unit,
    onExportLifeAgent: () -> Unit,
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
            lastWellbeing = wellbeingState.lastCommitted,
            persistenceAvailable = notesState.persistenceAvailable &&
                wellbeingState.persistenceAvailable,
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
            onCorrectWellbeing = {
                onWellbeingAction(WellbeingAction.StartCorrection(it.eventId))
                onNavigate(DemoRoute.CaptureWellbeing)
            },
            onUndoWellbeing = {
                onWellbeingAction(WellbeingAction.RequestUndo(it.eventId))
            },
            onRetryLastWellbeing = {
                onWellbeingAction(WellbeingAction.RetryLastCommitted)
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
            onExportLifeAgent = onExportLifeAgent,
            persistenceAvailable = notesState.persistenceAvailable &&
                wellbeingState.persistenceAvailable,
            zoneId = zoneId,
            appVersion = appVersion,
        )

        DemoRoute.CaptureFood -> FoodCaptureScreen(
            onBack,
            { onPreviewSave("Питание") },
            modifier,
        )
        DemoRoute.CaptureWellbeing -> {
            val editor = wellbeingState.editor
            when {
                editor != null -> WellbeingCaptureScreen(
                    state = editor,
                    onAction = onWellbeingAction,
                    modifier = modifier,
                )

                wellbeingState.editorLoadError != null -> WellbeingLoadErrorScreen(
                    message = wellbeingState.editorLoadError,
                    onRetry = { onWellbeingAction(WellbeingAction.RetryEditorLoad) },
                    onBack = { onWellbeingAction(WellbeingAction.ExitRequested) },
                    modifier = modifier,
                )

                else -> androidx.compose.foundation.layout.Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

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
        DemoRoute.CatalogWellbeing -> WellbeingCatalogScreen(
            state = wellbeingState.catalog,
            onBack = onBack,
            onRetry = { onWellbeingAction(WellbeingAction.RetryCatalog) },
            modifier = modifier,
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
