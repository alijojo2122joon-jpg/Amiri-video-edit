package com.amiri.cut.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.amiri.cut.core.effects.TextSpecDefaults
import com.amiri.cut.core.model.TextSpec
import com.amiri.cut.core.text.AnimUnit
import com.amiri.cut.core.text.Glass
import com.amiri.cut.core.text.TextAnimSpec
import com.amiri.cut.core.text.TextAnims
import com.amiri.cut.core.text.UnitXf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fonts: system families plus TTF/OTF files imported into files/fonts. Font ids are
 * "sys:<family>" or "file:<file name>". Persian/Arabic shaping and RTL come from the
 * platform text stack (HarfBuzz + bidi) used by StaticLayout.
 */
class FontManager(private val context: Context) {
    data class FontInfo(val id: String, val label: String)

    private val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val cache = ConcurrentHashMap<String, Typeface>()

    val systemFonts = listOf(
        FontInfo("sys:sans-serif", "Sans"),
        FontInfo("sys:sans-serif-medium", "Sans Medium"),
        FontInfo("sys:sans-serif-light", "Sans Light"),
        FontInfo("sys:sans-serif-condensed", "Condensed"),
        FontInfo("sys:sans-serif-black", "Sans Black"),
        FontInfo("sys:serif", "Serif"),
        FontInfo("sys:monospace", "Mono"),
        FontInfo("sys:casual", "Casual"),
        FontInfo("sys:cursive", "Cursive"),
    )

    fun imported(): List<FontInfo> = dir.listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }
        ?.sortedBy { it.name.lowercase() }
        ?.map { FontInfo("file:" + it.name, it.nameWithoutExtension) } ?: emptyList()

    /** Bundled fonts (SIL Open Font License): Vazirmatn for Persian/Arabic. */
    val bundledFonts = listOf(
        FontInfo("asset:Vazirmatn-Regular.ttf", "Vazirmatn"),
        FontInfo("asset:Vazirmatn-Light.ttf", "Vazirmatn Light"),
        FontInfo("asset:Vazirmatn-Bold.ttf", "Vazirmatn Bold"),
        FontInfo("asset:Vazirmatn-Black.ttf", "Vazirmatn Black"),
    )

    fun all(): List<FontInfo> = imported() + bundledFonts + systemFonts

    fun typeface(id: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return cache.getOrPut("$id/$style") {
            val base = when {
                id.startsWith("file:") -> runCatching { Typeface.createFromFile(File(dir, id.removePrefix("file:"))) }.getOrNull()
                id.startsWith("sys:") -> Typeface.create(id.removePrefix("sys:"), Typeface.NORMAL)
                id.startsWith("asset:") -> runCatching { Typeface.createFromAsset(context.assets, "fonts/" + id.removePrefix("asset:")) }.getOrNull()
                else -> null
            } ?: Typeface.DEFAULT
            Typeface.create(base, style)
        }
    }

    /** Copies a picked TTF/OTF into app storage. Returns the new font id or null. */
    fun import(uri: Uri, displayName: String): String? = runCatching {
        val ext = displayName.substringAfterLast('.', "ttf").lowercase().let { if (it == "otf") "otf" else "ttf" }
        val base = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _\\-\\u0600-\\u06FF]"), "_").ifBlank { "font" }
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) { f = File(dir, "$base $n.$ext"); n++ }
        context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        // Validate: must load as a typeface.
        Typeface.createFromFile(f)
        "file:" + f.name
    }.getOrNull()
}

/** Renders a text layer to a premultiplied bitmap at canvas scale. */
class TextRenderer(private val fonts: FontManager) {

    data class Result(val bitmap: Bitmap, val key: String)

    private fun v(spec: TextSpec, id: String, t: Long) = spec.props.at(id, t, TextSpecDefaults.def(id))

