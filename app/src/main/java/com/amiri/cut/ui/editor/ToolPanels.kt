package com.amiri.cut.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.amiri.cut.media.RotoBrushMode
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.glass
import com.amiri.cut.ui.theme.glassAccent

/** Contextual panel shown above the bottom toolbar for the active tool. */
@Composable
fun ToolPanel(c: EditorController, tool: EditorTool, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).glass(RoundedCornerShape(18.dp))
            .heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(10.dp),
    ) {
        Column {
            c.busy?.let { b -> BusyBar(c, b) }
            when (tool) {
                EditorTool.MEDIA -> MediaPanel(c)
                EditorTool.CUT -> CutPanel(c)
                EditorTool.ROTO -> RotoPanel(c)
                EditorTool.TRANSFORM -> TransformPanel(c)
                EditorTool.SPEED -> SpeedPanel(c)
                EditorTool.MASK -> MaskPanel(c)
                EditorTool.TRACK -> TrackPanel(c)
                EditorTool.TEXT -> TextPanel(c)
                EditorTool.COLOR -> ColorPanel(c)
                EditorTool.EFFECTS -> EffectsPanel(c)
                EditorTool.AUDIO -> AudioPanel(c)
                EditorTool.KEYS -> KeyframesPanel(c)
                EditorTool.SHAPE -> ShapePanel(c)
                EditorTool.TRANSITION -> TransitionPanel(c)
                EditorTool.FILTERS -> FiltersPanel(c)
                EditorTool.STABILIZE -> StabilizePanel(c)
            }
        }
    }
}

