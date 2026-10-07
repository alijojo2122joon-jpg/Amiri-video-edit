package com.amiri.cut.ui.picker

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.amiri.cut.ui.common.Badge
import com.amiri.cut.ui.common.GlassButton
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import com.amiri.cut.ui.theme.onAccent

/** What the gallery is opened for. */
data class PickRequest(
    val kinds: Set<GalleryItem.Kind> = setOf(GalleryItem.Kind.VIDEO, GalleryItem.Kind.IMAGE),
    val multiple: Boolean = true,
    val confirm: String = "Add",
    val title: String? = null,
)

private enum class Tab(val label: String) { ALL("All"), VIDEOS("Videos"), PHOTOS("Photos"), AUDIO("Music & audio") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPickerScreen(
    request: PickRequest,
    onCancel: () -> Unit,
    onPicked: (List<Uri>) -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val accent = LocalAccent.current
    val wantsVisual = GalleryItem.Kind.VIDEO in request.kinds || GalleryItem.Kind.IMAGE in request.kinds
    val wantsAudio = GalleryItem.Kind.AUDIO in request.kinds
    val tabs = buildList {
        if (wantsVisual) {
            if (GalleryItem.Kind.VIDEO in request.kinds && GalleryItem.Kind.IMAGE in request.kinds) add(Tab.ALL)
            if (GalleryItem.Kind.VIDEO in request.kinds) add(Tab.VIDEOS)
            if (GalleryItem.Kind.IMAGE in request.kinds) add(Tab.PHOTOS)
        }
        if (wantsAudio) add(Tab.AUDIO)
    }
    var tab by remember { mutableStateOf(tabs.first()) }
    var refresh by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(MediaLibrary.visualAccess(ctx)) }
    var audioOk by remember { mutableStateOf(MediaLibrary.audioAccess(ctx)) }
    var askedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val a = MediaLibrary.visualAccess(ctx); val b = MediaLibrary.audioAccess(ctx)
        if (a != access || b != audioOk) { access = a; audioOk = b; refresh++ }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        access = MediaLibrary.visualAccess(ctx); audioOk = MediaLibrary.audioAccess(ctx); refresh++
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(if (request.multiple) uris else uris.take(1))
    }
    fun openFiles() {
        val types = buildList {
            if (GalleryItem.Kind.VIDEO in request.kinds) add("video/*")
            if (GalleryItem.Kind.IMAGE in request.kinds) add("image/*")
            if (wantsAudio) add("audio/*")
        }
        filesLauncher.launch(types.toTypedArray())
    }
    fun askPermission() = permLauncher.launch(MediaLibrary.visualPermissions(wantsAudio))
    fun openAppSettings() = ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))

    // Ask straight away the first time, like any gallery app.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if ((wantsVisual && access == GalleryAccess.NONE) || (!wantsVisual && wantsAudio && !audioOk)) askPermission()
    }

    val visual by produceState<List<GalleryItem>?>(null, access, refresh) {
        value = if (wantsVisual && access != GalleryAccess.NONE) MediaLibrary.loadVisual(ctx) else emptyList()
    }
    val audio by produceState<List<GalleryItem>?>(null, audioOk, refresh) {
        value = if (wantsAudio && audioOk) MediaLibrary.loadAudio(ctx) else emptyList()
    }
    var album by remember { mutableStateOf(MediaLibrary.ALL) }
    var albumSheet by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<GalleryItem>() }
    var preview by remember { mutableStateOf<GalleryItem?>(null) }

    fun toggle(item: GalleryItem) {
        Haptics.select(view)
        if (selection.any { it.key == item.key }) selection.removeAll { it.key == item.key }
        else {
            if (!request.multiple) selection.clear()
            selection.add(item)
        }
    }

    BackHandler { if (preview != null) preview = null else onCancel() }

    val shown = remember(visual, tab, album) {
        val base = visual.orEmpty().filter { album == MediaLibrary.ALL || it.bucketId == album }
        when (tab) {
            Tab.VIDEOS -> base.filter { it.kind == GalleryItem.Kind.VIDEO }
            Tab.PHOTOS -> base.filter { it.kind == GalleryItem.Kind.IMAGE }
            Tab.ALL -> base.filter { it.kind in request.kinds }
            Tab.AUDIO -> emptyList()
        }
    }
    val albums = remember(visual) { MediaLibrary.albums(visual.orEmpty().filter { it.kind in request.kinds }) }
    val albumName = albums.firstOrNull { it.id == album }?.name ?: "Recents"

    Box(Modifier.fillMaxSize().background(Amiri.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── top bar ──
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { onCancel() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, "Close", tint = Amiri.TextPrimary)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (tab != Tab.AUDIO && wantsVisual && access != GalleryAccess.NONE) {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Amiri.SurfaceHigh).border(1.dp, Amiri.Line, RoundedCornerShape(50)).clickable { albumSheet = true }
                                .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(albumName, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Amiri.TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    } else Text(request.title ?: "Music & audio", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { openFiles() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = Amiri.TextSecondary, modifier = Modifier.size(18.dp))
                    Text("Files", color = Amiri.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
            }
            // ── tabs ──
            if (tabs.size > 1) {
                com.amiri.cut.ui.common.UnderlineTabs(tabs, tab, { it.label }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { tab = it }
            }
            if (request.title != null && tab != Tab.AUDIO) {
                Text(request.title, color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp))
            }
            // ── partial access banner ──
            if (tab != Tab.AUDIO && access == GalleryAccess.PARTIAL) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Amiri.SurfaceHigh)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Showing only the items you allowed.", color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("Allow more", color = accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { askPermission() }.padding(4.dp))
                }
            }

            // ── content ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    tab == Tab.AUDIO && !audioOk -> PermissionPrompt(
                        icon = Icons.Outlined.MusicNote, title = "Your music",
                        body = "Allow access to audio files to add songs and sound effects. Everything stays on this phone — Amiri Cut has no internet access.",
                        primary = if (askedOnce) "Open settings" else "Allow access",
                        onPrimary = { if (askedOnce) openAppSettings() else askPermission() }, onFiles = ::openFiles,
                    )
                    tab != Tab.AUDIO && access == GalleryAccess.NONE -> PermissionPrompt(
                        icon = Icons.Outlined.PhotoLibrary, title = "Your videos & photos",
                        body = "Allow access to see your gallery here. Nothing is copied or uploaded — Amiri Cut works fully offline.",
                        primary = if (askedOnce) "Open settings" else "Allow access",
                        onPrimary = { if (askedOnce) openAppSettings() else askPermission() }, onFiles = ::openFiles,
                    )
                    tab == Tab.AUDIO -> {
                        val list = audio
                        if (list != null && list.isEmpty()) EmptyNote("No audio files on this phone yet.")
                        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                            items(list.orEmpty(), key = { it.key }) { a ->
                                val idx = selection.indexOfFirst { it.key == a.key }
                                AudioRow(a, idx, accent) { toggle(a) }
                            }
                        }
                    }
                    else -> {
                        if (visual != null && shown.isEmpty()) EmptyNote(if (tab == Tab.PHOTOS) "No photos here." else if (tab == Tab.VIDEOS) "No videos here." else "Nothing here yet.")
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 6.dp, bottom = 150.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.key }) { item ->
                                val idx = selection.indexOfFirst { it.key == item.key }
                                GalleryTile(item, idx, accent, multiple = request.multiple, onClick = { toggle(item) }, onLong = { Haptics.heavy(view); preview = item })
                            }
                        }
                    }
                }
            }
        }

        // ── selection tray ──
        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Amiri.Surface)
                    .border(1.dp, Amiri.Line, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .navigationBarsPadding().padding(top = 14.dp, bottom = 14.dp),
            ) {
                val totalMs = selection.sumOf { if (it.kind == GalleryItem.Kind.IMAGE) 3000L else it.durationMs }
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selection.size == 1) "1 selected" else "${selection.size} selected",
                        color = Amiri.TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    )
                    Text("Total " + MediaLibrary.durationLabel(totalMs), color = Amiri.TextSecondary, style = com.amiri.cut.ui.theme.MonoStyle, fontSize = 12.sp)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(selection.toList(), key = { "s" + it.key }) { s ->
                            Box(Modifier.size(52.dp)) {
                                ThumbImage(s, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.72f))
                                        .clickable { selection.removeAll { it.key == s.key } },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Outlined.Close, "Remove", tint = Color.White, modifier = Modifier.size(12.dp)) }
                            }
                        }
                    }
                    com.amiri.cut.ui.common.PrimaryButton(
                        if (request.multiple && selection.size > 1) "${request.confirm} (${selection.size})" else request.confirm,
                        Modifier.padding(end = 18.dp), height = 48.dp,
                    ) { onPicked(selection.map { it.uri }) }
                }
            }
        }

        // ── full-screen preview ──
        preview?.let { item ->
            PreviewOverlay(
                item = item,
                selectedIndex = selection.indexOfFirst { it.key == item.key },
                onToggle = { toggle(item) },
                onClose = { preview = null },
            )
        }
    }

    if (albumSheet) AlbumSheet(albums, album, onPick = { album = it; albumSheet = false }, onDismiss = { albumSheet = false })
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Amiri.TextTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ThumbImage(item: GalleryItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val img by produceState(MediaLibrary.cached(item.key), item.key) {
        if (value == null) value = MediaLibrary.thumbnail(ctx, item)
    }
    Box(modifier.background(Amiri.SurfaceHigh)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryTile(item: GalleryItem, index: Int, accent: Color, multiple: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val selected = index >= 0
    val s by animateFloatAsState(if (selected) 0.92f else 1f, label = "tile")
    val ink = Amiri.accentInk(accent)
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        ThumbImage(item, Modifier.fillMaxSize().scale(s).clip(RoundedCornerShape(if (selected) 12.dp else 4.dp)))
        if (selected) Box(Modifier.fillMaxSize().scale(s).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.22f)).border(2.5.dp, ink, RoundedCornerShape(12.dp)))
        if (item.kind == GalleryItem.Kind.VIDEO) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxSize(0.38f).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Text(
                MediaLibrary.durationLabel(item.durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                style = com.amiri.cut.ui.theme.MonoStyle, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 5.dp),
            )
        }
        // selection circle
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                .background(if (selected) Amiri.accentBrush(accent) else androidx.compose.ui.graphics.SolidColor(Color.Black.copy(alpha = 0.28f)))
                .border(1.5.dp, if (selected) Color.White else Color.White.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                if (multiple) Text("${index + 1}", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                else Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun AudioRow(a: GalleryItem, index: Int, accent: Color, onClick: () -> Unit) {
    val selected = index >= 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) accent else Amiri.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.MusicNote, null, tint = if (selected) onAccent(accent) else Amiri.ClipAudio) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(a.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(a.artist, MediaLibrary.durationLabel(a.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            )
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (selected) accent else Color.Transparent)
                .border(1.5.dp, if (selected) accent else Amiri.TextTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Outlined.Check, null, tint = onAccent(accent), modifier = Modifier.size(15.dp)) }
    }
}

