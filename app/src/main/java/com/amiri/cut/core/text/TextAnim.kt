package com.amiri.cut.core.text

import com.amiri.cut.core.model.TextSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** What a text animation moves: the whole layer, each line, each word or each letter. */
enum class AnimUnit { ALL, LINE, WORD, LETTER }

/**
 * A text animation preset. [mask]: the unit is revealed inside its own box (content
 * moves, the box stays) — the classic "line reveal". [stagger]: overlap between units
 * (0 = one after another, 1 = all together).
 */
data class TextAnimSpec(val id: String, val label: String, val unit: AnimUnit, val group: String, val mask: Boolean = false, val stagger: Float = 0.35f)

/** Transform of one unit, relative to its rest position. Offsets are in em (font size). */
class UnitXf {
    var alpha = 1f; var dx = 0f; var dy = 0f; var sx = 1f; var sy = 1f; var rot = 0f; var blur = 0f
    var revealX = 1f // horizontal clip reveal (0..1) for wipes
    fun reset(): UnitXf { alpha = 1f; dx = 0f; dy = 0f; sx = 1f; sy = 1f; rot = 0f; blur = 0f; revealX = 1f; return this }
    val identity: Boolean get() = alpha >= 0.999f && abs(dx) < 1e-4f && abs(dy) < 1e-4f && abs(sx - 1f) < 1e-4f && abs(sy - 1f) < 1e-4f && abs(rot) < 1e-3f && blur < 1e-4f && revealX >= 0.999f
}

object Ease {
    fun outCubic(x: Float) = 1f - (1f - x).pow(3)
    fun inOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    fun outBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }
    fun outElastic(x: Float): Float = if (x <= 0f) 0f else if (x >= 1f) 1f else (2.0.pow(-10.0 * x) * sin((x * 10 - 0.75) * (2 * PI / 3)) + 1).toFloat()
    fun outBounce(x0: Float): Float {
        var x = x0
        val n1 = 7.5625f; val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { x -= 1.5f / d1; n1 * x * x + 0.75f }
            x < 2.5f / d1 -> { x -= 2.25f / d1; n1 * x * x + 0.9375f }
            else -> { x -= 2.625f / d1; n1 * x * x + 0.984375f }
        }
    }
}

object TextAnims {
    private fun a(id: String, label: String, unit: AnimUnit, group: String, mask: Boolean = false, stagger: Float = 0.35f) = TextAnimSpec(id, label, unit, group, mask, stagger)

    /** Entrance animations (exits use the same list, played backwards). */
    val ENTER: List<TextAnimSpec> = listOf(
        a("fade", "Fade", AnimUnit.ALL, "Basic"),
        a("slideUp", "Slide Up", AnimUnit.ALL, "Basic"),
        a("slideDown", "Slide Down", AnimUnit.ALL, "Basic"),
        a("slideLeft", "Slide Left", AnimUnit.ALL, "Basic"),
        a("slideRight", "Slide Right", AnimUnit.ALL, "Basic"),
        a("zoomIn", "Zoom In", AnimUnit.ALL, "Basic"),
        a("zoomOut", "Zoom Out", AnimUnit.ALL, "Basic"),
        a("pop", "Pop", AnimUnit.ALL, "Basic"),
        a("elastic", "Elastic", AnimUnit.ALL, "Basic"),
        a("bounce", "Bounce Drop", AnimUnit.ALL, "Basic"),
        a("spin", "Spin In", AnimUnit.ALL, "Basic"),
        a("blur", "Blur In", AnimUnit.ALL, "Basic"),
        a("wipe", "Wipe", AnimUnit.ALL, "Basic"),
        a("glitch", "Glitch", AnimUnit.ALL, "Basic"),
        a("typewriter", "Typewriter", AnimUnit.LETTER, "Letters", stagger = 0f),
        a("letterFade", "Letter Fade", AnimUnit.LETTER, "Letters"),
        a("letterRise", "Letter Rise", AnimUnit.LETTER, "Letters"),
        a("letterDrop", "Letter Drop", AnimUnit.LETTER, "Letters"),
        a("letterPop", "Letter Pop", AnimUnit.LETTER, "Letters"),
        a("letterSpin", "Letter Spin", AnimUnit.LETTER, "Letters"),
        a("letterBlur", "Letter Blur", AnimUnit.LETTER, "Letters"),
        a("letterFlip", "Letter Flip", AnimUnit.LETTER, "Letters"),
        a("letterBounce", "Letter Bounce", AnimUnit.LETTER, "Letters"),
        a("tracking", "Tracking In", AnimUnit.LETTER, "Letters", stagger = 1f),
        a("scatter", "Scatter", AnimUnit.LETTER, "Letters", stagger = 0.6f),
        a("wordRise", "Word Rise", AnimUnit.WORD, "Words"),
        a("wordPop", "Word Pop", AnimUnit.WORD, "Words"),
        a("wordFade", "Word Fade", AnimUnit.WORD, "Words", stagger = 0.5f),
        a("wordSlide", "Word Slide", AnimUnit.WORD, "Words"),
        a("wordBlur", "Word Blur", AnimUnit.WORD, "Words"),
        a("lineReveal", "Line Reveal", AnimUnit.LINE, "Lines", mask = true),
        a("lineSlide", "Line Slide", AnimUnit.LINE, "Lines"),
        a("lineFade", "Line Fade", AnimUnit.LINE, "Lines", stagger = 0.5f),
        a("wordReveal", "Word Reveal", AnimUnit.WORD, "Words", mask = true),
        a("letterReveal", "Letter Reveal", AnimUnit.LETTER, "Letters", mask = true),
    )

