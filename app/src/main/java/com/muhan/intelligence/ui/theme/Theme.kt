package com.muhan.intelligence.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.muhan.intelligence.domain.model.ThemeMode

// ---------------------------------------------------------------------------
// Brand palette. "慕" reads as a calm, deep violet-blue — trustworthy for an AI
// tool while staying distinct from the plain blue used by most chat apps.
// ---------------------------------------------------------------------------
private val BrandViolet = Color(0xFF6D5BF6)
private val BrandVioletDark = Color(0xFF4C3FD6)

private val LightColors = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E3FF),
    onPrimaryContainer = Color(0xFF1B1046),

    secondary = Color(0xFF5B5A72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E0F9),
    onSecondaryContainer = Color(0xFF181A2C),

    tertiary = Color(0xFF78536B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EE),
    onTertiaryContainer = Color(0xFF2C1226),

    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBFAFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3FA),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),

    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF21106F),
    primaryContainer = Color(0xFF3A2CB4),
    onPrimaryContainer = Color(0xFFE7E3FF),

    secondary = Color(0xFFC6C4DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE2E0F9),

    tertiary = Color(0xFFE8B9D3),
    onTertiary = Color(0xFF45263C),
    tertiaryContainer = Color(0xFF5E3C53),
    onTertiaryContainer = Color(0xFFFFD8EE),

    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),

    surfaceContainerLowest = Color(0xFF090C11),
    surfaceContainerLow = Color(0xFF161A20),
    surfaceContainer = Color(0xFF1A1E25),
    surfaceContainerHigh = Color(0xFF252932),
    surfaceContainerHighest = Color(0xFF30343E),

    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF47464F),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Reasoning block palette (used for the collapsible chain-of-thought panel).
 * Kept outside Material's scheme because it is intentionally a *quiet* surface.
 */
object MuHanColors {
    val reasoningLight = Color(0xFFF3F1FB)
    val reasoningDark = Color(0xFF1C1F27)
}

@Composable
fun MuHanTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MuHanTypography,
        shapes = MuHanShapes,
        content = content,
    )
}
