package com.amiri.cut.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import com.amiri.cut.core.effects.EffectCatalog
import com.amiri.cut.core.effects.TransformSpec
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.ClipKind
import com.amiri.cut.core.model.Effect
import com.amiri.cut.core.model.MaskMode
import com.amiri.cut.core.model.MaskShape
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.effects.MaskSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A decoded video frame living in an external OES texture. */
class VideoFrame(val oesTex: Int, val texMatrix: FloatArray)

/** Where the compositor gets its pixels from (preview players or export decoders). */
interface FrameSources {
    fun video(clip: Clip, asset: MediaAsset): VideoFrame?
    fun image(asset: MediaAsset): Bitmap?
    fun roto(file: String): Bitmap?
    fun lut(name: String): LutLoader.Lut?
}

data class RenderOptions(
    /** Draw a checkerboard behind transparent areas (preview of transparent projects). */
    val checker: Boolean = false,
    /** Before/After: skip color effects of this clip. */
    val bypassColorClipId: String? = null,
    /** While painting roto: show this clip without its roto mask. */
    val bypassRotoClipId: String? = null,
)

/**
 * The renderer. For every frame it builds each visible layer (bottom → top) in its
 * own source space — input + shape masks + roto, then the effect stack — and
 * composites it onto the canvas with the clip's transform, crop, opacity, blend mode
 * and (optional) transform motion blur. Adjustment layers apply their effect stack
 * to everything composited below them. Preview and export run this same code.
 */
class Compositor(private val text: TextRenderer) {

    private val pool = FboPool()
    private val programs = HashMap<String, Program>()
    private var white = 0

    private class Cached(var tex: Int, var key: Any?, var w: Int = 0, var h: Int = 0)

    private val imageTex = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private val rotoTex = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private val lutTex = HashMap<String, Cached>()
    private val curveTex = HashMap<String, Cached>()
    private val pathTex = LinkedHashMap<String, Cached>(8, 0.75f, true)
    private val textTex = HashMap<String, Cached>()

    private fun prog(name: String, fs: String): Program = programs.getOrPut(name) { Program(Shaders.VS, fs) }

    private fun whiteTex(): Int {
        if (white == 0) {
            val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            white = Gl.uploadBitmap(b)
            b.recycle()
        }
        return white
    }

    private fun <K> evict(map: LinkedHashMap<K, Cached>, max: Int) {
        while (map.size > max) {
            val first = map.entries.iterator().next()
            Gl.deleteTexture(first.value.tex)
            map.remove(first.key)
        }
    }

    // ───────────────────────────── frame ─────────────────────────────

    /**
     * Renders the frame at timeline time [t] into framebuffer [outFb] (0 = window) inside
     * [viewport] (x, y, w, h). The canvas is rendered at [cw]×[ch].
     * [capture] receives a downscaled RGBA copy of the canvas (for scopes) when non-null.
     */
    fun render(
        project: Project,
        t: Long,
        cw: Int,
        ch: Int,
        src: FrameSources,
        opt: RenderOptions,
        outFb: Int,
        viewport: IntArray,
        capture: ((ByteArray, Int, Int) -> Unit)? = null,
    ) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        var canvas = pool.obtain(cw, ch)
        var spare = pool.obtain(cw, ch)
        if (project.settings.background == CanvasBackground.BLACK) canvas.clear(0f, 0f, 0f, 1f) else canvas.clear()

        for (track in project.tracks.asReversed()) {
            if (track.hidden || track.kind == TrackKind.AUDIO) continue
            val clip = track.clipAt(t) ?: continue
            if (clip.kind == ClipKind.ADJUSTMENT) {
                val out = applyAdjustment(clip, canvas, t, src, opt)
                if (out !== canvas) { pool.recycle(canvas); canvas = out }
                continue
            }
            val layer = buildLayer(project, clip, t, cw, ch, src, opt) ?: continue
            composite(project, clip, layer, t, canvas, spare, cw, ch)
            pool.recycle(layer.fbo)
            val tmp = canvas; canvas = spare; spare = tmp
        }

