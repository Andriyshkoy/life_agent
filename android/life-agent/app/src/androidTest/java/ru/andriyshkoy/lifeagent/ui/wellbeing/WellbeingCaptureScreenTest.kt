package ru.andriyshkoy.lifeagent.ui.wellbeing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.screens.WELLBEING_CAPTURE_TAG
import ru.andriyshkoy.lifeagent.ui.screens.WELLBEING_COMMENT_TAG
import ru.andriyshkoy.lifeagent.ui.screens.WellbeingCaptureScreen
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingValueSnapshot

class WellbeingCaptureScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyAndCommentOnlyStatesCannotBeSaved() {
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                WellbeingCaptureScreen(
                    state = editor(comment = "Комментарий без выбора"),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(WELLBEING_CAPTURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WELLBEING_COMMENT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Нормальное").assertIsDisplayed()
        composeRule.onNodeWithText("Записать самочувствие").assertIsNotEnabled()
        composeRule.onNodeWithText(
            "Для сохранения нужен хотя бы один явный выбор.",
        ).assertIsDisplayed()
    }

    @Test
    fun selectedValueEnablesSaveAndDispatchesOneActionAtLargeFontScale() {
        val actions = mutableListOf<WellbeingAction>()
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                LifeAgentTheme(darkTheme = false) {
                    WellbeingCaptureScreen(
                        state = editor(selected = true),
                        onAction = actions::add,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Записать самочувствие")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.count { it == WellbeingAction.Save })
        }
    }

    @Test
    fun selectedValueHasExplicitClearAction() {
        val actions = mutableListOf<WellbeingAction>()
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                WellbeingCaptureScreen(
                    state = editor(selected = true),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Очистить").performClick()

        composeRule.runOnIdle {
            assertTrue(actions.single() is WellbeingAction.ClearDimension)
        }
    }

    private fun editor(
        comment: String = "",
        selected: Boolean = false,
    ): WellbeingEditorUiState {
        val dimensionId = UUID.fromString("00000000-0000-0000-0000-000000000100")
        val optionId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val snapshot = WellbeingValueSnapshot(
            dimensionId = dimensionId,
            dimensionVersion = 1,
            dimensionLabel = "Общее самочувствие",
            optionId = optionId,
            optionVersion = 1,
            optionLabel = "Нормальное",
            optionSortOrder = 0,
        )
        return WellbeingEditorUiState(
            dimensions = listOf(
                WellbeingDimensionUi(
                    dimensionId = dimensionId,
                    label = "Общее самочувствие",
                    sortOrder = 0,
                    options = listOf(
                        WellbeingOptionUi(
                            optionId = optionId,
                            label = "Нормальное",
                            sortOrder = 0,
                        ),
                    ),
                    selectedSnapshot = snapshot.takeIf { selected },
                ),
            ),
            comment = comment,
        )
    }
}
