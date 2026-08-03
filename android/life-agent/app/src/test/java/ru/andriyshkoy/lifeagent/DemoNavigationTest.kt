package ru.andriyshkoy.lifeagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.andriyshkoy.lifeagent.ui.DemoRoute
import ru.andriyshkoy.lifeagent.ui.TopLevelDestination
import ru.andriyshkoy.lifeagent.ui.topLevelDestination

class DemoNavigationTest {
    @Test
    fun topLevelRoutesMapToNavigationDestinations() {
        assertEquals(TopLevelDestination.Add, DemoRoute.Add.topLevelDestination())
        assertEquals(TopLevelDestination.Catalogs, DemoRoute.Catalogs.topLevelDestination())
        assertEquals(TopLevelDestination.Settings, DemoRoute.Settings.topLevelDestination())
    }

    @Test
    fun captureRoutesDoNotRenderTopLevelNavigation() {
        assertNull(DemoRoute.CaptureFood.topLevelDestination())
        assertNull(DemoRoute.CaptureWellbeing.topLevelDestination())
        assertNull(DemoRoute.CaptureMedication.topLevelDestination())
        assertNull(DemoRoute.CaptureNote.topLevelDestination())
    }
}
