package com.amiri.cut.ui.editor

import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.effects.AudioSpec
import com.amiri.cut.core.effects.EffectCatalog
import com.amiri.cut.core.effects.EffectCategory
import com.amiri.cut.core.effects.MaskSpec
import com.amiri.cut.core.effects.ParamSpec
import com.amiri.cut.core.effects.TextSpecDefaults
import com.amiri.cut.core.effects.TransformSpec
import com.amiri.cut.core.model.BlendMode
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.ClipKind
import com.amiri.cut.core.model.Interp
import com.amiri.cut.core.model.MaskMode
import com.amiri.cut.core.model.MaskShape
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.SpeedRamp
import com.amiri.cut.core.model.StabMode
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.render.CurveBuilder
import com.amiri.cut.ui.common.ChoiceChips
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.glass
import com.amiri.cut.ui.theme.glassAccent
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ═══════════════════════════════ shared widgets ═══════════════════════════════

@Composable
internal fun BusyBar(c: EditorController, b: EditorController.Busy) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${b.label} ${(b.progress * 100).toInt()}%", color = Amiri.TextPrimary, fontSize = 12.sp)
            LinearProgressIndicator(
                progress = { b.progress }, color = accent, trackColor = Amiri.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        Text("Cancel", color = Amiri.Danger, fontSize = 12.sp, modifier = Modifier.clickable { c.cancelBusy() }.padding(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Amiri.TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun NeedClip(text: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(text, color = Amiri.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun KeyDiamond(has: Boolean, animated: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Canvas(Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClick).padding(6.dp)) {
        rotate(45f) {
            val s = size.minDimension * 0.62f
            val o = Offset((size.width - s) / 2, (size.height - s) / 2)
            if (has) drawRect(accent, o, androidx.compose.ui.geometry.Size(s, s))
            else drawRect(if (animated) accent else Amiri.TextTertiary, o, androidx.compose.ui.geometry.Size(s, s), style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** A keyframable parameter: label (long-press = reset), slider, value, keyframe diamond. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ParamRow(c: EditorController, t: EditorController.PTarget, spec: ParamSpec, label: String = spec.label) {
    val accent = LocalAccent.current
    val pos by c.engine.position.collectAsState()
    @Suppress("UNUSED_VARIABLE") val proj = c.project
    val v = c.paramValue(t, spec.id, spec.default)
    val has = c.keyHere(t, spec.id)
    val anim = c.isAnimated(t, spec.id)
    @Suppress("UNUSED_EXPRESSION") pos
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, color = if (anim) accent else Amiri.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp).combinedClickable(onClick = { c.focusParam = t to spec.id }, onLongClick = {
                c.setParam(t, spec.id, spec.default, spec.default, live = false, label = "Reset ${spec.label}")
            }),
        )
        Slider(
            value = v.coerceIn(spec.min, spec.max),
            onValueChange = { c.focusParam = t to spec.id; c.setParam(t, spec.id, it, spec.default) },
            onValueChangeFinished = { c.endEdit(spec.label) },
            valueRange = spec.min..spec.max,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Amiri.SurfaceHigh),
            modifier = Modifier.weight(1f),
        )
        Text(spec.format(v), color = Amiri.TextSecondary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(46.dp).padding(start = 4.dp))
        KeyDiamond(has, anim) { c.focusParam = t to spec.id; c.toggleKey(t, spec.id, spec.default) }
    }
}

/** Keyframe navigation and interpolation of the focused parameter's key at the playhead. */
@Composable
internal fun KeyframeBar(c: EditorController, clip: Clip) {
    val pos by c.engine.position.collectAsState()
    @Suppress("UNUSED_EXPRESSION") pos
    val focus = c.focusParam?.takeIf { it.first.clipId == clip.id }
    val key = focus?.let { c.keyAtPlayhead(it.first, it.second) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keyframes", color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            PanelAction(Icons.Outlined.ChevronLeft, "Prev key") { c.jumpKey(clip.id, false) }
            PanelAction(Icons.Outlined.ChevronRight, "Next key") { c.jumpKey(clip.id, true) }
        }
        if (focus != null && key != null) {
            Hint("Interpolation after this key (${focus.second})")
            ChoiceChips(Interp.entries.toList(), key.interp, { it.label }) { c.setInterp(focus.first, focus.second, it) }
            if (key.interp == Interp.BEZIER) {
                BezierEditor(floatArrayOf(key.c1x, key.c1y, key.c2x, key.c2y),
                    onChange = { b -> c.setInterp(focus.first, focus.second, Interp.BEZIER, b, live = true) },
                    onEnd = { c.endEdit("Custom curve") })
            }
        } else {
            Hint("Tap ◇ next to a value to add a keyframe at the playhead. Values with keys are highlighted.")
        }
    }
}

/** Custom easing curve editor (cubic-bezier handles). */
@Composable
private fun BezierEditor(b: FloatArray, onChange: (FloatArray) -> Unit, onEnd: () -> Unit) {
    val accent = LocalAccent.current
    var cur by remember(b[0], b[1], b[2], b[3]) { mutableStateOf(b.copyOf()) }
    Canvas(
        Modifier.padding(vertical = 6.dp).size(220.dp, 120.dp).clip(RoundedCornerShape(10.dp)).background(Amiri.SurfaceHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    fun toPt(o: Offset) = (o.x / w).coerceIn(0f, 1f) to (1f - o.y / h).coerceIn(-0.5f, 1.5f)
                    val (x0, y0) = toPt(d.position)
                    val which = if (hypot(x0 - cur[0], y0 - cur[1]) < hypot(x0 - cur[2], y0 - cur[3])) 0 else 2
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        val (x, y) = toPt(ch.position)
                        val n = cur.copyOf(); n[which] = x; n[which + 1] = y
                        cur = n
                        onChange(n)
                        ch.consume()
                    } while (ev.changes.any { it.pressed })
                    onEnd()
                }
            },
    ) {
        val w = size.width; val h = size.height
        fun pt(x: Float, y: Float) = Offset(x * w, (1f - y) * h)
        val path = Path()
        for (i in 0..40) {
            val s = i / 40f
            val x = 3 * (1 - s) * (1 - s) * s * cur[0] + 3 * (1 - s) * s * s * cur[2] + s * s * s
            val y = 3 * (1 - s) * (1 - s) * s * cur[1] + 3 * (1 - s) * s * s * cur[3] + s * s * s
            val o = pt(x, y)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawLine(Color.White.copy(alpha = 0.3f), pt(0f, 0f), pt(cur[0], cur[1]), 1.5f)
        drawLine(Color.White.copy(alpha = 0.3f), pt(1f, 1f), pt(cur[2], cur[3]), 1.5f)
        drawPath(path, accent, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, 6.dp.toPx(), pt(cur[0], cur[1]))
        drawCircle(Color.White, 6.dp.toPx(), pt(cur[2], cur[3]))
    }
}

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759,
    0xFF00C7BE, 0xFF32ADE6, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFFA2845E,
)

/** RGB color made of three keyframable params: swatches + optional fine sliders. */
@Composable
internal fun ColorRow(c: EditorController, t: EditorController.PTarget, ids: Triple<String, String, String>, label: String, def: FloatArray, eyedropper: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val r = c.paramValue(t, ids.first, def[0]); val g = c.paramValue(t, ids.second, def[1]); val b = c.paramValue(t, ids.third, def[2])
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(label, color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(84.dp))
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(r, g, b)).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape).clickable { open = !open })
            Row(Modifier.weight(1f).padding(start = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SWATCHES.forEach { argb ->
                    val col = Color(argb)
                    Box(Modifier.size(20.dp).clip(CircleShape).background(col).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape).clickable {
                        c.setParam(t, ids.first, col.red, def[0], live = true)
                        c.setParam(t, ids.second, col.green, def[1], live = true)
                        c.setParam(t, ids.third, col.blue, def[2], live = true)
                        c.endEdit(label)
                    })
                }
            }
            if (eyedropper) {
                Icon(Icons.Outlined.Colorize, "Pick from video", tint = Amiri.TextPrimary, modifier = Modifier.padding(start = 6.dp).size(22.dp).clickable {
                    c.pickColorFor = { rr, gg, bb ->
                        c.setParam(t, ids.first, rr, def[0], live = true)
                        c.setParam(t, ids.second, gg, def[1], live = true)
                        c.setParam(t, ids.third, bb, def[2], live = true)
                        c.endEdit("Pick color")
                    }
                })
            }
        }
        if (open) {
            ParamRow(c, t, ParamSpec(ids.first, "Red", 0f, 1f, def[0]))
            ParamRow(c, t, ParamSpec(ids.second, "Green", 0f, 1f, def[1]))
            ParamRow(c, t, ParamSpec(ids.third, "Blue", 0f, 1f, def[2]))
        }
    }
}

