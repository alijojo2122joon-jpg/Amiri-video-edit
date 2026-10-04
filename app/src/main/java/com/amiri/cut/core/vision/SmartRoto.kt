package com.amiri.cut.core.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smart roto brush: strokes snap to the object's real edges (colour models + graph cut),
 * edges are refined into a soft matte that follows hair and fur (guided filter), and
 * masks are carried to the next frames by motion estimation + re-segmentation in a
 * narrow band around the moved edge (so the outline keeps hugging the subject even as
 * it turns, bends, or the background changes).
 *
 * All images here are at mask resolution; heavy work runs at a reduced work size.
 */
object SmartRoto {
    /** Longest side of the graph-cut work image. */
    var workMax = 320

    // ───────────────────────── helpers ─────────────────────────

    fun downscale(img: Segment.Image, dw: Int, dh: Int): Segment.Image {
        if (dw == img.w && dh == img.h) return img
        val out = FloatArray(dw * dh * 3)
        val sx = img.w.toFloat() / dw; val sy = img.h.toFloat() / dh
        for (y in 0 until dh) for (x in 0 until dw) {
            val x0 = (x * sx).toInt(); val x1 = max(x0 + 1, ((x + 1) * sx).toInt()).coerceAtMost(img.w)
            val y0 = (y * sy).toInt(); val y1 = max(y0 + 1, ((y + 1) * sy).toInt()).coerceAtMost(img.h)
            var r = 0f; var g = 0f; var b = 0f; var c = 0
            for (yy in y0 until y1) for (xx in x0 until x1) { val p = (yy * img.w + xx) * 3; r += img.rgb[p]; g += img.rgb[p + 1]; b += img.rgb[p + 2]; c++ }
            val o = (y * dw + x) * 3; out[o] = r / c; out[o + 1] = g / c; out[o + 2] = b / c
        }
        return Segment.Image(dw, dh, out)
    }

