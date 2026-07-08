package com.docscanner.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Default (Blue) ──

val Blue50 = Color(0xFFE3F2FD)
val Blue100 = Color(0xFFBBDEFB)
val Blue200 = Color(0xFF90CAF9)
val Blue800 = Color(0xFF1565C0)
val Blue900 = Color(0xFF0D47A1)

val Teal200 = Color(0xFF80CBC4)
val Teal400 = Color(0xFF26A69A)
val Teal800 = Color(0xFF00695C)

val DefaultLight = lightColorScheme(
    primary = Blue800,
    secondary = Teal400,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    onError = Color.White,
)

val DefaultDark = darkColorScheme(
    primary = Blue200,
    secondary = Teal200,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    error = Color(0xFFCF6679),
    onPrimary = Color(0xFF003258),
    onSecondary = Color(0xFF003730),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    onError = Color.Black,
)

// ── Ocean (Teal / Cyan) ──

val Cyan800 = Color(0xFF00838F)
val Cyan200 = Color(0xFF80DEEA)
val Teal700 = Color(0xFF00796B)

val OceanLight = lightColorScheme(
    primary = Teal800,
    secondary = Cyan800,
    background = Color(0xFFF5FAF9),
    surface = Color.White,
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A1C1B),
    onSurface = Color(0xFF1A1C1B),
    onError = Color.White,
)

val OceanDark = darkColorScheme(
    primary = Teal200,
    secondary = Cyan200,
    background = Color(0xFF111413),
    surface = Color(0xFF1D201F),
    error = Color(0xFFCF6679),
    onPrimary = Color(0xFF003730),
    onSecondary = Color(0xFF003B3F),
    onBackground = Color(0xFFDFE4E2),
    onSurface = Color(0xFFDFE4E2),
    onError = Color.Black,
)

// ── Forest (Green) ──

val Green800 = Color(0xFF2E7D32)
val Green200 = Color(0xFFA5D6A7)
val LightGreen800 = Color(0xFF558B2F)
val LightGreen200 = Color(0xFFC5E1A5)

val ForestLight = lightColorScheme(
    primary = Green800,
    secondary = LightGreen800,
    background = Color(0xFFF6FAF2),
    surface = Color.White,
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1B1E1A),
    onSurface = Color(0xFF1B1E1A),
    onError = Color.White,
)

val ForestDark = darkColorScheme(
    primary = Green200,
    secondary = LightGreen200,
    background = Color(0xFF111311),
    surface = Color(0xFF1D1F1C),
    error = Color(0xFFCF6679),
    onPrimary = Color(0xFF003A00),
    onSecondary = Color(0xFF1D3B00),
    onBackground = Color(0xFFE0E3DF),
    onSurface = Color(0xFFE0E3DF),
    onError = Color.Black,
)

// ── Royal (Cerulean Blue) ──

val Blue600 = Color(0xFF1E88E5)
val Blue300 = Color(0xFF64B5F6)
val Amber500 = Color(0xFFFFB300)
val Amber200 = Color(0xFFFFE082)

val RoyalLight = lightColorScheme(
    primary = Blue600,
    secondary = Amber500,
    tertiary = Color(0xFF00ACC1),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    error = Color(0xFFD32F2F),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    onError = Color.White,
)

// ── Tag Colors ──

val TagColors = listOf(
    Color(0xFFE53935), // Red
    Color(0xFFFB8C00), // Orange
    Color(0xFFFDD835), // Yellow
    Color(0xFF43A047), // Green
    Color(0xFF00ACC1), // Cyan
    Color(0xFF1E88E5), // Blue
    Color(0xFF5E35B1), // Purple
    Color(0xFFD81B60), // Pink
)

val RoyalDark = darkColorScheme(
    primary = Blue300,
    secondary = Amber200,
    tertiary = Color(0xFF80DEEA),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    error = Color(0xFFCF6679),
    onPrimary = Color(0xFF003258),
    onSecondary = Color.Black,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    onError = Color.Black,
)
