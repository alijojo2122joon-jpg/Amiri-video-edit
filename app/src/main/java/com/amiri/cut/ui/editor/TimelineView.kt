package com.amiri.cut.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.ClipKind
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.Track
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.core.timeline.TimelineOps
import com.amiri.cut.ui.common.paw
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.AmiriFont
import com.amiri.cut.ui.theme.Haptics
import com.amiri.cut.ui.theme.LocalAccent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Live state of an in-progress clip drag (drawn as a ghost; committed on release). */
private sealed interface DragOp {
    val clipId: String
    data class Move(
        override val clipId: String,
        val targetTrackId: String,
        val startUs: Long,
        val result: Project?,
        val snapUs: Long?,
    ) : DragOp
    data class Trim(override val clipId: String, val clip: Clip, val snapUs: Long?) : DragOp
}

private sealed interface Hit {
    data object Ruler : Hit
    data object Empty : Hit
    data object AddMedia : Hit
    data object AddAudio : Hit
    data object MuteMain : Hit
    data class TransBtn(val clipId: String) : Hit
    data class ClipHit(val track: Track, val clip: Clip) : Hit
    data class Handle(val clip: Clip, val start: Boolean) : Hit
    data class KeyHit(val clip: Clip, val t: Long) : Hit
}

private enum class Phase { TAP, DRAG, LONG, MULTI }

/**
 * Geometry shared between drawing and gesture code. Gesture coroutines are long-lived,
 * so they read the latest values from this holder instead of captured snapshots.
 */
private class TimelineGeometry {
    var project: Project? = null
    var posUs: Long = 0
    var pps: Float = 90f
    var width: Float = 0f
    var height: Float = 0f
    var scrollY: Float = 0f
    var rulerH: Float = 0f
    var handleW: Float = 0f
    var dp: Float = 1f
    var selectedId: String? = null
    /** Per track index (p.tracks order): height of its row (0 = folded away) and its top. */
    var rowHeights: List<Float> = emptyList()
    var rowTops: List<Float> = emptyList()
    /** Display order of track indices (top → bottom). */
    var order: List<Int> = emptyList()
    var mainIdx: Int = -1
    /** Per track: clip body height, attached-sound strip height, keyframe lane height. */
    var bodyHs: List<Float> = emptyList()
    var audioHs: List<Float> = emptyList()
    var keyHs: List<Float> = emptyList()
    /** Extra lane at the bottom ("+ Add audio"); 0 when not shown. */
    var addAudioTop: Float = 0f
    var addAudioH: Float = 0f
    var contentH: Float = 0f
    // Buttons drawn in the canvas (screen coordinates, refreshed on every draw).
    var addBtn: Rect? = null
    var muteBtn: Rect? = null
    var addAudioBtn: Rect? = null
    var transBtns: List<Pair<Rect, String>> = emptyList()

    val centerX: Float get() = width / 2f
    fun xOf(us: Long): Float = centerX + ((us - posUs) / 1_000_000.0 * pps).toFloat()
    fun usAt(x: Float): Long = posUs + ((x - centerX) / pps * 1_000_000.0).toLong()
    fun rowY(i: Int): Float = rulerH + rowTops[i] - scrollY

    fun trackIndexAt(y: Float): Int {
        for (i in order) {
            if (rowHeights[i] <= 0f) continue
            val top = rowY(i)
            if (y >= top && y < top + rowHeights[i]) return i
        }
        return -1
    }

    fun hitTest(o: Offset): Hit {
        val p = project ?: return Hit.Empty
        if (o.y < rulerH) return Hit.Ruler
        val slop = 6f * dp
        for ((r, id) in transBtns) if (r.inflate(slop).contains(o)) return Hit.TransBtn(id)
        addBtn?.let { if (it.inflate(slop).contains(o)) return Hit.AddMedia }
        muteBtn?.let { if (it.inflate(slop).contains(o)) return Hit.MuteMain }
        addAudioBtn?.let { if (it.contains(o)) return Hit.AddAudio }
        val i = trackIndexAt(o.y)
        if (i < 0) return Hit.Empty
        val t = p.tracks[i]
        // Keyframe lane (below the clip and its sound strip): nearest keyframe of any clip.
        val laneTop = rowY(i) + bodyHs[i] + audioHs[i]
        if (keyHs[i] > 0f && o.y >= laneTop - handleW * 0.3f) {
            var best: Hit.KeyHit? = null
            var bestD = handleW * 1.3f
            for (c in t.clips) {
                if (xOf(c.endUs) < o.x - bestD || xOf(c.startUs) > o.x + bestD) continue
                for (m in c.keyMarks()) {
                    val d = abs(xOf(c.startUs + m.t) - o.x)
                    if (d < bestD) { bestD = d; best = Hit.KeyHit(c, m.t) }
                }
            }
            if (best != null) return best
        }
        // Trim handles of the selected clip take priority (with a generous touch zone).
        selectedId?.let { sid ->
            val c = t.clips.firstOrNull { it.id == sid }
            if (c != null) {
                val xs = xOf(c.startUs)
                val xe = xOf(c.endUs)
                val mid = (xs + xe) / 2f
                val zone = handleW * 1.5f
                if (o.x >= xs - zone && o.x <= min(xs + zone, mid)) return Hit.Handle(c, true)
                if (o.x <= xe + zone && o.x >= max(xe - zone, mid)) return Hit.Handle(c, false)
            }
        }
        val us = usAt(o.x)
        val c = t.clips.firstOrNull { us >= it.startUs && us < it.endUs } ?: return Hit.Empty
        return Hit.ClipHit(t, c)
    }
}

private class TimelineIcons(val volUp: VectorPainter, val volOff: VectorPainter, val add: VectorPainter)

