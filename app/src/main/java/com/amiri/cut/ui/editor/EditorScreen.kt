package com.amiri.cut.ui.editor

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.model.ClipKind
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.ui.common.ActionSheet
import com.amiri.cut.ui.common.SheetAction
import com.amiri.cut.ui.common.TextInputDialog
import com.amiri.cut.ui.picker.GalleryItem
import com.amiri.cut.ui.picker.MediaPickerScreen
import com.amiri.cut.ui.picker.PickRequest
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.MonoStyle
import com.amiri.cut.ui.theme.onAccent
import com.amiri.cut.ui.theme.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    app: AmiriCutApp,
    projectId: String,
    importUris: List<Uri>,
    recover: Boolean,
    quick: QuickStart? = null,
    demo: com.amiri.cut.export.UiDemo.Plan? = null,
    onExit: () -> Unit,
) {
    val c = remember(projectId) { EditorController(app, projectId) }
    LaunchedEffect(c) {
        c.load(recover)
        if (importUris.isNotEmpty()) c.importNow(importUris, Placement.APPEND_TO_MAIN)
        quick?.let { delay(250); c.runQuickStart(it) }
        demo?.let { c.runDemo(it) }
    }
    // If the screen leaves composition without the back button (e.g. activity finishing), still save.
    DisposableEffect(c) { onDispose { c.close() } }

    BackHandler {
        when {
            c.picker != null -> c.picker = null
            c.activeTool != null -> c.activeTool = null
            c.selectedClipId != null -> c.select(null)
            else -> c.close(onExit)
        }
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
        ToastHost(c, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 64.dp))
        // Gallery picker over the editor (Add media / Overlay / Music / Replace).
        AnimatedVisibility(
            visible = c.picker != null,
            enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            val pick = c.picker
            if (pick != null) MediaPickerScreen(
                request = pick.request,
                onCancel = { c.picker = null },
                onPicked = { uris -> c.picker = null; c.onPicked(pick, uris) },
            )
        }
    }

    c.clipMenuFor?.let { id -> ClipMenuSheet(c, id) }
    c.keyMenu?.let { (id, t) -> KeyMenuSheet(c, id, t) }
    c.attachPrompt?.let { id -> AttachSheet(c, id) }
    c.trackMenuFor?.let { id -> TrackMenuSheet(c, id) }
}

@Composable
private fun EditorLayout(c: EditorController, onBack: () -> Unit) {
    DisposableEffect(Unit) {
        com.amiri.cut.ui.theme.CatSounds.inEditor = true
        com.amiri.cut.ui.theme.CatSounds.stopPurr()
        onDispose { com.amiri.cut.ui.theme.CatSounds.inEditor = false }
    }
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
                PlaybackBar(c)
            }
            Box(Modifier.weight(0.45f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    if (!expanded) LayerFxStrip(c)
                    if (!expanded) TimelineView(c, Modifier.weight(1f).fillMaxWidth()) else Box(Modifier.weight(1f))
                    ContextToolbar(c)
                }
                ToolSheetHost(c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.92f))
            }
        }
    } else {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(c, onBack)
                PreviewPane(c, Modifier.weight(1f).fillMaxWidth())
                PlaybackBar(c)
                // The lower region (timeline + toolbar). Tool sheets slide up over it, so the
                // preview keeps its size while a tool is open (like CapCut).
                val sheetOpen = c.activeTool != null
                val lowerDp = if (expanded) 76f else c.timelineHeightDp + 76f + 14f + (if (c.selectedClip()?.let { it.effects.isNotEmpty() || it.roto != null || it.stab != null || it.masks.isNotEmpty() } == true) 34f else 0f)
                val regionDp by androidx.compose.animation.core.animateFloatAsState(
                    if (sheetOpen) maxOf(lowerDp, 336f) else lowerDp, label = "region",
                )
                Box(Modifier.fillMaxWidth().height(regionDp.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        if (!expanded) LayerFxStrip(c)
                        if (!expanded) TimelineResizeHandle(c)
                        if (!expanded) TimelineView(c, Modifier.fillMaxWidth().weight(1f)) else Spacer(Modifier.weight(1f))
                        ContextToolbar(c)
                    }
                    ToolSheetHost(c, Modifier.align(Alignment.BottomCenter).fillMaxSize())
                }
            }
            // A cat strolls along the bottom every minute (runs away when you touch the screen).
            if (c.activeTool == null) com.amiri.cut.ui.common.WalkingCat(Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp).fillMaxWidth().height(36.dp), Color(0xFF55555E), LocalAccent.current)
        }
    }
}

