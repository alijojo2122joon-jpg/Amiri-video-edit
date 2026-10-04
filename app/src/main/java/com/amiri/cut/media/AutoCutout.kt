package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import com.amiri.cut.core.model.MediaAsset
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

/**
 * Automatic person cut-out: an on-device segmentation model (bundled, runs offline)
 * finds the person in every frame and writes a roto mask for it.
 */
object AutoCutout {
    private const val MODEL = "models/selfie_segmenter.tflite"

    fun available(context: Context): Boolean = runCatching { context.assets.open(MODEL).close(); true }.getOrDefault(false)

    /** Person confidence (0..1) for one picture, at the picture's size; null if unavailable. */
    fun confidence(context: Context, b: Bitmap): FloatArray? = runCatching {
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        val seg = ImageSegmenter.createFromOptions(context, opts)
        try {
            val res = seg.segment(BitmapImageBuilder(b).build())
            val m = res.confidenceMasks().orElse(null)?.firstOrNull() ?: return@runCatching null
            val fb = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val conf = FloatArray(m.width * m.height) { fb.get(it) }
            com.amiri.cut.core.vision.SmartRoto.resample(conf, m.width, m.height, b.width, b.height)
        } finally { seg.close() }
    }.getOrNull()

    /**
     * Segments frames at [times] (source µs) and hands each mask (white + alpha, kept =
     * opaque) to [onMask]. Returns the number of frames processed.
     */
    fun run(
        context: Context, asset: MediaAsset, times: List<Long>, edgeSoftness: Float,
        isCancelled: () -> Boolean, onProgress: (Float) -> Unit, cleanEdges: Boolean = true, onMask: (Long, Bitmap) -> Unit,
    ): Int {
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.VIDEO)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        val seg = ImageSegmenter.createFromOptions(context, opts)
        var count = 0
        var prev: FloatArray? = null
        var lastTs = -1L
        try {
            GrayVideo(context, asset, if (cleanEdges) 640 else 384, rgb = true).use { gv ->
                val w = gv.w; val h = gv.h
                gv.scanRgb(times, isCancelled) { t, px ->
                    val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                    var ts = t / 1000
                    if (ts <= lastTs) ts = lastTs + 1
                    lastTs = ts
                    val res = seg.segmentForVideo(BitmapImageBuilder(bmp).build(), ts)
                    val masks = res.confidenceMasks().orElse(null)
                    val m = masks?.firstOrNull()
                    if (m != null) {
                        val mw = m.width; val mh = m.height
                        val fb = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        val conf = FloatArray(mw * mh) { fb.get(it) }
                        // Calm frame-to-frame flicker.
                        val p = prev
                        if (p != null && p.size == conf.size) for (i in conf.indices) conf[i] = conf[i] * 0.75f + p[i] * 0.25f
                        prev = conf
                        if (cleanEdges && mw == w && mh == h) {
                            // Snap the AI mask to the real edges of this frame (hair, hands, clothes).
                            val img = com.amiri.cut.core.vision.Segment.Image.fromArgb(px, w, h)
                            val a = com.amiri.cut.core.vision.SmartRoto.refine(img, conf, edgeSoftness)
                            onMask(t, RotoSmart.bitmap(a, w, h))
                            count++
                            bmp.recycle()
                            onProgress(count.toFloat() / times.size)
                            return@scanRgb true
                        }
                        val lo = 0.5f - 0.15f - edgeSoftness * 0.25f
                        val hi = 0.5f + 0.15f + edgeSoftness * 0.25f
                        val out = IntArray(mw * mh) { i ->
                            val x = ((conf[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
                            val a = (x * x * (3 - 2 * x) * 255).toInt()
                            (a shl 24) or 0xFFFFFF
                        }
                        onMask(t, Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888))
                        count++
                    }
                    bmp.recycle()
                    onProgress(count.toFloat() / times.size)
                    true
                }
            }
        } finally {
            seg.close()
        }
        return count
    }
}
