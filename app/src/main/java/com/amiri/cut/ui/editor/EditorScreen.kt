package com.amiri.cut.ui.editor

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.ui.common.IconAction
import com.amiri.cut.ui.common.TextInputDialog
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.MonoStyle
import com.amiri.cut.ui.theme.glass
import com.amiri.cut.ui.theme.glassAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    app: AmiriCutApp,
    projectId: String,
    importUris: List<Uri>,
    recover: Boolean,
    onExit: () -> Unit,
) {
    val c = remember(projectId) { EditorController(app, projectId) }
    LaunchedEffect(c) {
        c.load(recover)
        if (importUris.isNotEmpty()) c.importUris(importUris, Placement.APPEND_TO_MAIN)
    }
    // If the screen leaves composition without the back button (e.g. activity finishing), still save.
    DisposableEffect(c) { onDispose { c.close() } }

    BackHandler {
        if (c.activeTool != null) c.activeTool = null else c.close(onExit)
    }

    val p = c.project
    Box(Modifier.fillMaxSize().background(Amiri.Bg)) {
        when {
            c.loadError != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.loadError!!, color = Amiri.TextSecondary)
                TextButton(onClick = onExit) { Text("Back") }
            }
            p == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = LocalAccent.current)
            else -> EditorLayout(c, onBack = { c.close(onExit) })
        }
        ToastHost(c, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 60.dp))
    }

    c.clipMenuFor?.let { id -> ClipMenuSheet(c, id) }
    c.keyMenu?.let { (id, t) -> KeyMenuSheet(c, id, t) }
    c.attachPrompt?.let { id -> AttachSheet(c, id) }
}

@Composable
private fun EditorLayout(c: EditorController, onBack: () -> Unit) {
    LaunchedEffect(Unit) { com.amiri.cut.ui.theme.CatSounds.stopPurr() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Roto needs a big canvas: hide the timeline automatically (toggle with ⤢ in the transport bar).
    LaunchedEffect(c.activeTool) {
        c.expandedPreview = c.activeTool == EditorTool.ROTO
        if (c.activeTool != EditorTool.SHAPE && c.activeTool != EditorTool.EFFECTS) c.cancelPen()
        if (c.activeTool != EditorTool.SHAPE) c.pathEdit = false
    }
    val expanded = c.expandedPreview
    if (landscape) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(0.55f).fillMaxHeight()) {
                TopBar(c, onBack)
                PreviewPane(c, Modifier.weight(1f).fillMaxWidth())
                TransportBar(c)
            }
            Column(Modifier.weight(0.45f).fillMaxHeight()) {
                if (!expanded) LayerFxStrip(c)
                if (!expanded) TimelineView(c, Modifier.weight(1f).fillMaxWidth()) else Box(Modifier.weight(1f))
                ToolArea(c)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(c, onBack)
            PreviewPane(c, Modifier.weight(1f).fillMaxWidth())
            TransportBar(c)
            if (!expanded) LayerFxStrip(c)
            if (!expanded) TimelineView(c, Modifier.fillMaxWidth().height(250.dp))
            ToolArea(c)
        }
    }
}

@Composable
private fun TopBar(c: EditorController, onBack: () -> Unit) {
    val p = c.project ?: return
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "Save and close", onClick = onBack)
        Column(Modifier.weight(1f).clickable { rename = true }.padding(horizontal = 6.dp)) {
            Text(p.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${p.settings.width}×${p.settings.height} · ${p.settings.fps} fps",
                color = Amiri.TextTertiary, fontSize = 10.sp,
            )
        }
        if (c.selectedClipId != null) {
            IconAction(Icons.Outlined.DeleteOutline, "Delete selected layer", tint = Amiri.Danger) { c.deleteSelected() }
        }
        IconAction(Icons.AutoMirrored.Outlined.Undo, "Undo", enabled = c.canUndo) { c.undo() }
        IconAction(Icons.AutoMirrored.Outlined.Redo, "Redo", enabled = c.canRedo) { c.redo() }
        Box(
            Modifier.padding(horizontal = 4.dp).glassAccent(accent, RoundedCornerShape(12.dp))
                .clickable { c.engine.pause(); export = true }.padding(horizontal = 12.dp, vertical = 7.dp),
        ) { Text("Export", color = Amiri.TextPrimary, fontSize = 13.sp) }
        Box {
            IconAction(Icons.Outlined.GridOn, "Guides and project", onClick = { guides = true })
            DropdownMenu(expanded = guides, onDismissRequest = { guides = false }) {
                CheckItem("Grid (thirds)", c.showGrid) { c.showGrid = !c.showGrid }
                CheckItem("Center", c.showCenter) { c.showCenter = !c.showCenter }
                CheckItem("Action safe", c.showActionSafe) { c.showActionSafe = !c.showActionSafe }
                CheckItem("Title safe", c.showTitleSafe) { c.showTitleSafe = !c.showTitleSafe }
                CheckItem("Reels / Shorts / TikTok / YouTube zones", c.showPlatformZones) { c.showPlatformZones = !c.showPlatformZones }
                HorizontalDivider(color = Amiri.Line)
                CheckItem("Scopes (histogram · waveform · vectorscope)", c.showScopes) { c.showScopes = !c.showScopes }
                HorizontalDivider(color = Amiri.Line)
                com.amiri.cut.storage.PreviewQuality.entries.forEach { q ->
                    CheckItem("Preview quality: ${q.label}", c.app.settings.previewQuality == q) { c.app.settings.updatePreviewQuality(q) }
                }
                CheckItem("Proxy mode (use proxies when built)", c.app.settings.proxyMode) { c.app.settings.updateProxyMode(!c.app.settings.proxyMode) }
                HorizontalDivider(color = Amiri.Line)
                DropdownMenuItem(
                    text = { Text("Save project") },
                    leadingIcon = { Icon(Icons.Outlined.Save, null) },
                    onClick = { guides = false; scope.launch { c.saveNow() } },
                )
            }
        }
    }
    if (rename) {
        TextInputDialog("Rename project", p.name, onDismiss = { rename = false }) { rename = false; c.rename(it) }
    }
    if (export) ExportSheet(c) { export = false }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Check, null, tint = if (checked) accent else Color.Transparent) },
        onClick = onClick,
    )
}