// ───────────────────────────── top bar ─────────────────────────────

@Composable
private fun RoundIcon(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Amiri.TextPrimary, size: Int = 38, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size.dp).pressScale(source, 0.86f).clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled) { Haptics.select(view); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) tint else Amiri.TextTertiary.copy(alpha = 0.55f), modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
private fun TopBar(c: EditorController, onBack: () -> Unit) {
    val p = c.project ?: return
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        com.amiri.cut.ui.common.DiscButton(Icons.Outlined.Close, "Save and close", size = 38) { onBack() }
        Box(Modifier.padding(start = 8.dp)) {
            com.amiri.cut.ui.common.DiscButton(Icons.Outlined.MoreHoriz, "Project menu", size = 38) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Amiri.SurfaceHigh, shape = RoundedCornerShape(18.dp)) {
                DropdownMenuItem(text = { Text("Rename project") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; rename = true })
                DropdownMenuItem(text = { Text("Save now") }, leadingIcon = { Icon(Icons.Outlined.Save, null) }, onClick = { menu = false; scope.launch { c.saveNow(); c.toast = Toast("Saved") } })
                DropdownMenuItem(text = { Text("Media in this project") }, leadingIcon = { Icon(Icons.Outlined.PermMedia, null) }, onClick = { menu = false; c.activeTool = EditorTool.MEDIA })
                HorizontalDivider(color = Amiri.Line)
                CheckItem("Grid (thirds)", c.showGrid) { c.showGrid = !c.showGrid }
                CheckItem("Center lines", c.showCenter) { c.showCenter = !c.showCenter }
                CheckItem("Safe areas", c.showActionSafe) { c.showActionSafe = !c.showActionSafe; c.showTitleSafe = c.showActionSafe }
                CheckItem("Reels / Shorts / TikTok zones", c.showPlatformZones) { c.showPlatformZones = !c.showPlatformZones }
                CheckItem("Scopes (histogram · waveform)", c.showScopes) { c.showScopes = !c.showScopes }
                HorizontalDivider(color = Amiri.Line)
                com.amiri.cut.storage.PreviewQuality.entries.forEach { q ->
                    CheckItem("Preview: ${q.label}", c.app.settings.previewQuality == q) { c.app.settings.updatePreviewQuality(q) }
                }
                CheckItem("Proxy mode", c.app.settings.proxyMode) { c.app.settings.updateProxyMode(!c.app.settings.proxyMode) }
                CheckItem("Pro track headers", c.proTrackHeaders) { c.proTrackHeaders = !c.proTrackHeaders }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { rename = true }) {
            Text(p.name, color = Amiri.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${p.settings.width}×${p.settings.height} · ${p.settings.fps} fps", color = Amiri.TextTertiary, fontSize = 11.sp,
                style = MonoStyle, maxLines = 1,
            )
        }
        val shortSide = minOf(p.settings.width, p.settings.height)
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(Amiri.SurfaceHigh).border(1.dp, Amiri.Line, RoundedCornerShape(50))
                .clickable { Haptics.select(view); c.engine.pause(); export = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (shortSide >= 2160) "4K" else "${shortSide}p", color = Amiri.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Amiri.TextSecondary, modifier = Modifier.size(18.dp))
        }
        val src = remember { MutableInteractionSource() }
        Box(
            Modifier.padding(start = 8.dp).height(36.dp).pressScale(src, 0.94f).clip(RoundedCornerShape(50)).background(Amiri.accentBrush(accent))
                .clickable(interactionSource = src, indication = null) { Haptics.confirm(view); c.engine.pause(); export = true }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Export", color = onAccent(accent), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
    if (rename) {
        TextInputDialog("Rename project", p.name, onDismiss = { rename = false }) { rename = false; c.rename(it) }
    }
    if (export || c.openExport) ExportSheet(c) { export = false; c.openExport = false }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Check, null, tint = if (checked) Amiri.accentInk(accent) else Color.Transparent) },
        onClick = onClick,
    )
}