    private fun color(spec: TextSpec, prefix: String, t: Long, alpha: Float): Int {
        val r = v(spec, "${prefix}r", t)
        val g = v(spec, "${prefix}g", t)
        val b = v(spec, "${prefix}b", t)
        return Color.argb((alpha.coerceIn(0f, 1f) * 255).roundToInt(), (r * 255).roundToInt().coerceIn(0, 255), (g * 255).roundToInt().coerceIn(0, 255), (b * 255).roundToInt().coerceIn(0, 255))
    }

    /** Animations that are drawn into the bitmap (per line / word / letter, blur, wipe). */
    private fun bitmapAnimActive(spec: TextSpec, t: Long, durUs: Long): Boolean {
        if (!TextAnims.animated(spec)) return false
        val (pin, pout) = TextAnims.phases(spec, t, durUs)
        fun inBitmap(a: TextAnimSpec?) = a != null && (a.unit != AnimUnit.ALL || a.id == "blur" || a.id == "wipe")
        if (pin < 1f && inBitmap(TextAnims.enter(spec.animIn))) return true
        if (pout > 0f && inBitmap(TextAnims.enter(spec.animOut))) return true
        return TextAnims.loop(spec.animLoop)?.let { it.unit != AnimUnit.ALL } == true
    }

    /** A key that changes whenever the rendered pixels would. */
    fun key(spec: TextSpec, t: Long, canvasW: Int, canvasH: Int, durUs: Long = Long.MAX_VALUE / 4): String {
        val sb = StringBuilder()
        sb.append(spec.text).append('|').append(spec.font).append(spec.align).append(spec.bold).append(spec.italic)
            .append('|').append(canvasW).append('x').append(canvasH)
            .append('|').append(spec.animIn).append(spec.animOut).append(spec.animLoop).append(spec.inDur).append(spec.outDur).append(spec.loopSpeed)
        for (p in TextSpecDefaults.STYLE) sb.append('|').append("%.3f".format(v(spec, p.id, t)))
        for ((pre, _, _) in TextSpecDefaults.COLORS) for (ch in listOf("r", "g", "b")) sb.append('|').append("%.3f".format(v(spec, pre + ch, t)))
        if (bitmapAnimActive(spec, t, durUs)) sb.append("|t").append(t / 1000)
        return sb.toString()
    }

    private class Prepared(val paint: TextPaint, val lay: StaticLayout, val margin: Int, val w: Int, val h: Int, val size: Float, val opacity: Float)

    /** Bitmap size (w, h) the layer will have, without drawing. */
    fun measure(spec: TextSpec, t: Long, canvasW: Int, canvasH: Int): Pair<Int, Int> {
        val p = prepare(spec, t, canvasW, canvasH)
        return p.w.coerceAtMost(4096) to p.h.coerceAtMost(4096)
    }

    /**
     * The liquid-glass panel around the text in bitmap pixels (top-left origin):
     * centre x, centre y, half width, half height, corner radius, font size.
     */
    fun panel(spec: TextSpec, t: Long, canvasW: Int, canvasH: Int): FloatArray {
        val p = prepare(spec, t, canvasW, canvasH)
        fun g(id: String) = spec.props.at(id, t, Glass.def(id))
        val hw = p.lay.width / 2f + g("gPadX") * p.size
        val hh = p.lay.height / 2f + g("gPadY") * p.size
        return floatArrayOf(p.margin + p.lay.width / 2f, p.margin + p.lay.height / 2f, hw, hh, g("gRadius").coerceIn(0f, 1f) * minOf(hw, hh), p.size)
    }

