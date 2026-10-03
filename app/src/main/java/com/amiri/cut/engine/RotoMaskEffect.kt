package com.amiri.cut.engine

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * State shared between the UI/engine (main thread) and the GL thread.
 * Bitmaps published here are never mutated afterwards.
 */
class RotoRuntime {
    @Volatile var mask: Bitmap? = null
    @Volatile var maskVersion: Int = 0
    @Volatile var invert: Boolean = false
    /** Show the untouched frame (while painting with "Result" off). */
    @Volatile var bypass: Boolean = false
    /** Display rotation of the active source (0/90/180/270), for mapping mask coords. */
    @Volatile var rotation: Int = 0

    fun publish(newMask: Bitmap?) {
        if (newMask !== mask) {
            mask = newMask
            maskVersion++
        }
    }
}

/**
 * GPU rotoscope mask: keeps the masked area of the frame, removes the rest
 * (premultiplied alpha → shows the canvas background). Implemented as a Media3
 * [GlEffect] so the same effect runs in ExoPlayer preview and Transformer export.
 */
@OptIn(UnstableApi::class)
class RotoMaskEffect(private val runtime: RotoRuntime) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        RotoMaskShaderProgram(runtime, useHdr)
}

@OptIn(UnstableApi::class)
private class RotoMaskShaderProgram(
    private val runtime: RotoRuntime,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram
    private var maskTex = 0
    private var uploadedVersion = -1
    private var inputIsUnrotated = false

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // If the decoder hands us frames before rotation is applied, map mask coordinates.
        val rot = runtime.rotation
        inputIsUnrotated = (rot == 90 || rot == 270) && inputWidth > inputHeight
        return Size(inputWidth, inputHeight)
    }

    private fun ensureMaskTexture() {
        if (maskTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            maskTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // 1×1 white placeholder so the sampler is always valid.
            val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, white, 0)
            white.recycle()
        }
        val v = runtime.maskVersion
        if (v != uploadedVersion) {
            val m = runtime.mask
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            if (m != null && !m.isRecycled) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, m, 0)
            }
            uploadedVersion = v
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            ensureMaskTexture()
            val hasMask = runtime.mask != null && !runtime.bypass
            val rot = if (inputIsUnrotated) runtime.rotation else 0
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uMask", maskTex, 1)
            program.setFloatUniform("uHasMask", if (hasMask) 1f else 0f)
            program.setFloatUniform("uInvert", if (runtime.invert) 1f else 0f)
            program.setFloatUniform("uRotation", rot.toFloat())
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        if (maskTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0)
            maskTex = 0
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        // Mask bitmap rows are uploaded top-first (t = 0 is the top of the image),
        // while frame textures have their origin at the bottom-left.
        private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform sampler2D uMask;
uniform float uHasMask;
uniform float uInvert;
uniform float uRotation;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float u = vTexSamplingCoord.x;
  float v = vTexSamplingCoord.y;
  vec2 m = vec2(u, 1.0 - v);
  if (uRotation > 45.0 && uRotation < 135.0) { m = vec2(v, u); }
  else if (uRotation > 135.0 && uRotation < 225.0) { m = vec2(1.0 - u, v); }
  else if (uRotation > 225.0) { m = vec2(1.0 - v, 1.0 - u); }
  float a = texture2D(uMask, m).a;
  a = mix(a, 1.0 - a, uInvert);
  a = mix(1.0, a, uHasMask);
  gl_FragColor = vec4(c.rgb * a, c.a * a);
}
"""
    }
}
