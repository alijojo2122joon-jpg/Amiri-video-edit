package com.amiri.cut.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.amiri.cut.core.effects.ShapeSpecDefaults
import com.amiri.cut.core.model.ShapeKind
import com.amiri.cut.core.model.ShapeSpec
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rasterizes vector shape layers at canvas scale (anti-aliased, premultiplied). */
object ShapeRenderer {

    private fun v(s: ShapeSpec, id: String, t: Long) = s.props.at(id, t, ShapeSpecDefaults.def(id))

    private fun color(s: ShapeSpec, pre: String, t: Long, a: Float) = Color.argb(
        (a.coerceIn(0f, 1f) * 255).roundToInt(),
        (v(s, "${pre}r", t) * 255).roundToInt().coerceIn(0, 255),
        (v(s, "${pre}g", t) * 255).roundToInt().coerceIn(0, 255),
        (v(s, "${pre}b", t) * 255).roundToInt().coerceIn(0, 255),
    )

    fun key(s: ShapeSpec, t: Long, cw: Int, ch: Int): String {
        val sb = StringBuilder(s.kind.name).append(cw).append('x').append(ch)
        if (s.kind == ShapeKind.PATH) sb.append('#').append(s.path.hashCode()).append(s.closed).append(s.roundCaps)
        for (p in ShapeSpecDefaults.PARAMS) sb.append('|').append("%.4f".format(v(s, p.id, t)))
        for ((pre, _, _) in ShapeSpecDefaults.COLORS) for (c in listOf("r", "g", "b")) sb.append('|').append("%.3f".format(v(s, pre + c, t)))
        return sb.toString()
    }

    private class Geo(val w: Float, val h: Float, val stroke: Float, val pad: Int) {
        val bw get() = (ceil(w + 2 * pad)).toInt().coerceIn(2, 4096)
        val bh get() = (ceil(h + 2 * pad)).toInt().coerceIn(2, 4096)
    }

    private fun geo(s: ShapeSpec, t: Long, cw: Int, ch: Int): Geo {
        val base = min(cw, ch).toFloat()
        if (s.kind == ShapeKind.PATH) return Geo(cw.toFloat(), ch.toFloat(), v(s, "strokeW", t) * base, 0)
        val w = max(1f, v(s, "w", t) * base)
        val h = max(1f, v(s, "h", t) * base)
        val stroke = v(s, "strokeW", t) * base
        return Geo(w, h, stroke, (stroke + 3f).roundToInt())
    }

    fun measure(s: ShapeSpec, t: Long, cw: Int, ch: Int): Pair<Int, Int> = geo(s, t, cw, ch).let { it.bw to it.bh }

    /** Builds an Android path from PATH vertices scaled to [w]×[h] (plus offset). */
    fun vertexPath(pts: List<Float>, closed: Boolean, w: Float, h: Float, ox: Float = 0f, oy: Float = 0f): Path {
        val path = Path()
        val n = pts.size / 6
        if (n == 0) return path
        fun x(i: Int) = ox + pts[i * 6] * w
        fun y(i: Int) = oy + pts[i * 6 + 1] * h
        path.moveTo(x(0), y(0))
        val segs = if (closed) n else n - 1
        for (k in 0 until segs) {
            val a = k
            val b = (k + 1) % n
            path.cubicTo(
                x(a) + pts[a * 6 + 4] * w, y(a) + pts[a * 6 + 5] * h,
                x(b) + pts[b * 6 + 2] * w, y(b) + pts[b * 6 + 3] * h,
                x(b), y(b),
            )
        }
        if (n == 1) path.lineTo(x(0) + 0.5f, y(0))
        if (closed) path.close()
        return path
    }

    /** Applies trim paths (start/end 0..1, offset wraps) to [src]. */
    fun trim(src: Path, start: Float, end: Float, offset: Float): Path {
        val s0 = start.coerceIn(0f, 1f)
        val e0 = end.coerceIn(0f, 1f)
        if (s0 <= 0.0001f && e0 >= 0.9999f && offset == 0f) return src
        val a = min(s0, e0)
        val b = max(s0, e0)
        val out = Path()
        if (b - a <= 0.0001f) return out
        val pm = android.graphics.PathMeasure(src, false)
        do {
            val len = pm.length
            if (len <= 0f) continue
            var from = (a + offset) % 1f
            if (from < 0f) from += 1f
            val span = b - a
            val to = from + span
            if (to <= 1f) pm.getSegment(from * len, to * len, out, true)
            else {
                pm.getSegment(from * len, len, out, true)
                pm.getSegment(0f, (to - 1f) * len, out, true)
            }
        } while (pm.nextContour())
        return out
    }

    /** The shape outline in the layer bitmap's pixel space (before trimming). */
    fun buildPath(s: ShapeSpec, t: Long, cw: Int, ch: Int): Path {
        val g = geo(s, t, cw, ch)
        val l = g.pad.toFloat()
        val r = RectF(l, l, l + g.w, l + g.h)
        val path = Path()
        when (s.kind) {
            ShapeKind.PATH -> return vertexPath(s.path, s.closed, g.w, g.h)
            ShapeKind.RECT -> { val rad = v(s, "radius", t) * min(g.w, g.h); path.addRoundRect(r, rad, rad, Path.Direction.CW) }
            ShapeKind.ELLIPSE -> path.addOval(r, Path.Direction.CW)
            ShapeKind.POLYGON, ShapeKind.STAR -> {
                val n = v(s, "sides", t).roundToInt().coerceIn(3, 16)
                val star = s.kind == ShapeKind.STAR
                val inner = v(s, "inner", t)
                val pts = if (star) n * 2 else n
                for (i in 0 until pts) {
                    val a = -Math.PI / 2 + i * 2 * Math.PI / pts
                    val k = if (star && i % 2 == 1) inner else 1f
                    val x = r.centerX() + (cos(a) * g.w / 2 * k).toFloat()
                    val y = r.centerY() + (sin(a) * g.h / 2 * k).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            ShapeKind.LINE -> path.addRoundRect(r, g.h / 2, g.h / 2, Path.Direction.CW)
        }
        return path
    }

    fun render(s: ShapeSpec, t: Long, cw: Int, ch: Int): Bitmap {
        val g = geo(s, t, cw, ch)
        val bmp = Bitmap.createBitmap(g.bw, g.bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = trim(buildPath(s, t, cw, ch), v(s, "trimStart", t), v(s, "trimEnd", t), v(s, "trimOffset", t))
        val fillA = v(s, "fillA", t)
        val fillable = s.kind != ShapeKind.PATH || s.closed
        if (fillA > 0.001f && fillable) c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(s, "f", t, fillA) })
        if (g.stroke > 0.3f && s.kind != ShapeKind.LINE) {
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = g.stroke; strokeJoin = Paint.Join.ROUND
                if (s.roundCaps) strokeCap = Paint.Cap.ROUND
                color = color(s, "s", t, v(s, "strokeA", t))
            })
        }
        return bmp
    }
}
