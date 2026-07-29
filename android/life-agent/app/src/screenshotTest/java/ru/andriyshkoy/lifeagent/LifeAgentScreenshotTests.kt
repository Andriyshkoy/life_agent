package ru.andriyshkoy.lifeagent

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.andriyshkoy.lifeagent.ui.DemoRoute
import ru.andriyshkoy.lifeagent.ui.LifeAgentApp
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

@PreviewTest
@Preview(
    name = "add_light_compact",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
fun AddLightCompactScreenshot() {
    LifeAgentApp(initialThemeMode = ThemeMode.Light)
}

@PreviewTest
@Preview(
    name = "add_dark_compact",
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun AddDarkCompactScreenshot() {
    LifeAgentApp(initialThemeMode = ThemeMode.Dark)
}

@PreviewTest
@Preview(
    name = "food_light_compact",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
fun FoodLightCompactScreenshot() {
    LifeAgentApp(
        initialRoute = DemoRoute.CaptureFood,
        initialThemeMode = ThemeMode.Light,
    )
}

@PreviewTest
@Preview(
    name = "settings_dark_compact",
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun SettingsDarkCompactScreenshot() {
    LifeAgentApp(
        initialRoute = DemoRoute.Settings,
        initialThemeMode = ThemeMode.Dark,
    )
}

@PreviewTest
@Preview(
    name = "add_light_expanded",
    widthDp = 960,
    heightDp = 720,
    showBackground = true,
)
@Composable
fun AddLightExpandedScreenshot() {
    LifeAgentApp(
        initialThemeMode = ThemeMode.Light,
        forceExpanded = true,
    )
}
