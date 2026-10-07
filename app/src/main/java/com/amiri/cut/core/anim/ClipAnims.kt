package com.amiri.cut.core.anim

import com.amiri.cut.core.model.ClipAnim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** One clip-animation preset. [kind]: entrance/exit ("in" — exits mirror it) or "combo" (whole clip). */
data class ClipAnimSpec(val id: String, val label: String, val kind: String)

/** Extra transform of a clip at one moment: offsets in canvas fractions, scale, degrees, alpha. */
class ClipXf {
    var dx = 0f; var dy = 0f; var sx = 1f; var sy = 1f; var rot = 0f; var alpha = 1f
    fun reset(): ClipXf { dx = 0f; dy = 0f; sx = 1f; sy = 1f; rot = 0f; alpha = 1f; return this }
    val identity: Boolean get() = abs(dx) < 1e-5f && abs(dy) < 1e-5f && abs(sx - 1f) < 1e-5f && abs(sy - 1f) < 1e-5f && abs(rot) < 1e-4f && alpha > 0.9999f
}

/**
 * Clip animations (In / Out / Combo) for pictures, videos, stickers and text layers.
 * In and Out play over their own durations at the clip's edges; a Combo runs across the
 * whole clip. Everything is a pure function of time, so preview and export match exactly.
 */
object ClipAnims {
    const val NONE = "None"

    val IN: List<ClipAnimSpec> = listOf(
        ClipAnimSpec("fade", "Fade", "in"),
        ClipAnimSpec("zoomIn", "Zoom in", "in"),
        ClipAnimSpec("zoomOut", "Zoom out", "in"),
        ClipAnimSpec("slideLeft", "Slide left", "in"),
        ClipAnimSpec("slideRight", "Slide right", "in"),
        ClipAnimSpec("slideUp", "Slide up", "in"),
        ClipAnimSpec("slideDown", "Slide down", "in"),
        ClipAnimSpec("pop", "Pop", "in"),
        ClipAnimSpec("rotate", "Rotate", "in"),
        ClipAnimSpec("spin", "Spin", "in"),
        ClipAnimSpec("swing", "Swing", "in"),
        ClipAnimSpec("drop", "Drop", "in"),
        ClipAnimSpec("flip", "Flip", "in"),
        ClipAnimSpec("shrink", "Squeeze", "in"),
    )

    val COMBO: List<ClipAnimSpec> = listOf(
        ClipAnimSpec("kenBurnsIn", "Slow zoom in", "combo"),
        ClipAnimSpec("kenBurnsOut", "Slow zoom out", "combo"),
        ClipAnimSpec("panLeft", "Pan left", "combo"),
        ClipAnimSpec("panRight", "Pan right", "combo"),
        ClipAnimSpec("rock", "Rock", "combo"),
        ClipAnimSpec("pulse", "Pulse", "combo"),
        ClipAnimSpec("heartbeat", "Heartbeat", "combo"),
        ClipAnimSpec("float", "Float", "combo"),
        ClipAnimSpec("wobble", "Wobble", "combo"),
        ClipAnimSpec("shake", "Shake", "combo"),
        ClipAnimSpec("spinLoop", "Spin", "combo"),
    )

    fun label(id: String): String = (IN + COMBO).firstOrNull { it.id == id }?.label ?: NONE

