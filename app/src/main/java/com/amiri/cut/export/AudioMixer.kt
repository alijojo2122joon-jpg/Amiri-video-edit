package com.amiri.cut.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.Track
import com.amiri.cut.engine.PreviewEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Streams decoded audio of one clip as stereo float samples addressable by source time.
 * Keeps a sliding window so long sources never need to fit in memory.
 */
private class ClipAudioReader(context: Context, uri: String, startUs: Long) {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var rate = 48_000
    private var channels = 2
    private var isFloat = false
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()

    /** Interleaved stereo window starting at [bufStartUs]. */
    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var bufStartUs = 0L

    val ok: Boolean

    init {
        var good = false
        runCatching {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (track != null) {
                extractor.selectTrack(track)
                val f = extractor.getTrackFormat(track)
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                val c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
                c.configure(f, null, null, 0)
                c.start()
                codec = c
                extractor.seekTo(max(0, startUs - 50_000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                bufStartUs = -1
                good = true
            }
        }
        ok = good
    }

    private fun framesToUs(f: Int): Long = f * 1_000_000L / rate

    /** Decodes until the window covers [us]. Returns false at end of stream. */
    private fun fill(us: Long): Boolean {
        val c = codec ?: return false
        while (bufStartUs < 0 || us >= bufStartUs + framesToUs(bufFrames)) {
            if (outputDone) return false
            if (!inputDone) {
                val i = c.dequeueInputBuffer(2_000)
                if (i >= 0) {
                    val b = c.getInputBuffer(i)!!
                    val n = extractor.readSampleData(b, 0)
                    if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = c.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (o >= 0) {
                if (info.size > 0) {
                    val ob = c.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val bb = ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                    append(bb, info.presentationTimeUs, us)
                }
                c.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        return true
    }

    private fun append(bb: ByteBuffer, ptsUs: Long, needUs: Long) {
        val frames = if (isFloat) bb.remaining() / 4 / channels else bb.remaining() / 2 / channels
        if (bufStartUs < 0) { bufStartUs = ptsUs; bufFrames = 0 }
        // Drop window history older than 1 s before what we need.
        val keepFrom = needUs - 1_000_000
        if (keepFrom > bufStartUs && bufFrames > 0) {
            val drop = min(bufFrames, ((keepFrom - bufStartUs) * rate / 1_000_000).toInt())
            if (drop > 0) {
                System.arraycopy(buf, drop * 2, buf, 0, (bufFrames - drop) * 2)
                bufFrames -= drop
                bufStartUs += framesToUs(drop)
            }
        }
        if (buf.size < (bufFrames + frames) * 2) buf = buf.copyOf(max((bufFrames + frames) * 2, buf.size * 2))
        if (isFloat) {
            val fb = bb.asFloatBuffer()
            for (f in 0 until frames) {
                val l = fb.get(f * channels)
                val r = if (channels > 1) fb.get(f * channels + 1) else l
                buf[(bufFrames + f) * 2] = l; buf[(bufFrames + f) * 2 + 1] = r
            }
        } else {
            val sb = bb.asShortBuffer()
            for (f in 0 until frames) {
                val l = sb.get(f * channels) / 32768f
                val r = if (channels > 1) sb.get(f * channels + 1) / 32768f else l
                buf[(bufFrames + f) * 2] = l; buf[(bufFrames + f) * 2 + 1] = r
            }
        }
        bufFrames += frames
    }

    /** Stereo sample at a source time (linear interpolation), or zeros past the end. */
    fun sample(us: Long, out: FloatArray) {
        if (!fill(us) || bufStartUs < 0 || us < bufStartUs) { out[0] = 0f; out[1] = 0f; return }
        val pos = (us - bufStartUs) * rate / 1_000_000.0
        val i = pos.toInt()
        if (i + 1 >= bufFrames) { out[0] = 0f; out[1] = 0f; return }
        val f = (pos - i).toFloat()
        out[0] = buf[i * 2] + (buf[(i + 1) * 2] - buf[i * 2]) * f
        out[1] = buf[i * 2 + 1] + (buf[(i + 1) * 2 + 1] - buf[i * 2 + 1]) * f
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

/**
 * Mixes every audible clip of the project into 48 kHz stereo PCM, block by block:
 * volume keyframes, fades, track/clip mute and speed (incl. ramps) are applied.
 */
class AudioMixer(private val context: Context, private val project: Project, private val reverse: Boolean = false) {
    val rate = 48_000
    private data class Source(val track: Track, val clip: Clip, val uri: String)

    private val sources: List<Source> = project.tracks.flatMap { t ->
        if (t.hidden && !t.acceptsAudio) emptyList()
        else t.clips.mapNotNull { c ->
            val a = project.asset(c.assetId) ?: return@mapNotNull null
            if (!a.hasAudio || a.type == MediaType.IMAGE) return@mapNotNull null
            if (!t.acceptsAudio && !t.acceptsVisual) return@mapNotNull null
            Source(t, c, a.uri)
        }
    }

    private val readers = HashMap<String, ClipAudioReader>()
    private val fx = HashMap<String, com.amiri.cut.core.audio.AudioFxChain>()

    private fun fxFor(c: Clip, t: Long): com.amiri.cut.core.audio.AudioFxChain {
        val ch = fx.getOrPut(c.id) { com.amiri.cut.core.audio.AudioFxChain(rate.toDouble()) }
        val l = t - c.startUs
        ch.update(c.audio.at("bass", l, 0f), c.audio.at("mid", l, 0f), c.audio.at("treble", l, 0f), c.audio.at("pan", l, 0f))
        return ch
    }
    private val whole = HashMap<String, Pair<FloatArray, Int>?>()

    /** Full decode (for reversed playback). Returns interleaved stereo + sample rate. */
    private fun decodeAll(uri: String, durationUs: Long): Pair<FloatArray, Int>? {
        val r = ClipAudioReader(context, uri, 0)
        if (!r.ok) { r.release(); return null }
        val outRate = rate
        val frames = (durationUs * outRate / 1_000_000L).toInt().coerceAtMost(outRate * 60 * 20)
        val data = FloatArray(frames * 2)
        val tmp = FloatArray(2)
        for (i in 0 until frames) {
            r.sample(i * 1_000_000L / outRate, tmp)
            data[i * 2] = tmp[0]; data[i * 2 + 1] = tmp[1]
        }
        r.release()
        return data to outRate
    }

    val hasAudio: Boolean get() = sources.isNotEmpty()

    /** Fills [out] (interleaved stereo shorts) with frames starting at timeline [startFrame]. */
    fun render(startFrame: Long, frames: Int, out: ShortArray) {
        val mix = FloatArray(frames * 2)
        val tmp = FloatArray(2)
        val blockStartUs = startFrame * 1_000_000L / rate
        val blockEndUs = (startFrame + frames) * 1_000_000L / rate
        for (s in sources) {
            val c = s.clip
            if (c.endUs <= blockStartUs || c.startUs >= blockEndUs) {
                if (c.endUs <= blockStartUs) readers.remove(c.id)?.release()
                continue
            }
            if (reverse) {
                val asset = project.asset(c.assetId) ?: continue
                val full = whole.getOrPut(c.id) { decodeAll(s.uri, asset.durationUs) } ?: continue
                val (data, sr) = full
                val n = data.size / 2
                for (i in 0 until frames) {
                    val t = (startFrame + i) * 1_000_000L / rate
                    if (t < c.startUs || t >= c.endUs) continue
                    val src = asset.durationUs - c.sourceTimeAt(t)
                    val k = (src * sr / 1_000_000L).toInt()
                    if (k in 0 until n) {
                        val g = PreviewEngine.gainAt(s.track, c, t)
                        tmp[0] = data[k * 2] * g; tmp[1] = data[k * 2 + 1] * g
                        if (i % 256 == 0 || i == 0) fxFor(c, t)
                        fx[c.id]?.takeIf { it.active }?.process(tmp)
                        mix[i * 2] += tmp[0]; mix[i * 2 + 1] += tmp[1]
                    }
                }
                continue
            }
            val r = readers.getOrPut(c.id) { ClipAudioReader(context, s.uri, c.sourceTimeAt(max(c.startUs, blockStartUs))) }
            if (!r.ok) continue
            var gain = 0f
            var chain: com.amiri.cut.core.audio.AudioFxChain? = null
            for (i in 0 until frames) {
                val t = (startFrame + i) * 1_000_000L / rate
                if (t < c.startUs || t >= c.endUs) continue
                if (i % 256 == 0 || gain == 0f || chain == null) { gain = PreviewEngine.gainAt(s.track, c, t); chain = fxFor(c, t) }
                r.sample(c.sourceTimeAt(t), tmp)
                if (gain <= 0f) continue
                tmp[0] *= gain; tmp[1] *= gain
                if (chain.active) chain.process(tmp)
                mix[i * 2] += tmp[0]
                mix[i * 2 + 1] += tmp[1]
            }
        }
        for (i in 0 until frames * 2) {
            // Soft clip to avoid harsh distortion when many sources sum above 0 dBFS.
            val v = mix[i]
            val sc = if (v > 1f || v < -1f) (v / (1f + kotlin.math.abs(v) - 1f)) else v
            out[i] = (sc.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    fun release() {
        readers.values.forEach { it.release() }
        readers.clear()
    }
}
