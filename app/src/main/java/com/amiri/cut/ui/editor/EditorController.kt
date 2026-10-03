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
    val available: Boolean get() = stage <= 1
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
}
