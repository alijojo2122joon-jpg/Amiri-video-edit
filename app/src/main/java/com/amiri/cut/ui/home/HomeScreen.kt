package com.amiri.cut.ui.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.GraphicEq
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import com.amiri.cut.ui.common.Badge
import com.amiri.cut.ui.common.ConfirmDialog
import com.amiri.cut.ui.common.SheetAction
import com.amiri.cut.ui.common.TextInputDialog
import com.amiri.cut.ui.common.paw
import com.amiri.cut.ui.editor.QuickStart
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.onAccent
import com.amiri.cut.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class QuickTool(val label: String, val icon: ImageVector, val tint: Color, val start: QuickStart?)

private val QUICK_TOOLS = listOf(
    QuickTool("Remove\nbackground", Icons.Outlined.PersonOutline, Color(0xFF7C8CFF), QuickStart.REMOVE_BG),
    QuickTool("Smart\nroto brush", Icons.Outlined.Brush, Color(0xFFFF7A9C), QuickStart.SMART_ROTO),
    QuickTool("Clean\nvoice", Icons.Outlined.GraphicEq, Color(0xFF2FD3A4), QuickStart.CLEAN_VOICE),
    QuickTool("Stabilize\nvideo", Icons.Outlined.CenterFocusWeak, Color(0xFFFFC14D), QuickStart.STABILIZE),
    QuickTool("Beat\nsync", Icons.Outlined.MusicNote, Color(0xFFB38CFF), QuickStart.BEAT_SYNC),
    QuickTool("Custom\ncanvas", Icons.Outlined.AspectRatio, Color(0xFF8FA3B8), null),
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

    com.amiri.cut.ui.common.PurrWhileVisible()
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painterResource(R.drawable.amiri_logo_mark), "Amiri Cut logo",
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Text(
                        "Amiri Cut", color = Amiri.TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp, modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Amiri.SurfaceHigh).clickable { Haptics.select(view); onSettings() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Settings, "Settings", tint = Amiri.TextPrimary, modifier = Modifier.size(21.dp)) }
                }
            }
            item(span = { GridItemSpan(2) }) { HeroNewProject(accent, onNewProject) }
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text("Quick tools", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(QUICK_TOOLS, key = { it.label }) { t ->
                            QuickToolCard(t) { Haptics.select(view); if (t.start != null) onQuickStart(t.start) else onCustomCanvas() }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Projects", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    val n = projects?.size ?: 0
                    if (n > 0) Badge("$n", Modifier.padding(start = 8.dp), bg = Amiri.SurfaceHigh, fg = Amiri.TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Restore, null, tint = Amiri.TextSecondary, modifier = Modifier.size(17.dp))
                        Text("Restore", color = Amiri.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
            val list = projects
            when {
                list == null -> Unit
                list.isEmpty() -> item(span = { GridItemSpan(2) }) { EmptyProjects(accent) }
                else -> items(list, key = { it.id }) { p ->
                    ProjectCard(
                        p = p,
                        onOpen = { if (p.hasRecovery) recoverTarget = p else onOpen(p.id, false) },
                        onMenu = { Haptics.heavy(view); menuTarget = p },
                    )
                }
            }
            item(span = { GridItemSpan(2) }) {
                Text(
                    "Everything stays on this phone · no internet, no account",
                    color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 18.dp),
                )
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

@Composable
private fun HeroNewProject(accent: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val view = LocalView.current
    val fg = onAccent(accent)
    val deep = lerp(accent, Color.Black, 0.32f)
    Box(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(
            Modifier.fillMaxWidth().height(172.dp).pressScale(source, 0.97f)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.12f), accent, deep), start = Offset.Zero, end = Offset(900f, 700f)))
                .clickable(interactionSource = source, indication = null) { Haptics.confirm(view); onClick() },
        ) {
            // Decoration: film perforations and a few paw prints.
            Canvas(Modifier.fillMaxSize()) {
                val hole = size.height / 11f
                for (i in 0 until 12) {
                    val y = i * size.height / 10f
                    drawRoundRect(fg.copy(alpha = 0.07f), Offset(size.width - hole * 1.6f, y), androidx.compose.ui.geometry.Size(hole, hole * 0.62f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                }
                drawLine(fg.copy(alpha = 0.06f), Offset(size.width - hole * 2.2f, 0f), Offset(size.width - hole * 2.2f, size.height), 2f)
                paw(Offset(size.width * 0.58f, size.height * 0.30f), size.height * 0.22f, fg.copy(alpha = 0.07f))
                paw(Offset(size.width * 0.70f, size.height * 0.72f), size.height * 0.16f, fg.copy(alpha = 0.06f))
                drawCircle(Color.White.copy(alpha = 0.10f), size.height * 0.9f, Offset(size.width * 0.05f, -size.height * 0.35f), style = Stroke(size.height * 0.08f))
            }
            Box(
                Modifier.align(Alignment.TopStart).padding(20.dp).size(54.dp).clip(CircleShape).background(fg),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, null, tint = accent, modifier = Modifier.size(32.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text("New project", color = fg, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                Text("Videos, photos and music · edit like a pro", color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, fontSize = 13.sp)
            }
        }
        // a cat peeking over the card
        com.amiri.cut.ui.common.PeekingCat(
            Modifier.align(Alignment.TopEnd).padding(end = 64.dp).size(width = 54.dp, height = 22.dp),
            Color(0xFF26262A), accent,
        )
    }
}

@Composable
private fun QuickToolCard(t: QuickTool, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.width(98.dp).pressScale(source, 0.94f).clip(RoundedCornerShape(20.dp)).background(Amiri.SurfaceHigh)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(t.tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(24.dp))
        }
        Text(
            t.label, color = Amiri.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 9.dp),
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
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(18.dp)).background(Amiri.SurfaceHigh)) {
            ProjectCover(p.thumbPath, p.width.toFloat() / p.height.coerceAtLeast(1))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Badge(FrameTime.shortDuration(p.durationUs), Modifier.align(Alignment.BottomEnd).padding(8.dp))
            if (p.hasRecovery) Badge("RECOVER", Modifier.align(Alignment.TopStart).padding(8.dp), bg = accent, fg = onAccent(accent))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.MoreHoriz, "Project options", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        Text(p.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        Text(
            DateUtils.getRelativeTimeSpanString(p.modifiedAt).toString() + " · ${p.height.coerceAtMost(p.width).let { if (it >= 2160) "4K" else "${it}p" }}",
            color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(start = 2.dp),
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
            Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(6.dp)).background(Amiri.SurfaceHighest))
            Icon(Icons.Outlined.Movie, null, tint = Amiri.TextTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EmptyProjects(accent: Color) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(24.dp)).background(Amiri.Surface).padding(vertical = 26.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.amiri.cut.ui.common.SleepingCat(Modifier.size(170.dp, 118.dp), Color(0xFF2C2C33), accent)
        Text("No projects yet", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Tap New project and pick videos or photos.\nThe cat will keep them safe on this phone.",
            color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
