package com.amiri.cut.export

import android.content.Context
import android.net.Uri
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.core.model.Track
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.model.newId
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds derived media files on device with the export pipeline:
 *  - reversed copies (for the Reverse tool)
 *  - low-resolution proxies (for Proxy mode)
 */
object Transcode {

    private fun evenSize(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        val s = longSide.toFloat() / max(w, h)
        val k = if (s < 1f) s else 1f
        return (((w * k).roundToInt() / 2) * 2).coerceAtLeast(16) to (((h * k).roundToInt() / 2) * 2).coerceAtLeast(16)
    }

    private fun singleClipProject(asset: MediaAsset, w: Int, h: Int, fps: Int): Project {
        val clip = Clip(newId(), asset.id, asset.name, 0, 0, asset.durationUs)
        return Project(
            id = "transcode", name = asset.name, createdAt = 0, modifiedAt = 0,
            settings = ProjectSettings(w, h, fps, "", "", CanvasBackground.BLACK),
            assets = listOf(asset),
            tracks = listOf(Track(newId(), TrackKind.VIDEO, "V1", clips = listOf(clip))),
        )
    }

    /** Writes a reversed copy of a video and returns it as a new asset. */
    fun reverse(
        context: Context, app: AmiriCutApp, projectId: String, asset: MediaAsset,
        cancelled: AtomicBoolean, onProgress: (Float) -> Unit,
    ): MediaAsset {
        require(asset.type == MediaType.VIDEO) { "Only videos can be reversed" }
        val (w, h) = evenSize(asset.displayWidth.coerceAtLeast(16), asset.displayHeight.coerceAtLeast(16), 1920)
        val dir = File(File(context.filesDir, "projects/$projectId"), "media").apply { mkdirs() }
        val out = File(dir, "reversed_${newId()}.mp4")
        val p = singleClipProject(asset, w, h, 30)
        Exporter(context, app, p, ExportSettings(w, h, 30, ExportQuality.MAXIMUM), out, cancelled, reverse = true) { pr ->
            onProgress(pr.fraction)
        }.run()
        return MediaAsset(
            id = newId(), uri = Uri.fromFile(out).toString(), type = MediaType.VIDEO,
            name = "Reversed · " + asset.name, durationUs = asset.durationUs, width = w, height = h, rotation = 0,
            hasAudio = asset.hasAudio,
        )
    }

    /** Builds a 540p proxy and returns its file URI. */
    fun proxy(context: Context, app: AmiriCutApp, asset: MediaAsset, cancelled: AtomicBoolean, onProgress: (Float) -> Unit): String {
        val (w, h) = evenSize(asset.displayWidth.coerceAtLeast(16), asset.displayHeight.coerceAtLeast(16), 960)
        val dir = File(app.caches.dir(com.amiri.cut.storage.CacheManager.Kind.PROXY), "").apply { mkdirs() }
        val out = File(dir, "${asset.id}.mp4")
        val p = singleClipProject(asset, w, h, 30)
        Exporter(context, app, p, ExportSettings(w, h, 30, ExportQuality.MEDIUM), out, cancelled) { pr -> onProgress(pr.fraction) }.run()
        return Uri.fromFile(out).toString()
    }
}
