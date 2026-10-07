package com.amiri.cut.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.core.history.History
import com.amiri.cut.core.model.MediaAsset
import com.amiri.cut.core.model.MediaType
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.core.timeline.TimelineOps
import com.amiri.cut.engine.PreviewEngine
import com.amiri.cut.media.BitmapLoader
import com.amiri.cut.media.MediaProbe
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.graphics.Bitmap as AndroidBitmap
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.Roto
import com.amiri.cut.core.model.RotoKey
import com.amiri.cut.core.model.Track
import com.amiri.cut.media.RotoBrushMode
import com.amiri.cut.media.RotoPainter
import com.amiri.cut.media.RotoPropagator

enum class Placement { APPEND_TO_MAIN, AT_PLAYHEAD, OVERLAY, BIN_ONLY }

/** One-tap flows from the Home screen: import, then jump straight into a tool. */
enum class QuickStart { REMOVE_BG, SMART_ROTO, CLEAN_VOICE, STABILIZE, BEAT_SYNC, ANIMATE, STICKERS }

enum class EditorTool(val label: String, val stage: Int) {
    MEDIA("Media", 1),
    CUT("Cut", 1),
    FILTERS("Filters", 6),
    TRANSITION("Transitions", 2),
    TRANSFORM("Transform", 2),
    KEYS("Keyframes", 3),
    SPEED("Speed", 2),
    MASK("Mask", 5),
    TRACK("Track", 8),
    STABILIZE("Stabilize", 8),
    ROTO("Roto", 9),
    TEXT("Text", 4),
    SHAPE("Shape", 4),
    COLOR("Color", 6),
    EFFECTS("Effects", 7),
    AUDIO("Audio", 10),
    RATIO("Ratio", 1),
    BACKGROUND("Background", 1),
    ANIMATION("Animation", 2),
    STICKERS("Stickers", 4),
    ;
    /** Every tool is implemented. */
    val available: Boolean get() = true
}

data class Toast(val text: String, val id: Long = System.nanoTime())

/**
 * Editor state holder. Owns the open project, its undo history, autosave and the
 * preview engine. All project mutations go through [commit] so every edit is
 * undoable and autosaved.
 */