@Composable
private fun PermissionPrompt(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    onFiles: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(40.dp))
        }
        Text(title, color = Amiri.TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
        Text(body, color = Amiri.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        GlassButton(primary, Modifier.fillMaxWidth().padding(top = 26.dp), primary = true, onClick = onPrimary)
        GlassButton("Browse files instead", Modifier.fillMaxWidth().padding(top = 10.dp), onClick = onFiles)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSheet(albums: List<Album>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Amiri.Surface) {
        Text("Albums", color = Amiri.TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            items(albums, key = { it.id }) { al ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(al.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cover = al.cover
                    if (cover != null) ThumbImage(cover, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                    else Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Amiri.SurfaceHigh))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(al.name, color = Amiri.TextPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${al.count}", color = Amiri.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (al.id == current) Icon(Icons.Outlined.Check, null, tint = LocalAccent.current)
                }
            }
        }
    }
}

@Composable
private fun PreviewOverlay(item: GalleryItem, selectedIndex: Int, onToggle: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val big by produceState<ImageBitmap?>(MediaLibrary.cached(item.key), item.key) {
        if (item.kind == GalleryItem.Kind.IMAGE) value = MediaLibrary.preview(ctx, item) ?: value
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        if (item.kind == GalleryItem.Kind.VIDEO) {
            AndroidView(
                factory = { c ->
                    android.widget.VideoView(c).apply {
                        setVideoURI(item.uri)
                        setOnPreparedListener { mp -> mp.isLooping = true; start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize().padding(vertical = 70.dp),
            )
        } else {
            big?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(vertical = 70.dp)) }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Close preview", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (item.kind == GalleryItem.Kind.VIDEO) Badge(MediaLibrary.durationLabel(item.durationMs), Modifier.padding(end = 8.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(if (selectedIndex >= 0) Amiri.SurfaceHighest else accent)
                .clickable(onClick = onToggle).padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (selectedIndex >= 0) "Selected ✓  ·  tap to remove" else "Select",
                color = if (selectedIndex >= 0) Amiri.TextPrimary else onAccent(accent), style = MaterialTheme.typography.labelLarge, fontSize = 15.sp,
            )
        }
    }
}
