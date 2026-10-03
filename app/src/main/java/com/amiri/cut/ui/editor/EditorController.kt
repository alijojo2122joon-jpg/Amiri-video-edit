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
    KEYS("Keyframes", 3),
    SPEED("Speed", 2),
    MASK("Mask", 5),
    TRACK("Track", 8),
    ROTO("Roto", 9),
    TEXT("Text", 4),
    SHAPE("Shape", 4),
    COLOR("Color", 6),
    EFFECTS("Effects", 7),
    AUDIO("Audio", 10),
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
        if (np == null) toast = Toast("Locked tracks and the last video track can't be removed")
        else {
            if (project?.track(trackId)?.clips?.any { it.id == selectedClipId } == true) selectedClipId = null
            commit("Remove track", np)
            toast = Toast("Track removed · Undo to bring it back")
        }
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
                val bmp = if (mode == RotoBrushMode.HAIR) {
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
                val refined = withContext(Dispatchers.Default) {
                    if (frame != null) {
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

    fun trackForward() {
        val c = selectedClip() ?: run { toast = Toast("Select the video clip to track"); return }
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Tracking needs a video clip"); return }
        val pos = engine.position.value.coerceIn(c.startUs, c.endUs - 1)
        val from = c.sourceTimeAt(pos)
        val to = (c.sourceOutUs - 1).coerceAtMost(a.durationUs - 1)
        val step = 1_000_000L / p.settings.fps
        val regions = trackRegions.map { android.graphics.RectF(it) }
        runBusy("Tracking") { cancel, prog ->
            val samples = withContext(Dispatchers.Default) {
                com.amiri.cut.media.MotionTracker.track(app, a, from, to, step, regions, { cancel.get() }, prog)
            }
            if (samples.size < 2) { toast = Toast("Couldn't track — choose a textured area"); return@runBusy }
            val cur = project ?: return@runBusy
            val old = cur.clip(c.id)?.tracking?.samples?.filter { it.sourceUs < from } ?: emptyList()
            val data = com.amiri.cut.core.model.TrackData(old + samples, scaleRot = regions.size >= 2)
            commit("Track", TimelineOps.updateClip(cur, c.id) { it.copy(tracking = data) })
            toast = Toast("Tracked ${samples.size} frames")
        }
    }

    fun clearTracking() = updateSelected("Clear track") { it.copy(tracking = null) }

    /** Attach the selected clip to [targetClipId]'s motion track. */
    fun follow(targetClipId: String?, position: Boolean = true, scale: Boolean = true, rotation: Boolean = true) {
        if (targetClipId == null) { updateSelected("Detach") { it.copy(follow = null) }; return }
        val ref = engine.position.value
        updateSelected("Attach to track") { it.copy(follow = com.amiri.cut.core.model.Follow(targetClipId, ref, position, scale, rotation)) }
    }

    // ═════════════════════════ Stabilization ═════════════════════════

    fun stabilize(mode: com.amiri.cut.core.model.StabMode, smoothness: Float, zoom: Float) {
        val c = selectedClip() ?: run { toast = Toast("Select a video clip"); return }
        val p = project ?: return
        val a = p.asset(c.assetId) ?: return
        if (a.type != MediaType.VIDEO) { toast = Toast("Stabilization needs a video clip"); return }
        val step = 1_000_000L / p.settings.fps
        runBusy("Stabilizing") { cancel, prog ->
            val samples = withContext(Dispatchers.Default) {
                com.amiri.cut.media.Stabilizer.analyze(app, a, c.sourceInUs, (c.sourceOutUs - 1).coerceAtMost(a.durationUs - 1), step, mode, smoothness, { cancel.get() }, prog)
            }
            if (samples.isEmpty()) { toast = Toast("Couldn't analyze this clip"); return@runBusy }
            val cur = project ?: return@runBusy
            commit("Stabilize", TimelineOps.updateClip(cur, c.id) {
                it.copy(stab = com.amiri.cut.core.model.Stabilization(mode, smoothness, zoom, true, samples))
            })
            toast = Toast("Stabilized")
        }
    }

    // ═════════════════════════ Text ═════════════════════════

    fun addText(text: String = "Text") {
        val (np, clip) = TimelineOps.addText(project ?: return, engine.position.value, text)
        commit("Add text", np)
        selectedClipId = clip.id
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
            if (c.kind == com.amiri.cut.core.model.ClipKind.MEDIA || c.kind == com.amiri.cut.core.model.ClipKind.ADJUSTMENT || c.kind == com.amiri.cut.core.model.ClipKind.TEXT) c.copy(effects = c.effects + e) else c
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

    fun cancelPen() { shapePen = false; shapePts.clear() }

    fun penUndo() { repeat(6) { if (shapePts.isNotEmpty()) shapePts.removeAt(shapePts.lastIndex) } }

    fun finishPen(closed: Boolean) {
        val n = shapePts.size / 6
        if (n < 2) { toast = Toast("Add at least 2 points"); return }
        val p = project ?: return
        val (np, clip) = TimelineOps.addPathShape(p, engine.position.value, shapePts.toList(), closed && n >= 3)
        commit(if (closed) "Pen shape" else "Pen line", np)
        selectedClipId = clip.id
        shapePen = false
        shapePts.clear()
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
