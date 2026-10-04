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
import androidx.compose.runtime.setValue
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

/** Plays the soft purr while this screen is visible (stops when the app goes to the background). */
@Composable
fun PurrWhileVisible() {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> com.amiri.cut.ui.theme.CatSounds.startPurr()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> com.amiri.cut.ui.theme.CatSounds.stopPurr()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        com.amiri.cut.ui.theme.CatSounds.startPurr()
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

/**
 * Every minute a cat strolls slowly along the bottom of the editor, swaying its tail.
 * If the screen is touched while it walks, it dashes away; a minute later it comes back.
 * It never takes touches (drawn only).
 */
@Composable
fun WalkingCat(modifier: Modifier, body: Color, eye: Color, intervalMs: Long = 60_000L) {
    val state = androidx.compose.runtime.remember { floatArrayOf(-1f, 1f, 0f, 0f) } // x (0..1, -1 = hidden), dir, phase, fleeing
    var frame by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            val dir = if (kotlin.random.Random.nextBoolean()) 1f else -1f
            state[0] = if (dir > 0) -0.15f else 1.15f; state[1] = dir; state[2] = 0f; state[3] = 0f
            val start = android.os.SystemClock.uptimeMillis()
            var last = start
            while (state[0] > -0.2f && state[0] < 1.2f) {
                androidx.compose.runtime.withFrameMillis { _ -> }
                val now = android.os.SystemClock.uptimeMillis()
                val dt = (now - last).coerceAtMost(64) / 1000f
                last = now
                if (state[3] == 0f && com.amiri.cut.ui.theme.CatSounds.lastTouchMs > start) state[3] = 1f
                val speed = if (state[3] > 0f) 0.9f else 1f / 18f // fraction of the width per second
                state[0] += state[1] * speed * dt
                state[2] += dt * (if (state[3] > 0f) 9f else 1.6f)
                frame = now
            }
            state[0] = -1f
            frame = android.os.SystemClock.uptimeMillis()
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        val x = state[0]
        if (x < -0.5f) return@Canvas
        val u = size.height * 0.95f
        val flee = state[3] > 0f
        val ph = state[2] * 2f * Math.PI.toFloat()
        val cx = x * size.width
        val bob = kotlin.math.abs(sin(ph)) * u * (if (flee) 0.08f else 0.03f)
        val cy = size.height - u * 0.42f - bob
        val dir = state[1]
        fun px(dx: Float) = cx + dir * dx * u
        // Legs (opposite pairs swing together).
        val legTop = cy + u * 0.12f
        val swing = if (flee) 38f else 24f
        listOf(-0.3f to 0f, -0.16f to Math.PI.toFloat(), 0.18f to Math.PI.toFloat(), 0.32f to 0f).forEach { (lx, off) ->
            val a = Math.toRadians((sin(ph + off) * swing).toDouble())
            val len = u * 0.3f
            drawLine(body, Offset(px(lx), legTop), Offset(px(lx) + dir * (sin(a) * len).toFloat(), legTop + (cos(a) * len).toFloat()), strokeWidth = u * 0.075f, cap = StrokeCap.Round)
        }
        // Tail: slow, flirty S-curve sway (straight up when running).
        val sway = sin(state[2] * 2.2f) * u * 0.18f
        val tail = Path().apply {
            moveTo(px(-0.46f), cy - u * 0.04f)
            if (flee) cubicTo(px(-0.66f), cy - u * 0.2f, px(-0.7f), cy - u * 0.5f, px(-0.62f), cy - u * 0.62f)
            else cubicTo(px(-0.7f), cy - u * 0.05f + sway * 0.3f, px(-0.58f) + sway * dir, cy - u * 0.5f, px(-0.74f) + sway * 1.2f * dir, cy - u * 0.62f)
        }
        drawPath(tail, body, style = Stroke(u * 0.07f, cap = StrokeCap.Round))
        // Body
        drawOval(body, Offset(cx - u * 0.5f, cy - u * 0.2f), Size(u, u * 0.4f))
        // Head + ears
        val hx = px(0.52f); val hy = cy - u * 0.24f; val hr = u * 0.2f
        val ears = Path().apply {
            moveTo(hx - hr * 0.8f, hy - hr * 0.35f); lineTo(hx - hr * 0.55f, hy - hr * 1.25f); lineTo(hx - hr * 0.05f, hy - hr * 0.75f); close()
            moveTo(hx + hr * 0.8f, hy - hr * 0.35f); lineTo(hx + hr * 0.6f, hy - hr * 1.25f); lineTo(hx + hr * 0.1f, hy - hr * 0.75f); close()
        }
        drawPath(ears, body)
        drawCircle(body, hr, Offset(hx, hy))
        val blink = (state[2] % 4f) > 3.85f
        val ex = hx + dir * hr * 0.45f; val ey = hy - hr * 0.1f
        if (blink && !flee) drawLine(eye, Offset(ex - hr * 0.12f, ey), Offset(ex + hr * 0.12f, ey), strokeWidth = hr * 0.08f)
        else drawOval(eye, Offset(ex - hr * 0.12f, ey - hr * (if (flee) 0.2f else 0.15f)), Size(hr * 0.24f, hr * (if (flee) 0.4f else 0.3f)))
        drawCircle(Color(0xFFF2A0B4), hr * 0.08f, Offset(hx + dir * hr * 0.92f, hy + hr * 0.15f))
    }
}
