package com.amiri.cut.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.amiri.cut.R

/**
 * Amiri Cut 2.0 design tokens.
 *
 * A professional editing surface: true-black canvas so the picture is the brightest thing on
 * screen, three neutral elevation steps for chrome, crisp white type with two quieter greys,
 * and one accent (the Amiri violet by default) reserved for primary actions and selection.
 */
object Amiri {
    // Canvas & elevation
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF0F0F11)        // panels, sheets
    val SurfaceHigh = Color(0xFF19191C)    // cards, chips, wells
    val SurfaceHighest = Color(0xFF242428) // raised / pressed
    val SurfaceTop = Color(0xFF303035)     // handles, scrub tracks

    // Hairlines
    val Line = Color(0x14FFFFFF)
    val LineStrong = Color(0x24FFFFFF)

    // Type
    val TextPrimary = Color(0xFFF7F7F8)
    val TextSecondary = Color(0xFFA1A1AA)
    val TextTertiary = Color(0xFF71717A)

    // Semantic
    val Danger = Color(0xFFFF4D6A)
    val Success = Color(0xFF2DD4A0)
    val Warning = Color(0xFFFBBF24)

    // Timeline lanes (each kind of layer recognisable at a glance)
    val ClipVideo = Color(0xFF1C1C20)
    val ClipOverlay = Color(0xFF4F7BFF)
    val ClipText = Color(0xFFE8A930)
    val ClipAudio = Color(0xFF14B8A6)
    val ClipShape = Color(0xFFF05FA6)
    val ClipAdjust = Color(0xFF8B5CF6)
    val ClipSound = Color(0xFF38BDF8)
    val ClipSticker = Color(0xFFF59E0B)

    /** A lighter version of the accent for text and line icons on black. */
    fun accentInk(accent: Color): Color = if (accent.luminance() < 0.3f) lerp(accent, Color.White, 0.32f) else accent

    /** Second stop of the accent gradient: the accent turned ~32° warmer and a touch lighter. */
    fun accent2(accent: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
        hsv[0] = (hsv[0] + 32f) % 360f
        hsv[2] = (hsv[2] * 1.05f).coerceAtMost(1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Fill of primary actions (Export, New project, Add). */
    fun accentBrush(accent: Color): Brush = Brush.horizontalGradient(listOf(accent, accent2(accent)))
}

val LocalAccent = staticCompositionLocalOf { Color(0xFF7C5CFF) }

/** Readable content colour on top of an accent fill. */
fun onAccent(accent: Color): Color = if (accent.luminance() > 0.5f) Color(0xFF111015) else Color.White

/** Inter (bundled, Latin subset) so the app looks the same whatever system font the phone uses. */
val AmiriFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

private val AmiriTypography = Typography(
    displaySmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = (-0.7).sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = (-0.3).sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.15).sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = (-0.05).sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = AmiriFont, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.5.sp),
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