    /** Bilinear resample of a float map. */
    fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = fx - x0
                val a = src[y0 * sw + x0] + (src[y0 * sw + x1] - src[y0 * sw + x0]) * tx
                val b = src[y1 * sw + x0] + (src[y1 * sw + x1] - src[y1 * sw + x0]) * tx
                out[y * dw + x] = a + (b - a) * ty
            }
        }
        return out
    }

    fun resampleBool(src: BooleanArray, sw: Int, sh: Int, dw: Int, dh: Int): BooleanArray {
        val f = resample(FloatArray(src.size) { if (src[it]) 1f else 0f }, sw, sh, dw, dh)
        return BooleanArray(f.size) { f[it] > 0.5f }
    }

    private fun workSize(w: Int, h: Int): Pair<Int, Int> {
        val s = min(1f, workMax.toFloat() / max(w, h))
        return max(8, (w * s).roundToInt()) to max(8, (h * s).roundToInt())
    }

    /**
     * Turns a hard object mask (at mask size) into the final soft matte: a guided filter
     * restricted to a band around the boundary, so the inside stays solid and the
     * outside stays clean while the edge follows real image detail.
     */
    fun matte(img: Segment.Image, hard: BooleanArray, softness: Float = 0.5f): FloatArray {
        val w = img.w; val h = img.h
        val r = max(2, (max(w, h) * (0.004f + 0.01f * softness)).roundToInt())
        val eps = 1e-4f + 4e-3f * softness * softness
        val inp = FloatArray(w * h) { if (hard[it]) 1f else 0f }
        val gf = Segment.guidedFilter(img.luma(), inp, w, h, r, eps)
        val inner = Segment.erode(hard, w, h, r)
        val outer = Segment.dilate(hard, w, h, r)
        return FloatArray(w * h) {
            when {
                inner[it] -> 1f
                !outer[it] -> 0f
                else -> {
                    // Crisp up the guided result a little so edges don't look smeared.
                    val v = (gf[it] - 0.5f) * (1.25f + 0.5f * (1 - softness)) + 0.5f
                    v.coerceIn(0f, 1f)
                }
            }
        }
    }

    // ───────────────────────── strokes ─────────────────────────

    /**
     * Applies one smart-brush stroke.
     * @param img the frame (mask size)
     * @param prev current matte (0..1, mask size) or null
     * @param stroke stroke pixels (mask size)
     * @param add true = add object, false = remove
     * @param prior optional AI person confidence (mask size, 0..1)
     * @return the new matte
     */
    fun stroke(
        img: Segment.Image, prev: FloatArray?, stroke: BooleanArray, add: Boolean,
        prior: FloatArray? = null, softness: Float = 0.5f,
    ): FloatArray {
        val W = img.w; val H = img.h
        val (w, h) = workSize(W, H)
        val small = downscale(img, w, h)
        val st = resampleBool(stroke, W, H, w, h).let { s -> if (s.none { it }) dilateOne(resampleF(stroke, W, H, w, h), w, h) else s }
        val pv = prev?.let { resample(it, W, H, w, h) }
        val pm = BooleanArray(w * h) { (pv?.get(it) ?: 0f) > 0.5f }
        val pr = prior?.let { resample(it, W, H, w, h) }
        val n = w * h
        val seeds = ByteArray(n)
        val maxDim = max(w, h)
        val band = max(2, maxDim / 100)

        val cut: BooleanArray
        if (add) {
            // Object seeds: the stroke + the solid inside of what's already selected.
            val inside = Segment.erode(pm, w, h, band)
            for (p in 0 until n) if (st[p] || inside[p]) seeds[p] = Segment.FG
            // Background seeds: the frame border (unless the object touches it) and anything
            // far from the stroke / selection, so a stroke grows into its object only.
            val fgAny = BooleanArray(n) { st[it] || pm[it] }
            val near = Segment.dilate(fgAny, w, h, (maxDim * 0.30f).roundToInt())
            val touch = Segment.dilate(fgAny, w, h, band * 2)
            for (p in 0 until n) {
                if (seeds[p] == Segment.FG) continue
                val x = p % w; val y = p / w
                val border = x < band || y < band || x >= w - band || y >= h - band
                if (!near[p] || (border && !touch[p])) seeds[p] = Segment.BG
            }
            val raw = Segment.cut(small, seeds, pr, priorWeight = 1.5f)
            cut = BooleanArray(n) { raw[it] || pm[it] }
        } else {
            // Remove: the stroke is background; selected parts away from the stroke stay.
            val sd = Segment.dilate(st, w, h, (maxDim * 0.12f).roundToInt())
            for (p in 0 until n) {
                seeds[p] = when {
                    st[p] -> Segment.BG
                    !pm[p] -> Segment.BG
                    !sd[p] -> Segment.FG
                    else -> Segment.UNKNOWN
                }
            }
            val raw = Segment.cut(small, seeds, pr, priorWeight = 1.5f)
            cut = BooleanArray(n) { raw[it] && pm[it] }
        }
        val full = resampleBool(cut, w, h, W, H)
        // Keep full-res detail of what was there before, away from the stroke's influence.
        val m = matte(img, full, softness)
        if (prev == null) return m
        val changed = resampleBool(BooleanArray(n) { cut[it] != pm[it] }, w, h, W, H).let { Segment.dilate(it, W, H, 3) }
        return FloatArray(W * H) { if (changed[it]) (if (add) max(m[it], prev[it]) else min(m[it], prev[it])) else prev[it] }
    }

    private fun resampleF(b: BooleanArray, sw: Int, sh: Int, dw: Int, dh: Int) = resample(FloatArray(b.size) { if (b[it]) 1f else 0f }, sw, sh, dw, dh)
    private fun dilateOne(f: FloatArray, w: Int, h: Int): BooleanArray {
        // A thin stroke can vanish when downscaled: keep any pixel it touched.
        return BooleanArray(w * h) { f[it] > 0.05f }
    }

    /** Snaps a rough mask (e.g. the AI person mask) to real edges. */
    fun refine(img: Segment.Image, rough: FloatArray, softness: Float = 0.5f, bandFrac: Float = 0.025f): FloatArray {
        val W = img.w; val H = img.h
        val (w, h) = workSize(W, H)
        val small = downscale(img, w, h)
        val r = resample(rough, W, H, w, h)
        val m = BooleanArray(w * h) { r[it] > 0.5f }
        if (m.none { it }) return FloatArray(W * H)
        val band = max(2, (max(w, h) * bandFrac).roundToInt())
        val inner = Segment.erode(m, w, h, band); val outer = Segment.dilate(m, w, h, band)
        val seeds = ByteArray(w * h) { if (inner[it]) Segment.FG else if (!outer[it]) Segment.BG else Segment.UNKNOWN }
        val cut = Segment.cut(small, seeds, r, priorWeight = 1f)
        return matte(img, resampleBool(cut, w, h, W, H), softness)
    }

    // ───────────────────────── propagation ─────────────────────────

    /** Similarity motion x' = s·R·(x − c) + c + t (work pixels). */
    class Motion(val cx: Float, val cy: Float, val s: Float, val a: Float, val tx: Float, val ty: Float) {
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx; val dy = y - cy; val c = cos(a) * s; val sn = sin(a) * s
            return (c * dx - sn * dy + cx + tx) to (sn * dx + c * dy + cy + ty)
        }
        fun inverse(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx - tx; val dy = y - cy - ty; val c = cos(-a) / s; val sn = sin(-a) / s
            return (c * dx - sn * dy + cx) to (sn * dx + c * dy + cy)
        }
    }

    /** State carried from frame to frame. */
    class Tracker internal constructor(
        internal var img: Segment.Image, internal var alpha: FloatArray,
        internal val fg: Gmm, internal val bg: Gmm,
    ) {
        /** Accumulated area of the key mask, used to stop drift from exploding. */
        internal val keyArea = alpha.count { it > 0.5f }.coerceAtLeast(1)
    }

    fun startTracker(img: Segment.Image, alpha: FloatArray): Tracker {
        val (w, h) = workSize(img.w, img.h)
        val small = downscale(img, w, h)
        val a = resample(alpha, img.w, img.h, w, h)
        val fg = Gmm(); val bg = Gmm()
        fun fit(model: Gmm, want: Boolean) {
            val buf = FloatArray(w * h * 3); var c = 0
            for (p in 0 until w * h) if ((a[p] > 0.5f) == want) { buf[c * 3] = small.rgb[p * 3]; buf[c * 3 + 1] = small.rgb[p * 3 + 1]; buf[c * 3 + 2] = small.rgb[p * 3 + 2]; c++ }
            model.fit(buf, c)
        }
        fit(fg, true); fit(bg, false)
        return Tracker(img, alpha.copyOf(), fg, bg)
    }

    /**
     * Moves the mask onto [next] (same size as the key frame). Returns the new matte.
     * [softness] like [matte].
     */
    fun track(t: Tracker, next: Segment.Image, softness: Float = 0.5f): FloatArray {
        val W = next.w; val H = next.h
        val (w, h) = workSize(W, H)
        val a = downscale(t.img, w, h).luma(); val b = downscale(next, w, h).luma()
        val prevA = resample(t.alpha, W, H, w, h)
        val pm = BooleanArray(w * h) { prevA[it] > 0.5f }
        if (pm.none { it }) { t.img = next; t.alpha = FloatArray(W * H); return t.alpha }

        // 1) Motion of the subject: global similarity + smooth local residual field.
        val field = motionField(a, b, w, h, pm)
        // 2) Warp the previous matte forward (backward mapping via the field at the target).
        val warped = warp(prevA, w, h, field)
        val wm = BooleanArray(w * h) { warped[it] > 0.5f }
        // 3) Re-segment in a narrow band around the moved edge.
        val band = max(3, max(w, h) / 45)
        val inner = Segment.erode(wm, w, h, band); val outer = Segment.dilate(wm, w, h, band)
        val seeds = ByteArray(w * h) { if (inner[it]) Segment.FG else if (!outer[it]) Segment.BG else Segment.UNKNOWN }
        val small = downscale(next, w, h)
        val prior = Segment.blur(warped, w, h, max(1, band / 2))
        val cut = Segment.cut(small, seeds, prior, priorWeight = 1.2f, iterations = 2, fgModel = t.fg, bgModel = t.bg)
        // Sanity: if the graph cut wildly disagrees with the motion estimate, trust the motion.
        var area = 0; var areaW = 0
        for (p in cut.indices) { if (cut[p]) area++; if (wm[p]) areaW++ }
        val use = if (areaW > 0 && abs(area - areaW).toFloat() / areaW > 0.35f) wm else cut
        val full = resampleBool(use, w, h, W, H)
        var m = matte(next, full, softness)
        // 4) Temporal smoothing at the edge only (kills flicker without lag in the body).
        val warpedFull = resample(warped, w, h, W, H)
        m = FloatArray(W * H) { i -> val v = m[i]; if (v > 0.02f && v < 0.98f) v * 0.8f + warpedFull[i] * 0.2f else v }
        t.img = next; t.alpha = m
        return m
    }

    /** Displacement field (dx,dy per pixel, work size) from frame a to b for the masked subject. */
    internal fun motionField(a: FloatArray, b: FloatArray, w: Int, h: Int, mask: BooleanArray): Pair<FloatArray, FloatArray> {
        val bounds = Segment.bounds(mask, w, h)!!
        val ring = Segment.dilate(mask, w, h, 3)
        // Global translation first (coarse search on the whole subject).
        val pts = ArrayList<Int>()
        for (y in 0 until h step 2) for (x in 0 until w step 2) if (mask[y * w + x]) pts += y * w + x
        val R = max(6, max(w, h) / 12)
        var gx = 0; var gy = 0; var best = Float.MAX_VALUE
        fun sad(list: List<Int>, mx: Int, my: Int): Float {
            var s = 0f; var c = 0
            for (p in list) { val x = p % w + mx; val y = p / w + my; if (x < 0 || y < 0 || x >= w || y >= h) continue; s += abs(a[p] - b[y * w + x]); c++ }
            return if (c < list.size / 3) Float.MAX_VALUE else s / c
        }
        val sub = if (pts.size > 1500) pts.filterIndexed { i, _ -> i % (pts.size / 1500 + 1) == 0 } else pts
        for (my in -R..R step 2) for (mx in -R..R step 2) { val c = sad(sub, mx, my); if (c < best) { best = c; gx = mx; gy = my } }
        for (my in gy - 2..gy + 2) for (mx in gx - 2..gx + 2) { val c = sad(sub, mx, my); if (c < best) { best = c; gx = mx; gy = my } }

        // Local patch matches around the global motion.
        val ps = max(4, max(w, h) / 40)          // patch half-size
        val stepP = ps
        val lr = max(3, max(w, h) / 50)          // local search radius
        val px = ArrayList<Float>(); val py = ArrayList<Float>(); val pdx = ArrayList<Float>(); val pdy = ArrayList<Float>(); val pw = ArrayList<Float>()
        var y = bounds[1]
        while (y <= bounds[3]) {
            var x = bounds[0]
            while (x <= bounds[2]) {
                if (ring[y * w + x]) {
                    // Patch texture (no texture → unreliable).
                    var mean = 0f; var cnt = 0
                    for (yy in max(0, y - ps)..min(h - 1, y + ps)) for (xx in max(0, x - ps)..min(w - 1, x + ps)) { mean += a[yy * w + xx]; cnt++ }
                    mean /= cnt
                    var vr = 0f
                    for (yy in max(0, y - ps)..min(h - 1, y + ps)) for (xx in max(0, x - ps)..min(w - 1, x + ps)) { val d = a[yy * w + xx] - mean; vr += d * d }
                    vr /= cnt
                    if (vr > 1e-4f) {
                        var bx = gx; var by = gy; var bc = Float.MAX_VALUE; var second = Float.MAX_VALUE
                        for (my in gy - lr..gy + lr) for (mx in gx - lr..gx + lr) {
                            var s = 0f; var c = 0
                            for (yy in max(0, y - ps)..min(h - 1, y + ps) step 2) for (xx in max(0, x - ps)..min(w - 1, x + ps) step 2) {
                                val tx = xx + mx; val ty = yy + my
                                if (tx < 0 || ty < 0 || tx >= w || ty >= h) { s += 0.5f; c++; continue }
                                s += abs(a[yy * w + xx] - b[ty * w + tx]); c++
                            }
                            s /= c
                            if (s < bc) { second = bc; bc = s; bx = mx; by = my } else if (s < second) second = s
                        }
                        val conf = (sqrt(vr) / (bc + 0.01f)).coerceAtMost(50f)
                        px += x.toFloat(); py += y.toFloat(); pdx += bx.toFloat(); pdy += by.toFloat(); pw += conf
                    }
                }
                x += stepP
            }
            y += stepP
        }
        val fx = FloatArray(w * h) { gx.toFloat() }; val fy = FloatArray(w * h) { gy.toFloat() }
        if (px.size < 3) return fx to fy
        // Robust similarity fit (iteratively reweighted), then smooth residuals.
        val sim = fitSimilarity(px, py, pdx, pdy, pw)
        val res = FloatArray(px.size * 2)
        for (i in px.indices) {
            val (mx, my) = sim.map(px[i], py[i])
            res[i * 2] = px[i] + pdx[i] - mx; res[i * 2 + 1] = py[i] + pdy[i] - my
        }
        // Field = similarity + Gaussian-weighted residual (sigma ~ 2 patch steps), computed coarse then upsampled.
        val cs = 4
        val cw = (w + cs - 1) / cs; val ch = (h + cs - 1) / cs
        val rx = FloatArray(cw * ch); val ry = FloatArray(cw * ch)
        val sig2 = 2f * (stepP * 2f) * (stepP * 2f)
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val X = cx * cs.toFloat(); val Y = cy * cs.toFloat()
            var sx = 0f; var sy = 0f; var sw = 0.15f // pull toward zero residual
            for (i in px.indices) {
                val dx = px[i] - X; val dy = py[i] - Y; val d2 = dx * dx + dy * dy
                if (d2 > sig2 * 4) continue
                val wt = exp(-d2 / sig2) * min(pw[i], 10f)
                sx += res[i * 2] * wt; sy += res[i * 2 + 1] * wt; sw += wt
            }
            rx[cy * cw + cx] = sx / sw; ry[cy * cw + cx] = sy / sw
        }
        val frx = resample(rx, cw, ch, w, h); val fry = resample(ry, cw, ch, w, h)
        // Store as backward map at target pixel: source = target − displacement(source ≈ target).
        for (yy in 0 until h) for (xx in 0 until w) {
            val (sx, sy) = sim.inverse(xx.toFloat(), yy.toFloat())
            val i = yy * w + xx
            fx[i] = xx - sx + frx[i]; fy[i] = yy - sy + fry[i]
        }
        return fx to fy
    }

    internal fun fitSimilarity(px: List<Float>, py: List<Float>, dx: List<Float>, dy: List<Float>, wt: List<Float>): Motion {
        val n = px.size
        var cx = 0f; var cy = 0f; var sw = 0f
        for (i in 0 until n) { cx += px[i] * wt[i]; cy += py[i] * wt[i]; sw += wt[i] }
        cx /= sw; cy /= sw
        val w = wt.toFloatArray()
        var motion = Motion(cx, cy, 1f, 0f, 0f, 0f)
        repeat(4) {
            // Solve for a = s cosθ, b = s sinθ, tx, ty with weighted least squares on centered coords.
            var sw2 = 0.0; var mxs = 0.0; var mys = 0.0; var mxd = 0.0; var myd = 0.0
            for (i in 0 until n) { sw2 += w[i]; mxs += w[i] * (px[i] - cx); mys += w[i] * (py[i] - cy); mxd += w[i] * (px[i] + dx[i] - cx); myd += w[i] * (py[i] + dy[i] - cy) }
            if (sw2 <= 0) return motion
            mxs /= sw2; mys /= sw2; mxd /= sw2; myd /= sw2
            var sxx = 0.0; var num_a = 0.0; var num_b = 0.0
            for (i in 0 until n) {
                val ux = px[i] - cx - mxs; val uy = py[i] - cy - mys
                val vx = px[i] + dx[i] - cx - mxd; val vy = py[i] + dy[i] - cy - myd
                sxx += w[i] * (ux * ux + uy * uy); num_a += w[i] * (ux * vx + uy * vy); num_b += w[i] * (ux * vy - uy * vx)
            }
            if (sxx <= 1e-9) return motion
            var A = num_a / sxx; var B = num_b / sxx
            var s = sqrt(A * A + B * B)
            // Limit per-frame scale and rotation changes (they're small between frames).
            s = s.coerceIn(0.9, 1.1)
            val ang = atan2(B, A).coerceIn(-0.15, 0.15)
            A = s * cos(ang); B = s * sin(ang)
            val tx = mxd - (A * mxs - B * mys); val ty = myd - (B * mxs + A * mys)
            motion = Motion(cx, cy, s.toFloat(), ang.toFloat(), tx.toFloat(), ty.toFloat())
            // Reweight by residual (Cauchy).
            for (i in 0 until n) {
                val (mx, my) = motion.map(px[i], py[i])
                val ex = px[i] + dx[i] - mx; val ey = py[i] + dy[i] - my
                w[i] = wt[i] / (1f + (ex * ex + ey * ey) / 4f)
            }
        }
        return motion
    }

    /** Backward warp: out(x) = src(x − field(x)). */
    internal fun warp(src: FloatArray, w: Int, h: Int, field: Pair<FloatArray, FloatArray>): FloatArray {
        val (fx, fy) = field
        return FloatArray(w * h) { i ->
            val x = i % w - fx[i]; val y = i / w - fy[i]
            if (x < -0.5f || y < -0.5f || x > w - 0.5f || y > h - 0.5f) 0f else {
                val cx = x.coerceIn(0f, w - 1f); val cy = y.coerceIn(0f, h - 1f)
                val x0 = floor(cx).toInt(); val y0 = floor(cy).toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
                val tx = cx - x0; val ty = cy - y0
                val a = src[y0 * w + x0] + (src[y0 * w + x1] - src[y0 * w + x0]) * tx
                val b = src[y1 * w + x0] + (src[y1 * w + x1] - src[y1 * w + x0]) * tx
                a + (b - a) * ty
            }
        }
    }
}
