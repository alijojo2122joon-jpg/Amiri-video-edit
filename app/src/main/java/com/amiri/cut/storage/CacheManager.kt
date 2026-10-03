package com.amiri.cut.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns every cache directory so the Cache Manager can measure and clear them.
 * Caches are disposable: deleting them never touches projects or source media.
 */
class CacheManager(context: Context) {
    enum class Kind(val label: String, val dirName: String) {
        PREVIEW("Preview cache (thumbnails & waveforms)", "preview"),
        PROXY("Proxy files", "proxy"),
        RENDER("Render cache", "render"),
    }

    private val base = context.cacheDir

    fun dir(kind: Kind): File = File(base, kind.dirName).apply { mkdirs() }
    fun thumbsDir(): File = File(dir(Kind.PREVIEW), "thumbs").apply { mkdirs() }
    fun waveformsDir(): File = File(dir(Kind.PREVIEW), "waveforms").apply { mkdirs() }

    suspend fun size(kind: Kind): Long = withContext(Dispatchers.IO) {
        dir(kind).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clear(kind: Kind) = withContext(Dispatchers.IO) {
        dir(kind).listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        fun formatBytes(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
            else -> "$b B"
        }
    }
}
