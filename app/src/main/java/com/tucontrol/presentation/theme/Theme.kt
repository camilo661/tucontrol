package com.tucontrol.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

private val TuControlColors = ColorScheme(
    primary = Palette.Periwinkle,
    primaryDim = Palette.PeriwinkleDim,
    primaryContainer = Palette.PeriwinkleDeep,
    onPrimary = Palette.OnPeriwinkle,
    onPrimaryContainer = Palette.OnPeriwinkleDeep,
    secondary = Palette.Teal,
    secondaryDim = Palette.TealDim,
    secondaryContainer = Palette.TealDeep,
    onSecondary = Palette.OnTeal,
    onSecondaryContainer = Palette.OnTealDeep,
    tertiary = Palette.Coral,
    tertiaryDim = Palette.CoralDim,
    tertiaryContainer = Palette.CoralDeep,
    onTertiary = Palette.OnCoral,
    onTertiaryContainer = Palette.OnCoralDeep,
    surfaceContainerLow = Palette.SurfaceLow,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    onSurface = Palette.OnSurface,
    onSurfaceVariant = Palette.OnSurfaceVariant,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineVariant,
    error = Palette.Error,
    errorDim = Palette.ErrorDim,
    errorContainer = Palette.ErrorDeep,
    onError = Palette.OnError,
    onErrorContainer = Palette.OnErrorDeep,
)

@Composable
fun TuControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TuControlColors, content = content)
}
