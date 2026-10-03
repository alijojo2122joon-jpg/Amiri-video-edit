package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.StabMode
import com.amiri.cut.core.model.StabSample
import com.amiri.cut.core.model.TrackSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Grayscale frames from a video at analysis resolution (hardware decode via MediaMetadataRetriever). */
private class FrameGrabber(context: Context, asset: MediaAsset, val w: Int) : AutoCloseable {
    private val r = MediaMetadataRetriever()
    val h: Int

    init {
        r.setDataSource(context, Uri.parse(asset.uri))
        val dw = asset.displayWidth.coerceAtLeast(1)
        val dh = asset.displayHeight.coerceAtLeast(1)
        h = max(16, (w.toFloat() * dh / dw).roundToInt())
    }

    fun gray(t: Long): FloatArray? {
        val b = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: return null
        val s = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return FloatArray(px.size) { i -> val c = px[i]; 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c) }
    }

    override fun close() { runCatching { r.release() } }
}

/** Block matching helpers (sum of absolute differences, coarse-to-fine, sub-pixel). */
private object Match {
    fun sad(a: FloatArray, b: FloatArray, w: Int, h: Int, cx: Int, cy: Int, half: Int, step: Int, mx: Int, my: Int): Float {
        var s = 0f
        var n = 0
        var y = -half
        while (y <= half) {
            var x = -half
            while (x <= half) {
                val ax = cx + x; val ay = cy + y
                val bx = ax + mx; val by = ay + my
                if (ax in 0 until w && ay in 0 until h && bx in 0 until w && by in 0 until h) {
                    s += abs(a[ay * w + ax] - b[by * w + bx]); n++
                }
                x += step
            }
            y += step
        }
        return if (n < 8) Float.MAX_VALUE else s / n
    }

    /** Returns (dx, dy) of the block centered at (cx, cy) from frame [a] to frame [b]. */
    fun find(a: FloatArray, b: FloatArray, w: Int, h: Int, cx: Int, cy: Int, half: Int, search: Int): Pair<Float, Float> {
        val step = max(1, half / 8)
        var best = 0 to 0
        var bestC = sad(a, b, w, h, cx, cy, half, step, 0, 0)
        var my = -search
        while (my <= search) {
            var mx = -search
            while (mx <= search) {
                val c = sad(a, b, w, h, cx, cy, half, step, mx, my)
                if (c < bestC) { bestC = c; best = mx to my }
                mx += 2
            }
            my += 2
        }
        val (bx0, by0) = best
        for (yy in by0 - 1..by0 + 1) for (xx in bx0 - 1..bx0 + 1) {
            val c = sad(a, b, w, h, cx, cy, half, step, xx, yy)
            if (c < bestC) { bestC = c; best = xx to yy }
        }
        val (bx, by) = best
        fun sub(l: Float, c: Float, r: Float): Float {
            if (l == Float.MAX_VALUE || r == Float.MAX_VALUE) return 0f
            val d = l - 2 * c + r
            return if (d <= 1e-6f) 0f else (0.5f * (l - r) / d).coerceIn(-0.5f, 0.5f)
        }
        val sx = sub(sad(a, b, w, h, cx, cy, half, step, bx - 1, by), bestC, sad(a, b, w, h, cx, cy, half, step, bx + 1, by))
        val sy = sub(sad(a, b, w, h, cx, cy, half, step, bx, by - 1), bestC, sad(a, b, w, h, cx, cy, half, step, bx, by + 1))
        return (bx + sx) to (by + sy)
    }

    fun variance(a: FloatArray, w: Int, h: Int, cx: Int, cy: Int, half: Int): Float {
        var s = 0f; var s2 = 0f; var n = 0
        for (y in cy - half..cy + half step 2) for (x in cx - half..cx + half step 2) {
            if (x in 0 until w && y in 0 until h) { val v = a[y * w + x]; s += v; s2 += v * v; n++ }
        }
        if (n == 0) return 0f
        val m = s / n
        return s2 / n - m * m
    }
}

/**
 * Motion tracking: follows one region (position) or two regions (position + scale +
 * rotation) from frame to frame with block matching on 320-px frames.
 */
