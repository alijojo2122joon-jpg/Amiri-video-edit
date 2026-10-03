package com.amiri.cut.media

import android.content.Context
import android.graphics.RectF
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.StabMode
import com.amiri.cut.core.model.StabSample
import com.amiri.cut.core.model.TrackSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A grayscale frame plus a half-resolution copy for coarse-to-fine search. */
private class Frame(val g: FloatArray, val w: Int, val h: Int) {
    val hw = max(1, w / 2)
    val hh = max(1, h / 2)
    val half: FloatArray = FloatArray(hw * hh).also { o ->
        for (y in 0 until hh) for (x in 0 until hw) {
            val x0 = min(w - 1, x * 2); val y0 = min(h - 1, y * 2)
            val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
            o[y * hw + x] = (g[y0 * w + x0] + g[y0 * w + x1] + g[y1 * w + x0] + g[y1 * w + x1]) * 0.25f
        }
    }
}

/** Block matching (zero-mean SAD, coarse-to-fine with motion prediction, sub-pixel). */
private object Match {
    /** Zero-mean SAD: robust to brightness changes (flicker, exposure). */
    fun cost(a: FloatArray, b: FloatArray, w: Int, h: Int, cx: Int, cy: Int, half: Int, step: Int, mx: Int, my: Int): Float {
        var sa = 0f; var sb = 0f; var n = 0
        var y = -half
        while (y <= half) {
            var x = -half
            while (x <= half) {
                val ax = cx + x; val ay = cy + y; val bx = ax + mx; val by = ay + my
                if (ax in 0 until w && ay in 0 until h && bx in 0 until w && by in 0 until h) { sa += a[ay * w + ax]; sb += b[by * w + bx]; n++ }
                x += step
            }
            y += step
        }
        if (n < 8) return Float.MAX_VALUE
        val d = (sb - sa) / n
        var s = 0f
        y = -half
        while (y <= half) {
            var x = -half
            while (x <= half) {
                val ax = cx + x; val ay = cy + y; val bx = ax + mx; val by = ay + my
                if (ax in 0 until w && ay in 0 until h && bx in 0 until w && by in 0 until h) s += abs(a[ay * w + ax] + d - b[by * w + bx])
                x += step
            }
            y += step
        }
        return s / n
    }

    private class Best(var x: Int, var y: Int, var c: Float)

    private fun search(a: FloatArray, b: FloatArray, w: Int, h: Int, cx: Int, cy: Int, half: Int, step: Int, px: Int, py: Int, r: Int): Best {
        val best = Best(px, py, cost(a, b, w, h, cx, cy, half, step, px, py))
        for (my in py - r..py + r) for (mx in px - r..px + r) {
            val c = cost(a, b, w, h, cx, cy, half, step, mx, my)
            if (c < best.c) { best.c = c; best.x = mx; best.y = my }
        }
        return best
    }

    /**
     * Displacement of the block at (cx, cy) from [fa] to [fb], searching ±[range] px around
     * the predicted motion (pdx, pdy). Returns dx, dy, cost.
     */
    fun find(fa: Frame, fb: Frame, cx: Float, cy: Float, half: Int, range: Int, pdx: Float, pdy: Float): FloatArray {
        val w = fa.w; val h = fa.h
        // Coarse: half resolution, wide search around the prediction.
        val hh = max(3, half / 2)
        val coarse = search(fa.half, fb.half, fa.hw, fa.hh, (cx / 2).roundToInt(), (cy / 2).roundToInt(), hh, max(1, hh / 6),
            (pdx / 2).roundToInt(), (pdy / 2).roundToInt(), max(2, range / 2))
        // Fine: full resolution around the coarse result (and around "no motion").
        val icx = cx.roundToInt(); val icy = cy.roundToInt()
        val step = max(1, half / 8)
        val best = search(fa.g, fb.g, w, h, icx, icy, half, step, coarse.x * 2, coarse.y * 2, 2)
        if (abs(coarse.x) > 1 || abs(coarse.y) > 1) {
            val z = search(fa.g, fb.g, w, h, icx, icy, half, step, 0, 0, 1)
            if (z.c < best.c) { best.c = z.c; best.x = z.x; best.y = z.y }
        }
        fun sub(l: Float, c: Float, r: Float): Float {
            if (l == Float.MAX_VALUE || r == Float.MAX_VALUE) return 0f
            val d = l - 2 * c + r
            return if (d <= 1e-6f) 0f else (0.5f * (l - r) / d).coerceIn(-0.5f, 0.5f)
        }
        val sx = sub(cost(fa.g, fb.g, w, h, icx, icy, half, step, best.x - 1, best.y), best.c, cost(fa.g, fb.g, w, h, icx, icy, half, step, best.x + 1, best.y))
        val sy = sub(cost(fa.g, fb.g, w, h, icx, icy, half, step, best.x, best.y - 1), best.c, cost(fa.g, fb.g, w, h, icx, icy, half, step, best.x, best.y + 1))
        return floatArrayOf(best.x + sx + (icx - cx), best.y + sy + (icy - cy), best.c)
    }

