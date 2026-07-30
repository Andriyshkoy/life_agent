package ru.andriyshkoy.lifeagent

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.andriyshkoy.lifeagent.ui.DemoRoute
import ru.andriyshkoy.lifeagent.ui.LifeAgentApp
import ru.andriyshkoy.lifeagent.ui.notes.LastNoteUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteAction
import ru.andriyshkoy.lifeagent.ui.notes.NoteEditorUiState
import ru.andriyshkoy.lifeagent.ui.notes.NoteTimestampUiState
import ru.andriyshkoy.lifeagent.ui.notes.NotesController
import ru.andriyshkoy.lifeagent.ui.notes.NotesUiState
import ru.andriyshkoy.lifeagent.ui.screens.NoteCaptureScreen
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

@PreviewTest
@Preview(
    name = "add_light_compact",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
fun AddLightCompactScreenshot() {
    val notesController = remember { PersistedNotesScreenshotController() }
    LifeAgentApp(
        initialThemeMode = ThemeMode.Light,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        notesController = notesController,
    )
}

@PreviewTest
@Preview(
    name = "add_dark_compact",
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun AddDarkCompactScreenshot() {
    val notesController = remember { PersistedNotesScreenshotController() }
    LifeAgentApp(
        initialThemeMode = ThemeMode.Dark,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        notesController = notesController,
    )
}

@PreviewTest
@Preview(
    name = "food_light_compact",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
fun FoodLightCompactScreenshot() {
    LifeAgentApp(
        initialRoute = DemoRoute.CaptureFood,
        initialThemeMode = ThemeMode.Light,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
    )
}

@PreviewTest
@Preview(
    name = "settings_dark_compact",
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun SettingsDarkCompactScreenshot() {
    val notesController = remember { PersistedNotesScreenshotController() }
    LifeAgentApp(
        initialRoute = DemoRoute.Settings,
        initialThemeMode = ThemeMode.Dark,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        appVersion = SCREENSHOT_APP_VERSION,
        notesController = notesController,
    )
}

@PreviewTest
@Preview(
    name = "timezone_light_compact",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
fun TimeZoneLightCompactScreenshot() {
    LifeAgentApp(
        initialRoute = DemoRoute.TimeZone,
        initialThemeMode = ThemeMode.Light,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        appVersion = SCREENSHOT_APP_VERSION,
    )
}

@PreviewTest
@Preview(
    name = "diagnostics_light_compact",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
fun DiagnosticsLightCompactScreenshot() {
    val notesController = remember { PersistedNotesScreenshotController() }
    LifeAgentApp(
        initialRoute = DemoRoute.Diagnostics,
        initialThemeMode = ThemeMode.Light,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        appVersion = SCREENSHOT_APP_VERSION,
        notesController = notesController,
    )
}

@PreviewTest
@Preview(
    name = "privacy_light_compact",
    widthDp = 412,
    heightDp = 1100,
    showBackground = true,
)
@Composable
fun PrivacyLightCompactScreenshot() {
    LifeAgentApp(
        initialRoute = DemoRoute.Privacy,
        initialThemeMode = ThemeMode.Light,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        appVersion = SCREENSHOT_APP_VERSION,
    )
}

@PreviewTest
@Preview(
    name = "add_light_expanded",
    widthDp = 960,
    heightDp = 720,
    showBackground = true,
)
@Composable
fun AddLightExpandedScreenshot() {
    val notesController = remember { PersistedNotesScreenshotController() }
    LifeAgentApp(
        initialThemeMode = ThemeMode.Light,
        forceExpanded = true,
        clock = SCREENSHOT_CLOCK,
        zoneId = SCREENSHOT_ZONE_ID,
        notesController = notesController,
    )
}

@PreviewTest
@Preview(
    name = "note_light_large_font",
    widthDp = 412,
    heightDp = 915,
    fontScale = 2f,
    showBackground = true,
)
@Composable
fun NoteLightLargeFontScreenshot() {
    LifeAgentTheme(darkTheme = false) {
        NoteCaptureScreen(
            state = NoteEditorUiState(
                text = "После прогулки стало легче сосредоточиться.",
                timestamp = NoteTimestampUiState(
                    defaultTimezoneId = SCREENSHOT_ZONE_ID.id,
                ),
            ),
            onAction = {},
        )
    }
}

private class PersistedNotesScreenshotController : NotesController {
    override val uiState: StateFlow<NotesUiState> = MutableStateFlow(
        NotesUiState(
            lastCommitted = LastNoteUiState.Empty,
            persistenceAvailable = true,
        ),
    )

    override fun dispatch(action: NoteAction) = Unit
}

private val SCREENSHOT_CLOCK = Clock.fixed(
    Instant.parse("2026-07-29T03:00:00Z"),
    ZoneOffset.UTC,
)
private val SCREENSHOT_ZONE_ID = ZoneId.of("Asia/Novosibirsk")
private const val SCREENSHOT_APP_VERSION = "0.1.0-screenshot"
