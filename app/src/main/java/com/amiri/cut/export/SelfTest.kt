package com.amiri.cut.export

import android.app.Activity
import android.net.Uri
import android.util.Log
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.Effect
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.core.model.ShapeKind
import com.amiri.cut.core.model.Transition
import com.amiri.cut.core.model.newId
import com.amiri.cut.core.timeline.TimelineOps
import com.amiri.cut.media.MediaProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Developer self-test (started with `am start … --ez amiri_selftest true`): builds a
 * project from `files/selftest.mp4` in the app's own external folder that uses most
 * render features, exports it through the normal render queue / foreground service and
 * logs the result with tag AmiriSelfTest. Does nothing if that file isn't there.
 */
object SelfTest {
    private const val TAG = "AmiriSelfTest"

    fun run(activity: Activity) {
        val app = activity.application as AmiriCutApp
        val dir = activity.getExternalFilesDir(null) ?: return
        val src = File(dir, "selftest.mp4")
        if (!src.exists()) { Log.w(TAG, "RESULT skipped: no selftest.mp4"); return }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val a = MediaProbe.probe(app, Uri.fromFile(src)) ?: run { Log.e(TAG, "RESULT FAILED probe"); return@launch }
                val now = System.currentTimeMillis()
                var p = Project(id = newId(), name = "selftest", createdAt = now, modifiedAt = now,
                    settings = ProjectSettings(720, 1280, 30, "9:16", "720p"), tracks = Project.defaultTracks())
                p = TimelineOps.appendToMain(p, a).first
                p = TimelineOps.appendToMain(p, a).first
                val main = p.tracks.first { t -> t.clips.size == 2 }
                val second = main.clips[1]
                p = TimelineOps.setTransition(p, second.id, Transition("whip", 800_000L))!!
                val first = main.clips[0]
                p = TimelineOps.updateClip(p, first.id) { c ->
                    c.copy(effects = listOf(
                        Effect(newId(), "color", opts = mapOf("look" to "Light Nostalgic")),
                        Effect(newId(), "glow"), Effect(newId(), "wave"),
                    ))
                } ?: p
                val (pt, txt) = TimelineOps.addText(p, 200_000L, "سلام Amiri Cut", 2_500_000L)
                p = TimelineOps.updateClip(pt, txt.id) { c -> c.copy(text = c.text?.let { com.amiri.cut.core.text.TextPresets.apply(it, com.amiri.cut.core.text.TextPresets.ALL[0]) }) } ?: pt
                val (pt2, txt2) = TimelineOps.addText(p, 2_800_000L, "Wave text", 2_000_000L)
                p = TimelineOps.updateClip(pt2, txt2.id) { c -> c.copy(text = c.text?.let { com.amiri.cut.core.text.TextPresets.apply(it, com.amiri.cut.core.text.TextPresets.ALL[9]) }) } ?: pt2
                val (p2, shp) = TimelineOps.addPathShape(p, 0, listOf(0.2f, 0.3f, 0f, 0f, 0.1f, 0f, 0.8f, 0.7f, -0.1f, 0f, 0f, 0f), false)
                p = TimelineOps.updateClip(p2, shp.id) { c -> c.copy(effects = listOf(Effect(newId(), "saber", opts = mapOf("source" to "Layer path")))) } ?: p2
                p = TimelineOps.addShape(p, 500_000L, ShapeKind.STAR).first
                val json = app.projects.json.encodeToString(Project.serializer(), p)
                val out = File(dir, "selftest-out.mp4")
                out.delete()
                out.createNewFile()
                ExportQueue.enqueue(app, "selftest", p, ExportSettings(720, 1280, 30), Uri.fromFile(out), json)
                Log.i(TAG, "enqueued")
                // Auto cut-out smoke check (no person in the test clip; must not crash).
                val cut = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        com.amiri.cut.media.AutoCutout.run(app, a, listOf(0L, 33_333L, 66_666L), 0.3f, { false }, {}) { _, _ -> }
                    }
                }
                Log.i(TAG, "CUTOUT available=${com.amiri.cut.media.AutoCutout.available(app)} result=${cut.getOrNull()} error=${cut.exceptionOrNull()}")
                var waited = 0
                while (waited < 600) {
                    val j = ExportQueue.jobs.value.lastOrNull { it.name == "selftest" }
                    if (j != null && (j.state == JobState.DONE || j.state == JobState.FAILED || j.state == JobState.CANCELLED)) {
                        Log.i(TAG, "RESULT ${j.state} error=${j.error} bytes=${out.length()}")
                        return@launch
                    }
                    if (waited % 10 == 0) Log.i(TAG, "progress ${j?.state} ${j?.progress?.frame}/${j?.progress?.totalFrames}")
                    delay(500)
                    waited++
                }
                Log.e(TAG, "RESULT TIMEOUT")
            } catch (t: Throwable) {
                Log.e(TAG, "RESULT FAILED ${t.javaClass.name}: ${t.message}", t)
            }
        }
    }
}