object MotionTracker {
    fun track(
        context: Context,
        asset: MediaAsset,
        fromUs: Long,
        toUs: Long,
        stepUs: Long,
        regions: List<RectF>,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<TrackSample> {
        val out = ArrayList<TrackSample>()
        FrameGrabber(context, asset, 320).use { g ->
            val w = g.w; val h = g.h
            var prev = g.gray(fromUs) ?: return out
            val centers = regions.map { r -> floatArrayOf(r.centerX() * w, r.centerY() * h) }.toMutableList()
            val halves = regions.map { r -> (max(r.width() * w, r.height() * h) / 2f).roundToInt().coerceIn(6, 48) }
            fun sample(t: Long): TrackSample {
                val c0 = centers[0]
                var scale = 1f
                var rot = 0f
                if (centers.size >= 2) {
                    val dx = centers[1][0] - c0[0]; val dy = centers[1][1] - c0[1]
                    val r0 = regions[1].centerX() * w - regions[0].centerX() * w
                    val r1 = regions[1].centerY() * h - regions[0].centerY() * h
                    scale = hypot(dx, dy) / max(1f, hypot(r0, r1))
                    rot = Math.toDegrees((atan2(dy, dx) - atan2(r1, r0)).toDouble()).toFloat()
                }
                val mx = if (centers.size >= 2) (c0[0] + centers[1][0]) / 2f else c0[0]
                val my = if (centers.size >= 2) (c0[1] + centers[1][1]) / 2f else c0[1]
                return TrackSample(t, mx / w, my / h, scale, rot)
            }
            out += sample(fromUs)
            val total = max(1L, (toUs - fromUs) / stepUs)
            var k = 1L
            var t = fromUs + stepUs
            while (t <= toUs) {
                if (isCancelled()) break
                val cur = g.gray(t) ?: break
                for (i in centers.indices) {
                    val c = centers[i]
                    val (dx, dy) = Match.find(prev, cur, w, h, c[0].roundToInt(), c[1].roundToInt(), halves[i], 20)
                    c[0] = (c[0] + dx).coerceIn(0f, w - 1f)
                    c[1] = (c[1] + dy).coerceIn(0f, h - 1f)
                }
                out += sample(t)
                prev = cur
                onProgress(k.toFloat() / total)
                k++
                t += stepUs
            }
        }
        return out
    }
}

/**
 * Video stabilizer. Estimates global camera motion between frames from a grid of
 * textured blocks (Basic: translation from the median; Advanced: a robust similarity
 * fit = translation + rotation + scale), smooths the camera path with a gaussian, and
 * outputs per-frame corrections that the compositor applies (with a small zoom).
 */
object Stabilizer {
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
    ): List<StabSample> {
        val times = ArrayList<Long>()
        val tx = ArrayList<Float>(); val ty = ArrayList<Float>(); val tr = ArrayList<Float>(); val ts = ArrayList<Float>()
        var w = 0; var h = 0
        FrameGrabber(context, asset, 256).use { g ->
            w = g.w; h = g.h
            var prev = g.gray(fromUs) ?: return emptyList()
            var X = 0f; var Y = 0f; var R = 0f; var S = 1f
            times += fromUs; tx += X; ty += Y; tr += R; ts += S
            val total = max(1L, (toUs - fromUs) / stepUs)
            var k = 1L
            var t = fromUs + stepUs
            while (t <= toUs) {
                if (isCancelled()) break
                val cur = g.gray(t) ?: break
                val pts = ArrayList<FloatArray>() // x, y, dx, dy
                val gx = 5; val gy = 4
                for (j in 0 until gy) for (i in 0 until gx) {
                    val cx = ((i + 0.5f) * w / gx).roundToInt()
                    val cy = ((j + 0.5f) * h / gy).roundToInt()
                    if (Match.variance(prev, w, h, cx, cy, 10) < 60f) continue
                    val (dx, dy) = Match.find(prev, cur, w, h, cx, cy, 10, 12)
                    pts += floatArrayOf(cx.toFloat(), cy.toFloat(), dx, dy)
                }
                var dxm = 0f; var dym = 0f; var rot = 0f; var sc = 1f
                if (pts.isNotEmpty()) {
                    dxm = median(pts.map { it[2] }); dym = median(pts.map { it[3] })
                    if (mode == StabMode.ADVANCED && pts.size >= 4) {
                        val inl = pts.filter { abs(it[2] - dxm) < 3f && abs(it[3] - dym) < 3f }.ifEmpty { pts }
                        fitSimilarity(inl, w / 2f, h / 2f)?.let { (a, b, fx, fy) ->
                            rot = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
                            sc = hypot(a, b)
                            dxm = fx; dym = fy
                        }
                    }
                }
                X += dxm; Y += dym; R += rot; S *= sc
                times += t; tx += X; ty += Y; tr += R; ts += S
                prev = cur
                onProgress(k.toFloat() / total)
                k++
                t += stepUs
            }
        }
        if (times.isEmpty()) return emptyList()
        val radius = (4 + smoothness * 45).roundToInt()
        val sX = smooth(tx, radius); val sY = smooth(ty, radius); val sR = smooth(tr, radius); val sS = smooth(ts, radius)
        return times.indices.map { i ->
            StabSample(
                sourceUs = times[i],
                dx = (sX[i] - tx[i]) / w,
                dy = (sY[i] - ty[i]) / h,
                rot = if (mode == StabMode.ADVANCED) sR[i] - tr[i] else 0f,
                scale = if (mode == StabMode.ADVANCED) (sS[i] / ts[i].coerceAtLeast(0.01f)) else 1f,
            )
        }
    }

    private fun median(v: List<Float>): Float {
        val s = v.sorted()
        return if (s.isEmpty()) 0f else s[s.size / 2]
    }

    /** Least-squares similarity about (ox, oy): x' = a·x − b·y + tx, y' = b·x + a·y + ty. */
    private fun fitSimilarity(p: List<FloatArray>, ox: Float, oy: Float): FloatArray? {
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
        val txv = (su - a * sx + b * sy) / n
        val tyv = (sv - b * sx - a * sy) / n
        return floatArrayOf(a.toFloat(), b.toFloat(), txv.toFloat(), tyv.toFloat())
    }

    private fun smooth(v: List<Float>, r: Int): FloatArray {
        val n = v.size
        val out = FloatArray(n)
        val sigma = max(1f, r / 2f)
        for (i in 0 until n) {
            var s = 0f; var ws = 0f
            for (k in max(0, i - r)..min(n - 1, i + r)) {
                val wgt = exp(-((k - i) * (k - i)) / (2 * sigma * sigma))
                s += v[k] * wgt; ws += wgt
            }
            out[i] = s / ws
        }
        return out
    }
}
