package ru.andriyshkoy.lifeagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteAction
import ru.andriyshkoy.lifeagent.ui.notes.NoteDialogUi
import ru.andriyshkoy.lifeagent.ui.notes.NoteEditorUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteTextError
import ru.andriyshkoy.lifeagent.ui.notes.NoteTimestampUiState
import ru.andriyshkoy.lifeagent.ui.notes.NotesController
import ru.andriyshkoy.lifeagent.ui.notes.NotesUiState
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

/**
 * Stable, debug-only UI states for Android Studio's Compose Preview.
 *
 * These fixtures never participate in runtime navigation or persistence.
 */
internal object DeveloperUiFixtures {
    val clock: Clock = Clock.fixed(
        Instant.parse("2026-07-29T03:00:00Z"),
        ZoneOffset.UTC,
    )
    val zoneId: ZoneId = ZoneId.of("Asia/Novosibirsk")

    val loading = NotesUiState(
        editorLoading = true,
        lastCommitted = LastNoteUiState.Loading,
        persistenceAvailable = true,
    )
    val empty = NotesUiState(
        lastCommitted = LastNoteUiState.Empty,
        persistenceAvailable = true,
    )
    val failed = NotesUiState(
        lastCommitted = LastNoteUiState.Failed,
        persistenceAvailable = true,
    )
    val validation = NotesUiState(
        editor = NoteEditorUiState(
            textError = NoteTextError.Empty,
            timestamp = fixtureTimestamp(),
        ),
        lastCommitted = LastNoteUiState.Empty,
        persistenceAvailable = true,
    )
    val retry = NotesUiState(
        editor = NoteEditorUiState(
            text = "После прогулки стало легче сосредоточиться.",
            timestamp = fixtureTimestamp(),
            formError = "Не удалось подтвердить сохранение",
            retryAvailable = true,
        ),
        lastCommitted = LastNoteUiState.Empty,
        persistenceAvailable = true,
    )
    val destructiveDialog = NotesUiState(
        editor = NoteEditorUiState(
            text = "Черновик заметки",
            timestamp = fixtureTimestamp(),
        ),
        lastCommitted = LastNoteUiState.Empty,
        dialog = NoteDialogUi.DiscardDraft,
        persistenceAvailable = true,
    )
    val storageUnavailable = NotesUiState(
        lastCommitted = LastNoteUiState.Failed,
        persistenceAvailable = false,
    )

    private fun fixtureTimestamp() = NoteTimestampUiState(
        defaultTimezoneId = zoneId.id,
    )
}

@Preview(
    name = "Loading",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun LoadingStatePreview() {
    FixtureApp(DemoRoute.CaptureNote, DeveloperUiFixtures.loading)
}

@Preview(
    name = "Empty",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun EmptyStatePreview() {
    FixtureApp(DemoRoute.Add, DeveloperUiFixtures.empty)
}

@Preview(
    name = "Failed",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun FailedStatePreview() {
    FixtureApp(DemoRoute.Add, DeveloperUiFixtures.failed)
}

@Preview(
    name = "Validation error",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun ValidationStatePreview() {
    FixtureApp(DemoRoute.CaptureNote, DeveloperUiFixtures.validation)
}

@Preview(
    name = "Exact retry",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun RetryStatePreview() {
    FixtureApp(DemoRoute.CaptureNote, DeveloperUiFixtures.retry)
}

@Preview(
    name = "Destructive dialog",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun DestructiveDialogStatePreview() {
    FixtureApp(DemoRoute.CaptureNote, DeveloperUiFixtures.destructiveDialog)
}

@Preview(
    name = "Storage unavailable",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun StorageUnavailableStatePreview() {
    FixtureApp(DemoRoute.Diagnostics, DeveloperUiFixtures.storageUnavailable)
}

@Preview(
    name = "First run",
    group = "M1 states",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun FirstRunStatePreview() {
    FixtureApp(
        route = DemoRoute.Add,
        state = DeveloperUiFixtures.empty,
        showFirstRun = true,
    )
}

@Composable
private fun FixtureApp(
    route: DemoRoute,
    state: NotesUiState,
    showFirstRun: Boolean = false,
) {
    val controller = remember(state) { FixtureNotesController(state) }
    LifeAgentApp(
        initialRoute = route,
        initialThemeMode = ThemeMode.Light,
        clock = DeveloperUiFixtures.clock,
        zoneId = DeveloperUiFixtures.zoneId,
        appVersion = "0.1.0-fixture",
        notesController = controller,
        showFirstRun = showFirstRun,
    )
}

private class FixtureNotesController(initialState: NotesUiState) : NotesController {
    override val uiState: StateFlow<NotesUiState> = MutableStateFlow(initialState)

    override fun dispatch(action: NoteAction) = Unit
}
