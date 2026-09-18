package com.baziche.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val DarkColorScheme = darkColorScheme(
    primary = BazicheEmeraldBright,
    onPrimary = BazicheBgRoot,
    primaryContainer = BazicheEmeraldDark,
    onPrimaryContainer = BazicheLime,
    secondary = BazicheLime,
    onSecondary = BazicheBgRoot,
    secondaryContainer = BazicheBgCardElevated,
    onSecondaryContainer = BazicheTextPrimary,
    tertiary = BazicheCyan,
    onTertiary = BazicheBgRoot,
    background = BazicheBgRoot,
    onBackground = BazicheTextPrimary,
    surface = BazicheBgSurface,
    onSurface = BazicheTextPrimary,
    surfaceVariant = BazicheBgCard,
    onSurfaceVariant = BazicheTextSecondary,
    outline = BazicheBorder,
    outlineVariant = BazicheBorderLight,
    error = BazicheRed,
    onError = BazicheTextPrimary,
    errorContainer = BazicheErrorContainer,
    onErrorContainer = BazicheOnErrorContainer,
)

@Composable
fun BazicheTheme(
    forceRtl: Boolean = true,
    content: @Composable () -> Unit,
) {
    val layoutDir = if (forceRtl) LayoutDirection.Rtl else LocalLayoutDirection.current

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = BazicheTypography,
            content = content,
        )
    }
}
