package ru.andriyshkoy.lifeagent.persistence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import ru.andriyshkoy.lifeagent.AppContainer
import ru.andriyshkoy.lifeagent.LifeAgentApplication
import ru.andriyshkoy.lifeagent.MainActivity
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.dao.LocalTableCounts
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.NoteSnapshot
import ru.andriyshkoy.lifeagent.ui.notes.NotesUiState
import ru.andriyshkoy.lifeagent.ui.notes.NotesViewModel
import ru.andriyshkoy.lifeagent.ui.screens.WELLBEING_COMMENT_TAG
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRecordStatus
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingSnapshot

/**
 * Black-box UI coverage backed by the real application graph:
 *
 * MainActivity -> production ViewModels -> Room repositories -> production SQLCipher DB.
 *
 * The last two methods are deliberately runner-selectable phases. A normal managed-device
 * run proves that they are independently valid; scripts/run_android_local_cold_start_smoke.sh
 * runs them in separate processes with an explicit force-stop between them.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivityOfflinePersistenceInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun launchMainActivityAsynchronously() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClass(context, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        waitForResumedActivity()
    }

    @After
    fun finishMainActivity() {
        resumedActivity()?.let { activity ->
            instrumentation.runOnMainSync {
                activity.finishAndRemoveTask()
            }
        }
        waitForNoResumedActivity()
    }

    @Test
    fun acceptanceFlowIsOfflineAppendOnlyAndRestoredAcrossActivityRecreation() {
        acceptanceStep("wait for initial persistent state") {
            assertNoTransportPermissions()
            waitForReadyApplication()
        }

        val container = productionContainer()
        val before = tableCounts(container)
        val runId = UUID.randomUUID().toString().take(8)
        val discardedDraft = "Local synthetic discarded draft $runId"
        val createdText = "Local synthetic offline note $runId"
        val correctedText = "Local synthetic corrected note $runId"

        acceptanceStep("enter unsaved draft") {
            openNoteEditor()
            editor().performTextInput(discardedDraft)
            closeSoftKeyboard()
        }

        // This is Activity/configuration recreation, not process-death evidence.
        acceptanceStep("restore draft after Activity recreation") {
            recreateActivity()
            waitForText("Новая заметка")
            composeRule.onNodeWithText(discardedDraft).assertIsDisplayed()
        }

        acceptanceStep("keep editing after system back") {
            pressBack()
            waitForText("Закрыть без сохранения?")
            composeRule.onNodeWithText("Продолжить редактирование").performClick()
            composeRule.onNodeWithText(discardedDraft).assertIsDisplayed()
        }

        acceptanceStep("discard draft without persistence mutation") {
            composeRule.onNodeWithText("Отмена").performClick()
            waitForText("Закрыть без сохранения?")
            composeRule.onNodeWithText("Удалить черновик").performClick()
            waitForText("Что записать?")
            assertCounts(before, tableCounts(container), mutationCount = 0)
        }

        val created = acceptanceStep("create append-only local note") {
            createNote(createdText)
            assertCurrentNote(
                container = container,
                text = createdText,
                expectedStatus = NoteRecordStatus.ACTIVE,
                expectedRevisionNo = 1,
            ).also {
                assertCounts(before, tableCounts(container), mutationCount = 1)
            }
        }

        val corrected = acceptanceStep("append local correction") {
            composeRule.onNode(hasText("Исправить") and hasClickAction())
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForText("Исправить заметку")
            editor().performTextReplacement(correctedText)
            closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(CREATED_NOTE_SNACKBAR_TEXT)
                .assertIsDisplayed()
            val snackbarBounds = composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
                    hasAnyDescendant(hasText(CREATED_NOTE_SNACKBAR_TEXT)),
                useUnmergedTree = true,
            ).fetchSemanticsNode().boundsInRoot
            val saveButton = composeRule.onNode(
                hasText("Сохранить исправление") and hasClickAction(),
            )
            val saveButtonBounds = saveButton.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Snackbar must not overlap the correction save button. " +
                    "snackbar=$snackbarBounds, saveButton=$saveButtonBounds",
                snackbarBounds.bottom <= saveButtonBounds.top,
            )
            saveButton
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            waitForCorrectionClickAcknowledgement(
                container = container,
                eventId = created.eventId,
                correctedText = correctedText,
            )
            waitForCorrectedNotePersistence(
                container = container,
                eventId = created.eventId,
                correctedText = correctedText,
            )
            waitForText("Что записать?")
            scrollToText(correctedText)

            assertCurrentNote(
                container = container,
                text = correctedText,
                expectedStatus = NoteRecordStatus.ACTIVE,
                expectedRevisionNo = 2,
            ).also {
                assertCounts(before, tableCounts(container), mutationCount = 2)
            }
        }
        assertEquals(created.eventId, corrected.eventId)

        acceptanceStep("restore committed correction after Activity recreation") {
            recreateActivity()
            waitForReadyApplication()
            scrollToText(correctedText)
        }

        val retracted = acceptanceStep("append local retraction") {
            composeRule.onNodeWithText("Отменить запись")
                .performScrollTo()
                .performClick()
            waitForText("Отменить запись заметки?")
            clickLastNodeWithText("Отменить запись")
            waitForText("Заметка отменена")

            assertCurrentNote(
                container = container,
                text = correctedText,
                expectedStatus = NoteRecordStatus.RETRACTED,
                expectedRevisionNo = 3,
            ).also {
                assertCounts(before, tableCounts(container), mutationCount = 3)
            }
        }
        assertEquals(created.eventId, retracted.eventId)
    }

    @Test
    fun fullAppCaptureRemainsUsableAtTwoHundredPercentFontScale() {
        assertNoTransportPermissions()
        waitForReadyApplication()

        val originalFontScale = activityFontScale()
        val marker = "Local synthetic 200 percent font ${UUID.randomUUID().toString().take(8)}"
        try {
            setActivityFontScale(2f)
            assertEquals(2f, activityFontScale(), 0.01f)

            openNoteEditor()
            editor().performTextInput(marker)
            closeSoftKeyboard()
            composeRule.onNodeWithText("Сохранить заметку")
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            waitForText("Что записать?")
            scrollToText(marker)

            assertCurrentNote(
                container = productionContainer(),
                text = marker,
                expectedStatus = NoteRecordStatus.ACTIVE,
                expectedRevisionNo = 1,
            )
        } finally {
            setActivityFontScale(originalFontScale)
        }
    }

    @Test
    fun phase1SeedSyntheticWellbeingForExternalColdStart() {
        assertNoTransportPermissions()
        assertAirplaneModeWhenRequired()
        waitForReadyApplication()

        val marker = coldStartMarker()
        val container = productionContainer()
        val existing = findCurrentWellbeing(container, marker)
        if (existing == null) {
            createWellbeing(marker)
        }
        assertCurrentWellbeing(
            container = container,
            comment = marker,
            expectedStatus = WellbeingRecordStatus.ACTIVE,
            expectedRevisionNo = 1,
        )
    }

    @Test
    fun phase2VerifySyntheticWellbeingAfterExternalColdStart() {
        assertNoTransportPermissions()
        assertAirplaneModeWhenRequired()
        waitForReadyApplication()

        val marker = coldStartMarker()
        scrollToText(marker)
        assertCurrentWellbeing(
            container = productionContainer(),
            comment = marker,
            expectedStatus = WellbeingRecordStatus.ACTIVE,
            expectedRevisionNo = 1,
        )
    }

    private fun waitForReadyApplication() {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            textExists(ONBOARDING_CONTINUE_TEXT) || textExists("Что записать?")
        }
        if (textExists(ONBOARDING_CONTINUE_TEXT)) {
            composeRule.onNodeWithText(ONBOARDING_CONTINUE_TEXT).performClick()
        }
        waitForText("Что записать?")
        productionContainer()
    }

    private fun openNoteEditor() {
        composeRule.onNodeWithText("Заметка")
            .performScrollTo()
            .performClick()
        waitForText("Новая заметка")
    }

    private fun createNote(text: String) {
        openNoteEditor()
        editor().performTextInput(text)
        closeSoftKeyboard()
        composeRule.onNodeWithText("Сохранить заметку")
            .assertIsEnabled()
            .performClick()
        waitForText("Что записать?")
        scrollToText(text)
    }

    private fun createWellbeing(comment: String) {
        composeRule.onNodeWithText("Самочувствие")
            .performScrollTo()
            .performClick()
        waitForText("Как ты себя чувствуешь?")
        waitForText("Нормальное")
        composeRule.onNodeWithText("Нормальное")
            .performScrollTo()
            .performClick()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(WELLBEING_COMMENT_TAG))
        composeRule.onNodeWithTag(WELLBEING_COMMENT_TAG).performTextInput(comment)
        closeSoftKeyboard()
        composeRule.onNodeWithText("Записать самочувствие")
            .assertIsEnabled()
            .performClick()
        waitForText("Что записать?")
        scrollToText(comment)
    }

    private fun editor() = composeRule.onNode(hasSetTextAction())

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            textExists(text)
        }
    }

    private fun textExists(text: String): Boolean =
        composeRule.onAllNodesWithText(text)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun scrollToText(text: String) {
        if (!textExists(text)) {
            composeRule.onNode(hasScrollAction())
                .performScrollToNode(hasText(text))
        }
        waitForText(text)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun clickLastNodeWithText(text: String) {
        val nodes = composeRule.onAllNodesWithText(text)
        val lastIndex = nodes.fetchSemanticsNodes().lastIndex
        assertTrue("Expected a node with text: $text", lastIndex >= 0)
        nodes[lastIndex].performClick()
    }

    private fun assertNoTransportPermissions() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

        val forbiddenTransportPermissions = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.CHANGE_NETWORK_STATE,
        )
        forbiddenTransportPermissions.forEach { permission ->
            assertFalse("Transport permission must be absent: $permission", requested.contains(permission))
        }
    }

    private fun assertAirplaneModeWhenRequired() {
        if (
            InstrumentationRegistry.getArguments()
                .getString(REQUIRE_AIRPLANE_MODE_ARGUMENT) != "true"
        ) {
            return
        }
        assertEquals(
            "Host acceptance requested real airplane mode",
            1,
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ),
        )
    }

    private fun productionContainer(): AppContainer =
        runBlocking(Dispatchers.IO) {
            val application = context.applicationContext as LifeAgentApplication
            application.openStorage().getOrThrow()
        }

    private fun productionDatabase(container: AppContainer): LifeAgentDatabase {
        val field = AppContainer::class.java.getDeclaredField("database")
        field.isAccessible = true
        return field.get(container) as LifeAgentDatabase
    }

    private fun tableCounts(container: AppContainer): LocalTableCounts =
        runBlocking(Dispatchers.IO) {
            productionDatabase(container).lifeEventMutationDao().tableCounts()
        }

    private fun findCurrentNote(
        container: AppContainer,
        text: String,
    ): NoteSnapshot? = runBlocking(Dispatchers.IO) {
        val repository = container.notesRepository
        repository.exportSnapshot().events.forEach { event ->
            repository.getByEventId(event.eventId)
                ?.takeIf { it.text == text }
                ?.let { return@runBlocking it }
        }
        null
    }

    private fun findCurrentWellbeing(
        container: AppContainer,
        comment: String,
    ): WellbeingSnapshot? = runBlocking(Dispatchers.IO) {
        val repository = container.wellbeingRepository
        repository.exportSnapshot().events.forEach { event ->
            repository.getByEventId(event.eventId)
                ?.takeIf { it.payload.comment == comment }
                ?.let { return@runBlocking it }
        }
        null
    }

    private fun waitForCorrectedNotePersistence(
        container: AppContainer,
        eventId: UUID,
        correctedText: String,
    ) {
        val viewModel = productionNotesViewModel()
        var lastState = viewModel.uiState.value
        var lastNote = currentNote(container, eventId)
        val terminalReached = waitForWallClockValue {
            lastState = viewModel.uiState.value
            lastNote = currentNote(container, eventId)
            true.takeIf {
                lastNote?.text == correctedText ||
                    lastState.editor == null ||
                    lastState.editor?.retryAvailable == true ||
                    lastState.editor?.formError != null
            }
        }

        if (
            terminalReached == null ||
            lastNote?.text != correctedText ||
            lastState.editor != null
        ) {
            throw correctionSaveFailure(
                container = container,
                state = lastState,
                note = lastNote,
                expectedText = correctedText,
            )
        }
    }

    private fun waitForCorrectionClickAcknowledgement(
        container: AppContainer,
        eventId: UUID,
        correctedText: String,
    ) {
        val acknowledged = runCatching {
            composeRule.waitUntil(timeoutMillis = CLICK_ACK_TIMEOUT_MILLIS) {
                textExists("Сохраняем") || textExists("Что записать?")
            }
        }.isSuccess
        if (acknowledged) return

        throw correctionSaveFailure(
            container = container,
            state = productionNotesViewModel().uiState.value,
            note = currentNote(container, eventId),
            expectedText = correctedText,
        )
    }

    private fun currentNote(
        container: AppContainer,
        eventId: UUID,
    ): NoteSnapshot? = runBlocking(Dispatchers.IO) {
        container.notesRepository.getByEventId(eventId)
    }

    private fun productionNotesViewModel(): NotesViewModel {
        val activity = requireNotNull(resumedActivity())
        var viewModel: NotesViewModel? = null
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(activity)
                .get(NotesViewModel::class.java)
        }
        return requireNotNull(viewModel)
    }

    private fun correctionSaveFailure(
        container: AppContainer,
        state: NotesUiState,
        note: NoteSnapshot?,
        expectedText: String,
    ): AssertionError {
        val editor = state.editor
        val counts = tableCounts(container)
        val semantics = runCatching {
            composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = 8)
        }.getOrElse { failure ->
            "<unavailable: ${failure.javaClass.simpleName}>"
        }
        return AssertionError(
            "Correction did not reach a committed, closed-editor state. " +
                "editorPresent=${editor != null}, " +
                "isSubmitting=${editor?.isSubmitting}, " +
                "retryAvailable=${editor?.retryAvailable}, " +
                "formError=${editor?.formError}, " +
                "lastCommitted=${state.lastCommitted.javaClass.simpleName}, " +
                "storedRevision=${note?.revisionNo}, " +
                "storedTextMatches=${note?.text == expectedText}, " +
                "counts=$counts.\nUnmerged semantics:\n$semantics",
        )
    }

    private fun assertCurrentNote(
        container: AppContainer,
        text: String,
        expectedStatus: NoteRecordStatus,
        expectedRevisionNo: Int,
    ): NoteSnapshot {
        val note = assertNotNullAndReturn(findCurrentNote(container, text))
        assertEquals(expectedStatus, note.status)
        assertEquals(expectedRevisionNo, note.revisionNo)

        return note
    }

    private fun assertCurrentWellbeing(
        container: AppContainer,
        comment: String,
        expectedStatus: WellbeingRecordStatus,
        expectedRevisionNo: Int,
    ): WellbeingSnapshot {
        val wellbeing = assertNotNullAndReturn(findCurrentWellbeing(container, comment))
        assertEquals(expectedStatus, wellbeing.status)
        assertEquals(expectedRevisionNo, wellbeing.revisionNo)
        assertEquals(1, wellbeing.payload.values.size)
        assertEquals("Общее самочувствие", wellbeing.payload.values.single().dimensionLabel)
        assertEquals("Нормальное", wellbeing.payload.values.single().optionLabel)

        return wellbeing
    }

    private fun assertCounts(
        before: LocalTableCounts,
        after: LocalTableCounts,
        mutationCount: Int,
    ) {
        assertEquals(before.events + if (mutationCount == 0) 0 else 1, after.events)
        assertEquals(before.heads + if (mutationCount == 0) 0 else 1, after.heads)
        assertEquals(before.captures + mutationCount, after.captures)
        assertEquals(before.revisions + mutationCount, after.revisions)
        assertEquals(before.parents + (mutationCount - 1).coerceAtLeast(0), after.parents)
    }

    private fun activityFontScale(): Float {
        return requireNotNull(resumedActivity()).resources.configuration.fontScale
    }

    @Suppress("DEPRECATION")
    private fun setActivityFontScale(fontScale: Float) {
        val activity = requireNotNull(resumedActivity())
        instrumentation.runOnMainSync {
            val configuration = Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
            }
            activity.resources.updateConfiguration(
                configuration,
                activity.resources.displayMetrics,
            )
            activity.application.resources.updateConfiguration(
                configuration,
                activity.application.resources.displayMetrics,
            )
        }
        recreateActivity()
        waitForReadyApplication()
    }

    private fun recreateActivity() {
        val previous = requireNotNull(resumedActivity())
        instrumentation.runOnMainSync {
            previous.recreate()
        }
        waitForWallClockCondition(
            description = "MainActivity recreation",
        ) {
            resumedActivity()?.let { it !== previous } == true
        }
    }

    private fun waitForResumedActivity(): MainActivity =
        waitForWallClockValue(producer = ::resumedActivity)
            ?: throw AssertionError("Timed out waiting for MainActivity to reach RESUMED")

    private fun waitForNoResumedActivity() {
        waitForWallClockCondition(
            description = "MainActivity cleanup",
            timeoutMillis = CLEANUP_TIMEOUT_MILLIS,
        ) {
            resumedActivity() == null
        }
    }

    private fun resumedActivity(): MainActivity? {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .singleOrNull()
        }
        return activity
    }

    private fun waitForWallClockCondition(
        description: String,
        timeoutMillis: Long = UI_TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ) {
        if (
            waitForWallClockValue(
                timeoutMillis = timeoutMillis,
                producer = { condition().takeIf { it } },
            ) == null
        ) {
            throw AssertionError("Timed out waiting for $description")
        }
    }

    private fun <T : Any> waitForWallClockValue(
        timeoutMillis: Long = UI_TIMEOUT_MILLIS,
        producer: () -> T?,
    ): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            producer()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)
        producer()?.let { return it }
        return null
    }

    private fun coldStartMarker(): String =
        InstrumentationRegistry.getArguments()
            .getString(COLD_MARKER_ARGUMENT)
            ?.takeIf { it.matches(COLD_MARKER_PATTERN) }
            ?: DEFAULT_COLD_MARKER

    private fun <T : Any> assertNotNullAndReturn(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }

    private inline fun <T> acceptanceStep(
        label: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (failure: Throwable) {
        throw AssertionError("Local acceptance failed at step: $label", failure)
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 30_000L
        const val CLICK_ACK_TIMEOUT_MILLIS = 5_000L
        const val CLEANUP_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 25L
        const val COLD_MARKER_ARGUMENT = "localColdStartMarker"
        const val REQUIRE_AIRPLANE_MODE_ARGUMENT = "localRequireAirplaneMode"
        const val DEFAULT_COLD_MARKER = "local-synthetic-wellbeing-cold-start-v1"
        const val ONBOARDING_CONTINUE_TEXT = "Продолжить локально"
        const val CREATED_NOTE_SNACKBAR_TEXT = "Заметка сохранена на устройстве"
        val COLD_MARKER_PATTERN = Regex("[A-Za-z0-9._-]{8,96}")
    }
}
