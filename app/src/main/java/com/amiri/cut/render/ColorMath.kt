package com.amiri.cut.render

import android.graphics.Bitmap
import com.amiri.cut.core.effects.ColorLooks
import com.amiri.cut.core.effects.EffectCatalog
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * CPU version of the Color Correction shader (same formulas), used to draw small look
 * previews in the UI. Clarity is approximated (no blur on the CPU).
 */
object ColorMath {
    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun hsv2rgb(h: Float, s: Float, v: Float): FloatArray {
        fun ch(k: Float): Float { val p = abs(((h + k) % 1f) * 6f - 3f); return v * (1f + s * ((p - 1f).coerceIn(0f, 1f) - 1f)) }
        return floatArrayOf(ch(1f), ch(2f / 3f), ch(1f / 3f))
    }

    private fun rgb2hsv(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        var h = when {
            d < 1e-6f -> 0f
            mx == r -> ((g - b) / d) / 6f
            mx == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        if (h < 0f) h += 1f
        return floatArrayOf(h, if (mx > 1e-6f) d / mx else 0f, mx)
    }

    /** Param values for a look at intensity 1 (defaults + look offsets, like the compositor). */
    fun lookParams(look: String?): (String) -> Float {
        val l = ColorLooks.look(look)
        return { id ->
            val def = EffectCatalog.COLOR.param(id)?.default ?: 0f
            val lv = l?.get(id)
            when {
                lv == null -> def
                id.endsWith("_hue") -> lv
                else -> def + lv
            }
        }
    }

    fun apply(src: Bitmap, v: (String) -> Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val ex = 2f.pow(v("exposure"))
        val temp = v("temperature"); val tint = v("tint")
        val bp = -v("blacks") * 0.12f; val wp = 1f - v("whites") * 0.2f
        val sh = v("shadows"); val hi = v("highlights"); val con = v("contrast"); val bri = v("brightness")
        fun chroma(x: Float, y: Float) = floatArrayOf(1.402f * y, -0.344f * x - 0.714f * y, 1.772f * x)
        val lc = chroma(v("lift_x"), v("lift_y")); val ll = v("lift_l")
        val gc = chroma(v("gamma_x"), v("gamma_y")); val gl = v("gamma_l")
        val kc = chroma(v("gain_x"), v("gain_y")); val kl = v("gain_l")
        val lift = FloatArray(3) { ll * 0.15f + lc[it] * 0.12f }
        val gam = FloatArray(3) { 1f + gl * 0.4f + gc[it] * 0.25f }
        val gain = FloatArray(3) { 1f + kl * 0.5f + kc[it] * 0.3f }
        val sat = v("saturation"); val vib = v("vibrance"); val fade = v("fade"); val dehaze = v("dehaze"); val clar = v("clarity")
        val shH = v("sh_hue"); val shS = v("sh_sat"); val hiH = v("hi_hue"); val hiS = v("hi_sat"); val bal = v("split_bal") * 0.3f
        val ts = hsv2rgb(shH, 1f, 1f).let { c -> val L = luma(c[0], c[1], c[2]); floatArrayOf(c[0] - L, c[1] - L, c[2] - L) }
        val th = hsv2rgb(hiH, 1f, 1f).let { c -> val L = luma(c[0], c[1], c[2]); floatArrayOf(c[0] - L, c[1] - L, c[2] - L) }
        val vig = v("vignette"); val vigF = v("vig_feather")
        val hsl = EffectCatalog.HSL_RANGES.map { r -> floatArrayOf(v("hsl_${r}_h"), v("hsl_${r}_s"), v("hsl_${r}_l")) }
        val anyHsl = hsl.any { it[0] != 0f || it[1] != 0f || it[2] != 0f }
        val aspect = w.toFloat() / h
        val c = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            c[0] = ((p shr 16) and 255) / 255f; c[1] = ((p shr 8) and 255) / 255f; c[2] = (p and 255) / 255f
            for (k in 0..2) c[k] *= ex
            c[0] *= 1f + 0.18f * temp; c[1] *= 1f + 0.04f * temp - 0.12f * tint; c[2] *= 1f - 0.18f * temp
            if (abs(clar) > 0.001f) { val L = luma(c[0], c[1], c[2]); val mid = 1f - ((L - 0.5f) * 2f).pow(2); for (k in 0..2) c[k] += (c[k] - L) * clar * 0.25f * mid + (L - 0.5f) * clar * 0.2f * mid }
            if (abs(dehaze) > 0.001f) {
                if (dehaze > 0f) { val kk = dehaze * 0.12f; for (k in 0..2) c[k] = (c[k] - kk) / (1f - kk * 1.6f); val L = luma(c[0], c[1], c[2]); for (k in 0..2) c[k] = L + (c[k] - L) * (1f + dehaze * 0.35f) }
                else { val hz = floatArrayOf(0.72f, 0.74f, 0.78f); for (k in 0..2) c[k] += (hz[k] - c[k]) * (-dehaze * 0.45f) }
            }
            for (k in 0..2) c[k] = (c[k] - bp) / max(wp - bp, 0.05f)
            var l = luma(c[0], c[1], c[2])
            val sw = sh * 0.3f * (1f - smooth(0f, 0.5f, l)); val hw = hi * 0.3f * smooth(0.5f, 1f, l)
            for (k in 0..2) { c[k] += sw + hw; c[k] = (c[k] - 0.5f) * (1f + con) + 0.5f + bri * 0.25f; c[k] *= gain[k]; c[k] += lift[k] * (1f - c[k]); c[k] = max(c[k], 0f).pow(1f / max(gam[k], 0.05f)) }
            if (anyHsl) {
                val hv = rgb2hsv(c[0].coerceIn(0f, 1f), c[1].coerceIn(0f, 1f), c[2].coerceIn(0f, 1f))
                val hue = hv[0] * 6f
                var dh = 0f; var ds = 0f; var dl = 0f
                for (i in 0 until 6) { var d = abs(hue - i); d = min(d, 6f - d); val wgt = max(0f, 1f - d); dh += wgt * hsl[i][0]; ds += wgt * hsl[i][1]; dl += wgt * hsl[i][2] }
                val s0 = hv[1]
                val rgb = hsv2rgb(((hv[0] + dh / 12f) % 1f + 1f) % 1f, (hv[1] * (1f + ds)).coerceIn(0f, 1f), hv[2])
                for (k in 0..2) c[k] = rgb[k] * (1f + dl * 0.6f * s0)
            }
            l = luma(c[0], c[1], c[2])
            for (k in 0..2) c[k] = l + (c[k] - l) * (1f + sat)
            if (abs(vib) > 0.001f) {
                val mx = max(c[0], max(c[1], c[2])).coerceIn(0f, 1f); val mn = min(c[0], min(c[1], c[2])).coerceIn(0f, 1f)
                val L = luma(c[0], c[1], c[2]); val f = 1f + vib * (1f - (mx - mn)) * 1.3f
                for (k in 0..2) c[k] = L + (c[k] - L) * f
            }
            if (shS > 0.001f || hiS > 0.001f) {
                val l3 = luma(c[0], c[1], c[2]).coerceIn(0f, 1f)
                val ws = 1f - smooth(0f, 0.55f + bal, l3); val wh = smooth(0.45f + bal, 1f, l3)
                for (k in 0..2) c[k] += ts[k] * shS * 0.45f * ws + th[k] * hiS * 0.45f * wh
            }
            if (fade > 0.001f) for (k in 0..2) c[k] = c[k].coerceIn(0f, 1f) * (1f - fade * 0.22f) + fade * 0.13f
            if (vig > 0.001f) {
                val dx = (x + 0.5f) / w - 0.5f; val dy = (y + 0.5f) / h - 0.5f
                val r = sqrt(dx * aspect * dx * aspect + dy * dy) / sqrt(0.25f * aspect * aspect + 0.25f)
                val f = 1f - vig * smooth(1f - vigF * 0.9f - 0.05f, 1.05f, r)
                for (k in 0..2) c[k] *= f
            }
            px[y * w + x] = (255 shl 24) or ((c[0].coerceIn(0f, 1f) * 255).toInt() shl 16) or ((c[1].coerceIn(0f, 1f) * 255).toInt() shl 8) or (c[2].coerceIn(0f, 1f) * 255).toInt()
        }
        @Suppress("UNUSED_VARIABLE") val unused = exp(0.0)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
