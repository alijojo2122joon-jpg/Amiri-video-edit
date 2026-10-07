package com.amiri.cut.ui.newproject

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.DiscButton
import com.amiri.cut.ui.common.GlassButton
import com.amiri.cut.ui.common.GroupLabel
import com.amiri.cut.ui.common.PrimaryButton
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class Res(val label: String, val shortSide: Int)
private data class Aspect(val label: String, val w: Int, val h: Int, val use: String)

private val RESOLUTIONS = listOf(Res("720p", 720), Res("1080p", 1080), Res("2K", 1440), Res("4K", 2160))
private val ASPECTS = listOf(
    Aspect("9:16", 9, 16, "TikTok · Reels"), Aspect("16:9", 16, 9, "YouTube"), Aspect("1:1", 1, 1, "Square"),
    Aspect("4:5", 4, 5, "Instagram"), Aspect("3:4", 3, 4, "Portrait"), Aspect("4:3", 4, 3, "Classic"),
    Aspect("21:9", 21, 9, "Cinema"), Aspect("Custom", 0, 0, "Your size"),
)
private val FPS = listOf(24, 25, 30, 50, 60)

/** Frame size from resolution class + aspect: the short side gets the resolution value. Always even. */
internal fun frameSize(shortSide: Int, aw: Int, ah: Int): Pair<Int, Int> {
    fun even(v: Double) = ((v / 2.0).roundToInt() * 2).coerceAtLeast(2)
    return if (aw <= ah) {
        even(shortSide.toDouble()) to even(shortSide.toDouble() * ah / aw)
    } else {
        even(shortSide.toDouble() * aw / ah) to even(shortSide.toDouble())
    }
}

@Composable
fun NewProjectScreen(
    app: AmiriCutApp,
    onBack: () -> Unit,
    onCreated: (projectId: String, importUris: List<Uri>) -> Unit,
) {
    com.amiri.cut.ui.common.PurrWhileVisible()
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    var name by remember { mutableStateOf("Edit " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())) }
    var res by remember { mutableStateOf(RESOLUTIONS[1]) }
    var aspect by remember { mutableStateOf(ASPECTS[0]) }
    var customW by remember { mutableStateOf("3") }
    var customH by remember { mutableStateOf("2") }
    var fps by remember { mutableStateOf(30) }
    var bg by remember { mutableStateOf(CanvasBackground.BLACK) }
    var busy by remember { mutableStateOf(false) }

    val aw = if (aspect.label == "Custom") customW.toIntOrNull()?.coerceIn(1, 100) ?: 1 else aspect.w
    val ah = if (aspect.label == "Custom") customH.toIntOrNull()?.coerceIn(1, 100) ?: 1 else aspect.h
    val (fw, fh) = frameSize(res.shortSide, aw, ah)

    fun create(uris: List<Uri>) {
        if (busy) return
        busy = true
        scope.launch {
            val settings = ProjectSettings(
                width = fw, height = fh, fps = fps,
                aspectLabel = if (aspect.label == "Custom") "$aw:$ah" else aspect.label,
                resolutionLabel = res.label, background = bg,
            )
            val p = app.projects.create(name.ifBlank { "Untitled" }, settings)
            onCreated(p.id, uris)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) create(uris)
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                DiscButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                Text("Custom canvas", color = Amiri.TextPrimary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 14.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                // Live canvas preview
                CanvasPreview(aw.toFloat() / ah, bg, "$fw × $fh", "$fps fps · ${if (aspect.label == "Custom") "$aw:$ah" else aspect.label}")
                if (fw > 3840 || fh > 3840) {
                    Text(
                        "Very large frame: some phones can't encode it. Export checks your device and suggests the closest size it supports.",
                        color = Amiri.Warning, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }

                GroupLabel("Name")
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amiri.accentInk(accent), unfocusedBorderColor = Amiri.Line,
                        focusedContainerColor = Amiri.Surface, unfocusedContainerColor = Amiri.Surface,
                        cursorColor = Amiri.accentInk(accent), focusedTextColor = Amiri.TextPrimary, unfocusedTextColor = Amiri.TextPrimary,
                    ),
                )

                GroupLabel("Aspect ratio")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ASPECTS.forEach { a -> AspectTile(a, a == aspect) { aspect = a } }
                }
                if (aspect.label == "Custom") {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumberField("Width", customW) { customW = it }
                        Text(":", color = Amiri.TextSecondary, fontSize = 18.sp)
                        NumberField("Height", customH) { customH = it }
                    }
                }

                GroupLabel("Resolution")
                ChoiceChips(RESOLUTIONS, res, { it.label }) { res = it }
                GroupLabel("Frame rate")
                ChoiceChips(FPS, fps, { "$it fps" }) { fps = it }
                GroupLabel("Background")
                ChoiceChips(CanvasBackground.entries.toList(), bg, { if (it == CanvasBackground.BLACK) "Black" else "Transparent" }) { bg = it }
                if (bg == CanvasBackground.TRANSPARENT) {
                    Text(
                        "Transparent areas show as a checkerboard while you edit. MP4 has no transparency, so they export as black.",
                        color = Amiri.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Empty project", Modifier.weight(1f), enabled = !busy) { create(emptyList()) }
                PrimaryButton("Add media", Modifier.weight(1.3f), icon = Icons.Outlined.AddPhotoAlternate, enabled = !busy) {
                    picker.launch(arrayOf("video/*", "image/*", "audio/*"))
                }
            }
        }
    }
}

