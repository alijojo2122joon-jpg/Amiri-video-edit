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
        // Exports normally happen from the (silent) editor: keep the home purr off during the test.
        com.amiri.cut.ui.theme.CatSounds.purrOn = false
        com.amiri.cut.ui.theme.CatSounds.stopPurr()
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
                // Clip animations + an art sticker: exercises zoomed (high-density) layers,
                // bicubic compositing and the photo resampler.
                p = TimelineOps.updateClip(p, first.id) { c -> c.copy(anim = com.amiri.cut.core.model.ClipAnim(inId = "zoomIn")) } ?: p
                p = TimelineOps.updateClip(p, second.id) { c -> c.copy(anim = com.amiri.cut.core.model.ClipAnim(comboId = "kenBurnsIn")) } ?: p
                val sticker = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    runCatching {
                        val bmp = com.amiri.cut.ui.editor.StickerArt.render("paw", 640)
                        val f = File(dir, "selftest-sticker.png")
                        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        com.amiri.cut.core.model.MediaAsset(
                            id = newId(), uri = Uri.fromFile(f).toString(), type = com.amiri.cut.core.model.MediaType.IMAGE,
                            name = "Sticker paw", durationUs = 0, width = bmp.width, height = bmp.height,
                        )
                    }.onFailure { Log.e(TAG, "sticker failed", it) }.getOrNull()
                }
                if (sticker != null) TimelineOps.placeOverlay(p, sticker, 300_000L, 2_000_000L, 0.38f)?.let { (pp, sc) ->
                    p = TimelineOps.updateClip(pp, sc.id) { c -> c.copy(anim = com.amiri.cut.core.model.ClipAnim(inId = "pop", comboId = "rock")) } ?: pp
                }
                Log.i(TAG, "STICKER ${sticker != null}")
                Log.i(TAG, "ENCODER ${runCatching { EncoderCaps.describe(VideoCodec.H264, 720, 1280, 30, ExportQuality.HIGH) }.getOrElse { "error $it" }}")
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
                // Voice isolation smoke check on the test clip's tone (noise → should drop).
                val dn = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        val pcm = com.amiri.cut.media.AudioDecode.mono(app, a.uri, 0, 2_000_000L)!!
                        val t0 = System.currentTimeMillis()
                        val o = com.amiri.cut.media.VoiceIsolation.clean(app, pcm.data, pcm.rate, 1f, 1, { false }, {})
                        fun rms(x: FloatArray) = kotlin.math.sqrt(x.fold(0.0) { s, v -> s + v * v } / x.size.coerceAtLeast(1))
                        "in=%.4f out=%.4f n=%d rate=%d ms=%d".format(rms(pcm.data), rms(o), pcm.data.size, pcm.rate, System.currentTimeMillis() - t0)
                    }
                }
                // Smart roto: segment a centre stroke on frame 0, then track 10 frames.
                val roto = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        val t0 = System.currentTimeMillis()
                        var w0 = 0; var h0 = 0; var first: FloatArray? = null
                        com.amiri.cut.media.GrayVideo(app, a, 640, rgb = true).use { gv -> w0 = gv.w; h0 = gv.h
                            gv.scanRgb(listOf(0L), { false }) { _, px ->
                                val img = com.amiri.cut.core.vision.Segment.Image.fromArgb(px, gv.w, gv.h)
                                val st = BooleanArray(gv.w * gv.h) { i -> val x = i % gv.w; val y = i / gv.w; kotlin.math.abs(y - gv.h / 2) < 6 && kotlin.math.abs(x - gv.w / 2) < gv.w / 8 }
                                first = com.amiri.cut.core.vision.SmartRoto.stroke(img, null, st, true); true } }
                        val f = first!!
                        val t1 = System.currentTimeMillis()
                        val res = com.amiri.cut.media.RotoSmart.propagate(app, a, com.amiri.cut.media.RotoSmart.bitmap(f, w0, h0), 0L, 333_333L, 33_333L, 0.45f, { false }, {})
                        "size=${w0}x$h0 strokeMs=${t1 - t0} area=%.3f tracked=${res.size} msPerFrame=${(System.currentTimeMillis() - t1) / res.size.coerceAtLeast(1)}".format(f.count { it > 0.5f }.toFloat() / f.size)
                    }
                }
                Log.i(TAG, "ROTO ${roto.getOrNull()} error=${roto.exceptionOrNull()}")
                Log.i(TAG, "DENOISE ${dn.getOrNull()} error=${dn.exceptionOrNull()}")
                Log.i(TAG, "CUTOUT available=${com.amiri.cut.media.AutoCutout.available(app)} result=${cut.getOrNull()} error=${cut.exceptionOrNull()}")
                var waited = 0
                while (waited < 600) {
                    val j = ExportQueue.jobs.value.lastOrNull { it.name == "selftest" }
                    if (j != null && (j.state == JobState.DONE || j.state == JobState.FAILED || j.state == JobState.CANCELLED)) {
                        Log.i(TAG, "ENCODED ${encodedInfo(out)}")
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

    /** Codec, size, H.264 profile/level (from the SPS) and bitrate of the exported file. */
    private fun encodedInfo(f: File): String = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return@runCatching "no video track"
            var prof = -1
            var lvl = -1
            fmt.getByteBuffer("csd-0")?.let { bb ->
                val b = ByteArray(bb.remaining()); bb.get(b)
                for (i in 0 until b.size - 6) {
                    if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 && (b[i + 3].toInt() and 0x1f) == 7) {
                        prof = b[i + 4].toInt() and 0xff; lvl = b[i + 6].toInt() and 0xff; break
                    }
                }
            }
            val durUs = if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) fmt.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
            val kbps = if (durUs > 0) f.length() * 8_000L / durUs else -1L
            "${fmt.getString(android.media.MediaFormat.KEY_MIME)} ${fmt.getInteger(android.media.MediaFormat.KEY_WIDTH)}x${fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT)} " +
                "profile_idc=$prof level_idc=$lvl avg_kbps=$kbps"
        } finally {
            ex.release()
        }
    }.getOrElse { "error $it" }
}