    fun std(a: FloatArray, w: Int, h: Int, cx: Int, cy: Int, half: Int): Float {
        var s = 0f; var s2 = 0f; var n = 0
        val st = max(1, half / 6)
        var y = cy - half
        while (y <= cy + half) {
            var x = cx - half
            while (x <= cx + half) {
                if (x in 0 until w && y in 0 until h) { val v = a[y * w + x]; s += v; s2 += v * v; n++ }
                x += st
            }
            y += st
        }
        if (n == 0) return 0f
        val m = s / n
        return sqrt(max(0f, s2 / n - m * m))
    }
}

/** Robust least-squares similarity fit about (ox, oy) with iterative outlier rejection. */
private object Fit {
    /** p = [x, y, dx, dy]; returns a, b, tx, ty for x' = a·x − b·y + tx, y' = b·x + a·y + ty. */
    fun similarity(p: List<FloatArray>, ox: Float, oy: Float): FloatArray? {
        var cur = p
        var res: FloatArray? = null
        for (iter in 0 until 4) {
            if (cur.size < 3) break
            val f = ls(cur, ox, oy) ?: break
            res = f
            val errs = cur.map { q ->
                val x = q[0] - ox; val y = q[1] - oy
                val u = f[0] * x - f[1] * y + f[2]; val v = f[1] * x + f[0] * y + f[3]
                hypot(u - (x + q[2]), v - (y + q[3]))
            }
            val med = errs.sorted()[errs.size / 2]
            val th = max(0.6f, med * 2.5f)
            val next = cur.filterIndexed { i, _ -> errs[i] <= th }
            if (next.size == cur.size) break
            cur = next
        }
        return res
    }

    private fun ls(p: List<FloatArray>, ox: Float, oy: Float): FloatArray? {
        var sxx = 0.0; var n = 0.0
        var sx = 0.0; var sy = 0.0; var su = 0.0; var sv = 0.0
        var sxu = 0.0; var syv = 0.0; var sxv = 0.0; var syu = 0.0
        for (q in p) {
            val x = (q[0] - ox).toDouble(); val y = (q[1] - oy).toDouble()
            val u = x + q[2]; val v = y + q[3]
            sxx += x * x + y * y; sx += x; sy += y; su += u; sv += v
            sxu += x * u; syv += y * v; sxv += x * v; syu += y * u; n += 1.0
        }
        val d = n * sxx - sx * sx - sy * sy
        if (abs(d) < 1e-9) return null
        val a = (n * (sxu + syv) - sx * su - sy * sv) / d
        val b = (n * (sxv - syu) + sy * su - sx * sv) / d
        val tx = (su - a * sx + b * sy) / n
        val ty = (sv - b * sx - a * sy) / n
        return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    fun median(v: List<Float>): Float { val s = v.sorted(); return if (s.isEmpty()) 0f else s[s.size / 2] }
}

/**
 * Motion tracker.
 *  - POSITION: follows one region; frame-to-frame matching with motion prediction and
 *    drift correction against the first frame's appearance.
 *  - SIMILARITY: follows one region's position, scale and rotation from a grid of
 *    sub-blocks inside it with a robust similarity fit (faces, signs, screens…).
 *  - TWO_POINT: two regions → position, scale and rotation from their line.
 * Works forwards or backwards (toUs < fromUs). Stops when the target is lost.
 */
object MotionTracker {
    enum class Mode { POSITION, SIMILARITY, TWO_POINT }

