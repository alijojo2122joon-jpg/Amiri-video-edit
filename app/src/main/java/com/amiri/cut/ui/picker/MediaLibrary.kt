package com.amiri.cut.ui.picker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/** One item of the device gallery (read through MediaStore — nothing is copied). */
data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val kind: Kind,
    val name: String,
    val durationMs: Long,
    val dateAdded: Long,
    val bucketId: String,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val artist: String = "",
) {
    enum class Kind { VIDEO, IMAGE, AUDIO }
    val key: String get() = uri.toString()
}

data class Album(val id: String, val name: String, val count: Int, val cover: GalleryItem?)

/** What access the user gave us to their photos & videos. */
enum class GalleryAccess { FULL, PARTIAL, NONE }

object MediaLibrary {
    const val ALL = "__all__"

    fun visualPermissions(includeAudio: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            if (includeAudio) add(Manifest.permission.READ_MEDIA_AUDIO)
        }.toTypedArray()
        Build.VERSION.SDK_INT >= 33 -> buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO)
            if (includeAudio) add(Manifest.permission.READ_MEDIA_AUDIO)
        }.toTypedArray()
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun visualAccess(ctx: Context): GalleryAccess = when {
        Build.VERSION.SDK_INT >= 33 && granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) && granted(ctx, Manifest.permission.READ_MEDIA_VIDEO) -> GalleryAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.PARTIAL
        Build.VERSION.SDK_INT >= 33 && (granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) || granted(ctx, Manifest.permission.READ_MEDIA_VIDEO)) -> GalleryAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryAccess.FULL
        else -> GalleryAccess.NONE
    }

    fun audioAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.READ_MEDIA_AUDIO)
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** All photos and videos, newest first (queried per collection so partial access works too). */
    suspend fun loadVisual(ctx: Context): List<GalleryItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<GalleryItem>(512)
        fun scan(base: Uri, kind: GalleryItem.Kind) {
            val video = kind == GalleryItem.Kind.VIDEO
            val proj = buildList {
                add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATE_ADDED); add(MediaStore.MediaColumns.BUCKET_ID)
                add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                if (video) add(MediaStore.Video.VideoColumns.DURATION)
            }.toTypedArray()
            runCatching {
                ctx.contentResolver.query(base, proj, "${MediaStore.MediaColumns.SIZE}>0", null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        out += GalleryItem(
                            id = id, uri = ContentUris.withAppendedId(base, id), kind = kind,
                            name = c.getString(1) ?: "", dateAdded = c.getLong(2),
                            bucketId = c.getString(3) ?: "", bucketName = c.getString(4) ?: "Other",
                            width = c.getInt(5), height = c.getInt(6),
                            durationMs = if (video) c.getLong(7) else 0L,
                        )
                    }
                }
            }
        }
        scan(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), GalleryItem.Kind.VIDEO)
        scan(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), GalleryItem.Kind.IMAGE)
        out.sortByDescending { it.dateAdded }
        out
    }

    /** Songs and other audio, newest first. */
    suspend fun loadAudio(ctx: Context): List<GalleryItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<GalleryItem>()
        val base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.BUCKET_ID, MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            ctx.contentResolver.query(base, proj, "${MediaStore.Audio.Media.DURATION}>1000", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += GalleryItem(
                        id = id, uri = ContentUris.withAppendedId(base, id), kind = GalleryItem.Kind.AUDIO,
                        name = c.getString(1) ?: "Audio", artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: "",
                        durationMs = c.getLong(3), dateAdded = c.getLong(4),
                        bucketId = c.getString(5) ?: "", bucketName = c.getString(6) ?: "Audio",
                        width = 0, height = 0,
                    )
                }
            }
        }
        out
    }

    fun albums(items: List<GalleryItem>): List<Album> {
        val groups = items.groupBy { it.bucketId }
        val list = groups.map { (id, l) -> Album(id, l.first().bucketName, l.size, l.firstOrNull()) }
            .sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.count })
        return listOf(Album(ALL, "Recents", items.size, items.firstOrNull())) + list
    }

    // ───────── thumbnails ─────────

    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(4)

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun thumbnail(ctx: Context, item: GalleryItem, px: Int = 256): ImageBitmap? {
        cache.get(item.key)?.let { return it }
        return withContext(thumbDispatcher) {
            cache.get(item.key) ?: runCatching {
                val b: Bitmap = ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
                b.asImageBitmap().also { cache.put(item.key, it) }
            }.getOrNull()
        }
    }

    /** A large preview bitmap for the full-screen viewer. */
    suspend fun preview(ctx: Context, item: GalleryItem, px: Int = 1440): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null).asImageBitmap() }.getOrNull()
    }

    fun durationLabel(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
