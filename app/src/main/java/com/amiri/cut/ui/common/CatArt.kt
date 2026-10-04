package com.amiri.cut.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/** Small original cat drawings used across the app (Amiri Cut's cat theme). */

/** A paw print centred at [c] with overall size [s], rotated by [deg]. */
fun DrawScope.paw(c: Offset, s: Float, color: Color, deg: Float = 0f) {
    rotate(deg, c) {
        drawOval(color, Offset(c.x - s * 0.32f, c.y - s * 0.05f), Size(s * 0.64f, s * 0.5f))
        val toes = listOf(-0.36f to -0.18f, -0.13f to -0.38f, 0.13f to -0.38f, 0.36f to -0.18f)
        for ((tx, ty) in toes) drawOval(color, Offset(c.x + tx * s - s * 0.12f, c.y + ty * s - s * 0.14f), Size(s * 0.24f, s * 0.28f))
    }
}

/** A single paw print. */
@Composable
fun PawPrint(modifier: Modifier, color: Color, rotation: Float = 0f) {
    Canvas(modifier) { paw(center, size.minDimension * 0.9f, color, rotation) }
}

/** Faint paw prints scattered like a cat walked across the screen. */
fun DrawScope.pawTrailBackground(color: Color) {
    val w = size.width; val h = size.height
    val s = w * 0.055f
    var i = 0
    var y = h * 0.92f
    while (y > -s) {
        val x = w * (0.72f + 0.18f * sin(i * 0.55f)) + (if (i % 2 == 0) -s * 0.6f else s * 0.6f)
        paw(Offset(x, y), s, color.copy(alpha = color.alpha * (0.4f + 0.6f * (i % 3) / 2f)), -20f + 12f * sin(i * 0.9f))
        y -= s * 1.7f
        i++
    }
}

/** A curled-up sleeping cat (for empty states). */
@Composable
fun SleepingCat(modifier: Modifier, body: Color, accent: Color) {
    val t = rememberInfiniteTransition(label = "sleep")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w * 0.5f; val cy = h * 0.62f
        val r = w * 0.3f * (1f + breathe * 0.02f)
        // Body (a soft oval) and curled tail.
        drawOval(body, Offset(cx - r * 1.25f, cy - r * 0.75f), Size(r * 2.5f, r * 1.45f))
        val tail = Path().apply {
            moveTo(cx + r * 1.1f, cy + r * 0.2f)
            cubicTo(cx + r * 1.5f, cy + r * 0.9f, cx - r * 0.2f, cy + r * 1.05f, cx - r * 0.75f, cy + r * 0.55f)
        }
        drawPath(tail, body, style = Stroke(r * 0.32f, cap = StrokeCap.Round))
        // Head with ears, resting on the left.
        val hx = cx - r * 0.85f; val hy = cy - r * 0.35f; val hr = r * 0.55f
        val ears = Path().apply {
            moveTo(hx - hr * 0.85f, hy - hr * 0.3f); lineTo(hx - hr * 0.7f, hy - hr * 1.25f); lineTo(hx - hr * 0.15f, hy - hr * 0.75f); close()
            moveTo(hx + hr * 0.85f, hy - hr * 0.3f); lineTo(hx + hr * 0.6f, hy - hr * 1.25f); lineTo(hx + hr * 0.1f, hy - hr * 0.75f); close()
        }
        drawPath(ears, body)
        drawCircle(body, hr, Offset(hx, hy))
        // Closed eyes and a pink nose.
        for (sx in listOf(-1f, 1f)) {
            val e = Path().apply {
                moveTo(hx + sx * hr * 0.55f - hr * 0.2f, hy - hr * 0.05f)
                quadraticBezierTo(hx + sx * hr * 0.55f, hy + hr * 0.12f, hx + sx * hr * 0.55f + hr * 0.2f, hy - hr * 0.05f)
            }
            drawPath(e, Color.Black.copy(alpha = 0.55f), style = Stroke(hr * 0.07f, cap = StrokeCap.Round))
        }
        drawCircle(Color(0xFFF2A0B4), hr * 0.08f, Offset(hx, hy + hr * 0.22f))
        // Z z z
        val zc = accent.copy(alpha = 0.4f + 0.5f * breathe)
        for (k in 0..2) {
            val zs = hr * (0.28f + k * 0.08f)
            val zx = hx + hr * (1.0f + k * 0.45f); val zy = hy - hr * (1.1f + k * 0.55f) - breathe * hr * 0.15f
            val z = Path().apply { moveTo(zx, zy); lineTo(zx + zs, zy); lineTo(zx, zy + zs); lineTo(zx + zs, zy + zs) }
            drawPath(z, zc, style = Stroke(hr * 0.06f, cap = StrokeCap.Round))
        }
    }
}

/** A cat head peeking over an edge (ears, eyes that blink). */
@Composable
fun PeekingCat(modifier: Modifier, body: Color, eye: Color) {
    val t = rememberInfiniteTransition(label = "peek")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "blink")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val hr = w * 0.42f
        val cx = w / 2f; val cy = h + hr * 0.25f
        val ears = Path().apply {
            moveTo(cx - hr * 0.9f, cy - hr * 0.45f); lineTo(cx - hr * 0.72f, cy - hr * 1.3f); lineTo(cx - hr * 0.2f, cy - hr * 0.85f); close()
            moveTo(cx + hr * 0.9f, cy - hr * 0.45f); lineTo(cx + hr * 0.72f, cy - hr * 1.3f); lineTo(cx + hr * 0.2f, cy - hr * 0.85f); close()
        }
        drawPath(ears, body)
        drawCircle(body, hr, Offset(cx, cy))
        val open = if (blink > 0.94f) 0.15f else 1f
        for (sx in listOf(-1f, 1f)) {
            val ex = cx + sx * hr * 0.4f; val ey = cy - hr * 0.42f
            drawOval(eye, Offset(ex - hr * 0.16f, ey - hr * 0.2f * open), Size(hr * 0.32f, hr * 0.4f * open))
            if (open > 0.5f) drawOval(Color.Black, Offset(ex - hr * 0.05f, ey - hr * 0.17f), Size(hr * 0.1f, hr * 0.34f))
        }
        drawCircle(Color(0xFFF2A0B4), hr * 0.07f, Offset(cx, cy - hr * 0.18f))
        // Whiskers
        for (sx in listOf(-1f, 1f)) for (k in 0..1) {
            val a = (if (k == 0) 0.05f else -0.12f)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(cx + sx * hr * 0.3f, cy - hr * 0.12f),
                Offset(cx + sx * hr * (1.15f), cy - hr * 0.12f + a * hr), strokeWidth = hr * 0.02f)
        }
    }
}

/** Animated paw steps (a loading indicator). */
@Composable
fun PawSteps(modifier: Modifier, color: Color, steps: Int = 5) {
    val t = rememberInfiniteTransition(label = "steps")
    val p by t.animateFloat(0f, steps.toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    Canvas(modifier) {
        val s = size.height * 0.8f
        val gap = size.width / steps
        for (i in 0 until steps) {
            val age = (p - i).let { if (it < 0) it + steps else it }
            val a = (1f - age / steps).coerceIn(0f, 1f)
            val y = size.height / 2f + (if (i % 2 == 0) -s * 0.18f else s * 0.18f)
            paw(Offset(gap * (i + 0.5f), y), s, color.copy(alpha = 0.15f + 0.85f * a * a), 90f)
        }
    }
}

@Suppress("unused") private val keep = cos(0.0)
