package com.amiri.cut.ui.newproject

import android.net.Uri
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.media.MediaProbe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Creates a project whose canvas matches the first picked video/photo (like CapCut's
 * "Original" ratio): portrait phone video → 9:16, landscape → 16:9, photos keep their shape.
 * The ratio can be changed any time in the editor (Ratio tool).
 */
object AutoProject {
    private val PRESETS = listOf(9 to 16, 16 to 9, 1 to 1, 4 to 5, 3 to 4, 4 to 3, 2 to 3, 3 to 2, 21 to 9)

    fun settingsFor(srcW: Int, srcH: Int): ProjectSettings {
        if (srcW <= 0 || srcH <= 0) return ProjectSettings(1080, 1920, 30, "9:16", "1080p")
        val ratio = srcW.toFloat() / srcH
        val preset = PRESETS.minByOrNull { (a, b) -> abs(ratio - a.toFloat() / b) / (a.toFloat() / b) }!!
        val presetRatio = preset.first.toFloat() / preset.second
        val usePreset = abs(ratio - presetRatio) / presetRatio < 0.03f
        val shortSrc = minOf(srcW, srcH)
        val short = when {
            shortSrc >= 1000 -> 1080
            shortSrc >= 700 -> 720
            else -> 720
        }
        fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
        val (w, h) = if (usePreset) frameSize(short, preset.first, preset.second)
        else if (srcW <= srcH) short to even(short * srcH.toFloat() / srcW) else even(short * srcW.toFloat() / srcH) to short
        val label = if (usePreset) "${preset.first}:${preset.second}" else "Original"
        return ProjectSettings(width = w, height = h, fps = 30, aspectLabel = label, resolutionLabel = if (short >= 1080) "1080p" else "720p")
    }

    suspend fun create(app: AmiriCutApp, uris: List<Uri>): Project {
        var settings = ProjectSettings(1080, 1920, 30, "9:16", "1080p")
        for (u in uris.take(4)) {
            val a = MediaProbe.probe(app, u) ?: continue
            if (a.type == MediaType.AUDIO) continue
            settings = settingsFor(a.displayWidth, a.displayHeight)
            break
        }
        val name = "Project " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        return app.projects.create(name, settings)
    }
}