private fun visualSelected(c: EditorController): Clip? {
    val clip = c.selectedClip() ?: return null
    val tr = c.project?.trackOfClip(clip.id) ?: return null
    return if (tr.acceptsVisual) clip else null
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "file")

// ═══════════════════════════════ Transform ═══════════════════════════════

@Composable
internal fun TransformPanel(c: EditorController) {
    val clip = visualSelected(c) ?: return NeedClip("Select a video, photo, text or adjustment clip on the timeline. Then drag on the preview to move it, pinch to scale and twist to rotate.")
    val t = EditorController.PTarget.Transform(clip.id)
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PanelAction(Icons.Outlined.CenterFocusStrong, "Fit") { c.fitFill(false) }
            PanelAction(Icons.Outlined.ZoomOutMap, "Fill") { c.fitFill(true) }
            PanelAction(Icons.Outlined.Flip, if (clip.flipH) "Unflip H" else "Flip H") { c.updateSelected("Flip") { it.copy(flipH = !it.flipH) } }
            PanelAction(Icons.Outlined.Flip, if (clip.flipV) "Unflip V" else "Flip V") { c.updateSelected("Flip") { it.copy(flipV = !it.flipV) } }
            PanelAction(Icons.Outlined.RestartAlt, "Reset") { c.resetTransform() }
        }
        KeyframeBar(c, clip)
        SectionTitle("Transform")
        TransformSpec.PARAMS.take(7).forEach { ParamRow(c, t, it) }
        SectionTitle("Crop")
        TransformSpec.PARAMS.drop(7).forEach { ParamRow(c, t, it) }
        SectionTitle("Blend mode")
        ChoiceChips(BlendMode.entries.toList(), clip.blend, { it.label }) { m -> c.updateSelected("Blend mode") { it.copy(blend = m) } }
        SectionTitle("Motion blur")
        TransformSpec.MOTION_BLUR.forEach { ParamRow(c, t, it) }
        Hint("Motion blur is computed from the keyframed movement of this layer.")
    }
}

// ═══════════════════════════════ Speed ═══════════════════════════════

private val RAMP_PRESETS = linkedMapOf(
    "Montage" to listOf(1.8f, 0.35f, 1.8f, 0.35f, 1.8f),
    "Smooth" to listOf(0.6f, 1f, 1.4f, 1f, 0.6f),
    "Fast" to listOf(0.6f, 1.2f, 2.4f, 3f, 3f),
    "Slow" to listOf(2.5f, 1.5f, 0.6f, 0.4f, 0.4f),
)

