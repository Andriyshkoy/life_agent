package ru.andriyshkoy.lifeagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoRouteTest {
    @Test
    fun captureRoutePredicateIncludesExactlyTheFourCaptureFlows() {
        val expected = setOf(
            DemoRoute.CaptureFood,
            DemoRoute.CaptureWellbeing,
            DemoRoute.CaptureMedication,
            DemoRoute.CaptureNote,
        )

        assertEquals(expected, DemoRoute.entries.filter { it.isCaptureRoute() }.toSet())
    }

    @Test
    fun captureRoutesReturnToAdd() {
        val routes = listOf(
            DemoRoute.CaptureFood,
            DemoRoute.CaptureWellbeing,
            DemoRoute.CaptureMedication,
            DemoRoute.CaptureNote,
        )

        routes.forEach { route ->
            assertEquals(DemoRoute.Add, route.backTarget())
        }
    }

    @Test
    fun catalogDetailsReturnToCatalogs() {
        val routes = listOf(
            DemoRoute.CatalogFood,
            DemoRoute.CatalogWellbeing,
            DemoRoute.CatalogMedication,
        )

        routes.forEach { route ->
            assertEquals(DemoRoute.Catalogs, route.backTarget())
        }
    }

    @Test
    fun settingsDetailsReturnToSettings() {
        val routes = listOf(
            DemoRoute.SyncSetup,
            DemoRoute.HealthConnect,
            DemoRoute.TimeZone,
            DemoRoute.Diagnostics,
            DemoRoute.Privacy,
        )

        routes.forEach { route ->
            assertEquals(DemoRoute.Settings, route.backTarget())
        }
    }
}
