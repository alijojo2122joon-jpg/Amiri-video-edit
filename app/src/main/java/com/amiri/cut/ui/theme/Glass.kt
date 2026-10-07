package com.amiri.cut.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Liquid glass" surface: translucent layered fill, a specular top edge and a
 * soft rim. Readability first — the fill is dense enough that text on it always
 * has contrast, regardless of what sits behind.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(20.dp),
    strength: Float = 1f,
    rim: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .background(Color(0xF2161618), shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.055f * strength),
                Color.White.copy(alpha = 0.012f * strength),
            )
        ),
        shape,
    )
    .border(
        rim,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.13f * strength),
                Color.White.copy(alpha = 0.04f * strength),
            )
        ),
        shape,
    )

/** Accent-tinted surface for selected / primary-but-quiet elements. */
fun Modifier.glassAccent(accent: Color, shape: Shape = RoundedCornerShape(20.dp)): Modifier = this
    .clip(shape)
    .background(accent.copy(alpha = 0.17f), shape)
    .border(1.dp, accent.copy(alpha = 0.55f), shape)

/** Solid card surface (no translucency) used by lists and sheets. */
fun Modifier.card(shape: Shape = RoundedCornerShape(18.dp), color: Color = Amiri.SurfaceHigh): Modifier = this
    .clip(shape)
    .background(color, shape)

/** Micro-interaction: gentle spring scale while pressed. */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Subtle haptics for editing events (snap, split, select, keyframe). */
object Haptics {
    var enabled: Boolean = true

    fun tick(v: View) { if (enabled) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun select(v: View) { if (enabled) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun confirm(v: View) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 30) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun reject(v: View) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 30) v.performHapticFeedback(HapticFeedbackConstants.REJECT)
        else v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    fun heavy(v: View) { if (enabled) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}
