package com.amiri.cut.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.amiri.cut.core.audio.BeatMath
import java.nio.ByteOrder
import kotlin.math.exp

/** Decodes a sound to an energy envelope and finds its beats (offline). */
object BeatDetector {
    /** Beat times in source seconds within [fromUs, toUs]. */
    fun detect(context: Context, uri: String, fromUs: Long, toUs: Long, mode: BeatMath.Mode, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): BeatMath.Result {
        val ex = MediaExtractor()
        ex.setDataSource(context, Uri.parse(uri), null)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); return BeatMath.Result(emptyList(), 0.0) }
        ex.selectTrack(ti)
        val f = ex.getTrackFormat(ti)
        var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(f, null, null, 0)
        codec.start()
        ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val hop = 512
        val full = ArrayList<Float>(); val low = ArrayList<Float>()
        var accF = 0.0; var accL = 0.0; var cnt = 0
        var lp = 0.0
        var alpha = 1.0 - exp(-2.0 * Math.PI * 150.0 / rate)
        var isFloat = false
        var firstPts = -1L
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        val span = (toUs - fromUs).coerceAtLeast(1)
        try {
            while (!outDone && !isCancelled()) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > toUs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    alpha = 1.0 - exp(-2.0 * Math.PI * 150.0 / rate)
                } else if (o >= 0) {
                    if (info.size > 0 && info.presentationTimeUs + 50_000 >= fromUs) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val frames = if (isFloat) info.size / 4 / ch else info.size / 2 / ch
                        for (k in 0 until frames) {
                            var s = 0.0
                            for (c in 0 until ch) s += if (isFloat) b.getFloat().toDouble() else b.getShort() / 32768.0
                            s /= ch
                            lp += alpha * (s - lp)
                            accF += s * s; accL += lp * lp; cnt++
                            if (cnt == hop) { full += (accF / hop).toFloat(); low += (accL / hop).toFloat(); accF = 0.0; accL = 0.0; cnt = 0 }
                        }
                        onProgress(((info.presentationTimeUs - fromUs).toFloat() / span).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() }
        }
        val env = BeatMath.onsets(full.toFloatArray(), low.toFloatArray())
        val hopSec = hop.toDouble() / rate
        val r = BeatMath.detect(env, hopSec, mode)
        val base = ((firstPts.coerceAtLeast(0) - fromUs) / 1e6)
        return BeatMath.Result(r.times.map { it + base + fromUs / 1e6 }, r.bpm)
    }
}