@Composable
internal fun SpeedPanel(c: EditorController) {
    val clip = c.selectedClip()?.takeIf { it.kind == ClipKind.MEDIA } ?: return NeedClip("Select a video or audio clip to change its speed.")
    val p = c.project ?: return
    val fps = p.settings.fps
    var custom by remember(clip.id) { mutableFloatStateOf(clip.speed) }
    Column {
        Text("Duration ${FrameTime.timecode(clip.durationUs, fps)} · ${"%.2f".format(clip.speed)}×", color = Amiri.TextPrimary, fontSize = 12.sp)
        SectionTitle("Speed")
        ChoiceChips(listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f), clip.speed, { "${it}×".replace(".0×", "×") }) { c.setSpeed(it) }
        LabeledSlider("Custom", custom, 0.1f..8f, "${"%.2f".format(custom)}×") { custom = it }
        Row {
            ToggleChip("Apply ${"%.2f".format(custom)}×", false) { c.setSpeed(custom) }
        }
        SectionTitle("Speed ramp")
        val current = clip.ramp
        ChoiceChips(listOf("None") + RAMP_PRESETS.keys + "Custom", current?.preset ?: "None", { it }) { name ->
            when (name) {
                "None" -> c.setRamp(null)
                "Custom" -> c.setRamp(SpeedRamp("Custom", current?.points ?: listOf(1f, 1f, 1f, 1f, 1f)))
                else -> c.setRamp(SpeedRamp(name, RAMP_PRESETS[name]!!))
            }
        }
        if (current != null) {
            RampEditor(current.points, onChange = { pts -> c.setRamp(SpeedRamp("Custom", pts), live = true) }, onEnd = { c.endEdit("Speed curve") })
            Hint("Drag the points: higher = faster. The clip keeps its length; audio pitch follows speed.")
        }
        SectionTitle("More")
        Row {
            PanelAction(Icons.Outlined.RestartAlt, if (clip.reversedFrom != null) "Un-reverse" else "Reverse") { c.reverseSelected() }
            PanelAction(Icons.Outlined.PlayArrow, "Freeze 2s") { c.freezeFrame() }
        }
    }
}

@Composable
private fun RampEditor(points: List<Float>, onChange: (List<Float>) -> Unit, onEnd: () -> Unit) {
    val accent = LocalAccent.current
    var pts by remember(points) { mutableStateOf(points) }
    fun toY(v: Float) = (kotlin.math.ln(v / 0.1f) / kotlin.math.ln(40f)).coerceIn(0f, 1f) // 0.1..4 log
    fun fromY(y: Float) = (0.1f * Math.pow(40.0, y.toDouble()).toFloat()).coerceIn(0.1f, 4f)
    Canvas(
        Modifier.padding(vertical = 6.dp).fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(Amiri.SurfaceHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val n = pts.size
                    val idx = ((d.position.x / w) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        val y = 1f - (ch.position.y / h).coerceIn(0f, 1f)
                        val np = pts.toMutableList(); np[idx] = fromY(y)
                        pts = np
                        onChange(np)
                        ch.consume()
                    } while (ev.changes.any { it.pressed })
                    onEnd()
                }
            },
    ) {
        val w = size.width; val h = size.height
        val one = (1f - toY(1f)) * h
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, one), Offset(w, one), 1f)
        val path = Path()
        pts.forEachIndexed { i, v ->
            val o = Offset(i / (pts.size - 1f) * w, (1f - toY(v)) * h)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, accent, style = Stroke(2.dp.toPx()))
        pts.forEachIndexed { i, v -> drawCircle(Color.White, 6.dp.toPx(), Offset(i / (pts.size - 1f) * w, (1f - toY(v)) * h)) }
    }
}

// ═══════════════════════════════ Mask ═══════════════════════════════

@Composable
internal fun MaskPanel(c: EditorController) {
    val clip = visualSelected(c)?.takeIf { it.kind != ClipKind.ADJUSTMENT } ?: return NeedClip("Select a video, photo or text clip to add masks.")
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PanelAction(Icons.Outlined.CropFree, "Rectangle") { c.addMask(MaskShape.RECT) }
            PanelAction(Icons.Outlined.GpsFixed, "Ellipse") { c.addMask(MaskShape.ELLIPSE) }
            PanelAction(Icons.Outlined.Add, if (c.penActive) "Close (${c.penPoints.size})" else "Pen") {
                if (c.penActive) c.closePenMask() else { c.penActive = true; c.penPoints = emptyList() }
            }
            if (c.penActive) PanelAction(Icons.Outlined.DeleteOutline, "Cancel pen") { c.penActive = false; c.penPoints = emptyList() }
        }
        if (c.penActive) Hint("Tap points around the shape on the preview, then Close.")
        if (clip.masks.isEmpty()) { Hint("Masks hide everything outside the shape. Up to 4 masks, combined with Add / Subtract / Intersect."); return@Column }
        val sel = clip.masks.firstOrNull { it.id == c.selectedMaskId } ?: clip.masks.last()
        ChoiceChips(clip.masks.map { it.id }, sel.id, { id -> val i = clip.masks.indexOfFirst { it.id == id }; "Mask ${i + 1} · " + clip.masks[i].shape.name.lowercase() }) { c.selectedMaskId = it }
        SectionTitle("Mode")
        ChoiceChips(MaskMode.entries.toList(), sel.mode, { it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } }) { m -> c.updateMask(sel.id, "Mask mode") { it.copy(mode = m) } }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Invert", sel.invert) { c.updateMask(sel.id, "Invert mask") { it.copy(invert = !it.invert) } }
            ToggleChip("Follow track", sel.followTrack) {
                if (clip.tracking == null) c.toast = Toast("Track this clip first (Track tool)")
                else c.updateMask(sel.id, "Mask follows track") { it.copy(followTrack = !it.followTrack) }
            }
            ToggleChip("Delete", false) { c.deleteMask(sel.id) }
        }
        KeyframeBar(c, clip)
        val t = EditorController.PTarget.Mask(clip.id, sel.id)
        MaskSpec.PARAMS.forEach { ParamRow(c, t, it) }
        Hint("Drag the mask on the preview to move it; pinch to resize, twist to rotate.")
    }
}