@Composable
private fun TransportBar(c: EditorController) {
    val p = c.project ?: return
    val pos by c.engine.position.collectAsState()
    val playing by c.engine.playing.collectAsState()
    val accent = LocalAccent.current
    val view = LocalView.current
    val fps = p.settings.fps
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(FrameTime.timecode(pos, fps), color = Amiri.TextPrimary, style = MonoStyle)
            Text(FrameTime.timecode(p.durationUs, fps), color = Amiri.TextTertiary, style = MonoStyle, fontSize = 10.sp)
        }
        IconAction(Icons.Outlined.SkipPrevious, "Previous edit", size = 36) { c.jumpPrevEdit() }
        IconAction(Icons.Outlined.ChevronLeft, "Previous frame", size = 36) { c.engine.stepFrames(-1); Haptics.tick(view) }
        Box(
            Modifier.padding(horizontal = 6.dp).size(44.dp).glassAccent(accent, CircleShape)
                .clickable { c.engine.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", tint = Amiri.TextPrimary)
        }
        IconAction(Icons.Outlined.ChevronRight, "Next frame", size = 36) { c.engine.stepFrames(1); Haptics.tick(view) }
        IconAction(Icons.Outlined.SkipNext, "Next edit", size = 36) { c.jumpNextEdit() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val keyed = c.selectedClipId != null && c.quickKeyHere()
            Box(
                Modifier.size(36.dp).clickable { c.toggleQuickKeys(); Haptics.confirm(view) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
                    rotate(45f) {
                        val s = size.minDimension * 0.7f
                        val o = Offset((size.width - s) / 2, (size.height - s) / 2)
                        if (keyed) drawRect(accent, o, androidx.compose.ui.geometry.Size(s, s))
                        else drawRect(if (c.selectedClipId != null) Amiri.TextPrimary else Amiri.TextTertiary, o, androidx.compose.ui.geometry.Size(s, s), style = Stroke(2f))
                    }
                }
            }
            IconAction(if (c.expandedPreview) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull, "Bigger preview", size = 34) { c.expandedPreview = !c.expandedPreview }
            IconAction(Icons.Outlined.BookmarkAdd, "Add marker", size = 34) { c.addMarker(); Haptics.tick(view) }
        }
    }
}

private fun EditorTool.icon(): ImageVector = when (this) {
    EditorTool.MEDIA -> Icons.Outlined.PermMedia
    EditorTool.CUT -> Icons.Outlined.ContentCut
    EditorTool.TRANSFORM -> Icons.Outlined.CropRotate
    EditorTool.SPEED -> Icons.Outlined.Speed
    EditorTool.MASK -> Icons.Outlined.Layers
    EditorTool.TRACK -> Icons.Outlined.TrackChanges
    EditorTool.ROTO -> Icons.Outlined.Brush
    EditorTool.TEXT -> Icons.Outlined.TextFields
    EditorTool.COLOR -> Icons.Outlined.Palette
    EditorTool.EFFECTS -> Icons.Outlined.AutoFixHigh
    EditorTool.AUDIO -> Icons.Outlined.GraphicEq
    EditorTool.KEYS -> Icons.Outlined.Timeline
    EditorTool.SHAPE -> Icons.Outlined.Category
    EditorTool.TRANSITION -> Icons.Outlined.Animation
    EditorTool.FILTERS -> Icons.Outlined.FilterVintage
    EditorTool.STABILIZE -> Icons.Outlined.CenterFocusWeak
}

