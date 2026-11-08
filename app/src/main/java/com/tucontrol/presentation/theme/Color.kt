package com.tucontrol.presentation.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** "Midnight Aurora": deep neutral surfaces, periwinkle primary, teal accent, coral for power. */
internal object Palette {
    val Periwinkle = Color(0xFFA8B9FF)
    val PeriwinkleDim = Color(0xFF8FA2EC)
    val PeriwinkleDeep = Color(0xFF2A3566)
    val OnPeriwinkle = Color(0xFF0E1638)
    val OnPeriwinkleDeep = Color(0xFFDCE2FF)

    val Teal = Color(0xFF5EEAD4)
    val TealDim = Color(0xFF3CC9B4)
    val TealDeep = Color(0xFF0F3D38)
    val OnTeal = Color(0xFF00201C)
    val OnTealDeep = Color(0xFFB4F5EA)

    val Coral = Color(0xFFFFB4A1)
    val CoralDim = Color(0xFFE99A86)
    val CoralDeep = Color(0xFF5A2A1E)
    val OnCoral = Color(0xFF3A0F05)
    val OnCoralDeep = Color(0xFFFFDAD1)

    val SurfaceLow = Color(0xFF111318)
    val Surface = Color(0xFF1A1D24)
    val SurfaceHigh = Color(0xFF252932)
    val OnSurface = Color(0xFFE8EAF2)
    val OnSurfaceVariant = Color(0xFFA9AEBD)
    val Outline = Color(0xFF6E7385)
    val OutlineVariant = Color(0xFF3A3F4C)

    val Error = Color(0xFFFF8A80)
    val ErrorDim = Color(0xFFE57368)
    val ErrorDeep = Color(0xFF5C1A15)
    val OnError = Color(0xFF410002)
    val OnErrorDeep = Color(0xFFFFDAD6)

    val Success = Color(0xFF7BE0A1)
}

/** Signature gradient used by the voice button and brand accents. */
object Gradients {
    val Aurora: Brush = Brush.linearGradient(listOf(Palette.Periwinkle, Palette.Teal))
    val AuroraDeep: Brush = Brush.linearGradient(listOf(Palette.PeriwinkleDeep, Palette.TealDeep))
}

object StatusColors {
    val Connected: Color = Palette.Success
    val Pending: Color = Palette.Periwinkle
    val Offline: Color = Palette.Outline
    val Error: Color = Palette.Error
}
