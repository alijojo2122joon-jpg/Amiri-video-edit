package com.amiri.cut.export

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel as PL
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlin.math.pow

enum class ExportQuality(val label: String, val bitsPerPixel: Float) {
    LOW("Low", 0.07f), MEDIUM("Medium", 0.11f), HIGH("High", 0.17f), MAXIMUM("Maximum", 0.26f)
}

enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("H.265 / HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC),
}

/** Reads what this device's hardware encoders can actually do. */
object EncoderCaps {

    data class Check(val ok: Boolean, val message: String, val suggestion: Suggestion? = null)
    data class Suggestion(val codec: VideoCodec, val width: Int, val height: Int, val fps: Int)

    private fun encoders(mime: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }.sortedBy { if (it.isHardwareAccelerated) 0 else 1 }

    private fun supportsOn(info: MediaCodecInfo, codec: VideoCodec, w: Int, h: Int, fps: Int): Boolean = runCatching {
        val vc = info.getCapabilitiesForType(codec.mime).videoCapabilities
        vc.areSizeAndRateSupported(w, h, fps.toDouble()) || vc.areSizeAndRateSupported(h, w, fps.toDouble())
    }.getOrDefault(false)

    fun supports(codec: VideoCodec, w: Int, h: Int, fps: Int): Boolean = encoders(codec.mime).any { supportsOn(it, codec, w, h, fps) }

    fun encoderName(codec: VideoCodec): String? = encoders(codec.mime).firstOrNull()?.name

    /** The encoder export will use: the first (hardware-preferred) one that handles this size and rate. */
    fun pick(codec: VideoCodec, w: Int, h: Int, fps: Int): MediaCodecInfo? {
        val all = encoders(codec.mime)
        return all.firstOrNull { supportsOn(it, codec, w, h, fps) } ?: all.firstOrNull()
    }

    /**
     * Target video bitrate. Bits per pixel fall gently with resolution and frame rate (bigger
     * and smoother pictures compress better), so every size gets a comparable visual quality.
     * 1080p30 → ≈ 4.4 / 6.8 / 10.6 / 16.2 Mbps; 4K30 → ≈ 14 / 22 / 34 / 52 Mbps (H.264).
     */
    fun bitrate(w: Int, h: Int, fps: Int, q: ExportQuality, codec: VideoCodec): Int {
        val px = w.toDouble() * h
        val res = (2_073_600.0 / px.coerceAtLeast(1.0)).pow(0.15)
        val rate = (30.0 / fps.coerceAtLeast(1)).pow(0.35)
        val base = px * fps * q.bitsPerPixel * res * rate * (if (codec == VideoCodec.HEVC) 0.7 else 1.0)
        return base.toLong().coerceIn(1_000_000L, 150_000_000L).toInt()
    }

    fun bitrateRange(codec: VideoCodec): IntRange? = encoders(codec.mime).firstOrNull()?.let { info ->
        runCatching { info.getCapabilitiesForType(codec.mime).videoCapabilities.bitrateRange.let { it.lower..it.upper } }.getOrNull()
    }

    // ── levels ──

    /** a / b: AVC = max macroblocks per second / per frame; HEVC = max luma samples per second / per picture. */
    private class Lv(val level: Int, val a: Long, val b: Long, val maxKbps: Long)

    private val AVC_LEVELS = listOf(
        Lv(PL.AVCLevel31, 108_000, 3_600, 14_000),
        Lv(PL.AVCLevel32, 216_000, 5_120, 20_000),
        Lv(PL.AVCLevel4, 245_760, 8_192, 20_000),
        Lv(PL.AVCLevel41, 245_760, 8_192, 50_000),
        Lv(PL.AVCLevel42, 522_240, 8_704, 50_000),
        Lv(PL.AVCLevel5, 589_824, 22_080, 135_000),
        Lv(PL.AVCLevel51, 983_040, 36_864, 240_000),
        Lv(PL.AVCLevel52, 2_073_600, 36_864, 240_000),
        Lv(PL.AVCLevel6, 4_177_920, 139_264, 240_000),
        Lv(PL.AVCLevel61, 8_355_840, 139_264, 480_000),
        Lv(PL.AVCLevel62, 16_711_680, 139_264, 800_000),
    )

    private val HEVC_MAIN = listOf(
        Lv(PL.HEVCMainTierLevel31, 33_177_600, 983_040, 10_000),
        Lv(PL.HEVCMainTierLevel4, 66_846_720, 2_228_224, 12_000),
        Lv(PL.HEVCMainTierLevel41, 133_693_440, 2_228_224, 20_000),
        Lv(PL.HEVCMainTierLevel5, 267_386_880, 8_912_896, 25_000),
        Lv(PL.HEVCMainTierLevel51, 534_773_760, 8_912_896, 40_000),
        Lv(PL.HEVCMainTierLevel52, 1_069_547_520, 8_912_896, 60_000),
        Lv(PL.HEVCMainTierLevel6, 1_069_547_520, 35_651_584, 60_000),
        Lv(PL.HEVCMainTierLevel61, 2_139_095_040, 35_651_584, 120_000),
        Lv(PL.HEVCMainTierLevel62, 4_278_190_080, 35_651_584, 240_000),
    )