    val LOOP: List<TextAnimSpec> = listOf(
        a("wave", "Wave", AnimUnit.LETTER, "Loop"),
        a("float", "Float", AnimUnit.ALL, "Loop"),
        a("pulse", "Pulse", AnimUnit.ALL, "Loop"),
        a("heartbeat", "Heartbeat", AnimUnit.ALL, "Loop"),
        a("shake", "Shake", AnimUnit.ALL, "Loop"),
        a("swing", "Swing", AnimUnit.ALL, "Loop"),
        a("jelly", "Jelly", AnimUnit.ALL, "Loop"),
        a("flicker", "Flicker", AnimUnit.ALL, "Loop"),
        a("letterJump", "Letter Jump", AnimUnit.LETTER, "Loop"),
        a("wordPulse", "Word Pulse", AnimUnit.WORD, "Loop"),
    )

    val GROUPS = listOf("Basic", "Letters", "Words", "Lines")

    fun enter(id: String?): TextAnimSpec? = ENTER.firstOrNull { it.id == id }
    fun loop(id: String?): TextAnimSpec? = LOOP.firstOrNull { it.id == id }

    private fun hash(i: Int, k: Int): Float {
        var x = i * 374761393 + k * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }

    /** Progress of unit [i] of [n] when the whole animation is at [p] (0..1). */
    fun unitProgress(p: Float, i: Int, n: Int, stagger: Float): Float {
        if (n <= 1) return p.coerceIn(0f, 1f)
        if (stagger <= 0f) return (p * n - i).coerceIn(0f, 1f)
        val k = (1f - stagger).coerceIn(0f, 1f)
        val d = 1f / (1f + (n - 1) * k)
        val start = i * k * d
        return ((p - start) / d).coerceIn(0f, 1f)
    }

    /**
     * Entrance transform at unit progress [u] (0 = hidden, 1 = at rest). Exits call this
     * with u = 1 − exit progress. [cx] is the unit's horizontal offset from the text
     * centre in em (used by Tracking / Scatter).
     */
    fun enterXf(id: String, u: Float, i: Int, cx: Float, xf: UnitXf) {
        if (u >= 1f) return
        val e = Ease.outCubic(u)
        when (id) {
            "fade", "letterFade", "wordFade", "lineFade" -> xf.alpha *= e
            "slideUp" -> { xf.alpha *= e; xf.dy += (1f - e) * 1.2f }
            "slideDown" -> { xf.alpha *= e; xf.dy -= (1f - e) * 1.2f }
            "slideLeft" -> { xf.alpha *= e; xf.dx += (1f - e) * 2f }
            "slideRight" -> { xf.alpha *= e; xf.dx -= (1f - e) * 2f }
            "zoomIn" -> { xf.alpha *= e; val s = 0.3f + 0.7f * e; xf.sx *= s; xf.sy *= s }
            "zoomOut" -> { xf.alpha *= e; val s = 1.6f - 0.6f * e; xf.sx *= s; xf.sy *= s }
            "pop", "wordPop", "letterPop" -> { xf.alpha *= (u * 3f).coerceAtMost(1f); val s = Ease.outBack(u).coerceAtLeast(0f); xf.sx *= s; xf.sy *= s }
            "elastic" -> { xf.alpha *= (u * 4f).coerceAtMost(1f); val s = Ease.outElastic(u); xf.sx *= s; xf.sy *= s }
            "bounce", "letterBounce" -> { xf.alpha *= (u * 4f).coerceAtMost(1f); xf.dy -= (1f - Ease.outBounce(u)) * 2.2f }
            "spin", "letterSpin" -> { xf.alpha *= e; xf.rot += (1f - e) * -180f; val s = 0.4f + 0.6f * e; xf.sx *= s; xf.sy *= s }
            "blur", "letterBlur", "wordBlur" -> { xf.alpha *= e; xf.blur += (1f - e) * 0.35f }
            "wipe" -> xf.revealX *= Ease.inOutCubic(u)
            "glitch" -> {
                val f = (u * 12f).toInt()
                xf.alpha *= if (u > 0.85f || hash(f, 3) > 0.35f) e.coerceAtLeast(0.6f) else 0f
                if (u < 0.85f) { xf.dx += (hash(f, 1) - 0.5f) * 0.6f * (1f - u); xf.dy += (hash(f, 2) - 0.5f) * 0.2f * (1f - u) }
            }
            "typewriter" -> xf.alpha *= if (u > 0f) 1f else 0f
            "letterRise", "wordRise", "lineSlide", "lineReveal", "wordReveal", "letterReveal" -> { if (id != "lineReveal" && id != "wordReveal" && id != "letterReveal") xf.alpha *= e; xf.dy += (1f - e) * 1.1f }
            "letterDrop" -> { xf.alpha *= e; xf.dy -= (1f - e) * 1.1f }
            "letterFlip" -> { xf.alpha *= (u * 3f).coerceAtMost(1f); xf.sy *= Ease.outBack(u).coerceAtLeast(0.01f) }
            "wordSlide" -> { xf.alpha *= e; xf.dx += (1f - e) * 1.2f }
            "tracking" -> { xf.alpha *= e; xf.dx += cx * (1f - e) * 0.9f }
            "scatter" -> {
                xf.alpha *= e
                xf.dx += (hash(i, 5) - 0.5f) * 4f * (1f - e); xf.dy += (hash(i, 6) - 0.5f) * 3f * (1f - e)
                xf.rot += (hash(i, 7) - 0.5f) * 240f * (1f - e)
            }
            else -> xf.alpha *= e
        }
    }

