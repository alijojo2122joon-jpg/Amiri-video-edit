package com.amiri.cut.ui.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.R
import com.amiri.cut.core.model.ProjectSummary
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.ui.common.ActionSheet
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.AppIconTile
import com.amiri.cut.ui.common.Badge
import com.amiri.cut.ui.common.ConfirmDialog
import com.amiri.cut.ui.common.DiscButton
import com.amiri.cut.ui.common.GlassButton
import com.amiri.cut.ui.common.SheetAction
import com.amiri.cut.ui.common.TextInputDialog
import com.amiri.cut.ui.editor.QuickStart
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.onAccent
import com.amiri.cut.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class QuickTool(val label: String, val icon: ImageVector, val colors: List<Color>, val start: QuickStart?)

private val QUICK_TOOLS = listOf(
    QuickTool("Remove\nbackground", Icons.Outlined.PersonOutline, listOf(Color(0xFF3B82F6), Color(0xFF22D3EE)), QuickStart.REMOVE_BG),
    QuickTool("Smart\nroto", Icons.Outlined.Brush, listOf(Color(0xFFEC4899), Color(0xFFF97316)), QuickStart.SMART_ROTO),
    QuickTool("Clean\nvoice", Icons.Outlined.GraphicEq, listOf(Color(0xFF10B981), Color(0xFF14B8A6)), QuickStart.CLEAN_VOICE),
    QuickTool("Stabilize", Icons.Outlined.CenterFocusWeak, listOf(Color(0xFFF59E0B), Color(0xFFEF4444)), QuickStart.STABILIZE),
    QuickTool("Beat\nsync", Icons.Outlined.MusicNote, listOf(Color(0xFF8B5CF6), Color(0xFF6366F1)), QuickStart.BEAT_SYNC),
    QuickTool("Animate\nclips", Icons.Outlined.Animation, listOf(Color(0xFFF43F5E), Color(0xFFA855F7)), QuickStart.ANIMATE),
    QuickTool("Stickers", Icons.Outlined.EmojiEmotions, listOf(Color(0xFFFBBF24), Color(0xFFF97316)), QuickStart.STICKERS),
    QuickTool("Custom\ncanvas", Icons.Outlined.AspectRatio, listOf(Color(0xFF64748B), Color(0xFF334155)), null),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    app: AmiriCutApp,
    onNewProject: () -> Unit,
    onQuickStart: (QuickStart) -> Unit,
    onCustomCanvas: () -> Unit,
    onOpen: (id: String, recover: Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val projects by produceState<List<ProjectSummary>?>(null, refresh) { value = app.projects.list() }

    var menuTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var recoverTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var backupTarget by remember { mutableStateOf<String?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { app.projects.importBackup(it) }
            }
            result.onSuccess { p ->
                Toast.makeText(context, "Restored “${p.name}”", Toast.LENGTH_SHORT).show()
                refresh++
            }.onFailure {
                Toast.makeText(context, "Not a valid Amiri Cut backup", Toast.LENGTH_LONG).show()
            }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = backupTarget
        backupTarget = null
        if (uri != null && id != null) scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { app.projects.exportBackup(id, it) } }
                .onSuccess { Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    fun restore() = restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))

    com.amiri.cut.ui.common.PurrWhileVisible()
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── header ──
            item(span = { GridItemSpan(2) }) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painterResource(R.drawable.amiri_logo_mark), "Amiri Cut logo",
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Amiri.Line, RoundedCornerShape(12.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Amiri Cut", color = Amiri.TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                        Text("Pro video editor · offline", color = Amiri.TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    DiscButton(Icons.Outlined.Settings, "Settings", size = 42) { onSettings() }
                }
            }
            // ── new project ──
            item(span = { GridItemSpan(2) }) { HeroNewProject(accent) { Haptics.confirm(view); onNewProject() } }

            // ── quick tools ──
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text("Quick tools", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Pick a video and jump straight into the tool", color = Amiri.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                    QUICK_TOOLS.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            row.forEach { t ->
                                QuickToolTile(t, Modifier.weight(1f)) {
                                    Haptics.select(view)
                                    if (t.start != null) onQuickStart(t.start) else onCustomCanvas()
                                }
                            }
                        }
                    }
                }
            }

            // ── projects ──
            item(span = { GridItemSpan(2) }) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Projects", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val n = projects?.size ?: 0
                    if (n > 0) Badge("$n", Modifier.padding(start = 8.dp), bg = Amiri.SurfaceHighest, fg = Amiri.TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { restore() }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Restore, null, tint = Amiri.TextSecondary, modifier = Modifier.size(17.dp))
                        Text("Restore", color = Amiri.TextSecondary, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
            val list = projects
            when {
                list == null -> Unit
                list.isEmpty() -> item(span = { GridItemSpan(2) }) { EmptyProjects(accent, onNew = onNewProject, onRestore = ::restore) }
                else -> items(list, key = { it.id }) { p ->
                    ProjectCard(
                        p = p,
                        onOpen = { Haptics.select(view); if (p.hasRecovery) recoverTarget = p else onOpen(p.id, false) },
                        onMenu = { Haptics.heavy(view); menuTarget = p },
                    )
                }
            }
            item(span = { GridItemSpan(2) }) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = Amiri.TextTertiary, modifier = Modifier.size(13.dp))
                    Text(
                        "  Offline · your videos never leave this phone",
                        color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    menuTarget?.let { p ->
        ActionSheet(
            title = p.name,
            subtitle = "${p.width}×${p.height} · ${p.fps} fps · " + FrameTime.shortDuration(p.durationUs),
            onDismiss = { menuTarget = null },
            actions = listOf(
                SheetAction("Rename", Icons.Outlined.DriveFileRenameOutline) { renameTarget = p },
                SheetAction("Duplicate", Icons.Outlined.ContentCopy) { scope.launch { app.projects.duplicate(p.id); refresh++ } },
                SheetAction("Back up to a file", Icons.Outlined.SaveAlt) {
                    backupTarget = p.id
                    backupLauncher.launch(p.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".amiricut")
                },
                SheetAction("Delete", Icons.Outlined.DeleteOutline, danger = true) { deleteTarget = p },
            ),
        )
    }
    renameTarget?.let { p ->
        TextInputDialog("Rename project", p.name, onDismiss = { renameTarget = null }) { name ->
            scope.launch { app.projects.rename(p.id, name); renameTarget = null; refresh++ }
        }
    }
    deleteTarget?.let { p ->
        ConfirmDialog(
            title = "Delete “${p.name}”?",
            message = "The project is removed. Your original videos and photos are never touched.",
            confirm = "Delete", danger = true,
            onDismiss = { deleteTarget = null },
        ) {
            scope.launch { app.projects.delete(p.id); deleteTarget = null; refresh++ }
        }
    }
    recoverTarget?.let { p ->
        ConfirmDialog(
            title = "Recover project?",
            message = "Amiri Cut closed unexpectedly while “${p.name}” was open. An autosave newer than the last save was found.",
            confirm = "Recover", dismiss = "Discard autosave",
            onDismiss = { recoverTarget = null },
            onDismissAction = {
                recoverTarget = null
                scope.launch { app.projects.discardRecovery(p.id); onOpen(p.id, false) }
            },
        ) {
            recoverTarget = null
            onOpen(p.id, true)
        }
    }
}

