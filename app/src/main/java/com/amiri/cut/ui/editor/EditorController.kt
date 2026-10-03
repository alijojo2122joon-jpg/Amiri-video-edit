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

enum class Placement { APPEND_TO_MAIN, AT_PLAYHEAD, BIN_ONLY }

enum class EditorTool(val label: String, val stage: Int) {
    MEDIA("Media", 1),
    CUT("Cut", 1),
    TRANSFORM("Transform", 2),
    SPEED("Speed", 2),
    MASK("Mask", 5),
    TRACK("Track", 8),
    ROTO("Roto", 9),
    TEXT("Text", 4),
    COLOR("Color", 6),
    EFFECTS("Effects", 7),
    AUDIO("Audio", 10),
    ;
    /** Tools that are fully implemented in this build. */
    val available: Boolean get() = this == MEDIA || this == CUT || this == ROTO
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
        engine.maskProvider = { clip, sourceUs ->
            clip.roto?.keyAt(sourceUs)?.let { k -> app.roto.load(projectId, k.file) }
        }
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
        importing += uris.size
        scope.launch {
            val assets = ArrayList<MediaAsset>()
            var failed = 0
            for (u in uris) {
                MediaProbe.persistPermission(app.contentResolver, u)
                val a = MediaProbe.probe(app, u)
                if (a == null) failed++ else assets += a
                importing--
            }
            val base = project ?: return@launch
            var p = base
            var cursor = engine.position.value
            for (a in assets) {
                p = when (placement) {
                    Placement.APPEND_TO_MAIN -> TimelineOps.appendToMain(p, a).first
                    Placement.AT_PLAYHEAD -> {
                        val (np, clip) = TimelineOps.placeAsset(p, a, cursor)
                        cursor = clip.endUs
                        np
                    }
                    Placement.BIN_ONLY -> TimelineOps.addAsset(p, a)
                }
                requestCaches(a)
            }
            if (assets.isNotEmpty()) commit(if (assets.size == 1) "Import ${assets[0].name}" else "Import ${assets.size} files", p)
            toast = when {
                failed > 0 && assets.isEmpty() -> Toast("Couldn't read the selected file(s)")
                failed > 0 -> Toast("Imported ${assets.size}, skipped $failed unreadable")
                else -> Toast(if (assets.size == 1) "Imported 1 file" else "Imported ${assets.size} files")
            }
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
        if (np == null) toast = Toast("Only empty, unlocked tracks can be removed")
        else commit("Remove track", np)
    }

    fun rename(name: String) {
        val p = project ?: return
        commit("Rename project", p.copy(name = name))
    }

    // ───────────────────────── Roto brush ─────────────────────────

    data class RotoTarget(val track: Track, val clip: Clip, val asset: MediaAsset)

    var rotoMode by mutableStateOf(RotoBrushMode.ADD)
    /** Brush diameter as a fraction of the frame width. */
    var rotoBrush by mutableFloatStateOf(0.07f)
    /** Edge feather as a fraction of the frame width. */
    var rotoFeather by mutableFloatStateOf(0.006f)
    /** Off while painting: full frame with the mask tinted; on: background removed. */
    var rotoShowResult by mutableStateOf(false)
    var rotoProgress by mutableStateOf<Float?>(null)
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
                val bmp = withContext(Dispatchers.Default) { RotoPainter.paint(base, w, h, points, mode, size, feather) }
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
                val refined = withContext(Dispatchers.Default) { RotoPropagator.refineEdge(base, radius = 3, softness = 0.35f) }
                writeKey(t, rotoSourceUs(t), refined, "Refine edge")
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
        rotoJob = scope.launch {
            rotoProgress = 0f
            val job = coroutineContext[Job]
            val results = withContext(Dispatchers.Default) {
                RotoPropagator.propagate(
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
}
