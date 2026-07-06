package com.docscanner.ui.theme

enum class DarkMode { SYSTEM, LIGHT, DARK }
enum class Palette { ROYAL, DEFAULT, OCEAN, FOREST }

data class ThemeConfig(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val palette: Palette = Palette.DEFAULT,
)
