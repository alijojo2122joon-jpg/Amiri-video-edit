package com.amiri.cut.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.storage.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Real audio waveforms: the audio track is decoded with MediaCodec to PCM and
 * reduced to peak values, [BUCKETS_PER_SECOND] buckets per second of source,
 * normalised to 0..255 and cached on disk (cache/preview/waveforms/<assetId>.wf).
 */
class WaveformCache(private val context: Context, private val cache: CacheManager) {

    class Waveform(val peaks: ByteArray) {
        /** Peak (0..1) over a source time range. */
        fun peak(fromUs: Long, toUs: Long): Float {
            if (peaks.isEmpty()) return 0f
            val a = (fromUs * BUCKETS_PER_SECOND / 1_000_000L).toInt().coerceIn(0, peaks.size - 1)
            val b = (toUs * BUCKETS_PER_SECOND / 1_000_000L).toInt().coerceIn(a, peaks.size - 1)
            var m = 0
            for (i in a..b) m = max(m, peaks[i].toInt() and 0xFF)
            return m / 255f
        }
    }

    private val waves = ConcurrentHashMap<String, Waveform>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun get(assetId: String): Waveform? = waves[assetId]

    fun request(asset: MediaAsset) {
        if (!asset.hasAudio) return
        if (waves.containsKey(asset.id) || !inFlight.add(asset.id)) return
        scope.launch {
            try {
                val f = File(cache.waveformsDir(), "${asset.id}.wf")
                val peaks = if (f.exists()) f.readBytes() else extract(asset)?.also { bytes ->
                    runCatching { f.writeBytes(bytes) }
                }
                if (peaks != null) {
                    waves[asset.id] = Waveform(peaks)
                    _version.value++
                }
            } finally {
                inFlight.remove(asset.id)
            }
        }
    }

    fun invalidate(assetId: String) {
        waves.remove(assetId)
        File(cache.waveformsDir(), "$assetId.wf").delete()
        _version.value++
    }

    private fun extract(asset: MediaAsset): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(asset.uri), null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else asset.durationUs
            val bucketCount = (durationUs * BUCKETS_PER_SECOND / 1_000_000L).toInt().coerceAtLeast(1) + 1
            val peaks = FloatArray(bucketCount)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = dec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val out = dec.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val bb = out.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val startUs = info.presentationTimeUs
                            if (isFloat) {
                                val fb = bb.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                for (f in 0 until frames) {
                                    var v = 0f
                                    for (c in 0 until channels) v = max(v, abs(fb.get(f * channels + c)))
                                    accumulate(peaks, startUs, f, sampleRate, v)
                                }
                            } else {
                                val sb = bb.asShortBuffer()
                                val frames = sb.remaining() / channels
                                for (f in 0 until frames) {
                                    var v = 0
                                    for (c in 0 until channels) v = max(v, abs(sb.get(f * channels + c).toInt()))
                                    accumulate(peaks, startUs, f, sampleRate, v / 32768f)
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val maxPeak = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
            return ByteArray(peaks.size) { i -> ((peaks[i] / maxPeak).coerceIn(0f, 1f) * 255f).toInt().toByte() }
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun accumulate(peaks: FloatArray, startUs: Long, frameIndex: Int, sampleRate: Int, v: Float) {
        val t = startUs + frameIndex * 1_000_000L / sampleRate
        val b = (t * BUCKETS_PER_SECOND / 1_000_000L).toInt()
        if (b in peaks.indices && v > peaks[b]) peaks[b] = v
    }

    companion object {
        const val BUCKETS_PER_SECOND = 100
    }
}