@Composable
fun TimelineView(c: EditorController, modifier: Modifier = Modifier) {
    val p = c.project ?: return
    val pos by c.engine.position.collectAsState()
    val thumbsVersion by c.app.thumbnails.version.collectAsState()
    val wavesVersion by c.app.waveforms.version.collectAsState()
    val accent = LocalAccent.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val icons = TimelineIcons(
        rememberVectorPainter(Icons.AutoMirrored.Outlined.VolumeUp),
        rememberVectorPainter(Icons.AutoMirrored.Outlined.VolumeOff),
        rememberVectorPainter(Icons.Outlined.Add),
    )

    val geo = remember { TimelineGeometry() }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableStateOf<DragOp?>(null) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var trackMenu by remember { mutableStateOf(false) }
    val pro = c.proTrackHeaders

    val rulerH = with(density) { 26.dp.toPx() }
    val headerW = 74.dp
    // The main track = the lowest video track (where "+" appends media).
    val mainIdx = p.tracks.indexOfLast { it.kind == TrackKind.VIDEO }
    fun bodyDp(i: Int, t: Track) = when {
        i == mainIdx -> 58.dp
        t.kind == TrackKind.TEXT -> 34.dp
        t.kind == TrackKind.AUDIO -> 42.dp
        t.clips.all { it.kind == ClipKind.TEXT } -> 34.dp
        else -> 44.dp
    }
    // A visual track whose clips carry sound shows that sound as a waveform strip under them,
    // and any track with animated clips gets a keyframe lane below.
    fun audioDp(t: Track) = if (t.acceptsVisual && t.clips.any { cl -> cl.kind == ClipKind.MEDIA && p.asset(cl.assetId)?.hasAudio == true }) 16.dp else 0.dp
    fun keyDp(t: Track) = if (t.clips.any { it.keyTimes().isNotEmpty() }) 16.dp else 0.dp
    fun rowHeightDp(i: Int, t: Track) = when {
        t.clips.isEmpty() && i == mainIdx -> bodyDp(i, t)
        t.clips.isEmpty() -> if (pro) 24.dp else 0.dp
        else -> bodyDp(i, t) + audioDp(t) + keyDp(t)
    }
    val gapPx = with(density) { 6.dp.toPx() }
    val heights = p.tracks.mapIndexed { i, t -> with(density) { rowHeightDp(i, t).toPx() } }
    geo.bodyHs = p.tracks.mapIndexed { i, t -> with(density) { (if (t.clips.isEmpty() && i != mainIdx) 24.dp else bodyDp(i, t)).toPx() } }
    geo.audioHs = p.tracks.map { with(density) { audioDp(it).toPx() } }
    geo.keyHs = p.tracks.map { with(density) { keyDp(it).toPx() } }
    // Display order: classic NLE order in pro mode; otherwise main track first (like CapCut),
    // then layers above it (overlays, text), then sound.
    val order: List<Int> = if (pro) p.tracks.indices.toList() else buildList {
        if (mainIdx >= 0) add(mainIdx)
        p.tracks.indices.filter { it != mainIdx && p.tracks[it].kind != TrackKind.AUDIO }.asReversed().forEach { add(it) }
        p.tracks.indices.filter { p.tracks[it].kind == TrackKind.AUDIO }.forEach { add(it) }
    }
    val tops = FloatArray(p.tracks.size)
    run {
        var acc = gapPx
        for (i in order) {
            tops[i] = acc
            if (heights[i] > 0f) acc += heights[i] + gapPx
        }
        val showAddAudio = !pro && p.tracks.none { it.kind == TrackKind.AUDIO && it.clips.isNotEmpty() }
        geo.addAudioTop = acc
        geo.addAudioH = if (showAddAudio) with(density) { 34.dp.toPx() } else 0f
        geo.contentH = acc + geo.addAudioH + gapPx
    }

    // Refresh geometry for gesture handlers.
    geo.project = p
    geo.posUs = pos
    geo.pps = c.pps
    geo.rulerH = rulerH
    geo.dp = density.density
    geo.handleW = with(density) { 14.dp.toPx() }
    geo.selectedId = c.selectedClipId
    geo.rowHeights = heights
    geo.rowTops = tops.toList()
    geo.order = order
    geo.mainIdx = mainIdx
    geo.scrollY = scrollY

    val fps = p.settings.fps
    fun maxPps() = fps * 120f // one frame = 120 px at max zoom
    fun minPps(): Float {
        val dur = max(geo.project?.durationUs ?: 0L, 10_000_000L) / 1_000_000f
        return max(2f, geo.width * 0.85f / dur)
    }
    fun clampScroll(v: Float) = v.coerceIn(0f, max(0f, geo.contentH - (geo.height - rulerH) + gapPx * 4))

    Row(modifier.background(Amiri.Bg)) {
        // ───────── Track headers (pro mode only) ─────────
        if (pro) Box(Modifier.width(headerW).fillMaxHeight().clipToBounds()) {
            Box(Modifier.offset { IntOffset(0, (rulerH - scrollY).roundToInt()) }.wrapContentHeight(Alignment.Top, unbounded = true)) {
                order.forEach { i ->
                    val t = p.tracks[i]
                    if (heights[i] > 0f) Box(Modifier.offset { IntOffset(0, tops[i].roundToInt()) }) {
                        TrackHeader(
                            t, Modifier.height(rowHeightDp(i, t)).width(headerW),
                            onLock = { c.toggleTrackLock(t.id) },
                            onHide = { c.toggleTrackHidden(t.id) },
                            onMute = { c.toggleTrackMuted(t.id) },
                            onDelete = { c.removeTrack(t.id) },
                            compact = t.clips.isEmpty(),
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(26.dp).background(Amiri.Bg).clickable { trackMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, "Add track", tint = Amiri.TextSecondary, modifier = Modifier.size(14.dp))
                    Text("Track", color = Amiri.TextSecondary, fontSize = 10.sp)
                }
                DropdownMenu(expanded = trackMenu, onDismissRequest = { trackMenu = false }) {
                    DropdownMenuItem(text = { Text("Add video track") }, onClick = { trackMenu = false; c.addTrack(TrackKind.VIDEO) })
                    DropdownMenuItem(text = { Text("Add overlay track") }, onClick = { trackMenu = false; c.addTrack(TrackKind.OVERLAY) })
                    DropdownMenuItem(text = { Text("Add audio track") }, onClick = { trackMenu = false; c.addTrack(TrackKind.AUDIO) })
                }
            }
        }

        // ───────── Timeline canvas ─────────
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { geo.width = it.width.toFloat(); geo.height = it.height.toFloat() }
                .pointerInput(Unit) {
                    var lastTapTime = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val hit = geo.hitTest(down.position)
                        val slop = viewConfiguration.touchSlop
                        var phase = Phase.LONG
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) { phase = Phase.MULTI; break }
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed) { phase = Phase.TAP; break }
                                if ((ch.position - down.position).getDistance() > slop) { phase = Phase.DRAG; break }
                            }
                        }

                        // Shared helpers ------------------------------------------------
                        suspend fun AwaitPointerEventScope.pinch() {
                            c.engine.pause()
                            var fine = geo.posUs.toDouble()
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) break
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (zoom != 1f && zoom.isFinite()) {
                                    val np = (c.pps * zoom).coerceIn(minPps(), maxPps())
                                    if ((c.pps < maxPps() && np >= maxPps()) || (c.pps > minPps() && np <= minPps())) Haptics.tick(view)
                                    c.pps = np
                                    geo.pps = np
                                }
                                fine -= pan.x / geo.pps * 1_000_000.0
                                c.engine.seekTo(fine.toLong().coerceAtLeast(0))
                                scrollY = clampScroll(scrollY - pan.y)
                                ev.changes.forEach { it.consume() }
                            }
                        }

                        suspend fun AwaitPointerEventScope.scrub() {
                            c.engine.pause()
                            val vt = VelocityTracker()
                            var fine = geo.posUs.toDouble()
                            var last = down.position
                            vt.addPosition(down.uptimeMillis, down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) { pinch(); return }
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.position - last
                                last = ch.position
                                vt.addPosition(ch.uptimeMillis, ch.position)
                                fine -= d.x / geo.pps * 1_000_000.0
                                fine = fine.coerceIn(0.0, geo.project?.durationUs?.toDouble() ?: 0.0)
                                c.engine.seekTo(fine.toLong())
                                scrollY = clampScroll(scrollY - d.y)
                                ch.consume()
                            }
                            val vx = vt.calculateVelocity().x
                            if (abs(vx) > 400f) {
                                flingJob = scope.launch {
                                    var prev = 0f
                                    var f = fine
                                    Animatable(0f).animateDecay(-vx, exponentialDecay(frictionMultiplier = 1.6f)) {
                                        val delta = value - prev
                                        prev = value
                                        f = (f + delta / geo.pps * 1_000_000.0).coerceIn(0.0, geo.project?.durationUs?.toDouble() ?: 0.0)
                                        c.engine.seekTo(f.toLong())
                                    }
                                }
                            }
                        }

                        suspend fun AwaitPointerEventScope.trim(handle: Hit.Handle) {
                            val proj = geo.project ?: return
                            val clip = handle.clip
                            val track = proj.trackOfClip(clip.id) ?: return
                            if (track.locked || clip.locked) { Haptics.reject(view); c.toast = Toast("Clip is locked"); return }
                            c.engine.pause()
                            val edge0 = if (handle.start) clip.startUs else clip.endUs
                            val targets = TimelineOps.snapTargets(proj, clip.id, geo.posUs)
                            var wasSnapped = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val dxUs = ((ch.position.x - down.position.x) / geo.pps * 1_000_000.0).toLong()
                                var edge = edge0 + dxUs
                                var snapUs: Long? = null
                                if (c.app.settings.snapping) {
                                    val thr = (with(density) { 10.dp.toPx() } / geo.pps * 1_000_000.0).toLong()
                                    val (s, snapped) = TimelineOps.snap(edge, targets, thr)
                                    if (snapped) { edge = s; snapUs = s }
                                    if (snapped != wasSnapped) { if (snapped) Haptics.tick(view); wasSnapped = snapped }
                                }
                                val updated = if (handle.start) TimelineOps.trimmedStart(proj, clip.id, edge)
                                else TimelineOps.trimmedEnd(proj, clip.id, edge)
                                if (updated != null) drag = DragOp.Trim(clip.id, updated, snapUs)
                                ch.consume()
                            }
                            val op = drag as? DragOp.Trim
                            drag = null
                            if (op != null && op.clip != clip) {
                                Haptics.confirm(view)
                                c.commitClipEdit("Trim", op.clip)
                            }
                        }

                        suspend fun AwaitPointerEventScope.move(hitClip: Hit.ClipHit) {
                            val proj = geo.project ?: return
                            val clip = hitClip.clip
                            if (hitClip.track.locked || clip.locked) {
                                Haptics.reject(view); c.toast = Toast("Clip is locked"); c.select(clip.id)
                                return
                            }
                            c.engine.pause()
                            Haptics.heavy(view)
                            c.select(clip.id)
                            geo.selectedId = clip.id
                            val grab = geo.usAt(down.position.x) - clip.startUs
                            val targets = TimelineOps.snapTargets(proj, clip.id, geo.posUs)
                            val thr = (with(density) { 10.dp.toPx() } / geo.pps * 1_000_000.0).toLong()
                            var moved = false
                            var wasSnapped = false
                            drag = DragOp.Move(clip.id, hitClip.track.id, clip.startUs, proj, null)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if ((ch.position - down.position).getDistance() > slop) moved = true
                                var start = geo.usAt(ch.position.x) - grab
                                var snapUs: Long? = null
                                if (c.app.settings.snapping) {
                                    val (s1, ok1) = TimelineOps.snap(start, targets, thr)
                                    val (s2, ok2) = TimelineOps.snap(start + clip.durationUs, targets, thr)
                                    when {
                                        ok1 && (!ok2 || abs(s1 - start) <= abs(s2 - start - clip.durationUs)) -> { start = s1; snapUs = s1 }
                                        ok2 -> { start = s2 - clip.durationUs; snapUs = s2 }
                                    }
                                    val snapped = snapUs != null
                                    if (snapped != wasSnapped) { if (snapped) Haptics.tick(view); wasSnapped = snapped }
                                }
                                start = start.coerceAtLeast(0)
                                val ti = geo.trackIndexAt(ch.position.y)
                                val targetTrack = if (ti >= 0) proj.tracks[ti] else hitClip.track
                                val result = TimelineOps.move(proj, clip.id, targetTrack.id, start)
                                drag = DragOp.Move(clip.id, targetTrack.id, FrameTime.quantize(start, fps), result, snapUs)
                                ch.consume()
                            }
                            val op = drag as? DragOp.Move
                            drag = null
                            when {
                                !moved -> c.clipMenuFor = clip.id
                                op?.result != null -> { Haptics.confirm(view); c.commitMove(op.result) }
                                else -> { Haptics.reject(view); c.toast = Toast("No room there") }
                            }
                        }

                        suspend fun AwaitPointerEventScope.keyDrag(k: Hit.KeyHit) {
                            c.engine.pause()
                            var cur = k.t
                            var movedKey = false
                            val f = FrameTime.fromFrame(1, fps)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val dt = ((ch.position.x - down.position.x) / geo.pps * 1_000_000.0).toLong()
                                val nt = FrameTime.quantize((k.t + dt).coerceIn(0, k.clip.durationUs - f), fps)
                                if (nt != cur) { c.moveKeysAt(k.clip.id, cur, nt, live = true); cur = nt; movedKey = true; Haptics.tick(view) }
                                ch.consume()
                            }
                            if (movedKey) c.endEdit("Move keyframes")
                            else if (phase == Phase.LONG) {
                                // Long-press without moving → keyframe menu (ease / linear / hold / delete).
                                if (c.selectedClipId != k.clip.id) c.select(k.clip.id)
                                c.engine.seekTo(k.clip.startUs + k.t)
                                c.keyMenu = k.clip.id to k.t
                                Haptics.heavy(view)
                            }
                        }

                        suspend fun AwaitPointerEventScope.waitUp() {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) break
                            }
                        }

                        // Dispatch ------------------------------------------------------
                        when (phase) {
                            Phase.TAP -> {
                                val now = System.currentTimeMillis()
                                val isDouble = now - lastTapTime < viewConfiguration.doubleTapTimeoutMillis &&
                                    (down.position - lastTapPos).getDistance() < slop * 3
                                lastTapTime = now
                                lastTapPos = down.position
                                when (hit) {
                                    Hit.Ruler -> { c.engine.pause(); c.engine.seekTo(geo.usAt(down.position.x)); Haptics.tick(view) }
                                    Hit.AddMedia -> { Haptics.confirm(view); c.openAddMedia(Placement.APPEND_TO_MAIN) }
                                    Hit.AddAudio -> { Haptics.select(view); c.openMusicPicker() }
                                    Hit.MuteMain -> {
                                        val mt = geo.project?.tracks?.getOrNull(geo.mainIdx)
                                        if (mt != null) { Haptics.confirm(view); c.toggleTrackMuted(mt.id); c.toast = Toast(if (mt.muted) "Clip audio on" else "Clip audio muted") }
                                    }
                                    is Hit.TransBtn -> { Haptics.select(view); c.select(hit.clipId); c.activeTool = EditorTool.TRANSITION }
                                    is Hit.ClipHit -> { c.select(if (c.selectedClipId == hit.clip.id) null else hit.clip.id); Haptics.select(view) }
                                    is Hit.Handle -> Unit
                                    is Hit.KeyHit -> {
                                        c.engine.pause()
                                        if (c.selectedClipId != hit.clip.id) c.select(hit.clip.id)
                                        c.engine.seekTo(hit.clip.startUs + hit.t)
                                        if (isDouble) { c.toggleEaseAt(hit.clip.id, hit.t); Haptics.confirm(view) } else Haptics.select(view)
                                    }
                                    Hit.Empty -> {
                                        if (isDouble) {
                                            // Double tap → fit whole project
                                            c.pps = minPps().coerceAtMost(maxPps())
                                            Haptics.tick(view)
                                        } else c.select(null)
                                    }
                                }
                            }
                            Phase.DRAG -> when (hit) {
                                is Hit.Handle -> trim(hit)
                                is Hit.KeyHit -> keyDrag(hit)
                                else -> scrub()
                            }
                            Phase.LONG -> when (hit) {
                                is Hit.KeyHit -> keyDrag(hit)
                                is Hit.Handle -> trim(hit)
                                is Hit.ClipHit -> move(hit)
                                Hit.Ruler -> {
                                    Haptics.heavy(view)
                                    c.addMarker(geo.usAt(down.position.x))
                                    waitUp()
                                }
                                Hit.Empty -> {
                                    val ti = geo.trackIndexAt(down.position.y)
                                    if (ti >= 0) { Haptics.heavy(view); c.trackMenuFor = geo.project?.tracks?.getOrNull(ti)?.id; waitUp() } else scrub()
                                }
                                else -> waitUp()
                            }
                            Phase.MULTI -> pinch()
                        }
                    }
                },
        ) {
            geo.width = size.width
            geo.height = size.height
            drawTimeline(
                geo = geo, project = p, drag = drag, accent = accent,
                measurer = measurer, c = c, icons = icons,
                // read versions so new thumbnails/waveforms trigger a redraw
                versions = thumbsVersion + wavesVersion,
            )
        }
    }
}