// ───────────────────────────── playback row ─────────────────────────────

@Composable
private fun PlaybackBar(c: EditorController) {
    val p = c.project ?: return
    val pos by c.engine.position.collectAsState()
    val playing by c.engine.playing.collectAsState()
    val accent = LocalAccent.current
    val view = LocalView.current
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.Bottom) {
            Text(FrameTime.shortClock(pos), color = Amiri.TextPrimary, style = MonoStyle, fontSize = 14.sp)
            Text(" / " + FrameTime.shortClock(p.durationUs), color = Amiri.TextTertiary, style = MonoStyle, fontSize = 12.sp)
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Outlined.ChevronLeft, "Previous frame", size = 34, tint = Amiri.TextSecondary) { c.engine.stepFrames(-1); Haptics.tick(view) }
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.padding(horizontal = 6.dp).size(44.dp).pressScale(src, 0.88f).clip(CircleShape).background(Color.White)
                    .clickable(interactionSource = src, indication = null) { c.engine.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(28.dp))
            }
            RoundIcon(Icons.Outlined.ChevronRight, "Next frame", size = 34, tint = Amiri.TextSecondary) { c.engine.stepFrames(1); Haptics.tick(view) }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            if (c.selectedClipId != null) {
                val keyed = c.quickKeyHere()
                Box(
                    Modifier.size(34.dp).clip(CircleShape).clickable { c.toggleQuickKeys(); Haptics.confirm(view) },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
                        rotate(45f) {
                            val s = size.minDimension * 0.72f
                            val o = Offset((size.width - s) / 2, (size.height - s) / 2)
                            if (keyed) drawRect(Amiri.accentInk(accent), o, androidx.compose.ui.geometry.Size(s, s))
                            else drawRect(Amiri.TextPrimary, o, androidx.compose.ui.geometry.Size(s, s), style = Stroke(2.2f))
                        }
                    }
                }
            }
            RoundIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", enabled = c.canUndo, size = 34) { c.undo() }
            RoundIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", enabled = c.canRedo, size = 34) { c.redo() }
            RoundIcon(if (c.expandedPreview) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull, "Bigger preview", size = 34) { c.expandedPreview = !c.expandedPreview }
        }
    }
}

// ───────────────────────────── bottom toolbar & tool sheets ─────────────────────────────

private data class ToolItem(val label: String, val icon: ImageVector, val enabled: Boolean = true, val danger: Boolean = false, val accent: Boolean = false, val onClick: () -> Unit)

private fun EditorTool.title(): String = when (this) {
    EditorTool.MEDIA -> "Media"
    EditorTool.CUT -> "Edit"
    EditorTool.TRANSFORM -> "Transform & crop"
    EditorTool.SPEED -> "Speed"
    EditorTool.MASK -> "Mask"
    EditorTool.TRACK -> "Motion tracking"
    EditorTool.ROTO -> "Remove background"
    EditorTool.TEXT -> "Text"
    EditorTool.COLOR -> "Adjust"
    EditorTool.EFFECTS -> "Effects"
    EditorTool.AUDIO -> "Audio"
    EditorTool.KEYS -> "Keyframes"
    EditorTool.SHAPE -> "Shapes"
    EditorTool.TRANSITION -> "Transition"
    EditorTool.FILTERS -> "Filters"
    EditorTool.STABILIZE -> "Stabilize"
    EditorTool.RATIO -> "Ratio"
    EditorTool.BACKGROUND -> "Background"
    EditorTool.ANIMATION -> "Animation"
    EditorTool.STICKERS -> "Stickers"
}

/** The open tool's sheet, sliding up over the timeline area. */
@Composable
private fun ToolSheetHost(c: EditorController, modifier: Modifier) {
    AnimatedVisibility(
        visible = c.activeTool != null,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        var last by remember { mutableStateOf(c.activeTool) }
        c.activeTool?.let { last = it }
        val t = last
        if (t != null) ToolSheet(c, t)
    }
}