    private fun prepare(spec: TextSpec, t: Long, canvasW: Int, canvasH: Int): Prepared {
        val size = (v(spec, "size", t) * canvasH).coerceAtLeast(4f)
        val opacity = v(spec, "opacity", t)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = fonts.typeface(spec.font, spec.bold, spec.italic)
            textSize = size
            letterSpacing = v(spec, "tracking", t)
            color = color(spec, "c", t, opacity)
        }
        val align = when (spec.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val text = spec.text.ifEmpty { " " }
        val lineH = v(spec, "lineHeight", t)
        fun layout(width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, lineH)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .build()
        val maxW = (canvasW * 0.92f).toInt()
        var lay = layout(maxW)
        var textW = 0f
        for (i in 0 until lay.lineCount) textW = max(textW, lay.getLineWidth(i))
        lay = layout(ceil(textW).toInt() + 2)

        val strokeW = v(spec, "strokeW", t) * size
        val shadowBlur = v(spec, "shadowBlur", t) * size
        val shadowDist = v(spec, "shadowDist", t) * size
        val glowR = v(spec, "glowRadius", t) * size
        val pad = v(spec, "padding", t) * size

        var margin = ceil(max(max(pad, strokeW + 2f), max(shadowBlur + shadowDist, glowR * 1.6f)) + 4f).toInt()
        // Room for letters that move / scale / blur during animations.
        if (TextAnims.animated(spec)) margin += ceil(size * 1.3f).toInt()
        val w = lay.width + margin * 2
        val h = lay.height + margin * 2
        return Prepared(paint, lay, margin, w, h, size, opacity)
    }

