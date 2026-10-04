package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.vision.Segment
import com.amiri.cut.core.vision.SmartRoto

/** Android glue for the smart roto engine (bitmaps ↔ float maps, video frames). */
object RotoSmart {
    fun image(b: Bitmap): Segment.Image {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Segment.Image.fromArgb(px, b.width, b.height)
    }

    /** Mask alpha 0..1 at w×h (rescaled if the bitmap differs). */
    fun alpha(b: Bitmap, w: Int, h: Int): FloatArray {
        val s = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return FloatArray(w * h) { ((px[it] ushr 24) and 255) / 255f }
    }

    fun bitmap(a: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { ((a[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or 0xFFFFFF }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    class Result(val sourceUs: Long, val mask: Bitmap)

    /**
     * Carries [startMask] (painted at [fromUs]) forward frame by frame with motion +
     * edge re-segmentation. Frames decoded sequentially at mask size.
     */
    fun propagate(
        context: Context, asset: MediaAsset, startMask: Bitmap, fromUs: Long, toUs: Long, stepUs: Long,
        softness: Float, isCancelled: () -> Boolean, onProgress: (Float) -> Unit,
    ): List<Result> {
        val out = ArrayList<Result>()
        val times = ArrayList<Long>()
        var t = fromUs
        while (t <= toUs) { times += t; t += stepUs }
        if (times.size < 2) return out
        GrayVideo(context, asset, startMask.width, rgb = true).use { gv ->
            val w = gv.w; val h = gv.h
            val key = alpha(startMask, w, h)
            var tracker: SmartRoto.Tracker? = null
            gv.scanRgb(times, isCancelled) { ts, px ->
                val img = Segment.Image.fromArgb(px, w, h)
                val tr = tracker
                if (tr == null) tracker = SmartRoto.startTracker(img, key)
                else {
                    val m = SmartRoto.track(tr, img, softness)
                    val bmp = bitmap(m, w, h)
                    out += Result(ts, if (w != startMask.width || h != startMask.height) Bitmap.createScaledBitmap(bmp, startMask.width, startMask.height, true) else bmp)
                    onProgress(out.size.toFloat() / (times.size - 1))
                }
                true
            }
        }
        return out
    }
}