    class Result(val samples: List<TrackSample>, val lostAtUs: Long?)

    fun track(
        context: Context,
        asset: MediaAsset,
        fromUs: Long,
        toUs: Long,
        stepUs: Long,
        regions: List<RectF>,
        mode: Mode,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Result {
        val out = ArrayList<TrackSample>()
        var lostAt: Long? = null
        val dir = if (toUs >= fromUs) 1 else -1
        val times = ArrayList<Long>()
        var tt = fromUs
        while (if (dir > 0) tt <= toUs else tt >= toUs) { times += tt; tt += dir * stepUs }
        if (times.isEmpty()) return Result(out, null)

        GrayVideo(context, asset, 480).use { gv ->
            val w = gv.w; val h = gv.h
            val regs = if (mode == Mode.TWO_POINT) regions.take(2) else regions.take(1)
            val cx = FloatArray(regs.size) { regs[it].centerX() * w }
            val cy = FloatArray(regs.size) { regs[it].centerY() * h }
            val halves = IntArray(regs.size) { (max(regs[it].width() * w, regs[it].height() * h) / 2f).roundToInt().coerceIn(6, 64) }
            val vx = FloatArray(regs.size); val vy = FloatArray(regs.size)
            var scale = 1f; var rot = 0f
            val rw = regs[0].width() * w; val rh = regs[0].height() * h
            var first: Frame? = null
            var prev: Frame? = null
            var bad = 0
            var k = 0

            fun sample(t: Long): TrackSample = when (mode) {
                Mode.TWO_POINT -> {
                    val dx = cx[1] - cx[0]; val dy = cy[1] - cy[0]
                    val r0 = (regs[1].centerX() - regs[0].centerX()) * w; val r1 = (regs[1].centerY() - regs[0].centerY()) * h
                    TrackSample(t, (cx[0] + cx[1]) / 2f / w, (cy[0] + cy[1]) / 2f / h,
                        hypot(dx, dy) / max(1f, hypot(r0, r1)), Math.toDegrees((atan2(dy, dx) - atan2(r1, r0)).toDouble()).toFloat())
                }
                else -> TrackSample(t, cx[0] / w, cy[0] / h, scale, rot)
            }

            gv.scan(times, isCancelled) { t, gray ->
                val cur = Frame(gray, w, h)
                val pf = prev
                var stop = false
                if (pf == null) {
                    first = cur
                    out += sample(t)
                } else {
                    var conf = 1f
                    if (mode == Mode.SIMILARITY) {
                        val pts = ArrayList<FloatArray>()
                        val n = 5
                        val sub = max(4, (min(rw, rh) * scale / (n + 1)).roundToInt()).coerceAtMost(24)
                        val rr = Math.toRadians(rot.toDouble())
                        val cr = cos(rr).toFloat(); val sr = sin(rr).toFloat()
                        for (j in 0 until n) for (i in 0 until n) {
                            val lx = ((i + 0.5f) / n - 0.5f) * rw * 0.9f * scale
                            val ly = ((j + 0.5f) / n - 0.5f) * rh * 0.9f * scale
                            val px = cx[0] + lx * cr - ly * sr; val py = cy[0] + lx * sr + ly * cr
                            if (px < 2 || py < 2 || px > w - 3 || py > h - 3) continue
                            val sd = Match.std(pf.g, w, h, px.roundToInt(), py.roundToInt(), sub)
                            if (sd < 4f) continue
                            val m = Match.find(pf, cur, px, py, sub, 28, vx[0], vy[0])
                            if (m[2] < sd * 1.2f) pts += floatArrayOf(px, py, m[0], m[1])
                        }
                        val f = if (pts.size >= 4) Fit.similarity(pts, cx[0], cy[0]) else null
                        if (f != null) {
                            val ds = hypot(f[0], f[1]).coerceIn(0.85f, 1.18f)
                            val dr = Math.toDegrees(atan2(f[1], f[0]).toDouble()).toFloat().coerceIn(-12f, 12f)
                            vx[0] = f[2]; vy[0] = f[3]
                            cx[0] += f[2]; cy[0] += f[3]; scale *= ds; rot += dr
                        } else if (pts.isNotEmpty()) {
                            vx[0] = Fit.median(pts.map { it[2] }); vy[0] = Fit.median(pts.map { it[3] })
                            cx[0] += vx[0]; cy[0] += vy[0]
                            conf = 0.5f
                        } else conf = 0f
                    } else {
                        for (i in cx.indices) {
                            val sd = Match.std(pf.g, w, h, cx[i].roundToInt(), cy[i].roundToInt(), halves[i]).coerceAtLeast(1f)
                            val m = Match.find(pf, cur, cx[i], cy[i], halves[i], 40, vx[i], vy[i])
                            var nx = cx[i] + m[0]; var ny = cy[i] + m[1]
                            var c = m[2]
                            // Drift correction: re-match the first frame's appearance nearby.
                            val f0 = first
                            if (f0 != null && mode == Mode.POSITION) {
                                val ox = regs[i].centerX() * w; val oy = regs[i].centerY() * h
                                val d = Match.find(f0, cur, ox, oy, halves[i], 2, nx - ox, ny - oy)
                                if (d[2] < c * 1.15f) { nx = ox + d[0]; ny = oy + d[1]; c = min(c, d[2]) }
                            }
                            vx[i] = (nx - cx[i]) * 0.7f + vx[i] * 0.3f; vy[i] = (ny - cy[i]) * 0.7f + vy[i] * 0.3f
                            cx[i] = nx.coerceIn(0f, w - 1f); cy[i] = ny.coerceIn(0f, h - 1f)
                            if (c > sd * 1.6f) conf = 0f
                        }
                    }
                    if (conf <= 0f) bad++ else bad = 0
                    if (bad >= 6) { lostAt = t; stop = true } else out += sample(t)
                }
                prev = cur
                k++
                onProgress(k.toFloat() / times.size)
                !stop
            }
        }
        // Drop the last uncertain samples before a loss.
        if (lostAt != null && out.size > 6) repeat(5) { out.removeAt(out.lastIndex) }
        return Result(if (dir < 0) out.reversed() else out, lostAt)
    }
}

/**
 * Video stabilizer. Estimates global camera motion between frames from a dense grid of
 * textured blocks with a robust similarity fit (moving subjects are rejected as
 * outliers), then smooths the camera path (BASIC: position, ADVANCED: + rotation & scale)
 * or locks it completely (LOCK = tripod). Returns per-frame corrections and the zoom
 * needed to hide the moving borders.
 */
object Stabilizer {
    class Result(val samples: List<StabSample>, val autoZoom: Float)

