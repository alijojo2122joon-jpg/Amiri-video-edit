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
        val w = max(1f, v(s, "w", t) * base)
        val h = max(1f, v(s, "h", t) * base)
        val stroke = v(s, "strokeW", t) * base
        return Geo(w, h, stroke, (stroke + 3f).roundToInt())
    }

    fun measure(s: ShapeSpec, t: Long, cw: Int, ch: Int): Pair<Int, Int> = geo(s, t, cw, ch).let { it.bw to it.bh }

    fun render(s: ShapeSpec, t: Long, cw: Int, ch: Int): Bitmap {
        val g = geo(s, t, cw, ch)
        val bmp = Bitmap.createBitmap(g.bw, g.bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val l = g.pad.toFloat()
        val r = RectF(l, l, l + g.w, l + g.h)
        val path = Path()
        when (s.kind) {
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
        val fillA = v(s, "fillA", t)
        if (fillA > 0.001f) c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(s, "f", t, fillA) })
        if (g.stroke > 0.3f && s.kind != ShapeKind.LINE) {
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = g.stroke; strokeJoin = Paint.Join.ROUND
                color = color(s, "s", t, v(s, "strokeA", t))
            })
        }
        return bmp
    }
}