    fun render(spec: TextSpec, t: Long, canvasW: Int, canvasH: Int, durUs: Long = Long.MAX_VALUE / 4): Bitmap {
        val pr = prepare(spec, t, canvasW, canvasH)
        val paint = pr.paint
        val lay = pr.lay
        val margin = pr.margin
        val w = pr.w
        val h = pr.h
        val size = pr.size
        val opacity = pr.opacity
        val strokeW = v(spec, "strokeW", t) * size
        val shadowA = v(spec, "shadowA", t)
        val shadowBlur = v(spec, "shadowBlur", t) * size
        val shadowDist = v(spec, "shadowDist", t) * size
        val shadowAng = Math.toRadians(v(spec, "shadowAngle", t).toDouble())
        val glow = v(spec, "glow", t)
        val glowR = v(spec, "glowRadius", t) * size
        val bgA = v(spec, "bgA", t)
        val pad = v(spec, "padding", t) * size
        val radius = v(spec, "radius", t) * size
        val bmp = Bitmap.createBitmap(w.coerceAtMost(4096), h.coerceAtMost(4096), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        if (bgA > 0.001f) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(spec, "b", t, bgA * opacity) }
            val r = RectF(margin - pad, margin - pad, margin + lay.width + pad, margin + lay.height + pad)
            c.drawRoundRect(r, radius, radius, bp)
        }
        val fill = paint.color
        // All text passes (glow, shadow, stroke, fill) in layout coordinates.
        fun passes(cv: Canvas) {
            if (glow > 0.001f && glowR > 0.5f) {
                paint.style = Paint.Style.FILL
                paint.color = color(spec, "g", t, opacity)
                paint.setShadowLayer(glowR, 0f, 0f, color(spec, "g", t, opacity))
                val n = 1 + (glow * 3).roundToInt()
                repeat(n) { lay.draw(cv) }
                paint.clearShadowLayer()
            }
            if (shadowA > 0.001f) {
                paint.style = Paint.Style.FILL
                paint.color = color(spec, "sh", t, shadowA * opacity)
                paint.setShadowLayer(shadowBlur.coerceAtLeast(0.1f), (cos(shadowAng) * shadowDist).toFloat(), (sin(shadowAng) * shadowDist).toFloat(), color(spec, "sh", t, shadowA * opacity))
                lay.draw(cv)
                paint.clearShadowLayer()
            }
            if (strokeW > 0.1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeW * 2f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = color(spec, "s", t, opacity)
                lay.draw(cv)
            }
            paint.style = Paint.Style.FILL
            paint.color = fill
            lay.draw(cv)
        }
        c.save()
        c.translate(margin.toFloat(), margin.toFloat())
        if (bitmapAnimActive(spec, t, durUs)) drawAnimated(c, spec, t, durUs, pr, ::passes) else passes(c)
        c.restore()
        return bmp
    }

    private class TUnit(val start: Int, val end: Int, val line: Int, var x0: Float, var x1: Float) {
        var letter = 0; var word = 0
    }

    /** Draws the text unit by unit, each with its own transform (shaping is kept: every unit draws the full line, clipped). */
    private fun drawAnimated(c: Canvas, spec: TextSpec, t: Long, durUs: Long, pr: Prepared, passes: (Canvas) -> Unit) {
        val lay = pr.lay
        val size = pr.size
        val text = lay.text.toString()
        val (pin, pout) = TextAnims.phases(spec, t, durUs)
        val aIn = TextAnims.enter(spec.animIn)?.takeIf { pin < 1f }
        val aOut = TextAnims.enter(spec.animOut)?.takeIf { pout > 0f }
        val aLoop = TextAnims.loop(spec.animLoop)?.takeIf { it.unit != AnimUnit.ALL }
        // Layer-level (ALL) moves are done by the compositor; here only blur/wipe of ALL.
        fun bitmapUnit(a: TextAnimSpec?) = when {
            a == null -> null
            a.unit == AnimUnit.ALL && a.id != "blur" && a.id != "wipe" -> null
            else -> a.unit
        }
        val units = listOfNotNull(bitmapUnit(aIn), bitmapUnit(aOut), aLoop?.unit)
        val finest = units.maxByOrNull { it.ordinal } ?: AnimUnit.ALL
        val rtl = lay.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

        // Letters, words, lines.
        fun ranges(it: java.text.BreakIterator): List<IntArray> {
            it.setText(text)
            val out = ArrayList<IntArray>()
            var s0 = it.first(); var e0 = it.next()
            while (e0 != java.text.BreakIterator.DONE) {
                if (text.substring(s0, e0).isNotBlank()) out += intArrayOf(s0, e0)
                s0 = e0; e0 = it.next()
            }
            return out
        }
        val words = ranges(java.text.BreakIterator.getWordInstance())
        fun geo(s0: Int, e0: Int): TUnit {
            val line = lay.getLineForOffset(s0)
            val le = lay.getLineEnd(line)
            val e = minOf(e0, le)
            val xa = lay.getPrimaryHorizontal(s0)
            val xb = if (e < le) lay.getPrimaryHorizontal(e) else if (rtl) lay.getLineLeft(line) else lay.getLineRight(line)
            return TUnit(s0, e, line, minOf(xa, xb), maxOf(xa, xb))
        }
        val drawUnits: List<TUnit> = when (finest) {
            AnimUnit.LETTER -> ranges(java.text.BreakIterator.getCharacterInstance()).map { geo(it[0], it[1]) }
            AnimUnit.WORD -> words.map { geo(it[0], it[1]) }
            AnimUnit.LINE -> (0 until lay.lineCount).map { l -> TUnit(lay.getLineStart(l), lay.getLineEnd(l), l, 0f, lay.width.toFloat()) }
            AnimUnit.ALL -> listOf(TUnit(0, text.length, -1, 0f, lay.width.toFloat()))
        }
        drawUnits.forEachIndexed { i, u -> u.letter = i; u.word = words.indexOfFirst { w -> u.start >= w[0] && u.start < w[1] }.coerceAtLeast(0) }
        val nLines = lay.lineCount
        fun idx(unit: AnimUnit, u: TUnit): Pair<Int, Int> = when (unit) {
            AnimUnit.LETTER -> u.letter to drawUnits.size
            AnimUnit.WORD -> (if (finest == AnimUnit.WORD) u.letter else u.word) to (if (finest == AnimUnit.WORD) drawUnits.size else words.size)
            AnimUnit.LINE -> u.line.coerceAtLeast(0) to nLines
            AnimUnit.ALL -> 0 to 1
        }
        val xf = UnitXf()
        val secs = t / 1_000_000f * spec.loopSpeed
        val m = pr.margin.toFloat()
        for (u in drawUnits) {
            xf.reset()
            val cx = ((u.x0 + u.x1) / 2f - lay.width / 2f) / size
            aIn?.let { a -> if (bitmapUnit(a) != null) { val (k, n) = idx(a.unit, u); TextAnims.enterXf(a.id, TextAnims.unitProgress(pin, k, n, a.stagger), k, cx, xf) } }
            aOut?.let { a -> if (bitmapUnit(a) != null) { val (k, n) = idx(a.unit, u); TextAnims.enterXf(a.id, 1f - TextAnims.unitProgress(pout, k, n, a.stagger), k, cx, xf) } }
            aLoop?.let { a -> val (k, n) = idx(a.unit, u); TextAnims.loopXf(a.id, secs, k, n, xf) }
            if (xf.alpha <= 0.003f) continue
            // The unit's box (outer units extend into the margin so nothing is cut).
            val top = if (u.line <= 0) -m else lay.getLineTop(u.line).toFloat()
            val bottom = if (u.line < 0 || u.line == lay.lineCount - 1) lay.height + m else lay.getLineBottom(u.line).toFloat()
            val lineL = if (u.line >= 0) lay.getLineLeft(u.line) else 0f
            val lineR = if (u.line >= 0) lay.getLineRight(u.line) else lay.width.toFloat()
            val left = if (finest == AnimUnit.LINE || finest == AnimUnit.ALL || u.x0 <= lineL + 0.5f) -m else u.x0
            val right = if (finest == AnimUnit.LINE || finest == AnimUnit.ALL || u.x1 >= lineR - 0.5f) lay.width + m else u.x1
            val box = RectF(left, top, right, bottom)
            val px = (u.x0 + u.x1) / 2f
            val py = if (u.line >= 0) (lay.getLineTop(u.line) + lay.getLineBottom(u.line)) / 2f else lay.height / 2f
            val saveCount = c.save()
            val mask = (aIn?.mask == true && pin < 1f) || (aOut?.mask == true && pout > 0f)
            if (mask) c.clipRect(RectF(left, if (u.line >= 0) lay.getLineTop(u.line).toFloat() else top, right, if (u.line >= 0) lay.getLineBottom(u.line).toFloat() else bottom))
            c.translate(px + xf.dx * size, py + xf.dy * size)
            if (xf.rot != 0f) c.rotate(xf.rot)
            if (xf.sx != 1f || xf.sy != 1f) c.scale(xf.sx, xf.sy)
            c.translate(-px, -py)
            c.clipRect(box)
            if (xf.revealX < 1f) {
                val wv = box.width() * xf.revealX.coerceIn(0f, 1f)
                if (rtl) c.clipRect(box.right - wv, box.top, box.right, box.bottom) else c.clipRect(box.left, box.top, box.left + wv, box.bottom)
            }
            if (xf.alpha < 0.999f) c.saveLayerAlpha(box, (xf.alpha * 255).roundToInt().coerceIn(0, 255))
            val blurR = xf.blur * size
            if (blurR > 0.5f) paint(pr).maskFilter = android.graphics.BlurMaskFilter(blurR, android.graphics.BlurMaskFilter.Blur.NORMAL)
            passes(c)
            paint(pr).maskFilter = null
            c.restoreToCount(saveCount)
        }
    }

    private fun paint(pr: Prepared): TextPaint = pr.paint
}

