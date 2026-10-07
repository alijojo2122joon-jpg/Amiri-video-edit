package com.amiri.cut.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.Project
import com.amiri.cut.media.BitmapLoader
import com.amiri.cut.render.Compositor
import com.amiri.cut.render.FrameSources
import com.amiri.cut.render.LutLoader
import com.amiri.cut.render.RenderOptions
import com.amiri.cut.render.TextRenderer
import com.amiri.cut.render.VideoFrame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class ExportSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val quality: ExportQuality = ExportQuality.HIGH,
    val codec: VideoCodec = VideoCodec.H264,
    /** Custom video bitrate in bit/s, or null for automatic. */
    val bitrate: Int? = null,
    val audioBitrate: Int = 192_000,
)

data class ExportProgress(
    val frame: Int,
    val totalFrames: Int,
    val fps: Float,
    val etaSeconds: Int,
    val stage: String,
) {
    val fraction: Float get() = if (totalFrames <= 0) 0f else frame.toFloat() / totalFrames
}

/**
 * Renders a project to an MP4 file: every frame is composited with the same
 * [Compositor] as the preview into the hardware encoder's input surface; audio is mixed
 * by [AudioMixer] and encoded to AAC; both are muxed with MediaMuxer.
 */
class Exporter(
    private val context: Context,
    private val app: AmiriCutApp,
    private val project: Project,
    private val settings: ExportSettings,
    private val outFile: File,
    private val cancelled: AtomicBoolean,
    /** Play every source backwards (used to build reversed proxies of a single clip). */
    private val reverse: Boolean = false,
    private val onProgress: (ExportProgress) -> Unit,
) {
    private val fps = settings.fps
    private val frameUs = 1_000_000L / fps

    fun run() {
        val duration = project.durationUs
        require(duration > 0) { "The timeline is empty" }
        val totalFrames = max(1, ((duration * fps + 999_999) / 1_000_000).toInt())

        // ── audio first (fast), kept in memory as encoded AAC samples ──
        onProgress(ExportProgress(0, totalFrames, 0f, 0, "Mixing audio"))
        val audio = encodeAudio(duration)
        if (cancelled.get()) throw InterruptedException("Cancelled")

        // ── video ──
        val w = settings.width
        val h = settings.height
        val bitrate = settings.bitrate ?: EncoderCaps.bitrate(w, h, fps, settings.quality, settings.codec)
        val encoder = openEncoder(w, h, bitrate)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val egl = EglCore()
        egl.makeCurrent(inputSurface)
        val compositor = Compositor(TextRenderer(app.fonts))
        val decoders = HashMap<String, ExportDecoder>()
        // Most recently used photos only: long slideshows must not hold every bitmap at once.
        val images = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 10
        }
        val luts = HashMap<String, LutLoader.Lut?>()
        val sources = object : FrameSources {
            override fun video(clip: Clip, asset: MediaAsset): VideoFrame? {
                val dec = decoders.getOrPut(clip.id) { ExportDecoder(context, asset.uri, frameUs) }
                val st = clip.sourceTimeAt(currentT).coerceIn(0L, (asset.durationUs - frameUs).coerceAtLeast(0L))
                return dec.frameAt(if (reverse) (asset.durationUs - st - frameUs).coerceAtLeast(0) else st)
            }
            override fun image(asset: MediaAsset): Bitmap? =
                images.getOrPut(asset.id) { BitmapLoader.decodeImage(context, asset, max(w, h)) ?: return null }
            override fun roto(file: String): Bitmap? = app.roto.load(project.id, file)
            override fun lut(name: String): LutLoader.Lut? = luts.getOrPut(name) { app.luts.file(name)?.let { LutLoader.load(it) } }
        }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var muxing = false
        var audioIdx = 0
        val info = MediaCodec.BufferInfo()
        val started = System.nanoTime()

        fun writeAudioUpTo(ptsUs: Long) {
            if (!muxing || audioTrack < 0) return
            while (audioIdx < audio.samples.size && audio.samples[audioIdx].second.presentationTimeUs <= ptsUs) {
                val (data, bi) = audio.samples[audioIdx]
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(data), bi)
                audioIdx++
            }
        }

        fun drain(eos: Boolean) {
            if (eos) encoder.signalEndOfInputStream()
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audio.format != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                        muxing = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            writeAudioUpTo(info.presentationTimeUs)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            val viewport = intArrayOf(0, 0, w, h)
            for (n in 0 until totalFrames) {
                if (cancelled.get()) throw InterruptedException("Cancelled")
                currentT = n.toLong() * 1_000_000L / fps
                compositor.render(project, currentT, w, h, sources, RenderOptions(checker = false), 0, viewport)
                // Free hardware decoders of clips that have finished.
                if (n % 15 == 0) {
                    // (kept a little longer: transitions show the outgoing clip past its end)
                    val done = decoders.keys.filter { id -> (project.clip(id)?.endUs ?: 0L) + 3_000_000L <= currentT }
                    done.forEach { id -> decoders.remove(id)?.let { d -> runCatching { d.release() } } }
                }
                egl.setPresentationTime(currentT * 1000)
                egl.swap()
                drain(false)
                if (n % 3 == 0 || n == totalFrames - 1) {
                    val el = (System.nanoTime() - started) / 1e9f
                    val rate = (n + 1) / el.coerceAtLeast(0.001f)
                    onProgress(ExportProgress(n + 1, totalFrames, rate, ((totalFrames - n - 1) / rate.coerceAtLeast(0.1f)).toInt(), "Rendering"))
                }
            }
            drain(true)
            writeAudioUpTo(Long.MAX_VALUE)
        } finally {
            decoders.values.forEach { runCatching { it.release() } }
            compositor.release()
            egl.release()
            runCatching { encoder.stop() }
            encoder.release()
            inputSurface.release()
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    @Volatile private var currentT = 0L

    /**
     * Opens the video encoder with the quality-tuned format (High profile, right level, VBR);
     * if this device's encoder rejects any of it, falls back to the plain format.
     */
    private fun openEncoder(w: Int, h: Int, bitrate: Int): MediaCodec {
        val codec = settings.codec
        EncoderCaps.pick(codec, w, h, fps)?.let { info ->
            val enc = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull()
            if (enc != null) {
                try {
                    enc.configure(EncoderCaps.tunedFormat(info, codec, w, h, fps, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return enc
                } catch (t: Throwable) {
                    android.util.Log.w("AmiriExport", "Tuned encoder format rejected by ${info.name}; using the basic one", t)
                    runCatching { enc.release() }
                }
            }
        }
        val enc = MediaCodec.createEncoderByType(codec.mime)
        enc.configure(EncoderCaps.basicFormat(codec, w, h, fps, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return enc
    }

    private class EncodedAudio(val format: MediaFormat?, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    private fun encodeAudio(durationUs: Long): EncodedAudio {
        val mixer = AudioMixer(context, project, reverse)
        val rate = mixer.rate
        val totalFrames = durationUs * rate / 1_000_000
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 2 * 2 * 8)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var frame = 0L
        val block = 1024
        val pcm = ShortArray(block * 2)
        var inputDone = false
        try {
            while (true) {
                if (cancelled.get()) throw InterruptedException("Cancelled")
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!
                        b.clear()
                        if (frame >= totalFrames) {
                            enc.queueInputBuffer(i, 0, 0, frame * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(block.toLong(), totalFrames - frame).toInt()
                            if (mixer.hasAudio) mixer.render(frame, n, pcm) else pcm.fill(0)
                            val bytes = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until n * 2) bytes.putShort(pcm[k])
                            b.put(bytes.array(), 0, n * 4)
                            enc.queueInputBuffer(i, 0, n * 4, frame * 1_000_000L / rate, 0)
                            frame += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = enc.outputFormat
                else if (o >= 0) {
                    val ob = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val data = ByteArray(info.size)
                        ob.position(info.offset); ob.get(data)
                        val bi = MediaCodec.BufferInfo().apply { set(0, data.size, info.presentationTimeUs, info.flags) }
                        out += data to bi
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { enc.stop() }
            enc.release()
            mixer.release()
        }
        return EncodedAudio(outFormat, out)
    }
}