        if (capture != null) captureCanvas(canvas, capture)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFb)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        val p = prog("present", Shaders.PRESENT)
        p.use()
        p.tex("uTex", 0, canvas.tex)
        p.f1("uChecker", if (opt.checker && project.settings.background == CanvasBackground.TRANSPARENT) 1f else 0f)
        p.f1("uCell", max(8f, viewport[2] / 40f))
        p.draw()
        pool.recycle(canvas)
        pool.recycle(spare)
    }

    private fun captureCanvas(canvas: Fbo, cb: (ByteArray, Int, Int) -> Unit) {
        val w = 256
        val h = max(16, (256f * canvas.height / canvas.width).roundToInt())
        val small = pool.obtain(w, h)
        small.bind()
        val p = prog("copy", Shaders.COPY)
        p.use(); p.tex("uTex", 0, canvas.tex); p.draw()
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bytes = ByteArray(w * h * 4)
        buf.get(bytes)
        pool.recycle(small)
        cb(bytes, w, h)
    }

    // ───────────────────────────── layers ─────────────────────────────

    private class Layer(val fbo: Fbo, val baseW: Float, val baseH: Float)

    private fun buildLayer(project: Project, clip: Clip, t: Long, cw: Int, ch: Int, src: FrameSources, opt: RenderOptions): Layer? {
        val local = t - clip.startUs
        val srcUs = clip.sourceTimeAt(t)
        var oes = false
        val texId: Int
        var texMatrix: FloatArray? = null
        val sw: Float
        val sh: Float
        val baseW: Float
        val baseH: Float

        if (clip.kind == ClipKind.TEXT) {
            val spec = clip.text ?: return null
            val key = text.key(spec, local, cw, ch)
            val c = textTex.getOrPut(clip.id) { Cached(0, null) }
            if (c.key != key) {
                val bmp = text.render(spec, local, cw, ch)
                c.tex = Gl.uploadBitmap(bmp, c.tex)
                c.w = bmp.width; c.h = bmp.height; c.key = key
                bmp.recycle()
            }
            texId = c.tex
            sw = c.w.toFloat(); sh = c.h.toFloat()
            baseW = sw; baseH = sh
        } else {
            val asset = project.asset(clip.assetId) ?: return null
            when (asset.type) {
                MediaType.VIDEO -> {
                    val vf = src.video(clip, asset) ?: return null
                    oes = true
                    texId = vf.oesTex
                    texMatrix = vf.texMatrix
                    sw = asset.displayWidth.takeIf { it > 0 }?.toFloat() ?: cw.toFloat()
                    sh = asset.displayHeight.takeIf { it > 0 }?.toFloat() ?: ch.toFloat()
                }
                MediaType.IMAGE -> {
                    val bmp = src.image(asset) ?: return null
                    val c = imageTex.getOrPut(asset.id) { Cached(0, null) }
                    if (c.key !== bmp) { c.tex = Gl.uploadBitmap(bmp, c.tex); c.key = bmp; c.w = bmp.width; c.h = bmp.height }
                    evict(imageTex, 24)
                    texId = c.tex
                    sw = c.w.toFloat(); sh = c.h.toFloat()
                }
                MediaType.AUDIO -> return null
            }
            val fit = min(cw / sw, ch / sh)
            baseW = sw * fit
            baseH = sh * fit
        }

        val lw = baseW.roundToInt().coerceIn(2, 4096)
        val lh = baseH.roundToInt().coerceIn(2, 4096)
        val l0 = pool.obtain(lw, lh)
        l0.bind()
        val p = if (oes) prog("in_oes", Shaders.INPUT_OES) else prog("in_2d", Shaders.INPUT_2D)
        p.use()
        p.tex("uTex", 0, texId, oes)
        if (oes) p.mat4("uTexMatrix", texMatrix!!)
        setMaskUniforms(p, clip, local, srcUs, sw / sh, src, opt)
        p.draw()

        var cur = l0
        for (e in clip.effects) {
            if (!e.enabled) continue
            if (opt.bypassColorClipId == clip.id && EffectCatalog.spec(e.type)?.category == com.amiri.cut.core.effects.EffectCategory.COLOR) continue
            cur = applyEffect(e, cur, local, src)
        }
        return Layer(cur, baseW, baseH)
    }

    private val maskA = FloatArray(16)
    private val maskB = FloatArray(16)
    private val maskC = FloatArray(16)

    private fun setMaskUniforms(p: Program, clip: Clip, local: Long, srcUs: Long, aspect: Float, src: FrameSources, opt: RenderOptions) {
        val w = whiteTex()
        // Roto
        val roto = clip.roto
        var rotoTexId = w
        var hasRoto = 0f
        if (roto != null && roto.enabled && roto.keys.isNotEmpty() && opt.bypassRotoClipId != clip.id) {
            val k = roto.keyAt(srcUs)
            val bmp = k?.let { src.roto(it.file) }
            if (bmp != null) {
                val c = rotoTex.getOrPut(k!!.file) { Cached(0, null) }
                if (c.key !== bmp) { c.tex = Gl.uploadBitmap(bmp, c.tex); c.key = bmp }
                evict(rotoTex, 24)
                rotoTexId = c.tex
                hasRoto = 1f
            }
        }
        p.tex("uRoto", 1, rotoTexId)
        p.f1("uHasRoto", hasRoto)
        p.f1("uRotoInvert", if (roto?.invert == true) 1f else 0f)
        p.f1("uAspect", aspect)

        // Shape masks (up to 4, up to 2 path masks)
        val masks = clip.masks.take(4)
        var pathIdx = 0
        val pathTexIds = intArrayOf(w, w)
        val track = clip.tracking
        val ref = track?.samples?.firstOrNull()
        val now = track?.at(srcUs)
        masks.forEachIndexed { i, m ->
            fun v(id: String) = m.props.at(id, local, MaskSpec.def(id))
            var x = v("x")
            var y = v("y")
            var rot = v("rot")
            var sx = 1f
            if (m.followTrack && ref != null && now != null) {
                x += now.x - ref.x; y += now.y - ref.y
                rot += now.rot - ref.rot; sx = now.scale / ref.scale.coerceAtLeast(0.01f)
            }
            maskA[i * 4] = x; maskA[i * 4 + 1] = y
            maskA[i * 4 + 2] = v("w") * sx; maskA[i * 4 + 3] = v("h") * sx
            maskB[i * 4] = Math.toRadians(rot.toDouble()).toFloat()
            maskB[i * 4 + 1] = v("feather")
            maskB[i * 4 + 2] = v("expand")
            maskB[i * 4 + 3] = v("opacity")
            var pi = 0f
            if (m.shape == MaskShape.PATH && pathIdx < 2 && m.path.size >= 6) {
                pathTexIds[pathIdx] = pathTexture(m.path, v("feather"))
                pi = pathIdx.toFloat()
                pathIdx++
            }
            maskC[i * 4] = when (m.shape) { MaskShape.RECT -> 0f; MaskShape.ELLIPSE -> 1f; MaskShape.PATH -> if (m.path.size >= 6) 2f else 0f }
            maskC[i * 4 + 1] = when (m.mode) { MaskMode.ADD -> 0f; MaskMode.SUBTRACT -> 1f; MaskMode.INTERSECT -> 2f }
            maskC[i * 4 + 2] = if (m.invert) 1f else 0f
            maskC[i * 4 + 3] = pi
        }
        p.i1("uMaskCount", masks.size)
        if (masks.isNotEmpty()) {
            p.f4v("uMaskA", maskA, 4)
            p.f4v("uMaskB", maskB, 4)
            p.f4v("uMaskC", maskC, 4)
        }
        p.tex("uPath0", 2, pathTexIds[0])
        p.tex("uPath1", 3, pathTexIds[1])
    }

    /** Rasterizes a polygon given in its unit box (0..1) into a soft alpha texture. */
    private fun pathTexture(path: List<Float>, feather: Float): Int {
        val key = path.joinToString(",") { "%.4f".format(it) } + "|" + "%.3f".format(feather)
        pathTex[key]?.let { return it.tex }
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pp = Path()
        pp.moveTo(path[0] * size, path[1] * size)
        var i = 2
        while (i + 1 < path.size) { pp.lineTo(path[i] * size, path[i + 1] * size); i += 2 }
        pp.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            val f = feather * size
            if (f >= 0.5f) maskFilter = BlurMaskFilter(f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(pp, paint)
        val tex = Gl.uploadBitmap(bmp)
        bmp.recycle()
        pathTex[key] = Cached(tex, key)
        evict(pathTex, 8)
        return tex
    }

    // ───────────────────────────── compositing ─────────────────────────────

    private val inv = FloatArray(9 * 12)

    private fun composite(project: Project, clip: Clip, layer: Layer, t: Long, canvas: Fbo, out: Fbo, cw: Int, ch: Int) {
        val local = t - clip.startUs
        fun tv(id: String, at: Long = local) = clip.transform.at(id, at, TransformSpec.def(id))
        val mbAmount = tv("mbAmount")
        val moving = clip.transform.animated || clip.follow != null || clip.stab != null
        val samples = if (mbAmount > 0.001f && moving) (2 + (mbAmount * 10).roundToInt()).coerceAtMost(12) else 1
        val frameUs = 1_000_000.0 / project.settings.fps
        val shutter = tv("mbShutter") / 360.0
        for (i in 0 until samples) {
            val ts = if (samples == 1) t else t - (shutter * frameUs * i / (samples - 1)).toLong()
            inverseMatrix(project, clip, ts, layer.baseW, layer.baseH, cw, ch, inv, i * 9)
        }
        out.bind()
        val p = prog("composite", Shaders.COMPOSITE)
        p.use()
        p.tex("uLayer", 0, layer.fbo.tex)
        p.tex("uDst", 1, canvas.tex)
        p.mat3v("uInv", inv, samples)
        p.i1("uSamples", samples)
        p.f4("uCrop", tv("cropL"), tv("cropT"), tv("cropR"), tv("cropB"))
        p.f1("uOpacity", tv("opacity").coerceIn(0f, 1f))
        p.i1("uBlend", clip.blend.ordinal)
        p.draw()
    }

    fun inverseMatrix(project: Project, clip: Clip, ts: Long, baseW: Float, baseH: Float, cw: Int, ch: Int, dst: FloatArray, off: Int) =
        LayerMath.inverse(project, clip, ts, baseW, baseH, cw, ch, dst, off)

    // ───────────────────────────── adjustment layers ─────────────────────────────

    private fun applyAdjustment(clip: Clip, canvas: Fbo, t: Long, src: FrameSources, opt: RenderOptions): Fbo {
        val local = t - clip.startUs
        val active = clip.effects.filter { it.enabled }
        if (active.isEmpty()) return canvas
        // Work on a copy so the original stays available for the opacity mix.
        var work = pool.obtain(canvas.width, canvas.height)
        work.bind()
        prog("copy", Shaders.COPY).apply { use(); tex("uTex", 0, canvas.tex); draw() }
        for (e in active) {
            if (opt.bypassColorClipId == clip.id && EffectCatalog.spec(e.type)?.category == com.amiri.cut.core.effects.EffectCategory.COLOR) continue
            work = applyEffect(e, work, local, src)
        }
        val opacity = clip.transform.at("opacity", local, 1f).coerceIn(0f, 1f)
        val out = pool.obtain(canvas.width, canvas.height)
        out.bind()
        prog("mix", Shaders.MIX).apply {
            use(); tex("uTex", 0, work.tex); tex("uOrig", 1, canvas.tex); f1("uAmount", opacity); draw()
        }
        pool.recycle(work)
        return out
    }

    // ───────────────────────────── effects ─────────────────────────────

    private fun blur(input: Fbo, radiusPx: Float): Fbo {
        var down = 1
        while (radiusPx / down > 8f && down < 8) down *= 2
        val bw = max(2, input.width / down)
        val bh = max(2, input.height / down)
        val a = pool.obtain(bw, bh)
        a.bind()
        prog("copy", Shaders.COPY).apply { use(); tex("uTex", 0, input.tex); draw() }
        val b = pool.obtain(bw, bh)
        val step = max(0.5f, radiusPx / down / 4f)
        val p = prog("blur", Shaders.BLUR)
        b.bind(); p.use(); p.tex("uTex", 0, a.tex); p.f2("uDir", step / bw, 0f); p.draw()
        a.bind(); p.use(); p.tex("uTex", 0, b.tex); p.f2("uDir", 0f, step / bh); p.draw()
        pool.recycle(b)
        return a
    }

    private fun blendMode(e: Effect): Int = if (e.opts["blend"] == "Add") 1 else 0

    private fun applyEffect(e: Effect, input: Fbo, local: Long, src: FrameSources): Fbo {
        val spec = EffectCatalog.spec(e.type) ?: return input
        fun v(id: String) = e.props.at(id, local, spec.param(id)?.default ?: 0f)
        val w = input.width
        val h = input.height
        val aspect = w.toFloat() / h
        val secs = local / 1_000_000f
        val maxDim = max(w, h).toFloat()
        val out = pool.obtain(w, h)

        fun run(name: String, fs: String, setup: (Program) -> Unit) {
            out.bind()
            val p = prog(name, fs)
            p.use()
            p.tex("uTex", 0, input.tex)
            setup(p)
            p.draw()
        }

        when (e.type) {
            "color" -> {
                val blurAmt = v("blur")
                val blurred = if (blurAmt > 0.001f) blur(input, 2f + blurAmt * 0.03f * maxDim) else null
                val curveKey = listOf("curve_m", "curve_r", "curve_g", "curve_b").joinToString("|") { e.opts[it] ?: CurveBuilder.IDENTITY }
                val hasCurve = listOf("curve_m", "curve_r", "curve_g", "curve_b").any { !CurveBuilder.isIdentity(e.opts[it]) }
                val curve = if (hasCurve) curveTex.getOrPut(curveKey) {
                    Cached(Gl.uploadRgba(CurveBuilder.bytes(e.opts["curve_m"], e.opts["curve_r"], e.opts["curve_g"], e.opts["curve_b"]), 256, 1), curveKey)
                }.tex else whiteTex()
                val hh = FloatArray(6); val hs = FloatArray(6); val hl = FloatArray(6)
                var anyHsl = false
                EffectCatalog.HSL_RANGES.forEachIndexed { i, r ->
                    hh[i] = v("hsl_${r}_h"); hs[i] = v("hsl_${r}_s"); hl[i] = v("hsl_${r}_l")
                    if (hh[i] != 0f || hs[i] != 0f || hl[i] != 0f) anyHsl = true
                }
                fun chroma(x: Float, y: Float) = floatArrayOf(1.402f * y, -0.344f * x - 0.714f * y, 1.772f * x)
                val lc = chroma(v("lift_x"), v("lift_y")); val ll = v("lift_l")
                val gc = chroma(v("gamma_x"), v("gamma_y")); val gl = v("gamma_l")
                val kc = chroma(v("gain_x"), v("gain_y")); val kl = v("gain_l")
                run("color", Shaders.COLOR) { p ->
                    p.tex("uBlurTex", 1, blurred?.tex ?: input.tex)
                    p.tex("uCurve", 2, curve)
                    p.f2("uTexel", 1f / w, 1f / h)
                    p.f1("uAspect", aspect)
                    p.f1("uExposure", v("exposure")); p.f1("uContrast", v("contrast")); p.f1("uBrightness", v("brightness"))
                    p.f1("uHighlights", v("highlights")); p.f1("uShadows", v("shadows")); p.f1("uWhites", v("whites")); p.f1("uBlacks", v("blacks"))
                    p.f1("uSaturation", v("saturation")); p.f1("uTemp", v("temperature")); p.f1("uTint", v("tint"))
                    p.f1v("uHslH", hh, 6); p.f1v("uHslS", hs, 6); p.f1v("uHslL", hl, 6)
                    p.f1("uHasHsl", if (anyHsl) 1f else 0f)
                    p.f3("uLift", ll * 0.15f + lc[0] * 0.12f, ll * 0.15f + lc[1] * 0.12f, ll * 0.15f + lc[2] * 0.12f)
                    p.f3("uGamma", 1f + gl * 0.4f + gc[0] * 0.25f, 1f + gl * 0.4f + gc[1] * 0.25f, 1f + gl * 0.4f + gc[2] * 0.25f)
                    p.f3("uGain", 1f + kl * 0.5f + kc[0] * 0.3f, 1f + kl * 0.5f + kc[1] * 0.3f, 1f + kl * 0.5f + kc[2] * 0.3f)
                    p.f1("uVignette", v("vignette")); p.f1("uVigFeather", v("vig_feather"))
                    p.f1("uSharpen", v("sharpen")); p.f1("uBlur", blurAmt)
                    p.f1("uHasCurve", if (hasCurve) 1f else 0f)
                }
                blurred?.let { pool.recycle(it) }
            }
            "lut" -> {
                val name = e.opts["file"]
                val lut = name?.let { src.lut(it) }
                if (lut == null) { pool.recycle(out); return input }
                val c = lutTex.getOrPut(name!!) { Cached(Gl.uploadBitmap(lut.bitmap), name, lut.size) }
                run("lut", Shaders.LUT) { p ->
                    p.tex("uLut", 1, c.tex); p.f1("uSize", c.w.toFloat()); p.f1("uStrength", v("strength"))
                }
            }
            "chroma" -> run("chroma", Shaders.CHROMA) { p ->
                p.f3("uKey", v("kr"), v("kg"), v("kb"))
                p.f1("uTol", v("tolerance")); p.f1("uSoft", v("softness")); p.f1("uSpill", v("spill")); p.f1("uEdge", v("edge"))
            }
            "glow" -> {
                val bright = pool.obtain(w, h)
                bright.bind()
                prog("glow_b", Shaders.GLOW_BRIGHT).apply {
                    use(); tex("uTex", 0, input.tex); f1("uThr", v("threshold")); f1("uSoft", v("softness")); draw()
                }
                val g = blur(bright, 2f + v("radius") * 0.12f * maxDim)
                pool.recycle(bright)
                run("glow_c", Shaders.GLOW_COMBINE) { p ->
                    p.tex("uGlow", 1, g.tex); p.f3("uColor", v("cr"), v("cg"), v("cb"))
                    p.f1("uIntensity", v("intensity")); p.i1("uMode", blendMode(e))
                }
                pool.recycle(g)
            }
            "sweep" -> run("sweep", Shaders.SWEEP) { p ->
                p.f1("uPos", v("pos")); p.f1("uAngle", Math.toRadians(v("angle").toDouble()).toFloat())
                p.f1("uWidth", v("width")); p.f1("uIntensity", v("intensity")); p.f1("uSoft", v("softness"))
                p.f1("uOpacity", v("opacity")); p.f3("uColor", v("cr"), v("cg"), v("cb")); p.f1("uAspect", aspect)
                p.i1("uMode", blendMode(e))
            }
            "rays" -> run("rays", Shaders.RAYS) { p ->
                p.f2("uCenter", v("x"), 1f - v("y")); p.f1("uDir", Math.toRadians(v("direction").toDouble()).toFloat())
                p.f1("uIntensity", v("intensity")); p.f1("uLength", v("length")); p.f1("uDecay", v("decay"))
                p.f1("uSoft", v("softness")); p.f1("uOpacity", v("opacity")); p.f3("uColor", v("cr"), v("cg"), v("cb"))
            }
            "leak" -> {
                val cols = LEAK_COLORS[e.opts["preset"] ?: "Amber"] ?: LEAK_COLORS.values.first()
                run("leak", Shaders.LEAK) { p ->
                    p.f3("uC1", cols[0], cols[1], cols[2]); p.f3("uC2", cols[3], cols[4], cols[5]); p.f3("uC3", cols[6], cols[7], cols[8])
                    p.f2("uCenter", v("x"), 1f - v("y")); p.f1("uTime", secs); p.f1("uIntensity", v("intensity"))
                    p.f1("uOpacity", v("opacity")); p.f1("uScale", v("scale")); p.f1("uRot", Math.toRadians(v("rot").toDouble()).toFloat())
                    p.f1("uSpeed", v("speed")); p.f1("uAspect", aspect); p.i1("uMode", blendMode(e))
                }
            }
            "film" -> {
                val b = blur(input, 2f + 0.012f * maxDim)
                run("film", Shaders.FILM) { p ->
                    p.tex("uBlurTex", 1, b.tex); p.f2("uRes", w.toFloat(), h.toFloat()); p.f1("uTime", secs); p.f1("uAspect", aspect)
                    p.f1("uGrain", v("grain")); p.f1("uGrainSize", v("grainSize")); p.f1("uDust", v("dust")); p.f1("uScratch", v("scratch"))
                    p.f1("uFlicker", v("flicker")); p.f1("uVignette", v("vignette")); p.f1("uHalation", v("halation"))
                    p.f1("uFade", v("fade")); p.f1("uChroma", v("chroma")); p.f1("uBlurAmt", v("blur")); p.f1("uWarmth", v("warmth"))
                }
                pool.recycle(b)
            }
            "blur" -> {
                val r = v("radius")
                if (r <= 0.001f) { pool.recycle(out); return input }
                val b = blur(input, 1f + r * 0.06f * maxDim)
                run("copy", Shaders.COPY) { p -> p.tex("uTex", 0, b.tex) }
                pool.recycle(b)
            }
            "dirblur" -> {
                val a = Math.toRadians(v("angle").toDouble())
                val len = v("length") * 0.06f * maxDim / 8f
                run("dirblur", Shaders.DIRBLUR) { p -> p.f2("uStep", (cos(a) * len / w).toFloat(), (sin(a) * len / h).toFloat()) }
            }
            "zoomblur" -> run("zoomblur", Shaders.ZOOMBLUR) { p -> p.f2("uCenter", v("x"), 1f - v("y")); p.f1("uAmount", v("amount")) }
            "chromab" -> run("chromab", Shaders.CHROMAB) { p -> p.f1("uAmount", v("amount")); p.f1("uAngle", Math.toRadians(v("angle").toDouble()).toFloat()) }
            "wave" -> run("wave", Shaders.WAVE) { p ->
                p.f1("uAmp", v("amp")); p.f1("uFreq", v("freq")); p.f1("uSpeed", v("speed"))
                p.f1("uAngle", Math.toRadians(v("angle").toDouble()).toFloat()); p.f1("uTime", secs); p.f1("uAspect", aspect)
            }
            "bulge" -> run("bulge", Shaders.BULGE) { p ->
                p.f2("uCenter", v("x"), 1f - v("y")); p.f1("uAmount", v("amount")); p.f1("uRadius", v("radius")); p.f1("uAspect", aspect)
            }
            "sharpen" -> run("sharpen", Shaders.SHARPEN) { p -> p.f2("uTexel", 1f / w, 1f / h); p.f1("uAmount", v("amount")) }
            "posterize" -> run("posterize", Shaders.POSTERIZE) { p -> p.f1("uLevels", v("levels").coerceAtLeast(2f)) }
            "mosaic" -> run("mosaic", Shaders.MOSAIC) { p -> val s = v("size").coerceAtLeast(0.001f); p.f2("uCell", s / aspect, s) }
            "vignette" -> run("vignette", Shaders.VIGNETTE) { p ->
                p.f1("uAmount", v("amount")); p.f1("uFeather", v("feather")); p.f1("uRound", v("roundness")); p.f1("uAspect", aspect)
            }
            else -> { pool.recycle(out); return input }
        }
        pool.recycle(input)
        return out
    }

    fun release() {
        programs.values.forEach { it.release() }
        programs.clear()
        (imageTex.values + rotoTex.values + lutTex.values + curveTex.values + pathTex.values + textTex.values).forEach { Gl.deleteTexture(it.tex) }
        imageTex.clear(); rotoTex.clear(); lutTex.clear(); curveTex.clear(); pathTex.clear(); textTex.clear()
        Gl.deleteTexture(white); white = 0
        pool.releaseAll()
    }

    /** Drops cached textures for text clips that no longer exist. */
    fun pruneText(liveClipIds: Set<String>) {
        val dead = textTex.keys.filter { it !in liveClipIds }
        dead.forEach { Gl.deleteTexture(textTex[it]!!.tex); textTex.remove(it) }
    }

    companion object {
        /** Light-leak presets: three RGB colors each. */
        val LEAK_COLORS: Map<String, FloatArray> = mapOf(
            "Amber" to floatArrayOf(1f, 0.55f, 0.15f, 1f, 0.8f, 0.35f, 0.9f, 0.3f, 0.1f),
            "Rose" to floatArrayOf(1f, 0.35f, 0.5f, 1f, 0.6f, 0.7f, 0.8f, 0.2f, 0.4f),
            "Sunset" to floatArrayOf(1f, 0.4f, 0.1f, 0.9f, 0.2f, 0.4f, 1f, 0.75f, 0.3f),
            "Teal Burn" to floatArrayOf(0.1f, 0.8f, 0.8f, 1f, 0.5f, 0.2f, 0.2f, 0.5f, 0.9f),
            "Magenta" to floatArrayOf(0.9f, 0.2f, 0.8f, 0.5f, 0.2f, 1f, 1f, 0.4f, 0.6f),
            "Golden Hour" to floatArrayOf(1f, 0.75f, 0.3f, 1f, 0.9f, 0.6f, 1f, 0.5f, 0.15f),
            "Cold Flash" to floatArrayOf(0.5f, 0.75f, 1f, 0.85f, 0.95f, 1f, 0.3f, 0.5f, 1f),
            "Vintage Red" to floatArrayOf(0.95f, 0.15f, 0.1f, 1f, 0.45f, 0.2f, 0.7f, 0.1f, 0.15f),
        )
    }
}

