package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.amiri.cut.core.model.MediaAsset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Propagates a roto mask through following frames using on-device motion tracking.
 *
 * Each frame is decoded (hardware decoder via MediaMetadataRetriever), reduced to a
 * small grayscale image, and the masked subject is located in the next frame by
 * coarse-to-fine block matching (sum of absolute differences over the subject's
 * pixels, with sub-pixel refinement). The keyframe mask is then moved by the
 * accumulated motion. Tracks position (translation); scale/rotation and shape
 * changes are fixed by painting corrections on later frames.
 */
object RotoPropagator {

    private const val TRACK_W = 192
    private const val SEARCH = 14

    class Result(val sourceUs: Long, val mask: Bitmap)

    /**
     * @param fromUs source time of [startMask]
     * @param toUs   last source time to generate (exclusive of fromUs)
     * @param stepUs frame step in source time
     * @return generated masks (one per step), or partial results if cancelled
     */
    fun propagate(
        context: Context,
        asset: MediaAsset,
        startMask: Bitmap,
        fromUs: Long,
        toUs: Long,
        stepUs: Long,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<Result> {
        val out = ArrayList<Result>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(asset.uri))
            val aspect = startMask.width.toFloat() / startMask.height
            val tw = TRACK_W
            val th = max(16, (TRACK_W / aspect).roundToInt())

            fun frame(t: Long): FloatArray? {
                val b = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, tw, th) ?: return null
                val s = if (b.width != tw || b.height != th) Bitmap.createScaledBitmap(b, tw, th, true) else b
                val px = IntArray(tw * th)
                s.getPixels(px, 0, tw, 0, 0, tw, th)
                return FloatArray(px.size) { i ->
                    val c = px[i]
                    0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                }
            }

            // Subject weights at tracking resolution.
            val small = Bitmap.createScaledBitmap(startMask, tw, th, true)
            val sp = IntArray(tw * th)
            small.getPixels(sp, 0, tw, 0, 0, tw, th)
            val weight = BooleanArray(sp.size) { Color.alpha(sp[it]) > 128 }
            if (weight.none { it }) return out

            var prev = frame(fromUs) ?: return out
            var dx = 0f
            var dy = 0f
            val total = max(1L, (toUs - fromUs) / stepUs)
            var k = 1L
            var t = fromUs + stepUs
            while (t <= toUs) {
                if (isCancelled()) break
                val cur = frame(t) ?: break
                val (ddx, ddy) = match(prev, cur, tw, th, weight, dx, dy)
                dx += ddx
                dy += ddy
                val moved = translate(startMask, dx * startMask.width / tw, dy * startMask.height / th)
                out += Result(t, moved)
                prev = cur
                onProgress(k.toFloat() / total)
                k++
                t += stepUs
            }
        } catch (_: Throwable) {
            // Return what we have.
        } finally {
            runCatching { r.release() }
        }
        return out
    }

    /** Finds the shift of the subject (weights moved by [ox],[oy]) from [a] to [b]. */
    private fun match(a: FloatArray, b: FloatArray, w: Int, h: Int, weight: BooleanArray, ox: Float, oy: Float): Pair<Float, Float> {
        val ix = ox.roundToInt()
        val iy = oy.roundToInt()
        // Sample subject pixels (subsampled for speed).
        val pts = ArrayList<Int>()
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            if (!weight[y * w + x]) continue
            val sx = x + ix
            val sy = y + iy
            if (sx in 0 until w && sy in 0 until h) pts += sy * w + sx
        }
        if (pts.isEmpty()) return 0f to 0f

        fun cost(mx: Int, my: Int): Float {
            var sum = 0f
            var n = 0
            for (p in pts) {
                val px = p % w + mx
                val py = p / w + my
                if (px < 0 || py < 0 || px >= w || py >= h) continue
                sum += abs(a[p] - b[py * w + px])
                n++
            }
            return if (n < pts.size / 3) Float.MAX_VALUE else sum / n
        }

        // Coarse (step 2) then fine (step 1) search.
        var best = 0 to 0
        var bestC = cost(0, 0)
        for (my in -SEARCH..SEARCH step 2) for (mx in -SEARCH..SEARCH step 2) {
            val c = cost(mx, my)
            if (c < bestC) { bestC = c; best = mx to my }
        }
        val (cx, cy) = best
        for (my in cy - 1..cy + 1) for (mx in cx - 1..cx + 1) {
            val c = cost(mx, my)
            if (c < bestC) { bestC = c; best = mx to my }
        }
        // Sub-pixel parabola fit on each axis.
        val (bx, by) = best
        val c0 = bestC
        val sxp = subPixel(cost(bx - 1, by), c0, cost(bx + 1, by))
        val syp = subPixel(cost(bx, by - 1), c0, cost(bx, by + 1))
        return (bx + sxp) to (by + syp)
    }

    private fun subPixel(l: Float, c: Float, r: Float): Float {
        if (l == Float.MAX_VALUE || r == Float.MAX_VALUE) return 0f
        val d = l - 2 * c + r
        if (d <= 1e-6f) return 0f
        return (0.5f * (l - r) / d).coerceIn(-0.5f, 0.5f)
    }

    fun translate(src: Bitmap, dx: Float, dy: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, dx, dy, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Refine edge: smooths the matte (box blur) and re-sharpens it to a clean soft edge. */
    fun refineEdge(src: Bitmap, radius: Int, softness: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var a = FloatArray(px.size) { Color.alpha(px[it]) / 255f }
        a = boxBlur(a, w, h, radius)
        a = boxBlur(a, w, h, radius)
        val lo = 0.5f - softness / 2f
        val hi = 0.5f + softness / 2f
        for (i in a.indices) {
            val v = ((a[i] - lo) / max(1e-3f, hi - lo)).coerceIn(0f, 1f)
            val s = v * v * (3 - 2 * v)
            px[i] = Color.argb((s * 255).roundToInt(), 255, 255, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += src[y * w + min(w - 1, max(0, x))]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / (2 * r + 1)
                acc += src[y * w + min(w - 1, x + r + 1)] - src[y * w + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[min(h - 1, max(0, y)) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / (2 * r + 1)
                acc += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
            }
        }
        return out
    }
}
