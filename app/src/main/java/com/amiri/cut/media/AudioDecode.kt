package com.amiri.cut.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes a sound (audio file or a video's audio) to mono float PCM. */
object AudioDecode {
    class Pcm(val data: FloatArray, val rate: Int, val startUs: Long)

    fun mono(context: Context, uri: String, fromUs: Long, toUs: Long, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): Pcm? {
        val ex = MediaExtractor()
        ex.setDataSource(context, Uri.parse(uri), null)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); return null }
        ex.selectTrack(ti)
        val f = ex.getTrackFormat(ti)
        var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(f, null, null, 0)
        codec.start()
        ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var isFloat = false
        var out = FloatArray(1 shl 16)
        var n = 0
        var firstPts = -1L
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        val span = (toUs - fromUs).coerceAtLeast(1)
        try {
            while (!outDone && !isCancelled()) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val sz = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (sz < 0 || ex.sampleTime > toUs + 100_000) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val pts = info.presentationTimeUs
                        val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val frames = if (isFloat) info.size / 4 / ch else info.size / 2 / ch
                        for (k in 0 until frames) {
                            var s = 0f
                            for (c in 0 until ch) s += if (isFloat) b.getFloat() else b.getShort() / 32768f
                            val t = pts + k * 1_000_000L / rate
                            if (t < fromUs || t >= toUs) continue
                            if (firstPts < 0) firstPts = t
                            if (n == out.size) out = out.copyOf(out.size * 2)
                            out[n++] = s / ch
                        }
                        onProgress(((pts - fromUs).toFloat() / span).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() }
        }
        return Pcm(out.copyOf(n), rate, if (firstPts < 0) fromUs else firstPts)
    }

    /** Resamples with a simple windowed-sinc (good quality, fine for offline work). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val ratio = to.toDouble() / from
        val n = (x.size * ratio).toInt()
        val out = FloatArray(n)
        val cutoff = minOf(1.0, ratio) * 0.95
        val half = 12
        for (i in 0 until n) {
            val center = i / ratio
            val c0 = center.toInt()
            var s = 0.0; var ws = 0.0
            for (k in c0 - half + 1..c0 + half) {
                if (k < 0 || k >= x.size) continue
                val d = (k - center) * cutoff
                val sinc = if (kotlin.math.abs(d) < 1e-9) 1.0 else kotlin.math.sin(Math.PI * d) / (Math.PI * d)
                val w = 0.5 + 0.5 * kotlin.math.cos(Math.PI * (k - center) / half)
                val g = sinc * w
                s += x[k] * g; ws += g
            }
            out[i] = if (ws > 1e-9) (s / ws).toFloat() else 0f
        }
        return out
    }

    fun writeWav(file: java.io.File, x: FloatArray, rate: Int) {
        file.parentFile?.mkdirs()
        val bb = java.nio.ByteBuffer.allocate(44 + x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + x.size * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(x.size * 2)
        for (v in x) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        file.writeBytes(bb.array())
    }
}
