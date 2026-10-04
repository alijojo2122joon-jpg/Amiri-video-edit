package com.amiri.cut.core.vision

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Colour model: a K-component Gaussian mixture in RGB (0..1), full covariance. */
class Gmm(private val k: Int = 5) {
    private val wgt = DoubleArray(k)
    private val mean = Array(k) { DoubleArray(3) }
    private val inv = Array(k) { DoubleArray(9) }
    private val logNorm = DoubleArray(k)
    var valid = false
        private set

    /** Fits to samples (r,g,b triples) with k-means + covariance. */
    fun fit(s: FloatArray, count: Int) {
        if (count < k * 4) { valid = false; return }
        val step = max(1, count / 6000)
        val idx = (0 until count step step).toList()
        // k-means++-lite init: spread by luminance order
        val sorted = idx.sortedBy { s[it * 3] + s[it * 3 + 1] + s[it * 3 + 2] }
        for (c in 0 until k) { val i = sorted[(sorted.size - 1) * (2 * c + 1) / (2 * k)]; for (j in 0..2) mean[c][j] = s[i * 3 + j].toDouble() }
        val assign = IntArray(idx.size)
        repeat(8) {
            for ((n, i) in idx.withIndex()) {
                var best = 0; var bd = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d0 = s[i * 3] - mean[c][0]; val d1 = s[i * 3 + 1] - mean[c][1]; val d2 = s[i * 3 + 2] - mean[c][2]
                    val dd = d0 * d0 + d1 * d1 + d2 * d2
                    if (dd < bd) { bd = dd; best = c }
                }
                assign[n] = best
            }
            val sum = Array(k) { DoubleArray(3) }; val cnt = IntArray(k)
            for ((n, i) in idx.withIndex()) { val c = assign[n]; cnt[c]++; for (j in 0..2) sum[c][j] += s[i * 3 + j] }
            for (c in 0 until k) if (cnt[c] > 0) for (j in 0..2) mean[c][j] = sum[c][j] / cnt[c]
        }
        val cov = Array(k) { DoubleArray(9) }; val cnt = IntArray(k)
        for ((n, i) in idx.withIndex()) {
            val c = assign[n]; cnt[c]++
            for (a in 0..2) for (b in 0..2) cov[c][a * 3 + b] += (s[i * 3 + a] - mean[c][a]) * (s[i * 3 + b] - mean[c][b])
        }
        for (c in 0 until k) {
            wgt[c] = cnt[c].toDouble() / idx.size
            val m = cov[c]
            for (a in 0..8) m[a] = if (cnt[c] > 0) m[a] / cnt[c] else 0.0
            for (a in 0..2) m[a * 4] += 1e-4 // regularise
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            val dd = max(det, 1e-12)
            val iv = inv[c]
            iv[0] = (m[4] * m[8] - m[5] * m[7]) / dd; iv[1] = (m[2] * m[7] - m[1] * m[8]) / dd; iv[2] = (m[1] * m[5] - m[2] * m[4]) / dd
            iv[3] = (m[5] * m[6] - m[3] * m[8]) / dd; iv[4] = (m[0] * m[8] - m[2] * m[6]) / dd; iv[5] = (m[2] * m[3] - m[0] * m[5]) / dd
            iv[6] = (m[3] * m[7] - m[4] * m[6]) / dd; iv[7] = (m[1] * m[6] - m[0] * m[7]) / dd; iv[8] = (m[0] * m[4] - m[1] * m[3]) / dd
            logNorm[c] = ln(max(wgt[c], 1e-9)) - 0.5 * ln(dd) - 1.5 * ln(2 * PI)
        }
        valid = true
    }

    /** −log likelihood of a colour. */
    fun nll(r: Float, g: Float, b: Float): Float {
        if (!valid) return 4f
        var p = 0.0
        for (c in 0 until k) {
            if (wgt[c] <= 0.0) continue
            val d0 = r - mean[c][0]; val d1 = g - mean[c][1]; val d2 = b - mean[c][2]
            val iv = inv[c]
            val q = d0 * (iv[0] * d0 + iv[1] * d1 + iv[2] * d2) + d1 * (iv[3] * d0 + iv[4] * d1 + iv[5] * d2) + d2 * (iv[6] * d0 + iv[7] * d1 + iv[8] * d2)
            p += exp(logNorm[c] - 0.5 * q)
        }
        return (-ln(max(p, 1e-30))).toFloat().coerceIn(-40f, 60f)
    }

    fun copyFrom(o: Gmm) {
        for (c in 0 until k) { wgt[c] = o.wgt[c]; logNorm[c] = o.logNorm[c]; o.mean[c].copyInto(mean[c]); o.inv[c].copyInto(inv[c]) }
        valid = o.valid
    }
}

