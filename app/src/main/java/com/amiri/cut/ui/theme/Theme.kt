package com.amiri.cut.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Restrained palette: near-black surfaces, one quiet user-selectable accent. */
object Amiri {
    val Bg = Color(0xFF0A0A0B)
    val Surface = Color(0xFF111113)
    val SurfaceHigh = Color(0xFF18181B)
    val Line = Color(0x1FFFFFFF)
    val TextPrimary = Color(0xFFEDEDEF)
    val TextSecondary = Color(0xFF8D8D95)
    val TextTertiary = Color(0xFF5C5C63)
    val Danger = Color(0xFFE5737A)

    // Timeline clip tints — deliberately desaturated.
    val ClipVideo = Color(0xFF26313D)
    val ClipOverlay = Color(0xFF332B3D)
    val ClipText = Color(0xFF3B3427)
    val ClipAudio = Color(0xFF213530)
}

val LocalAccent = staticCompositionLocalOf { Color(0xFF9FC3FF) }

private val AmiriTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.4.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable
fun AmiriTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0B0B0C),
        secondary = accent,
        background = Amiri.Bg,
        onBackground = Amiri.TextPrimary,
        surface = Amiri.Surface,
        onSurface = Amiri.TextPrimary,
        surfaceVariant = Amiri.SurfaceHigh,
        onSurfaceVariant = Amiri.TextSecondary,
        surfaceContainer = Amiri.Surface,
        surfaceContainerHigh = Amiri.SurfaceHigh,
        surfaceContainerHighest = Color(0xFF1E1E22),
        outline = Amiri.Line,
        outlineVariant = Amiri.Line,
        error = Amiri.Danger,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = AmiriTypography, content = content)
    }
}
