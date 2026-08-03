package ru.andriyshkoy.lifeagent

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.andriyshkoy.lifeagent.export.ContentResolverTruncatingOutputFactory
import ru.andriyshkoy.lifeagent.export.ExportDeliveryResult
import ru.andriyshkoy.lifeagent.export.ExportUiEvent
import ru.andriyshkoy.lifeagent.export.ExportUiPhase
import ru.andriyshkoy.lifeagent.export.after
import ru.andriyshkoy.lifeagent.export.deliverExportDocument
import ru.andriyshkoy.lifeagent.ui.AppSnackbarMessage
import ru.andriyshkoy.lifeagent.ui.LifeAgentApp
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NotesViewModel
import ru.andriyshkoy.lifeagent.ui.sync.DefaultSyncSetupController
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val lifeAgentApplication = application as LifeAgentApplication
        setContent {
            val storageState by produceState<StorageState>(
                initialValue = StorageState.Loading,
                key1 = lifeAgentApplication,
            ) {
                value = withContext(Dispatchers.IO) {
                    lifeAgentApplication.openStorage().fold(
                        onSuccess = StorageState::Ready,
                        onFailure = StorageState::Unavailable,
                    )
                }
            }

            when (val state = storageState) {
                StorageState.Loading -> LoadingLifeAgentApp()
                is StorageState.Ready -> ReadyLifeAgentApp(
                    container = state.container,
                    activity = this,
                )

                is StorageState.Unavailable -> UnavailableLifeAgentApp()
            }
        }
    }
}

@Composable
private fun LoadingLifeAgentApp() {
    LifeAgentTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Открываем локальное хранилище…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun ReadyLifeAgentApp(
    container: AppContainer,
    activity: MainActivity,
) {
    val notesFactory = remember(container) {
        viewModelFactory {
            initializer {
                NotesViewModel(
                    repository = container.notesRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
    val notesViewModel: NotesViewModel = viewModel(factory = notesFactory)
    val syncSetupFactory = remember(container) {
        viewModelFactory {
            initializer {
                container.createSyncSetupController()
            }
        }
    }
    val syncSetupController: DefaultSyncSetupController = viewModel(
        factory = syncSetupFactory,
    )
    val scope = rememberCoroutineScope()

    val preferences = remember(activity) {
        activity.getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
    }
    var showFirstRun by rememberSaveable {
        mutableStateOf(!preferences.getBoolean(FIRST_RUN_COMPLETE, false))
    }
    var exportPhase by remember { mutableStateOf(ExportUiPhase.Idle) }
    var externalMessage by remember { mutableStateOf<AppSnackbarMessage?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(JSON_MIME_TYPE),
    ) { destination ->
        if (destination == null) {
            exportPhase = exportPhase.after(ExportUiEvent.DestinationCancelled)
            return@rememberLauncherForActivityResult
        }

        exportPhase = exportPhase.after(ExportUiEvent.DestinationAccepted)
        externalMessage = appMessage("Готовим проверенный JSON-экспорт…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    deliverExportDocument(
                        generate = { container.exportNotes() },
                        outputFactory = ContentResolverTruncatingOutputFactory(
                            contentResolver = activity.contentResolver,
                            destination = destination,
                        ),
                    )
                }
                externalMessage = result.toUserMessage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                externalMessage = appMessage(
                    "Не удалось завершить экспорт. Возможно, в выбранном файле остались " +
                        "неполные данные — удалите его вручную. Данные в приложении не изменены.",
                )
            } finally {
                exportPhase = exportPhase.after(ExportUiEvent.DeliveryFinished)
            }
        }
    }

    LifeAgentApp(
        notesController = notesViewModel,
        syncSetupController = syncSetupController,
        showFirstRun = showFirstRun,
        onContinueLocally = {
            preferences.edit().putBoolean(FIRST_RUN_COMPLETE, true).apply()
            showFirstRun = false
        },
        onExportNotes = {
            val nextPhase = exportPhase.after(ExportUiEvent.ChooseRequested)
            if (nextPhase == exportPhase) return@LifeAgentApp

            exportPhase = nextPhase
            try {
                createDocument.launch(EXPORT_FILENAME)
            } catch (cancelled: CancellationException) {
                exportPhase = exportPhase.after(ExportUiEvent.LaunchFailed)
                throw cancelled
            } catch (_: Exception) {
                exportPhase = exportPhase.after(ExportUiEvent.LaunchFailed)
                externalMessage = appMessage(
                    "Не удалось открыть выбор файла. Данные в приложении не изменены.",
                )
            }
        },
        externalMessage = externalMessage,
        onExternalMessageConsumed = { consumedId ->
            if (externalMessage?.id == consumedId) {
                externalMessage = null
            }
        },
    )
}

@Composable
private fun UnavailableLifeAgentApp() {
    var externalMessage by remember {
        mutableStateOf<AppSnackbarMessage?>(
            appMessage(
                "Зашифрованное локальное хранилище недоступно. " +
                    "Запись данных отключена; существующие данные не удалены.",
            ),
        )
    }
    LifeAgentApp(
        fallbackLastCommitted = LastNoteUiState.Failed,
        externalMessage = externalMessage,
        onExternalMessageConsumed = { consumedId ->
            if (externalMessage?.id == consumedId) {
                externalMessage = null
            }
        },
    )
}

private fun ExportDeliveryResult.toUserMessage(): AppSnackbarMessage =
    when (this) {
        ExportDeliveryResult.Success ->
            appMessage("Экспорт заметок сохранён")

        ExportDeliveryResult.GenerationFailed ->
            appMessage(
                "Не удалось подготовить экспорт. Выбранный файл не был записан; " +
                    "данные в приложении не изменены.",
            )

        is ExportDeliveryResult.WriteFailed ->
            if (destinationSanitized) {
                appMessage(
                    "Не удалось сохранить экспорт. Незавершённый файл очищен; " +
                        "данные в приложении не изменены.",
                )
            } else {
                appMessage(
                    "Не удалось сохранить экспорт. Возможно, в выбранном файле остались " +
                        "неполные данные — удалите его вручную. " +
                        "Данные в приложении не изменены.",
                )
            }
    }

private fun appMessage(text: String): AppSnackbarMessage = AppSnackbarMessage(
    id = UUID.randomUUID().toString(),
    text = text,
)

private sealed interface StorageState {
    data object Loading : StorageState

    data class Ready(
        val container: AppContainer,
    ) : StorageState

    data class Unavailable(
        val cause: Throwable,
    ) : StorageState {
        override fun toString(): String = "StorageState.Unavailable"
    }
}

private const val UI_PREFERENCES = "life-agent-ui"
private const val FIRST_RUN_COMPLETE = "first-run-local-mode-complete"
private const val JSON_MIME_TYPE = "application/json"
private const val EXPORT_FILENAME = "life-agent-notes.json"
