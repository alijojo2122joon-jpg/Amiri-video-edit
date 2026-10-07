package com.amiri.cut.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.ProjectSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Screenshot harness for CI (`am start … --es amiri_screen editor`): builds demo projects
 * from files pushed into the app's own external folder so every screen can be captured
 * on the emulator. Only runs when that intent extra is present; never touches user data.
 */
object UiDemo {
    private const val TAG = "AmiriUiDemo"

    data class Plan(val screen: String, val projectId: String? = null, val uris: List<Uri> = emptyList(), val extras: List<Uri> = emptyList())

    private fun dir(app: AmiriCutApp) = app.getExternalFilesDir(null)

    fun file(app: AmiriCutApp, name: String): File? = dir(app)?.let { File(it, name) }?.takeIf { it.exists() && it.length() > 0 }

    suspend fun prepare(app: AmiriCutApp, screen: String, scan: List<String>): Plan = withContext(Dispatchers.IO) {
        if (scan.isNotEmpty()) scanFiles(app, scan)
        when {
            screen == "home" -> { seedProjects(app); Plan(screen) }
            screen == "picker" -> Plan(screen)
            screen.startsWith("editor") || screen == "export" -> {
                val main = listOfNotNull(file(app, "demo1.mp4"), file(app, "demo_cat.jpg"), file(app, "demo2.mp4"), file(app, "selftest.mp4"))
                    .take(3).map { Uri.fromFile(it) }
                val extras = listOfNotNull(file(app, "demo_overlay.png"), file(app, "demo_music.wav")).map { Uri.fromFile(it) }
                val p = app.projects.create("Weekend in the hills", ProjectSettings(1080, 1920, 30, "9:16", "1080p"))
                Plan(screen, p.id, main, extras)
            }
            else -> Plan("home")
        }
    }

    private suspend fun scanFiles(app: AmiriCutApp, paths: List<String>) {
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                var left = paths.size
                MediaScannerConnection.scanFile(app, paths.toTypedArray(), null) { _, _ ->
                    left--
                    if (left <= 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
        Log.i(TAG, "scanned ${paths.size} files")
    }

    /** Three finished-looking projects so the Home screen grid can be reviewed. */
    private suspend fun seedProjects(app: AmiriCutApp) {
        if (app.projects.list().size >= 3) return
        val covers = listOfNotNull(file(app, "demo1.mp4"), file(app, "demo_cat.jpg"), file(app, "demo2.mp4"))
        val specs = listOf(
            Triple("Weekend in the hills", ProjectSettings(1080, 1920, 30, "9:16", "1080p"), 0),
            Triple("Mochi the cat", ProjectSettings(1080, 1350, 30, "4:5", "1080p"), 1),
            Triple("Travel reel", ProjectSettings(1920, 1080, 30, "16:9", "1080p"), 2),
        )
        for ((name, settings, ci) in specs) {
            val p = app.projects.create(name, settings)
            val cover = covers.getOrNull(ci) ?: covers.firstOrNull() ?: continue
            frameOf(cover)?.let { app.projects.writeThumb(p.id, it) }
        }
    }

    private fun frameOf(f: File): Bitmap? = runCatching {
        if (f.name.endsWith(".mp4")) {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(f.absolutePath); r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640) } finally { r.release() }
        } else BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
    }.getOrNull()
}