/** Helpers for the column-major canvas→layer affine produced by [Compositor.inverseMatrix]. */
object Affine {
    /** Inverts a 2D affine (column-major 3×3). */
    fun invert(m: FloatArray): FloatArray? {
        val a = m[0]; val b = m[3]; val c = m[6]
        val d = m[1]; val e = m[4]; val f = m[7]
        val det = a * e - b * d
        if (kotlin.math.abs(det) < 1e-12f) return null
        val ia = e / det; val ib = -b / det; val id = -d / det; val ie = a / det
        val ic = -(ia * c + ib * f); val iff = -(id * c + ie * f)
        return floatArrayOf(ia, id, 0f, ib, ie, 0f, ic, iff, 1f)
    }

    /** Layer uv (top-left origin) → canvas pixel (top-left origin). */
    fun forward(invM: FloatArray, u: Float, v: Float, ch: Int): FloatArray? {
        val f = invert(invM) ?: return null
        val gu = u
        val gv = 1f - v
        val x = f[0] * gu + f[3] * gv + f[6]
        val y = f[1] * gu + f[4] * gv + f[7]
        return floatArrayOf(x, ch - y)
    }

    /** Canvas pixel (top-left origin) → layer uv (top-left origin). */
    fun toLayer(invM: FloatArray, x: Float, y: Float, ch: Int): FloatArray {
        val gx = x
        val gy = ch - y
        val u = invM[0] * gx + invM[3] * gy + invM[6]
        val v = invM[1] * gx + invM[4] * gy + invM[7]
        return floatArrayOf(u, 1f - v)
    }
}

