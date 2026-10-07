package com.amiri.cut.ui.editor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.export.EncoderCaps
import com.amiri.cut.export.ExportQuality
import com.amiri.cut.export.ExportQueue
import com.amiri.cut.export.ExportSettings
import com.amiri.cut.export.JobState
import com.amiri.cut.export.VideoCodec
import com.amiri.cut.ui.common.AmiriSlider
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.PrimaryButton
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.MonoStyle
import kotlin.math.roundToInt

private val RES = listOf(480, 720, 1080, 1440, 2160)
private val FPS = listOf(24, 25, 30, 50, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(c: EditorController, onDismiss: () -> Unit) {
    val p = c.project ?: return
    val accent = LocalAccent.current
    val aspect = p.settings.aspect
    val projShort = minOf(p.settings.width, p.settings.height)
    var short by remember { mutableStateOf(RES.minByOrNull { kotlin.math.abs(it - projShort) } ?: 1080) }
    var fps by remember { mutableStateOf(FPS.minByOrNull { kotlin.math.abs(it - p.settings.fps) } ?: 30) }
    var quality by remember { mutableStateOf(ExportQuality.HIGH) }
    var codec by remember { mutableStateOf(VideoCodec.H264) }
    var customBitrate by remember { mutableStateOf(false) }
    var mbps by remember { mutableFloatStateOf(16f) }
    var advanced by remember { mutableStateOf(false) }

    fun dims(s: Int): Pair<Int, Int> {
        fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
        return if (aspect < 1f) even(s.toFloat()) to even(s / aspect) else even(s * aspect) to even(s.toFloat())
    }
    val (w, h) = dims(short)
    val check = remember(codec, w, h, fps) { EncoderCaps.check(codec, w, h, fps) }
    val autoBitrate = EncoderCaps.bitrate(w, h, fps, quality, codec)
    val bitrate = if (customBitrate) (mbps * 1_000_000).toInt() else autoBitrate
    val settings = ExportSettings(w, h, fps, quality, codec, if (customBitrate) bitrate else null)
    val jobs by ExportQueue.jobs.collectAsState()
    val sizeMb = (bitrate.toDouble() + 192_000.0) * (p.durationUs / 1e6) / 8.0 / 1_000_000.0

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) c.enqueueExport(settings, uri)
    }
    val fileName = p.name.replace(Regex("[^A-Za-z0-9 _\\-\\u0600-\\u06FF]"), "_") + ".mp4"
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> createDoc.launch(fileName) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Amiri.Surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Amiri.SurfaceTop)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Export", color = Amiri.TextPrimary, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${FrameTime.shortClock(p.durationUs)} · MP4 · AAC audio", color = Amiri.TextSecondary, fontSize = 12.5.sp, style = MonoStyle,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )

            SettingCard("Resolution", if (short == 2160) "4K" else if (short == 1440) "2K" else "${short}p") {
                StopSlider(RES.map { if (it == 2160) "4K" else if (it == 1440) "2K" else "${it}p" }, RES.indexOf(short).coerceAtLeast(0)) { short = RES[it] }
            }
            SettingCard("Frame rate", "$fps fps") {
                StopSlider(FPS.map { "$it" }, FPS.indexOf(fps).coerceAtLeast(0)) { fps = FPS[it] }
            }
            val qs = ExportQuality.entries.toList()
            SettingCard("Quality", "${qualityName(quality)} · ${"%.1f".format(autoBitrate / 1e6)} Mbps") {
                StopSlider(qs.map { qualityName(it) }, qs.indexOf(quality)) { quality = qs[it] }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { advanced = !advanced }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", color = Amiri.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = Amiri.TextSecondary)
            }
            AnimatedVisibility(advanced) {
                Column {
                    Text("Codec", color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    ChoiceChips(VideoCodec.entries.toList(), codec, { it.label }) { codec = it }
                    Text("Bitrate", color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    ChoiceChips(listOf(false, true), customBitrate, { if (it) "Custom" else "Automatic" }) { customBitrate = it }
                    if (customBitrate) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        AmiriSlider(mbps, { mbps = it }, Modifier.weight(1f), 1f..120f)
                        Text("${mbps.roundToInt()} Mbps", color = Amiri.TextPrimary, style = MonoStyle, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }

            // summary
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Amiri.SurfaceHigh)
                    .border(1.dp, Amiri.Line, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("$w × $h · $fps fps", color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = MonoStyle)
                    Text(check.message, color = if (check.ok) Amiri.TextTertiary else Amiri.Danger, fontSize = 11.sp, maxLines = 2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("≈ " + if (sizeMb >= 1000) "%.1f GB".format(sizeMb / 1000) else "${sizeMb.roundToInt().coerceAtLeast(1)} MB", color = Amiri.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("file size", color = Amiri.TextTertiary, fontSize = 11.sp)
                }
            }
            check.suggestion?.let { s ->
                Text(
                    "Use ${s.codec.label} ${s.width}×${s.height} @ ${s.fps} fps instead",
                    color = Amiri.accentInk(accent), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp).clickable {
                        codec = s.codec; fps = s.fps
                        short = RES.minByOrNull { kotlin.math.abs(it - minOf(s.width, s.height)) } ?: short
                    },
                )
            }
            PrimaryButton(
                "Export video", Modifier.fillMaxWidth().padding(top = 14.dp), icon = Icons.Outlined.FileDownload,
                enabled = check.ok && p.durationUs > 0, height = 54.dp,
            ) {
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                else createDoc.launch(fileName)
            }
            Text(
                "Renders on the GPU and the hardware encoder, in the background — you can keep editing.",
                color = Amiri.TextTertiary, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            if (jobs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("RENDER QUEUE", color = Amiri.TextTertiary, style = MaterialTheme.typography.labelSmall, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
                    Text("Clear finished", color = Amiri.accentInk(accent), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { ExportQueue.clearFinished() })
                }
                jobs.forEach { j ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Amiri.SurfaceHigh)
                            .border(1.dp, Amiri.Line, RoundedCornerShape(16.dp)).padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${j.name} · ${j.settings.width}×${j.settings.height}", color = Amiri.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (j.state == JobState.QUEUED || j.state == JobState.RUNNING) {
                                Text("Cancel", color = Amiri.Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { ExportQueue.cancel(j.id) }.padding(6.dp))
                            }
                        }
                        val pr = j.progress
                        val status = when (j.state) {
                            JobState.QUEUED -> "Queued"
                            JobState.RUNNING -> if (pr == null) "Starting…" else
                                "${pr.stage} ${(pr.fraction * 100).toInt()}% · ${"%.1f".format(pr.fps)} fps · ${pr.etaSeconds / 60}:${"%02d".format(pr.etaSeconds % 60)} left"
                            JobState.DONE -> "Done — saved ✓"
                            JobState.FAILED -> "Failed: ${j.error}"
                            JobState.CANCELLED -> "Cancelled"
                        }
                        Text(status, color = when (j.state) { JobState.FAILED -> Amiri.Danger; JobState.DONE -> Amiri.Success; else -> Amiri.TextSecondary }, fontSize = 11.5.sp)
                        if (j.state == JobState.RUNNING) {
                            LinearProgressIndicator(
                                progress = { pr?.fraction ?: 0f }, color = Amiri.accentInk(accent), trackColor = Amiri.SurfaceTop,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun qualityName(q: ExportQuality) = when (q) {
    ExportQuality.LOW -> "Smaller"
    ExportQuality.MEDIUM -> "Standard"
    ExportQuality.HIGH -> "High"
    ExportQuality.MAXIMUM -> "Ultra"
}

@Composable
private fun SettingCard(title: String, value: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(Amiri.SurfaceHigh)
            .border(1.dp, Amiri.Line, RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Amiri.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, color = Amiri.accentInk(LocalAccent.current), fontSize = 13.sp, fontWeight = FontWeight.Bold, style = MonoStyle)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Discrete slider with labelled stops (CapCut's export controls). */
@Composable
private fun StopSlider(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    val accent = LocalAccent.current
    val ink = Amiri.accentInk(accent)
    val n = labels.size
    val cb by rememberUpdatedState(onSelect)
    val sel by rememberUpdatedState(selected)
    Column {
        Box(
            Modifier.fillMaxWidth().height(30.dp)
                .pointerInput(n) {
                    detectTapGestures { o ->
                        val pad = 10.dp.toPx()
                        val f = ((o.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        val i = (f * (n - 1)).roundToInt()
                        if (i != sel) { Haptics.tick(view); cb(i) }
                    }
                }
                .pointerInput(n) {
                    detectHorizontalDragGestures { ch, _ ->
                        val pad = 10.dp.toPx()
                        val f = ((ch.position.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        val i = (f * (n - 1)).roundToInt()
                        if (i != sel) { Haptics.tick(view); cb(i) }
                        ch.consume()
                    }
                }
                .drawBehind {
                    val pad = 10.dp.toPx()
                    val cy = size.height / 2f
                    val usable = size.width - 2 * pad
                    val th = 3.dp.toPx()
                    drawRoundRect(Color.White.copy(alpha = 0.13f), Offset(pad, cy - th / 2), Size(usable, th), CornerRadius(th))
                    val x = pad + usable * (sel.toFloat() / (n - 1).coerceAtLeast(1))
                    drawRoundRect(ink, Offset(pad, cy - th / 2), Size(x - pad, th), CornerRadius(th))
                    for (i in 0 until n) {
                        val sx = pad + usable * (i.toFloat() / (n - 1).coerceAtLeast(1))
                        drawCircle(if (i <= sel) ink else Color.White.copy(alpha = 0.3f), 3.dp.toPx(), Offset(sx, cy))
                    }
                    drawCircle(Color.Black.copy(alpha = 0.35f), 11.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
                    drawCircle(Color.White, 9.5.dp.toPx(), Offset(x, cy))
                },
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, color = if (i == selected) Amiri.TextPrimary else Amiri.TextTertiary, fontSize = 11.sp,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = when (i) { 0 -> TextAlign.Start; n - 1 -> TextAlign.End; else -> TextAlign.Center },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
