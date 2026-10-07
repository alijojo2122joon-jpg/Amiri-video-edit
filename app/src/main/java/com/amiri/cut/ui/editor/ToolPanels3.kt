package com.amiri.cut.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.model.FillMode
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.onAccent
import kotlin.math.abs
import kotlin.math.roundToInt

private data class RatioOption(val label: String, val w: Int, val h: Int, val hint: String)

private val RATIOS = listOf(
    RatioOption("Original", 0, 0, "Your clip"),
    RatioOption("9:16", 9, 16, "TikTok · Reels"),
    RatioOption("16:9", 16, 9, "YouTube"),
    RatioOption("1:1", 1, 1, "Square"),
    RatioOption("4:5", 4, 5, "Instagram"),
    RatioOption("3:4", 3, 4, "Portrait"),
    RatioOption("4:3", 4, 3, "Classic"),
    RatioOption("2:1", 2, 1, "Wide"),
    RatioOption("21:9", 21, 9, "Cinema"),
    RatioOption("2.35:1", 47, 20, "Scope"),
)

@Composable
internal fun RatioPanel(c: EditorController) {
    val p = c.project ?: return
    val accent = LocalAccent.current
    val view = LocalView.current
    val cur = p.settings.width.toFloat() / p.settings.height
    val curLabel = p.settings.aspectLabel
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RATIOS.forEach { r ->
                val on = if (r.w == 0) curLabel == "Original" else curLabel != "Original" && abs(cur - r.w.toFloat() / r.h) < 0.01f
                Column(
                    Modifier.width(74.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) accent.copy(alpha = 0.16f) else Amiri.SurfaceHigh)
                        .border(1.5.dp, if (on) accent else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable { Haptics.select(view); if (r.w == 0) c.setRatioOriginal() else c.setRatio(r.w, r.h, r.label) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.size(34.dp)) {
                        val ratio = if (r.w == 0) cur else r.w.toFloat() / r.h
                        val bw = if (ratio >= 1f) size.width else size.height * ratio
                        val bh = if (ratio >= 1f) size.width / ratio else size.height
                        val o = Offset((size.width - bw) / 2, (size.height - bh) / 2)
                        drawRoundRect(if (on) accent else Amiri.TextSecondary, o, Size(bw, bh), CornerRadius(5f, 5f), style = Stroke(2.4f * density))
                    }
                    Text(r.label, color = if (on) Amiri.TextPrimary else Amiri.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text(r.hint, color = Amiri.TextTertiary, fontSize = 9.5.sp, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
        Text(
            "${p.settings.width} × ${p.settings.height}  ·  clips are refitted automatically — use Background to fill empty space",
            color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private val SWATCHES = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF2B2B30, 0xFFF4A261, 0xFFE76F51, 0xFFFF5D8F, 0xFFB06CFF,
    0xFF4D7CFF, 0xFF2EC4B6, 0xFF3DDC97, 0xFFFFD166, 0xFFEADBC8, 0xFF1D3557, 0xFF6D4C41,
)

@Composable
internal fun BackgroundPanel(c: EditorController) {
    val p = c.project ?: return
    val fill = p.settings.fill
    val accent = LocalAccent.current
    val view = LocalView.current
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTile("None", Icons.Outlined.Block, fill.mode == FillMode.BLACK, Modifier.weight(1f)) { c.setFill("Background none") { it.copy(mode = FillMode.BLACK) } }
            ModeTile("Blur", Icons.Outlined.BlurOn, fill.mode == FillMode.BLUR, Modifier.weight(1f)) { c.setFill("Background blur") { it.copy(mode = FillMode.BLUR) } }
            ModeTile("Color", Icons.Outlined.Palette, fill.mode == FillMode.COLOR, Modifier.weight(1f)) {
                c.setFill("Background color") { it.copy(mode = FillMode.COLOR, color = if (it.color == 0xFF000000) 0xFFF4A261 else it.color) }
            }
        }
        when (fill.mode) {
            FillMode.BLUR -> {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Blur", color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                    Slider(
                        value = fill.blur, onValueChange = { v -> c.setFill("Blur", live = true) { it.copy(blur = v) } },
                        onValueChangeFinished = { c.endEdit("Background blur") },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Amiri.SurfaceHighest),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(fill.blur * 100).roundToInt()}", color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(36.dp).padding(start = 8.dp))
                }
                Text("The picture behind is your clip, enlarged and blurred — no black bars.", color = Amiri.TextTertiary, fontSize = 11.sp)
            }
            FillMode.COLOR -> {
                Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { argb ->
                        val on = fill.color == argb
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                                .border(if (on) 3.dp else 1.dp, if (on) accent else Amiri.LineStrong, CircleShape)
                                .clickable { Haptics.select(view); c.setFill("Background color") { it.copy(mode = FillMode.COLOR, color = argb) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Icon(Icons.Outlined.Check, null, tint = if (Color(argb).luminanceSimple() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            FillMode.BLACK -> Text(
                "Empty areas of the frame stay black. Choose Blur for a soft, full-frame look like CapCut.",
                color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun Color.luminanceSimple() = 0.299f * red + 0.587f * green + 0.114f * blue

@Composable
private fun ModeTile(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val view = LocalView.current
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) accent else Amiri.SurfaceHigh)
            .clickable { Haptics.select(view); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (on) onAccent(accent) else Amiri.TextPrimary, modifier = Modifier.size(24.dp))
        Text(label, color = if (on) onAccent(accent) else Amiri.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    }
}