@Composable
private fun TrackHeader(t: Track, modifier: Modifier, onLock: () -> Unit, onHide: () -> Unit, onMute: () -> Unit, onDelete: () -> Unit, compact: Boolean = false) {
    if (compact) {
        Row(modifier.background(Amiri.Surface).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, color = Amiri.TextTertiary, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Close, "Delete track", tint = Amiri.TextTertiary, modifier = Modifier.size(16.dp).clickable(onClick = onDelete).padding(2.dp))
        }
        return
    }
    val small = t.kind == TrackKind.TEXT
    Column(
        modifier.background(Amiri.Surface).padding(horizontal = 6.dp, vertical = if (small) 2.dp else 5.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.name, color = if (t.hidden) Amiri.TextTertiary else Amiri.TextSecondary,
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.Close, "Delete track", tint = Amiri.TextTertiary,
                modifier = Modifier.size(16.dp).clickable(onClick = onDelete).padding(2.dp),
            )
        }
        Row {
            HeaderIcon(if (t.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, t.locked, onLock)
            if (t.kind != TrackKind.AUDIO) HeaderIcon(if (t.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, t.hidden, onHide)
            if (t.kind == TrackKind.AUDIO || t.kind == TrackKind.VIDEO || t.kind == TrackKind.OVERLAY) {
                HeaderIcon(if (t.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, t.muted, onMute)
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val view = LocalView.current
    Box(
        Modifier.size(20.dp).clickable { Haptics.select(view); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (active) accent else Amiri.TextTertiary, modifier = Modifier.size(14.dp))
    }
}

// ───────────────────────────── drawing ─────────────────────────────

private fun laneColor(track: Track, clip: Clip): Color = when {
    clip.kind == ClipKind.TEXT -> Amiri.ClipText
    clip.kind == ClipKind.SHAPE -> Amiri.ClipShape
    clip.kind == ClipKind.ADJUSTMENT -> Amiri.ClipAdjust
    track.kind == TrackKind.AUDIO -> Amiri.ClipAudio
    track.kind == TrackKind.OVERLAY -> Amiri.ClipOverlay
    else -> Amiri.ClipVideo
}

private fun DrawScope.drawTimeline(
    geo: TimelineGeometry,
    project: Project,
    drag: DragOp?,
    accent: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    c: EditorController,
    icons: TimelineIcons,
    @Suppress("UNUSED_PARAMETER") versions: Int,
) {
    val w = size.width
    val dp = geo.dp
    val rulerH = geo.rulerH
    val fps = project.settings.fps
    val buttons = ArrayList<Pair<Rect, String>>()
    geo.addBtn = null; geo.muteBtn = null; geo.addAudioBtn = null

    // Tracks area
    clipRect(top = rulerH) {
        for (i in geo.order) {
            val t = project.tracks[i]
            val h = geo.rowHeights[i]
            if (h <= 0f) continue
            val y = geo.rowY(i)
            if (y > size.height || y + h < rulerH) continue
            if (t.clips.isEmpty()) {
                if (i == geo.mainIdx) {
                    // Empty project: one big "Add media" target where the main track will be.
                    val bw = 168f * dp; val bh = geo.bodyHs[i] - 8f * dp
                    val r = Rect(Offset(geo.xOf(0) + 8f * dp, y + 4f * dp), Size(bw, bh))
                    drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(10f * dp))
                    translate(r.left + 14f * dp, r.top + (bh - 22f * dp) / 2f) { with(icons.add) { draw(Size(22f * dp, 22f * dp), colorFilter = ColorFilter.tint(Color.Black)) } }
                    val lay = measurer.measure("Add media", TextStyle(color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AmiriFont))
                    drawText(lay, topLeft = Offset(r.left + 44f * dp, r.top + (bh - lay.size.height) / 2f))
                    geo.addBtn = r
                } else drawRoundRect(Color.White.copy(alpha = 0.03f), Offset(0f, y), Size(w, h), CornerRadius(4f * dp))
                continue
            }
            for (clip in t.clips) {
                if (drag != null && drag.clipId == clip.id) continue
                drawClip(geo, project, t, clip, y, geo.bodyHs[i], geo.audioHs[i], geo.keyHs[i], accent, measurer, c, ghost = false, invalid = false)
            }
            // Transition buttons on every butt cut of this track.
            for (k in 1 until t.clips.size) {
                val a = t.clips[k - 1]; val b = t.clips[k]
                if (abs(a.endUs - b.startUs) > 1_000) continue
                val x = geo.xOf(b.startUs)
                if (x < -20f * dp || x > w + 20f * dp) continue
                val s = (if (i == geo.mainIdx) 22f else 18f) * dp
                val r = Rect(Offset(x - s / 2f, y + geo.bodyHs[i] / 2f - s / 2f), Size(s, s))
                val has = b.transIn != null
                drawRoundRect(if (has) accent else Color.White, r.topLeft, r.size, CornerRadius(5f * dp))
                drawRoundRect(Color.Black.copy(alpha = 0.35f), r.topLeft, r.size, CornerRadius(5f * dp), style = Stroke(1f * dp))
                // bow-tie glyph
                val gx = r.center.x; val gy = r.center.y; val gw = s * 0.26f; val gh = s * 0.22f
                val bow = Path().apply { moveTo(gx - gw, gy - gh); lineTo(gx + gw, gy + gh); lineTo(gx + gw, gy - gh); lineTo(gx - gw, gy + gh); close() }
                drawPath(bow, if (has) Color.Black.copy(alpha = 0.8f) else Color(0xFF26262A))
                buttons += r to b.id
            }
            // Mute switch + add button around the main track.
            if (i == geo.mainIdx) {
                val bh = geo.bodyHs[i]
                val first = t.clips.first()
                val mx = geo.xOf(first.startUs) - 50f * dp
                if (mx > -40f * dp) {
                    val r = Rect(Offset(mx, y + bh / 2f - 19f * dp), Size(38f * dp, 38f * dp))
                    drawRoundRect(Amiri.SurfaceHigh, r.topLeft, r.size, CornerRadius(10f * dp))
                    val ic = if (t.muted) icons.volOff else icons.volUp
                    translate(r.left + 9f * dp, r.top + 9f * dp) { with(ic) { draw(Size(20f * dp, 20f * dp), colorFilter = ColorFilter.tint(if (t.muted) Amiri.Danger else Amiri.TextPrimary)) } }
                    geo.muteBtn = r
                }
                val ex = geo.xOf(t.clips.last().endUs) + 12f * dp
                if (ex < w + 10f * dp) {
                    val s = min(40f * dp, bh - 8f * dp)
                    val r = Rect(Offset(ex, y + bh / 2f - s / 2f), Size(s, s))
                    drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(9f * dp))
                    translate(r.left + (s - 24f * dp) / 2f, r.top + (s - 24f * dp) / 2f) { with(icons.add) { draw(Size(24f * dp, 24f * dp), colorFilter = ColorFilter.tint(Color.Black)) } }
                    geo.addBtn = r
                }
            }
            if (t.locked) drawHatch(Offset(0f, y), Size(w, h))
            if (t.hidden) drawRect(Color.Black.copy(alpha = 0.5f), Offset(0f, y), Size(w, h))
        }

        // "+ Add audio" lane (until there is music).
        if (geo.addAudioH > 0f) {
            val y = geo.rulerH + geo.addAudioTop - geo.scrollY
            val left = max(geo.xOf(0), 8f * dp)
            val r = Rect(Offset(left, y), Size(max(w - left - 8f * dp, 140f * dp), geo.addAudioH))
            drawRoundRect(Amiri.SurfaceHigh, r.topLeft, r.size, CornerRadius(8f * dp))
            val lay = measurer.measure("＋  Add audio", TextStyle(color = Amiri.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = AmiriFont))
            drawText(lay, topLeft = Offset(r.left + 12f * dp, r.top + (r.height - lay.size.height) / 2f))
            geo.addAudioBtn = r
        }

        // Drag ghost
        when (drag) {
            is DragOp.Move -> {
                val ti = project.tracks.indexOfFirst { it.id == drag.targetTrackId }
                val src = project.clip(drag.clipId)
                if (ti >= 0 && src != null && geo.rowHeights[ti] > 0f) {
                    val ghost = src.copy(startUs = drag.startUs)
                    drawClip(geo, project, project.tracks[ti], ghost, geo.rowY(ti), geo.bodyHs[ti], geo.audioHs[ti], geo.keyHs[ti], accent, measurer, c, ghost = true, invalid = drag.result == null)
                }
            }
            is DragOp.Trim -> {
                val ti = project.tracks.indexOfFirst { t -> t.clips.any { it.id == drag.clipId } }
                if (ti >= 0) drawClip(geo, project, project.tracks[ti], drag.clip, geo.rowY(ti), geo.bodyHs[ti], geo.audioHs[ti], geo.keyHs[ti], accent, measurer, c, ghost = true, invalid = false)
            }
            null -> Unit
        }

        // Marker lines (beats thinner and gold)
        for (m in project.markers) {
            val x = geo.xOf(m.timeUs)
            if (x < 0 || x > w) continue
            if (m.label == "♪") drawLine(Color(0xFFFFD27A).copy(alpha = 0.16f), Offset(x, rulerH), Offset(x, size.height), 1f)
            else drawLine(accent.copy(alpha = 0.25f), Offset(x, rulerH), Offset(x, size.height), 1f)
        }
    }
    geo.transBtns = buttons

    // Ruler
    drawRect(Amiri.Bg, Offset.Zero, Size(w, rulerH))
    drawRuler(geo, fps, measurer, rulerH)
    for (m in project.markers) {
        val x = geo.xOf(m.timeUs)
        if (x < -10 || x > w + 10) continue
        if (m.label == "♪") {
            drawCircle(Color(0xFFFFD27A), 3.2f, Offset(x, rulerH * 0.82f))
            continue
        }
        val path = Path().apply {
            moveTo(x - 5f, 2f); lineTo(x + 5f, 2f); lineTo(x + 5f, rulerH * 0.45f); lineTo(x, rulerH * 0.62f); lineTo(x - 5f, rulerH * 0.45f); close()
        }
        drawPath(path, accent.copy(alpha = 0.9f))
    }

    // Snap guide
    val snap = when (drag) { is DragOp.Move -> drag.snapUs; is DragOp.Trim -> drag.snapUs; null -> null }
    if (snap != null) {
        val x = geo.xOf(snap)
        drawLine(Color(0xFFFFD27A), Offset(x, 0f), Offset(x, size.height), 1.5f * dp)
    }

    // Playhead (fixed at center; the timeline scrolls beneath it)
    val cx = geo.centerX
    drawLine(Color.White, Offset(cx, rulerH * 0.35f), Offset(cx, size.height), 2f * dp)
    paw(Offset(cx, rulerH * 0.34f), rulerH * 0.62f, accent)
}

private fun DrawScope.drawHatch(o: Offset, s: Size) {
    clipRect(o.x, o.y, o.x + s.width, o.y + s.height) {
        var x = o.x - s.height
        while (x < o.x + s.width) {
            drawLine(Color.White.copy(alpha = 0.05f), Offset(x, o.y + s.height), Offset(x + s.height, o.y), 1.5f)
            x += 14f
        }
    }
}

private fun DrawScope.drawClip(
    geo: TimelineGeometry,
    project: Project,
    track: Track,
    clip: Clip,
    rowY: Float,
    rowH: Float,
    soundH: Float,
    keyH: Float,
    accent: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    c: EditorController,
    ghost: Boolean,
    invalid: Boolean,
) {
    val w = size.width
    val dp = geo.dp
    val x0 = geo.xOf(clip.startUs)
    val x1 = geo.xOf(clip.endUs)
    if (x1 < -4f || x0 > w + 4f) return
    val gap = 1f * dp
    val top = rowY
    val h = rowH
    val left = max(x0 + gap, -8f)
    val right = min(x1 - gap, w + 8f)
    if (right <= left) return
    val rr = CornerRadius(7f * dp, 7f * dp)
    val selected = clip.id == geo.selectedId
    val asset = project.asset(clip.assetId)
    val missing = asset != null && clip.assetId in c.missingAssets
    val lane = laneColor(track, clip)
    val isMediaVisual = clip.kind == ClipKind.MEDIA && track.kind != TrackKind.AUDIO
    val bodyColor = when {
        isMediaVisual -> Color(0xFF15171C)
        track.kind == TrackKind.AUDIO -> lane.copy(alpha = 0.26f)
        else -> lane
    }
    val roundPath = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, right, top + h, rr)) }
    drawPath(roundPath, bodyColor.copy(alpha = if (ghost) 0.75f * bodyColor.alpha else bodyColor.alpha))

    clipPath(roundPath) {
        if (asset != null && isMediaVisual) {
            val strip = c.app.thumbnails.strip(asset.id)
            if (strip != null) {
                val tileH = h
                val tileW = (tileH * strip.aspect.coerceIn(0.4f, 2.4f)).coerceAtLeast(12f)
                val firstK = floor((max(0f, -x0)) / tileW).toInt()
                val lastK = ceil((min(x1, w) - x0) / tileW).toInt()
                for (k in firstK..lastK) {
                    val tx = x0 + k * tileW
                    if (tx > x1) break
                    val centerUs = geo.usAt(tx + tileW / 2f).coerceIn(clip.startUs, clip.endUs - 1)
                    val img = strip.frameAt(clip.sourceTimeAt(centerUs)) ?: continue
                    drawImage(
                        img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(tx.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(ceil(tileW).toInt() + 1, h.roundToInt()),
                        alpha = if (ghost) 0.6f else 1f,
                    )
                }
            }
            if (track.kind == TrackKind.OVERLAY) drawRect(Brush.verticalGradient(listOf(Color.Transparent, lane.copy(alpha = 0.35f)), top, top + h), Offset(left, top), Size(right - left, h))
        }
        if (asset != null && track.kind == TrackKind.AUDIO && asset.hasAudio) {
            val wf = c.app.waveforms.get(asset.id)
            val col = Amiri.ClipAudio.copy(alpha = if (track.muted || clip.muted) 0.35f else 0.95f)
            if (wf != null) {
                val mid = top + h * 0.56f
                val amp = h * 0.36f
                var x = max(left, 0f)
                val end = min(right, w)
                val step = 3f * dp
                while (x < end) {
                    val t0 = geo.usAt(x).coerceIn(clip.startUs, clip.endUs)
                    val t1 = geo.usAt(x + step).coerceIn(clip.startUs, clip.endUs)
                    val pk = wf.peak(clip.sourceTimeAt(t0), clip.sourceTimeAt(t1))
                    val bh = max(1f * dp, pk * amp)
                    drawLine(col, Offset(x, mid - bh), Offset(x, mid + bh), 1.6f * dp)
                    x += step
                }
            } else {
                drawLine(col.copy(alpha = 0.4f), Offset(left, top + h * 0.56f), Offset(right, top + h * 0.56f), 1f)
            }
        }

        // Label
        val label = buildString {
            if (clip.locked) append("🔒 ")
            when {
                clip.kind == ClipKind.TEXT -> append("T  ")
                clip.kind == ClipKind.ADJUSTMENT -> append("◇ ")
                track.kind == TrackKind.AUDIO -> append("♪  ")
                else -> Unit
            }
            if (clip.kind == ClipKind.TEXT) append(clip.text?.text?.lineSequence()?.firstOrNull()?.ifBlank { clip.name } ?: clip.name) else append(clip.name)
            if (missing) append(" · MISSING")
        }
        val onLane = clip.kind == ClipKind.TEXT || clip.kind == ClipKind.SHAPE || clip.kind == ClipKind.ADJUSTMENT
        val textCol = when {
            missing -> Amiri.Danger
            onLane -> Color(0xFF17110A)
            else -> Color.White
        }
        val showLabel = !isMediaVisual || track.kind == TrackKind.OVERLAY || (right - left) > 220f * dp
        val textX = max(left, 0f) + 8f * dp
        val avail = (min(right, w) - textX - 6f * dp).toInt()
        if (showLabel && avail > 24 && !(isMediaVisual && !selected && track.kind != TrackKind.OVERLAY)) {
            val layout = measurer.measure(
                label,
                style = TextStyle(color = textCol, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = AmiriFont,
                    shadow = if (onLane) null else androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 3f)),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = avail),
            )
            val ly = if (onLane || track.kind == TrackKind.AUDIO) top + (if (track.kind == TrackKind.AUDIO) 4f * dp else (h - layout.size.height) / 2f) else top + h - layout.size.height - 4f * dp
            drawText(layout, topLeft = Offset(textX, ly))
        }

        // Duration badge (selected) and speed badge.
        val badges = buildList {
            if (selected) add(String.format(java.util.Locale.US, "%.1fs", clip.durationUs / 1_000_000.0))
            if (clip.speed != 1f || clip.ramp != null) add(if (clip.ramp != null) "curve" else String.format(java.util.Locale.US, "%.2g×", clip.speed))
            if (clip.effects.isNotEmpty()) add("fx ${clip.effects.size}")
            if (clip.roto != null && clip.roto.keys.isNotEmpty()) add(if (clip.roto.enabled) "cut-out" else "cut-out off")
        }
        var bx = max(left, 0f) + 5f * dp
        for (b in badges) {
            val lay = measurer.measure(b, TextStyle(color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = AmiriFont))
            val bw = lay.size.width + 10f * dp
            if (bx + bw > min(right, w) - 4f * dp) break
            drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(bx, top + 4f * dp), Size(bw, lay.size.height + 3f * dp), CornerRadius(5f * dp))
            drawText(lay, topLeft = Offset(bx + 5f * dp, top + 5.5f * dp))
            bx += bw + 4f * dp
        }
    }

    if (missing) drawPath(roundPath, Amiri.Danger.copy(alpha = 0.18f))
    if (invalid) drawPath(roundPath, Amiri.Danger.copy(alpha = 0.35f))

    when {
        invalid -> drawPath(roundPath, Amiri.Danger, style = Stroke(2f * dp))
        selected || ghost -> drawPath(roundPath, Color.White, style = Stroke(2f * dp))
        isMediaVisual && track.kind == TrackKind.OVERLAY -> drawPath(roundPath, Amiri.ClipOverlay.copy(alpha = 0.9f), style = Stroke(1.5f * dp))
        else -> drawPath(roundPath, Color.White.copy(alpha = 0.06f), style = Stroke(1f))
    }

    // Attached sound: waveform strip right under the picture (louder = taller).
    if (soundH > 0f && asset != null && asset.hasAudio && track.kind != TrackKind.AUDIO) {
        val st = rowY + rowH + 2f * dp
        val sh = soundH - 3f * dp
        val dim = clip.muted || track.muted
        val col = Amiri.ClipAudio.copy(alpha = if (dim) 0.25f else 0.85f)
        drawRoundRect(Amiri.ClipAudio.copy(alpha = if (ghost) 0.08f else 0.14f), Offset(left, st), Size(right - left, sh), CornerRadius(4f * dp))
        clipRect(max(left, 0f), st, min(right, w), st + sh) {
            val wf = c.app.waveforms.get(asset.id)
            val base = st + sh - 1f * dp
            if (wf != null) {
                var x = max(left, 0f)
                val end = min(right, w)
                val step = 2f * dp
                val path = Path().apply { moveTo(x, base) }
                while (x <= end) {
                    val t0 = geo.usAt(x).coerceIn(clip.startUs, clip.endUs)
                    val t1 = geo.usAt(x + step).coerceIn(clip.startUs, clip.endUs)
                    val g = com.amiri.cut.engine.PreviewEngine.gainAt(track.copy(muted = false), clip.copy(muted = false), t0).coerceIn(0f, 2f)
                    val pk = (wf.peak(clip.sourceTimeAt(t0), clip.sourceTimeAt(t1)) * g).coerceIn(0f, 1f)
                    path.lineTo(x, base - max(0.8f, pk * (sh - 3f * dp)))
                    x += step
                }
                path.lineTo(end, base); path.close()
                drawPath(path, col)
            } else {
                drawLine(col, Offset(left, base), Offset(right, base), 1f)
            }
        }
    }

    // Keyframe lane: every keyframe of the layer. Linear = diamond, eased side = hourglass half,
    // hold = square half (like After Effects).
    if (keyH > 0f && !ghost) {
        val marks = clip.keyMarks()
        if (marks.isNotEmpty()) {
            val lt = rowY + rowH + soundH
            val ky = lt + keyH / 2f
            val d = min(6f * dp, keyH * 0.36f)
            val keyCol = if (selected) Color(0xFFFFD27A) else Color(0xFFBFC3CC)
            for (k in 0 until marks.size - 1) {
                val xa = geo.xOf(clip.startUs + marks[k].t); val xb = geo.xOf(clip.startUs + marks[k + 1].t)
                drawLine(keyCol.copy(alpha = 0.35f), Offset(xa, ky), Offset(xb, ky), 1.2f * dp)
            }
            for (m in marks) {
                val kx = geo.xOf(clip.startUs + m.t)
                if (kx < left - d || kx > right + d) continue
                val shape = Path().apply {
                    moveTo(kx, ky - d)
                    if (m.easeIn) { lineTo(kx - d, ky - d); lineTo(kx - d * 0.25f, ky); lineTo(kx - d, ky + d) }
                    else lineTo(kx - d, ky)
                    lineTo(kx, ky + d)
                    when {
                        m.hold -> { lineTo(kx + d * 0.85f, ky + d); lineTo(kx + d * 0.85f, ky - d) }
                        m.easeOut -> { lineTo(kx + d, ky + d); lineTo(kx + d * 0.25f, ky); lineTo(kx + d, ky - d) }
                        else -> lineTo(kx + d, ky)
                    }
                    close()
                }
                drawPath(shape, keyCol)
                drawPath(shape, Color.Black.copy(alpha = 0.7f), style = Stroke(1.2f))
            }
        }
    }

    // Trim handles (white, CapCut style)
    if (selected && !ghost) {
        val hw = geo.handleW
        val locked = clip.locked || track.locked
        val handleColor = if (locked) Amiri.TextTertiary else Color.White
        val grip = Color.Black.copy(alpha = 0.55f)
        if (x0 > -hw) {
            drawRoundRect(handleColor, Offset(x0 - hw + 2f * dp, top), Size(hw, h), CornerRadius(6f * dp, 6f * dp))
            drawLine(grip, Offset(x0 - hw / 2f + 2f * dp, top + h * 0.32f), Offset(x0 - hw / 2f + 2f * dp, top + h * 0.68f), 2f * dp)
        }
        if (x1 < w + hw) {
            drawRoundRect(handleColor, Offset(x1 - 2f * dp, top), Size(hw, h), CornerRadius(6f * dp, 6f * dp))
            drawLine(grip, Offset(x1 + hw / 2f - 2f * dp, top + h * 0.32f), Offset(x1 + hw / 2f - 2f * dp, top + h * 0.68f), 2f * dp)
        }
    }
}

