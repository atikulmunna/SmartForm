package com.app.smartform.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * SmartForm commits to a single dark, athletic "neon" identity — no light mode and
 * no wallpaper-driven dynamic color, so the brand looks the same on every device
 * and reads well over a live camera feed.
 */
private val SmartFormColors = darkColorScheme(
    primary = NeonLime,
    onPrimary = Charcoal900,
    primaryContainer = LimeContainer,
    onPrimaryContainer = OnLimeContainer,

    secondary = ElectricCyan,
    onSecondary = Charcoal900,
    secondaryContainer = TealContainer,
    onSecondaryContainer = OnTealContainer,

    tertiary = SoftViolet,
    onTertiary = Charcoal900,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = OnVioletContainer,

    background = Charcoal900,
    onBackground = InkHigh,

    surface = Charcoal800,
    onSurface = InkHigh,
    surfaceVariant = Charcoal700,
    onSurfaceVariant = InkMuted,
    surfaceContainer = Charcoal700,
    surfaceContainerHigh = Charcoal600,
    surfaceContainerHighest = Charcoal600,

    error = NeonRed,
    onError = Charcoal900,
    errorContainer = RedContainer,
    onErrorContainer = OnRedContainer,

    outline = OutlineDim,
    outlineVariant = OutlineFaint,
)

@Composable
fun SmartFormTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Charcoal900.toArgb()
            window.navigationBarColor = Charcoal900.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = SmartFormColors,
        typography = Typography,
        content = content
    )
}
