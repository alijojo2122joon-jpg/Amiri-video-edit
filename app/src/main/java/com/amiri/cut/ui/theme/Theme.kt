package com.amiri.cut.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.amiri.cut.R

/**
 * Amiri Cut 1.0 design tokens.
 *
 * Deep neutral surfaces (true-black canvas, layered greys for chrome), crisp white text,
 * one user-selectable accent for primary actions, and vivid lane colours on the timeline
 * so every kind of layer is recognisable at a glance.
 */
object Amiri {
    val Bg = Color(0xFF060607)
    val Surface = Color(0xFF111113)
    val SurfaceHigh = Color(0xFF1B1B1E)
    val SurfaceHighest = Color(0xFF26262A)
    val Line = Color(0x17FFFFFF)
    val LineStrong = Color(0x2BFFFFFF)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFA4A4AC)
    val TextTertiary = Color(0xFF68686F)
    val Danger = Color(0xFFFF5D6C)
    val Success = Color(0xFF3DDC97)

    // Timeline lane colours.
    val ClipVideo = Color(0xFF1C2027)
    val ClipOverlay = Color(0xFF6F7DFF)
    val ClipText = Color(0xFFF2A93B)
    val ClipAudio = Color(0xFF17B38A)
    val ClipShape = Color(0xFFE85D9E)
    val ClipAdjust = Color(0xFF8E6CFF)
    val ClipSound = Color(0xFF2EC4B6)
}

val LocalAccent = staticCompositionLocalOf { Color(0xFFF4A261) }

/** Readable content colour on top of an accent fill. */
fun onAccent(accent: Color): Color = if (accent.luminance() > 0.45f) Color(0xFF16100B) else Color.White

/** Inter (bundled, Latin subset) so the app looks the same whatever system font the phone uses. */
val AmiriFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

private val AmiriTypography = Typography(
    displaySmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.6).sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.4).sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.2).sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.1).sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.2.sp),
)

/** Time readouts: tabular figures so digits don't jitter while playing. */
val MonoStyle = TextStyle(fontFamily = AmiriFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
fun AmiriTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent(accent),
        secondary = accent,
        onSecondary = onAccent(accent),
        background = Amiri.Bg,
        onBackground = Amiri.TextPrimary,
        surface = Amiri.Surface,
        onSurface = Amiri.TextPrimary,
        surfaceVariant = Amiri.SurfaceHigh,
        onSurfaceVariant = Amiri.TextSecondary,
        surfaceContainerLowest = Amiri.Bg,
        surfaceContainerLow = Amiri.Surface,
        surfaceContainer = Amiri.Surface,
        surfaceContainerHigh = Amiri.SurfaceHigh,
        surfaceContainerHighest = Amiri.SurfaceHighest,
        outline = Amiri.LineStrong,
        outlineVariant = Amiri.Line,
        error = Amiri.Danger,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = AmiriTypography, content = content)
    }
}
