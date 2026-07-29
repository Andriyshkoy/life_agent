package ru.andriyshkoy.lifeagent.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode

@Preview(
    name = "Add · light",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
)
@Composable
private fun AddLightPreview() {
    LifeAgentApp(initialThemeMode = ThemeMode.Light)
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
    LifeAgentApp(initialThemeMode = ThemeMode.Dark)
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
    )
}