    /** Looping motion at time [t] seconds for unit [i]. */
    fun loopXf(id: String, t: Float, i: Int, n: Int, xf: UnitXf) {
        val w = 2f * PI.toFloat()
        when (id) {
            "wave", "letterJump" -> {
                val ph = t * 1.4f - i * 0.12f
                xf.dy += if (id == "wave") sin(w * ph) * 0.18f else -abs(sin(w * ph * 0.5f)).pow(6) * 0.45f
            }
            "float" -> xf.dy += sin(w * t * 0.5f) * 0.18f
            "pulse" -> { val s = 1f + sin(w * t) * 0.06f; xf.sx *= s; xf.sy *= s }
            "heartbeat" -> { val f = (t % 1f); val s = 1f + (exp1(f, 0.0f) + exp1(f, 0.22f) * 0.7f) * 0.12f; xf.sx *= s; xf.sy *= s }
            "shake" -> { val f = (t * 24f).toInt(); xf.dx += (hash(f, 11) - 0.5f) * 0.12f; xf.dy += (hash(f, 12) - 0.5f) * 0.12f }
            "swing" -> xf.rot += sin(w * t * 0.6f) * 8f
            "jelly" -> { val s = sin(w * t * 1.2f) * 0.07f; xf.sx *= 1f + s; xf.sy *= 1f - s }
            "flicker" -> { val f = (t * 14f).toInt(); xf.alpha *= if (hash(f, 21) > 0.12f) 1f else 0.25f }
            "wordPulse" -> { val ph = (t * 1.2f - i * 0.25f); val s = 1f + (sin(w * ph) * 0.5f + 0.5f).pow(4) * 0.18f; xf.sx *= s; xf.sy *= s }
        }
        @Suppress("UNUSED_EXPRESSION") n
    }

    private fun exp1(f: Float, at: Float): Float { val d = f - at; return if (d < 0f) 0f else kotlin.math.exp(-d * 18f) }

    /** True when the text has any animation (in, out or loop). */
    fun animated(s: TextSpec): Boolean = s.animIn != "None" || s.animOut != "None" || s.animLoop != "None"

    /** Enter/exit progress for clip-local time [localUs] in a clip of [durUs]: (pIn, pOut). */
    fun phases(s: TextSpec, localUs: Long, durUs: Long): Pair<Float, Float> {
        val t = localUs / 1_000_000f
        val d = durUs / 1_000_000f
        val inD = s.inDur.coerceAtLeast(0.05f).coerceAtMost(d / 2f)
        val outD = s.outDur.coerceAtLeast(0.05f).coerceAtMost(d / 2f)
        val pin = if (s.animIn == "None") 1f else (t / inD).coerceIn(0f, 1f)
        val pout = if (s.animOut == "None") 0f else ((t - (d - outD)) / outD).coerceIn(0f, 1f)
        return pin to pout
    }

    /** Whole-layer transform (ALL-unit presets + ALL loops), applied by the compositor. */
    fun layerXf(s: TextSpec, localUs: Long, durUs: Long, xf: UnitXf = UnitXf()): UnitXf {
        xf.reset()
        if (!animated(s)) return xf
        val (pin, pout) = phases(s, localUs, durUs)
        enter(s.animIn)?.takeIf { it.unit == AnimUnit.ALL && it.id != "blur" && it.id != "wipe" }?.let { enterXf(it.id, pin, 0, 0f, xf) }
        enter(s.animOut)?.takeIf { it.unit == AnimUnit.ALL && it.id != "blur" && it.id != "wipe" }?.let { enterXf(it.id, 1f - pout, 0, 0f, xf) }
        loop(s.animLoop)?.takeIf { it.unit == AnimUnit.ALL }?.let { loopXf(it.id, localUs / 1_000_000f * s.loopSpeed, 0, 1, xf) }
        return xf
    }

    @Suppress("unused") private val keep = cos(0.0)
}
