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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A decoded video frame living in an external OES texture. [srcW]×[srcH] is the frame's
 * upright size when it differs from the asset's (e.g. a 960-px preview proxy); 0 = asset size.
 */
class VideoFrame(val oesTex: Int, val texMatrix: FloatArray, val srcW: Int = 0, val srcH: Int = 0)

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
        /** Viewer zoom & pan for the editor (zoom, panX, panY as fractions of the view, y down). */
        view: FloatArray = NO_VIEW,
    ) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        var canvas = pool.obtain(cw, ch)
        var spare = pool.obtain(cw, ch)
        val fill = project.settings.fill
        when {
            fill.mode == com.amiri.cut.core.model.FillMode.COLOR -> {
                val c = fill.color
                canvas.clear(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, 1f)
            }
            fill.mode == com.amiri.cut.core.model.FillMode.BLUR -> {
                canvas.clear(0f, 0f, 0f, 1f)
                drawBlurFill(project, t, cw, ch, src, opt, canvas, fill.blur)
            }
            project.settings.background == CanvasBackground.BLACK -> canvas.clear(0f, 0f, 0f, 1f)
            else -> canvas.clear()
        }

        for (track in project.tracks.asReversed()) {
            if (track.hidden || track.kind == TrackKind.AUDIO) continue
            val ts = com.amiri.cut.core.model.Transitions.at(track, t)
            if (ts != null && ts.b.kind != ClipKind.ADJUSTMENT && ts.a?.kind != ClipKind.ADJUSTMENT) {
                val out = renderTransition(project, ts, t, cw, ch, src, opt, canvas, spare)
                if (out != null) { val tmp = canvas; canvas = spare; spare = tmp; continue }
            }
            val clip = track.clipAt(t) ?: continue
            if (clip.kind == ClipKind.ADJUSTMENT) {
                val out = applyAdjustment(clip, canvas, t, src, opt)
                if (out !== canvas) { pool.recycle(canvas); canvas = out }
                continue
            }
            val layer = buildLayer(project, clip, t, cw, ch, src, opt) ?: continue
            if (clip.kind == ClipKind.TEXT && clip.text?.glass?.let { it != "None" } == true) {
                if (glassPass(project, clip, layer, t, cw, ch, canvas, spare)) { val tmp = canvas; canvas = spare; spare = tmp }
            }
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
        p.f3("uView", view[0], view[1], view[2])
        p.draw()
        pool.recycle(canvas)
        pool.recycle(spare)
        pool.endFrame()
    }

    /**
     * Canvas "blur" background: the bottom-most picture at [t], scaled to cover the frame
     * and heavily blurred, so a landscape clip in a vertical frame has no black bars.
     */
    private fun drawBlurFill(project: Project, t: Long, cw: Int, ch: Int, src: FrameSources, opt: RenderOptions, canvas: Fbo, amount: Float) {
        var clip: Clip? = null
        for (track in project.tracks.asReversed()) {
            if (track.hidden || track.kind == TrackKind.AUDIO || track.kind == TrackKind.TEXT) continue
            val c = track.clipAt(t) ?: continue
            if (c.kind != ClipKind.MEDIA) continue
            val a = project.asset(c.assetId) ?: continue
            if (a.type == MediaType.AUDIO) continue
            clip = c; break
        }
        val c = clip ?: return
        val layer = buildLayer(project, c, t, cw, ch, src, opt, resScale = 0.5f) ?: return
        val k = max(cw / layer.baseW, ch / layer.baseH)
        val sx = cw / (layer.baseW * k)
        val sy = ch / (layer.baseH * k)
        val sw = max(2, cw / 4); val sh = max(2, ch / 4)
        val small = pool.obtain(sw, sh)
        small.bind()
        prog("cover", Shaders.COVER).apply { use(); tex("uTex", 0, layer.fbo.tex); f2("uScale", sx, sy); f1("uDim", 0.82f); draw() }
        pool.recycle(layer.fbo)
        val blurred = blur(small, (6f + 26f * amount.coerceIn(0f, 1f)))
        pool.recycle(small)
        canvas.bind()
        prog("copy", Shaders.COPY).apply { use(); tex("uTex", 0, blurred.tex); draw() }
        pool.recycle(blurred)
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

    /**
     * A built layer: [baseW]×[baseH] is its fitted size in canvas pixels (what transforms act
     * on); its pixels are stored at [q]× that, and it is shown at [s]× (display scale).
     */
    private class Layer(val fbo: Fbo, val baseW: Float, val baseH: Float, val q: Float = 1f, val s: Float = 1f)

    private val qInv = FloatArray(9)

    /**
     * How large the clip appears at [t], relative to its fitted size, along its most enlarged
     * axis (1 = as fitted, 2 = zoomed in 2×, 0.4 = a small picture-in-picture).
     */
    private fun displayScale(project: Project, clip: Clip, t: Long, baseW: Float, baseH: Float, cw: Int, ch: Int): Float {
        LayerMath.inverse(project, clip, t, baseW, baseH, cw, ch, qInv, 0)
        // Jacobian canvas px → base px (column-major: [0]=du/dx, [1]=dv/dx, [3]=du/dy, [4]=dv/dy).
        val a = qInv[0] * baseW; val b = qInv[3] * baseW
        val c = qInv[1] * baseH; val d = qInv[4] * baseH
        val s1 = a * a + b * b + c * c + d * d
        val det = abs(a * d - b * c)
        val disc = kotlin.math.sqrt(max(0f, s1 * s1 - 4f * det * det))
        val sigMin = kotlin.math.sqrt(max(1e-12f, (s1 - disc) / 2f))
        val s = 1f / sigMin
        return if (s.isFinite()) s.coerceIn(0.01f, 64f) else 1f
    }

    /**
     * Pixel density of a media layer: follows the display scale so a zoomed-in clip keeps the
     * source's real detail (up to its native resolution and a GPU budget of 2.25× the canvas),
     * and a shrunken one is filtered once, cleanly, from the source. Quantized to quarter
     * octaves so animated zooms reuse a handful of buffer sizes.
     */
    private fun layerDensity(s: Float, srcW: Float, srcH: Float, baseW: Float, baseH: Float, cw: Int, ch: Int): Float {
        val native = max(1f, min(srcW / baseW, srcH / baseH))
        val budget = kotlin.math.sqrt(2.25f * cw * ch / max(1f, baseW * baseH))
        val qMax = minOf(native, budget, 4096f / baseW, 4096f / baseH).coerceAtLeast(0.25f)
        val q = s.coerceIn(0.25f, qMax)
        // Round up (never store fewer pixels than shown), ignoring the last ~2.5 % of a step.
        val steps = kotlin.math.ceil(kotlin.math.ln(q) / kotlin.math.ln(2f) * 4f - 0.15f).toInt()
        return Math.pow(2.0, steps / 4.0).toFloat().coerceIn(0.25f, max(0.25f, qMax))
    }

    private fun buildLayer(
        project: Project, clip: Clip, t: Long, cw: Int, ch: Int, src: FrameSources, opt: RenderOptions,
        /** Fixed pixel density instead of the adaptive one (e.g. 0.5 for the blurred fill). */
        resScale: Float? = null,
    ): Layer? {
        val local = t - clip.startUs
        val srcUs = clip.sourceTimeAt(t)
        var oes = false
        val texId: Int
        var texMatrix: FloatArray? = null
        val sw: Float
        val sh: Float
        val baseW: Float
        val baseH: Float
        // Real pixel size of the texture being sampled (upright), for resampling.
        var pxW: Float
        var pxH: Float

        if (clip.kind == ClipKind.SHAPE) {
            val spec = clip.shape ?: return null
            val key = ShapeRenderer.key(spec, local, cw, ch)
            val c = textTex.getOrPut(clip.id) { Cached(0, null) }
            if (c.key != key) {
                val bmp = ShapeRenderer.render(spec, local, cw, ch)
                c.tex = Gl.uploadBitmap(bmp, c.tex)
                c.w = bmp.width; c.h = bmp.height; c.key = key
                bmp.recycle()
            }
            texId = c.tex
            sw = c.w.toFloat(); sh = c.h.toFloat()
            baseW = sw; baseH = sh
            pxW = sw; pxH = sh
        } else if (clip.kind == ClipKind.TEXT) {
            val spec = clip.text ?: return null
            val key = text.key(spec, local, cw, ch, clip.durationUs)
            val c = textTex.getOrPut(clip.id) { Cached(0, null) }
            if (c.key != key) {
                val bmp = text.render(spec, local, cw, ch, clip.durationUs)
                c.tex = Gl.uploadBitmap(bmp, c.tex)
                c.w = bmp.width; c.h = bmp.height; c.key = key
                bmp.recycle()
            }
            texId = c.tex
            sw = c.w.toFloat(); sh = c.h.toFloat()
            baseW = sw; baseH = sh
            pxW = sw; pxH = sh
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
                    pxW = if (vf.srcW > 0) vf.srcW.toFloat() else sw
                    pxH = if (vf.srcH > 0) vf.srcH.toFloat() else sh
                }
                MediaType.IMAGE -> {
                    val bmp = src.image(asset) ?: return null
                    val c = imageTex.getOrPut(asset.id) { Cached(0, null) }
                    // Weak key: the texture cache must not keep decoded bitmaps alive.
                    if ((c.key as? java.lang.ref.WeakReference<*>)?.get() !== bmp) {
                        c.tex = Gl.uploadBitmap(bmp, c.tex); c.key = java.lang.ref.WeakReference(bmp); c.w = bmp.width; c.h = bmp.height
                    }
                    evict(imageTex, 16)
                    texId = c.tex
                    sw = c.w.toFloat(); sh = c.h.toFloat()
                    pxW = sw; pxH = sh
                }
                MediaType.AUDIO -> return null
            }
            val fit = min(cw / sw, ch / sh)
            baseW = sw * fit
            baseH = sh * fit
        }

        val media = clip.kind != ClipKind.TEXT && clip.kind != ClipKind.SHAPE
        val shown = if (resScale == null) displayScale(project, clip, t, baseW, baseH, cw, ch) else 1f
        val q = resScale ?: if (media) layerDensity(shown, pxW, pxH, baseW, baseH, cw, ch) else 1f
        val lw = (baseW * q).roundToInt().coerceIn(2, 4096)
        val lh = (baseH * q).roundToInt().coerceIn(2, 4096)
        val l0 = pool.obtain(lw, lh)
        l0.bind()
        val p = if (oes) prog("in_oes", Shaders.INPUT_OES) else prog("in_2d", Shaders.INPUT_2D)
        p.use()
        p.tex("uTex", 0, texId, oes)
        if (oes) p.mat4("uTexMatrix", texMatrix!!)
        setResampleUniforms(p, oes, texMatrix, pxW, pxH, lw, lh, media)
        setMaskUniforms(p, clip, local, srcUs, sw / sh, src, opt)
        p.draw()

        var cur = l0
        for (e in clip.effects) {
            if (!e.enabled) continue
            if (opt.bypassColorClipId == clip.id && EffectCatalog.spec(e.type)?.category == com.amiri.cut.core.effects.EffectCategory.COLOR) continue
            cur = applyEffect(e, cur, local, src, clip, cw, ch)
        }
        return Layer(cur, baseW, baseH, lw / baseW, shown)
    }

    /**
     * Input-pass resampling: supersample when the source has more pixels than the layer
     * (taps per axis ≈ source pixels per layer pixel), bicubic when it has fewer.
     */
    private fun setResampleUniforms(p: Program, oes: Boolean, m: FloatArray?, pxW: Float, pxH: Float, lw: Int, lh: Int, media: Boolean) {
        val rx = pxW / lw
        val ry = pxH / lh
        val tx = if (rx > 1.02f) kotlin.math.ceil(rx - 0.02f).coerceIn(1f, 6f) else 1f
        val ty = if (ry > 1.02f) kotlin.math.ceil(ry - 0.02f).coerceIn(1f, 6f) else 1f
        p.f2("uTaps", tx, ty)
        p.f2("uPx", 1f / lw, 1f / lh)
        p.f1("uCubic", if (media && rx < 0.98f && ry < 0.98f) 1f else 0f)
        if (oes && m != null) {
            // One source pixel along each texture axis, through the (possibly rotated/cropped) matrix.
            p.f2("uTexel", abs(m[0]) / pxW + abs(m[4]) / pxH, abs(m[1]) / pxW + abs(m[5]) / pxH)
            val xs = floatArrayOf(m[12], m[0] + m[12], m[4] + m[12], m[0] + m[4] + m[12])
            val ys = floatArrayOf(m[13], m[1] + m[13], m[5] + m[13], m[1] + m[5] + m[13])
            p.f4("uTcBox", xs.min(), ys.min(), xs.max(), ys.max())
        } else {
            p.f2("uTexel", 1f / pxW, 1f / pxH)
            p.f4("uTcBox", 0f, 0f, 1f, 1f)
        }
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
                if ((c.key as? java.lang.ref.WeakReference<*>)?.get() !== bmp) { c.tex = Gl.uploadBitmap(bmp, c.tex); c.key = java.lang.ref.WeakReference(bmp) }
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
        masks.forEachIndexed { i, m ->
            fun v(id: String) = m.props.at(id, local, MaskSpec.def(id))
            var x = v("x")
            var y = v("y")
            var rot = v("rot")
            var sx = 1f
            val mm = com.amiri.cut.core.model.MaskMotion.apply(m, clip.tracking, srcUs, x, y, aspect)
            x = mm[0]; y = mm[1]; rot += mm[2]; sx = mm[3]
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

    private val animXf = com.amiri.cut.core.anim.ClipXf()

    private fun composite(project: Project, clip: Clip, layer: Layer, t: Long, canvas: Fbo, out: Fbo, cw: Int, ch: Int) {
        val local = t - clip.startUs
        fun tv(id: String, at: Long = local) = clip.transform.at(id, at, TransformSpec.def(id))
        val mbAmount = tv("mbAmount")
        val moving = clip.transform.animated || clip.follow != null || clip.stab != null || clip.effects.any { it.enabled && it.type == "wiggle" }
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
        val textAlpha = clip.text?.let { com.amiri.cut.core.text.TextAnims.layerXf(it, local, clip.durationUs).alpha } ?: 1f
        val animAlpha = if (com.amiri.cut.core.anim.ClipAnims.active(clip.anim)) com.amiri.cut.core.anim.ClipAnims.xf(clip.anim, local, clip.durationUs, animXf).alpha else 1f
        p.f1("uOpacity", (tv("opacity") * textAlpha * animAlpha).coerceIn(0f, 1f))
        p.i1("uBlend", clip.blend.ordinal)
        // Shown larger than its stored pixels (zoom past the source/budget, scaled text): bicubic.
        p.f1("uCubic", if (samples == 1 && layer.s / layer.q > 1.1f) 1f else 0f)
        p.f2("uLayerTexel", 1f / layer.fbo.width, 1f / layer.fbo.height)
        p.draw()
    }

    /**
     * Renders both sides of a transition onto transparent canvases (each with its own
     * transform, blend and opacity), mixes them with the transition shader and lays the
     * result over [canvas] into [out]. Returns null if the transition type is unknown.
     */
    private fun renderTransition(
        project: Project, ts: com.amiri.cut.core.model.TransState, t: Long, cw: Int, ch: Int,
        src: FrameSources, opt: RenderOptions, canvas: Fbo, out: Fbo,
    ): Fbo? {
        val spec = com.amiri.cut.core.effects.TransitionCatalog.spec(ts.tr.type) ?: return null
        fun side(c: Clip?): Fbo {
            val dst = pool.obtain(cw, ch)
            dst.clear()
            if (c == null) return dst
            val layer = buildLayer(project, c, t, cw, ch, src, opt) ?: return dst
            val empty = pool.obtain(cw, ch)
            empty.clear()
            composite(project, c, layer, t, empty, dst, cw, ch)
            pool.recycle(empty)
            pool.recycle(layer.fbo)
            return dst
        }
        val fa = side(ts.a)
        val fb = side(ts.b)
        out.bind()
        val p = prog("transition", Shaders.TRANSITION)
        p.use()
        p.tex("uBase", 0, canvas.tex)
        p.tex("uA", 1, fa.tex)
        p.tex("uB", 2, fb.tex)
        p.f1("uP", ts.progress)
        p.i1("uType", spec.index)
        val d = when (ts.tr.dir) { 1 -> floatArrayOf(-1f, 0f); 2 -> floatArrayOf(0f, -1f); 3 -> floatArrayOf(0f, 1f); else -> floatArrayOf(1f, 0f) }
        p.f2("uDir", d[0], d[1])
        p.f1("uAspect", cw.toFloat() / ch)
        p.f1("uSoft", ts.tr.softness)
        p.f2("uTexel", 1f / cw, 1f / ch)
        p.draw()
        pool.recycle(fa); pool.recycle(fb)
        return out
    }

    private val glassState = com.amiri.cut.core.text.Glass.State()
    private val glassInv = FloatArray(9)

    /** Draws the text's liquid-glass panel over [canvas] into [out]. */
    private fun glassPass(project: Project, clip: Clip, layer: Layer, t: Long, cw: Int, ch: Int, canvas: Fbo, out: Fbo): Boolean {
        val spec = clip.text ?: return false
        val local = t - clip.startUs
        fun g(id: String) = spec.props.at(id, local, com.amiri.cut.core.text.Glass.def(id))
        val st = com.amiri.cut.core.text.Glass.state(spec, local, clip.durationUs, glassState)
        val animA = if (com.amiri.cut.core.anim.ClipAnims.active(clip.anim)) com.amiri.cut.core.anim.ClipAnims.xf(clip.anim, local, clip.durationUs).alpha else 1f
        val op = g("gOpacity") * st.alpha * animA * clip.transform.at("opacity", local, 1f).coerceIn(0f, 1f)
        if (op <= 0.003f) return false
        val pn = text.panel(spec, local, cw, ch)
        LayerMath.inverse(project, clip, t, layer.baseW, layer.baseH, cw, ch, glassInv, 0, textAnim = false)
        val maxDim = max(cw, ch).toFloat()
        val blurAmt = g("gBlur") * st.blur
        val bl = if (blurAmt > 0.01f) blur(canvas, 2f + blurAmt * 0.035f * maxDim) else null
        out.bind()
        val p = prog("glass", Shaders.GLASS)
        p.use()
        p.tex("uDst", 0, canvas.tex)
        p.tex("uBlur", 1, bl?.tex ?: canvas.tex)
        p.mat3v("uInv", glassInv, 1)
        p.f2("uLayer", layer.baseW, layer.baseH)
        p.f4("uRect", pn[0], pn[1] + st.dy * pn[5], pn[2] * st.sx, pn[3] * st.sy)
        p.f1("uRadius", pn[4] * st.radius)
        p.f2("uCanvas", cw.toFloat(), ch.toFloat())
        p.f1("uRefract", g("gRefract")); p.f1("uChroma", g("gChroma")); p.f1("uRim", g("gRim")); p.f1("uGloss", g("gGloss"))
        p.f1("uTintA", g("gTintA")); p.f3("uTint", g("gtr"), g("gtg"), g("gtb"))
        p.f1("uSat", g("gSat")); p.f1("uBright", g("gBright")); p.f1("uShadow", g("gShadow"))
        p.f1("uOpacity", op.coerceIn(0f, 1f)); p.f1("uBlurMix", if (bl != null) 1f else 0f)
        p.f1("uWobble", st.wobble); p.f1("uTime", local / 1_000_000f)
        p.draw()
        bl?.let { pool.recycle(it) }
        return true
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
            work = applyEffect(e, work, local, src, clip, canvas.width, canvas.height)
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

    private fun applyEffect(e: Effect, input: Fbo, local: Long, src: FrameSources, clip: Clip, cw: Int, ch: Int): Fbo {
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
                // A chosen look adds its offsets (× intensity) on top of the user's values.
                val look = com.amiri.cut.core.effects.ColorLooks.look(e.opts["look"])
                val amt = if (look != null) v("lookAmt") else 0f
                fun v(id: String): Float {
                    val base = e.props.at(id, local, spec.param(id)?.default ?: 0f)
                    val lv = look?.get(id) ?: return base
                    if (id.endsWith("_hue")) {
                        val satId = id.removeSuffix("_hue") + "_sat"
                        return if (e.props.at(satId, local, 0f) == 0f) lv else base
                    }
                    return base + lv * amt
                }
                val blurAmt = v("blur")
                val clar = v("clarity")
                val clarTex = if (abs(clar) > 0.001f) blur(input, 2f + 0.012f * maxDim) else null
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
                    p.tex("uClarTex", 3, clarTex?.tex ?: input.tex)
                    p.f1("uClarity", clar); p.f1("uDehaze", v("dehaze")); p.f1("uVibrance", v("vibrance")); p.f1("uFade", v("fade"))
                    p.f4("uSplit", v("sh_hue"), v("sh_sat"), v("hi_hue"), v("hi_sat")); p.f1("uSplitBal", v("split_bal"))
                }
                blurred?.let { pool.recycle(it) }
                clarTex?.let { pool.recycle(it) }
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
            "ccsweep" -> run("ccsweep", Shaders.CC_SWEEP) { p ->
                p.f2("uCenter", v("cx"), 1f - v("cy")); p.f1("uDir", Math.toRadians(v("direction").toDouble()).toFloat())
                p.f1("uWidth", v("width")); p.f1("uIntensity", v("intensity")); p.f1("uEdgeI", v("edgeIntensity"))
                p.f1("uEdgeT", v("edgeThickness")); p.f3("uColor", v("cr"), v("cg"), v("cb")); p.f1("uAspect", aspect)
                p.f2("uTexel", 1f / w, 1f / h)
                p.i1("uShape", when (e.opts["shape"]) { "Linear" -> 0; "Sharp" -> 2; else -> 1 })
                p.i1("uRecept", when (e.opts["reception"]) { "Composite" -> 1; "Cutout" -> 2; else -> 0 })
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
                p.f1("uPhase", v("phase") / 360f)
                p.i1("uType", listOf("Sine", "Square", "Triangle", "Sawtooth", "Circle", "Semicircle", "Noise").indexOf(e.opts["type"] ?: "Sine").coerceAtLeast(0))
                p.i1("uPin", listOf("None", "All edges", "Left & right", "Top & bottom").indexOf(e.opts["pin"] ?: "None").coerceAtLeast(0))
            }
            "saber" -> {
                val core = saberCore(e, clip, local, w, h, cw, ch, ::v)
                if (core == null) { pool.recycle(out); return input }
                val spread = v("spread")
                val g1 = blur(core, 2f + (0.004f + spread * 0.02f) * maxDim)
                val g2 = blur(core, 4f + (0.02f + spread * 0.12f) * maxDim)
                val fs = v("flickerSpeed").toDouble()
                val flick = (1.0 - v("flicker") * (0.5 + 0.5 * Wiggle.noise(secs * fs, 7, 1.0))).toFloat().coerceIn(0f, 1f)
                run("saber", Shaders.SABER) { p ->
                    p.tex("uCore", 1, core.tex); p.tex("uG1", 2, g1.tex); p.tex("uG2", 3, g2.tex)
                    p.f3("uColor", v("cr"), v("cg"), v("cb")); p.f1("uGlow", v("glow")); p.f1("uCoreB", v("coreBright"))
                    p.f1("uFlick", flick); p.f1("uDist", v("distort")); p.f1("uTime", secs * v("distortSpeed")); p.f1("uAspect", aspect)
                    p.i1("uMode", if (e.opts["composite"] == "Saber only") 1 else 0)
                }
                pool.recycle(core); pool.recycle(g1); pool.recycle(g2)
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

    private val saberTex = LinkedHashMap<String, Cached>(8, 0.75f, true)

    /**
     * Rasterizes the saber core (a stroked, trimmed path in layer pixel space) and returns it
     * in an FBO (white, premultiplied). The path comes from the layer's pen path / shape
     * outline, its masks, or the straight line parameters.
     */
    private fun saberCore(e: Effect, clip: Clip, local: Long, w: Int, h: Int, cw: Int, ch: Int, v: (String) -> Float): Fbo? {
        val drawn = if (e.opts["source"] == "Drawn path") e.opts["path"]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size >= 12 } else null
        val path: Path = if (drawn != null) {
            ShapeRenderer.vertexPath(drawn, e.opts["closed"] == "true", w.toFloat(), h.toFloat())
        } else if (e.opts["source"] == "Line") {
            Path().apply { moveTo(v("x1") * w, v("y1") * h); lineTo(v("x2") * w, v("y2") * h) }
        } else {
            val spec = clip.shape
            when {
                spec != null -> ShapeRenderer.buildPath(spec, local, cw, ch).also { pth ->
                    // Shape bitmaps are rendered at canvas scale; the layer FBO may differ slightly.
                    val (bw, bh) = ShapeRenderer.measure(spec, local, cw, ch)
                    if (bw != w || bh != h) pth.transform(android.graphics.Matrix().apply { setScale(w.toFloat() / bw, h.toFloat() / bh) })
                }
                clip.masks.isNotEmpty() -> Path().also { all ->
                    for (m in clip.masks) {
                        fun mv(id: String) = m.props.at(id, local, MaskSpec.def(id))
                        val cx = mv("x") * w; val cy = mv("y") * h
                        val mw = mv("w") * w; val mh = mv("h") * h
                        val one = Path()
                        when (m.shape) {
                            MaskShape.RECT -> one.addRect(cx - mw / 2, cy - mh / 2, cx + mw / 2, cy + mh / 2, Path.Direction.CW)
                            MaskShape.ELLIPSE -> one.addOval(cx - mw / 2, cy - mh / 2, cx + mw / 2, cy + mh / 2, Path.Direction.CW)
                            MaskShape.PATH -> if (m.path.size >= 6) {
                                // Path points live in the mask's unit box.
                                one.moveTo(cx - mw / 2 + m.path[0] * mw, cy - mh / 2 + m.path[1] * mh)
                                var i = 2
                                while (i + 1 < m.path.size) { one.lineTo(cx - mw / 2 + m.path[i] * mw, cy - mh / 2 + m.path[i + 1] * mh); i += 2 }
                                one.close()
                            }
                        }
                        val rot = mv("rot")
                        if (rot != 0f) one.transform(android.graphics.Matrix().apply { setRotate(rot, cx, cy) })
                        all.addPath(one)
                    }
                }
                else -> Path().apply { moveTo(v("x1") * w, v("y1") * h); lineTo(v("x2") * w, v("y2") * h) }
            }
        }
        val trimmed = ShapeRenderer.trim(path, v("start"), v("end"), v("offset"))
        val stroke = max(1.2f, v("core") * min(w, h))
        val key = "${clip.id}|${e.id}|$w|$h|$stroke|" + android.graphics.PathMeasure(trimmed, false).length.toString() +
            "|${v("start")}|${v("end")}|${v("offset")}|${v("x1")},${v("y1")},${v("x2")},${v("y2")}|${clip.shape?.path?.hashCode()}|${clip.masks.hashCode()}|${e.opts["path"]?.hashCode()}|${e.opts["source"]}"
        val c = saberTex.getOrPut(clip.id + e.id) { Cached(0, null) }
        if (c.key != key) {
            val sc = min(1f, 2048f / max(w, h))
            val bw = max(2, (w * sc).roundToInt()); val bh = max(2, (h * sc).roundToInt())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.scale(sc, sc)
            cv.drawPath(trimmed, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            })
            c.tex = Gl.uploadBitmap(bmp, c.tex); c.key = key
            bmp.recycle()
            evict(saberTex, 8)
        }
        val fb = pool.obtain(w, h)
        fb.bind()
        prog("copy_flip", Shaders.COPY_FLIP).apply { use(); tex("uTex", 0, c.tex); draw() }
        return fb
    }

    fun release() {
        saberTex.values.forEach { Gl.deleteTexture(it.tex) }; saberTex.clear()
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
        val NO_VIEW = floatArrayOf(1f, 0f, 0f)

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
        if (clip.kind == ClipKind.SHAPE) {
            val spec = clip.shape ?: return null
            val m = ShapeRenderer.measure(spec, t - clip.startUs, cw, ch)
            return m.first.toFloat() to m.second.toFloat()
        }
        val a = project.asset(clip.assetId) ?: return null
        val sw = a.displayWidth.takeIf { it > 0 }?.toFloat() ?: return null
        val sh = a.displayHeight.takeIf { it > 0 }?.toFloat() ?: return null
        val fit = min(cw / sw, ch / sh)
        return sw * fit to sh * fit
    }

    /**
     * Canvas pixel (top-left origin) of a point given in [tc]'s source uv, at timeline time
     * [ts], using [tc]'s own transform (its own follow is ignored to avoid loops).
     */
    fun trackedPoint(project: Project, tc: Clip, u: Float, v: Float, ts: Long, cw: Int, ch: Int): FloatArray? {
        val c = tc.copy(follow = null)
        val t = ts.coerceIn(c.startUs, c.endUs - 1)
        val (bw, bh) = baseSize(project, c, t, cw, ch, null) ?: return null
        return Affine.forward(inverseOf(project, c, t, bw, bh, cw, ch), u, v, ch)
    }

    /** Canvas-pixel (GL origin) → layer-uv (GL origin) affine for the clip at [ts], column-major. */
    fun inverseOf(project: Project, clip: Clip, ts: Long, baseW: Float, baseH: Float, cw: Int, ch: Int): FloatArray =
        FloatArray(9).also { inverse(project, clip, ts, baseW, baseH, cw, ch, it, 0) }

    /**
     * Writes the canvas-pixel → layer-uv affine map (column-major mat3) for [clip] at
     * timeline time [ts] into [dst] at [off]. Includes stabilization and track follow.
     */
    fun inverse(project: Project, clip: Clip, ts: Long, baseW: Float, baseH: Float, cw: Int, ch: Int, dst: FloatArray, off: Int, textAnim: Boolean = true) {
        val local = ts - clip.startUs
        fun tv(id: String) = clip.transform.at(id, local, TransformSpec.def(id)).toDouble()
        var px = tv("px")
        var py = tv("py")
        var scale = tv("scale")
        var rot = tv("rot")
        val ax = tv("ax")
        val ay = tv("ay")

        // Wiggle Position (motion effects).
        for (e in clip.effects) {
            if (!e.enabled || e.type != "wiggle") continue
            fun ev(id: String) = e.props.at(id, local, EffectCatalog.WIGGLE.param(id)?.default ?: 0f).toDouble()
            val x = local / 1_000_000.0 * ev("freq")
            val seed = ev("seed").toInt()
            val det = ev("detail")
            val axes = e.opts["axes"] ?: "X & Y"
            val amp = ev("amp")
            if (axes != "Y only") px += Wiggle.noise(x, seed, det) * amp
            if (axes != "X only") py += Wiggle.noise(x, seed + 101, det) * amp
            rot += Wiggle.noise(x, seed + 202, det) * ev("rotAmp")
            scale *= 1.0 + Wiggle.noise(x, seed + 303, det) * ev("scaleAmp")
        }

        // Whole-text animations (slide, zoom, pop, loops…).
        var tsx = 1.0
        var tsy = 1.0
        if (textAnim) clip.text?.let { sp ->
            if (com.amiri.cut.core.text.TextAnims.animated(sp)) {
                val ax = com.amiri.cut.core.text.TextAnims.layerXf(sp, local, clip.durationUs)
                val em = sp.props.at("size", local, com.amiri.cut.core.effects.TextSpecDefaults.def("size")) * ch * scale
                px += ax.dx * em / cw
                py += ax.dy * em / ch
                tsx = ax.sx.toDouble(); tsy = ax.sy.toDouble()
                rot += ax.rot
            }
        }

        // Clip animations (In / Out / Combo): same scale/rotation pivot as the layer.
        if (com.amiri.cut.core.anim.ClipAnims.active(clip.anim)) {
            val ax2 = com.amiri.cut.core.anim.ClipAnims.xf(clip.anim, local, clip.durationUs)
            px += ax2.dx; py += ax2.dy
            tsx *= ax2.sx.toDouble(); tsy *= ax2.sy.toDouble()
            rot += ax2.rot
        }

        // Follow another clip's motion track.
        clip.follow?.let { f ->
            val tc = project.clip(f.clipId)
            val td = tc?.tracking
            val ta = tc?.let { project.asset(it.assetId) }
            if (tc != null && td != null && td.samples.isNotEmpty() && ta != null) {
                val now = td.at(tc.sourceTimeAt(ts.coerceIn(tc.startUs, tc.endUs - 1)))
                val ref = td.at(tc.sourceTimeAt(f.refTimelineUs.coerceIn(tc.startUs, tc.endUs - 1)))
                if (now != null && ref != null) {
                    if (f.position) {
                        // Tracked point on the canvas (the tracked clip's own position/zoom included).
                        val pn = trackedPoint(project, tc, now.x, now.y, ts, cw, ch)
                        val pr = trackedPoint(project, tc, ref.x, ref.y, f.refTimelineUs, cw, ch)
                        if (pn != null && pr != null) {
                            px += (pn[0] - pr[0]) / cw
                            py += (pn[1] - pr[1]) / ch
                        } else {
                            val sw = ta.displayWidth.coerceAtLeast(1).toDouble()
                            val sh = ta.displayHeight.coerceAtLeast(1).toDouble()
                            val fit = min(cw / sw, ch / sh)
                            px += (now.x - ref.x) * sw * fit / cw
                            py += (now.y - ref.y) * sh * fit / ch
                        }
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

        val sx = scale * tsx * (if (clip.flipH) -1.0 else 1.0)
        val sy = scale * tsy * (if (clip.flipV) -1.0 else 1.0)
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

/** Smooth deterministic 1D noise for Wiggle (value noise + optional octave), range ≈ −1..1. */
object Wiggle {
    private fun hash(i: Long, seed: Int): Double {
        var x = i * 374761393L + seed * 668265263L
        x = (x xor (x ushr 13)) * 1274126177L
        x = x xor (x ushr 16)
        return ((x and 0xFFFFFF).toDouble() / 0xFFFFFF) * 2.0 - 1.0
    }

    private fun value(x: Double, seed: Int): Double {
        val i = Math.floor(x).toLong()
        val f = x - i
        val u = f * f * (3 - 2 * f)
        return hash(i, seed) + (hash(i + 1, seed) - hash(i, seed)) * u
    }

    fun noise(x: Double, seed: Int, detail: Double): Double {
        val a = value(x, seed)
        val b = value(x * 2.0 + 13.7, seed + 31) * 0.5
        return (a + b * detail) / (1.0 + 0.5 * detail)
    }
}
