package com.amiri.cut.export

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.amiri.cut.render.Gl
import com.amiri.cut.render.VideoFrame
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Frame-accurate hardware decoder for export: decodes a video file into an OES texture
 * and can deliver the frame for any source time (seeking back to the previous sync
 * frame when needed, skipping unneeded frames without rendering them).
 * Must be used on the thread that owns the current EGL context.
 */
class ExportDecoder(context: Context, uri: String, private val frameUs: Long) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val texture: Int = Gl.createOesTexture()
    private val surfaceTexture = SurfaceTexture(texture)
    private val surface = Surface(surfaceTexture)
    private val matrix = FloatArray(16)
    private val thread = HandlerThread("ExportDecoderFrames").apply { start() }
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var available = false

    private var inputDone = false
    private var outputDone = false
    private var lastPts = -1L
    private val info = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        surfaceTexture.setOnFrameAvailableListener({
            lock.withLock { available = true; cond.signalAll() }
        }, Handler(thread.looper))
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    private fun seek(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        lastPts = -1
    }

    /** Ensures the texture holds the frame for [targetUs] (source time). */
    fun frameAt(targetUs: Long): VideoFrame? {
        val half = frameUs / 2
        if (lastPts >= 0 && (targetUs < lastPts - half || targetUs > lastPts + 2_000_000)) seek(targetUs)
        if (lastPts < 0 && targetUs > 0 && !inputDone) {
            // Fresh decoder: jump close to the target.
            if (extractor.sampleTime < targetUs - 2_000_000) seek(targetUs)
        }
        if (lastPts >= 0 && targetUs <= lastPts + half) return VideoFrame(texture, matrix)
        if (outputDone) return if (lastPts >= 0) VideoFrame(texture, matrix) else null

        var guard = 0
        while (!outputDone && guard++ < 20_000) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(2_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            if (outIdx >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                val wanted = info.size > 0 && pts >= targetUs - half
                codec.releaseOutputBuffer(outIdx, wanted)
                if (wanted) {
                    awaitFrame()
                    lastPts = pts
                    if (eos) outputDone = true
                    return VideoFrame(texture, matrix)
                }
                if (eos) {
                    outputDone = true
                    return if (lastPts >= 0) VideoFrame(texture, matrix) else null
                }
            }
        }
        return if (lastPts >= 0) VideoFrame(texture, matrix) else null
    }

    private fun awaitFrame() {
        lock.withLock {
            var waited = 0
            while (!available && waited < 50) {
                cond.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                waited++
            }
            available = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(matrix)
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        surface.release()
        surfaceTexture.release()
        Gl.deleteTexture(texture)
        thread.quitSafely()
    }
}
