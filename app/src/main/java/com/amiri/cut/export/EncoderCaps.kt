package com.amiri.cut.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

enum class ExportQuality(val label: String, val bitsPerPixel: Float) {
    LOW("Low", 0.05f), MEDIUM("Medium", 0.09f), HIGH("High", 0.14f), MAXIMUM("Maximum", 0.22f)
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

    fun supports(codec: VideoCodec, w: Int, h: Int, fps: Int): Boolean = encoders(codec.mime).any { info ->
        runCatching {
            val vc = info.getCapabilitiesForType(codec.mime).videoCapabilities
            vc.areSizeAndRateSupported(w, h, fps.toDouble()) || vc.areSizeAndRateSupported(h, w, fps.toDouble())
        }.getOrDefault(false)
    }

    fun encoderName(codec: VideoCodec): String? = encoders(codec.mime).firstOrNull()?.name

    fun bitrate(w: Int, h: Int, fps: Int, q: ExportQuality, codec: VideoCodec): Int {
        val base = w.toLong() * h * fps * q.bitsPerPixel * (if (codec == VideoCodec.HEVC) 0.7f else 1f)
        return base.toLong().coerceIn(1_000_000L, 150_000_000L).toInt()
    }

    fun bitrateRange(codec: VideoCodec): IntRange? = encoders(codec.mime).firstOrNull()?.let { info ->
        runCatching { info.getCapabilitiesForType(codec.mime).videoCapabilities.bitrateRange.let { it.lower..it.upper } }.getOrNull()
    }

    /** Validates a configuration and proposes the closest supported one instead of failing. */
    fun check(codec: VideoCodec, w: Int, h: Int, fps: Int): Check {
        if (supports(codec, w, h, fps)) return Check(true, "${codec.label} ${w}×$h @ $fps fps is supported by ${encoderName(codec) ?: "this device"}")
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
