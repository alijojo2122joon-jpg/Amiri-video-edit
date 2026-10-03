package com.amiri.cut.ui.newproject

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.core.model.ProjectSettings
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.BarRow
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.common.Gap
import com.amiri.cut.ui.common.GlassButton
import com.amiri.cut.ui.common.GlassCard
import com.amiri.cut.ui.common.IconAction
import com.amiri.cut.ui.common.SectionLabel
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class Res(val label: String, val shortSide: Int)
private data class Aspect(val label: String, val w: Int, val h: Int)

private val RESOLUTIONS = listOf(Res("720p", 720), Res("1080p", 1080), Res("1440p", 1440), Res("4K", 2160))
private val ASPECTS = listOf(
    Aspect("9:16", 9, 16), Aspect("16:9", 16, 9), Aspect("1:1", 1, 1), Aspect("4:5", 4, 5),
    Aspect("4:3", 4, 3), Aspect("21:9", 21, 9), Aspect("Custom", 0, 0),
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
            BarRow {
                IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                Text("New project", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Project name") }, modifier = Modifier.fillMaxWidth(),
                )

                // Live canvas preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(120.dp).height(120.dp), contentAlignment = Alignment.Center) {
                        val ratio = aw.toFloat() / ah
                        Box(
                            Modifier
                                .then(if (ratio >= 1f) Modifier.fillMaxWidth() else Modifier.height(120.dp))
                                .aspectRatio(ratio)
                                .background(if (bg == CanvasBackground.BLACK) Color.Black else Color(0xFF2A2A2E), RoundedCornerShape(6.dp))
                                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
                        )
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("$fw × $fh", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text("$fps fps · ${if (aspect.label == "Custom") "$aw:$ah" else aspect.label}", color = Amiri.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        if (fw > 3840 || fh > 3840) {
                            Gap(h = 6)
                            Text(
                                "Very large frame — some encoders can't export this size. Export will check your device and suggest a supported size.",
                                color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Column {
                    SectionLabel("Resolution")
                    ChoiceChips(RESOLUTIONS, res, { it.label }) { res = it }
                }
                Column {
                    SectionLabel("Aspect ratio")
                    ChoiceChips(ASPECTS, aspect, { it.label }) { aspect = it }
                    if (aspect.label == "Custom") {
                        Gap(h = 10)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                customW, { customW = it.filter(Char::isDigit).take(3) }, singleLine = true,
                                label = { Text("W") }, modifier = Modifier.width(90.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Text(":", color = Amiri.TextSecondary)
                            OutlinedTextField(
                                customH, { customH = it.filter(Char::isDigit).take(3) }, singleLine = true,
                                label = { Text("H") }, modifier = Modifier.width(90.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                }
                Column {
                    SectionLabel("Frame rate")
                    ChoiceChips(FPS, fps, { "$it" }) { fps = it }
                }
                Column {
                    SectionLabel("Background")
                    ChoiceChips(CanvasBackground.entries.toList(), bg, { if (it == CanvasBackground.BLACK) "Black" else "Transparent" }) { bg = it }
                    if (bg == CanvasBackground.TRANSPARENT) {
                        Gap(h = 8)
                        GlassCard {
                            Text(
                                "Transparent areas are shown as a checkerboard while editing. MP4 (H.264/HEVC) has no alpha channel, so they export as black.",
                                color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Gap(h = 8)
            }
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("Create", Modifier.weight(1f), icon = Icons.Outlined.Check, enabled = !busy) { create(emptyList()) }
                GlassButton("Import media", Modifier.weight(1.3f), icon = Icons.Outlined.AddPhotoAlternate, primary = true, enabled = !busy) {
                    picker.launch(arrayOf("video/*", "image/*", "audio/*"))
                }
            }
        }
    }
}
