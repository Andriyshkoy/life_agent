package ru.andriyshkoy.lifeagent.ui.notes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.DemoRoute
import ru.andriyshkoy.lifeagent.ui.screens.AddScreen
import ru.andriyshkoy.lifeagent.ui.screens.NoteCaptureScreen
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme

class NoteCaptureScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blankNoteCannotBeSaved() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                NoteCaptureScreen(
                    state = NoteEditorUiState(),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Сохранить заметку").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(
            "Время события: Сейчас. Часовой пояс: ${java.time.ZoneId.systemDefault().id}",
        ).assertIsDisplayed()
    }

    @Test
    fun validNoteRemainsUsableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                LifeAgentTheme(darkTheme = false) {
                    NoteCaptureScreen(
                        state = NoteEditorUiState(text = "Текст для проверки"),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Текст для проверки").assertIsDisplayed()
        composeRule.onNodeWithText("Сохранить заметку").assertIsEnabled()
        composeRule.onNodeWithText("Отмена").assertIsDisplayed()
    }

    @Test
    fun failedLastActionExposesRetryAction() {
        var retryCount = 0
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                AddScreen(
                    expanded = false,
                    onNavigate = { _: DemoRoute -> },
                    lastNote = LastNoteUiState.Failed,
                    persistenceAvailable = true,
                    onRetryLastNote = { retryCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Повторить")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, retryCount)
        }
    }
}