private fun DrawScope.drawRuler(geo: TimelineGeometry, fps: Int, measurer: androidx.compose.ui.text.TextMeasurer, rulerH: Float) {
    val w = size.width
    val pps = geo.pps
    val frameUs = FrameTime.frameDurationUs(fps)
    // Candidate label intervals: whole frames first, then seconds.
    val frameSteps = listOf(1, 2, 5, 10).filter { it < fps }.map { it * frameUs }
    val secSteps = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800).map { it * 1_000_000.0 }
    val minLabelPx = 78f * geo.dp / 2.6f
    val major = (frameSteps + secSteps).firstOrNull { it / 1_000_000.0 * pps >= minLabelPx * 2.6f } ?: secSteps.last()
    val majorIsFrames = major < 1_000_000.0
    val minor = major / 2.0

    val tStart = max(0.0, geo.usAt(0f).toDouble())
    val tEnd = geo.usAt(w).toDouble()
    var i = floor(tStart / minor).toLong()
    val style = TextStyle(color = Amiri.TextTertiary, fontSize = 10.sp, fontFamily = AmiriFont, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    while (true) {
        val t = i * minor
        if (t > tEnd) break
        if (t >= 0) {
            val x = geo.xOf(t.toLong())
            val isMajor = i % 2 == 0L
            if (isMajor) {
                val us = t.roundToLong()
                val text = if (majorIsFrames) {
                    val f = ((us % 1_000_000L) * fps / 1_000_000L)
                    if (f == 0L) FrameTime.shortClock(us) else "${f}f"
                } else FrameTime.shortClock(us)
                val layout = measurer.measure(text, style)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, (rulerH - layout.size.height) / 2f))
            } else {
                drawCircle(Amiri.TextTertiary.copy(alpha = 0.8f), 1.6f * geo.dp, Offset(x, rulerH / 2f))
            }
        }
        i++
    }
}

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
