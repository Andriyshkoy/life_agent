package ru.andriyshkoy.lifeagent.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.sync.SyncBootstrapUiStatus
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupController
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupNotice
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupSummary
import ru.andriyshkoy.lifeagent.ui.sync.SyncSetupUiState
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme

class SyncSetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enrollmentCodeIsMaskedAndClearedBeforeOwnershipTransfer() {
        val controller = FakeController(
            SyncSetupUiState.LocalOnly(SyncSetupSummary.Empty),
        )
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                SyncSetupScreen(onBack = {}, controller = controller)
            }
        }

        composeRule.onNodeWithText("Подключить сервер").performClick()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText("Подключить").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput(VALID_CODE)
        composeRule.onNodeWithText("Подключить").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(VALID_CODE, controller.submittedCode)
            assertTrue(controller.submittedArrayWiped)
        }
        composeRule.onNodeWithText("7 групп по 4 символа · введено 0 из 28")
            .assertIsDisplayed()
    }

    @Test
    fun readyStateShowsBodylessStatusAndAcceptedManualRequest() {
        val controller = FakeController(
            SyncSetupUiState.Ready(
                SyncSetupSummary(
                    pendingCount = 2,
                    bootstrap = SyncBootstrapUiStatus.IN_PROGRESS,
                    lastServerConfirmationAt = Instant.parse("2026-08-03T04:05:00Z"),
                ),
            ),
        )
        composeRule.setContent {
            LifeAgentTheme(darkTheme = false) {
                SyncSetupScreen(
                    onBack = {},
                    controller = controller,
                    zoneId = ZoneId.of("Asia/Novosibirsk"),
                )
            }
        }

        composeRule.onNodeWithText("Синхронизация подключена").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("Выполняется").assertIsDisplayed()
        composeRule.onNodeWithText("3 августа 2026, 11:05").assertIsDisplayed()

        composeRule.onNodeWithText("Синхронизировать сейчас").performClick()

        composeRule.onNodeWithText("Запрос на синхронизацию принят").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, controller.enqueueCount) }
    }

    private class FakeController(initial: SyncSetupUiState) : SyncSetupController {
        private val mutableState = MutableStateFlow(initial)
        private val mutableNotices = MutableSharedFlow<SyncSetupNotice>(extraBufferCapacity = 1)

        override val uiState: StateFlow<SyncSetupUiState> = mutableState
        override val notices: Flow<SyncSetupNotice> = mutableNotices

        var submittedCode: String? = null
        var submittedArrayWiped: Boolean = false
        var enqueueCount: Int = 0

        override fun showCodeEntry() {
            val summary = when (val state = mutableState.value) {
                is SyncSetupUiState.LocalOnly -> state.summary
                else -> SyncSetupSummary.Empty
            }
            mutableState.value = SyncSetupUiState.CodeEntry(summary)
        }

        override fun cancelCodeEntry() {
            mutableState.value = SyncSetupUiState.LocalOnly(SyncSetupSummary.Empty)
        }

        override fun submitEnrollment(ownedCode: CharArray) {
            submittedCode = ownedCode.concatToString()
            ownedCode.fill('\u0000')
            submittedArrayWiped = ownedCode.all { it == '\u0000' }
        }

        override fun enqueueNow() {
            enqueueCount += 1
            mutableNotices.tryEmit(SyncSetupNotice.QUEUED)
        }
    }

    private companion object {
        const val VALID_CODE = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345"
    }
}
