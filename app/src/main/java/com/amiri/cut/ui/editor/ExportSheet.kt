package com.amiri.cut.ui.editor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.export.EncoderCaps
import com.amiri.cut.export.ExportQuality
import com.amiri.cut.export.ExportQueue
import com.amiri.cut.export.ExportSettings
import com.amiri.cut.export.JobState
import com.amiri.cut.export.VideoCodec
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.GlassButton
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.LocalAccent
import kotlin.math.roundToInt

private data class ExportPreset(val label: String, val shortSide: Int?, val fps: Int?, val quality: ExportQuality, val codec: VideoCodec)

private val PRESETS = listOf(
    ExportPreset("Project", null, null, ExportQuality.HIGH, VideoCodec.H264),
    ExportPreset("Social 1080p", 1080, 30, ExportQuality.HIGH, VideoCodec.H264),
    ExportPreset("Smooth 1080p60", 1080, 60, ExportQuality.HIGH, VideoCodec.H264),
    ExportPreset("4K Master", 2160, null, ExportQuality.MAXIMUM, VideoCodec.HEVC),
    ExportPreset("Small 720p", 720, 30, ExportQuality.MEDIUM, VideoCodec.H264),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(c: EditorController, onDismiss: () -> Unit) {
    val p = c.project ?: return
    val accent = LocalAccent.current
    val aspect = p.settings.aspect
    val projShort = minOf(p.settings.width, p.settings.height)
    var short by remember { mutableStateOf(projShort) }
    var fps by remember { mutableStateOf(p.settings.fps) }
    var quality by remember { mutableStateOf(ExportQuality.HIGH) }
    var codec by remember { mutableStateOf(VideoCodec.H264) }
    var customBitrate by remember { mutableStateOf(false) }
    var mbps by remember { mutableFloatStateOf(16f) }

    fun dims(s: Int): Pair<Int, Int> {
        fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
        return if (aspect < 1f) even(s.toFloat()) to even(s / aspect) else even(s * aspect) to even(s.toFloat())
    }
    val (w, h) = dims(short)
    val check = remember(codec, w, h, fps) { EncoderCaps.check(codec, w, h, fps) }
    val autoBitrate = EncoderCaps.bitrate(w, h, fps, quality, codec)
    val settings = ExportSettings(w, h, fps, quality, codec, if (customBitrate) (mbps * 1_000_000).toInt() else null)
    val jobs by ExportQueue.jobs.collectAsState()

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) c.enqueueExport(settings, uri)
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        createDoc.launch(p.name.replace(Regex("[^A-Za-z0-9 _\\-\\u0600-\\u06FF]"), "_") + ".mp4")
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Amiri.SurfaceHigh, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Export", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text("${FrameTime.timecode(p.durationUs, p.settings.fps)} · MP4 · AAC audio", color = Amiri.TextSecondary, fontSize = 12.sp)

            Label("Presets")
            var preset by remember { mutableStateOf(PRESETS[0]) }
            ChoiceChips(PRESETS, preset, { it.label }) { pr ->
                preset = pr
                short = pr.shortSide ?: projShort; fps = pr.fps ?: p.settings.fps; quality = pr.quality; codec = pr.codec
            }
            Label("Resolution")
            ChoiceChips(listOf(720, 1080, 1440, 2160), short, { if (it == 2160) "4K" else "${it}p" }) { short = it }
            Label("Frame rate")
            ChoiceChips(listOf(24, 25, 30, 50, 60), fps, { "$it fps" }) { fps = it }
            Label("Quality")
            ChoiceChips(ExportQuality.entries.toList(), quality, { it.label }) { quality = it }
            Label("Codec")
            ChoiceChips(VideoCodec.entries.toList(), codec, { it.label }) { codec = it }
            Label("Bitrate")
            ChoiceChips(listOf(false, true), customBitrate, { if (it) "Custom" else "Auto · ${"%.1f".format(autoBitrate / 1e6)} Mbps" }) { customBitrate = it }
            if (customBitrate) LabeledSlider("Mbps", mbps, 1f..120f, "${mbps.roundToInt()} Mbps") { mbps = it }

            Text("$w × $h · $fps fps", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(check.message, color = if (check.ok) Amiri.TextSecondary else Amiri.Danger, fontSize = 12.sp)
            check.suggestion?.let { s ->
                Text(
                    "Use ${s.codec.label} ${s.width}×${s.height} @ ${s.fps} fps instead",
                    color = accent, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp).clickable {
                        codec = s.codec; fps = s.fps
                        short = minOf(s.width, s.height)
                    },
                )
            }
            GlassButton("Export to…", Modifier.fillMaxWidth().padding(top = 12.dp), primary = true, enabled = check.ok && p.durationUs > 0) {
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                else createDoc.launch(p.name.replace(Regex("[^A-Za-z0-9 _\\-\\u0600-\\u06FF]"), "_") + ".mp4")
            }
            Text("Rendering runs on the device's GPU and hardware encoder, in the background — you can leave the editor.", color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

            if (jobs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("RENDER QUEUE", color = Amiri.TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Clear finished", color = accent, fontSize = 12.sp, modifier = Modifier.clickable { ExportQueue.clearFinished() })
                }
                jobs.forEach { j ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${j.name} · ${j.settings.width}×${j.settings.height}", color = Amiri.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (j.state == JobState.QUEUED || j.state == JobState.RUNNING) {
                                Text("Cancel", color = Amiri.Danger, fontSize = 12.sp, modifier = Modifier.clickable { ExportQueue.cancel(j.id) }.padding(6.dp))
                            }
                        }
                        val pr = j.progress
                        val status = when (j.state) {
                            JobState.QUEUED -> "Queued"
                            JobState.RUNNING -> if (pr == null) "Starting…" else
                                "${pr.stage} ${(pr.fraction * 100).toInt()}% · frame ${pr.frame}/${pr.totalFrames} · ${"%.1f".format(pr.fps)} fps · ${pr.etaSeconds / 60}:${"%02d".format(pr.etaSeconds % 60)} left"
                            JobState.DONE -> "Done — saved"
                            JobState.FAILED -> "Failed: ${j.error}"
                            JobState.CANCELLED -> "Cancelled"
                        }
                        Text(status, color = if (j.state == JobState.FAILED) Amiri.Danger else Amiri.TextSecondary, fontSize = 11.sp)
                        if (j.state == JobState.RUNNING) {
                            LinearProgressIndicator(
                                progress = { pr?.fraction ?: 0f }, color = accent, trackColor = Amiri.Surface,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t.uppercase(), color = Amiri.TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Suppress("unused")
private val keepArr = Arrangement.Center
