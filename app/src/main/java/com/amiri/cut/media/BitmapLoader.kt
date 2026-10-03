package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.amiri.cut.core.model.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Bitmap decoding helpers with down-sampling and EXIF rotation. */
object BitmapLoader {

    private val previewCache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Decodes an image so its longest side is ≈ [maxSide], rotated upright. */
    fun decodeImage(context: Context, asset: MediaAsset, maxSide: Int): Bitmap? = runCatching {
        val uri = Uri.parse(asset.uri)
        val longest = max(asset.width, asset.height).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        if (asset.rotation == 0) raw
        else {
            val m = Matrix().apply { postRotate(asset.rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()

    /** Image for the preview canvas (cached). */
    suspend fun previewImage(context: Context, asset: MediaAsset, maxSide: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${asset.id}@$maxSide"
            previewCache.get(key) ?: decodeImage(context, asset, maxSide)?.also { previewCache.put(key, it) }
        }

    /** A single frame from a video (used for project covers). */
    fun videoFrame(context: Context, asset: MediaAsset, atUs: Long, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(asset.uri))
            val w = asset.displayWidth.coerceAtLeast(1)
            val h = asset.displayHeight.coerceAtLeast(1)
            val scale = maxSide.toFloat() / max(w, h)
            r.getScaledFrameAtTime(
                atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (w * scale).toInt().coerceAtLeast(2), (h * scale).toInt().coerceAtLeast(2),
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