/**
 * Interactive object segmentation (GrabCut-style): colour mixtures for object and
 * background, contrast-sensitive edges, hard seeds from strokes, an optional prior
 * (AI person confidence or the previous frame's mask), solved with a graph cut.
 */
object Segment {
    /** Seed labels. */
    const val UNKNOWN: Byte = 0
    const val FG: Byte = 1
    const val BG: Byte = 2

    class Image(val w: Int, val h: Int, val rgb: FloatArray) {
        companion object {
            fun fromArgb(px: IntArray, w: Int, h: Int) = Image(w, h, FloatArray(w * h * 3) { i ->
                val c = px[i / 3]
                when (i % 3) { 0 -> ((c shr 16) and 255) / 255f; 1 -> ((c shr 8) and 255) / 255f; else -> (c and 255) / 255f }
            })
        }
        fun luma(): FloatArray = FloatArray(w * h) { 0.299f * rgb[it * 3] + 0.587f * rgb[it * 3 + 1] + 0.114f * rgb[it * 3 + 2] }
    }

    /**
     * @param seeds FG / BG / UNKNOWN per pixel
     * @param prior optional object probability 0..1 per pixel (weight [priorWeight])
     * @param fgModel / bgModel optional fixed colour models (else learned from seeds)
     * @return object mask (true = object)
     */
    fun cut(
        img: Image, seeds: ByteArray, prior: FloatArray? = null, priorWeight: Float = 2f,
        iterations: Int = 2, fgModel: Gmm? = null, bgModel: Gmm? = null, gamma: Float = 50f,
    ): BooleanArray {
        val w = img.w; val h = img.h; val n = w * h
        val rgb = img.rgb
        // Contrast parameter β from the mean squared colour difference.
        var acc = 0.0; var cnt = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            if (x + 1 < w) { acc += d2(rgb, p, p + 1); cnt++ }
            if (y + 1 < h) { acc += d2(rgb, p, p + w); cnt++ }
        }
        val beta = 1.0 / (2.0 * max(acc / max(cnt, 1), 1e-6))
        var label = BooleanArray(n) { seeds[it] == FG || (seeds[it] == UNKNOWN && (prior?.get(it) ?: 0f) > 0.5f) }
        val fg = Gmm(); val bg = Gmm()
        val buf = FloatArray(n * 3)
        for (iter in 0 until iterations) {
            fun fitTo(model: Gmm, want: Boolean) {
                var c = 0
                for (p in 0 until n) {
                    val isSeedFg = seeds[p] == FG; val isSeedBg = seeds[p] == BG
                    val take = if (want) (isSeedFg || (iter > 0 && label[p] && !isSeedBg)) else (isSeedBg || (iter > 0 && !label[p] && !isSeedFg))
                    if (take) { buf[c * 3] = rgb[p * 3]; buf[c * 3 + 1] = rgb[p * 3 + 1]; buf[c * 3 + 2] = rgb[p * 3 + 2]; c++ }
                }
                model.fit(buf, c)
            }
            if (fgModel != null && iter == 0) fg.copyFrom(fgModel) else fitTo(fg, true)
            if (bgModel != null && iter == 0) bg.copyFrom(bgModel) else fitTo(bg, false)
            if (!fg.valid || !bg.valid) {
                // Not enough seeds for colour: fall back to prior / seeds.
                if (!fg.valid && fgModel != null) fg.copyFrom(fgModel)
                if (!bg.valid && bgModel != null) bg.copyFrom(bgModel)
            }
            val g = GraphCut(w, h)
            val inf = 1e6f
            for (p in 0 until n) {
                when (seeds[p]) {
                    FG -> g.tr[p] = inf
                    BG -> g.tr[p] = -inf
                    else -> {
                        val r = rgb[p * 3]; val gg = rgb[p * 3 + 1]; val b = rgb[p * 3 + 2]
                        var dFg = fg.nll(r, gg, b); var dBg = bg.nll(r, gg, b)
                        if (prior != null) {
                            val pr = prior[p].coerceIn(0.02f, 0.98f)
                            dFg += -ln(pr) * priorWeight; dBg += -ln(1f - pr) * priorWeight
                        }
                        // source edge = cost of background, sink edge = cost of object
                        g.tr[p] = dBg - dFg
                    }
                }
            }
            for (y in 0 until h) for (x in 0 until w) {
                val p = y * w + x
                if (x + 1 < w) { val c = (gamma * exp(-beta * d2(rgb, p, p + 1))).toFloat(); g.cap[p * 4] = c; g.cap[(p + 1) * 4 + 2] = c }
                if (y + 1 < h) { val c = (gamma * exp(-beta * d2(rgb, p, p + w))).toFloat(); g.cap[p * 4 + 1] = c; g.cap[(p + w) * 4 + 3] = c }
            }
            g.maxflow()
            label = BooleanArray(n) { g.isSource(it) }
        }
        return keepConnectedToSeeds(label, seeds, w, h)
    }

    private fun d2(rgb: FloatArray, a: Int, b: Int): Double {
        val r = rgb[a * 3] - rgb[b * 3]; val g = rgb[a * 3 + 1] - rgb[b * 3 + 1]; val bb = rgb[a * 3 + 2] - rgb[b * 3 + 2]
        return (r * r + g * g + bb * bb).toDouble()
    }

    /** Removes islands that don't touch a foreground seed (stray specks in the background). */
    fun keepConnectedToSeeds(mask: BooleanArray, seeds: ByteArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val q = IntArray(mask.size); var qh = 0; var qt = 0
        for (p in mask.indices) if (mask[p] && seeds[p] == FG) { out[p] = true; q[qt++] = p }
        if (qt == 0) return mask
        while (qh < qt) {
            val p = q[qh++]; val x = p % w
            fun visit(r: Int) { if (mask[r] && !out[r]) { out[r] = true; q[qt++] = r } }
            if (x > 0) visit(p - 1); if (x + 1 < w) visit(p + 1); if (p >= w) visit(p - w); if (p + w < mask.size) visit(p + w)
        }
        // Also keep sizeable components not touching seeds (e.g. a second arm behind) — fill small holes instead.
        return fillHoles(out, w, h)
    }

    /** Fills background holes that are fully enclosed by the object. */
    fun fillHoles(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(mask.size)
        val q = IntArray(mask.size); var qh = 0; var qt = 0
        fun seed(p: Int) { if (!mask[p] && !outside[p]) { outside[p] = true; q[qt++] = p } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (qh < qt) {
            val p = q[qh++]; val x = p % w
            if (x > 0) seed(p - 1); if (x + 1 < w) seed(p + 1); if (p >= w) seed(p - w); if (p + w < mask.size) seed(p + w)
        }
        return BooleanArray(mask.size) { mask[it] || !outside[it] }
    }

    /** Distance-limited dilation (square) of a boolean mask by [r] pixels. */
    fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return m
        val tmp = BooleanArray(m.size); val out = BooleanArray(m.size)
        for (y in 0 until h) { var last = -100000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= r) tmp[y * w + x] = true }
            last = 100000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= r) tmp[y * w + x] = true } }
        for (x in 0 until w) { var last = -100000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= r) out[y * w + x] = true }
            last = 100000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= r) out[y * w + x] = true } }
        return out
    }

    fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(m.size) { !m[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(m.size) { !d[it] }
    }

    /**
     * Guided filter (He et al.): turns a hard mask into a soft matte whose edges follow the
     * image's real edges (hair, fur, motion blur). [guide] is luma 0..1.
     */
    fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        fun box(src: FloatArray): FloatArray {
            val tmp = FloatArray(src.size); val out = FloatArray(src.size)
            for (y in 0 until h) {
                var s = 0f
                for (x in -r..r) s += src[y * w + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = s / (2 * r + 1)
                    s += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                var s = 0f
                for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = s / (2 * r + 1)
                    s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
                }
            }
            return out
        }
        val n = w * h
        val mI = box(guide); val mP = box(input)
        val ip = FloatArray(n) { guide[it] * input[it] }; val ii = FloatArray(n) { guide[it] * guide[it] }
        val mIp = box(ip); val mII = box(ii)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val cov = mIp[i] - mI[i] * mP[i]; val v = mII[i] - mI[i] * mI[i]
            a[i] = cov / (v + eps); b[i] = mP[i] - a[i] * mI[i]
        }
        val ma = box(a); val mb = box(b)
        return FloatArray(n) { (ma[it] * guide[it] + mb[it]).coerceIn(0f, 1f) }
    }

    /** Box blur of a float map (radius r), used for soft priors. */
    fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dummy = FloatArray(src.size) { 0.5f }
        // A guided filter with a flat guide is a box filter twice; simpler: reuse with eps huge.
        return guidedFilter(dummy, src, w, h, r, 1e6f)
    }

    /** Smallest/largest x,y of a mask: [minX, minY, maxX, maxY] or null if empty. */
    fun bounds(m: BooleanArray, w: Int, h: Int): IntArray? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }

    @Suppress("unused") private fun keep() = sqrt(1.0)
}
