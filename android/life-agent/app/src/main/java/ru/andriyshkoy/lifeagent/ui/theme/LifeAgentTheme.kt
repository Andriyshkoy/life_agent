package ru.andriyshkoy.lifeagent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Emerald700 = Color(0xFF216A55)
private val Emerald800 = Color(0xFF16503F)
private val Mint100 = Color(0xFFD4F0E4)
private val Mint200 = Color(0xFFB8E2D1)
private val Ink900 = Color(0xFF13211C)
private val Sage700 = Color(0xFF4A685D)
private val Sage300 = Color(0xFFAEC2B9)
private val CanvasLight = Color(0xFFF5F8F6)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceMutedLight = Color(0xFFE8EFEB)
private val OutlineLight = Color(0xFF708079)
private val BrickLight = Color(0xFF9A403A)
private val BrickContainerLight = Color(0xFFFFDAD6)

private val MintDark = Color(0xFF8FD6BA)
private val MintSoftDark = Color(0xFFBFE9D8)
private val EmeraldContainerDark = Color(0xFF174E3E)
private val CanvasDark = Color(0xFF0C1511)
private val SurfaceDark = Color(0xFF121D18)
private val SurfaceMutedDark = Color(0xFF1D2A24)
private val OutlineDark = Color(0xFF899A92)
private val InkLight = Color(0xFFE7F0EB)
private val BrickDark = Color(0xFFFFB4AB)
private val BrickContainerDark = Color(0xFF70322E)

private val LightColors = lightColorScheme(
    primary = Emerald700,
    onPrimary = Color.White,
    primaryContainer = Mint100,
    onPrimaryContainer = Color(0xFF0A382C),
    secondary = Sage700,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEBE4),
    onSecondaryContainer = Color(0xFF20372E),
    tertiary = Color(0xFF596544),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0EBC8),
    onTertiaryContainer = Color(0xFF293119),
    background = CanvasLight,
    onBackground = Ink900,
    surface = SurfaceLight,
    onSurface = Ink900,
    surfaceVariant = SurfaceMutedLight,
    onSurfaceVariant = Color(0xFF44534C),
    outline = OutlineLight,
    outlineVariant = Color(0xFFC5D0CA),
    error = BrickLight,
    onError = Color.White,
    errorContainer = BrickContainerLight,
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = MintDark,
    onPrimary = Color(0xFF07382B),
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = MintSoftDark,
    secondary = Sage300,
    onSecondary = Color(0xFF1D352C),
    secondaryContainer = Color(0xFF30483E),
    onSecondaryContainer = Color(0xFFD4E8DE),
    tertiary = Color(0xFFC5D3A8),
    onTertiary = Color(0xFF303A1D),
    tertiaryContainer = Color(0xFF46512E),
    onTertiaryContainer = Color(0xFFE1EDC3),
    background = CanvasDark,
    onBackground = InkLight,
    surface = SurfaceDark,
    onSurface = InkLight,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = Color(0xFFBECAC4),
    outline = OutlineDark,
    outlineVariant = Color(0xFF394740),
    error = BrickDark,
    onError = Color(0xFF690005),
    errorContainer = BrickContainerDark,
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LifeAgentTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val LifeAgentShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Immutable
data class LifeAgentSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val huge: Dp = 40.dp,
)

val LocalLifeAgentSpacing = staticCompositionLocalOf { LifeAgentSpacing() }

object LifeAgentThemeValues {
    val spacing: LifeAgentSpacing
        @Composable
        get() = LocalLifeAgentSpacing.current
}

enum class ThemeMode(val label: String) {
    System("Системная"),
    Light("Светлая"),
    Dark("Тёмная"),
}

@Composable
fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun LifeAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLifeAgentSpacing provides LifeAgentSpacing(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = LifeAgentTypography,
            shapes = LifeAgentShapes,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
