package ru.andriyshkoy.lifeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ru.andriyshkoy.lifeagent.ui.screens.AddScreen
import ru.andriyshkoy.lifeagent.ui.screens.CatalogListScreen
import ru.andriyshkoy.lifeagent.ui.screens.CatalogsScreen
import ru.andriyshkoy.lifeagent.ui.screens.FoodCaptureScreen
import ru.andriyshkoy.lifeagent.ui.screens.HealthConnectScreen
import ru.andriyshkoy.lifeagent.ui.screens.MedicationCaptureScreen
import ru.andriyshkoy.lifeagent.ui.screens.NoteCaptureScreen
import ru.andriyshkoy.lifeagent.ui.screens.SettingsScreen
import ru.andriyshkoy.lifeagent.ui.screens.SyncSetupScreen
import ru.andriyshkoy.lifeagent.ui.screens.WellbeingCaptureScreen
import ru.andriyshkoy.lifeagent.ui.theme.LifeAgentTheme
import ru.andriyshkoy.lifeagent.ui.theme.ThemeMode
import ru.andriyshkoy.lifeagent.ui.theme.resolveDarkTheme

private data class DestinationVisual(
    val destination: TopLevelDestination,
    val icon: ImageVector,
)

private val destinationVisuals = listOf(
    DestinationVisual(TopLevelDestination.Add, Icons.Rounded.AddCircle),
    DestinationVisual(TopLevelDestination.Catalogs, Icons.Rounded.Tune),
    DestinationVisual(TopLevelDestination.Settings, Icons.Rounded.Settings),
)

@Composable
fun LifeAgentApp(
    initialRoute: DemoRoute = DemoRoute.Add,
    initialThemeMode: ThemeMode = ThemeMode.System,
    forceExpanded: Boolean? = null,
) {
    var routeName by rememberSaveable { mutableStateOf(initialRoute.name) }
    var themeModeName by rememberSaveable { mutableStateOf(initialThemeMode.name) }

    val route = DemoRoute.valueOf(routeName)
    val themeMode = ThemeMode.valueOf(themeModeName)
    val darkTheme = resolveDarkTheme(themeMode)

    LifeAgentTheme(darkTheme = darkTheme) {
        LifeAgentAppContent(
            route = route,
            themeMode = themeMode,
            onThemeModeChange = { themeModeName = it.name },
            onNavigate = { routeName = it.name },
            forceExpanded = forceExpanded,
        )
    }
}

@Composable
private fun LifeAgentAppContent(
    route: DemoRoute,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (DemoRoute) -> Unit,
    forceExpanded: Boolean?,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedMessage) {
        val message = savedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Отменить",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        savedMessage = null
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            snackbarHostState.showSnackbar(
                message = "Последнее действие отменено",
                duration = SnackbarDuration.Short,
            )
        }
    }

    val onBack = {
        onNavigate(
            when (route) {
                DemoRoute.CaptureFood,
                DemoRoute.CaptureWellbeing,
                DemoRoute.CaptureMedication,
                DemoRoute.CaptureNote,
                -> DemoRoute.Add

                DemoRoute.CatalogFood,
                DemoRoute.CatalogWellbeing,
                DemoRoute.CatalogMedication,
                -> DemoRoute.Catalogs

                DemoRoute.SyncSetup,
                DemoRoute.HealthConnect,
                -> DemoRoute.Settings

                else -> DemoRoute.Add
            },
        )
    }

    if (route.topLevelDestination() == null) {
        BackHandler(onBack = onBack)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = forceExpanded ?: (maxWidth >= 720.dp)
        val currentTopLevel = route.topLevelDestination()

        if (expanded && currentTopLevel != null) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(
                    selected = currentTopLevel,
                    onSelect = { onNavigate(it.asRoute()) },
                )
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.weight(1f),
                ) { padding ->
                    AppScreen(
                        route = route,
                        expanded = true,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onNavigate = onNavigate,
                        onBack = onBack,
                        onSaved = { label ->
                            onNavigate(DemoRoute.Add)
                            savedMessage = "$label сохранено на устройстве"
                        },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (currentTopLevel != null) {
                        AppNavigationBar(
                            selected = currentTopLevel,
                            onSelect = { onNavigate(it.asRoute()) },
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                AppScreen(
                    route = route,
                    expanded = expanded,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onNavigate = onNavigate,
                    onBack = onBack,
                    onSaved = { label ->
                        onNavigate(DemoRoute.Add)
                        savedMessage = "$label сохранено на устройстве"
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun AppScreen(
    route: DemoRoute,
    expanded: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (DemoRoute) -> Unit,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        DemoRoute.Add -> AddScreen(
            expanded = expanded,
            onNavigate = onNavigate,
            modifier = modifier,
        )

        DemoRoute.Catalogs -> CatalogsScreen(
            onNavigate = onNavigate,
            modifier = modifier,
        )

        DemoRoute.Settings -> SettingsScreen(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onNavigate = onNavigate,
            modifier = modifier,
        )

        DemoRoute.CaptureFood -> FoodCaptureScreen(onBack, { onSaved("Питание") }, modifier)
        DemoRoute.CaptureWellbeing -> WellbeingCaptureScreen(
            onBack,
            { onSaved("Самочувствие") },
            modifier,
        )

        DemoRoute.CaptureMedication -> MedicationCaptureScreen(
            onBack,
            { onSaved("Приём") },
            modifier,
        )

        DemoRoute.CaptureNote -> NoteCaptureScreen(onBack, { onSaved("Заметка") }, modifier)
        DemoRoute.CatalogFood -> CatalogListScreen(CatalogKind.Food, onBack, modifier)
        DemoRoute.CatalogWellbeing -> CatalogListScreen(
            CatalogKind.Wellbeing,
            onBack,
            modifier,
        )

        DemoRoute.CatalogMedication -> CatalogListScreen(
            CatalogKind.Medication,
            onBack,
            modifier,
        )

        DemoRoute.SyncSetup -> SyncSetupScreen(onBack, modifier)
        DemoRoute.HealthConnect -> HealthConnectScreen(onBack, modifier)
    }
}

@Composable
private fun AppNavigationBar(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        destinationVisuals.forEach { item ->
            NavigationBarItem(
                selected = item.destination == selected,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.destination.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Icon(
                imageVector = Icons.Rounded.AddCircle,
                contentDescription = "Life Agent",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        },
    ) {
        destinationVisuals.forEach { item ->
            NavigationRailItem(
                selected = item.destination == selected,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.destination.label) },
            )
        }
    }
}

private fun TopLevelDestination.asRoute(): DemoRoute = when (this) {
    TopLevelDestination.Add -> DemoRoute.Add
    TopLevelDestination.Catalogs -> DemoRoute.Catalogs
    TopLevelDestination.Settings -> DemoRoute.Settings
}