// ═══════════════════════════════ Track / Stabilize / Attach ═══════════════════════════════

@Composable
internal fun TrackPanel(c: EditorController) {
    val clip = visualSelected(c) ?: return NeedClip("Select the video clip you want to track or stabilize.")
    val p = c.project ?: return
    val asset = p.asset(clip.assetId)
    val isVideo = asset?.type == MediaType.VIDEO
    Column {
        if (isVideo) {
            SectionTitle("Motion tracking")
            ChoiceChips(listOf(1, 2), c.trackRegions.size, { if (it == 1) "1 point · position" else "2 points · + scale & rotation" }) { n ->
                c.trackRegions = if (n == 1) c.trackRegions.take(1)
                else (c.trackRegions.take(1) + android.graphics.RectF(0.62f, 0.42f, 0.74f, 0.58f))
            }
            Hint("Place the box(es) on a detailed area on the preview, then track from the playhead forward.")
            Row {
                PanelAction(Icons.Outlined.PlayArrow, "Track ▶") { c.trackForward() }
                PanelAction(Icons.Outlined.DeleteOutline, "Clear", enabled = clip.tracking != null) { c.clearTracking() }
            }
            clip.tracking?.let { Hint("Track data: ${it.samples.size} frames${if (it.scaleRot) " · scale & rotation" else ""}. Attach text or overlays to it below, or use it in Mask → Follow track.") }

            SectionTitle("Stabilize")
            var mode by remember(clip.id) { mutableStateOf(clip.stab?.mode ?: StabMode.BASIC) }
            var smooth by remember(clip.id) { mutableFloatStateOf(clip.stab?.smoothness ?: 0.5f) }
            var zoom by remember(clip.id) { mutableFloatStateOf(clip.stab?.zoom ?: 1.08f) }
            ChoiceChips(StabMode.entries.toList(), mode, { if (it == StabMode.BASIC) "Basic · position" else "Advanced · + rotation & scale" }) { mode = it }
            LabeledSlider("Smooth", smooth, 0f..1f, "${(smooth * 100).toInt()}%") { smooth = it }
            LabeledSlider("Zoom", zoom, 1f..1.3f, "${"%.2f".format(zoom)}×") { zoom = it }
            Row {
                PanelAction(Icons.Outlined.PlayArrow, "Analyze") { c.stabilize(mode, smooth, zoom) }
                clip.stab?.let { st ->
                    PanelAction(if (st.enabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (st.enabled) "Disable" else "Enable") {
                        c.updateSelected("Stabilization") { it.copy(stab = it.stab?.copy(enabled = !st.enabled)) }
                    }
                    PanelAction(Icons.Outlined.ZoomOutMap, "Set zoom") { c.updateSelected("Stabilize zoom") { it.copy(stab = it.stab?.copy(zoom = zoom)) } }
                    PanelAction(Icons.Outlined.DeleteOutline, "Remove") { c.updateSelected("Remove stabilization") { it.copy(stab = null) } }
                }
            }
        }

        SectionTitle("Attach to a track")
        val trackers = p.tracks.flatMap { it.clips }.filter { it.id != clip.id && it.tracking != null }
        if (trackers.isEmpty()) {
            Hint("No tracked clips yet. Track a video clip first, then select a text or overlay clip and attach it here.")
        } else {
            val f = clip.follow
            ChoiceChips(listOf<String?>(null) + trackers.map { it.id }, f?.clipId, { id -> if (id == null) "None" else "Follow " + trackers.first { it.id == id }.name.take(18) }) { id ->
                c.follow(id)
            }
            if (f != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Position", f.position) { c.follow(f.clipId, !f.position, f.scale, f.rotation) }
                    ToggleChip("Scale", f.scale) { c.follow(f.clipId, f.position, !f.scale, f.rotation) }
                    ToggleChip("Rotation", f.rotation) { c.follow(f.clipId, f.position, f.scale, !f.rotation) }
                }
                Hint("Attached at the current playhead — the layer keeps its offset from the tracked point.")
            }
        }
    }
}

// ═══════════════════════════════ Text ═══════════════════════════════

@Composable
internal fun TextPanel(c: EditorController) {
    val context = LocalContext.current
    val clip = c.selectedClip()?.takeIf { it.kind == ClipKind.TEXT }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) c.importFont(uri, displayName(context, uri))
    }
    Column {
        Row {
            PanelAction(Icons.Outlined.TextFields, "Add text") { c.addText() }
            if (clip != null) PanelAction(Icons.Outlined.Upload, "Import font") {
                fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*"))
            }
        }
        if (clip == null) { Hint("Add a text layer, or select one on the timeline to edit it. Persian, Arabic and English are supported (RTL)."); return@Column }
        val spec = clip.text ?: return@Column
        var text by remember(clip.id) { mutableStateOf(spec.text) }
        var editing by remember { mutableIntStateOf(0) }
        LaunchedEffect(editing) { if (editing > 0) { delay(900); c.endEdit("Edit text") } }
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v; editing++; c.updateText("Edit text", live = true) { it.copy(text = v) } },
            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
        )
        SectionTitle("Font")
        val fonts = remember(c.historyVersion) { c.app.fonts.all() }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fonts.forEach { f ->
                val tf: Typeface = remember(f.id) { c.app.fonts.typeface(f.id, false, false) }
                val sel = f.id == spec.font
                val accent = LocalAccent.current
                Box(
                    Modifier.then(if (sel) Modifier.glassAccent(accent, RoundedCornerShape(10.dp)) else Modifier.glass(RoundedCornerShape(10.dp), 0.7f))
                        .clickable { c.updateText("Font") { it.copy(font = f.id) } }.padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text(f.label + " · امیری", color = Amiri.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily(tf)) }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChips(listOf(0, 1, 2), spec.align, { listOf("Start", "Center", "End")[it] }, Modifier.weight(1f)) { a -> c.updateText("Align") { it.copy(align = a) } }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Bold", spec.bold) { c.updateText("Bold") { it.copy(bold = !it.bold) } }
            ToggleChip("Italic", spec.italic) { c.updateText("Italic") { it.copy(italic = !it.italic) } }
        }
        KeyframeBar(c, clip)
        val t = EditorController.PTarget.Text(clip.id)
        SectionTitle("Style")
        TextSpecDefaults.STYLE.forEach { ParamRow(c, t, it) }
        SectionTitle("Colors")
        TextSpecDefaults.COLORS.forEach { (pre, label, def) -> ColorRow(c, t, Triple("${pre}r", "${pre}g", "${pre}b"), label, def) }
        Hint("Position, scale, rotation and opacity of the text: use the Transform tool (or drag on the preview).")
    }
}

