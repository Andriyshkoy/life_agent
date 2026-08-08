package ru.andriyshkoy.lifeagent.ui.wellbeing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableWellbeingControllerTest {
    @Test
    fun unavailableFallbackNeverPretendsToSave() {
        val controller = UnavailableWellbeingController()

        assertFalse(controller.uiState.value.persistenceAvailable)
        assertTrue(controller.uiState.value.catalog is WellbeingCatalogUiState.Unavailable)

        controller.dispatch(WellbeingAction.StartCreate)
        assertNull(controller.uiState.value.editor)
        assertTrue(controller.uiState.value.editorLoadError != null)

        controller.dispatch(WellbeingAction.Save)
        assertNull(controller.uiState.value.completion)
    }
}