    private val HEVC_HIGH = listOf(
        Lv(PL.HEVCHighTierLevel41, 133_693_440, 2_228_224, 50_000),
        Lv(PL.HEVCHighTierLevel5, 267_386_880, 8_912_896, 100_000),
        Lv(PL.HEVCHighTierLevel51, 534_773_760, 8_912_896, 160_000),
        Lv(PL.HEVCHighTierLevel52, 1_069_547_520, 8_912_896, 240_000),
        Lv(PL.HEVCHighTierLevel61, 2_139_095_040, 35_651_584, 480_000),
        Lv(PL.HEVCHighTierLevel62, 4_278_190_080, 35_651_584, 800_000),
    )

    /** Lowest standard level that fits the size, rate and bitrate (so every player accepts the file). */
    fun level(codec: VideoCodec, w: Int, h: Int, fps: Int, bitrate: Int): Int? = when (codec) {
        VideoCodec.H264 -> {
            val mbs = ((w + 15) / 16).toLong() * ((h + 15) / 16)
            // High profile allows 1.25× the base bitrate limit.
            AVC_LEVELS.firstOrNull { mbs * fps <= it.a && mbs <= it.b && bitrate <= it.maxKbps * 1250 }?.level
        }
        VideoCodec.HEVC -> {
            val ps = w.toLong() * h
            fun fits(l: Lv) = ps * fps <= l.a && ps <= l.b && bitrate <= l.maxKbps * 1000
            (HEVC_MAIN.firstOrNull(::fits) ?: HEVC_HIGH.firstOrNull(::fits))?.level
        }
    }

    // ── formats ──

    /** The plain format every encoder accepts (fallback). */
    fun basicFormat(codec: VideoCodec, w: Int, h: Int, fps: Int, bitrate: Int): MediaFormat =
        MediaFormat.createVideoFormat(codec.mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    /**
     * The quality-tuned format: H.264 High / HEVC Main profile at the right level (instead of the
     * Baseline many encoders default to), variable bitrate so detailed scenes get more bits, and a
     * 2 s keyframe interval. Only settings the encoder reports supporting are requested.
     */
    fun tunedFormat(info: MediaCodecInfo, codec: VideoCodec, w: Int, h: Int, fps: Int, bitrate: Int): MediaFormat {
        val caps = runCatching { info.getCapabilitiesForType(codec.mime) }.getOrNull()
        val br = caps?.videoCapabilities?.bitrateRange?.let { bitrate.coerceIn(it.lower, it.upper) } ?: bitrate
        val f = basicFormat(codec, w, h, fps, br)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        if (caps == null) return f
        val profile = if (codec == VideoCodec.H264) PL.AVCProfileHigh else PL.HEVCProfileMain
        val offered = caps.profileLevels.filter { it.profile == profile }
        val lvl = level(codec, w, h, fps, br)
        if (offered.isNotEmpty()) {
            val maxLevel = offered.maxOf { it.level }
            f.setInteger(MediaFormat.KEY_PROFILE, profile)
            f.setInteger(MediaFormat.KEY_LEVEL, if (lvl != null && lvl <= maxLevel) lvl else maxLevel)
        }
        if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        return f
    }

    /** Human summary of what [tunedFormat] would request (shown in the self-test). */
    fun describe(codec: VideoCodec, w: Int, h: Int, fps: Int, q: ExportQuality): String {
        val info = pick(codec, w, h, fps) ?: return "no ${codec.label} encoder"
        val br = bitrate(w, h, fps, q, codec)
        val f = tunedFormat(info, codec, w, h, fps, br)
        val prof = if (f.containsKey(MediaFormat.KEY_PROFILE)) (if (codec == VideoCodec.H264) "High" else "Main") else "default profile"
        val vbr = if (f.containsKey(MediaFormat.KEY_BITRATE_MODE)) "VBR" else "CBR/default"
        return "${info.name} · $prof · $vbr · ${"%.1f".format(f.getInteger(MediaFormat.KEY_BIT_RATE) / 1e6)} Mbps"
    }

    /** Validates a configuration and proposes the closest supported one instead of failing. */
    fun check(codec: VideoCodec, w: Int, h: Int, fps: Int): Check {
        if (supports(codec, w, h, fps)) return Check(true, "${codec.label} ${w}×$h @ $fps fps is supported by ${pick(codec, w, h, fps)?.name ?: "this device"}")
        val other = if (codec == VideoCodec.H264) VideoCodec.HEVC else VideoCodec.H264
        if (supports(other, w, h, fps)) {
            return Check(false, "${codec.label} can't encode ${w}×$h @ $fps on this device.", Suggestion(other, w, h, fps))
        }
        for (f in listOf(fps, 30, 25, 24).distinct()) {
            var sw = w
            var sh = h
            repeat(6) {
                if (supports(codec, sw, sh, f)) return Check(false, "${w}×$h @ $fps isn't supported here.", Suggestion(codec, sw, sh, f))
                if (supports(other, sw, sh, f)) return Check(false, "${w}×$h @ $fps isn't supported here.", Suggestion(other, sw, sh, f))
                sw = ((sw * 0.75f).toInt() / 2) * 2
                sh = ((sh * 0.75f).toInt() / 2) * 2
            }
        }
        return Check(false, "No encoder on this device supports this size.")
    }
}