// ═══════════════════════════════ Color ═══════════════════════════════

@Composable
internal fun ColorPanel(c: EditorController) {
    val context = LocalContext.current
    val clip = visualSelected(c) ?: return NeedClip("Select a clip (or an adjustment layer to grade several clips at once).")
    var tab by remember { mutableStateOf("Basic") }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) c.importLut(uri, displayName(context, uri), clip.effects.firstOrNull { it.type == "lut" }?.id)
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(if (c.colorBefore) "Showing BEFORE" else "Before / After", c.colorBefore) { c.colorBefore = !c.colorBefore }
            ToggleChip("Scopes", c.showScopes) { c.showScopes = !c.showScopes }
        }
        ChoiceChips(listOf("Basic", "HSL", "Curves", "Wheels", "Detail", "LUT"), tab, { it }, Modifier.padding(top = 8.dp)) { tab = it }
        if (tab == "LUT") {
            val luts = remember(c.historyVersion) { c.app.luts.list() }
            val lutFx = clip.effects.firstOrNull { it.type == "lut" }
            Row {
                PanelAction(Icons.Outlined.Upload, "Import .cube") { lutPicker.launch(arrayOf("*/*")) }
                if (lutFx != null) PanelAction(Icons.Outlined.DeleteOutline, "Remove LUT") { c.removeEffect(lutFx.id) }
            }
            if (luts.isNotEmpty()) {
                ChoiceChips(luts, lutFx?.opts?.get("file") ?: "", { it.removeSuffix(".cube") }) { name ->
                    if (lutFx == null) c.addEffect("lut", mapOf("file" to name)) else c.updateEffect(lutFx.id, "LUT") { it.copy(opts = it.opts + ("file" to name)) }
                }
            } else Hint("Import a .cube 3D LUT from your storage. It is copied into the app and works fully offline.")
            if (lutFx != null) ParamRow(c, EditorController.PTarget.Fx(clip.id, lutFx.id), EffectCatalog.LUT.params[0])
            return@Column
        }
        val fx = clip.effects.firstOrNull { it.type == "color" }
        if (fx == null) {
            Row { PanelAction(Icons.Outlined.Add, "Start grading") { c.ensureColorEffect() } }
            return@Column
        }
        val t = EditorController.PTarget.Fx(clip.id, fx.id)
        val spec = EffectCatalog.COLOR
        KeyframeBar(c, clip)
        when (tab) {
            "Basic" -> spec.params.take(10).forEach { ParamRow(c, t, it) }
            "HSL" -> {
                var range by remember { mutableStateOf("red") }
                val hues = mapOf("red" to Color(0xFFFF4D4D), "yellow" to Color(0xFFFFD54D), "green" to Color(0xFF5CD65C), "cyan" to Color(0xFF4DE1E1), "blue" to Color(0xFF4D7CFF), "magenta" to Color(0xFFE14DE1))
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EffectCatalog.HSL_RANGES.forEach { r ->
                        Box(Modifier.size(if (r == range) 28.dp else 22.dp).clip(CircleShape).background(hues[r]!!)
                            .border(if (r == range) 2.dp else 0.dp, Color.White, CircleShape).clickable { range = r })
                    }
                }
                listOf("h" to "Hue", "s" to "Saturation", "l" to "Luminance").forEach { (k, lbl) ->
                    ParamRow(c, t, spec.param("hsl_${range}_$k")!!, "$lbl")
                }
            }
            "Curves" -> {
                var ch by remember { mutableStateOf("curve_m") }
                ChoiceChips(listOf("curve_m", "curve_r", "curve_g", "curve_b"), ch, { mapOf("curve_m" to "RGB", "curve_r" to "Red", "curve_g" to "Green", "curve_b" to "Blue")[it]!! }) { ch = it }
                val pts = CurveBuilder.parse(fx.opts[ch])
                CurveEditor(pts, when (ch) { "curve_r" -> Color(0xFFFF6B6B); "curve_g" -> Color(0xFF6BFF8F); "curve_b" -> Color(0xFF6BA8FF); else -> Color.White },
                    onChange = { np -> c.updateEffect(fx.id, "Curves", live = true) { it.copy(opts = it.opts + (ch to CurveBuilder.format(np))) } },
                    onEnd = { c.endEdit("Curves") })
                Row {
                    ToggleChip("Reset curve", false) { c.updateEffect(fx.id, "Reset curve") { it.copy(opts = it.opts - ch) } }
                }
                Hint("Drag points · tap to add · long-press a point to remove it.")
            }
            "Wheels" -> {
                listOf("lift" to "Lift (shadows)", "gamma" to "Gamma (midtones)", "gain" to "Gain (highlights)").forEach { (w, lbl) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        ColorWheel(
                            x = c.paramValue(t, "${w}_x", 0f), y = c.paramValue(t, "${w}_y", 0f),
                            onChange = { x, y -> c.setParam(t, "${w}_x", x, 0f); c.setParam(t, "${w}_y", y, 0f) },
                            onEnd = { c.endEdit(lbl) },
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(lbl, color = Amiri.TextPrimary, fontSize = 12.sp)
                            ParamRow(c, t, spec.param("${w}_l")!!, "Level")
                            Text("Reset", color = LocalAccent.current, fontSize = 11.sp, modifier = Modifier.clickable {
                                c.setParam(t, "${w}_x", 0f, 0f, live = true); c.setParam(t, "${w}_y", 0f, 0f, live = true); c.setParam(t, "${w}_l", 0f, 0f, live = true); c.endEdit("Reset $lbl")
                            })
                        }
                    }
                }
            }
            "Detail" -> listOf("vignette", "vig_feather", "sharpen", "blur").forEach { ParamRow(c, t, spec.param(it)!!) }
        }
    }
}

