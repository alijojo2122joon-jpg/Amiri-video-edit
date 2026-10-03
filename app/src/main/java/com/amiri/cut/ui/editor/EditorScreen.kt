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
}

@Composable
private fun EditorLayout(c: EditorController, onBack: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(0.55f).fillMaxHeight()) {
                TopBar(c, onBack)
                PreviewPane(c, Modifier.weight(1f).fillMaxWidth())
                TransportBar(c)
            }
            Column(Modifier.weight(0.45f).fillMaxHeight()) {
                TimelineView(c, Modifier.weight(1f).fillMaxWidth())
                ToolArea(c)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(c, onBack)
            PreviewPane(c, Modifier.weight(1f).fillMaxWidth())
            TransportBar(c)
            TimelineView(c, Modifier.fillMaxWidth().height(250.dp))
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconAction(Icons.Outlined.BookmarkAdd, "Add marker", size = 36) { c.addMarker(); Haptics.tick(view) }
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
                    Icon(t.icon(), t.label, tint = if (active) accent else Amiri.TextPrimary, modifier = Modifier.size(21.dp))
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
            val actions: List<Pair<String, () -> Unit>> = listOf(
                "Split at playhead" to { c.select(clip.id); c.split(); Haptics.confirm(view) },
                "Duplicate" to { c.select(clip.id); c.duplicateSelected() },
                (if (clip.locked) "Unlock clip" else "Lock clip") to { c.toggleClipLock(clip.id) },
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
