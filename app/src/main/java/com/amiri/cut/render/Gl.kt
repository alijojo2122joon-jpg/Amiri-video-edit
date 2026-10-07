package com.amiri.cut.render

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small OpenGL ES 2.0 toolkit used by the compositor (preview and export share it). */
object Gl {
    private const val TAG = "AmiriGl"

    fun check(op: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) Log.w(TAG, "$op: glError 0x${Integer.toHexString(e)}")
    }

    fun createTexture2D(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        params2D(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    fun params2D(target: Int, linear: Boolean = true) {
        val f = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, f)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, f)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        params2D(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return ids[0]
    }

    /** Uploads a bitmap (row 0 → t = 0, i.e. image top at v = 0). Returns the texture id. */
    fun uploadBitmap(bmp: Bitmap, existing: Int = 0): Int {
        val id = if (existing != 0) existing else IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        params2D(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return id
    }

    /** Uploads raw RGBA bytes (no premultiplication). */
    fun uploadRgba(bytes: ByteArray, w: Int, h: Int, existing: Int = 0): Int {
        val id = if (existing != 0) existing else IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        params2D(GLES20.GL_TEXTURE_2D)
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes).position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        return id
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
}

/** Off-screen render target (RGBA8 texture). */
class Fbo(val width: Int, val height: Int) {
    val tex: Int = Gl.createTexture2D(width, height)
    val fbo: Int

    init {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
    }

    fun clear(r: Float = 0f, g: Float = 0f, b: Float = 0f, a: Float = 0f) {
        bind()
        GLES20.glClearColor(r, g, b, a)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        Gl.deleteTexture(tex)
    }
}

/** Reuses FBOs by size so per-frame passes don't allocate. */
class FboPool {
    private val free = HashMap<Long, ArrayDeque<Fbo>>()
    private val all = ArrayList<Fbo>()
    private val idleSince = HashMap<Fbo, Int>()
    private var frame = 0

    private fun key(w: Int, h: Int) = (w.toLong() shl 32) or h.toLong()

    fun obtain(w: Int, h: Int): Fbo {
        val q = free[key(w, h)]
        val f = q?.removeLastOrNull()
        if (f != null) { idleSince.remove(f); return f }
        return Fbo(w, h).also { all += it }
    }

    fun recycle(f: Fbo) {
        free.getOrPut(key(f.width, f.height)) { ArrayDeque() }.addLast(f)
        idleSince[f] = frame
    }

    /**
     * Call once per rendered frame. Frees buffers that sat unused for [idleFrames] frames, so
     * sizes that come and go (zoom animations, viewer pinch-zoom) don't pile up GPU memory.
     */
    fun endFrame(idleFrames: Int = 90) {
        frame++
        if (frame % 15 != 0) return
        val it = free.values.iterator()
        while (it.hasNext()) {
            val q = it.next()
            val stale = q.filter { f -> frame - (idleSince[f] ?: frame) > idleFrames }
            if (stale.isEmpty()) continue
            stale.forEach { f -> q.remove(f); idleSince.remove(f); all.remove(f); f.release() }
            if (q.isEmpty()) it.remove()
        }
    }

    /** Frees FBOs not of the given sizes (call when the canvas size changes). */
    fun trim(maxCount: Int = 48) {
        if (all.size <= maxCount) return
        free.values.forEach { q -> q.forEach { it.release(); all.remove(it); idleSince.remove(it) }; q.clear() }
    }

    fun releaseAll() {
        all.forEach { it.release() }
        all.clear()
        free.clear()
        idleSince.clear()
    }
}

/** A linked GLSL program with cached uniform locations and a full-screen quad. */
class Program(vertex: String, fragment: String) {
    val id: Int
    private val locs = HashMap<String, Int>()
    private val aPos: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        aPos = GLES20.glGetAttribLocation(id, "aPos")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log\n$src")
        }
        return s
    }

    fun loc(name: String): Int = locs.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun use() = GLES20.glUseProgram(id)

    fun f1(n: String, v: Float) { val l = loc(n); if (l >= 0) GLES20.glUniform1f(l, v) }
    fun f2(n: String, a: Float, b: Float) { val l = loc(n); if (l >= 0) GLES20.glUniform2f(l, a, b) }
    fun f3(n: String, a: Float, b: Float, c: Float) { val l = loc(n); if (l >= 0) GLES20.glUniform3f(l, a, b, c) }
    fun f4(n: String, a: Float, b: Float, c: Float, d: Float) { val l = loc(n); if (l >= 0) GLES20.glUniform4f(l, a, b, c, d) }
    fun i1(n: String, v: Int) { val l = loc(n); if (l >= 0) GLES20.glUniform1i(l, v) }
    fun f1v(n: String, v: FloatArray, count: Int) { val l = loc(n); if (l >= 0) GLES20.glUniform1fv(l, count, v, 0) }
    fun f4v(n: String, v: FloatArray, count: Int) { val l = loc(n); if (l >= 0) GLES20.glUniform4fv(l, count, v, 0) }
    fun mat3v(n: String, v: FloatArray, count: Int) { val l = loc(n); if (l >= 0) GLES20.glUniformMatrix3fv(l, count, false, v, 0) }
    fun mat4(n: String, v: FloatArray) { val l = loc(n); if (l >= 0) GLES20.glUniformMatrix4fv(l, 1, false, v, 0) }

    fun tex(n: String, unit: Int, texId: Int, oes: Boolean = false) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texId)
        i1(n, unit)
    }

    /** Draws the full-screen quad into the currently bound framebuffer. */
    fun draw() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun release() = GLES20.glDeleteProgram(id)

    companion object {
        val QUAD: FloatBuffer = Gl.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    }
}