@Composable
private fun ColorWheel(x: Float, y: Float, onChange: (Float, Float) -> Unit, onEnd: () -> Unit) {
    Canvas(
        Modifier.size(84.dp).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                do {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.first()
                    val r = size.width / 2f
                    var dx = (ch.position.x - r) / r
                    var dy = -(ch.position.y - r) / r
                    val len = hypot(dx, dy)
                    if (len > 1f) { dx /= len; dy /= len }
                    onChange(dx, dy)
                    ch.consume()
                } while (ev.changes.any { it.pressed })
                onEnd()
            }
        },
    ) {
        val r = size.minDimension / 2
        drawCircle(Brush.sweepGradient(listOf(Color(0xFFFF4040), Color(0xFFFF40FF), Color(0xFF4040FF), Color(0xFF40FFFF), Color(0xFF40FF40), Color(0xFFFFFF40), Color(0xFFFF4040))), r)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF808080), Color(0x00808080)), radius = r), r)
        drawCircle(Color.Black.copy(alpha = 0.35f), r, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, 6.dp.toPx(), Offset(r + x * r, r - y * r), style = Stroke(2.dp.toPx()))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurveEditor(points: List<Pair<Float, Float>>, color: Color, onChange: (List<Pair<Float, Float>>) -> Unit, onEnd: () -> Unit) {
    var pts by remember(points) { mutableStateOf(points) }
    Canvas(
        Modifier.padding(vertical = 6.dp).size(220.dp).clip(RoundedCornerShape(10.dp)).background(Amiri.SurfaceHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    fun norm(o: Offset) = (o.x / w).coerceIn(0f, 1f) to (1f - o.y / h).coerceIn(0f, 1f)
                    val (x0, y0) = norm(d.position)
                    var list = pts.toMutableList()
                    var idx = list.indices.minByOrNull { hypot(list[it].first - x0, list[it].second - y0) } ?: -1
                    if (idx < 0 || hypot(list[idx].first - x0, list[idx].second - y0) > 0.08f) {
                        list.add(x0 to y0); list = list.sortedBy { it.first }.toMutableList()
                        idx = list.indexOfFirst { it.first == x0 && it.second == y0 }
                    }
                    val start = System.currentTimeMillis()
                    var moved = false
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        if ((ch.position - d.position).getDistance() > 6f) moved = true
                        val (x, y) = norm(ch.position)
                        val lo = if (idx > 0) list[idx - 1].first + 0.01f else 0f
                        val hi = if (idx < list.size - 1) list[idx + 1].first - 0.01f else 1f
                        val fixedX = if (idx == 0) 0f.coerceAtMost(x) else if (idx == list.size - 1) 1f.coerceAtLeast(x) else x
                        list[idx] = (if (idx == 0 || idx == list.size - 1) list[idx].first else fixedX.coerceIn(lo, hi)) to y
                        pts = list.toList()
                        onChange(pts)
                        ch.consume()
                    } while (ev.changes.any { it.pressed })
                    if (!moved && System.currentTimeMillis() - start > 450 && idx in 1 until list.size - 1) {
                        list.removeAt(idx); pts = list.toList(); onChange(pts)
                    }
                    onEnd()
                }
            },
    ) {
        val w = size.width; val h = size.height
        for (k in 1..3) {
            drawLine(Color.White.copy(alpha = 0.08f), Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1f)
            drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
        }
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h), Offset(w, 0f), 1f)
        val s = CurveBuilder.sample(pts)
        val path = Path()
        for (i in 0 until 256) { val o = Offset(i / 255f * w, (1f - s[i]) * h); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
        pts.forEach { drawCircle(Color.White, 5.dp.toPx(), Offset(it.first * w, (1f - it.second) * h)) }
    }
}

// ═══════════════════════════════ Effects ═══════════════════════════════

