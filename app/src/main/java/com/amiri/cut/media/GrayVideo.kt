package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.amiri.cut.core.model.MediaAsset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fast grayscale frames for analysis (tracking, stabilization). Decodes sequentially with
 * MediaCodec (Y plane only, rotation applied) instead of seeking for every frame, and
 * visits frames forwards or backwards (backwards in decoded chunks). Falls back to
 * MediaMetadataRetriever when the hardware decoder can't hand out YUV images.
 */
class GrayVideo(private val context: Context, private val asset: MediaAsset, val w: Int, private val rgb: Boolean = false) : AutoCloseable {
    val h: Int = max(16, (w.toFloat() * asset.displayHeight.coerceAtLeast(1) / asset.displayWidth.coerceAtLeast(1)).roundToInt())

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var useRetriever = false
    private var retriever: MediaMetadataRetriever? = null
    private val info = MediaCodec.BufferInfo()
    private var frameUs = 33_333L

    init {
        runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(context, Uri.parse(asset.uri), null)
            val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ex.selectTrack(ti)
            val f = ex.getTrackFormat(ti)
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) frameUs = (1_000_000L / f.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1))
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            c.configure(f, null, null, 0)
            c.start()
            extractor = ex; codec = c
        }.onFailure { useRetriever = true }
    }

    /**
     * Visits [times] in the given order (ascending or descending); [onFrame] gets the frame
     * closest to each time and returns false to stop.
     */
    /** Like [scan] but delivers ARGB pixels (w×h, display orientation). Needs rgb = true. */
    fun scanRgb(times: List<Long>, isCancelled: () -> Boolean, onFrame: (Long, IntArray) -> Boolean) =
        scanAny(times, isCancelled) { t, f -> onFrame(t, f as IntArray) }

    fun scan(times: List<Long>, isCancelled: () -> Boolean, onFrame: (Long, FloatArray) -> Boolean) =
        scanAny(times, isCancelled) { t, f -> onFrame(t, f as FloatArray) }

    private fun scanAny(times: List<Long>, isCancelled: () -> Boolean, onFrame: (Long, Any) -> Boolean) {
        if (times.isEmpty()) return
        val backward = times.size > 1 && times.last() < times.first()
        if (!backward) {
            var i = 0
            while (i < times.size) {
                val chunk = times.subList(i, min(times.size, i + 45))
                val frames = collect(chunk)
                for (k in chunk.indices) {
                    if (isCancelled()) return
                    val g = frames[k] ?: return
                    if (!onFrame(chunk[k], g)) return
                }
                i += chunk.size
            }
        } else {
            var i = 0
            while (i < times.size) {
                val chunk = times.subList(i, min(times.size, i + 45))
                val asc = chunk.reversed()
                val frames = collect(asc)
                for (k in asc.indices.reversed()) {
                    if (isCancelled()) return
                    val g = frames[k] ?: return
                    if (!onFrame(asc[k], g)) return
                }
                i += chunk.size
            }
        }
    }

    /** Decodes frames for ascending [targets]. */
    private fun collect(targets: List<Long>): Array<Any?> {
        val out = arrayOfNulls<Any>(targets.size)
        if (!useRetriever) {
            val ok = runCatching { decodeInto(targets, out) }.getOrDefault(false)
            if (ok) return out
            useRetriever = true
        }
        val r = retriever ?: MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(asset.uri)); retriever = it }
        for (k in targets.indices) out[k] = runCatching { if (rgb) retrieverRgb(r, targets[k]) else retrieverGray(r, targets[k]) }.getOrNull()
        return out
    }

    private fun decodeInto(targets: List<Long>, out: Array<Any?>): Boolean {
        val ex = extractor ?: return false
        val c = codec ?: return false
        c.flush()
        ex.seekTo(max(0, targets.first()), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var inputDone = false
        var k = 0
        var last: Any? = null
        val half = frameUs / 2
        var idle = 0
        while (k < targets.size) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(5_000)
                if (ii >= 0) {
                    val buf = c.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 5_000)
            if (oi >= 0) {
                idle = 0
                val pts = info.presentationTimeUs
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && pts >= targets[k] - half) {
                    val img = c.getOutputImage(oi)
                    if (img == null) { c.releaseOutputBuffer(oi, false); return false }
                    val g: Any = img.use { if (rgb) yuvToArgb(it) else yToGray(it) }
                    last = g
                    while (k < targets.size && pts >= targets[k] - half) { out[k] = g; k++ }
                }
                c.releaseOutputBuffer(oi, false)
                if (eos) { while (k < targets.size) { out[k] = last; k++ }; break }
            } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (++idle > 400) break
            }
        }
        return out.firstOrNull() != null
    }

    /** Downsamples the Y plane to w×h in display orientation (box filter). */
    private fun yToGray(img: android.media.Image): FloatArray {
        val pl = img.planes[0]
        val buf = pl.buffer
        val rs = pl.rowStride
        val ps = pl.pixelStride
        val sw = img.cropRect.width().takeIf { it > 0 } ?: img.width
        val sh = img.cropRect.height().takeIf { it > 0 } ?: img.height
        val ox = img.cropRect.left; val oy = img.cropRect.top
        val rot = ((asset.rotation % 360) + 360) % 360
        val g = FloatArray(w * h)
        // Display size (after rotation) mapped onto the source grid.
        val dw = if (rot % 180 == 0) sw else sh
        val dh = if (rot % 180 == 0) sh else sw
        val fx = dw.toFloat() / w; val fy = dh.toFloat() / h
        val taps = min(3, max(1, fx.toInt()))
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (ty in 0 until taps) for (tx in 0 until taps) {
                val dx = ((x + (tx + 0.5f) / taps) * fx).toInt().coerceIn(0, dw - 1)
                val dy = ((y + (ty + 0.5f) / taps) * fy).toInt().coerceIn(0, dh - 1)
                val sx: Int; val sy: Int
                when (rot) {
                    90 -> { sx = dy; sy = sh - 1 - dx }
                    180 -> { sx = sw - 1 - dx; sy = sh - 1 - dy }
                    270 -> { sx = sw - 1 - dy; sy = dx }
                    else -> { sx = dx; sy = dy }
                }
                s += (buf.get((oy + sy) * rs + (ox + sx) * ps).toInt() and 0xFF)
            }
            g[y * w + x] = s / (taps * taps)
        }
        return g
    }

    private fun retrieverRgb(r: MediaMetadataRetriever, t: Long): IntArray? {
        val b = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: return null
        val s = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return px
    }

    /** YUV 4:2:0 → ARGB at w×h in display orientation (nearest sampling). */
    private fun yuvToArgb(img: android.media.Image): IntArray {
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        val by = py.buffer; val bu = pu.buffer; val bv = pv.buffer
        val sw = img.cropRect.width().takeIf { it > 0 } ?: img.width
        val sh = img.cropRect.height().takeIf { it > 0 } ?: img.height
        val ox = img.cropRect.left; val oy = img.cropRect.top
        val rot = ((asset.rotation % 360) + 360) % 360
        val dw = if (rot % 180 == 0) sw else sh
        val dh = if (rot % 180 == 0) sh else sw
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = ((x + 0.5f) * dw / w).toInt().coerceIn(0, dw - 1)
            val dy = ((y + 0.5f) * dh / h).toInt().coerceIn(0, dh - 1)
            val sx: Int; val sy: Int
            when (rot) {
                90 -> { sx = dy; sy = sh - 1 - dx }
                180 -> { sx = sw - 1 - dx; sy = sh - 1 - dy }
                270 -> { sx = sw - 1 - dy; sy = dx }
                else -> { sx = dx; sy = dy }
            }
            val yy = (by.get((oy + sy) * py.rowStride + (ox + sx) * py.pixelStride).toInt() and 0xFF).toFloat()
            val cx = (ox + sx) / 2; val cy = (oy + sy) / 2
            val u = (bu.get(cy * pu.rowStride + cx * pu.pixelStride).toInt() and 0xFF) - 128f
            val v = (bv.get(cy * pv.rowStride + cx * pv.pixelStride).toInt() and 0xFF) - 128f
            val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
            val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
            val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
            out[y * w + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun retrieverGray(r: MediaMetadataRetriever, t: Long): FloatArray? {
        val b = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: return null
        val s = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return FloatArray(px.size) { i -> val c = px[i]; 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c) }
    }

    override fun close() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        runCatching { extractor?.release() }
        runCatching { retriever?.release() }
    }
}
