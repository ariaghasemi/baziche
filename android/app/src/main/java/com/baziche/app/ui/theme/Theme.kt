package com.baziche.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = BazicheGreen,
    onPrimary = BazichePaper,
    primaryContainer = BazicheLime,
    onPrimaryContainer = BazicheInk,
    secondary = BazicheGreenDark,
    tertiary = BazicheAmber,
    background = BazichePaper,
    surface = BazichePaper,
    error = BazicheRed,
)

private val DarkScheme = darkColorScheme(
    primary = BazicheLime,
    onPrimary = BazicheInk,
    primaryContainer = BazicheGreenDark,
    onPrimaryContainer = BazichePaper,
    secondary = BazicheLime,
    tertiary = BazicheAmber,
    background = BazicheInk,
    surface = BazicheInk,
    error = BazicheRed,
)

@Composable
fun BazicheTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