/** The big "New project" card: deep violet panel, glowing plus, a fan of film frames. */
@Composable
private fun HeroNewProject(accent: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val accent2 = Amiri.accent2(accent)
    val inf = rememberInfiniteTransition(label = "hero")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(188.dp).pressScale(source, 0.975f)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B1530), Color(0xFF120F1E), Color(0xFF0C0B12)), start = Offset.Zero, end = Offset(1100f, 800f)))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(28.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // brand glow
            val g = Offset(size.width * (0.78f + drift * 0.06f), size.height * 0.28f)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f), Color.Transparent), center = g, radius = size.width * 0.55f), radius = size.width * 0.55f, center = g)
            val g2 = Offset(size.width * 0.98f, size.height * 1.05f)
            drawCircle(Brush.radialGradient(listOf(accent2.copy(alpha = 0.35f), Color.Transparent), center = g2, radius = size.width * 0.4f), radius = size.width * 0.4f, center = g2)
            // a fan of three film frames on the right
            val fw = size.height * 0.46f
            val fh = fw * 1.42f
            val cx = size.width * 0.80f
            val cy = size.height * 0.52f
            listOf(-14f to 0.55f, 0f to 0.8f, 12f to 1f).forEachIndexed { i, (ang, a) ->
                val dx = (i - 1) * fw * 0.42f
                rotate(ang + (i - 1) * drift * 2f, Offset(cx + dx, cy)) {
                    val tl = Offset(cx + dx - fw / 2, cy - fh / 2)
                    drawRoundRect(Color.Black.copy(alpha = 0.35f), tl + Offset(0f, 6f), Size(fw, fh), CornerRadius(fw * 0.14f))
                    drawRoundRect(
                        Brush.linearGradient(listOf(accent.copy(alpha = a), accent2.copy(alpha = a * 0.85f)), start = tl, end = tl + Offset(fw, fh)),
                        tl, Size(fw, fh), CornerRadius(fw * 0.14f),
                    )
                    drawRoundRect(Color.White.copy(alpha = 0.25f * a), tl, Size(fw, fh), CornerRadius(fw * 0.14f), style = Stroke(2f))
                    if (i == 2) {
                        // play glyph on the front frame
                        val pc = Offset(tl.x + fw / 2, tl.y + fh / 2)
                        val r = fw * 0.16f
                        val tri = Path().apply { moveTo(pc.x - r * 0.6f, pc.y - r); lineTo(pc.x + r, pc.y); lineTo(pc.x - r * 0.6f, pc.y + r); close() }
                        drawPath(tri, Color.White.copy(alpha = 0.92f))
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, null, tint = accent, modifier = Modifier.size(34.dp)) }
            Text("New project", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, modifier = Modifier.padding(top = 14.dp))
            Text("Videos, photos and music from your gallery", color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun QuickToolTile(t: QuickTool, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(source, 0.92f).clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIconTile(t.icon, t.colors, size = 54.dp)
        Text(
            t.label, color = Amiri.TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(p: ProjectSummary, onOpen: () -> Unit, onMenu: () -> Unit) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.pressScale(source, 0.97f).combinedClickable(interactionSource = source, indication = null, onClick = onOpen, onLongClick = onMenu),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(20.dp)).background(Amiri.SurfaceHigh)
                .border(1.dp, Amiri.Line, RoundedCornerShape(20.dp)),
        ) {
            ProjectCover(p.thumbPath, p.width.toFloat() / p.height.coerceAtLeast(1))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Badge(
                p.height.coerceAtMost(p.width).let { if (it >= 2160) "4K" else "${it}p" } + " · " + p.aspectLabel.ifBlank { "${p.width}:${p.height}" },
                Modifier.align(Alignment.TopStart).padding(8.dp),
            )
            Badge(FrameTime.shortDuration(p.durationUs), Modifier.align(Alignment.BottomEnd).padding(8.dp))
            if (p.hasRecovery) Badge("RECOVER", Modifier.align(Alignment.BottomStart).padding(8.dp), bg = accent, fg = onAccent(accent))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.MoreHoriz, "Project options", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        Text(p.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, start = 2.dp))
        Text(
            "Edited " + DateUtils.getRelativeTimeSpanString(p.modifiedAt).toString().replaceFirstChar { it.lowercase() },
            color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun ProjectCover(path: String?, aspect: Float) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    val b = bmp
    if (b != null) {
        Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val w = if (aspect >= 1f) 64f else 64f * aspect
            val h = if (aspect >= 1f) 64f / aspect else 64f
            Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(8.dp)).background(Amiri.SurfaceHighest))
            Icon(Icons.Outlined.Movie, null, tint = Amiri.TextTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EmptyProjects(accent: Color, onNew: () -> Unit, onRestore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Amiri.Surface)
            .border(1.dp, Amiri.Line, RoundedCornerShape(24.dp)).padding(vertical = 24.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.amiri.cut.ui.common.SleepingCat(Modifier.size(150.dp, 104.dp), Color(0xFF2A2A31), accent)
        Text("No projects yet", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Start a new project from your videos and photos.\nEverything you make stays on this phone.",
            color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Restore backup", icon = Icons.Outlined.Restore, onClick = onRestore)
            com.amiri.cut.ui.common.PrimaryButton("New project", icon = Icons.Outlined.Add, onClick = onNew)
        }
    }
}