@Composable
private fun ToolArea(c: EditorController) {
    val accent = LocalAccent.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = c.activeTool != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            c.activeTool?.let { ToolPanel(c, it) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EditorTool.entries.forEach { t ->
                val active = c.activeTool == t
                Column(
                    Modifier.width(60.dp)
                        .then(if (active) Modifier.glass(RoundedCornerShape(14.dp)) else Modifier)
                        .clickable {
                            Haptics.select(view)
                            c.activeTool = if (active) null else t
                        }
                        .padding(vertical = 7.dp)
                        .alpha(if (t.available) 1f else 0.45f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The active tool wears little cat ears.
                    Box(contentAlignment = Alignment.Center) {
                        if (active) androidx.compose.foundation.Canvas(Modifier.size(width = 30.dp, height = 30.dp)) {
                            val ear = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * 0.12f, size.height * 0.3f); lineTo(size.width * 0.2f, 0f); lineTo(size.width * 0.38f, size.height * 0.18f); close()
                                moveTo(size.width * 0.88f, size.height * 0.3f); lineTo(size.width * 0.8f, 0f); lineTo(size.width * 0.62f, size.height * 0.18f); close()
                            }
                            drawPath(ear, accent.copy(alpha = 0.85f))
                        }
                        Icon(t.icon(), t.label, tint = if (active) accent else Amiri.TextPrimary, modifier = Modifier.size(21.dp))
                    }
                    Text(t.label, color = if (active) accent else Amiri.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun ToastHost(c: EditorController, modifier: Modifier) {
    val t = c.toast
    LaunchedEffect(t) {
        if (t != null) {
            delay(1_900)
            if (c.toast == t) c.toast = null
        }
    }
    AnimatedVisibility(visible = t != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            t?.text ?: "",
            color = Amiri.TextPrimary, fontSize = 12.sp,
            modifier = Modifier.glass(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The selected layer's effects (and roto / stabilization) as chips right above the
 * timeline: tap the dot to switch one on/off, tap the name to edit it, ✕ to delete it.
 */
@Composable
private fun LayerFxStrip(c: EditorController) {
    val clip = c.selectedClip() ?: return
    val items = ArrayList<Triple<String, Boolean, String>>() // key, enabled, label
    clip.effects.forEach { e -> items += Triple("fx:" + e.id, e.enabled, com.amiri.cut.core.effects.EffectCatalog.spec(e.type)?.label ?: e.type) }
    clip.roto?.takeIf { it.keys.isNotEmpty() }?.let { items += Triple("roto", it.enabled, "Roto") }
    clip.stab?.let { items += Triple("stab", it.enabled, "Stabilize") }
    if (clip.masks.isNotEmpty()) items += Triple("masks", true, "Masks ${clip.masks.size}")
    if (items.isEmpty()) return
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0D0D0F)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("fx", color = Amiri.TextTertiary, fontSize = 11.sp)
        items.forEach { (key, on, label) ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) accent.copy(alpha = 0.16f) else Amiri.Surface)
                    .padding(start = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).clickable { c.toggleLayerItem(clip.id, key) }.padding(7.dp)
                        .clip(CircleShape).background(if (on) accent else Amiri.TextTertiary.copy(alpha = 0.4f)),
                )
                Text(
                    label, color = if (on) Amiri.TextPrimary else Amiri.TextTertiary, fontSize = 12.sp,
                    modifier = Modifier.clickable { c.openLayerItem(clip.id, key) }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                if (key != "masks") Icon(
                    Icons.Outlined.Close, "Delete $label", tint = Amiri.TextSecondary,
                    modifier = Modifier.size(26.dp).clickable { c.deleteLayerItem(clip.id, key) }.padding(6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(c: EditorController, trackedClipId: String) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) c.attachOverlayFromUri(trackedClipId, uri) else c.attachPrompt = null
    }
    ModalBottomSheet(onDismissRequest = { c.attachPrompt = null }, containerColor = Amiri.SurfaceHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Tracking done ✓", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                "What should follow the tracked point? It is placed exactly on the point at the playhead and moves with it.",
                color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachChoice(Icons.Outlined.TextFields, "Text", Modifier.weight(1f)) { c.attachNewToTrack(trackedClipId, EditorController.AttachKind.TEXT) }
                AttachChoice(Icons.Outlined.Category, "Shape", Modifier.weight(1f)) { c.attachNewToTrack(trackedClipId, EditorController.AttachKind.SHAPE) }
                AttachChoice(Icons.Outlined.PermMedia, "Overlay", Modifier.weight(1f)) { picker.launch(arrayOf("image/*", "video/*")) }
            }
            Text(
                "Not now", color = Amiri.TextSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp).clickable { c.attachPrompt = null }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AttachChoice(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Amiri.Surface).clickable(onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = LocalAccent.current, modifier = Modifier.size(28.dp))
        Text(label, color = Amiri.TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyMenuSheet(c: EditorController, clipId: String, local: Long) {
    val p = c.project ?: return
    val clip = p.clip(clipId) ?: run { c.keyMenu = null; return }
    ModalBottomSheet(onDismissRequest = { c.keyMenu = null }, containerColor = Amiri.SurfaceHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Keyframe · ${clip.name}", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                FrameTime.timecode(clip.startUs + local, p.settings.fps) + "  ·  double-tap a keyframe to toggle Easy Ease",
                color = Amiri.TextSecondary, style = MonoStyle, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            val actions: List<Pair<String, () -> Unit>> = listOf(
                "⧗  Easy Ease (both sides)" to { c.easyEaseAt(clipId, local) },
                "◁  Ease in (arrive slowly)" to { c.easeArriveAt(clipId, local) },
                "▷  Ease out (leave slowly)" to { c.easeLeaveAt(clipId, local) },
                "◆  Linear" to { c.linearAt(clipId, local) },
                "■  Hold (jump to next key)" to { c.setInterpAllAt(clipId, local, com.amiri.cut.core.model.Interp.HOLD) },
                "Curve editor (Keys tool)" to { c.select(clipId); c.activeTool = EditorTool.KEYS },
                "Copy keyframes here" to { c.select(clipId); c.copyKeyframesAt() },
                "Paste keyframes at playhead" to { c.select(clipId); c.pasteKeyframesAt() },
                "Delete keyframe" to { c.deleteKeysAt(clipId, local) },
            )
            actions.forEach { (label, f) ->
                Text(
                    label,
                    color = if (label.startsWith("Delete")) Amiri.Danger else Amiri.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { c.keyMenu = null; f() }.padding(vertical = 13.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipMenuSheet(c: EditorController, clipId: String) {
    val p = c.project ?: return
    val clip = p.clip(clipId) ?: run { c.clipMenuFor = null; return }
    val view = LocalView.current
    ModalBottomSheet(onDismissRequest = { c.clipMenuFor = null }, containerColor = Amiri.SurfaceHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(clip.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${FrameTime.timecode(clip.startUs, p.settings.fps)} → ${FrameTime.timecode(clip.endUs, p.settings.fps)} · " +
                    "${FrameTime.toFrame(clip.durationUs, p.settings.fps)} frames",
                color = Amiri.TextSecondary, style = MonoStyle, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            val actions: List<Pair<String, () -> Unit>> = listOf<Pair<String, () -> Unit>>(
                "Split at playhead" to { c.select(clip.id); c.split(); Haptics.confirm(view) },
                "Duplicate" to { c.select(clip.id); c.duplicateSelected() },
                (if (clip.locked) "Unlock clip" else "Lock clip") to { c.toggleClipLock(clip.id) },
            ) + (if (p.trackOfClip(clip.id)?.acceptsVisual == true && p.asset(clip.assetId)?.hasAudio == true && !clip.muted)
                listOf<Pair<String, () -> Unit>>("Detach audio" to { c.detachAudio(clip.id) }) else emptyList()) + listOf<Pair<String, () -> Unit>>(
                "Copy effects & style" to { c.copyAttributes(clip.id) },
            ) + (if (c.clipboard != null) listOf<Pair<String, () -> Unit>>(
                "Paste effects" to { c.pasteAttributes("effects", clip.id) },
                "Paste position / scale / rotation" to { c.pasteAttributes("transform", clip.id) },
                "Paste everything (style + effects)" to { c.pasteAttributes("all", clip.id) },
            ) else emptyList()) + (if (clip.kind != com.amiri.cut.core.model.ClipKind.TEXT && c.project?.markers?.any { it.label == "♪" } == true)
                listOf<Pair<String, () -> Unit>>("Cut on beats" to { c.select(clip.id); c.cutOnBeats() }) else emptyList()) + listOf<Pair<String, () -> Unit>>(
                "Delete" to { c.select(clip.id); c.deleteSelected() },
                "Ripple delete" to { c.select(clip.id); c.rippleDeleteSelected() },
            )
            actions.forEach { (label, f) ->
                Text(
                    label,
                    color = if (label.startsWith("Delete") || label.startsWith("Ripple")) Amiri.Danger else Amiri.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { c.clipMenuFor = null; f() }.padding(vertical = 13.dp),
                )
            }
        }
    }
}