/** .cube 3D LUT → 2D strip bitmap (N² × N; pixel (r + b·N, g)). */
object LutLoader {
    data class Lut(val size: Int, val bitmap: Bitmap)

    fun load(file: File): Lut? = runCatching {
        var n = 0
        val values = ArrayList<Float>()
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when {
                    parts[0] == "LUT_3D_SIZE" -> n = parts[1].toInt()
                    parts[0] == "DOMAIN_MIN" -> domainMin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    parts[0] == "DOMAIN_MAX" -> domainMax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    parts[0].first().isDigit() || parts[0].first() == '-' || parts[0].first() == '.' -> if (parts.size >= 3) {
                        values += parts[0].toFloat(); values += parts[1].toFloat(); values += parts[2].toFloat()
                    }
                }
            }
        }
        require(n in 2..128 && values.size >= n * n * n * 3) { "Not a 3D .cube LUT" }
        val px = IntArray(n * n * n)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            fun ch(k: Int) = ((values[i + k] - domainMin[k]) / (domainMax[k] - domainMin[k])).coerceIn(0f, 1f)
            val x = r + b * n
            px[g * n * n + x] = Color.argb(255, (ch(0) * 255).roundToInt(), (ch(1) * 255).roundToInt(), (ch(2) * 255).roundToInt())
            i += 3
        }
        Lut(n, Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888))
    }.getOrNull()
}