@Composable
internal fun EffectsPanel(c: EditorController) {
    val accent = LocalAccent.current
    var cat by remember { mutableStateOf(EffectCategory.LIGHT) }
    val clip = visualSelected(c)
    Column {
        Row {
            PanelAction(Icons.Outlined.Layers, "Adjustment layer") { c.addAdjustmentLayer() }
        }
        if (clip == null) { Hint("Select a clip to add effects, or add an adjustment layer to affect every layer below it."); return@Column }
        SectionTitle("Add effect")
        ChoiceChips(EffectCategory.entries.toList(), cat, { it.label }) { cat = it }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EffectCatalog.byCategory(cat).forEach { sp ->
                Box(Modifier.glass(RoundedCornerShape(10.dp), 0.7f).clickable { c.addEffect(sp.type) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("+ " + sp.label, color = Amiri.TextPrimary, fontSize = 12.sp)
                }
            }
        }
        if (clip.effects.isEmpty()) { Hint("Effects run on the GPU in order, top to bottom, in preview and export."); return@Column }
        SectionTitle("Effect stack (top → bottom)")
        val sel = clip.effects.firstOrNull { it.id == c.selectedEffectId } ?: clip.effects.last()
        clip.effects.forEachIndexed { i, e ->
            val spec = EffectCatalog.spec(e.type)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (e.id == sel.id) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { c.selectedEffectId = e.id }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}. ${spec?.label ?: e.type}", color = if (e.enabled) Amiri.TextPrimary else Amiri.TextTertiary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(if (e.enabled) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, "Bypass", tint = Amiri.TextSecondary,
                    modifier = Modifier.size(30.dp).padding(5.dp).clickable { c.updateEffect(e.id, "Bypass effect") { it.copy(enabled = !it.enabled) } })
                Icon(Icons.Outlined.ArrowUpward, "Up", tint = Amiri.TextSecondary, modifier = Modifier.size(30.dp).padding(5.dp).clickable { c.moveEffect(e.id, -1) })
                Icon(Icons.Outlined.ArrowDownward, "Down", tint = Amiri.TextSecondary, modifier = Modifier.size(30.dp).padding(5.dp).clickable { c.moveEffect(e.id, 1) })
                Icon(Icons.Outlined.DeleteOutline, "Remove", tint = Amiri.Danger, modifier = Modifier.size(30.dp).padding(5.dp).clickable { c.removeEffect(e.id) })
            }
        }
        val spec = EffectCatalog.spec(sel.type) ?: return@Column
        SectionTitle(spec.label)
        spec.options.forEach { o ->
            Text(o.label, color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            ChoiceChips(o.choices, sel.opts[o.id] ?: o.default, { it }) { v ->
                if (sel.type == "film" && o.id == "preset") c.applyFilmPreset(sel.id, v)
                else c.updateEffect(sel.id, o.label) { it.copy(opts = it.opts + (o.id to v)) }
            }
        }
        if (sel.type == "lut") {
            val luts = remember(c.historyVersion) { c.app.luts.list() }
            if (luts.isEmpty()) Hint("No LUTs yet — import one in Color → LUT.")
            else ChoiceChips(luts, sel.opts["file"] ?: "", { it.removeSuffix(".cube") }) { n -> c.updateEffect(sel.id, "LUT") { it.copy(opts = it.opts + ("file" to n)) } }
        }
        if (sel.type == "color") { Hint("Edit this grade in the Color tool."); return@Column }
        KeyframeBar(c, clip)
        val t = EditorController.PTarget.Fx(clip.id, sel.id)
        val colorIds = spec.colors.flatMap { listOf(it.first, it.second, it.third) }.toSet()
        spec.params.filter { it.id !in colorIds }.forEach { ParamRow(c, t, it) }
        spec.colors.forEach { tr ->
            val d = floatArrayOf(spec.param(tr.first)!!.default, spec.param(tr.second)!!.default, spec.param(tr.third)!!.default)
            ColorRow(c, t, tr, if (sel.type == "chroma") "Key color" else "Color", d, eyedropper = sel.type == "chroma")
        }
    }
}

// ═══════════════════════════════ Audio ═══════════════════════════════

@Composable
internal fun AudioPanel(c: EditorController) {
    var preferName by remember { mutableStateOf("SFX") }
    // Audio files and videos (e.g. clips saved from Instagram) — only their sound is used.
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) c.importSound(uri, preferName)
    }
    val types = arrayOf("audio/*", "video/*")
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PanelAction(Icons.Outlined.GraphicEq, "+ Sound effect") { preferName = "SFX"; soundPicker.launch(types) }
            PanelAction(Icons.Outlined.LibraryMusic, "+ Music") { preferName = "Music"; soundPicker.launch(types) }
            PanelAction(Icons.Outlined.Mic, "+ Voice") { preferName = "Voice"; soundPicker.launch(types) }
            val sel = c.selectedClip()
            val selTrack = sel?.let { c.project?.trackOfClip(it.id) }
            if (sel != null && selTrack?.acceptsVisual == true && c.project?.asset(sel.assetId)?.hasAudio == true && !sel.muted) {
                PanelAction(Icons.Outlined.LinkOff, "Detach audio") { c.detachAudio(sel.id) }
            }
            if (sel != null) PanelAction(Icons.Outlined.DeleteOutline, "Delete clip") { c.deleteSelected() }
        }
        val clip = c.selectedClip()?.takeIf { cl -> c.project?.asset(cl.assetId)?.hasAudio == true }
        if (clip == null) {
            Hint("Sound effects go on their own SFX tracks under the music, at the playhead. You can pick audio files or videos (only the sound is used). Select a clip with sound to set volume, fades, pan and EQ.")
            return@Column
        }
        val t = EditorController.PTarget.Audio(clip.id)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(if (clip.muted) "Muted" else "Mute", clip.muted) { c.updateSelected("Mute") { it.copy(muted = !it.muted) } }
        }
        KeyframeBar(c, clip)
        AudioSpec.PARAMS.forEach { ParamRow(c, t, it) }
        Hint("All of these can be keyframed and sound the same in preview and export. Up to 100% volume is heard in preview; boosts above 100% are applied in export.")
    }
}

// ═══════════════════════════════ Shapes ═══════════════════════════════