@Composable
private fun ToolSheet(c: EditorController, tool: EditorTool) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(Amiri.Surface)
            .border(1.dp, Amiri.Line, shape)
            // swallow touches so nothing under the sheet reacts
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Amiri.SurfaceTop))
        }
        com.amiri.cut.ui.common.SheetHeader(tool.title()) { c.activeTool = null }
        ToolPanel(c, tool, Modifier.weight(1f))
    }
}

@Composable
private fun ContextToolbar(c: EditorController) {
    val p = c.project ?: return
    val view = LocalView.current
    val sel = c.selectedClip()
    val track = sel?.let { p.trackOfClip(it.id) }
    val asset = sel?.let { p.asset(it.assetId) }
    val sticker = sel?.name?.startsWith("Sticker") == true
    val items: List<ToolItem> = when {
        sel == null -> listOf(
            ToolItem("Edit", Icons.Outlined.ContentCut) { c.selectMainAtPlayhead() },
            ToolItem("Audio", Icons.Outlined.MusicNote) { c.activeTool = EditorTool.AUDIO },
            ToolItem("Text", Icons.Outlined.TextFields) { c.activeTool = EditorTool.TEXT },
            ToolItem("Stickers", Icons.Outlined.EmojiEmotions) { c.activeTool = EditorTool.STICKERS },
            ToolItem("Overlay", Icons.Outlined.PictureInPictureAlt) { c.openOverlayPicker() },
            ToolItem("Effects", Icons.Outlined.AutoFixHigh) { c.activeTool = EditorTool.EFFECTS },
            ToolItem("Filters", Icons.Outlined.FilterVintage) { c.activeTool = EditorTool.FILTERS },
            ToolItem("Adjust", Icons.Outlined.Tune) { c.activeTool = EditorTool.COLOR },
            ToolItem("Animation", Icons.Outlined.Animation) { c.selectMainAtPlayhead(); c.activeTool = EditorTool.ANIMATION },
            ToolItem("Ratio", Icons.Outlined.AspectRatio) { c.activeTool = EditorTool.RATIO },
            ToolItem("Background", Icons.Outlined.Wallpaper) { c.activeTool = EditorTool.BACKGROUND },
            ToolItem("Shapes", Icons.Outlined.Category) { c.activeTool = EditorTool.SHAPE },
            ToolItem("Remove BG", Icons.Outlined.PersonOutline) { c.selectMainAtPlayhead(); c.activeTool = EditorTool.ROTO },
            ToolItem("Marker", Icons.Outlined.BookmarkAdd) { c.addMarker(); Haptics.tick(view); c.toast = Toast("Marker added") },
        )
        sticker -> listOf(
            ToolItem("Animation", Icons.Outlined.Animation) { c.activeTool = EditorTool.ANIMATION },
            ToolItem("Stickers", Icons.Outlined.EmojiEmotions) { c.activeTool = EditorTool.STICKERS },
            ToolItem("Transform", Icons.Outlined.CropRotate) { c.activeTool = EditorTool.TRANSFORM },
            ToolItem("Split", Icons.Outlined.ContentCut) { c.split(); Haptics.confirm(view) },
            ToolItem("Duplicate", Icons.Outlined.ContentCopy) { c.duplicateSelected() },
            ToolItem("Keyframes", Icons.Outlined.Timeline) { c.activeTool = EditorTool.KEYS },
            ToolItem("Track", Icons.Outlined.TrackChanges) { c.activeTool = EditorTool.TRACK },
            lockItem(c, sel.locked),
            ToolItem("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.deleteSelected() },
        )
        sel.kind == ClipKind.TEXT -> listOf(
            ToolItem("Edit", Icons.Outlined.TextFields) { c.activeTool = EditorTool.TEXT },
            ToolItem("Animation", Icons.Outlined.Animation) { c.activeTool = EditorTool.ANIMATION },
            ToolItem("Split", Icons.Outlined.ContentCut) { c.split(); Haptics.confirm(view) },
            ToolItem("Duplicate", Icons.Outlined.ContentCopy) { c.duplicateSelected() },
            ToolItem("Transform", Icons.Outlined.CropRotate) { c.activeTool = EditorTool.TRANSFORM },
            ToolItem("Effects", Icons.Outlined.AutoFixHigh) { c.activeTool = EditorTool.EFFECTS },
            ToolItem("Keyframes", Icons.Outlined.Timeline) { c.activeTool = EditorTool.KEYS },
            ToolItem("Track", Icons.Outlined.TrackChanges) { c.activeTool = EditorTool.TRACK },
            lockItem(c, sel.locked),
            ToolItem("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.deleteSelected() },
        )
        sel.kind == ClipKind.SHAPE -> listOf(
            ToolItem("Edit", Icons.Outlined.Category) { c.activeTool = EditorTool.SHAPE },
            ToolItem("Animation", Icons.Outlined.Animation) { c.activeTool = EditorTool.ANIMATION },
            ToolItem("Split", Icons.Outlined.ContentCut) { c.split(); Haptics.confirm(view) },
            ToolItem("Duplicate", Icons.Outlined.ContentCopy) { c.duplicateSelected() },
            ToolItem("Transform", Icons.Outlined.CropRotate) { c.activeTool = EditorTool.TRANSFORM },
            ToolItem("Effects", Icons.Outlined.AutoFixHigh) { c.activeTool = EditorTool.EFFECTS },
            ToolItem("Keyframes", Icons.Outlined.Timeline) { c.activeTool = EditorTool.KEYS },
            lockItem(c, sel.locked),
            ToolItem("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.deleteSelected() },
        )
        sel.kind == ClipKind.ADJUSTMENT -> listOf(
            ToolItem("Adjust", Icons.Outlined.Tune) { c.activeTool = EditorTool.COLOR },
            ToolItem("Filters", Icons.Outlined.FilterVintage) { c.activeTool = EditorTool.FILTERS },
            ToolItem("Effects", Icons.Outlined.AutoFixHigh) { c.activeTool = EditorTool.EFFECTS },
            ToolItem("Split", Icons.Outlined.ContentCut) { c.split(); Haptics.confirm(view) },
            ToolItem("Duplicate", Icons.Outlined.ContentCopy) { c.duplicateSelected() },
            ToolItem("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.deleteSelected() },
        )
        track?.kind == TrackKind.AUDIO -> listOf(
            ToolItem("Volume", Icons.AutoMirrored.Outlined.VolumeUp) { c.activeTool = EditorTool.AUDIO },
            ToolItem("Split", Icons.Outlined.ContentCut) { c.split(); Haptics.confirm(view) },
            ToolItem("Speed", Icons.Outlined.Speed) { c.activeTool = EditorTool.SPEED },
            ToolItem("Beats", Icons.Outlined.GraphicEq) { c.activeTool = EditorTool.AUDIO },
            ToolItem("Duplicate", Icons.Outlined.ContentCopy) { c.duplicateSelected() },
            ToolItem("Replace", Icons.Outlined.SwapHoriz) { c.openReplacePicker() },
            lockItem(c, sel.locked),
            ToolItem("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.deleteSelected() },
        )
        else -> buildList {
            add(ToolItem("Split", Icons.Outlined.ContentCut) { c.split(); Haptics.confirm(view) })
            add(ToolItem("Speed", Icons.Outlined.Speed) { c.activeTool = EditorTool.SPEED })
            add(ToolItem("Animation", Icons.Outlined.Animation) { c.activeTool = EditorTool.ANIMATION })
            add(ToolItem("Enhance", Icons.Outlined.AutoAwesome, accent = true) { c.enhanceSelected(); Haptics.confirm(view) })
            if (asset?.hasAudio == true) add(ToolItem("Volume", Icons.AutoMirrored.Outlined.VolumeUp) { c.activeTool = EditorTool.AUDIO })
            add(ToolItem("Transform", Icons.Outlined.CropRotate) { c.activeTool = EditorTool.TRANSFORM })
            add(ToolItem("Remove BG", Icons.Outlined.PersonOutline) { c.activeTool = EditorTool.ROTO })
            add(ToolItem("Filters", Icons.Outlined.FilterVintage) { c.activeTool = EditorTool.FILTERS })
            add(ToolItem("Adjust", Icons.Outlined.Tune) { c.activeTool = EditorTool.COLOR })
            add(ToolItem("Effects", Icons.Outlined.AutoFixHigh) { c.activeTool = EditorTool.EFFECTS })
            add(ToolItem("Transition", Icons.Outlined.Compare) { c.activeTool = EditorTool.TRANSITION })
            add(ToolItem("Mask", Icons.Outlined.Layers) { c.activeTool = EditorTool.MASK })
            add(ToolItem("Keyframes", Icons.Outlined.Timeline) { c.activeTool = EditorTool.KEYS })
            add(ToolItem("Track", Icons.Outlined.TrackChanges) { c.activeTool = EditorTool.TRACK })
            if (asset?.type == MediaType.VIDEO) add(ToolItem("Stabilize", Icons.Outlined.CenterFocusWeak) { c.activeTool = EditorTool.STABILIZE })
            add(ToolItem("Duplicate", Icons.Outlined.ContentCopy) { c.duplicateSelected() })
            if (asset?.type == MediaType.VIDEO) add(ToolItem("Reverse", Icons.Outlined.Replay) { c.reverseSelected() })
            if (asset?.type == MediaType.VIDEO) add(ToolItem("Freeze", Icons.Outlined.AcUnit) { c.freezeFrame() })
            if (asset?.hasAudio == true && !sel.muted) add(ToolItem("Extract audio", Icons.Outlined.Audiotrack) { c.detachAudio(sel.id) })
            add(ToolItem("Replace", Icons.Outlined.SwapHoriz) { c.openReplacePicker() })
            add(ToolItem("Brush", Icons.Outlined.Brush) { c.activeTool = EditorTool.ROTO })
            add(lockItem(c, sel.locked))
            add(ToolItem("Ripple delete", Icons.Outlined.DeleteSweep, danger = true) { c.rippleDeleteSelected() })
            add(ToolItem("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.deleteSelected() })
        }
    }
    Column(Modifier.fillMaxWidth().background(Amiri.Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Amiri.Line))
        Row(
            Modifier.fillMaxWidth().height(75.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sel != null) {
                Box(
                    Modifier.padding(start = 8.dp, end = 2.dp).size(width = 40.dp, height = 54.dp).clip(RoundedCornerShape(14.dp)).background(Amiri.SurfaceHigh)
                        .clickable { Haptics.select(view); c.select(null) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ChevronLeft, "Back", tint = Amiri.TextPrimary) }
            }
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { t -> ToolButton(t) }
                }
                // fade hint that the row scrolls
                Box(
                    Modifier.align(Alignment.CenterEnd).width(28.dp).height(75.dp)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Amiri.Bg))),
                )
            }
        }
    }
}

private fun lockItem(c: EditorController, locked: Boolean) =
    ToolItem(if (locked) "Unlock" else "Lock", if (locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock) { c.toggleClipLock() }

@Composable
private fun ToolButton(t: ToolItem) {
    val view = LocalView.current
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val tint = when {
        t.danger -> Amiri.Danger
        t.accent -> Amiri.accentInk(accent)
        else -> Amiri.TextPrimary
    }
    Column(
        Modifier.width(64.dp).pressScale(source, 0.88f)
            .clickable(interactionSource = source, indication = null, enabled = t.enabled) { Haptics.select(view); t.onClick() }
            .padding(vertical = 8.dp)
            .alpha(if (t.enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            t.label, color = if (t.danger) Amiri.Danger else if (t.accent) Amiri.accentInk(accent) else Amiri.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp),
        )
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
    AnimatedVisibility(visible = t != null, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut(), modifier = modifier) {
        Text(
            t?.text ?: "",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xF0232327))
                .border(1.dp, Amiri.Line, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A small grab bar: drag up to make the timeline taller, down to make it smaller. */
@Composable
private fun TimelineResizeHandle(c: EditorController) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(14.dp).background(Amiri.Bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { c.saveTimelineHeight() }) { ch, dy ->
                    ch.consume()
                    c.timelineHeightDp = (c.timelineHeightDp - with(density) { dy.toDp().value }).coerceIn(110f, 560f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 30.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Amiri.SurfaceTop))
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
    clip.roto?.takeIf { it.keys.isNotEmpty() }?.let { items += Triple("roto", it.enabled, "Cut-out") }
    clip.stab?.let { items += Triple("stab", it.enabled, "Stabilize") }
    if (clip.masks.isNotEmpty()) items += Triple("masks", true, "Masks ${clip.masks.size}")
    if (items.isEmpty()) return
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AutoFixHigh, null, tint = Amiri.TextTertiary, modifier = Modifier.size(15.dp))
        items.forEach { (key, on, label) ->
            Row(
                Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(if (on) Amiri.SurfaceHighest else Amiri.SurfaceHigh)
                    .border(1.dp, if (on) Amiri.accentInk(accent).copy(alpha = 0.5f) else Amiri.Line, RoundedCornerShape(50))
                    .padding(start = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(24.dp).clickable { c.toggleLayerItem(clip.id, key) }.padding(7.dp)
                        .clip(CircleShape).background(if (on) Amiri.accentInk(accent) else Amiri.TextTertiary.copy(alpha = 0.45f)),
                )
                Text(
                    label, color = if (on) Amiri.TextPrimary else Amiri.TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { c.openLayerItem(clip.id, key) }.padding(horizontal = 4.dp, vertical = 5.dp),
                )
                if (key != "masks") Icon(
                    Icons.Outlined.Close, "Delete $label", tint = Amiri.TextSecondary,
                    modifier = Modifier.size(24.dp).clickable { c.deleteLayerItem(clip.id, key) }.padding(6.dp),
                )
            }
        }
    }
}

// ───────────────────────────── sheets ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(c: EditorController, trackedClipId: String) {
    ModalBottomSheet(onDismissRequest = { c.attachPrompt = null }, containerColor = Amiri.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Tracking done ✓", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                "What should follow the tracked point? It is placed exactly on the point at the playhead and moves with it.",
                color = Amiri.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachChoice(Icons.Outlined.TextFields, "Text", Modifier.weight(1f)) { c.attachNewToTrack(trackedClipId, EditorController.AttachKind.TEXT) }
                AttachChoice(Icons.Outlined.Category, "Shape", Modifier.weight(1f)) { c.attachNewToTrack(trackedClipId, EditorController.AttachKind.SHAPE) }
                AttachChoice(Icons.Outlined.PermMedia, "Overlay", Modifier.weight(1f)) { c.openAttachPicker(trackedClipId) }
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
        modifier.clip(RoundedCornerShape(18.dp)).background(Amiri.SurfaceHigh).border(1.dp, Amiri.Line, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Amiri.accentInk(LocalAccent.current), modifier = Modifier.size(28.dp))
        Text(label, color = Amiri.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun KeyMenuSheet(c: EditorController, clipId: String, local: Long) {
    val p = c.project ?: return
    val clip = p.clip(clipId) ?: run { c.keyMenu = null; return }
    ActionSheet(
        title = "Keyframe · ${clip.name}",
        subtitle = FrameTime.timecode(clip.startUs + local, p.settings.fps) + "  ·  double-tap a keyframe to toggle Easy Ease",
        onDismiss = { c.keyMenu = null },
        actions = listOf(
            SheetAction("Easy Ease (both sides)", Icons.Outlined.Timeline) { c.easyEaseAt(clipId, local) },
            SheetAction("Ease in (arrive slowly)", Icons.Outlined.ChevronLeft) { c.easeArriveAt(clipId, local) },
            SheetAction("Ease out (leave slowly)", Icons.Outlined.ChevronRight) { c.easeLeaveAt(clipId, local) },
            SheetAction("Linear", Icons.Outlined.Timeline) { c.linearAt(clipId, local) },
            SheetAction("Hold (jump to next key)", Icons.Outlined.Pause) { c.setInterpAllAt(clipId, local, com.amiri.cut.core.model.Interp.HOLD) },
            SheetAction("Curve editor", Icons.Outlined.Tune) { c.select(clipId); c.activeTool = EditorTool.KEYS },
            SheetAction("Copy keyframes here", Icons.Outlined.ContentCopy) { c.select(clipId); c.copyKeyframesAt() },
            SheetAction("Paste keyframes at playhead", Icons.Outlined.ContentCopy) { c.select(clipId); c.pasteKeyframesAt() },
            SheetAction("Delete keyframe", Icons.Outlined.DeleteOutline, danger = true) { c.deleteKeysAt(clipId, local) },
        ),
    )
}

@Composable
private fun ClipMenuSheet(c: EditorController, clipId: String) {
    val p = c.project ?: return
    val clip = p.clip(clipId) ?: run { c.clipMenuFor = null; return }
    val view = LocalView.current
    val actions = buildList {
        add(SheetAction("Split at playhead", Icons.Outlined.ContentCut) { c.select(clip.id); c.split(); Haptics.confirm(view) })
        add(SheetAction("Duplicate", Icons.Outlined.ContentCopy) { c.select(clip.id); c.duplicateSelected() })
        add(SheetAction(if (clip.locked) "Unlock clip" else "Lock clip", if (clip.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock) { c.toggleClipLock(clip.id) })
        if (p.trackOfClip(clip.id)?.acceptsVisual == true && p.asset(clip.assetId)?.hasAudio == true && !clip.muted)
            add(SheetAction("Extract audio", Icons.Outlined.Audiotrack) { c.detachAudio(clip.id) })
        add(SheetAction("Copy effects & style", Icons.Outlined.ContentCopy) { c.copyAttributes(clip.id) })
        if (c.clipboard != null) {
            add(SheetAction("Paste effects", Icons.Outlined.AutoFixHigh) { c.pasteAttributes("effects", clip.id) })
            add(SheetAction("Paste position / scale / rotation", Icons.Outlined.CropRotate) { c.pasteAttributes("transform", clip.id) })
            add(SheetAction("Paste everything", Icons.Outlined.ContentCopy) { c.pasteAttributes("all", clip.id) })
        }
        if (clip.kind != ClipKind.TEXT && p.markers.any { it.label == "♪" })
            add(SheetAction("Cut on beats", Icons.Outlined.GraphicEq) { c.select(clip.id); c.cutOnBeats() })
        add(SheetAction("Ripple delete", Icons.Outlined.DeleteSweep, danger = true) { c.select(clip.id); c.rippleDeleteSelected() })
        add(SheetAction("Delete", Icons.Outlined.DeleteOutline, danger = true) { c.select(clip.id); c.deleteSelected() })
    }
    ActionSheet(
        title = clip.name,
        subtitle = "${FrameTime.timecode(clip.startUs, p.settings.fps)} → ${FrameTime.timecode(clip.endUs, p.settings.fps)} · " +
            "${FrameTime.toFrame(clip.durationUs, p.settings.fps)} frames",
        onDismiss = { c.clipMenuFor = null },
        actions = actions,
    )
}

@Composable
private fun TrackMenuSheet(c: EditorController, trackId: String) {
    val p = c.project ?: return
    val t = p.track(trackId) ?: run { c.trackMenuFor = null; return }
    val actions = buildList {
        add(SheetAction(if (t.locked) "Unlock track" else "Lock track", if (t.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock) { c.toggleTrackLock(t.id) })
        if (t.kind != TrackKind.AUDIO) add(SheetAction(if (t.hidden) "Show track" else "Hide track", Icons.Outlined.Layers) { c.toggleTrackHidden(t.id) })
        if (t.kind != TrackKind.TEXT) add(SheetAction(if (t.muted) "Unmute track" else "Mute track", Icons.AutoMirrored.Outlined.VolumeUp) { c.toggleTrackMuted(t.id) })
        add(SheetAction("Add a new track like this", Icons.Outlined.Layers) { c.addTrack(t.kind) })
        add(SheetAction("Delete track", Icons.Outlined.DeleteOutline, danger = true) { c.removeTrack(t.id) })
    }
    ActionSheet(
        title = "Track · ${t.name}",
        subtitle = "${t.clips.size} clip(s)" + (if (t.locked) " · locked" else "") + (if (t.hidden) " · hidden" else "") + (if (t.muted) " · muted" else ""),
        onDismiss = { c.trackMenuFor = null },
        actions = actions,
    )
}
