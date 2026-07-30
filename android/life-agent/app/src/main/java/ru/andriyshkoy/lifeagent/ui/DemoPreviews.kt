package ru.andriyshkoy.lifeagent.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

@Preview(
    name = "Add · light",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun AddLightPreview() {
    LifeAgentApp(
        initialThemeMode = ThemeMode.Light,
        clock = PREVIEW_CLOCK,
        zoneId = PREVIEW_ZONE_ID,
    )
}

@Preview(
    name = "Add · dark",
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun AddDarkPreview() {
    LifeAgentApp(
        initialThemeMode = ThemeMode.Dark,
        clock = PREVIEW_CLOCK,
        zoneId = PREVIEW_ZONE_ID,
    )
}

@Preview(
    name = "Add · expanded",
    widthDp = 960,
    heightDp = 720,
    showBackground = true,
)
@Composable
private fun AddExpandedPreview() {
    LifeAgentApp(
        initialThemeMode = ThemeMode.Light,
        forceExpanded = true,
        clock = PREVIEW_CLOCK,
        zoneId = PREVIEW_ZONE_ID,
    )
}

@Preview(
    name = "Food capture",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun FoodCapturePreview() {
    LifeAgentApp(
        initialRoute = DemoRoute.CaptureFood,
        initialThemeMode = ThemeMode.Light,
        clock = PREVIEW_CLOCK,
        zoneId = PREVIEW_ZONE_ID,
    )
}

private val PREVIEW_CLOCK = Clock.fixed(
    Instant.parse("2026-07-29T03:00:00Z"),
    ZoneOffset.UTC,
)
private val PREVIEW_ZONE_ID = ZoneId.of("Asia/Novosibirsk")