    private fun outCubic(x: Float) = 1f - (1f - x).pow(3)
    private fun outBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }
    private fun outElastic(x: Float): Float = if (x <= 0f) 0f else if (x >= 1f) 1f else (2.0.pow(-10.0 * x) * sin((x * 10 - 0.75) * (2 * PI / 3)) + 1).toFloat()
    private fun outBounce(x0: Float): Float {
        var x = x0
        val n1 = 7.5625f; val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { x -= 1.5f / d1; n1 * x * x + 0.75f }
            x < 2.5f / d1 -> { x -= 2.25f / d1; n1 * x * x + 0.9375f }
            else -> { x -= 2.625f / d1; n1 * x * x + 0.984375f }
        }
    }

    /**
     * Applies an entrance preset at progress [p] (0 = start of the animation, 1 = at rest).
     * For exits the caller passes p = remaining fraction and [exit] = true, which mirrors
     * the motion so the clip keeps moving the same way as it leaves.
     */
    private fun edge(id: String, p0: Float, exit: Boolean, x: ClipXf) {
        val p = p0.coerceIn(0f, 1f)
        val e = outCubic(p)
        val m = 1f - e // how far from rest
        val s = if (exit) -1f else 1f // exits continue the motion
        when (id) {
            "fade" -> x.alpha *= p
            "zoomIn" -> { val k = if (exit) 1f + 0.45f * m else 0.55f + 0.45f * e; x.sx *= k; x.sy *= k; x.alpha *= (p * 2.5f).coerceAtMost(1f) }
            "zoomOut" -> { val k = if (exit) 1f - 0.45f * m else 1.45f - 0.45f * e; x.sx *= k; x.sy *= k; x.alpha *= (p * 2.5f).coerceAtMost(1f) }
            "slideLeft" -> x.dx += s * m * 1.05f
            "slideRight" -> x.dx -= s * m * 1.05f
            "slideUp" -> x.dy += s * m * 1.05f
            "slideDown" -> x.dy -= s * m * 1.05f
            "pop" -> { val k = outBack(p).coerceAtLeast(0f); x.sx *= k; x.sy *= k; x.alpha *= (p * 4f).coerceAtMost(1f) }
            "rotate" -> { x.rot += -s * 90f * m; val k = 0.7f + 0.3f * e; x.sx *= k; x.sy *= k; x.alpha *= (p * 2f).coerceAtMost(1f) }
            "spin" -> { x.rot += -s * 360f * m; val k = 0.25f + 0.75f * e; x.sx *= k; x.sy *= k; x.alpha *= (p * 3f).coerceAtMost(1f) }
            "swing" -> { x.rot += s * 28f * (1f - outElastic(p)); x.alpha *= (p * 3f).coerceAtMost(1f) }
            "drop" -> { x.dy -= s * (1f - outBounce(p)) * 0.9f; x.alpha *= (p * 4f).coerceAtMost(1f) }
            "flip" -> { x.sx *= e.coerceAtLeast(0.001f); x.alpha *= (p * 3f).coerceAtMost(1f) }
            "shrink" -> { x.sy *= e.coerceAtLeast(0.001f); x.sx *= 1f + 0.25f * m; x.alpha *= (p * 2f).coerceAtMost(1f) }
        }
    }

    private fun hash(i: Int, k: Int): Float {
        var v = i * 374761393 + k * 668265263
        v = (v xor (v ushr 13)) * 1274126177
        return ((v xor (v ushr 16)) and 0xFFFF) / 65535f
    }

    private fun combo(id: String, t: Float, u: Float, speed: Float, x: ClipXf) {
        val w = 2f * PI.toFloat() * speed
        when (id) {
            "kenBurnsIn" -> { val k = 1f + 0.14f * u; x.sx *= k; x.sy *= k }
            "kenBurnsOut" -> { val k = 1.14f - 0.14f * u; x.sx *= k; x.sy *= k }
            "panLeft" -> { x.sx *= 1.12f; x.sy *= 1.12f; x.dx += 0.05f * (1f - 2f * u) }
            "panRight" -> { x.sx *= 1.12f; x.sy *= 1.12f; x.dx -= 0.05f * (1f - 2f * u) }
            "rock" -> x.rot += 6f * sin(w * t * 0.7f)
            "pulse" -> { val k = 1f + 0.05f * sin(w * t); x.sx *= k; x.sy *= k }
            "heartbeat" -> {
                val ph = (t * speed) - floor(t * speed)
                val beat = (if (ph < 0.12f) sin(ph / 0.12f * PI.toFloat()) else 0f) + (if (ph in 0.2f..0.32f) 0.7f * sin((ph - 0.2f) / 0.12f * PI.toFloat()) else 0f)
                val k = 1f + 0.08f * beat; x.sx *= k; x.sy *= k
            }
            "float" -> x.dy += 0.02f * sin(w * t * 0.6f)
            "wobble" -> { val q = 0.05f * sin(w * t * 1.2f); x.sx *= 1f + q; x.sy *= 1f - q }
            "shake" -> {
                val f = (t * 14f * speed).toInt()
                val a = (t * 14f * speed) - f
                fun n(k: Int) = (hash(f, k) * (1f - a) + hash(f + 1, k) * a) * 2f - 1f
                x.dx += 0.012f * n(1); x.dy += 0.012f * n(2); x.rot += 1.2f * n(3)
            }
            "spinLoop" -> x.rot += (t * 90f * speed) % 360f
        }
    }

    /** The animation transform of a clip at [localUs] (time since the clip's start). */
    fun xf(a: ClipAnim?, localUs: Long, durUs: Long, out: ClipXf = ClipXf()): ClipXf {
        out.reset()
        if (a == null) return out
        val d = durUs.coerceAtLeast(1L) / 1e6f
        val t = (localUs / 1e6f).coerceIn(0f, d)
        val inD = if (a.inId != NONE) a.inDur.coerceIn(0.05f, 5f).coerceAtMost(d * 0.9f) else 0f
        val outD = if (a.outId != NONE) a.outDur.coerceIn(0.05f, 5f).coerceAtMost((d - inD).coerceAtLeast(0.05f)) else 0f
        if (a.comboId != NONE) combo(a.comboId, t, t / d, a.comboSpeed.coerceIn(0.1f, 4f), out)
        if (inD > 0f && t < inD) edge(a.inId, t / inD, exit = false, x = out)
        if (outD > 0f && t > d - outD) edge(a.outId, (d - t) / outD, exit = true, x = out)
        return out
    }

    fun active(a: ClipAnim?): Boolean = a != null && (a.inId != NONE || a.outId != NONE || a.comboId != NONE)
}
