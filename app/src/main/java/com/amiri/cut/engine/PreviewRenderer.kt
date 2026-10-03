package com.amiri.cut.engine

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.media.BitmapLoader
import com.amiri.cut.render.Compositor
import com.amiri.cut.render.FrameSources
import com.amiri.cut.render.Gl
import com.amiri.cut.render.LutLoader
import com.amiri.cut.render.TextRenderer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * GLSurfaceView renderer for the editor preview. Owns one SurfaceTexture per decoder
 * slot and draws [PreviewEngine.frameState] with the shared [Compositor].
 */
class PreviewRenderer(
    private val app: AmiriCutApp,
    private val projectId: String,
    private val engine: PreviewEngine,
) : GLSurfaceView.Renderer {

    var view: GLSurfaceView? = null
    private var compositor: Compositor? = null
    private val oes = IntArray(PreviewEngine.SLOTS)
    private val sts = arrayOfNulls<SurfaceTexture>(PreviewEngine.SLOTS)
    private val mats = Array(PreviewEngine.SLOTS) { FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) } }
    private val fresh = Array(PreviewEngine.SLOTS) { AtomicBoolean(false) }
    private var viewW = 1
    private var viewH = 1
    private val main = Handler(Looper.getMainLooper())

    /** Preview resolution scale (Full/Half/Quarter). */
    @Volatile var quality: Float = 1f

    /** Viewer zoom/pan: zoom, panX, panY (fractions of the view, y down). */
    @Volatile var viewXform: FloatArray = floatArrayOf(1f, 0f, 0f)

    /** When set, receives a 256-px wide RGBA copy of every 4th frame (scopes). */
    @Volatile var scopeSink: ((ByteArray, Int, Int) -> Unit)? = null
    private var frameCount = 0

    private val luts = ConcurrentHashMap<String, LutLoader.Lut>()
    private val images = ConcurrentHashMap<String, Bitmap>()
    private val loadingImages = ConcurrentHashMap.newKeySet<String>()

    private val sources = object : FrameSources {
        override fun video(clip: Clip, asset: MediaAsset): com.amiri.cut.render.VideoFrame? {
            val fs = engine.frameState ?: return null
            val slot = fs.slotOfClip[clip.id] ?: return null
            if (sts[slot] == null) return null
            return com.amiri.cut.render.VideoFrame(oes[slot], mats[slot])
        }

        override fun image(asset: MediaAsset): Bitmap? {
            images[asset.id]?.let { return it }
            if (loadingImages.add(asset.id)) {
                Thread {
                    val b = BitmapLoader.decodeImage(app, asset, 2048)
                    if (b != null) images[asset.id] = b
                    loadingImages.remove(asset.id)
                    view?.requestRender()
                }.start()
            }
            return null
        }

        override fun roto(file: String): Bitmap? = app.roto.load(projectId, file)

        override fun lut(name: String): LutLoader.Lut? =
            luts[name] ?: app.luts.file(name)?.let { LutLoader.load(it) }?.also { luts[name] = it }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        compositor?.release()
        compositor = Compositor(TextRenderer(app.fonts))
        val surfaces = ArrayList<Surface>()
        for (i in 0 until PreviewEngine.SLOTS) {
            oes[i] = Gl.createOesTexture()
            val st = SurfaceTexture(oes[i])
            st.setOnFrameAvailableListener {
                fresh[i].set(true)
                view?.requestRender()
            }
            sts[i] = st
            surfaces += Surface(st)
        }
        main.post {
            engine.onInvalidate = { view?.requestRender() }
            engine.attachSurfaces(surfaces)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        for (i in 0 until PreviewEngine.SLOTS) {
            if (fresh[i].getAndSet(false)) {
                sts[i]?.let { st ->
                    runCatching { st.updateTexImage(); st.getTransformMatrix(mats[i]) }
                }
            }
        }
        val c = compositor ?: return
        val fs = engine.frameState
        if (fs == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        val v = viewXform
        // Render more pixels when zoomed in so details stay sharp (capped for the GPU).
        val q = quality.coerceIn(0.25f, 1f) * v[0].coerceIn(1f, 3f)
        val cw = (viewW * q).roundToInt().coerceIn(2, 4096)
        val ch = (viewH * q).roundToInt().coerceIn(2, 4096)
        val sink = scopeSink
        frameCount++
        try {
            c.render(
                fs.project, fs.timeUs, cw, ch, sources, fs.options, 0, intArrayOf(0, 0, viewW, viewH),
                capture = if (sink != null && frameCount % 4 == 0) sink else null,
                view = v,
            )
        } catch (t: Throwable) {
            android.util.Log.e("AmiriPreview", "render failed", t)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0.15f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    /** Call on the GL thread (via queueEvent) when the view goes away. */
    fun releaseGl() {
        compositor?.release()
        compositor = null
        sts.forEach { it?.release() }
        for (i in oes.indices) { Gl.deleteTexture(oes[i]); oes[i] = 0; sts[i] = null }
    }

    fun clearImageCache(assetId: String? = null) {
        if (assetId == null) images.clear() else images.remove(assetId)
    }
}
