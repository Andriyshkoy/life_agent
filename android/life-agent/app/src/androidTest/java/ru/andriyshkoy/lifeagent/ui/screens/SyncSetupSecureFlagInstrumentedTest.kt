package ru.andriyshkoy.lifeagent.ui.screens

import android.view.WindowManager
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.andriyshkoy.lifeagent.MainActivity

class SyncSetupSecureFlagInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun secureFlagIsScopedToSynchronizationSurface() {
        composeRule.waitForIdle()
        if (
            composeRule.onAllNodesWithText("Продолжить локально")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            composeRule.onNodeWithText("Продолжить локально").performClick()
        }

        composeRule.onNodeWithText("Настройки").performClick()
        composeRule.onNodeWithText("Синхронизация").performClick()
        composeRule.waitForIdle()

        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            composeRule.activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE,
        )

        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.waitForIdle()

        assertEquals(
            0,
            composeRule.activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