@Composable
private fun CanvasPreview(ratio: Float, bg: CanvasBackground, dims: String, sub: String) {
    val accent = LocalAccent.current
    val r by animateFloatAsState(ratio.coerceIn(0.2f, 5f), label = "ratio")
    Box(
        Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(22.dp)).background(Amiri.Surface)
            .border(1.dp, Amiri.Line, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding(top = 18.dp, bottom = 58.dp, start = 18.dp, end = 18.dp).drawBehind {
                // Fit the frame inside the available area.
                val maxW = size.width; val maxH = size.height
                val w: Float; val h: Float
                if (maxW / maxH > r) { h = maxH; w = h * r } else { w = maxW; h = w / r }
                val tl = Offset((maxW - w) / 2f, (maxH - h) / 2f)
                val cr = CornerRadius(8.dp.toPx())
                if (bg == CanvasBackground.BLACK) drawRoundRect(Color.Black, tl, Size(w, h), cr)
                else {
                    val cell = 8.dp.toPx()
                    drawRoundRect(Color(0xFF2A2A2E), tl, Size(w, h), cr)
                    var y = 0
                    while (y * cell < h) {
                        var x = 0
                        while (x * cell < w) {
                            if ((x + y) % 2 == 0) drawRect(
                                Color(0xFF38383D), Offset(tl.x + x * cell, tl.y + y * cell),
                                Size(minOf(cell, w - x * cell), minOf(cell, h - y * cell)),
                            )
                            x++
                        }
                        y++
                    }
                }
                drawRoundRect(Amiri.accentInk(accent), tl, Size(w, h), cr, style = Stroke(1.5.dp.toPx()))
                // Rule-of-thirds guides
                val g = Color.White.copy(alpha = 0.12f)
                for (i in 1..2) {
                    drawLine(g, Offset(tl.x + w * i / 3f, tl.y), Offset(tl.x + w * i / 3f, tl.y + h), 1f)
                    drawLine(g, Offset(tl.x, tl.y + h * i / 3f), Offset(tl.x + w, tl.y + h * i / 3f), 1f)
                }
            },
        )
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dims, color = Amiri.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = MonoStyle)
            Text(sub, color = Amiri.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AspectTile(a: Aspect, selected: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(78.dp).clip(shape).background(if (selected) Amiri.SurfaceHighest else Amiri.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Color.White else Amiri.Line, shape)
            .clickable { Haptics.select(view); onClick() }.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            val ratio = if (a.w > 0) a.w.toFloat() / a.h else 1.4f
            val (bw, bh) = if (ratio >= 1f) 30f to 30f / ratio else 30f * ratio to 30f
            Box(
                Modifier.size(bw.dp, bh.dp).clip(RoundedCornerShape(4.dp))
                    .border(1.6.dp, if (selected) Color.White else Amiri.TextTertiary, RoundedCornerShape(4.dp))
                    .then(if (a.w == 0) Modifier.background(Color.White.copy(alpha = 0.06f)) else Modifier),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(a.label, color = if (selected) Amiri.TextPrimary else Amiri.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(a.use, color = Amiri.TextTertiary, fontSize = 9.5.sp, maxLines = 1)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(3)) }, singleLine = true,
        label = { Text(label) }, modifier = Modifier.width(110.dp),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amiri.accentInk(accent), unfocusedBorderColor = Amiri.Line,
            focusedContainerColor = Amiri.Surface, unfocusedContainerColor = Amiri.Surface,
            cursorColor = Amiri.accentInk(accent), focusedTextColor = Amiri.TextPrimary, unfocusedTextColor = Amiri.TextPrimary,
            focusedLabelColor = Amiri.accentInk(accent), unfocusedLabelColor = Amiri.TextTertiary,
        ),
    )
}