/** Pure layer geometry (no GL): used by the renderer and by on-screen gizmos. */
object LayerMath {
    /** Base (untransformed) size of a layer on a [cw]×[ch] canvas, or null if it has no size. */
    fun baseSize(project: Project, clip: Clip, t: Long, cw: Int, ch: Int, text: TextRenderer?): Pair<Float, Float>? {
        if (clip.kind == ClipKind.TEXT) {
            val spec = clip.text ?: return null
            val m = text?.measure(spec, t - clip.startUs, cw, ch) ?: return null
            return m.first.toFloat() to m.second.toFloat()
        }
        val a = project.asset(clip.assetId) ?: return null
        val sw = a.displayWidth.takeIf { it > 0 }?.toFloat() ?: return null
        val sh = a.displayHeight.takeIf { it > 0 }?.toFloat() ?: return null
        val fit = min(cw / sw, ch / sh)
        return sw * fit to sh * fit
    }

    /** Canvas-pixel (GL origin) → layer-uv (GL origin) affine for the clip at [ts], column-major. */
    fun inverseOf(project: Project, clip: Clip, ts: Long, baseW: Float, baseH: Float, cw: Int, ch: Int): FloatArray =
        FloatArray(9).also { inverse(project, clip, ts, baseW, baseH, cw, ch, it, 0) }

