package com.docscanner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = DefaultLight
private val DarkColorScheme = DefaultDark

private fun pickPalette(palette: Palette, isDark: Boolean) = when (palette) {
    Palette.DEFAULT -> if (isDark) DefaultDark else DefaultLight
    Palette.OCEAN   -> if (isDark) OceanDark else OceanLight
    Palette.FOREST  -> if (isDark) ForestDark else ForestLight
}

@Composable
fun DocScannerTheme(
    themeManager: ThemeManager,
    content: @Composable () -> Unit,
) {
    val config by themeManager.config.collectAsState()
    val isDark = when (config.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val colorScheme = pickPalette(config.palette, isDark)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
