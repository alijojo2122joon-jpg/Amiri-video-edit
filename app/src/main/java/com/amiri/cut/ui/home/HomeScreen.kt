package com.amiri.cut.ui.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.R
import androidx.compose.ui.res.painterResource
import com.amiri.cut.core.model.ProjectSummary
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.ui.common.AmbientBackground
import com.amiri.cut.ui.common.ConfirmDialog
import com.amiri.cut.ui.common.GlassButton
import com.amiri.cut.ui.common.IconAction
import com.amiri.cut.ui.common.SectionLabel
import com.amiri.cut.ui.common.TextInputDialog
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    app: AmiriCutApp,
    onNewProject: () -> Unit,
    onOpen: (id: String, recover: Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val projects by produceState<List<ProjectSummary>?>(null, refresh) { value = app.projects.list() }

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
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.amiri_logo_mark), "Amiri Cut logo",
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Amiri Cut", color = Amiri.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Text("Offline editor · made with 🐾", color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    IconAction(Icons.Outlined.Settings, "Settings", onClick = onSettings)
                }
            }
            item {
                // A cat peeking over the buttons.
                Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
                    com.amiri.cut.ui.common.PeekingCat(Modifier.size(width = 56.dp, height = 30.dp), Color(0xFF2A2A30), LocalAccent.current)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton("New Project", Modifier.weight(1f), icon = Icons.Outlined.Add, primary = true, onClick = onNewProject)
                    GlassButton("Open Project", Modifier.weight(1f), icon = Icons.Outlined.FolderOpen) {
                        restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }
            }
            item { SectionLabel("Recent projects", Modifier.padding(top = 16.dp)) }

            val list = projects
            when {
                list == null -> Unit
                list.isEmpty() -> item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        com.amiri.cut.ui.common.SleepingCat(Modifier.size(180.dp, 130.dp), Color(0xFF2C2C33), LocalAccent.current)
                        Text(
                            "The cat is napping… no projects yet.\nCreate one — everything stays on this device.",
                            color = Amiri.TextSecondary, style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                else -> items(list, key = { it.id }) { p ->
                    ProjectRow(
                        p = p,
                        onOpen = { if (p.hasRecovery) recoverTarget = p else onOpen(p.id, false) },
                        onRename = { renameTarget = p },
                        onDelete = { deleteTarget = p },
                        onDuplicate = { scope.launch { app.projects.duplicate(p.id); refresh++ } },
                        onBackup = {
                            backupTarget = p.id
                            backupLauncher.launch(p.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".amiricut")
                        },
                    )
                }
            }
        }
    }

    renameTarget?.let { p ->
        TextInputDialog("Rename project", p.name, onDismiss = { renameTarget = null }) { name ->
            scope.launch { app.projects.rename(p.id, name); renameTarget = null; refresh++ }
        }
    }
    deleteTarget?.let { p ->
        ConfirmDialog(
            title = "Delete “${p.name}”?",
            message = "The project file is removed. Your original media files are never touched.",
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    p: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onBackup: () -> Unit,
) {
    val view = LocalView.current
    val accent = LocalAccent.current
    var menu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> { Haptics.confirm(view); onRename() }
                SwipeToDismissBoxValue.EndToStart -> { Haptics.confirm(view); onDelete() }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the dialog does the work
        },
    )
    val shape = RoundedCornerShape(20.dp)
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val toDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().clip(shape).background(if (toDelete) Amiri.Danger.copy(alpha = 0.18f) else accent.copy(alpha = 0.14f))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toDelete) Arrangement.End else Arrangement.Start,
            ) {
                Icon(
                    if (toDelete) Icons.Outlined.DeleteOutline else Icons.Outlined.DriveFileRenameOutline, null,
                    tint = if (toDelete) Amiri.Danger else accent,
                )
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().glass(shape)
                .combinedClickable(onClick = onOpen, onLongClick = { Haptics.heavy(view); menu = true })
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectThumb(p.thumbPath, p.width.toFloat() / p.height)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(p.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.width}×${p.height} · ${p.fps} fps · ${p.aspectLabel}",
                    color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Edited " + DateUtils.getRelativeTimeSpanString(p.modifiedAt) + " · " + FrameTime.shortDuration(p.durationUs),
                        color = Amiri.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    )
                    if (p.hasRecovery) {
                        Text(
                            "  RECOVER", color = accent, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Box {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Backup to file…") }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) }, onClick = { menu = false; onBackup() })
                    DropdownMenuItem(text = { Text("Delete", color = Amiri.Danger) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Amiri.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ProjectThumb(path: String?, aspect: Float) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(width = 76.dp, height = 76.dp).clip(shape).background(Amiri.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Aspect hint frame
            val w = if (aspect >= 1f) 48f else 48f * aspect
            val h = if (aspect >= 1f) 48f / aspect else 48f
            Box(Modifier.size(w.dp, h.dp).glass(RoundedCornerShape(4.dp), strength = 0.6f))
            Icon(Icons.Outlined.Movie, null, tint = Amiri.TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}
