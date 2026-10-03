package com.amiri.cut.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edge-aware matting for the soft (hair) roto brush and Refine Edge.
 *
 * Inside an "unknown" band, each pixel's opacity is estimated from the colors of the
 * nearby known foreground (inside the current mask) and known background (outside it):
 * alpha = projection of the pixel color onto the line between the local mean
 * background and foreground colors. Fine strands of hair that differ in color from the
 * background therefore keep partial opacity instead of being cut by a hard brush edge.
 */
object RotoMatting {

    /**
     * @param image frame at mask resolution
     * @param base current mask (alpha), or null for empty
     * @param band unknown region (alpha) where matting is computed
     * @param radius search window radius in pixels
     * @param contrast >1 makes the matte crisper, <1 softer
     */
    fun refine(image: Bitmap, base: Bitmap?, band: Bitmap, radius: Int, contrast: Float): Bitmap {
        val w = band.width
        val h = band.height
        val img = if (image.width != w || image.height != h) Bitmap.createScaledBitmap(image, w, h, true) else image
        val px = IntArray(w * h).also { img.getPixels(it, 0, w, 0, 0, w, h) }
        val a = FloatArray(w * h)
        if (base != null) {
            val b = if (base.width != w || base.height != h) Bitmap.createScaledBitmap(base, w, h, true) else base
            val bp = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
            for (i in bp.indices) a[i] = Color.alpha(bp[i]) / 255f
        }
        val bandA = FloatArray(w * h)
        IntArray(w * h).also { band.getPixels(it, 0, w, 0, 0, w, h) }.forEachIndexed { i, c -> bandA[i] = Color.alpha(c) / 255f }

        val r = radius.coerceIn(2, 40)
        val step = max(1, r / 7)
        val sigma2 = 2f * (r * 0.6f) * (r * 0.6f)
        val out = a.copyOf()
        val rr = FloatArray(w * h); val gg = FloatArray(w * h); val bb = FloatArray(w * h)
        for (i in px.indices) { rr[i] = Color.red(px[i]) / 255f; gg[i] = Color.green(px[i]) / 255f; bb[i] = Color.blue(px[i]) / 255f }

        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val bw = bandA[i]
            if (bw < 0.04f) continue
            var fr = 0f; var fg = 0f; var fb = 0f; var fw = 0f
            var br = 0f; var bg = 0f; var bbw = 0f; var bwt = 0f
            var dy = -r
            while (dy <= r) {
                val yy = y + dy
                if (yy in 0 until h) {
                    var dx = -r
                    while (dx <= r) {
                        val xx = x + dx
                        if (xx in 0 until w) {
                            val j = yy * w + xx
                            if (bandA[j] < 0.3f) {
                                val sw = exp(-(dx * dx + dy * dy) / sigma2)
                                if (a[j] > 0.9f) { fr += rr[j] * sw; fg += gg[j] * sw; fb += bb[j] * sw; fw += sw }
                                else if (a[j] < 0.1f) { br += rr[j] * sw; bg += gg[j] * sw; bbw += bb[j] * sw; bwt += sw }
                            }
                        }
                        dx += step
                    }
                }
                dy += step
            }
            if (fw < 1e-3f || bwt < 1e-3f) continue
            fr /= fw; fg /= fw; fb /= fw
            br /= bwt; bg /= bwt; bbw /= bwt
            val dr = fr - br; val dg = fg - bg; val db = fb - bbw
            val den = dr * dr + dg * dg + db * db
            if (den < 1e-4f) continue
            var al = ((rr[i] - br) * dr + (gg[i] - bg) * dg + (bb[i] - bbw) * db) / den
            al = ((al - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
            out[i] = a[i] * (1f - bw) + al * bw
        }
        // Light 3×3 smoothing inside the band only.
        val sm = out.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (bandA[i] < 0.04f) continue
            var s = 0f
            for (k in -1..1) for (l in -1..1) s += out[(y + k) * w + x + l]
            sm[i] = s / 9f
        }
        val res = IntArray(w * h) { i -> Color.argb((sm[i] * 255).roundToInt().coerceIn(0, 255), 255, 255, 255) }
        return Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The soft edge zone of a mask (for Refine Edge): where a blurred copy is neither 0 nor 1. */
    fun edgeBand(mask: Bitmap, radius: Int): Bitmap {
        val w = mask.width
        val h = mask.height
        val p = IntArray(w * h).also { mask.getPixels(it, 0, w, 0, 0, w, h) }
        val a = FloatArray(w * h) { Color.alpha(p[it]) / 255f }
        val r = radius.coerceIn(1, 30)
        // Box blur (two passes).
        val t = FloatArray(w * h)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += a[y * w + min(w - 1, max(0, x))]
            for (x in 0 until w) {
                t[y * w + x] = acc / (2 * r + 1)
                acc += a[y * w + min(w - 1, x + r + 1)] - a[y * w + max(0, x - r)]
            }
        }
        val b = FloatArray(w * h)
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += t[min(h - 1, max(0, y)) * w + x]
            for (y in 0 until h) {
                b[y * w + x] = acc / (2 * r + 1)
                acc += t[min(h - 1, y + r + 1) * w + x] - t[max(0, y - r) * w + x]
            }
        }
        val out = IntArray(w * h) { i ->
            val v = b[i]
            val band = if (v > 0.02f && v < 0.98f) 1f else 0f
            Color.argb((band * 255).roundToInt(), 255, 255, 255)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