@Composable
internal fun PanelAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val view = LocalView.current
    Column(
        Modifier.width(66.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { Haptics.select(view); onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (enabled) Amiri.TextPrimary else Amiri.TextTertiary, modifier = Modifier.size(22.dp))
        Text(label, color = if (enabled) Amiri.TextSecondary else Amiri.TextTertiary, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CutPanel(c: EditorController) {
    val view = LocalView.current
    val sel = c.selectedClipId
    val clip = sel?.let { c.project?.clip(it) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        PanelAction(Icons.Outlined.ContentCut, "Split") { c.split(); Haptics.confirm(view) }
        PanelAction(Icons.Outlined.DeleteOutline, "Delete", enabled = sel != null) { c.deleteSelected() }
        PanelAction(Icons.Outlined.DeleteSweep, "Ripple del", enabled = sel != null) { c.rippleDeleteSelected() }
        PanelAction(Icons.Outlined.ContentCopy, "Duplicate", enabled = sel != null) { c.duplicateSelected() }
        PanelAction(if (clip?.locked == true) Icons.Outlined.LockOpen else Icons.Outlined.Lock, if (clip?.locked == true) "Unlock" else "Lock clip", enabled = sel != null) { c.toggleClipLock() }
        PanelAction(Icons.Outlined.AcUnit, "Freeze") { c.freezeFrame() }
        PanelAction(Icons.Outlined.SwapVert, "Reverse", enabled = sel != null) { c.reverseSelected() }
        PanelAction(Icons.Outlined.Bookmark, "Marker") { c.addMarker(); Haptics.tick(view) }
        PanelAction(Icons.Outlined.BookmarkRemove, "Del marker") { if (!c.removeMarkerAtPlayhead()) c.toast = Toast("No marker at the playhead") }
    }
}

@Composable
private fun MediaPanel(c: EditorController) {
    val p = c.project ?: return
    val accent = LocalAccent.current
    var replaceTarget by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        c.importUris(uris, Placement.AT_PLAYHEAD)
    }
    val replacer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = replaceTarget
        replaceTarget = null
        if (uri != null && id != null) c.replaceMedia(id, uri)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Media · ${p.assets.size}", color = Amiri.TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (c.importing > 0) Text("Importing ${c.importing}…", color = accent, fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
            Row(
                Modifier.glassAccent(accent, RoundedCornerShape(10.dp))
                    .clickable { importer.launch(arrayOf("video/*", "image/*", "audio/*")) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = accent, modifier = Modifier.size(16.dp))
                Text("Import", color = Amiri.TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (p.assets.isEmpty()) {
            Text(
                "Import videos, photos or audio. Files are read in place — nothing is copied or uploaded.",
                color = Amiri.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp),
            )
        } else {
            Text("Tap to add at the playhead", color = Amiri.TextTertiary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.assets.sortedByDescending { it.importedAt }, key = { it.id }) { a ->
                    AssetTile(
                        c, a, missing = a.id in c.missingAssets,
                        onAdd = { c.addAssetAtPlayhead(a.id) },
                        onReplace = {
                            replaceTarget = a.id
                            replacer.launch(if (a.type == MediaType.AUDIO) arrayOf("audio/*") else arrayOf("video/*", "image/*"))
                        },
                        onRemove = { c.removeAsset(a.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetTile(
    c: EditorController,
    a: MediaAsset,
    missing: Boolean,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    val view = LocalView.current
    val thumbsVersion = c.app.thumbnails.version.collectAsState().value
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(84.dp)) {
        Box(
            Modifier.size(84.dp, 60.dp).clip(RoundedCornerShape(10.dp)).background(Amiri.SurfaceHigh)
                .clickable {
                    if (missing) onReplace() else { Haptics.confirm(view); onAdd() }
                },
            contentAlignment = Alignment.Center,
        ) {
            val img = remember(thumbsVersion, a.id) { c.app.thumbnails.strip(a.id)?.frameAt(0) }
            if (img != null && !missing) {
                Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    when {
                        missing -> Icons.Outlined.WarningAmber
                        a.type == MediaType.AUDIO -> Icons.Outlined.AudioFile
                        a.type == MediaType.IMAGE -> Icons.Outlined.Photo
                        else -> Icons.Outlined.Movie
                    },
                    null, tint = if (missing) Amiri.Danger else Amiri.TextSecondary,
                )
            }
            Box(Modifier.align(Alignment.TopEnd).size(24.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Text("⋯", color = Amiri.TextPrimary, fontSize = 14.sp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Add at playhead") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, enabled = !missing, onClick = { menu = false; onAdd() })
                    DropdownMenuItem(
                        text = { Text(if (missing) "Relink missing media" else "Replace media") },
                        leadingIcon = { Icon(if (missing) Icons.Outlined.Link else Icons.Outlined.SwapHoriz, null) },
                        onClick = { menu = false; onReplace() },
                    )
                    if (a.type == MediaType.VIDEO) DropdownMenuItem(
                        text = { Text(if (a.proxyUri != null) "Rebuild proxy" else "Build proxy (fast preview)") },
                        leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                        onClick = { menu = false; c.makeProxy(a.id) },
                    )
                    DropdownMenuItem(text = { Text("Remove from media") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; onRemove() })
                }
            }
            if (a.type != MediaType.IMAGE) {
                Text(
                    FrameTime.shortDuration(a.durationUs),
                    color = Amiri.TextPrimary, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            if (missing) "Missing · tap to relink" else a.name,
            color = if (missing) Amiri.Danger else Amiri.TextSecondary, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ───────────────────────────── Roto ─────────────────────────────

@Composable
private fun RotoPanel(c: EditorController) {
    val accent = LocalAccent.current
    val view = LocalView.current
    val pos by c.engine.position.collectAsState()
    val t = remember(c.project, pos, c.selectedClipId) { c.rotoTarget() }
    val progress = c.rotoProgress
    Column {
        if (t == null) {
            Text("Roto brush", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                "Move the playhead over a video or photo clip, then paint the part you want to keep. Everything you don't paint is removed.",
                color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }
        val roto = t.clip.roto
        val keyHere = c.rotoKeyHere(t)
        Row(Modifier.padding(bottom = 4.dp)) {
            PanelAction(androidx.compose.material.icons.Icons.Outlined.AutoAwesome, "✨ Auto cut-out (person)") { c.select(t.clip.id); c.autoCutout() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.clip.name, color = Amiri.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        roto == null || roto.keys.isEmpty() -> "No mask yet — paint the subject to keep it"
                        keyHere -> "${roto.keys.size} mask frame(s) · editing this frame"
                        else -> "${roto.keys.size} mask frame(s) · held from earlier frame"
                    } + if (roto?.enabled == false) " · OFF" else "",
                    color = Amiri.TextTertiary, fontSize = 10.sp,
                )
            }
            ToggleChip("Result", c.rotoShowResult) { c.rotoShowResult = !c.rotoShowResult }
        }

        if (progress != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress }, color = accent, trackColor = Amiri.SurfaceHigh,
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Text("Tracking ${(progress * 100).toInt()}%", color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
                Text("Cancel", color = Amiri.Danger, fontSize = 12.sp, modifier = Modifier.clickable { c.rotoCancel() }.padding(6.dp))
            }
            return@Column
        }

        // Brush modes
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RotoBrushMode.entries.forEach { m ->
                ToggleChip(m.label, c.rotoMode == m) { Haptics.select(view); c.rotoMode = m }
            }
        }
        // Sliders
        LabeledSlider("Brush", c.rotoBrush, 0.005f..0.30f, "${"%.1f".format(c.rotoBrush * 100)}%") { c.rotoBrush = it }
        if (c.rotoMode == RotoBrushMode.HAIR) {
            LabeledSlider("Detail", c.rotoHairRadius, 4f..30f, "${c.rotoHairRadius.toInt()} px") { c.rotoHairRadius = it }
            LabeledSlider("Edge", c.rotoHairContrast, 0.5f..3f, "${"%.1f".format(c.rotoHairContrast)}×") { c.rotoHairContrast = it }
            Text(
                "Paint over hair or fur along the edge of the mask: it separates strands from the background by color. Paint the solid body with Character first.",
                color = Amiri.TextTertiary, fontSize = 10.sp,
            )
        } else {
            LabeledSlider("Feather", c.rotoFeather, 0f..0.05f, "${"%.1f".format(c.rotoFeather * 100)}%") { c.rotoFeather = it }
        }
        Text(
            "One finger paints · two fingers zoom and move the view · ⤢ shows/hides the timeline",
            color = Amiri.TextTertiary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp),
        )
        // Actions
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PanelAction(Icons.Outlined.PlayArrow, "Propagate") { c.rotoPropagate() }
            PanelAction(Icons.Outlined.AutoFixHigh, "Refine edge") { c.rotoRefineEdge() }
            PanelAction(Icons.Outlined.InvertColors, if (roto?.invert == true) "Uninvert" else "Invert") { c.rotoToggleInvert() }
            PanelAction(Icons.Outlined.ChevronLeft, "Prev key") { c.rotoJumpKey(false) }
            PanelAction(Icons.Outlined.ChevronRight, "Next key") { c.rotoJumpKey(true) }
            PanelAction(Icons.Outlined.LayersClear, "Clear frame") { c.rotoClearFrame() }
            PanelAction(if (roto?.enabled == false) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (roto?.enabled == false) "Enable" else "Disable") { c.rotoToggleEnabled() }
            PanelAction(Icons.Outlined.DeleteOutline, "Remove") { c.rotoRemove() }
        }
    }
}

@Composable
internal fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .then(if (on) Modifier.glassAccent(accent, shape) else Modifier.glass(shape, strength = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (on) Amiri.TextPrimary else Amiri.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
        Text(label, color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(56.dp))
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Amiri.SurfaceHigh),
            modifier = Modifier.weight(1f),
        )
        Text(display, color = Amiri.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}
