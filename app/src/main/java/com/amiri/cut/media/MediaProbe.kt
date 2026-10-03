package com.amiri.cut.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads technical metadata from user-picked media (Storage Access Framework URIs). */
object MediaProbe {

    /** Keep read access to picked files across reboots so projects reopen without re-picking. */
    fun persistPermission(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun isReachable(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment ?: "media"
    }

    private fun guessType(mime: String?, name: String): MediaType? {
        if (mime != null) {
            when {
                mime.startsWith("video/") -> return MediaType.VIDEO
                mime.startsWith("image/") -> return MediaType.IMAGE
                mime.startsWith("audio/") -> return MediaType.AUDIO
            }
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mov", "mkv", "webm", "3gp", "m4v" -> MediaType.VIDEO
            "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif" -> MediaType.IMAGE
            "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus" -> MediaType.AUDIO
            else -> null
        }
    }

    suspend fun probe(context: Context, uri: Uri): MediaAsset? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val type = guessType(context.contentResolver.getType(uri), name) ?: return@withContext null
        when (type) {
            MediaType.IMAGE -> probeImage(context, uri, name)
            MediaType.VIDEO, MediaType.AUDIO -> probeAv(context, uri, name, type)
        }
    }

    private fun probeImage(context: Context, uri: Uri, name: String): MediaAsset? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        return MediaAsset(
            id = newId(), uri = uri.toString(), type = MediaType.IMAGE, name = name,
            durationUs = 0, width = opts.outWidth, height = opts.outHeight, rotation = rotation,
        )
    }

    private fun probeAv(context: Context, uri: Uri, name: String, type: MediaType): MediaAsset? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (durMs <= 0) return null
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val realType = if (type == MediaType.VIDEO && !hasVideo) MediaType.AUDIO else type
            MediaAsset(
                id = newId(), uri = uri.toString(), type = realType, name = name,
                durationUs = durMs * 1000, width = w, height = h, rotation = rot,
                hasAudio = hasAudio || realType == MediaType.AUDIO,
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