@Composable
internal fun ShapePanel(c: EditorController) {
    val clip = c.selectedClip()?.takeIf { it.kind == ClipKind.SHAPE }
    val PATH = com.amiri.cut.core.model.ShapeKind.PATH
    Column {
        if (c.shapePen) {
            val n = c.penPoints.size / 6
            Text(
                if (c.penFreehand) "Freehand: draw the line with one finger. Two fingers zoom/pan."
                else "Pen: tap = corner point · tap & drag = curve · tap the yellow first point to close. Two fingers zoom/pan.",
                color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ToggleChip("Freehand", c.penFreehand) { c.penFreehand = !c.penFreehand; c.penPoints.clear() }
                if (!c.penFreehand) {
                    PanelAction(Icons.Outlined.Check, "Finish line ($n)") { c.finishPen(false) }
                    PanelAction(Icons.Outlined.CropFree, "Close shape") { c.finishPen(true) }
                    PanelAction(Icons.AutoMirrored.Outlined.Undo, "Undo point") { c.penUndo() }
                }
                PanelAction(Icons.Outlined.DeleteOutline, "Cancel") { c.cancelPen() }
            }
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PanelAction(Icons.Outlined.Draw, "+ Pen / line") { c.addShape(PATH) }
            com.amiri.cut.core.model.ShapeKind.entries.filter { it != PATH }.forEach { k ->
                PanelAction(
                    when (k) {
                        com.amiri.cut.core.model.ShapeKind.RECT -> Icons.Outlined.CropFree
                        com.amiri.cut.core.model.ShapeKind.ELLIPSE -> Icons.Outlined.GpsFixed
                        com.amiri.cut.core.model.ShapeKind.POLYGON -> Icons.Outlined.Layers
                        com.amiri.cut.core.model.ShapeKind.STAR -> Icons.Outlined.Add
                        else -> Icons.Outlined.Flip
                    },
                    "+ " + k.label,
                ) { c.addShape(k) }
            }
        }
        if (clip == null) { Hint("Add a shape or draw a line / shape with the pen (stroke or fill, like After Effects shape layers). Select a shape layer to edit it; animate it with keyframes, Trim paths or the Saber effect."); return@Column }
        val spec = clip.shape ?: return@Column
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (spec.kind == PATH) {
                ToggleChip("Edit points", c.pathEdit) { c.pathEdit = !c.pathEdit }
                ToggleChip("Closed (fill)", spec.closed) { c.setPathClosed(!spec.closed) }
                ToggleChip("Round ends", spec.roundCaps) { c.setPathRoundCaps(!spec.roundCaps) }
            }
            PanelAction(Icons.Outlined.DeleteOutline, "Delete layer") { c.deleteSelected() }
        }
        if (spec.kind != PATH) ChoiceChips(com.amiri.cut.core.model.ShapeKind.entries.filter { it != PATH }, spec.kind, { it.label }) { c.setShapeKind(it) }
        KeyframeBar(c, clip)
        val t = EditorController.PTarget.Shape(clip.id)
        val skip = if (spec.kind == PATH) setOf("w", "h", "radius", "sides", "inner") else emptySet()
        com.amiri.cut.core.effects.ShapeSpecDefaults.PARAMS.filter { it.id !in skip }.forEach { ParamRow(c, t, it) }
        com.amiri.cut.core.effects.ShapeSpecDefaults.COLORS.forEach { (pre, label, def) -> ColorRow(c, t, Triple("${pre}r", "${pre}g", "${pre}b"), label, def) }
    }
}

// ═══════════════════════════════ Keyframes ═══════════════════════════════

@Composable
internal fun KeyframesPanel(c: EditorController) {
    val clip = visualSelected(c) ?: c.selectedClip() ?: return NeedClip("Select a clip. Then press ◆ (in the bar under the preview) or any ◇ next to a value to add keyframes at the playhead.")
    val pos by c.engine.position.collectAsState()
    @Suppress("UNUSED_EXPRESSION") pos
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PanelAction(Icons.Outlined.Add, if (c.quickKeyHere()) "Remove ◆" else "Key ◆ all") { c.toggleQuickKeys() }
            PanelAction(Icons.Outlined.ChevronLeft, "Prev key") { c.jumpKey(clip.id, false) }
            PanelAction(Icons.Outlined.ChevronRight, "Next key") { c.jumpKey(clip.id, true) }
        }
        Hint("◆ keys Position, Scale, Rotation and Opacity together. Change values at another time to create motion. Keyframes show as diamonds on the clip in the timeline — drag them to retime.")
        KeyframeBar(c, clip)
        // Every animated parameter of this clip, grouped by owner.
        data class Row3(val target: EditorController.PTarget, val spec: ParamSpec, val group: String)
        val rows = ArrayList<Row3>()
        val tt = EditorController.PTarget.Transform(clip.id)
        (TransformSpec.PARAMS + TransformSpec.MOTION_BLUR).forEach { rows += Row3(tt, it, "Transform") }
        clip.effects.forEach { e ->
            val sp = EffectCatalog.spec(e.type) ?: return@forEach
            sp.params.forEach { rows += Row3(EditorController.PTarget.Fx(clip.id, e.id), it, sp.label) }
        }
        clip.masks.forEachIndexed { i, m -> MaskSpec.PARAMS.forEach { rows += Row3(EditorController.PTarget.Mask(clip.id, m.id), it, "Mask ${i + 1}") } }
        if (clip.text != null) TextSpecDefaults.STYLE.forEach { rows += Row3(EditorController.PTarget.Text(clip.id), it, "Text") }
        if (clip.shape != null) com.amiri.cut.core.effects.ShapeSpecDefaults.PARAMS.forEach { rows += Row3(EditorController.PTarget.Shape(clip.id), it, "Shape") }
        AudioSpec.PARAMS.forEach { rows += Row3(EditorController.PTarget.Audio(clip.id), it, "Audio") }
        val animated = rows.filter { c.isAnimated(it.target, it.spec.id) }
        SectionTitle("Animated (${animated.size})")
        if (animated.isEmpty()) Hint("Nothing animated yet.")
        animated.forEach { r -> ParamRow(c, r.target, r.spec, r.group + " · " + r.spec.label) }
        SectionTitle("Transform")
        TransformSpec.PARAMS.take(7).forEach { ParamRow(c, tt, it) }
    }
}

@Suppress("unused")
private fun keepMath() = listOf(min(1, 2), max(1, 2), sin(0.0), cos(0.0), atan2(0.0, 1.0))
@Suppress("unused")
private val keepIcons = listOf(Icons.Outlined.FontDownload, Icons.Outlined.LinkOff)
