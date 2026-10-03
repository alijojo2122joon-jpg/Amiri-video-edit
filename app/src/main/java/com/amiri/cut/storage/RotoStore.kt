package com.amiri.cut.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.amiri.cut.core.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Roto mask images: files/projects/<projectId>/roto/<name>.png
 * Files are write-once (every edit writes a new file), so undo/redo, split and
 * duplicate can safely share them.
 */
class RotoStore(context: Context) {
    private val root = File(context.filesDir, "projects")

    private val cache = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun dir(projectId: String) = File(File(root, projectId), "roto").apply { mkdirs() }

    /** Saves a mask and returns its file name. Call off the main thread. */
    fun saveBlocking(projectId: String, mask: Bitmap): String {
        val name = newId() + ".png"
        val f = File(dir(projectId), name)
        val tmp = File(f.parentFile, "$name.tmp")
        tmp.outputStream().use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
        cache.put("$projectId/$name", mask)
        return name
    }

    suspend fun save(projectId: String, mask: Bitmap): String = withContext(Dispatchers.IO) { saveBlocking(projectId, mask) }

    fun cached(projectId: String, name: String): Bitmap? = cache.get("$projectId/$name")

    /** Loads (and caches) a mask. Safe on any thread; disk read is ~ms for a 640 px mask. */
    fun load(projectId: String, name: String): Bitmap? {
        val key = "$projectId/$name"
        cache.get(key)?.let { return it }
        val f = File(dir(projectId), name)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = false }
        val b = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        cache.put(key, b)
        return b
    }

    suspend fun preload(projectId: String, names: List<String>) = withContext(Dispatchers.IO) {
        for (n in names) load(projectId, n)
    }
}
