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
    Box(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).glass(RoundedCornerShape(18.dp)).padding(10.dp)) {
        when (tool) {
            EditorTool.MEDIA -> MediaPanel(c)
            EditorTool.CUT -> CutPanel(c)
            else -> NotYetPanel(tool)
        }
    }
}

@Composable
private fun PanelAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
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

/** Honest placeholder: the tool exists in the roadmap but is not implemented in this build. */
@Composable
private fun NotYetPanel(tool: EditorTool) {
    val what = when (tool) {
        EditorTool.TRANSFORM -> "Crop, rotate, flip, scale, position, anchor, opacity, blend mode."
        EditorTool.SPEED -> "Constant speed 0.25×–4×, custom curves, reverse, freeze frame."
        EditorTool.MASK -> "Rectangle, ellipse and pen masks with feather, expansion, invert and keyframes."
        EditorTool.TRACK -> "On-device position / scale / rotation tracking with proxy resolution."
        EditorTool.ROTO -> "Roto brush with add/subtract, propagation and refine edge."
        EditorTool.TEXT -> "Typography engine with Persian/Arabic RTL, custom TTF/OTF fonts."
        EditorTool.COLOR -> "Basic, HSL, curves, lift/gamma/gain, .CUBE LUTs, scopes, before/after."
        EditorTool.EFFECTS -> "GPU effects: glow, light sweep, light rays, light leaks, film, blur, motion blur, chroma key."
        EditorTool.AUDIO -> "Volume and fades with keyframes, speed, per-clip mute."
        else -> ""
    }
    Column(Modifier.fillMaxWidth().height(86.dp), verticalArrangement = Arrangement.Center) {
        Text("${tool.label} — arrives in Stage ${tool.stage}", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(what, color = Amiri.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text("Not implemented in this build — no fake controls.", color = Amiri.TextTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