    /**
     * Writes the canvas-pixel → layer-uv affine map (column-major mat3) for [clip] at
     * timeline time [ts] into [dst] at [off]. Includes stabilization and track follow.
     */
    fun inverse(project: Project, clip: Clip, ts: Long, baseW: Float, baseH: Float, cw: Int, ch: Int, dst: FloatArray, off: Int) {
        val local = ts - clip.startUs
        fun tv(id: String) = clip.transform.at(id, local, TransformSpec.def(id)).toDouble()
        var px = tv("px")
        var py = tv("py")
        var scale = tv("scale")
        var rot = tv("rot")
        val ax = tv("ax")
        val ay = tv("ay")

        // Follow another clip's motion track.
        clip.follow?.let { f ->
            val tc = project.clip(f.clipId)
            val td = tc?.tracking
            val ta = tc?.let { project.asset(it.assetId) }
            if (tc != null && td != null && td.samples.isNotEmpty() && ta != null) {
                val now = td.at(tc.sourceTimeAt(ts.coerceIn(tc.startUs, tc.endUs - 1)))
                val ref = td.at(tc.sourceTimeAt(f.refTimelineUs.coerceIn(tc.startUs, tc.endUs - 1)))
                if (now != null && ref != null) {
                    val sw = ta.displayWidth.coerceAtLeast(1).toDouble()
                    val sh = ta.displayHeight.coerceAtLeast(1).toDouble()
                    val fit = min(cw / sw, ch / sh)
                    if (f.position) {
                        px += (now.x - ref.x) * sw * fit / cw
                        py += (now.y - ref.y) * sh * fit / ch
                    }
                    if (f.scale) scale *= (now.scale / ref.scale.coerceAtLeast(0.01f)).toDouble()
                    if (f.rotation) rot += (now.rot - ref.rot).toDouble()
                }
            }
        }

        // Stabilization (inner correction of the content).
        var sdx = 0.0
        var sdy = 0.0
        var srot = 0.0
        var sscale = 1.0
        clip.stab?.takeIf { it.enabled }?.let { st ->
            st.at(clip.sourceTimeAt(ts))?.let { s ->
                sdx = s.dx.toDouble(); sdy = s.dy.toDouble(); srot = s.rot.toDouble(); sscale = s.scale.toDouble()
            }
            sscale *= st.zoom.toDouble()
        }

        val sx = scale * (if (clip.flipH) -1.0 else 1.0)
        val sy = scale * (if (clip.flipV) -1.0 else 1.0)
        val th = Math.toRadians(rot)
        val c = cos(th)
        val s = sin(th)
        val sth = Math.toRadians(srot)
        val sc = cos(sth)
        val ss = sin(sth)
        val cx = cw / 2.0 + px * cw
        val cy = ch / 2.0 + py * ch

        // Row-major matrices, composed right-to-left.
        var m = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, ch.toDouble(), 0.0, 0.0, 1.0) // F: GL px → top-left px
        m = mul(doubleArrayOf(1.0, 0.0, -cx, 0.0, 1.0, -cy, 0.0, 0.0, 1.0), m)        // T(-(C+pos))
        m = mul(doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0), m)              // R⁻¹
        m = mul(doubleArrayOf(1.0 / nz(sx), 0.0, 0.0, 0.0, 1.0 / nz(sy), 0.0, 0.0, 0.0, 1.0), m) // S⁻¹
        m = mul(doubleArrayOf(1.0, 0.0, -sdx * baseW, 0.0, 1.0, -sdy * baseH, 0.0, 0.0, 1.0), m) // stab offset
        m = mul(doubleArrayOf(sc, ss, 0.0, -ss, sc, 0.0, 0.0, 0.0, 1.0), m)            // stab R⁻¹
        m = mul(doubleArrayOf(1.0 / nz(sscale), 0.0, 0.0, 0.0, 1.0 / nz(sscale), 0.0, 0.0, 0.0, 1.0), m)
        m = mul(doubleArrayOf(1.0, 0.0, ax * baseW, 0.0, 1.0, ay * baseH, 0.0, 0.0, 1.0), m) // + anchor
        m = mul(doubleArrayOf(1.0 / baseW, 0.0, 0.0, 0.0, -1.0 / baseH, 1.0, 0.0, 0.0, 1.0), m) // → uv (GL)
        // Column-major output
        for (r in 0 until 3) for (col in 0 until 3) dst[off + col * 3 + r] = m[r * 3 + col].toFloat()
    }


    private fun nz(v: Double) = if (kotlin.math.abs(v) < 1e-6) 1e-6 else v

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }


}
