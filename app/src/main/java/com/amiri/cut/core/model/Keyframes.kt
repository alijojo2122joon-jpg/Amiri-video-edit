package com.amiri.cut.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Interpolation used from a keyframe to the next one. */
@Serializable
enum class Interp(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
    BEZIER("Bezier"),
    HOLD("Hold"),
}

/**
 * A keyframe. [t] is relative to the clip start on the timeline (so moving a clip
 * moves its animation). For BEZIER, (c1x,c1y,c2x,c2y) is a CSS-style cubic-bezier
 * easing curve for the segment that starts at this key.
 */
@Serializable
data class Key(
    val t: Long,
    val v: Float,
    val interp: Interp = Interp.LINEAR,
    val c1x: Float = 0.42f,
    val c1y: Float = 0f,
    val c2x: Float = 0.58f,
    val c2y: Float = 1f,
)

/** An animatable scalar. With no keys it is constant [v]. */
@Serializable
data class Param(val v: Float, val keys: List<Key> = emptyList()) {
    val animated: Boolean get() = keys.isNotEmpty()

    fun at(t: Long): Float = Keyframes.eval(this, t)

    fun shift(dt: Long): Param = if (keys.isEmpty()) this else copy(keys = keys.map { it.copy(t = it.t + dt) })

    /** Sets the value: adds/replaces a key at [t] when animated, else changes the constant. */
    fun set(t: Long, value: Float, tolerance: Long): Param =
        if (keys.isEmpty()) copy(v = value) else withKey(t, value, tolerance)

    fun withKey(t: Long, value: Float, tolerance: Long, interp: Interp? = null): Param {
        val existing = keys.firstOrNull { abs(it.t - t) <= tolerance }
        val k = existing?.copy(v = value, interp = interp ?: existing.interp) ?: Key(t, value, interp ?: Interp.LINEAR)
        return copy(keys = (keys.filterNot { it === existing } + k).sortedBy { it.t })
    }

    fun keyNear(t: Long, tolerance: Long): Key? = keys.firstOrNull { abs(it.t - t) <= tolerance }

    fun withoutKeyNear(t: Long, tolerance: Long): Param {
        val k = keyNear(t, tolerance) ?: return this
        val rest = keys.filterNot { it === k }
        return if (rest.isEmpty()) Param(k.v) else copy(keys = rest)
    }
}

/** A set of named animatable params (one effect, the transform, a mask, …). */
@Serializable
data class Props(val p: Map<String, Param> = emptyMap()) {
    operator fun get(id: String): Param? = p[id]
    fun at(id: String, t: Long, default: Float): Float = p[id]?.at(t) ?: default
    fun with(id: String, param: Param): Props = copy(p = p + (id to param))
    fun shift(dt: Long): Props = if (dt == 0L) this else copy(p = p.mapValues { it.value.shift(dt) })
    val animated: Boolean get() = p.values.any { it.animated }
    fun keyTimes(): List<Long> = p.values.flatMap { pr -> pr.keys.map { it.t } }

    companion object {
        fun of(vararg pairs: Pair<String, Float>) = Props(pairs.associate { it.first to Param(it.second) })
    }
}

object Keyframes {

    fun eval(p: Param, t: Long): Float {
        val k = p.keys
        if (k.isEmpty()) return p.v
        if (t <= k.first().t) return k.first().v
        if (t >= k.last().t) return k.last().v
        // Binary search for the segment.
        var lo = 0
        var hi = k.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (k[mid].t <= t) lo = mid else hi = mid
        }
        val a = k[lo]
        val b = k[hi]
        val span = (b.t - a.t).toFloat()
        if (span <= 0f) return b.v
        val u = (t - a.t) / span
        val e = ease(a, u)
        return a.v + (b.v - a.v) * e
    }

    fun ease(a: Key, u: Float): Float = when (a.interp) {
        Interp.LINEAR -> u
        Interp.HOLD -> 0f
        Interp.EASE_IN -> cubicBezier(0.42f, 0f, 1f, 1f, u)
        Interp.EASE_OUT -> cubicBezier(0f, 0f, 0.58f, 1f, u)
        Interp.EASE_IN_OUT -> cubicBezier(0.42f, 0f, 0.58f, 1f, u)
        Interp.BEZIER -> cubicBezier(a.c1x, a.c1y, a.c2x, a.c2y, u)
    }

    /** CSS cubic-bezier(x1,y1,x2,y2) evaluated at progress [x] (0..1). */
    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        fun bx(s: Float) = 3 * (1 - s) * (1 - s) * s * x1 + 3 * (1 - s) * s * s * x2 + s * s * s
        fun by(s: Float) = 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s
        fun dbx(s: Float) = 3 * (1 - s) * (1 - s) * x1 + 6 * (1 - s) * s * (x2 - x1) + 3 * s * s * (1 - x2)
        // Newton iterations, falling back to bisection.
        var s = x
        repeat(8) {
            val d = dbx(s)
            if (abs(d) < 1e-6f) return@repeat
            s -= (bx(s) - x) / d
            s = s.coerceIn(0f, 1f)
        }
        if (abs(bx(s) - x) > 1e-3f) {
            var lo = 0f
            var hi = 1f
            s = x
            repeat(30) {
                if (bx(s) < x) lo = s else hi = s
                s = (lo + hi) / 2
            }
        }
        return by(s)
    }
}