    fun analyze(
        context: Context,
        asset: MediaAsset,
        fromUs: Long,
        toUs: Long,
        stepUs: Long,
        mode: StabMode,
        smoothness: Float,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Result {
        val times = ArrayList<Long>()
        var t0 = fromUs
        while (t0 <= toUs) { times += t0; t0 += stepUs }
        val ts = ArrayList<Long>()
        val tx = ArrayList<Float>(); val ty = ArrayList<Float>(); val tr = ArrayList<Float>(); val tsc = ArrayList<Float>()
        var w = 0; var h = 0
        GrayVideo(context, asset, 360).use { gv ->
            w = gv.w; h = gv.h
            var prev: Frame? = null
            var X = 0f; var Y = 0f; var R = 0f; var S = 0f // S = log scale
            var pdx = 0f; var pdy = 0f
            var k = 0
            gv.scan(times, isCancelled) { t, gray ->
                val cur = Frame(gray, w, h)
                val pf = prev
                if (pf != null) {
                    val pts = ArrayList<FloatArray>()
                    val gx = 9; val gy = 7
                    val half = 9
                    for (j in 0 until gy) for (i in 0 until gx) {
                        val cx = (i + 0.5f) * w / gx; val cy = (j + 0.5f) * h / gy
                        if (Match.std(pf.g, w, h, cx.roundToInt(), cy.roundToInt(), half) < 5f) continue
                        val m = Match.find(pf, cur, cx, cy, half, 36, pdx, pdy)
                        pts += floatArrayOf(cx, cy, m[0], m[1])
                    }
                    var dx = 0f; var dy = 0f; var dr = 0f; var ds = 0f
                    if (pts.isNotEmpty()) {
                        dx = Fit.median(pts.map { it[2] }); dy = Fit.median(pts.map { it[3] })
                        if (pts.size >= 5) {
                            val f = Fit.similarity(pts, w / 2f, h / 2f)
                            if (f != null) {
                                dx = f[2]; dy = f[3]
                                if (mode != StabMode.BASIC) {
                                    dr = Math.toDegrees(atan2(f[1], f[0]).toDouble()).toFloat().coerceIn(-8f, 8f)
                                    ds = ln(hypot(f[0], f[1]).coerceIn(0.9f, 1.1f).toDouble()).toFloat()
                                }
                            }
                        }
                    }
                    pdx = dx; pdy = dy
                    X += dx; Y += dy; R += dr; S += ds
                }
                ts += t; tx += X; ty += Y; tr += R; tsc += S
                prev = cur
                k++
                onProgress(k.toFloat() / times.size)
                true
            }
        }
        if (ts.isEmpty()) return Result(emptyList(), 1f)
        val n = ts.size
        val target: List<FloatArray> = if (mode == StabMode.LOCK) {
            // Tripod: hold the average framing for the whole clip.
            val mx = tx.average().toFloat(); val my = ty.average().toFloat(); val mr = tr.average().toFloat(); val ms = tsc.average().toFloat()
            List(n) { floatArrayOf(mx, my, mr, ms) }
        } else {
            val radius = (6 + smoothness * smoothness * 160).roundToInt()
            val r2 = radius / 2 + 1
            val a = smooth(smooth(tx, radius), r2)
            val b = smooth(smooth(ty, radius), r2)
            val c = smooth(smooth(tr, radius), r2)
            val d = smooth(smooth(tsc, radius), r2)
            List(n) { floatArrayOf(a[it], b[it], c[it], d[it]) }
        }
        val aspect = w.toFloat() / h
        var need = 1f
        val samples = ts.indices.map { i ->
            val dxN = (target[i][0] - tx[i]) / w
            val dyN = (target[i][1] - ty[i]) / h
            val rot = if (mode == StabMode.BASIC) 0f else target[i][2] - tr[i]
            val sc = if (mode == StabMode.BASIC) 1f else exp((target[i][3] - tsc[i]).toDouble()).toFloat()
            // Zoom needed so the shifted/rotated frame still covers the canvas.
            val rr = Math.toRadians(abs(rot).toDouble())
            val zr = (cos(rr) + sin(rr) * max(aspect, 1f / aspect)).toFloat()
            val z = max(1f + 2f * abs(dxN), 1f + 2f * abs(dyN)) * zr / sc.coerceAtLeast(0.5f)
            need = max(need, z)
            StabSample(ts[i], dxN, dyN, rot, sc)
        }
        return Result(samples, need.coerceIn(1f, 1.6f))
    }

    private fun smooth(v: List<Float>, r: Int): List<Float> {
        val n = v.size
        val out = ArrayList<Float>(n)
        val sigma = max(1f, r / 2f)
        for (i in 0 until n) {
            var s = 0f; var ws = 0f
            for (k in max(0, i - r)..min(n - 1, i + r)) {
                val wgt = exp(-((k - i) * (k - i)) / (2 * sigma * sigma))
                s += v[k] * wgt; ws += wgt
            }
            out += s / ws
        }
        return out
    }
}