/**
 * Tone curves → 256×1 texture: R,G,B channels hold per-channel curves, A the master
 * curve. Points are "x,y;x,y;…" (0..1), interpolated with a monotone cubic.
 */
object CurveBuilder {
    const val IDENTITY = "0,0;1,1"

    fun parse(s: String?): List<Pair<Float, Float>> {
        val pts = (s ?: IDENTITY).split(';').mapNotNull { p ->
            val xy = p.split(',')
            if (xy.size == 2) (xy[0].toFloatOrNull() ?: return@mapNotNull null) to (xy[1].toFloatOrNull() ?: return@mapNotNull null) else null
        }.sortedBy { it.first }
        return if (pts.size >= 2) pts else listOf(0f to 0f, 1f to 1f)
    }

    fun format(pts: List<Pair<Float, Float>>): String = pts.sortedBy { it.first }.joinToString(";") { "%.4f,%.4f".format(it.first, it.second) }

    fun isIdentity(s: String?): Boolean = s == null || parse(s).all { kotlin.math.abs(it.first - it.second) < 1e-3f }

    /** Monotone cubic (Fritsch–Carlson) sampled at 256 points. */
    fun sample(pts: List<Pair<Float, Float>>): FloatArray {
        val n = pts.size
        val xs = FloatArray(n) { pts[it].first }
        val ys = FloatArray(n) { pts[it].second }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-5f, xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0f else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val tau = 3f / kotlin.math.sqrt(s); m[i] = tau * a * d[i]; m[i + 1] = tau * b * d[i] }
        }
        return FloatArray(256) { k ->
            val x = k / 255f
            if (x <= xs[0]) return@FloatArray ys[0]
            if (x >= xs[n - 1]) return@FloatArray ys[n - 1]
            var i = 0
            while (i < n - 2 && x > xs[i + 1]) i++
            val h = xs[i + 1] - xs[i]
            val t = (x - xs[i]) / h
            val t2 = t * t
            val t3 = t2 * t
            ((2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]).coerceIn(0f, 1f)
        }
    }

    /** RGBA bytes (256×1, straight alpha): R,G,B per-channel curves, A = master curve. */
    fun bytes(master: String?, r: String?, g: String?, b: String?): ByteArray {
        val M = sample(parse(master))
        val R = sample(parse(r))
        val G = sample(parse(g))
        val B = sample(parse(b))
        val out = ByteArray(256 * 4)
        for (k in 0 until 256) {
            out[k * 4] = (R[k] * 255).roundToInt().toByte()
            out[k * 4 + 1] = (G[k] * 255).roundToInt().toByte()
            out[k * 4 + 2] = (B[k] * 255).roundToInt().toByte()
            out[k * 4 + 3] = (M[k] * 255).roundToInt().toByte()
        }
        return out
    }
}
