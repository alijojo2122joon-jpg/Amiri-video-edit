package com.amiri.cut.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.storage.CacheManager
import com.amiri.cut.storage.PerformanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generates and caches timeline filmstrip thumbnails, in memory and on disk
 * (cache/preview/thumbs/<assetId>/<index>.jpg). Frames are decoded with the
 * platform retriever, which uses the hardware decoder where available.
 */
class ThumbnailCache(
    private val context: Context,
    private val cache: CacheManager,
    private val modeProvider: () -> PerformanceMode,
) {
    class Strip(val intervalUs: Long, val frames: Array<ImageBitmap?>, val aspect: Float) {
        /** Frame nearest to a source time; falls back to any decoded neighbour. */
        fun frameAt(sourceUs: Long): ImageBitmap? {
            if (frames.isEmpty()) return null
            val i = if (intervalUs <= 0) 0 else (sourceUs / intervalUs).toInt().coerceIn(0, frames.size - 1)
            frames[i]?.let { return it }
            for (d in 1 until frames.size) {
                frames.getOrNull(i - d)?.let { return it }
                frames.getOrNull(i + d)?.let { return it }
            }
            return null
        }
    }

    private val strips = ConcurrentHashMap<String, Strip>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(modeProvider().workers))

    private val _version = MutableStateFlow(0)
    /** Bumped whenever new frames are ready, so the timeline can redraw. */
    val version: StateFlow<Int> = _version

    fun strip(assetId: String): Strip? = strips[assetId]

    fun request(asset: MediaAsset) {
        if (asset.type == MediaType.AUDIO) return
        if (strips.containsKey(asset.id) || !inFlight.add(asset.id)) return
        scope.launch {
            try {
                if (asset.type == MediaType.IMAGE) loadImage(asset) else loadVideo(asset)
            } finally {
                inFlight.remove(asset.id)
            }
        }
    }

    /** Drops in-memory frames for an asset (e.g. after Replace Media). */
    fun invalidate(assetId: String) {
        strips.remove(assetId)
        File(cache.thumbsDir(), assetId).deleteRecursively()
        _version.value++
    }

    private fun loadImage(asset: MediaAsset) {
        val bmp = BitmapLoader.decodeImage(context, asset, THUMB_H * 2) ?: return
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        strips[asset.id] = Strip(Long.MAX_VALUE, arrayOf(bmp.asImageBitmap()), aspect)
        _version.value++
    }

    private suspend fun loadVideo(asset: MediaAsset) {
        val mode = modeProvider()
        val seconds = asset.durationUs / 1_000_000.0
        val count = (seconds * mode.thumbsPerMinute / 60.0).roundToInt().coerceIn(2, 240)
        val interval = max(1L, asset.durationUs / count)
        val dw = asset.displayWidth.coerceAtLeast(1)
        val dh = asset.displayHeight.coerceAtLeast(1)
        val aspect = dw.toFloat() / dh
        val th = THUMB_H
        val tw = (th * aspect).roundToInt().coerceAtLeast(8)
        val frames = arrayOfNulls<ImageBitmap>(count)
        val strip = Strip(interval, frames, aspect)
        strips[asset.id] = strip

        val dir = File(cache.thumbsDir(), asset.id).apply { mkdirs() }
        val retriever = MediaMetadataRetriever()
        var opened = false
        try {
            // Disk hits first (instant), then decode the missing frames.
            for (i in 0 until count) {
                val f = File(dir, "$i.jpg")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { frames[i] = it.asImageBitmap() }
            }
            _version.value++
            for (i in 0 until count) {
                if (!scope.isActive) break
                if (frames[i] != null) continue
                if (!opened) {
                    retriever.setDataSource(context, Uri.parse(asset.uri))
                    opened = true
                }
                val t = i * interval + interval / 2
                val bmp: Bitmap = runCatching {
                    retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                }.getOrNull() ?: continue
                frames[i] = bmp.asImageBitmap()
                runCatching { File(dir, "$i.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) } }
                if (i % 3 == 0) _version.value++
            }
        } catch (_: Throwable) {
            // Unreadable media: leave whatever frames we have.
        } finally {
            runCatching { retriever.release() }
            _version.value++
        }
    }

    companion object {
        const val THUMB_H = 96
    }
}
