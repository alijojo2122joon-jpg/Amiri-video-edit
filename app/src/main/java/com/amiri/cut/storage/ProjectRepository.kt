package com.amiri.cut.storage

import android.content.Context
import android.graphics.Bitmap
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.core.model.ProjectSummary
import com.amiri.cut.core.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * On-device project storage. Layout:
 *
 *   files/projects/<id>/project.json   last explicitly saved state (source of truth)
 *   files/projects/<id>/autosave.json  newest autosave while the editor is open
 *   files/projects/<id>/session.lock   exists while the project is open in the editor
 *   files/projects/<id>/thumb.jpg      cover image for the Home screen
 *
 * A project whose lock file survived (app crashed / was killed) and whose autosave is
 * newer than project.json is offered for recovery. All writes are atomic (temp + rename).
 */
class ProjectRepository(context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun dir(id: String) = File(root, id)
    private fun projectFile(id: String) = File(dir(id), "project.json")
    private fun autosaveFile(id: String) = File(dir(id), "autosave.json")
    private fun lockFile(id: String) = File(dir(id), "session.lock")
    fun thumbFile(id: String) = File(dir(id), "thumb.jpg")

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readProject(f: File): Project? = runCatching {
        json.decodeFromString(Project.serializer(), f.readText())
    }.getOrNull()

    suspend fun list(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            val p = readProject(File(d, "project.json")) ?: readProject(File(d, "autosave.json")) ?: return@mapNotNull null
            ProjectSummary(
                id = p.id,
                name = p.name,
                width = p.settings.width,
                height = p.settings.height,
                fps = p.settings.fps,
                aspectLabel = p.settings.aspectLabel,
                modifiedAt = p.modifiedAt,
                durationUs = p.durationUs,
                thumbPath = thumbFile(p.id).takeIf { it.exists() }?.absolutePath,
                hasRecovery = hasRecoverySync(p.id),
            )
        }?.sortedByDescending { it.modifiedAt } ?: emptyList()
    }

    suspend fun create(name: String, settings: ProjectSettings): Project = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val p = Project(
            id = newId(),
            name = name,
            createdAt = now,
            modifiedAt = now,
            settings = settings,
            tracks = Project.defaultTracks(),
        )
        writeAtomic(projectFile(p.id), json.encodeToString(Project.serializer(), p).toByteArray())
        p
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        readProject(projectFile(id)) ?: readProject(autosaveFile(id))
    }

    suspend fun loadAutosave(id: String): Project? = withContext(Dispatchers.IO) { readProject(autosaveFile(id)) }

    /** Explicit save: writes project.json and discards the autosave. */
    suspend fun save(p: Project) = withContext(Dispatchers.IO) {
        writeAtomic(projectFile(p.id), json.encodeToString(Project.serializer(), p).toByteArray())
        autosaveFile(p.id).delete()
    }

    suspend fun autosave(p: Project) = withContext(Dispatchers.IO) {
        writeAtomic(autosaveFile(p.id), json.encodeToString(Project.serializer(), p).toByteArray())
    }

    fun markOpen(id: String) {
        runCatching { lockFile(id).writeText(System.currentTimeMillis().toString()) }
    }

    fun markClosed(id: String) {
        lockFile(id).delete()
    }

    private fun hasRecoverySync(id: String): Boolean {
        val a = autosaveFile(id)
        if (!lockFile(id).exists() || !a.exists()) return false
        val p = projectFile(id)
        return !p.exists() || a.lastModified() > p.lastModified()
    }

    suspend fun hasRecovery(id: String): Boolean = withContext(Dispatchers.IO) { hasRecoverySync(id) }

    /** User chose not to recover: drop the autosave and stale lock. */
    suspend fun discardRecovery(id: String) = withContext(Dispatchers.IO) {
        autosaveFile(id).delete()
        lockFile(id).delete()
    }

    suspend fun rename(id: String, newName: String) = withContext(Dispatchers.IO) {
        val p = readProject(projectFile(id)) ?: return@withContext
        writeAtomic(projectFile(id), json.encodeToString(Project.serializer(), p.copy(name = newName)).toByteArray())
    }

    suspend fun duplicate(id: String): Project? = withContext(Dispatchers.IO) {
        val p = readProject(projectFile(id)) ?: readProject(autosaveFile(id)) ?: return@withContext null
        val now = System.currentTimeMillis()
        val copy = p.copy(id = newId(), name = p.name + " copy", createdAt = now, modifiedAt = now)
        writeAtomic(projectFile(copy.id), json.encodeToString(Project.serializer(), copy).toByteArray())
        thumbFile(id).takeIf { it.exists() }?.copyTo(thumbFile(copy.id), overwrite = true)
        copy
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
    }

    suspend fun writeThumb(id: String, bmp: Bitmap) = withContext(Dispatchers.IO) {
        val f = thumbFile(id)
        val tmp = File(f.parentFile, "thumb.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        tmp.renameTo(f)
    }

    // ───────────── Backup / Restore (.amiricut = zip of project.json + thumb) ─────────────

    suspend fun exportBackup(id: String, out: OutputStream) = withContext(Dispatchers.IO) {
        val p = readProject(autosaveFile(id))?.takeIf { hasRecoverySync(id) } ?: readProject(projectFile(id))
            ?: error("Project not found")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(json.encodeToString(Project.serializer(), p).toByteArray())
            zip.closeEntry()
            val t = thumbFile(id)
            if (t.exists()) {
                zip.putNextEntry(ZipEntry("thumb.jpg"))
                t.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Restores a backup as a NEW project (never overwrites an existing one). */
    suspend fun importBackup(input: InputStream): Project = withContext(Dispatchers.IO) {
        var project: Project? = null
        var thumb: ByteArray? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    "project.json" -> project = json.decodeFromString(Project.serializer(), zip.readBytes().decodeToString())
                    "thumb.jpg" -> thumb = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        val src = project ?: error("Not an AMIRI CUT backup")
        val now = System.currentTimeMillis()
        val p = src.copy(id = newId(), modifiedAt = now)
        writeAtomic(projectFile(p.id), json.encodeToString(Project.serializer(), p).toByteArray())
        thumb?.let { writeAtomic(thumbFile(p.id), it) }
        p
    }
}