class EditorController(
    val app: AmiriCutApp,
    val projectId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val engine = PreviewEngine(app)

    /** Measures text layers for on-screen gizmos (no GL needed). */
    val textMeasurer = com.amiri.cut.render.TextRenderer(app.fonts)

    /** Eyedropper: when set, the next tap on the preview picks a color for this callback. */
    var pickColorFor by mutableStateOf<((Float, Float, Float) -> Unit)?>(null)

    var project by mutableStateOf<Project?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    private val history = History(limit = 100)
    var historyVersion by mutableIntStateOf(0)
        private set
    val canUndo: Boolean get() { historyVersion; return history.canUndo }
    val canRedo: Boolean get() { historyVersion; return history.canRedo }

    var selectedClipId by mutableStateOf<String?>(null)
        private set
    var clipMenuFor by mutableStateOf<String?>(null)
    var missingAssets by mutableStateOf<Set<String>>(emptySet())
        private set
    var importing by mutableIntStateOf(0)
        private set
    var toast by mutableStateOf<Toast?>(null)
    var activeTool by mutableStateOf<EditorTool?>(null)

    /** Timeline zoom in pixels per second. */
    var pps by mutableFloatStateOf(90f)

    // Preview overlays
    var showGrid by mutableStateOf(false)
    var showCenter by mutableStateOf(false)
    var showActionSafe by mutableStateOf(false)
    var showTitleSafe by mutableStateOf(false)
    var showPlatformZones by mutableStateOf(false)

    private var autosaveJob: Job? = null
    private var dirty = false
    private var closed = false
    private var closing = false
    val isClosing: Boolean get() = closing

    init {
        engine.useProxies = app.settings.proxyMode
        // Periodic autosave safety net (every 10 s while there are unsaved changes).
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (dirty) autosaveNow()
            }
        }
    }

    // ───────────────────────── lifecycle ─────────────────────────

    suspend fun load(recover: Boolean) {
        val p = (if (recover) app.projects.loadAutosave(projectId) else null) ?: app.projects.load(projectId)
        if (p == null) {
            loadError = "This project could not be opened."
            return
        }
        app.projects.markOpen(projectId)
        setProjectInternal(p)
        engine.seekTo(p.playheadUs)
        p.assets.forEach(::requestCaches)
        refreshMissing()
        if (recover) {
            dirty = true
            toast = Toast("Project recovered from autosave")
        }
    }

    suspend fun refreshMissing() {
        val p = project ?: return
        missingAssets = withContext(Dispatchers.IO) {
            p.assets.filterNot { MediaProbe.isReachable(app, it.uri) }.map { it.id }.toSet()
        }
        if (missingAssets.isNotEmpty()) toast = Toast("${missingAssets.size} media file(s) missing — relink in Media")
    }

    /** Saves the project explicitly, writes the cover and releases players. Safe to call once. */
    suspend fun saveAndClose() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            engine.pause()
            val p = project
            if (p != null) {
                val final = p.copy(playheadUs = engine.position.value)
                app.projects.save(final)
                writeCover(final)
            }
            app.projects.markClosed(projectId)
            engine.release()
        }
    }

    /** Saves, releases everything, then calls [onDone] on the main thread. Idempotent. */
    fun close(onDone: () -> Unit = {}) {
        if (closing) return
        closing = true
        app.appScope.launch {
            saveAndClose()
            scope.cancel()
            onDone()
        }
    }

    suspend fun saveNow() {
        val p = project ?: return
        app.projects.save(p.copy(playheadUs = engine.position.value))
        dirty = false
        writeCover(p)
        toast = Toast("Saved")
    }

    private suspend fun autosaveNow() {
        val p = project ?: return
        app.projects.autosave(p.copy(playheadUs = engine.position.value))
        dirty = false
    }

    private fun scheduleAutosave() {
        dirty = true
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(1_500)
            autosaveNow()
        }
    }

    private suspend fun writeCover(p: Project) {
        withContext(Dispatchers.IO) { writeCoverBlocking(p) }
    }

    private suspend fun writeCoverBlocking(p: Project) {
        val first = TimelineOps.topVisualClipAt(p, 0)?.second
            ?: p.tracks.filter { it.acceptsVisual }.flatMap { it.clips }.minByOrNull { it.startUs }
            ?: return
        val asset = p.asset(first.assetId) ?: return
        val bmp: Bitmap? = when (asset.type) {
            MediaType.VIDEO -> BitmapLoader.videoFrame(app, asset, first.sourceInUs, 320)
            MediaType.IMAGE -> BitmapLoader.decodeImage(app, asset, 320)
            MediaType.AUDIO -> null
        }
        if (bmp != null) app.projects.writeThumb(p.id, bmp)
    }

    // ───────────────────────── history ─────────────────────────

    private fun setProjectInternal(p: Project) {
        project = p
        engine.setProject(p)
        if (selectedClipId != null && p.clip(selectedClipId!!) == null) selectedClipId = null
    }

    /** Applies an edit as one undoable step. */
    fun commit(label: String, newProject: Project?) {
        val cur = project ?: return
        if (newProject == null) {
            toast = Toast("Not possible here (locked or no room)")
            return
        }
        if (newProject == cur) return
        history.record(cur, label)
        historyVersion++
        setProjectInternal(newProject.copy(modifiedAt = System.currentTimeMillis()))
        scheduleAutosave()
    }

    fun undo() {
        val cur = project ?: return
        val e = history.undo(cur) ?: return
        historyVersion++
        setProjectInternal(e.project)
        toast = Toast("Undo: ${e.label}")
        scheduleAutosave()
    }

    fun redo() {
        val cur = project ?: return
        val e = history.redo(cur) ?: return
        historyVersion++
        setProjectInternal(e.project)
        toast = Toast("Redo: ${e.label}")
        scheduleAutosave()
    }

    // ───────────────────────── media ─────────────────────────

    private fun requestCaches(a: MediaAsset) {
        app.thumbnails.request(a)
        app.waveforms.request(a)
    }

    fun importUris(uris: List<Uri>, placement: Placement) {
        if (uris.isEmpty()) return
        scope.launch { importNow(uris, placement) }
    }

    /** Imports and places media; returns the assets that could be read. */
    suspend fun importNow(uris: List<Uri>, placement: Placement): List<MediaAsset> {
        if (uris.isEmpty()) return emptyList()
        importing += uris.size
        val assets = ArrayList<MediaAsset>()
        var failed = 0
        for (u in uris) {
            MediaProbe.persistPermission(app.contentResolver, u)
            val a = MediaProbe.probe(app, u)
            if (a == null) failed++ else assets += a
            importing--
        }
        val base = project ?: return assets
        var p = base
        var cursor = engine.position.value
        var lastClip: String? = null
        for (a in assets) {
            p = when (placement) {
                Placement.APPEND_TO_MAIN -> TimelineOps.appendToMain(p, a).let { (np, cl) -> lastClip = cl.id; np }
                Placement.AT_PLAYHEAD -> {
                    val (np, clip) = TimelineOps.placeAsset(p, a, cursor)
                    cursor = clip.endUs
                    lastClip = clip.id
                    np
                }
                Placement.OVERLAY -> {
                    val r = if (a.type == MediaType.AUDIO) TimelineOps.placeAsset(p, a, cursor)
                    else TimelineOps.placeOverlay(p, a, cursor, if (a.isStill) 3_000_000L else 3_600_000_000L, 0.55f) ?: TimelineOps.placeAsset(p, a, cursor)
                    lastClip = r.second.id
                    r.first
                }
                Placement.BIN_ONLY -> TimelineOps.addAsset(p, a)
            }
            requestCaches(a)
        }
        if (assets.isNotEmpty()) commit(if (assets.size == 1) "Import ${assets[0].name}" else "Import ${assets.size} files", p)
        if (placement == Placement.OVERLAY && lastClip != null) selectedClipId = lastClip
        toast = when {
            failed > 0 && assets.isEmpty() -> Toast("Couldn't read the selected file(s)")
            failed > 0 -> Toast("Imported ${assets.size}, skipped $failed unreadable")
            else -> Toast(if (assets.size == 1) "Added 1 item" else "Added ${assets.size} items")
        }
        return assets
    }

    /** Runs a Home-screen quick tool on the first clip of the project. */
    fun runQuickStart(q: QuickStart) {
        val p = project ?: return
        val first = p.tracks.filter { it.kind == TrackKind.VIDEO }.asReversed().firstNotNullOfOrNull { t -> t.clips.firstOrNull() }
            ?: p.tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull() } ?: return
        select(first.id)
        engine.pause()
        engine.seekTo(first.startUs + minOf(first.durationUs / 3, 1_000_000L))
        when (q) {
            QuickStart.REMOVE_BG -> { activeTool = EditorTool.ROTO; autoCutout() }
            QuickStart.SMART_ROTO -> { rotoMode = RotoBrushMode.SMART; activeTool = EditorTool.ROTO; toast = Toast("Scribble on the subject — it snaps to the edges") }
            QuickStart.CLEAN_VOICE -> { activeTool = EditorTool.AUDIO; isolateVoice(1f, false) }
            QuickStart.STABILIZE -> { activeTool = EditorTool.STABILIZE; stabilize(com.amiri.cut.core.model.StabMode.ADVANCED, 0.6f, 0f) }
            QuickStart.BEAT_SYNC -> { activeTool = EditorTool.AUDIO; toast = Toast("Add music, then tap Find beats") }
            QuickStart.ANIMATE -> { activeTool = EditorTool.ANIMATION; toast = Toast("Pick an In, Out or Combo animation") }
            QuickStart.STICKERS -> { select(null); activeTool = EditorTool.STICKERS }
        }
    }

    fun addAssetAtPlayhead(assetId: String) {
        val p = project ?: return
        val a = p.asset(assetId) ?: return
        val (np, clip) = TimelineOps.placeAsset(p, a, engine.position.value)
        commit("Add ${a.name}", np)
        selectedClipId = clip.id
    }

    /** Replace Media / Relink Missing Media: same category (visual ↔ visual, audio ↔ audio). */
    fun replaceMedia(assetId: String, uri: Uri) {
        scope.launch {
            val p = project ?: return@launch
            val old = p.asset(assetId) ?: return@launch
            MediaProbe.persistPermission(app.contentResolver, uri)
            val probed = MediaProbe.probe(app, uri)
            if (probed == null) { toast = Toast("Couldn't read that file"); return@launch }
            val oldVisual = old.type != MediaType.AUDIO
            val newVisual = probed.type != MediaType.AUDIO
            if (oldVisual != newVisual) { toast = Toast("Pick a ${if (oldVisual) "video or photo" else "audio"} file"); return@launch }
            app.thumbnails.invalidate(assetId)
            app.waveforms.invalidate(assetId)
            val np = TimelineOps.replaceAssetSource(p, assetId, probed)
            commit("Replace ${old.name}", np)
            np.asset(assetId)?.let(::requestCaches)
            missingAssets = missingAssets - assetId
            toast = Toast("Media replaced")
        }
    }

    fun removeAsset(assetId: String) {
        val p = project ?: return
        val np = TimelineOps.removeAsset(p, assetId)
        if (np == null) toast = Toast("In use on the timeline — delete its clips first")
        else commit("Remove from media", np)
    }

    // ───────────────────────── selection & clip ops ─────────────────────────

    fun select(clipId: String?) {
        selectedClipId = clipId
    }

    fun split() {
        val p = project ?: return
        val pos = engine.position.value
        val sel = selectedClipId?.let { p.clip(it) }
        if (sel != null && sel.contains(pos)) {
            val r = TimelineOps.split(p, sel.id, pos)
            if (r == null) { toast = Toast("Can't split here"); return }
            commit("Split", r.first)
        } else {
            val np = TimelineOps.splitAll(p, pos)
            if (np == null) { toast = Toast("Nothing to split under the playhead"); return }
            commit("Split", np)
        }
    }

    fun deleteSelected() {
        val id = selectedClipId ?: run { toast = Toast("Select a clip first"); return }
        commit("Delete", TimelineOps.delete(project ?: return, id))
        selectedClipId = null
    }

    fun rippleDeleteSelected() {
        val id = selectedClipId ?: run { toast = Toast("Select a clip first"); return }
        commit("Ripple delete", TimelineOps.rippleDelete(project ?: return, id))
        selectedClipId = null
    }

    fun duplicateSelected() {
        val id = selectedClipId ?: run { toast = Toast("Select a clip first"); return }
        val r = TimelineOps.duplicate(project ?: return, id)
        commit("Duplicate", r?.first)
        r?.second?.let { selectedClipId = it.id }
    }

    fun toggleClipLock(clipId: String? = selectedClipId) {
        val p = project ?: return
        val c = clipId?.let { p.clip(it) } ?: return
        commit(if (c.locked) "Unlock clip" else "Lock clip", TimelineOps.setClipLocked(p, c.id, !c.locked))
    }

    fun commitClipEdit(label: String, updated: com.amiri.cut.core.model.Clip) {
        commit(label, TimelineOps.replaceClip(project ?: return, updated))
    }

    fun commitMove(newProject: Project) = commit("Move clip", newProject)

    // ───────────────────────── markers & navigation ─────────────────────────

    fun addMarker(atUs: Long = engine.position.value) {
        val p = project ?: return
        commit("Add marker", TimelineOps.addMarker(p, atUs))
    }

    /** Removes the marker within ±2 frames of the playhead, if any. */
    fun removeMarkerAtPlayhead(): Boolean {
        val p = project ?: return false
        val tol = FrameTime.fromFrame(2, p.settings.fps)
        val m = p.markers.minByOrNull { kotlin.math.abs(it.timeUs - engine.position.value) } ?: return false
        if (kotlin.math.abs(m.timeUs - engine.position.value) > tol) return false
        commit("Remove marker", TimelineOps.removeMarker(p, m.id))
        return true
    }

    fun jumpPrevEdit() {
        val p = project ?: return
        val pos = engine.position.value
        val t = TimelineOps.editPoints(p).lastOrNull { it < pos - 1 } ?: 0L
        engine.pause(); engine.seekTo(t)
    }

    fun jumpNextEdit() {
        val p = project ?: return
        val pos = engine.position.value
        val t = TimelineOps.editPoints(p).firstOrNull { it > pos + 1 } ?: p.durationUs
        engine.pause(); engine.seekTo(t)
    }

    // ───────────────────────── tracks ─────────────────────────

    fun toggleTrackLock(trackId: String) {
        val t = project?.track(trackId) ?: return
        commit(if (t.locked) "Unlock track" else "Lock track", TimelineOps.setTrackLocked(project!!, trackId, !t.locked))
    }

    fun toggleTrackHidden(trackId: String) {
        val t = project?.track(trackId) ?: return
        commit(if (t.hidden) "Show track" else "Hide track", TimelineOps.setTrackHidden(project!!, trackId, !t.hidden))
    }

    fun toggleTrackMuted(trackId: String) {
        val t = project?.track(trackId) ?: return
        commit(if (t.muted) "Unmute track" else "Mute track", TimelineOps.setTrackMuted(project!!, trackId, !t.muted))
    }

    fun addTrack(kind: TrackKind) {
        commit("Add track", TimelineOps.addTrack(project ?: return, kind))
    }

    fun removeTrack(trackId: String) {
        val np = TimelineOps.removeTrack(project ?: return, trackId)
        if (np == null) toast = Toast("Locked tracks and the last video track can't be removed")
        else {
            if (project?.track(trackId)?.clips?.any { it.id == selectedClipId } == true) selectedClipId = null
            commit("Remove track", np)
            toast = Toast("Track removed · Undo to bring it back")
        }
    }

    // ───────────────────────── redesign: gallery picker, ratio, background ─────────────────────────

    /** An open in-editor gallery request and what to do with the result. */
    data class EditorPick(
        val request: com.amiri.cut.ui.picker.PickRequest,
        val placement: Placement? = null,
        val replaceAssetId: String? = null,
        val attachTo: String? = null,
        val sound: Boolean = false,
    )

    var picker by mutableStateOf<EditorPick?>(null)
    var trackMenuFor by mutableStateOf<String?>(null)
    var proTrackHeaders by mutableStateOf(false)
    var openExport by mutableStateOf(false)

    private val visualKinds = setOf(com.amiri.cut.ui.picker.GalleryItem.Kind.VIDEO, com.amiri.cut.ui.picker.GalleryItem.Kind.IMAGE)

    fun openAddMedia(placement: Placement = Placement.APPEND_TO_MAIN) {
        engine.pause()
        picker = EditorPick(com.amiri.cut.ui.picker.PickRequest(visualKinds, multiple = true, confirm = "Add"), placement)
    }

    fun openOverlayPicker() {
        engine.pause()
        picker = EditorPick(com.amiri.cut.ui.picker.PickRequest(visualKinds, multiple = true, confirm = "Add overlay"), Placement.OVERLAY)
    }

    fun openMusicPicker() {
        engine.pause()
        picker = EditorPick(
            com.amiri.cut.ui.picker.PickRequest(setOf(com.amiri.cut.ui.picker.GalleryItem.Kind.AUDIO), multiple = false, confirm = "Use", title = "Music & audio"),
            sound = true,
        )
    }

    fun openReplacePicker() {
        val sel = selectedClip() ?: return
        val a = project?.asset(sel.assetId) ?: return
        engine.pause()
        val kinds = if (a.type == MediaType.AUDIO) setOf(com.amiri.cut.ui.picker.GalleryItem.Kind.AUDIO) else visualKinds
        picker = EditorPick(com.amiri.cut.ui.picker.PickRequest(kinds, multiple = false, confirm = "Replace", title = "Replace “${sel.name}”"), replaceAssetId = a.id)
    }

    fun openAttachPicker(trackedClipId: String) {
        picker = EditorPick(com.amiri.cut.ui.picker.PickRequest(visualKinds, multiple = false, confirm = "Attach"), attachTo = trackedClipId)
    }

    fun onPicked(pick: EditorPick, uris: List<Uri>) {
        if (uris.isEmpty()) return
        when {
            pick.replaceAssetId != null -> replaceMedia(pick.replaceAssetId, uris.first())
            pick.attachTo != null -> attachOverlayFromUri(pick.attachTo, uris.first())
            pick.sound -> importSound(uris.first(), "Music")
            else -> importUris(uris, pick.placement ?: Placement.APPEND_TO_MAIN)
        }
    }

    /** "Edit" with nothing selected: pick the picture under the playhead (CapCut behaviour). */
    fun selectMainAtPlayhead() {
        val p = project ?: return
        val pos = engine.position.value
        val hit = TimelineOps.topVisualClipAt(p, pos)?.second
            ?: p.tracks.filter { it.kind == TrackKind.VIDEO }.asReversed().firstNotNullOfOrNull { t -> t.clips.minByOrNull { kotlin.math.abs(it.startUs - pos) } }
        if (hit == null) { toast = Toast("Add a video or photo first"); openAddMedia(); return }
        select(hit.id)
    }

    /** Changes the canvas shape, keeping the resolution class (short side). */
    fun setRatio(aw: Int, ah: Int, label: String) {
        val p = project ?: return
        val short = minOf(p.settings.width, p.settings.height)
        val (w, h) = com.amiri.cut.ui.newproject.frameSize(short, aw, ah)
        if (w == p.settings.width && h == p.settings.height) return
        commit("Ratio $label", p.copy(settings = p.settings.copy(width = w, height = h, aspectLabel = label)))
        engine.refreshFrame()
    }

    /** Original ratio = the first picture's own shape. */
    fun setRatioOriginal() {
        val p = project ?: return
        val a = p.tracks.filter { it.kind == TrackKind.VIDEO }.asReversed().flatMap { it.clips }.firstNotNullOfOrNull { cl -> p.asset(cl.assetId)?.takeIf { it.type != MediaType.AUDIO } }
            ?: run { toast = Toast("Add a video or photo first"); return }
        val s = com.amiri.cut.ui.newproject.AutoProject.settingsFor(a.displayWidth, a.displayHeight)
        val short = minOf(p.settings.width, p.settings.height)
        val scale = short.toFloat() / minOf(s.width, s.height)
        fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
        commit("Ratio Original", p.copy(settings = p.settings.copy(width = even(s.width * scale), height = even(s.height * scale), aspectLabel = "Original")))
        engine.refreshFrame()
    }

    fun setFill(label: String, live: Boolean = false, f: (com.amiri.cut.core.model.CanvasFill) -> com.amiri.cut.core.model.CanvasFill) {
        val p = project ?: return
        val np = p.copy(settings = p.settings.copy(fill = f(p.settings.fill)))
        if (live) { beginEdit(); liveEdit(np) } else commit(label, np)
        engine.refreshFrame()
    }

    /** CI screenshot harness: fills the demo project and puts the editor in the requested state. */
    suspend fun runDemo(plan: com.amiri.cut.export.UiDemo.Plan) {
        val png = plan.extras.filter { it.path?.endsWith(".png") == true }
        val wav = plan.extras.filter { it.path?.endsWith(".wav") == true }
        if (png.isNotEmpty()) { engine.seekTo(1_200_000L); importNow(png, Placement.OVERLAY) }
        if (wav.isNotEmpty()) { engine.seekTo(0L); importNow(wav, Placement.AT_PLAYHEAD) }
        engine.seekTo(400_000L)
        addText("Weekend vibes")
        val p = project ?: return
        val main = p.tracks.filter { it.kind == TrackKind.VIDEO }.asReversed().firstNotNullOfOrNull { t -> t.clips.firstOrNull() }
        if (main != null) {
            p.tracks.firstNotNullOfOrNull { t -> t.clips.getOrNull(1)?.takeIf { t.kind == TrackKind.VIDEO } }?.let {
                setTransition(it.id, com.amiri.cut.core.model.Transition("whip", 600_000L))
            }
            select(main.id)
            engine.seekTo(main.startUs + 1_300_000L)
        }
        val screen = plan.screen
        when {
            screen == "editor" -> select(null)
            screen == "editor:ANIMATION" -> {
                setAnim("Animation") { it.copy(inId = "zoomIn", inDur = 0.6f, comboId = "kenBurnsIn") }
                activeTool = EditorTool.ANIMATION
            }
            screen == "editor:STICKERS" -> {
                addArtSticker("ginger")
                kotlinx.coroutines.delay(1200)
                activeTool = EditorTool.STICKERS
            }
            screen.startsWith("editor:") -> runCatching { activeTool = EditorTool.valueOf(screen.substringAfter(':')) }
            screen == "export" -> openExport = true
        }
        pps = 70f
        toast = null
    }

    fun rename(name: String) {
        val p = project ?: return
        commit("Rename project", p.copy(name = name))
    }

    // ───────────────────────── Roto brush ─────────────────────────

    data class RotoTarget(val track: Track, val clip: Clip, val asset: MediaAsset)

    var rotoMode by mutableStateOf(RotoBrushMode.SMART)
    /** Smart brush: use the on-device person model as a hint. */
    var rotoAiAssist by mutableStateOf(false)
    /** Smart edge softness 0 (crisp) .. 1 (soft, for hair/fur). */
    var rotoSoftness by mutableFloatStateOf(0.45f)
    /** Propagate with the smart tracker (motion + edge re-segmentation) instead of plain shift. */
    var rotoSmartTrack by mutableStateOf(true)
    /** Brush diameter as a fraction of the frame width. */
    var rotoBrush by mutableFloatStateOf(0.07f)
    /** Edge feather as a fraction of the frame width. */
    var rotoFeather by mutableFloatStateOf(0.006f)
    /** Hair brush: matting window (px at mask resolution) and edge contrast. */
    var rotoHairRadius by mutableFloatStateOf(12f)
    var rotoHairContrast by mutableFloatStateOf(1.2f)

    /** The exact source frame of the roto target at mask resolution. */
    private suspend fun rotoFrame(t: RotoTarget, src: Long, w: Int, h: Int): AndroidBitmap? = withContext(Dispatchers.IO) {
        val b = if (t.asset.type == MediaType.VIDEO) BitmapLoader.videoFrame(app, t.asset, src, maxOf(w, h), exact = true)
        else BitmapLoader.decodeImage(app, t.asset, maxOf(w, h))
        b?.let { if (it.width != w || it.height != h) AndroidBitmap.createScaledBitmap(it, w, h, true) else it }
    }
    /** Off while painting: full frame with the mask tinted; on: background removed. */
    var rotoShowResult by mutableStateOf(false)
    var rotoProgress by mutableStateOf<Float?>(null)
        private set
    /** A smart stroke is being computed. */
    var rotoBusy by mutableStateOf(false)
        private set
    /** Bumped whenever a mask image changes (overlay redraw). */
    var rotoVersion by mutableIntStateOf(0)
        private set
    private var rotoJob: Job? = null
    private val rotoMutex = Mutex()

    /** The clip the roto tool works on: the selected clip if it is under the playhead, else the top visible one. */
    fun rotoTarget(): RotoTarget? {
        val p = project ?: return null
        val pos = engine.position.value
        val sel = selectedClipId?.let { id -> p.trackOfClip(id)?.let { t -> t to t.clips.first { it.id == id } } }
        val pair = sel?.takeIf { (t, c) -> t.acceptsVisual && c.contains(pos) } ?: TimelineOps.topVisualClipAt(p, pos) ?: return null
        val asset = p.asset(pair.second.assetId) ?: return null
        if (asset.type == MediaType.AUDIO) return null
        return RotoTarget(pair.first, pair.second, asset)
    }

    fun rotoSourceUs(t: RotoTarget): Long = t.clip.sourceTimeAt(engine.position.value).coerceAtLeast(0)

    /** Mask resolution: longest side 640 px at the media's display aspect. */
    private fun maskSize(a: MediaAsset): Pair<Int, Int> {
        val w = a.displayWidth.takeIf { it > 0 } ?: 1920
        val h = a.displayHeight.takeIf { it > 0 } ?: 1080
        return if (w >= h) 640 to (640f * h / w).toInt().coerceAtLeast(16)
        else (640f * w / h).toInt().coerceAtLeast(16) to 640
    }

    fun currentRotoMask(t: RotoTarget): AndroidBitmap? =
        t.clip.roto?.keyAt(rotoSourceUs(t))?.let { app.roto.load(projectId, it.file) }

    /** Is there a key exactly on this frame (vs. a mask held from an earlier key)? */
    fun rotoKeyHere(t: RotoTarget): Boolean {
        val r = t.clip.roto ?: return false
        val src = rotoSourceUs(t)
        val half = 500_000L / (project?.settings?.fps ?: 30)
        return r.keys.any { kotlin.math.abs(it.sourceUs - src) <= half }
    }

    private fun editableTarget(): RotoTarget? {
        val t = rotoTarget()
        if (t == null) { toast = Toast("Move the playhead over a video or photo clip"); return null }
        if (t.track.locked || t.clip.locked) { toast = Toast("Clip is locked"); return null }
        return t
    }

    private suspend fun writeKey(t: RotoTarget, sourceUs: Long, mask: AndroidBitmap, label: String) {
        val name = app.roto.save(projectId, mask)
        val p = project ?: return
        val clip = p.clip(t.clip.id) ?: return
        val roto = (clip.roto ?: Roto()).withKey(RotoKey(sourceUs, name))
        commit(label, TimelineOps.replaceClip(p, clip.copy(roto = roto)))
        rotoVersion++
        engine.refreshFrame()
    }

    /** Applies one brush/lasso stroke (points normalised to the media frame, 0..1). */
    fun applyRotoStroke(points: List<Pair<Float, Float>>) {
        val t = editableTarget() ?: return
        if (points.isEmpty()) return
        val src = rotoSourceUs(t)
        val mode = rotoMode
        val size = rotoBrush
        val feather = rotoFeather
        scope.launch {
            rotoMutex.withLock {
                val fresh = project?.clip(t.clip.id)?.let { t.copy(clip = it) } ?: return@withLock
                val base = fresh.clip.roto?.keyAt(src)?.let { app.roto.load(projectId, it.file) }
                val (w, h) = maskSize(t.asset)
                val smart = mode == RotoBrushMode.SMART || mode == RotoBrushMode.SMART_CUT
                val bmp = if (smart) {
                    val frame = rotoFrame(fresh, src, w, h)
                    if (frame == null) { toast = Toast("Couldn't read this frame"); return@withLock }
                    rotoBusy = true
                    try {
                        val soft = rotoSoftness
                        val ai = rotoAiAssist
                        withContext(Dispatchers.Default) {
                            val img = com.amiri.cut.media.RotoSmart.image(frame)
                            val prev = base?.let { com.amiri.cut.media.RotoSmart.alpha(it, w, h) }
                            val st = RotoPainter.paint(null, w, h, points, RotoBrushMode.ADD, size, 0f).let { com.amiri.cut.media.RotoSmart.alpha(it, w, h) }
                                .let { a -> BooleanArray(a.size) { a[it] > 0.5f } }
                            val prior = if (ai) com.amiri.cut.media.AutoCutout.confidence(app, frame) else null
                            val m = com.amiri.cut.core.vision.SmartRoto.stroke(img, prev, st, mode == RotoBrushMode.SMART, prior, soft)
                            com.amiri.cut.media.RotoSmart.bitmap(m, w, h)
                        }
                    } finally { rotoBusy = false }
                } else if (mode == RotoBrushMode.HAIR) {
                    val frame = rotoFrame(fresh, src, w, h)
                    if (frame == null) { toast = Toast("Couldn't read this frame"); return@withLock }
                    val radius = rotoHairRadius.toInt()
                    val contrast = rotoHairContrast
                    withContext(Dispatchers.Default) {
                        val band = RotoPainter.paint(null, w, h, points, RotoBrushMode.ADD, size, 0f)
                        com.amiri.cut.media.RotoMatting.refine(frame, base, band, radius, contrast)
                    }
                } else withContext(Dispatchers.Default) { RotoPainter.paint(base, w, h, points, mode, size, feather) }
                writeKey(fresh, src, bmp, "Roto ${mode.label.lowercase()}")
            }
        }
    }

    /** Empties the mask on this frame (everything removed until you paint again). */
    fun rotoClearFrame() {
        val t = editableTarget() ?: return
        val (w, h) = maskSize(t.asset)
        scope.launch { rotoMutex.withLock { writeKey(t, rotoSourceUs(t), RotoPainter.empty(w, h), "Roto clear") } }
    }

    /** Refine Edge: smooths jagged brush edges into a clean, soft matte on this frame. */
    fun rotoRefineEdge() {
        val t = editableTarget() ?: return
        val base = currentRotoMask(t) ?: run { toast = Toast("Paint a mask first"); return }
        scope.launch {
            rotoMutex.withLock {
                val src = rotoSourceUs(t)
                val frame = rotoFrame(t, src, base.width, base.height)
                val radius = rotoHairRadius.toInt()
                val contrast = rotoHairContrast
                val soft = rotoSoftness
                val refined = withContext(Dispatchers.Default) {
                    if (frame != null && rotoSmartTrack) {
                        val w = base.width; val h = base.height
                        val a = com.amiri.cut.media.RotoSmart.alpha(base, w, h)
                        com.amiri.cut.media.RotoSmart.bitmap(com.amiri.cut.core.vision.SmartRoto.refine(com.amiri.cut.media.RotoSmart.image(frame), a, soft, 0.012f), w, h)
                    } else if (frame != null) {
                        val band = com.amiri.cut.media.RotoMatting.edgeBand(base, 4)
                        com.amiri.cut.media.RotoMatting.refine(frame, base, band, radius, contrast)
                    } else RotoPropagator.refineEdge(base, radius = 3, softness = 0.35f)
                }
                writeKey(t, src, refined, "Refine edge")
            }
        }
    }

    fun rotoToggleInvert() {
        val t = editableTarget() ?: return
        val r = t.clip.roto ?: run { toast = Toast("Paint a mask first"); return }
        commit("Roto invert", TimelineOps.replaceClip(project ?: return, t.clip.copy(roto = r.copy(invert = !r.invert))))
        engine.refreshFrame()
    }

    fun rotoToggleEnabled() {
        val t = editableTarget() ?: return
        val r = t.clip.roto ?: return
        commit(if (r.enabled) "Roto off" else "Roto on", TimelineOps.replaceClip(project ?: return, t.clip.copy(roto = r.copy(enabled = !r.enabled))))
        engine.refreshFrame()
    }

    fun rotoRemove() {
        val t = editableTarget() ?: return
        if (t.clip.roto == null) return
        commit("Remove roto", TimelineOps.replaceClip(project ?: return, t.clip.copy(roto = null)))
        rotoVersion++
        engine.refreshFrame()
    }

    /** Moves the playhead to the previous/next roto key of the target clip. */
    fun rotoJumpKey(forward: Boolean) {
        val t = rotoTarget() ?: return
        val keys = t.clip.roto?.keys ?: return
        val src = rotoSourceUs(t)
        val k = if (forward) keys.firstOrNull { it.sourceUs > src + 1 } else keys.lastOrNull { it.sourceUs < src - 1 }
        k ?: return
        engine.pause()
        engine.seekTo(t.clip.startUs + t.clip.sourceToTimeline(k.sourceUs - t.clip.sourceInUs))
    }

    /**
     * Frame propagation: tracks the subject from this frame forward (until the next key
     * or the clip end) and writes a moved mask for every frame.
     */
    fun rotoPropagate() {
        val t = editableTarget() ?: return
        if (t.asset.type != MediaType.VIDEO) { toast = Toast("Photos use one mask for the whole clip"); return }
        val base = currentRotoMask(t) ?: run { toast = Toast("Paint a mask on this frame first"); return }
        val p = project ?: return
        val src0 = rotoSourceUs(t)
        val step = 1_000_000L / p.settings.fps
        val nextKey = t.clip.roto?.keys?.firstOrNull { it.sourceUs > src0 + step / 2 }?.sourceUs
        val end = (nextKey?.minus(step) ?: (t.clip.sourceOutUs - 1)).coerceAtMost(t.asset.durationUs - 1)
        if (end <= src0) { toast = Toast("Nothing to propagate after this frame"); return }
        engine.pause()
        rotoJob?.cancel()
        val smartTrack = rotoSmartTrack
        val soft = rotoSoftness
        rotoJob = scope.launch {
            rotoProgress = 0f
            val job = coroutineContext[Job]
            val results = withContext(Dispatchers.Default) {
                if (smartTrack) {
                    val r = runCatching {
                        com.amiri.cut.media.RotoSmart.propagate(
                            app, t.asset, base, src0, end, step, soft,
                            isCancelled = { job?.isActive == false },
                            onProgress = { f -> rotoProgress = f * 0.9f },
                        )
                    }.onFailure { android.util.Log.e("AmiriRoto", "smart propagate", it) }.getOrNull()
                    r?.map { RotoPropagator.Result(it.sourceUs, it.mask) } ?: RotoPropagator.propagate(
                        app, t.asset, base, src0, end, step,
                        isCancelled = { job?.isActive == false },
                        onProgress = { f -> rotoProgress = f * 0.9f },
                    )
                } else RotoPropagator.propagate(
                    app, t.asset, base, src0, end, step,
                    isCancelled = { job?.isActive == false },
                    onProgress = { f -> rotoProgress = f * 0.9f },
                )
            }
            if (results.isNotEmpty()) {
                val names = withContext(Dispatchers.IO) { results.map { app.roto.saveBlocking(projectId, it.mask) } }
                val cur = project
                val clip = cur?.clip(t.clip.id)
                if (cur != null && clip != null) {
                    val newKeys = results.mapIndexed { i, r -> RotoKey(r.sourceUs, names[i]) }
                    val lastUs = results.last().sourceUs
                    val kept = (clip.roto?.keys ?: emptyList()).filterNot { it.sourceUs > src0 && it.sourceUs <= lastUs }
                    val roto = (clip.roto ?: Roto()).copy(keys = (kept + newKeys).sortedBy { it.sourceUs })
                    commit("Propagate roto", TimelineOps.replaceClip(cur, clip.copy(roto = roto)))
                    rotoVersion++
                    engine.refreshFrame()
                    toast = Toast("Mask tracked across ${results.size} frames")
                }
            }
            rotoProgress = null
        }
    }

    fun rotoCancel() {
        rotoJob?.cancel()
        rotoProgress = null
        toast = Toast("Propagation cancelled")
    }

    // ═════════════════════════ Stage 2+ : parameters & keyframes ═════════════════════════

    /** What a parameter belongs to. */
    sealed interface PTarget {
        val clipId: String
        data class Transform(override val clipId: String) : PTarget
        data class Fx(override val clipId: String, val effectId: String) : PTarget
        data class Mask(override val clipId: String, val maskId: String) : PTarget
        data class Text(override val clipId: String) : PTarget
        data class Audio(override val clipId: String) : PTarget
        data class Shape(override val clipId: String) : PTarget
    }

    private var editBase: Project? = null

    /** The parameter last touched (drives the keyframe interpolation editor). */
    var focusParam by mutableStateOf<Pair<PTarget, String>?>(null)

    /** Starts a continuous edit (slider drag, gizmo gesture) — one undo step until [endEdit]. */
    fun beginEdit() { if (editBase == null) editBase = project }

    /** Applies a project change immediately without creating an undo step. */
    fun liveEdit(p: Project?) {
        if (p == null) return
        beginEdit()
        setProjectInternal(p)
    }

    fun endEdit(label: String) {
        val base = editBase ?: return
        editBase = null
        val cur = project ?: return
        if (cur != base) {
            history.record(base, label)
            historyVersion++
            project = cur.copy(modifiedAt = System.currentTimeMillis())
            engine.setProject(project!!)
            scheduleAutosave()
        }
    }

    fun selectedClip(): Clip? = selectedClipId?.let { project?.clip(it) }

    fun propsOf(t: PTarget): com.amiri.cut.core.model.Props? {
        val c = project?.clip(t.clipId) ?: return null
        return when (t) {
            is PTarget.Transform -> c.transform
            is PTarget.Fx -> c.effects.firstOrNull { it.id == t.effectId }?.props
            is PTarget.Mask -> c.masks.firstOrNull { it.id == t.maskId }?.props
            is PTarget.Text -> c.text?.props
            is PTarget.Audio -> c.audio
            is PTarget.Shape -> c.shape?.props
        }
    }

    private fun withProps(c: Clip, t: PTarget, f: (com.amiri.cut.core.model.Props) -> com.amiri.cut.core.model.Props): Clip = when (t) {
        is PTarget.Transform -> c.copy(transform = f(c.transform))
        is PTarget.Fx -> c.copy(effects = c.effects.map { if (it.id == t.effectId) it.copy(props = f(it.props)) else it })
        is PTarget.Mask -> c.copy(masks = c.masks.map { if (it.id == t.maskId) it.copy(props = f(it.props)) else it })
        is PTarget.Text -> c.copy(text = c.text?.let { it.copy(props = f(it.props)) })
        is PTarget.Audio -> c.copy(audio = f(c.audio))
        is PTarget.Shape -> c.copy(shape = c.shape?.let { it.copy(props = f(it.props)) })
    }

    /** Clip-local playhead time (for keyframes). */
    fun localTime(clipId: String): Long {
        val c = project?.clip(clipId) ?: return 0
        return (engine.position.value - c.startUs).coerceIn(0, c.durationUs)
    }

    private fun keyTolerance(): Long = 500_000L / (project?.settings?.fps ?: 30)

    fun paramValue(t: PTarget, id: String, default: Float): Float =
        propsOf(t)?.at(id, localTime(t.clipId), default) ?: default

    fun isAnimated(t: PTarget, id: String): Boolean = propsOf(t)?.get(id)?.animated == true

    fun keyHere(t: PTarget, id: String): Boolean =
        propsOf(t)?.get(id)?.keyNear(localTime(t.clipId), keyTolerance()) != null

    /** Sets a value at the playhead (adds/updates a key when the parameter is animated). */
    fun setParam(t: PTarget, id: String, value: Float, default: Float, live: Boolean = true, label: String = "Adjust") {
        val p = project ?: return
        val lt = localTime(t.clipId)
        val tol = keyTolerance()
        val np = TimelineOps.updateClip(p, t.clipId) { c ->
            withProps(c, t) { pr -> pr.with(id, (pr[id] ?: com.amiri.cut.core.model.Param(default)).set(lt, value, tol)) }
        }
        if (np == null) { toast = Toast("Clip is locked"); return }
        if (live) liveEdit(np) else commit(label, np)
    }

    /** Diamond button: adds a key with the current value, or removes the key at the playhead. */
    fun toggleKey(t: PTarget, id: String, default: Float) {
        val p = project ?: return
        val lt = localTime(t.clipId)
        val tol = keyTolerance()
        val cur = paramValue(t, id, default)
        val has = keyHere(t, id)
        val np = TimelineOps.updateClip(p, t.clipId) { c ->
            withProps(c, t) { pr ->
                val param = pr[id] ?: com.amiri.cut.core.model.Param(default)
                pr.with(id, if (has) param.withoutKeyNear(lt, tol) else param.withKey(lt, cur, tol))
            }
        }
        commit(if (has) "Remove keyframe" else "Add keyframe", np)
    }

    fun keyAtPlayhead(t: PTarget, id: String): com.amiri.cut.core.model.Key? =
        propsOf(t)?.get(id)?.keyNear(localTime(t.clipId), keyTolerance())

    /** Changes interpolation (and bezier handles) of the key at the playhead. */
    fun setInterp(t: PTarget, id: String, interp: com.amiri.cut.core.model.Interp, bez: FloatArray? = null, live: Boolean = false) {
        val p = project ?: return
        val lt = localTime(t.clipId)
        val tol = keyTolerance()
        val np = TimelineOps.updateClip(p, t.clipId) { c ->
            withProps(c, t) { pr ->
                val param = pr[id] ?: return@withProps pr
                pr.with(id, param.copy(keys = param.keys.map { k ->
                    if (kotlin.math.abs(k.t - lt) <= tol) {
                        if (bez != null) k.copy(interp = interp, c1x = bez[0], c1y = bez[1], c2x = bez[2], c2y = bez[3]) else k.copy(interp = interp)
                    } else k
                }))
            }
        }
        if (live) liveEdit(np) else commit("Interpolation", np)
    }

    /** All keyframe times of the selected clip (clip-local µs). */
    fun keyTimesOf(clipId: String): List<Long> = project?.clip(clipId)?.keyTimes() ?: emptyList()

    /** Moves every key at [fromLocal] to [toLocal] (timeline keyframe drag). */
    fun moveKeysAt(clipId: String, fromLocal: Long, toLocal: Long, live: Boolean) {
        val p = project ?: return
        val tol = keyTolerance()
        fun mv(pr: com.amiri.cut.core.model.Props) = com.amiri.cut.core.model.Props(pr.p.mapValues { (_, prm) ->
            if (prm.keys.isEmpty()) prm else prm.copy(keys = prm.keys.map { k -> if (kotlin.math.abs(k.t - fromLocal) <= tol) k.copy(t = toLocal) else k }.sortedBy { it.t })
        })
        val np = TimelineOps.updateClip(p, clipId) { c ->
            c.copy(
                transform = mv(c.transform), audio = mv(c.audio),
                effects = c.effects.map { it.copy(props = mv(it.props)) },
                masks = c.masks.map { it.copy(props = mv(it.props)) },
                text = c.text?.let { it.copy(props = mv(it.props)) },
                shape = c.shape?.let { it.copy(props = mv(it.props)) },
            )
        }
        if (live) liveEdit(np) else commit("Move keyframes", np)
    }

    fun jumpKey(clipId: String, forward: Boolean) {
        val c = project?.clip(clipId) ?: return
        val lt = localTime(clipId)
        val keys = c.keyTimes()
        val k = if (forward) keys.firstOrNull { it > lt + keyTolerance() } else keys.lastOrNull { it < lt - keyTolerance() }
        k ?: return
        engine.pause()
        engine.seekTo(c.startUs + k)
    }

    // ═════════════════════════ Transform ═════════════════════════

    fun updateSelected(label: String, live: Boolean = false, f: (Clip) -> Clip) {
        val id = selectedClipId ?: run { toast = Toast("Select a clip first"); return }
        val np = TimelineOps.updateClip(project ?: return, id, f)
        if (np == null) { toast = Toast("Clip is locked"); return }
        if (live) liveEdit(np) else commit(label, np)
    }

    fun resetTransform() = updateSelected("Reset transform") { it.copy(transform = com.amiri.cut.core.model.Props(), flipH = false, flipV = false, blend = com.amiri.cut.core.model.BlendMode.NORMAL) }

    /** Scale so the layer covers the whole canvas (Fill) or fits inside it (Fit). */
    fun fitFill(fill: Boolean) {
        val p = project ?: return
        val c = selectedClip() ?: return
        val a = p.asset(c.assetId) ?: return
        val cw = p.settings.width.toFloat(); val ch = p.settings.height.toFloat()
        val sw = a.displayWidth.toFloat().coerceAtLeast(1f); val sh = a.displayHeight.toFloat().coerceAtLeast(1f)
        val fit = kotlin.math.min(cw / sw, ch / sh)
        val scale = if (fill) kotlin.math.max(cw / (sw * fit), ch / (sh * fit)) else 1f
        setParam(PTarget.Transform(c.id), "scale", scale, 1f, live = false, label = if (fill) "Fill" else "Fit")
        setParam(PTarget.Transform(c.id), "px", 0f, 0f, live = false, label = "Center")
        setParam(PTarget.Transform(c.id), "py", 0f, 0f, live = false, label = "Center")
    }

    // ═════════════════════════ Speed / reverse / freeze ═════════════════════════

    fun setSpeed(v: Float) {
        val id = selectedClipId ?: run { toast = Toast("Select a clip first"); return }
        val np = TimelineOps.setSpeed(project ?: return, id, v)
        if (np == null) toast = Toast("Not enough room after the clip — move or trim the next clip first")
        else commit("Speed ${"%.2f".format(v)}×", np)
    }

    fun setRamp(r: com.amiri.cut.core.model.SpeedRamp?, live: Boolean = false) =
        updateSelected("Speed ramp", live) { it.copy(ramp = r) }

    /** Busy state for long on-device jobs (reverse, proxy, tracking, stabilization). */
    data class Busy(val label: String, val progress: Float)
    var busy by mutableStateOf<Busy?>(null)
        private set
    private var busyJob: Job? = null
    private var busyCancel: java.util.concurrent.atomic.AtomicBoolean? = null

    fun cancelBusy() {
        busyCancel?.set(true)
        busyJob?.cancel()
        busy = null
    }

    private fun runBusy(label: String, block: suspend (cancel: java.util.concurrent.atomic.AtomicBoolean, progress: (Float) -> Unit) -> Unit) {
        if (busy != null) { toast = Toast("Wait for the current job to finish"); return }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        busyCancel = cancel
        busy = Busy(label, 0f)
        engine.pause()
        busyJob = scope.launch {
            try {
                block(cancel) { f -> busy = Busy(label, f.coerceIn(0f, 1f)) }
            } catch (t: Throwable) {
                if (!cancel.get() && t !is kotlinx.coroutines.CancellationException) toast = Toast("$label failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = null
                busyCancel = null
            }
        }
    }

    fun reverseSelected() {
        val c = selectedClip() ?: run { toast = Toast("Select a video clip"); return }
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Only video clips can be reversed"); return }
        // Reverse again → back to the original source.
        val orig = c.reversedFrom?.let { p.asset(it) }
        if (orig != null) {
            commit("Un-reverse", TimelineOps.swapToReversed(p, c.id, orig, a.id))
            return
        }
        runBusy("Reversing") { cancel, prog ->
            val rev = withContext(Dispatchers.Default) {
                val r = com.amiri.cut.export.Transcode.reverse(app, app, projectId, a, cancel, prog)
                r
            }
            val cur = project ?: return@runBusy
            commit("Reverse", TimelineOps.swapToReversed(cur, c.id, rev, a.id))
            requestCaches(rev)
            toast = Toast("Clip reversed")
        }
    }

    fun freezeFrame(seconds: Float = 2f) {
        val p = project ?: return
        val pos = engine.position.value
        val c = selectedClip()?.takeIf { it.contains(pos) } ?: TimelineOps.topVisualClipAt(p, pos)?.second
            ?: run { toast = Toast("Move the playhead over a video clip"); return }
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Freeze frame works on video clips"); return }
        val src = c.sourceTimeAt(pos)
        runBusy("Freeze frame") { _, prog ->
            val still = withContext(Dispatchers.IO) {
                val bmp = BitmapLoader.videoFrame(app, a, src, 2160) ?: error("Couldn't read that frame")
                val dir = java.io.File(app.filesDir, "projects/$projectId/media").apply { mkdirs() }
                val f = java.io.File(dir, "freeze_${com.amiri.cut.core.model.newId()}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                prog(1f)
                MediaAsset(
                    id = com.amiri.cut.core.model.newId(), uri = Uri.fromFile(f).toString(), type = MediaType.IMAGE,
                    name = "Freeze " + FrameTime.timecode(src, p.settings.fps), durationUs = 0, width = bmp.width, height = bmp.height,
                )
            }
            val cur = project ?: return@runBusy
            val r = TimelineOps.insertFreeze(cur, c.id, pos, still, (seconds * 1_000_000).toLong())
            commit("Freeze frame", r?.first)
            r?.second?.let { selectedClipId = it.id }
            requestCaches(still)
        }
    }

    fun makeProxy(assetId: String) {
        val a = project?.asset(assetId) ?: return
        if (a.type != MediaType.VIDEO) return
        runBusy("Building proxy") { cancel, prog ->
            val uri = withContext(Dispatchers.Default) { com.amiri.cut.export.Transcode.proxy(app, app, a, cancel, prog) }
            val cur = project ?: return@runBusy
            commit("Proxy", cur.copy(assets = cur.assets.map { if (it.id == assetId) it.copy(proxyUri = uri) else it }))
            toast = Toast("Proxy ready")
        }
    }

    // ═════════════════════════ Masks ═════════════════════════

    var selectedMaskId by mutableStateOf<String?>(null)
    /** Pen tool: points (source uv) waiting to be closed into a path mask. */
    var penPoints by mutableStateOf<List<Pair<Float, Float>>>(emptyList())
    var penActive by mutableStateOf(false)
    var anchorDragging by mutableStateOf(false)

    fun addMask(shape: com.amiri.cut.core.model.MaskShape) {
        val m = com.amiri.cut.core.model.ShapeMask(com.amiri.cut.core.model.newId(), shape)
        updateSelected("Add mask") { c -> c.copy(masks = (c.masks + m).takeLast(4)) }
        selectedMaskId = m.id
    }

    fun closePenMask() {
        val pts = penPoints
        penActive = false
        penPoints = emptyList()
        if (pts.size < 3) { toast = Toast("Tap at least 3 points"); return }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val w = (maxX - minX).coerceAtLeast(0.01f); val h = (maxY - minY).coerceAtLeast(0.01f)
        val path = pts.flatMap { listOf((it.first - minX) / w, (it.second - minY) / h) }
        val props = com.amiri.cut.core.model.Props.of("x" to (minX + w / 2), "y" to (minY + h / 2), "w" to w, "h" to h, "feather" to 0.01f)
        val m = com.amiri.cut.core.model.ShapeMask(com.amiri.cut.core.model.newId(), com.amiri.cut.core.model.MaskShape.PATH, props = props, path = path)
        updateSelected("Pen mask") { c -> c.copy(masks = (c.masks + m).takeLast(4)) }
        selectedMaskId = m.id
    }

    fun updateMask(maskId: String, label: String, live: Boolean = false, f: (com.amiri.cut.core.model.ShapeMask) -> com.amiri.cut.core.model.ShapeMask) =
        updateSelected(label, live) { c -> c.copy(masks = c.masks.map { if (it.id == maskId) f(it) else it }) }

    fun deleteMask(maskId: String) {
        updateSelected("Delete mask") { c -> c.copy(masks = c.masks.filterNot { it.id == maskId }) }
        if (selectedMaskId == maskId) selectedMaskId = null
    }

    // ═════════════════════════ Motion tracking ═════════════════════════

    /** Regions to track (source uv). One = position; two = position + scale + rotation. */
    var trackRegions by mutableStateOf(listOf(android.graphics.RectF(0.42f, 0.42f, 0.58f, 0.58f)))

    var trackMode by mutableStateOf(com.amiri.cut.media.MotionTracker.Mode.POSITION)

    /**
     * Joins newly tracked samples to existing data: keeps old samples on the other side of
     * [from] and offsets the new ones so position, scale and rotation continue smoothly.
     */
    private fun mergeTrack(old: com.amiri.cut.core.model.TrackData?, new: List<com.amiri.cut.core.model.TrackSample>, from: Long, dir: Int, scaleRot: Boolean): com.amiri.cut.core.model.TrackData {
        val base = old?.at(from)
        val anchor = new.firstOrNull { it.sourceUs == from } ?: (if (dir > 0) new.first() else new.last())
        val adj = if (old == null || base == null || old.samples.isEmpty()) new else new.map {
            it.copy(
                x = it.x + (base.x - anchor.x), y = it.y + (base.y - anchor.y),
                scale = it.scale * base.scale / anchor.scale.coerceAtLeast(0.01f), rot = it.rot + base.rot - anchor.rot,
            )
        }
        val keep = old?.samples?.filter { if (dir > 0) it.sourceUs < from else it.sourceUs > from } ?: emptyList()
        return com.amiri.cut.core.model.TrackData((keep + adj).sortedBy { it.sourceUs }, scaleRot = scaleRot || (old?.scaleRot == true))
    }

    /** Tracks the selected video clip from the playhead: [dir] 1 forward, -1 backward, 0 both ways. */
    fun track(dir: Int) {
        val c = selectedClip() ?: run { toast = Toast("Select the video clip to track"); return }
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Tracking needs a video clip"); return }
        val pos = engine.position.value.coerceIn(c.startUs, c.endUs - 1)
        val from = c.sourceTimeAt(pos).coerceIn(0, a.durationUs - 1)
        val step = 1_000_000L / p.settings.fps
        val mode = trackMode
        val regions = (if (mode == com.amiri.cut.media.MotionTracker.Mode.TWO_POINT) trackRegions.take(2) else trackRegions.take(1)).map { android.graphics.RectF(it) }
        if (mode == com.amiri.cut.media.MotionTracker.Mode.TWO_POINT && regions.size < 2) { toast = Toast("Place both boxes first"); return }
        runBusy("Tracking") { cancel, prog ->
            var data = project?.clip(c.id)?.tracking
            var lost: Long? = null
            var count = 0
            for (d in if (dir == 0) listOf(-1, 1) else listOf(dir)) {
                val to = if (d > 0) (c.sourceOutUs - 1).coerceAtMost(a.durationUs - 1) else c.sourceInUs.coerceAtLeast(0)
                if (to == from) continue
                val r = withContext(Dispatchers.Default) {
                    com.amiri.cut.media.MotionTracker.track(app, a, from, to, step * d, regions, mode, { cancel.get() }, { f -> prog(if (dir == 0) (if (d < 0) f / 2 else 0.5f + f / 2) else f) })
                }
                if (r.samples.size >= 2) { data = mergeTrack(data, r.samples, from, d, mode != com.amiri.cut.media.MotionTracker.Mode.POSITION); count += r.samples.size }
                if (r.lostAtUs != null) lost = r.lostAtUs
            }
            if (count < 2 || data == null) { toast = Toast("Couldn't track — choose a detailed area (edges, texture, contrast)"); return@runBusy }
            val cur = project ?: return@runBusy
            commit("Track", TimelineOps.updateClip(cur, c.id) { it.copy(tracking = data) })
            attachPrompt = c.id
            toast = Toast(if (lost != null) "Tracked $count frames · target lost — place the box again there and continue" else "Tracked $count frames")
        }
    }

    fun trackForward() = track(1)

    /** Plays the selected text clip from its start (to see its entrance). */
    fun previewTextFromStart() {
        val c = selectedClip() ?: return
        engine.seekTo(c.startUs); engine.play()
    }

    /** Plays the last seconds of the selected text clip (to see its exit). */
    fun previewTextEnd() {
        val c = selectedClip() ?: return
        val d = (c.text?.outDur ?: 0.6f).coerceAtLeast(c.text?.glassOutDur ?: 0f)
        engine.seekTo((c.endUs - (d * 1_000_000).toLong() - 500_000L).coerceAtLeast(c.startUs)); engine.play()
    }

    // ═════════════════════════ Beat sync ═════════════════════════

    /** Finds the beats of the selected sound (audio clip or video with sound) and marks them on the timeline. */
    fun detectBeats(mode: com.amiri.cut.core.audio.BeatMath.Mode) {
        val c = selectedClip() ?: run { toast = Toast("Select a music clip (or a video with sound)"); return }
        val p = project ?: return
        val a = p.asset(c.assetId)?.takeIf { it.hasAudio } ?: run { toast = Toast("This clip has no sound"); return }
        runBusy("Finding beats") { cancel, prog ->
            val r = withContext(Dispatchers.Default) {
                com.amiri.cut.media.BeatDetector.detect(app, a.uri, c.sourceInUs, c.sourceOutUs, mode, { cancel.get() }, prog)
            }
            if (r.times.isEmpty()) { toast = Toast("No clear beat found"); return@runBusy }
            val cur = project ?: return@runBusy
            val cl = cur.clip(c.id) ?: return@runBusy
            val times = r.times.map { src -> cl.startUs + cl.sourceToTimeline((src * 1_000_000).toLong() - cl.sourceInUs) }
                .filter { it in cl.startUs until cl.endUs }
            commit("Beat markers", TimelineOps.setBeatMarkers(cur, times, cl.startUs, cl.endUs))
            toast = Toast("${times.size} beats marked" + (if (r.bpm > 0) " · ${r.bpm.toInt()} BPM" else "") + " — clips snap to them")
        }
    }

    fun clearBeatMarkers() {
        val p = project ?: return
        commit("Clear beat markers", p.copy(markers = p.markers.filterNot { it.label == "♪" }))
    }

    /** Cuts the selected clip at every beat marker inside it. */
    fun cutOnBeats() {
        val p = project ?: return
        val id = selectedClipId ?: run { toast = Toast("Select the clip to cut"); return }
        val np = TimelineOps.splitAtMarkers(p, id) ?: run { toast = Toast("No beat markers inside this clip — find beats first"); return }
        commit("Cut on beats", np)
        toast = Toast("Cut on the beats")
    }

    // ═════════════════════════ Voice-over ═════════════════════════

    private var recorder: com.amiri.cut.media.VoiceRecorder? = null
    private var recordFile: java.io.File? = null
    var recordingStartUs by mutableStateOf<Long?>(null)
    var recordLevel by mutableFloatStateOf(0f)
    /** Play the timeline while recording so you can narrate over the picture. */
    var recordPlayAlong by mutableStateOf(true)

    fun startVoiceRecording() {
        if (recorder != null) return
        val p = project ?: return
        val f = java.io.File(java.io.File(app.filesDir, "voice/${p.id}").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.wav")
        val r = com.amiri.cut.media.VoiceRecorder(f) { lvl -> scope.launch { recordLevel = lvl } }
        if (!r.start()) { toast = Toast("Can't record: ${r.error ?: "microphone unavailable"}"); return }
        recorder = r; recordFile = f
        recordingStartUs = engine.position.value
        if (recordPlayAlong) engine.play()
    }

    fun stopVoiceRecording() {
        val r = recorder ?: return
        val start = recordingStartUs ?: 0L
        engine.pause()
        recorder = null; recordingStartUs = null; recordLevel = 0f
        scope.launch {
            withContext(Dispatchers.IO) { r.stop() }
            val f = recordFile ?: return@launch
            val a = MediaProbe.probe(app, Uri.fromFile(f)) ?: run { toast = Toast("Recording failed"); return@launch }
            val p = project ?: return@launch
            val res = TimelineOps.placeSound(p, a.copy(name = "Voice ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())}"), start, "Voice") ?: return@launch
            val np = TimelineOps.updateClip(res.first, res.second.id) { c ->
                c.copy(audio = c.audio.with("voice", com.amiri.cut.core.model.Param(1f)).with("denoise", com.amiri.cut.core.model.Param(0.35f)).with("enhance", com.amiri.cut.core.model.Param(0.4f)))
            } ?: res.first
            commit("Record voice", np)
            requestCaches(a)
            selectedClipId = res.second.id
            toast = Toast("Voice added · noise reduction on — music with Auto-duck gets quieter under it")
        }
    }

    // ═════════════════════════ Auto cut-out ═════════════════════════

    /** Finds the person in every frame of the selected video and makes a roto mask for it. */
    fun autoCutout(edgeSoftness: Float = 0.3f) {
        val c = selectedClip() ?: run { toast = Toast("Select a video clip"); return }
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO && a.type != MediaType.IMAGE) { toast = Toast("Auto cut-out needs a video or photo"); return }
        if (!com.amiri.cut.media.AutoCutout.available(app)) { toast = Toast("The cut-out model isn't included in this build"); return }
        val step = 1_000_000L / p.settings.fps
        val times = if (a.type == MediaType.IMAGE) listOf(0L) else {
            val l = ArrayList<Long>(); var t = c.sourceInUs
            val end = (c.sourceOutUs - 1).coerceAtMost(a.durationUs - 1)
            while (t <= end) { l += t; t += step }
            l
        }
        if (a.type == MediaType.IMAGE) { toast = Toast("Auto cut-out works on videos — for photos use the roto brush"); return }
        runBusy("Auto cut-out") { cancel, prog ->
            val keys = ArrayList<com.amiri.cut.core.model.RotoKey>()
            val n = try {
                withContext(Dispatchers.Default) {
                    com.amiri.cut.media.AutoCutout.run(app, a, times, edgeSoftness, { cancel.get() }, prog) { t, mask ->
                        keys += com.amiri.cut.core.model.RotoKey(t, app.roto.saveBlocking(projectId, mask))
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("AmiriCutout", "failed", e)
                toast = Toast("Auto cut-out isn't supported on this device (${e.javaClass.simpleName})")
                return@runBusy
            }
            if (n == 0) { toast = Toast("Couldn't analyse this clip"); return@runBusy }
            val cur = project ?: return@runBusy
            commit("Auto cut-out", TimelineOps.updateClip(cur, c.id) { it.copy(roto = com.amiri.cut.core.model.Roto(enabled = true, keys = keys)) } ?: return@runBusy)
            toast = Toast("Person cut out on $n frames — refine with the roto brush if needed")
        }
    }

    // ═════════════════════════ AI voice isolation ═════════════════════════

    /** Separates the human voice from wind / traffic / crowd / hum with the on-device network. */
    fun isolateVoice(strength: Float, strong: Boolean) {
        val c = selectedClip() ?: run { toast = Toast("Select a clip with sound"); return }
        val p = project ?: return
        val a = p.asset(c.assetId)?.takeIf { it.hasAudio } ?: run { toast = Toast("This clip has no sound"); return }
        runBusy(if (strong) "Isolating voice (strong)" else "Isolating voice") { cancel, prog ->
            val file = withContext(Dispatchers.Default) {
                val pcm = com.amiri.cut.media.AudioDecode.mono(app, a.uri, c.sourceInUs, c.sourceOutUs, { cancel.get() }) { f -> prog(f * 0.15f) }
                    ?: return@withContext null
                if (pcm.data.isEmpty()) return@withContext null
                // Pad the start if the decoder began a little late, so timing stays exact.
                val lead = ((pcm.startUs - c.sourceInUs) * pcm.rate / 1_000_000L).toInt().coerceIn(0, pcm.rate)
                val src = if (lead > 0) FloatArray(lead) + pcm.data else pcm.data
                val out = com.amiri.cut.media.VoiceIsolation.clean(app, src, pcm.rate, strength, if (strong) 2 else 1, { cancel.get() }) { f -> prog(0.15f + f * 0.8f) }
                if (cancel.get()) return@withContext null
                val f = java.io.File(java.io.File(app.filesDir, "voice/${p.id}"), "clean-${System.currentTimeMillis()}.wav")
                com.amiri.cut.media.AudioDecode.writeWav(f, out, pcm.rate)
                f
            } ?: run { toast = Toast("Couldn't process this sound"); return@runBusy }
            val asset = MediaProbe.probe(app, Uri.fromFile(file)) ?: run { toast = Toast("Couldn't read the cleaned sound"); return@runBusy }
            val cur = project ?: return@runBusy
            val r = TimelineOps.useCleanSound(cur, c.id, asset.copy(name = c.name + " (clean voice)")) ?: run { toast = Toast("Couldn't place the cleaned sound"); return@runBusy }
            commit("AI voice isolation", r.first)
            requestCaches(asset)
            selectedClipId = r.second.id
            toast = Toast("Voice isolated — wind and noise removed. Undo to compare with the original.")
        }
    }

    // ═════════════════════════ Effect presets ═════════════════════════

    private val presetPrefs get() = app.getSharedPreferences("fx_presets", android.content.Context.MODE_PRIVATE)
    var presetVersion by mutableStateOf(0)

    fun effectPresets(): List<String> = presetPrefs.all.keys.sorted()

    fun saveEffectPreset(name: String) {
        val c = selectedClip() ?: return
        if (c.effects.isEmpty()) { toast = Toast("This layer has no effects to save"); return }
        val json = app.projects.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.amiri.cut.core.model.Effect.serializer()), c.effects)
        presetPrefs.edit().putString(name, json).apply()
        presetVersion++
        toast = Toast("Saved preset “$name”")
    }

    fun applyEffectPreset(name: String) {
        val json = presetPrefs.getString(name, null) ?: return
        val fx = runCatching { app.projects.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.amiri.cut.core.model.Effect.serializer()), json) }.getOrNull() ?: return
        updateSelected("Preset $name") { c -> c.copy(effects = c.effects + fx.map { it.copy(id = com.amiri.cut.core.model.newId()) }) }
    }

    fun deleteEffectPreset(name: String) { presetPrefs.edit().remove(name).apply(); presetVersion++ }

    // ═════════════════════════ Copy / paste ═════════════════════════

    /** Copied layer attributes (a clip snapshot). */
    var clipboard by mutableStateOf<Clip?>(null)
    /** Copied keyframe values: (target kind, param id) → value. */
    private var keyClipboard: List<Triple<String, String, Float>> = emptyList()

    fun copyAttributes(clipId: String? = selectedClipId) {
        val c = project?.clip(clipId ?: return) ?: return
        clipboard = c
        toast = Toast("Copied effects & style of ${c.name}")
    }

    /** What: "effects", "transform", "all". */
    fun pasteAttributes(what: String, clipId: String? = selectedClipId) {
        val src = clipboard ?: run { toast = Toast("Copy a layer first"); return }
        val p = project ?: return
        val id = clipId ?: return
        val np = TimelineOps.updateClip(p, id) { c ->
            val fx = src.effects.map { it.copy(id = com.amiri.cut.core.model.newId()) }
            when (what) {
                "effects" -> c.copy(effects = c.effects + fx)
                "transform" -> c.copy(transform = src.transform, blend = src.blend, flipH = src.flipH, flipV = src.flipV)
                else -> c.copy(
                    effects = fx, transform = src.transform, blend = src.blend, flipH = src.flipH, flipV = src.flipV,
                    masks = if (c.kind == src.kind) src.masks.map { it.copy(id = com.amiri.cut.core.model.newId()) } else c.masks,
                    text = if (c.text != null && src.text != null) src.text.copy(text = c.text.text) else c.text,
                    shape = if (c.shape != null && src.shape != null) src.shape else c.shape,
                    audio = src.audio,
                )
            }
        } ?: return
        commit("Paste ${what}", np)
        toast = Toast("Pasted")
    }

    /** Copies every keyframed value of the selected clip at the playhead. */
    fun copyKeyframesAt() {
        val c = selectedClip() ?: return
        val lt = localTime(c.id)
        val tol = keyTolerance()
        val out = ArrayList<Triple<String, String, Float>>()
        fun grab(kind: String, pr: com.amiri.cut.core.model.Props) = pr.p.forEach { (id, prm) -> if (prm.keyNear(lt, tol) != null) out += Triple(kind, id, prm.at(lt)) }
        grab("transform", c.transform)
        c.text?.let { grab("text", it.props) }
        c.shape?.let { grab("shape", it.props) }
        grab("audio", c.audio)
        c.effects.forEach { e -> grab("fx:" + e.type, e.props) }
        keyClipboard = out
        toast = Toast(if (out.isEmpty()) "No keyframes at the playhead" else "Copied ${out.size} keyframes")
    }

    /** Pastes copied keyframe values as keys at the playhead (on matching parameters). */
    fun pasteKeyframesAt() {
        val c = selectedClip() ?: return
        if (keyClipboard.isEmpty()) { toast = Toast("Copy keyframes first"); return }
        val p = project ?: return
        val lt = localTime(c.id)
        val tol = keyTolerance()
        fun put(pr: com.amiri.cut.core.model.Props, kind: String): com.amiri.cut.core.model.Props {
            var r = pr
            for ((k, id, v) in keyClipboard) if (k == kind) {
                val prm = r.p[id] ?: com.amiri.cut.core.model.Param(v)
                r = r.with(id, prm.withKey(lt, v, tol))
            }
            return r
        }
        val np = TimelineOps.updateClip(p, c.id) { cl ->
            cl.copy(
                transform = put(cl.transform, "transform"), audio = put(cl.audio, "audio"),
                text = cl.text?.let { it.copy(props = put(it.props, "text")) },
                shape = cl.shape?.let { it.copy(props = put(it.props, "shape")) },
                effects = cl.effects.map { e -> e.copy(props = put(e.props, "fx:" + e.type)) },
            )
        } ?: return
        commit("Paste keyframes", np)
        toast = Toast("Keyframes pasted")
    }

    // ═════════════════════════ Layer effect strip ═════════════════════════

    fun toggleLayerItem(clipId: String, key: String) {
        val p = project ?: return
        val np = TimelineOps.updateClip(p, clipId) { c ->
            when {
                key.startsWith("fx:") -> c.copy(effects = c.effects.map { if (it.id == key.removePrefix("fx:")) it.copy(enabled = !it.enabled) else it })
                key == "roto" -> c.copy(roto = c.roto?.copy(enabled = !c.roto.enabled))
                key == "stab" -> c.copy(stab = c.stab?.copy(enabled = !c.stab.enabled))
                else -> c
            }
        } ?: return
        commit("Toggle effect", np)
    }

    fun deleteLayerItem(clipId: String, key: String) {
        val p = project ?: return
        val np = TimelineOps.updateClip(p, clipId) { c ->
            when {
                key.startsWith("fx:") -> c.copy(effects = c.effects.filterNot { it.id == key.removePrefix("fx:") })
                key == "roto" -> c.copy(roto = null)
                key == "stab" -> c.copy(stab = null)
                else -> c
            }
        } ?: return
        commit("Delete effect", np)
    }

    fun openLayerItem(clipId: String, key: String) {
        selectedClipId = clipId
        when {
            key.startsWith("fx:") -> {
                selectedEffectId = key.removePrefix("fx:")
                activeTool = if (project?.clip(clipId)?.effects?.firstOrNull { it.id == selectedEffectId }?.type == "color") EditorTool.COLOR else EditorTool.EFFECTS
            }
            key == "roto" -> activeTool = EditorTool.ROTO
            key == "stab" -> activeTool = EditorTool.STABILIZE
            key == "masks" -> activeTool = EditorTool.MASK
        }
    }

    /** Set after a successful track: asks what to attach to the tracked point (clip id). */
    var attachPrompt by mutableStateOf<String?>(null)

    enum class AttachKind { TEXT, SHAPE, OVERLAY }

    /**
     * Creates a text / shape / overlay layer exactly on [trackedClipId]'s tracked point at the
     * playhead, lasting to the end of the tracked clip, and attaches it to the track so it
     * follows the point (and its scale/rotation when tracked).
     */
    fun attachNewToTrack(trackedClipId: String, kind: AttachKind, overlay: MediaAsset? = null) {
        val p = project ?: return
        val tc = p.clip(trackedClipId) ?: return
        val td = tc.tracking ?: run { toast = Toast("Track the clip first"); return }
        val t = engine.position.value.coerceIn(tc.startUs, tc.endUs - 1)
        val sample = td.at(tc.sourceTimeAt(t)) ?: return
        val cw = p.settings.width; val ch = p.settings.height
        val pt = com.amiri.cut.render.LayerMath.trackedPoint(p, tc, sample.x, sample.y, t, cw, ch)
        val px = (pt?.get(0) ?: (sample.x * cw)) / cw - 0.5f
        val py = (pt?.get(1) ?: (sample.y * ch)) / ch - 0.5f
        val dur = (tc.endUs - t).coerceAtLeast(1_000_000L)
        val r: Pair<Project, Clip> = when (kind) {
            AttachKind.TEXT -> TimelineOps.addText(p, t, "Text", dur)
            AttachKind.SHAPE -> TimelineOps.addShape(p, t, com.amiri.cut.core.model.ShapeKind.ELLIPSE, dur).let { (np, c) ->
                val nc = c.copy(shape = c.shape?.copy(props = c.shape.props.with("w", com.amiri.cut.core.model.Param(0.18f)).with("h", com.amiri.cut.core.model.Param(0.18f))))
                (TimelineOps.updateClip(np, c.id) { nc } ?: np) to nc
            }
            AttachKind.OVERLAY -> TimelineOps.placeOverlay(p, overlay ?: return, t, dur) ?: return
        }
        var np = r.first
        val id = r.second.id
        np = TimelineOps.updateClip(np, id) { c ->
            c.copy(
                transform = c.transform.with("px", com.amiri.cut.core.model.Param(px)).with("py", com.amiri.cut.core.model.Param(py)),
                follow = com.amiri.cut.core.model.Follow(trackedClipId, t, true, td.scaleRot, td.scaleRot),
            )
        } ?: np
        commit("Attach ${kind.name.lowercase()} to track", np)
        selectedClipId = id
        attachPrompt = null
        activeTool = when (kind) { AttachKind.TEXT -> EditorTool.TEXT; AttachKind.SHAPE -> EditorTool.SHAPE; AttachKind.OVERLAY -> EditorTool.TRANSFORM }
        toast = Toast("Attached — it follows the tracked point")
    }

    /** Imports a photo/video and attaches it to the tracked point. */
    fun attachOverlayFromUri(trackedClipId: String, uri: Uri) {
        scope.launch {
            MediaProbe.persistPermission(app.contentResolver, uri)
            val a = MediaProbe.probe(app, uri) ?: run { toast = Toast("Couldn't read that file"); return@launch }
            if (a.type == MediaType.AUDIO) { toast = Toast("Pick a photo or video"); return@launch }
            requestCaches(a)
            attachNewToTrack(trackedClipId, AttachKind.OVERLAY, a)
        }
    }

    /**
     * Tracks a mask by its own area (position, and scale & rotation in Similarity mode) so
     * it sticks to what it covers. [dir] 1 forward, -1 backward, 0 both ways.
     */
    fun trackMask(maskId: String, dir: Int) {
        val c = selectedClip() ?: return
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Mask tracking needs a video clip"); return }
        val m = c.masks.firstOrNull { it.id == maskId } ?: return
        val pos = engine.position.value.coerceIn(c.startUs, c.endUs - 1)
        val local = pos - c.startUs
        val from = c.sourceTimeAt(pos).coerceIn(0, a.durationUs - 1)
        fun mv(id: String) = m.props.at(id, local, com.amiri.cut.core.effects.MaskSpec.def(id))
        // Where the mask is shown right now (its current track offset applied).
        var x = mv("x"); var y = mv("y")
        val td = m.track
        if (td != null && td.samples.isNotEmpty()) {
            val ref = m.trackRefUs?.let { td.at(it) } ?: td.samples.first()
            td.at(from)?.let { now -> x += now.x - ref.x; y += now.y - ref.y }
        }
        val mw = mv("w").coerceIn(0.03f, 0.6f); val mh = mv("h").coerceIn(0.03f, 0.6f)
        val region = android.graphics.RectF(x - mw / 2, y - mh / 2, x + mw / 2, y + mh / 2)
        val mode = if (trackMode == com.amiri.cut.media.MotionTracker.Mode.POSITION) com.amiri.cut.media.MotionTracker.Mode.POSITION else com.amiri.cut.media.MotionTracker.Mode.SIMILARITY
        val step = 1_000_000L / p.settings.fps
        runBusy("Tracking mask") { cancel, prog ->
            var data = td
            var count = 0
            var lost: Long? = null
            for (d in if (dir == 0) listOf(-1, 1) else listOf(dir)) {
                val to = if (d > 0) (c.sourceOutUs - 1).coerceAtMost(a.durationUs - 1) else c.sourceInUs.coerceAtLeast(0)
                if (to == from) continue
                val r = withContext(Dispatchers.Default) {
                    com.amiri.cut.media.MotionTracker.track(app, a, from, to, step * d, listOf(region), mode, { cancel.get() }, { f -> prog(if (dir == 0) (if (d < 0) f / 2 else 0.5f + f / 2) else f) })
                }
                if (r.samples.size >= 2) { data = mergeTrack(data, r.samples, from, d, mode != com.amiri.cut.media.MotionTracker.Mode.POSITION); count += r.samples.size }
                if (r.lostAtUs != null) lost = r.lostAtUs
            }
            val newData = data
            if (count < 2 || newData == null) { toast = Toast("Couldn't track this mask — make it cover a detailed area"); return@runBusy }
            val cur = project ?: return@runBusy
            // First track: the mask sits where it was drawn at this frame.
            val refUs = if (td == null) from else m.trackRefUs
            commit("Track mask", TimelineOps.updateClip(cur, c.id) { cl ->
                cl.copy(masks = cl.masks.map { if (it.id == maskId) it.copy(track = newData, trackRefUs = refUs, followTrack = false) else it })
            })
            toast = Toast(if (lost != null) "Mask tracked $count frames · target lost at one point" else "Mask tracked $count frames")
        }
    }

    fun clearMaskTrack(maskId: String) = updateSelected("Clear mask track") { c ->
        c.copy(masks = c.masks.map { if (it.id == maskId) it.copy(track = null, trackRefUs = null) else it })
    }

    fun clearTracking() = updateSelected("Clear track") { it.copy(tracking = null) }

    /** Attach the selected clip to [targetClipId]'s motion track. */
    fun follow(targetClipId: String?, position: Boolean = true, scale: Boolean = true, rotation: Boolean = true) {
        if (targetClipId == null) { updateSelected("Detach") { it.copy(follow = null) }; return }
        val ref = engine.position.value
        updateSelected("Attach to track") { it.copy(follow = com.amiri.cut.core.model.Follow(targetClipId, ref, position, scale, rotation)) }
    }

    // ═════════════════════════ Transitions ═════════════════════════

    /** The cut the transition tools work on: the clip (on the selected clip's track) whose start is nearest the playhead. */
    fun transitionTarget(): Clip? {
        val p = project ?: return null
        val sel = selectedClip()
        val track = sel?.let { p.trackOfClip(it.id) } ?: p.tracks.firstOrNull { it.kind == TrackKind.VIDEO } ?: return null
        if (!track.acceptsVisual) return null
        val pos = engine.position.value
        val withPrev = track.clips.filter { b -> track.clips.any { it.id != b.id && kotlin.math.abs(it.endUs - b.startUs) <= 1_000 } }
        return withPrev.minByOrNull { kotlin.math.abs(it.startUs - pos) } ?: track.clips.minByOrNull { kotlin.math.abs(it.startUs - pos) }
    }

    fun setTransition(clipId: String, tr: com.amiri.cut.core.model.Transition?, label: String = if (tr == null) "Remove transition" else "Transition") {
        val p = project ?: return
        val np = TimelineOps.setTransition(p, clipId, tr) ?: run { toast = Toast("That track is locked"); return }
        commit(label, np)
    }

    fun setTransitionAllCuts(clipId: String) {
        val p = project ?: return
        val tr = p.clip(clipId)?.transIn ?: return
        val track = p.trackOfClip(clipId) ?: return
        val np = TimelineOps.setTransitionAllCuts(p, track.id, tr) ?: return
        commit("Transition on all cuts", np)
        toast = Toast("Applied to every cut on ${track.name}")
    }

    /** Previews the transition: plays from just before the cut. */
    fun previewTransition(clipId: String) {
        val c = project?.clip(clipId) ?: return
        val d = c.transIn?.durationUs ?: 600_000L
        engine.seekTo((c.startUs - d / 2 - 400_000L).coerceAtLeast(0))
        engine.play()
    }

    // ═════════════════════════ Stabilization ═════════════════════════

    fun stabilize(mode: com.amiri.cut.core.model.StabMode, smoothness: Float, zoom: Float) {
        val c = selectedClip() ?: run { toast = Toast("Select a video clip"); return }
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Stabilization needs a video clip"); return }
        val step = 1_000_000L / p.settings.fps
        runBusy("Stabilizing") { cancel, prog ->
            val res = withContext(Dispatchers.Default) {
                com.amiri.cut.media.Stabilizer.analyze(app, a, c.sourceInUs, (c.sourceOutUs - 1).coerceAtMost(a.durationUs - 1), step, mode, smoothness, { cancel.get() }, prog)
            }
            if (res.samples.isEmpty()) { toast = Toast("Couldn't analyze this clip"); return@runBusy }
            val cur = project ?: return@runBusy
            // zoom < 1 = automatic: just enough to hide the moving borders.
            val z = if (zoom < 1f) res.autoZoom else zoom
            commit("Stabilize", TimelineOps.updateClip(cur, c.id) {
                it.copy(stab = com.amiri.cut.core.model.Stabilization(mode, smoothness, z, true, res.samples))
            })
            toast = Toast("Stabilized · zoom ${"%.2f".format(z)}×")
        }
    }

    // ═════════════════════════ Text ═════════════════════════

    fun addText(text: String = "Text") {
        val (np, clip) = TimelineOps.addText(project ?: return, engine.position.value, text)
        commit("Add text", np)
        selectedClipId = clip.id
    }

    // ═════════════════════════ Clip animations ═════════════════════════

    /** Changes the selected clip's In / Out / Combo animation (removed when everything is None). */
    fun setAnim(label: String, live: Boolean = false, f: (com.amiri.cut.core.model.ClipAnim) -> com.amiri.cut.core.model.ClipAnim) =
        updateSelected(label, live) { c ->
            val a = f(c.anim ?: com.amiri.cut.core.model.ClipAnim())
            c.copy(anim = a.takeIf { com.amiri.cut.core.anim.ClipAnims.active(it) })
        }

    /** Plays the part of the clip where an animation was just picked, so it can be seen. */
    fun previewAnim(part: String) {
        val c = selectedClip() ?: return
        val a = c.anim ?: return
        val start = when (part) {
            "out" -> c.endUs - (a.outDur * 1_000_000).toLong() - 300_000L
            "combo" -> engine.position.value.coerceIn(c.startUs, c.endUs - 1)
            else -> c.startUs
        }.coerceIn(c.startUs, (c.endUs - 1).coerceAtLeast(c.startUs))
        engine.seekTo(start)
        engine.play()
    }

    // ═════════════════════════ Stickers ═════════════════════════

    /**
     * Adds an emoji sticker (a text layer) at the playhead. It starts static so it is visible
     * right where it was added; motion comes from the sticker bar or the Animation tool.
     */
    fun addEmojiSticker(emoji: String) {
        val p = project ?: return
        engine.pause()
        val (np, clip) = TimelineOps.addText(p, engine.position.value, emoji, 3_000_000L)
        val spec = com.amiri.cut.core.model.TextSpec(
            text = emoji,
            props = com.amiri.cut.core.model.Props.of("size" to 0.17f),
        )
        val withSpec = TimelineOps.updateClip(np, clip.id) { it.copy(text = spec, name = "Sticker $emoji") } ?: np
        commit("Add sticker", withSpec)
        selectedClipId = clip.id
    }

    /** Adds one of the drawn cat stickers as a transparent picture overlay. */
    fun addArtSticker(kind: String) {
        val p0 = project ?: return
        engine.pause()
        val at = engine.position.value
        scope.launch {
            val asset = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = com.amiri.cut.ui.editor.StickerArt.render(kind, 640)
                    val dir = java.io.File(app.filesDir, "projects/$projectId/media").apply { mkdirs() }
                    val f = java.io.File(dir, "sticker_${kind}_${com.amiri.cut.core.model.newId()}.png")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    MediaAsset(
                        id = com.amiri.cut.core.model.newId(), uri = Uri.fromFile(f).toString(), type = MediaType.IMAGE,
                        name = "Sticker " + com.amiri.cut.ui.editor.StickerArt.label(kind), durationUs = 0, width = bmp.width, height = bmp.height,
                    )
                }.getOrNull()
            } ?: run { toast = Toast("Couldn't create the sticker"); return@launch }
            val cur = project ?: p0
            val r = TimelineOps.placeOverlay(cur, asset, at, 3_000_000L, 0.38f) ?: TimelineOps.placeAsset(cur, asset, at)
            val named = TimelineOps.updateClip(r.first, r.second.id) { it.copy(name = asset.name) } ?: r.first
            commit("Add sticker", named)
            selectedClipId = r.second.id
            requestCaches(asset)
        }
    }

    // ═════════════════════════ One-tap enhance ═════════════════════════

    /** Crisper, livelier picture in one tap: sharpen + clarity + vibrance + a touch of dehaze. */
    fun enhanceSelected() {
        val c = selectedClip() ?: run { toast = Toast("Select a clip first"); return }
        val fxId = c.effects.firstOrNull { it.type == "color" }?.id ?: ensureColorEffect() ?: return
        val t = PTarget.Fx(c.id, fxId)
        beginEdit()
        listOf("sharpen" to 0.32f, "clarity" to 0.22f, "vibrance" to 0.18f, "dehaze" to 0.08f, "contrast" to 0.05f).forEach { (id, v) ->
            setParam(t, id, v, 0f)
        }
        endEdit("Enhance")
        toast = Toast("Enhanced ✨")
    }

    fun updateText(label: String, live: Boolean = false, f: (com.amiri.cut.core.model.TextSpec) -> com.amiri.cut.core.model.TextSpec) =
        updateSelected(label, live) { c -> c.text?.let { c.copy(text = f(it), name = f(it).text.take(24).ifBlank { "Text" }) } ?: c }

    fun importFont(uri: Uri, name: String) {
        scope.launch {
            val id = withContext(Dispatchers.IO) { app.fonts.import(uri, name) }
            if (id == null) toast = Toast("Not a valid TTF/OTF font")
            else { updateText("Font") { it.copy(font = id) }; toast = Toast("Font imported") }
        }
    }

    // ═════════════════════════ Effects / color / LUT ═════════════════════════

    var selectedEffectId by mutableStateOf<String?>(null)
    var colorBefore by mutableStateOf(false)
    var showScopes by mutableStateOf(false)

    fun addEffect(type: String, opts: Map<String, String> = emptyMap()): String? {
        val spec = com.amiri.cut.core.effects.EffectCatalog.spec(type) ?: return null
        val defaults = spec.options.associate { it.id to it.default } + opts
        var props = com.amiri.cut.core.model.Props()
        if (type == "film") {
            com.amiri.cut.core.effects.EffectCatalog.FILM_PRESET_VALUES[defaults["preset"]]?.forEach { (k, v) -> props = props.with(k, com.amiri.cut.core.model.Param(v)) }
        }
        val e = com.amiri.cut.core.model.Effect(com.amiri.cut.core.model.newId(), type, props = props, opts = defaults)
        updateSelected("Add ${spec.label}") { c ->
            c.copy(effects = c.effects + e)
        }
        selectedEffectId = e.id
        return e.id
    }

    /** The clip's color-correction effect, created on first use. */
    fun ensureColorEffect(): String? {
        val c = selectedClip() ?: return null
        c.effects.firstOrNull { it.type == "color" }?.let { return it.id }
        return addEffect("color")
    }

    fun updateEffect(effectId: String, label: String, live: Boolean = false, f: (com.amiri.cut.core.model.Effect) -> com.amiri.cut.core.model.Effect) =
        updateSelected(label, live) { c -> c.copy(effects = c.effects.map { if (it.id == effectId) f(it) else it }) }

    fun removeEffect(effectId: String) {
        updateSelected("Remove effect") { c -> c.copy(effects = c.effects.filterNot { it.id == effectId }) }
        if (selectedEffectId == effectId) selectedEffectId = null
    }

    fun moveEffect(effectId: String, delta: Int) = updateSelected("Reorder effects") { c ->
        val l = c.effects.toMutableList()
        val i = l.indexOfFirst { it.id == effectId }
        val j = (i + delta).coerceIn(0, l.size - 1)
        if (i >= 0 && i != j) { val e = l.removeAt(i); l.add(j, e) }
        c.copy(effects = l)
    }

    fun applyFilmPreset(effectId: String, preset: String) {
        val values = com.amiri.cut.core.effects.EffectCatalog.FILM_PRESET_VALUES[preset] ?: return
        updateEffect(effectId, "Film preset") { e ->
            var pr = e.props
            values.forEach { (k, v) -> pr = pr.with(k, com.amiri.cut.core.model.Param(v)) }
            e.copy(props = pr, opts = e.opts + ("preset" to preset))
        }
    }

    fun importLut(uri: Uri, name: String, effectId: String?) {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { app.luts.import(uri, name) }
            if (stored == null) { toast = Toast("Not a valid .cube 3D LUT"); return@launch }
            val id = effectId ?: addEffect("lut", mapOf("file" to stored))
            if (effectId != null) updateEffect(effectId, "LUT") { it.copy(opts = it.opts + ("file" to stored)) }
            selectedEffectId = id
            toast = Toast("LUT imported")
        }
    }

    // ═════════════════════════ Filters (looks) ═════════════════════════

    /** The clip the Filters tool works on: the selected visual clip, else the clip under the playhead. */
    fun filterTarget(): Clip? {
        val p = project ?: return null
        selectedClip()?.let { c -> if (p.trackOfClip(c.id)?.acceptsVisual == true) return c }
        val pos = engine.position.value
        return p.tracks.filter { it.acceptsVisual && !it.hidden }.firstNotNullOfOrNull { t ->
            t.clipAt(pos)?.takeIf { it.kind == com.amiri.cut.core.model.ClipKind.MEDIA }
        }
    }

    /** The whole-video filter: an adjustment layer named "Filter" covering the timeline. */
    fun wholeVideoFilter(): Clip? = project?.tracks?.flatMap { it.clips }?.firstOrNull { it.adjustment && it.name.startsWith("Filter") }

    private fun lookEffect(c: Clip, name: String?): Clip {
        val fx = c.effects.firstOrNull { it.type == "color" }
        val base = fx ?: com.amiri.cut.core.model.Effect(com.amiri.cut.core.model.newId(), "color")
        val upd = if (name == null) base.copy(opts = base.opts - "look") else base.copy(opts = base.opts + ("look" to name))
        return c.copy(effects = if (fx == null) c.effects + upd else c.effects.map { if (it.id == fx.id) upd else it })
    }

    /** Applies a look ([name] null = none) to one clip, or to the whole video. */
    fun applyFilter(name: String?, wholeVideo: Boolean) {
        var p = project ?: return
        if (wholeVideo) {
            var f = wholeVideoFilter()
            if (f == null) {
                if (name == null) return
                val dur = p.durationUs.coerceAtLeast(1_000_000L)
                val (np, clip) = TimelineOps.addAdjustment(p, 0, dur)
                p = np; f = clip
            }
            val id = f.id
            val np = TimelineOps.updateClip(p, id) { c -> lookEffect(c, name).copy(name = "Filter · ${name ?: "none"}", sourceOutUs = maxOf(c.sourceOutUs, p.durationUs - c.startUs)) } ?: return
            commit("Filter ${name ?: "none"} (whole video)", np)
            selectedClipId = id
            selectedEffectId = np.clip(id)?.effects?.firstOrNull { it.type == "color" }?.id
        } else {
            val c = filterTarget() ?: run { toast = Toast("Put the playhead over a clip (or pick Whole video)"); return }
            val np = TimelineOps.updateClip(p, c.id) { lookEffect(it, name) } ?: return
            commit("Filter ${name ?: "none"}", np)
            selectedClipId = c.id
            selectedEffectId = np.clip(c.id)?.effects?.firstOrNull { it.type == "color" }?.id
        }
    }

    fun addAdjustmentLayer() {
        val (np, clip) = TimelineOps.addAdjustment(project ?: return, engine.position.value)
        commit("Add adjustment layer", np)
        selectedClipId = clip.id
    }

    /** Eyedropper: reads the source pixel at (u, v) of the selected video/photo clip. */
    fun pickColor(u: Float, v: Float, onColor: (Float, Float, Float) -> Unit) {
        val p = project ?: return
        val c = selectedClip() ?: return
        val a = p.asset(c.assetId) ?: return
        val src = c.sourceTimeAt(engine.position.value)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                if (a.type == MediaType.VIDEO) BitmapLoader.videoFrame(app, a, src, 320) else BitmapLoader.decodeImage(app, a, 320)
            } ?: return@launch
            val x = (u * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (v * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val px = bmp.getPixel(x, y)
            onColor(android.graphics.Color.red(px) / 255f, android.graphics.Color.green(px) / 255f, android.graphics.Color.blue(px) / 255f)
        }
    }

    /** Pushes the current before/after and roto-painting state to the renderer. */
    fun updateRenderOptions() {
        val sel = selectedClipId
        val rotoPainting = activeTool == EditorTool.ROTO && !rotoShowResult
        engine.options = com.amiri.cut.render.RenderOptions(
            checker = true,
            bypassColorClipId = if (colorBefore) sel else null,
            bypassRotoClipId = if (rotoPainting) rotoTarget()?.clip?.id else null,
        )
    }

    // ═════════════════════════ Export ═════════════════════════

    fun enqueueExport(settings: com.amiri.cut.export.ExportSettings, out: Uri) {
        val p = project ?: return
        val json = app.projects.json.encodeToString(Project.serializer(), p)
        com.amiri.cut.export.ExportQueue.enqueue(app, p.name, p, settings, out, json)
        toast = Toast("Export started — it continues in the background")
    }

    // ═════════════════════════ Viewer (preview zoom / pan) ═════════════════════════

    /** Preview zoom (1 = fit) and pan as fractions of the preview size (y down). */
    var viewZoom by mutableFloatStateOf(1f)
    var viewPanX by mutableFloatStateOf(0f)
    var viewPanY by mutableFloatStateOf(0f)
    /** Timeline height in dp (room for the main track plus a few lanes; drag the handle to resize). */
    var timelineHeightDp by mutableFloatStateOf(app.getSharedPreferences("amiri_settings", android.content.Context.MODE_PRIVATE).getFloat("timelineH", 196f))
    fun saveTimelineHeight() { app.getSharedPreferences("amiri_settings", android.content.Context.MODE_PRIVATE).edit().putFloat("timelineH", timelineHeightDp).apply() }

    /** Hide the timeline to give the preview the whole screen (auto on in Roto). */
    var expandedPreview by mutableStateOf(false)

    fun setView(zoom: Float, panX: Float, panY: Float) {
        val z = zoom.coerceIn(0.5f, 10f)
        val lim = 0.5f + z / 2f
        viewZoom = z
        viewPanX = panX.coerceIn(-lim, lim)
        viewPanY = panY.coerceIn(-lim, lim)
    }

    fun resetView() = setView(1f, 0f, 0f)

    /** Zooms about a focus point given in fractions of the preview (0..1, y down). */
    fun zoomViewAt(factor: Float, fx: Float, fy: Float) {
        val z0 = viewZoom
        val z1 = (z0 * factor).coerceIn(0.5f, 10f)
        val cx = fx - 0.5f
        val cy = fy - 0.5f
        val tx = cx - (cx - viewPanX) * z1 / z0
        val ty = cy - (cy - viewPanY) * z1 / z0
        setView(z1, tx, ty)
    }

    // ═════════════════════════ Shapes ═════════════════════════

    fun addShape(kind: com.amiri.cut.core.model.ShapeKind) {
        if (kind == com.amiri.cut.core.model.ShapeKind.PATH) { startPen(); return }
        var (np, clip) = TimelineOps.addShape(project ?: return, engine.position.value, kind)
        if (kind == com.amiri.cut.core.model.ShapeKind.LINE) {
            np = TimelineOps.updateClip(np, clip.id) { c ->
                c.copy(shape = c.shape?.copy(props = com.amiri.cut.core.model.Props.of("w" to 0.6f, "h" to 0.015f)))
            } ?: np
        }
        commit("Add ${kind.label}", np)
        selectedClipId = clip.id
    }

    fun setShapeKind(kind: com.amiri.cut.core.model.ShapeKind) =
        updateSelected("Shape type") { c -> c.shape?.let { c.copy(shape = it.copy(kind = kind), name = kind.label) } ?: c }

    // ═════════════════════════ Pen tool (path shapes) ═════════════════════════

    /** Vertices being drawn (6 floats each: x, y, inX, inY, outX, outY; canvas-normalised). */
    val shapePts = androidx.compose.runtime.mutableStateListOf<Float>()
    var shapePen by mutableStateOf(false)
    var penFreehand by mutableStateOf(false)
    /** Edit the vertices of the selected pen path on the preview. */
    var pathEdit by mutableStateOf(false)

    fun startPen() {
        engine.pause()
        shapePts.clear()
        shapePen = true
        pathEdit = false
        toast = Toast(if (penFreehand) "Draw with your finger" else "Tap to add points · drag to curve · tap the first point to close")
    }

    fun cancelPen() { shapePen = false; shapePts.clear(); penSaber = null; penMakeSaber = false }

    /** When set, the pen draws a Saber path for (clip id, effect id) instead of a shape. */
    var penSaber by mutableStateOf<Pair<String, String>?>(null)
    /** When set, the pen shape gets a Saber effect (outline hidden) — a "saber line". */
    var penMakeSaber by mutableStateOf(false)

    fun startSaberPen(clipId: String, effectId: String) {
        startPen()
        penSaber = clipId to effectId
    }

    fun startSaberLine() {
        startPen()
        penMakeSaber = true
    }

    /** Stores the drawn pen path (canvas space) into a Saber effect, in the layer's own space. */
    private fun finishSaberPath(clipId: String, effectId: String, closed: Boolean) {
        val p = project ?: return
        val clip = p.clip(clipId) ?: return
        val t = engine.position.value.coerceIn(clip.startUs, clip.endUs - 1)
        val cw = p.settings.width; val ch = p.settings.height
        val (bw, bh) = com.amiri.cut.render.LayerMath.baseSize(p, clip, t, cw, ch, textMeasurer) ?: return
        val inv = com.amiri.cut.render.LayerMath.inverseOf(p, clip, t, bw, bh, cw, ch)
        fun toL(x: Float, y: Float) = com.amiri.cut.render.Affine.toLayer(inv, x * cw, y * ch, ch)
        val src = shapePts.toList()
        val out = ArrayList<Float>(src.size)
        for (i in 0 until src.size / 6) {
            val b = i * 6
            val x = src[b]; val y = src[b + 1]
            val l = toL(x, y)
            val li = toL(x + src[b + 2], y + src[b + 3]); val lo = toL(x + src[b + 4], y + src[b + 5])
            out += listOf(l[0], l[1], li[0] - l[0], li[1] - l[1], lo[0] - l[0], lo[1] - l[1])
        }
        val enc = out.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }
        commit("Saber path", TimelineOps.updateClip(p, clipId) { c ->
            c.copy(effects = c.effects.map {
                if (it.id == effectId) it.copy(opts = it.opts + ("path" to enc) + ("closed" to closed.toString()) + ("source" to "Drawn path")) else it
            })
        } ?: return)
        selectedEffectId = effectId
    }

    fun penUndo() { repeat(6) { if (shapePts.isNotEmpty()) shapePts.removeAt(shapePts.lastIndex) } }

    fun finishPen(closed: Boolean) {
        val n = shapePts.size / 6
        if (n < 2) { toast = Toast("Add at least 2 points"); return }
        val p = project ?: return
        penSaber?.let { (cid, eid) ->
            finishSaberPath(cid, eid, closed && n >= 3)
            cancelPen()
            toast = Toast("Saber path set — animate it with Start / End offset")
            return
        }
        val makeSaber = penMakeSaber
        var (np, clip) = TimelineOps.addPathShape(p, engine.position.value, shapePts.toList(), closed && n >= 3)
        if (makeSaber) {
            val spec = com.amiri.cut.core.effects.EffectCatalog.SABER
            val e = com.amiri.cut.core.model.Effect(com.amiri.cut.core.model.newId(), "saber", opts = spec.options.associate { it.id to it.default })
            np = TimelineOps.updateClip(np, clip.id) { c ->
                c.copy(
                    name = "Saber line", effects = c.effects + e,
                    shape = c.shape?.copy(props = c.shape.props.with("strokeA", com.amiri.cut.core.model.Param(0f)).with("fillA", com.amiri.cut.core.model.Param(0f))),
                )
            } ?: np
            selectedEffectId = e.id
        }
        commit(if (makeSaber) "Saber line" else if (closed) "Pen shape" else "Pen line", np)
        selectedClipId = clip.id
        cancelPen()
        if (makeSaber) { activeTool = EditorTool.EFFECTS; toast = Toast("Saber line ready — color, glow and Start/End are in Effects") }
    }

    /** Moves vertex [index] of a pen path ([part] 0 = point, 1 = in handle, 2 = out handle). */
    fun movePathPoint(clipId: String, index: Int, part: Int, x: Float, y: Float) {
        val p = project ?: return
        liveEdit(TimelineOps.updateClip(p, clipId) { c ->
            val sp = c.shape ?: return@updateClip c
            if (index < 0 || index >= sp.vertexCount) return@updateClip c
            val pts = sp.path.toMutableList()
            val b = index * 6
            when (part) {
                0 -> { pts[b] = x; pts[b + 1] = y }
                1 -> { pts[b + 2] = x - pts[b]; pts[b + 3] = y - pts[b + 1]; pts[b + 4] = -pts[b + 2]; pts[b + 5] = -pts[b + 3] }
                else -> { pts[b + 4] = x - pts[b]; pts[b + 5] = y - pts[b + 1]; pts[b + 2] = -pts[b + 4]; pts[b + 3] = -pts[b + 5] }
            }
            c.copy(shape = sp.copy(path = pts))
        })
    }

    fun setPathClosed(closed: Boolean) = updateSelected(if (closed) "Close path" else "Open path") { c ->
        c.shape?.let { sp -> c.copy(shape = sp.copy(closed = closed)) } ?: c
    }

    fun setPathRoundCaps(round: Boolean) = updateSelected("Line caps") { c -> c.shape?.let { c.copy(shape = it.copy(roundCaps = round)) } ?: c }

    // ═════════════════════════ Sound ═════════════════════════

    /** Imports a sound effect (audio file, or a video's sound — e.g. a downloaded clip) at the playhead. */
    fun importSound(uri: Uri, preferName: String = "SFX") {
        scope.launch {
            MediaProbe.persistPermission(app.contentResolver, uri)
            val a = MediaProbe.probe(app, uri)
            if (a == null) { toast = Toast("Couldn't read that file"); return@launch }
            if (!a.hasAudio) { toast = Toast("That file has no sound"); return@launch }
            val p = project ?: return@launch
            val r = TimelineOps.placeSound(p, a, engine.position.value, preferName)
            if (r == null) { toast = Toast("Couldn't place that sound"); return@launch }
            commit("Add sound ${a.name}", r.first)
            requestCaches(a)
            selectedClipId = r.second.id
            toast = Toast("Added on ${r.first.trackOfClip(r.second.id)?.name}")
        }
    }

    fun detachAudio(clipId: String? = selectedClipId) {
        val p = project ?: return
        val id = clipId ?: run { toast = Toast("Select a video first"); return }
        val r = TimelineOps.detachAudio(p, id)
        if (r == null) { toast = Toast("This clip has no (unmuted) sound to detach"); return }
        commit("Detach audio", r.first)
        selectedClipId = r.second.id
        toast = Toast("Audio detached to ${r.first.trackOfClip(r.second.id)?.name}")
    }

    // ═════════════════════════ Keyframe interpolation (all params) ═════════════════════════

    private fun mapKeysAt(clipId: String, local: Long, label: String, f: (com.amiri.cut.core.model.Param, com.amiri.cut.core.model.Key) -> com.amiri.cut.core.model.Param) {
        val p = project ?: return
        val tol = keyTolerance()
        fun mv(pr: com.amiri.cut.core.model.Props) = com.amiri.cut.core.model.Props(pr.p.mapValues { (_, prm) ->
            val k = prm.keyNear(local, tol)
            if (k == null) prm else f(prm, k)
        })
        commit(label, TimelineOps.updateClip(p, clipId) { c ->
            c.copy(
                transform = mv(c.transform), audio = mv(c.audio),
                effects = c.effects.map { it.copy(props = mv(it.props)) },
                masks = c.masks.map { it.copy(props = mv(it.props)) },
                text = c.text?.let { it.copy(props = mv(it.props)) },
                shape = c.shape?.let { it.copy(props = mv(it.props)) },
            )
        })
    }

    /** Easy Ease / Linear / Hold for every key of the clip at [local] (clip-local time). */
    fun setInterpAllAt(clipId: String, local: Long, interp: com.amiri.cut.core.model.Interp) =
        mapKeysAt(clipId, local, "Keyframe: ${interp.label}") { prm, k ->
            prm.copy(keys = prm.keys.map { if (it === k) it.copy(interp = interp) else it })
        }

    /** Easy Ease (both sides): also eases the segment arriving at this key. */
    fun easyEaseAt(clipId: String, local: Long) =
        mapKeysAt(clipId, local, "Easy Ease") { prm, k ->
            val i = prm.keys.indexOf(k)
            prm.copy(keys = prm.keys.mapIndexed { j, it ->
                when {
                    j == i -> it.copy(interp = com.amiri.cut.core.model.Interp.EASE_IN_OUT)
                    j == i - 1 && it.interp == com.amiri.cut.core.model.Interp.LINEAR -> it.copy(interp = com.amiri.cut.core.model.Interp.EASE_IN_OUT)
                    else -> it
                }
            })
        }

    fun deleteKeysAt(clipId: String, local: Long) =
        mapKeysAt(clipId, local, "Delete keyframes") { prm, _ -> prm.withoutKeyNear(local, keyTolerance()) }

    /** Keyframe menu target (clip id, clip-local time) opened by long-press on the timeline. */
    var keyMenu by mutableStateOf<Pair<String, Long>?>(null)

    /** Ease the arrival at this key (the segment coming in slows down). */
    fun easeArriveAt(clipId: String, local: Long) {
        val p = project ?: return
        val tol = keyTolerance()
        fun mv(pr: com.amiri.cut.core.model.Props) = com.amiri.cut.core.model.Props(pr.p.mapValues { (_, prm) ->
            val k = prm.keyNear(local, tol) ?: return@mapValues prm
            val i = prm.keys.indexOf(k)
            if (i <= 0) prm else prm.copy(keys = prm.keys.mapIndexed { j, it ->
                if (j == i - 1) it.copy(interp = if (it.interp == com.amiri.cut.core.model.Interp.EASE_IN) com.amiri.cut.core.model.Interp.EASE_IN_OUT else com.amiri.cut.core.model.Interp.EASE_OUT) else it
            })
        })
        commit("Ease in", TimelineOps.updateClip(p, clipId) { c ->
            c.copy(
                transform = mv(c.transform), audio = mv(c.audio),
                effects = c.effects.map { it.copy(props = mv(it.props)) },
                masks = c.masks.map { it.copy(props = mv(it.props)) },
                text = c.text?.let { it.copy(props = mv(it.props)) },
                shape = c.shape?.let { it.copy(props = mv(it.props)) },
            )
        })
    }

    /** Ease the departure from this key (the segment going out starts slowly). */
    fun easeLeaveAt(clipId: String, local: Long) = mapKeysAt(clipId, local, "Ease out") { prm, k ->
        prm.copy(keys = prm.keys.map {
            if (it === k) it.copy(interp = if (it.interp == com.amiri.cut.core.model.Interp.EASE_OUT) com.amiri.cut.core.model.Interp.EASE_IN_OUT else com.amiri.cut.core.model.Interp.EASE_IN) else it
        })
    }

    /** Makes both sides linear (also the incoming segment). */
    fun linearAt(clipId: String, local: Long) = mapKeysAt(clipId, local, "Linear keyframe") { prm, k ->
        val i = prm.keys.indexOf(k)
        prm.copy(keys = prm.keys.mapIndexed { j, it ->
            when {
                j == i -> it.copy(interp = com.amiri.cut.core.model.Interp.LINEAR)
                j == i - 1 && it.interp != com.amiri.cut.core.model.Interp.HOLD -> it.copy(interp = com.amiri.cut.core.model.Interp.LINEAR)
                else -> it
            }
        })
    }

    /** Keyframe picked in the Keyframes tool (clip id, clip-local time). */
    var selectedKey by mutableStateOf<Pair<String, Long>?>(null)

    private fun flowKey(k: com.amiri.cut.core.model.Key, f: com.amiri.cut.core.model.FlowPresets.Flow?) =
        if (f == null) k.copy(interp = com.amiri.cut.core.model.Interp.LINEAR)
        else k.copy(interp = com.amiri.cut.core.model.Interp.BEZIER, c1x = f.c1x, c1y = f.c1y, c2x = f.c2x, c2y = f.c2y)

    /** Applies a Flow curve (null = linear) to the motion leaving the keyframe at [local] (or arriving, for the last key). */
    fun applyFlowAt(clipId: String, local: Long, f: com.amiri.cut.core.model.FlowPresets.Flow?) =
        mapKeysAt(clipId, local, "Flow: ${f?.name ?: "Linear"}") { prm, k ->
            val i = prm.keys.indexOf(k)
            val target = if (i == prm.keys.lastIndex && i > 0) i - 1 else i
            prm.copy(keys = prm.keys.mapIndexed { j, it -> if (j == target) flowKey(it, f) else it })
        }

    /** Applies a Flow curve to every keyframe of one clip, or of the whole video when [clipId] is null. */
    fun applyFlowAll(clipId: String?, f: com.amiri.cut.core.model.FlowPresets.Flow?) {
        val p = project ?: return
        fun mv(pr: com.amiri.cut.core.model.Props) = com.amiri.cut.core.model.Props(pr.p.mapValues { (_, prm) ->
            if (prm.keys.size < 2) prm else prm.copy(keys = prm.keys.mapIndexed { j, k -> if (j < prm.keys.lastIndex) flowKey(k, f) else k })
        })
        var np = p
        for (c in p.tracks.flatMap { it.clips }) {
            if (clipId != null && c.id != clipId) continue
            if (c.keyTimes().isEmpty()) continue
            np = TimelineOps.updateClip(np, c.id) { cl ->
                cl.copy(
                    transform = mv(cl.transform), audio = mv(cl.audio),
                    effects = cl.effects.map { it.copy(props = mv(it.props)) },
                    masks = cl.masks.map { it.copy(props = mv(it.props)) },
                    text = cl.text?.let { it.copy(props = mv(it.props)) },
                    shape = cl.shape?.let { it.copy(props = mv(it.props)) },
                )
            } ?: np
        }
        commit("Flow ${f?.name ?: "Linear"} on all keyframes", np)
    }

    /** Toggles a key between linear and eased (double-tap on the timeline). */
    fun toggleEaseAt(clipId: String, local: Long) {
        val c = project?.clip(clipId) ?: return
        val m = c.keyMarks(keyTolerance()).minByOrNull { kotlin.math.abs(it.t - local) } ?: return
        if (m.easeIn || m.easeOut) linearAt(clipId, m.t)
        else easyEaseAt(clipId, m.t)
    }

    // ═════════════════════════ Quick keyframes ═════════════════════════

    private val quickIds = listOf("px", "py", "scale", "rot", "opacity")

    /** Does the selected clip have transform keys at the playhead? */
    fun quickKeyHere(): Boolean {
        val c = selectedClip() ?: return false
        val t = PTarget.Transform(c.id)
        return quickIds.any { keyHere(t, it) }
    }

    /** ◆ button: keys Position, Scale, Rotation and Opacity at the playhead (or removes them). */
    fun toggleQuickKeys() {
        val c = selectedClip() ?: run { toast = Toast("Select a clip first"); return }
        if (project?.trackOfClip(c.id)?.acceptsVisual != true) { toast = Toast("Keyframes for sound are in the Audio tool"); return }
        val p = project ?: return
        val t = PTarget.Transform(c.id)
        val lt = localTime(c.id)
        val tol = keyTolerance()
        val remove = quickKeyHere()
        val np = TimelineOps.updateClip(p, c.id) { cl ->
            withProps(cl, t) { pr ->
                var out = pr
                for (id in quickIds) {
                    val d = com.amiri.cut.core.effects.TransformSpec.def(id)
                    val prm = out[id] ?: com.amiri.cut.core.model.Param(d)
                    out = out.with(id, if (remove) prm.withoutKeyNear(lt, tol) else prm.withKey(lt, prm.at(lt), tol))
                }
                out
            }
        }
        commit(if (remove) "Remove keyframes" else "Add keyframes", np)
        focusParam = t to "px"
        toast = Toast(if (remove) "Keyframes removed" else "Keyframes added — move the playhead and change position, scale, rotation or opacity")
    }

    /** Moves the anchor point without moving the layer (After Effects "pan behind"). */
    fun setAnchorKeepingPlace(clipId: String, ax: Float, ay: Float, canvasX: Float, canvasY: Float) {
        val t = PTarget.Transform(clipId)
        setParam(t, "ax", ax, 0.5f)
        setParam(t, "ay", ay, 0.5f)
        setParam(t, "px", canvasX - 0.5f, 0f)
        setParam(t, "py", canvasY - 0.5f, 0f)
    }
}
