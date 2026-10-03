package com.amiri.cut.storage

import android.content.Context
import android.net.Uri
import com.amiri.cut.render.LutLoader
import java.io.File

/** Imported .cube LUTs live in files/luts (copied, so they keep working offline forever). */
class LutStore(private val context: Context) {
    private val dir = File(context.filesDir, "luts").apply { mkdirs() }

    fun list(): List<String> = dir.listFiles()?.filter { it.extension.equals("cube", true) }?.map { it.name }?.sorted() ?: emptyList()

    fun file(name: String): File? = File(dir, name).takeIf { it.exists() }

    /** Copies and validates a .cube file. Returns its stored name, or null if invalid. */
    fun import(uri: Uri, displayName: String): String? {
        val base = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _\\-]"), "_").ifBlank { "lut" }
        var f = File(dir, "$base.cube")
        var n = 2
        while (f.exists()) { f = File(dir, "$base $n.cube"); n++ }
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            if (LutLoader.load(f) == null) { f.delete(); null } else f.name
        }.getOrNull()
    }

    fun delete(name: String) { File(dir, name).delete() }
}
