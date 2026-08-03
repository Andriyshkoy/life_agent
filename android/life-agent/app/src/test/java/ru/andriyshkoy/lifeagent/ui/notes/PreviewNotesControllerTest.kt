package ru.andriyshkoy.lifeagent.ui.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class PreviewNotesControllerTest {
    @Test
    fun unavailableFallbackDoesNotClaimThatLastNoteIsEmpty() {
        val controller = PreviewNotesController(
            initialLastCommitted = LastNoteUiState.Failed,
        )

        controller.dispatch(NoteAction.RetryLastCommitted)

        assertSame(LastNoteUiState.Failed, controller.uiState.value.lastCommitted)
        assertFalse(controller.uiState.value.persistenceAvailable)
    }

    @Test
    fun toolingPreviewCanStillDeclareItsSyntheticEmptyState() {
        val controller = PreviewNotesController()

        assertSame(LastNoteUiState.Empty, controller.uiState.value.lastCommitted)
        assertFalse(controller.uiState.value.persistenceAvailable)
    }
}
