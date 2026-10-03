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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.Track
import com.amiri.cut.core.model.TrackKind
import com.amiri.cut.core.time.FrameTime
import com.amiri.cut.core.timeline.TimelineOps
import com.amiri.cut.ui.theme.Amiri
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
    data class ClipHit(val track: Track, val clip: Clip) : Hit
    data class Handle(val clip: Clip, val start: Boolean) : Hit
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
    var selectedId: String? = null
    var rowHeights: List<Float> = emptyList()
    var rowTops: List<Float> = emptyList()
    val contentH: Float get() = (rowTops.lastOrNull() ?: 0f) + (rowHeights.lastOrNull() ?: 0f)

    val centerX: Float get() = width / 2f
    fun xOf(us: Long): Float = centerX + ((us - posUs) / 1_000_000.0 * pps).toFloat()
    fun usAt(x: Float): Long = posUs + ((x - centerX) / pps * 1_000_000.0).toLong()
    fun rowY(i: Int): Float = rulerH + rowTops[i] - scrollY

    fun trackIndexAt(y: Float): Int {
        val p = project ?: return -1
        for (i in p.tracks.indices) {
            val top = rowY(i)
            if (y >= top && y < top + rowHeights[i]) return i
        }
        return -1
    }

    fun hitTest(o: Offset): Hit {
        val p = project ?: return Hit.Empty
        if (o.y < rulerH) return Hit.Ruler
        val i = trackIndexAt(o.y)
        if (i < 0) return Hit.Empty
        val t = p.tracks[i]
        // Trim handles of the selected clip take priority (with a generous touch zone).
        selectedId?.let { sid ->
            val c = t.clips.firstOrNull { it.id == sid }
            if (c != null) {
                val xs = xOf(c.startUs)
                val xe = xOf(c.endUs)
                val mid = (xs + xe) / 2f
                val zone = handleW * 1.4f
                if (o.x >= xs - zone && o.x <= min(xs + zone, mid)) return Hit.Handle(c, true)
                if (o.x <= xe + zone && o.x >= max(xe - zone, mid)) return Hit.Handle(c, false)
            }
        }
        val us = usAt(o.x)
        val c = t.clips.firstOrNull { us >= it.startUs && us < it.endUs } ?: return Hit.Empty
        return Hit.ClipHit(t, c)
    }
}

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
    val measurer = rememberTextMeasurer(cacheSize = 64)

    val geo = remember { TimelineGeometry() }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableStateOf<DragOp?>(null) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var trackMenu by remember { mutableStateOf(false) }

    val rulerH = with(density) { 26.dp.toPx() }
    val headerW = 74.dp
    fun rowHeightDp(kind: TrackKind) = when (kind) {
        TrackKind.VIDEO, TrackKind.OVERLAY -> 54.dp
        TrackKind.TEXT -> 36.dp
        TrackKind.AUDIO -> 44.dp
    }
    val gapPx = with(density) { 3.dp.toPx() }
    val heights = p.tracks.map { with(density) { rowHeightDp(it.kind).toPx() } }
    val tops = run {
        var acc = 0f
        heights.map { h -> val t = acc; acc += h + gapPx; t }
    }

    // Refresh geometry for gesture handlers.
    geo.project = p
    geo.posUs = pos
    geo.pps = c.pps
    geo.rulerH = rulerH
    geo.handleW = with(density) { 12.dp.toPx() }
    geo.selectedId = c.selectedClipId
    geo.rowHeights = heights
    geo.rowTops = tops
    geo.scrollY = scrollY

    val fps = p.settings.fps
    fun maxPps() = fps * 120f // one frame = 120 px at max zoom
    fun minPps(): Float {
        val dur = max(geo.project?.durationUs ?: 0L, 10_000_000L) / 1_000_000f
        return max(2f, geo.width * 0.85f / dur)
    }
    fun clampScroll(v: Float) = v.coerceIn(0f, max(0f, geo.contentH - (geo.height - rulerH) + gapPx * 4))

    Row(modifier.background(Color(0xFF0D0D0F))) {
        // ───────── Track headers ─────────
        Box(Modifier.width(headerW).fillMaxHeight().clipToBounds()) {
            Column(
                Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .offset { IntOffset(0, (rulerH - scrollY).roundToInt()) },
            ) {
                p.tracks.forEachIndexed { i, t ->
                    TrackHeader(
                        t, Modifier.height(rowHeightDp(t.kind)).fillMaxWidth(),
                        onLock = { c.toggleTrackLock(t.id) },
                        onHide = { c.toggleTrackHidden(t.id) },
                        onMute = { c.toggleTrackMuted(t.id) },
                    )
                    if (i < p.tracks.lastIndex) Box(Modifier.height(3.dp))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(26.dp).background(Color(0xFF0D0D0F))
                    .clickable { trackMenu = true },
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
                                    is Hit.ClipHit -> { c.select(hit.clip.id); Haptics.select(view) }
                                    is Hit.Handle -> Unit
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
                                else -> scrub()
                            }
                            Phase.LONG -> when (hit) {
                                is Hit.Handle -> trim(hit)
                                is Hit.ClipHit -> move(hit)
                                Hit.Ruler -> {
                                    Haptics.heavy(view)
                                    c.addMarker(geo.usAt(down.position.x))
                                    waitUp()
                                }
                                Hit.Empty -> scrub()
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
                measurer = measurer, c = c, gapPx = gapPx,
                // read versions so new thumbnails/waveforms trigger a redraw
                versions = thumbsVersion + wavesVersion,
            )
        }
    }
}

@Composable
private fun TrackHeader(t: Track, modifier: Modifier, onLock: () -> Unit, onHide: () -> Unit, onMute: () -> Unit) {
    val small = t.kind == TrackKind.TEXT
    Column(
        modifier.background(Color(0xFF131316)).padding(horizontal = 6.dp, vertical = if (small) 2.dp else 5.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(
            t.name, color = if (t.hidden) Amiri.TextTertiary else Amiri.TextSecondary,
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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

private val RulerBg = Color(0xFF111114)
private val RowA = Color(0xFF121215)
private val RowB = Color(0xFF101013)

private fun clipColor(kind: TrackKind): Color = when (kind) {
    TrackKind.VIDEO -> Amiri.ClipVideo
    TrackKind.OVERLAY -> Amiri.ClipOverlay
    TrackKind.TEXT -> Amiri.ClipText
    TrackKind.AUDIO -> Amiri.ClipAudio
}

private fun DrawScope.drawTimeline(
    geo: TimelineGeometry,
    project: Project,
    drag: DragOp?,
    accent: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    c: EditorController,
    gapPx: Float,
    @Suppress("UNUSED_PARAMETER") versions: Int,
) {
    val w = size.width
    val rulerH = geo.rulerH
    val fps = project.settings.fps

    // Tracks area
    clipRect(top = rulerH) {
        project.tracks.forEachIndexed { i, t ->
            val y = geo.rowY(i)
            val h = geo.rowHeights[i]
            if (y > size.height || y + h < rulerH) return@forEachIndexed
            drawRect(if (i % 2 == 0) RowA else RowB, Offset(0f, y), Size(w, h))
            for (clip in t.clips) {
                if (drag != null && drag.clipId == clip.id) continue
                drawClip(geo, project, t, clip, y, h, accent, measurer, c, ghost = false, invalid = false)
            }
            if (t.locked) drawHatch(Offset(0f, y), Size(w, h))
            if (t.hidden) drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, y), Size(w, h))
        }

        // Drag ghost
        when (drag) {
            is DragOp.Move -> {
                val ti = project.tracks.indexOfFirst { it.id == drag.targetTrackId }
                val src = project.clip(drag.clipId)
                if (ti >= 0 && src != null) {
                    val ghost = src.copy(startUs = drag.startUs)
                    drawClip(geo, project, project.tracks[ti], ghost, geo.rowY(ti), geo.rowHeights[ti], accent, measurer, c, ghost = true, invalid = drag.result == null)
                }
            }
            is DragOp.Trim -> {
                val ti = project.tracks.indexOfFirst { t -> t.clips.any { it.id == drag.clipId } }
                if (ti >= 0) drawClip(geo, project, project.tracks[ti], drag.clip, geo.rowY(ti), geo.rowHeights[ti], accent, measurer, c, ghost = true, invalid = false)
            }
            null -> Unit
        }

        // Marker lines
        for (m in project.markers) {
            val x = geo.xOf(m.timeUs)
            if (x < 0 || x > w) continue
            drawLine(accent.copy(alpha = 0.22f), Offset(x, rulerH), Offset(x, size.height), 1f)
        }
    }

    // Ruler
    drawRect(RulerBg, Offset.Zero, Size(w, rulerH))
    drawRuler(geo, fps, measurer, rulerH)
    for (m in project.markers) {
        val x = geo.xOf(m.timeUs)
        if (x < -10 || x > w + 10) continue
        val path = Path().apply {
            moveTo(x - 5f, 2f); lineTo(x + 5f, 2f); lineTo(x + 5f, rulerH * 0.45f); lineTo(x, rulerH * 0.62f); lineTo(x - 5f, rulerH * 0.45f); close()
        }
        drawPath(path, accent.copy(alpha = 0.85f))
    }
    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, rulerH), Offset(w, rulerH), 1f)

    // Snap guide
    val snap = when (drag) { is DragOp.Move -> drag.snapUs; is DragOp.Trim -> drag.snapUs; null -> null }
    if (snap != null) {
        val x = geo.xOf(snap)
        drawLine(Color(0xFFFFD27A), Offset(x, 0f), Offset(x, size.height), 1.5f)
    }

    // Playhead (fixed at center; the timeline scrolls beneath it)
    val cx = geo.centerX
    drawLine(accent, Offset(cx, rulerH * 0.3f), Offset(cx, size.height), 2f)
    drawRoundRect(accent, Offset(cx - 5f, 0f), Size(10f, rulerH * 0.55f), CornerRadius(3f, 3f))
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
    accent: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    c: EditorController,
    ghost: Boolean,
    invalid: Boolean,
) {
    val w = size.width
    val x0 = geo.xOf(clip.startUs)
    val x1 = geo.xOf(clip.endUs)
    if (x1 < -4f || x0 > w + 4f) return
    val pad = 2f
    val top = rowY + pad
    val h = rowH - pad * 2
    val left = max(x0, -8f)
    val right = min(x1, w + 8f)
    val rr = CornerRadius(7f, 7f)
    val selected = clip.id == geo.selectedId
    val asset = project.asset(clip.assetId)
    val missing = asset != null && clip.assetId in c.missingAssets

    drawRoundRect(clipColor(track.kind).copy(alpha = if (ghost) 0.75f else 1f), Offset(left, top), Size(right - left, h), rr)

    clipRect(max(left, 0f), top, min(right, w), top + h) {
        if (asset != null && track.kind != TrackKind.AUDIO) {
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
                        dstSize = IntSize(ceil(tileW).toInt(), h.roundToInt()),
                        alpha = if (ghost) 0.6f else 0.92f,
                    )
                }
                // Readability scrim behind the clip name
                drawRect(Color.Black.copy(alpha = 0.28f), Offset(left, top), Size(right - left, 16f + 10f))
            }
        }
        if (asset != null && (track.kind == TrackKind.AUDIO) && asset.hasAudio) {
            val wf = c.app.waveforms.get(asset.id)
            if (wf != null) {
                val mid = top + h * 0.58f
                val amp = h * 0.38f
                val col = Color(0xFF8FE3C4).copy(alpha = if (track.muted) 0.25f else 0.7f)
                var x = max(left, 0f)
                val end = min(right, w)
                val step = 2f
                while (x < end) {
                    val t0 = geo.usAt(x).coerceIn(clip.startUs, clip.endUs)
                    val t1 = geo.usAt(x + step).coerceIn(clip.startUs, clip.endUs)
                    val pk = wf.peak(clip.sourceTimeAt(t0), clip.sourceTimeAt(t1))
                    val bh = max(1f, pk * amp)
                    drawLine(col, Offset(x, mid - bh), Offset(x, mid + bh), 1.4f)
                    x += step
                }
            } else {
                drawLine(Color(0xFF8FE3C4).copy(alpha = 0.25f), Offset(left, top + h * 0.58f), Offset(right, top + h * 0.58f), 1f)
            }
        }

        // Clip name
        val label = buildString {
            if (clip.locked) append("🔒 ")
            if (clip.roto != null && clip.roto.keys.isNotEmpty()) append(if (clip.roto.enabled) "◐ ROTO · " else "◐ off · ")
            append(clip.name)
            if (missing) append(" · MISSING")
        }
        val textX = max(left, 0f) + 6f
        val avail = (min(right, w) - textX - 4f).toInt()
        if (avail > 20) {
            val layout = measurer.measure(
                label,
                style = TextStyle(color = if (missing) Amiri.Danger else Color.White.copy(alpha = 0.9f), fontSize = 9.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = avail),
            )
            drawText(layout, topLeft = Offset(textX, top + 3f))
        }
    }

    if (missing) drawRoundRect(Amiri.Danger.copy(alpha = 0.16f), Offset(left, top), Size(right - left, h), rr)
    if (invalid) drawRoundRect(Amiri.Danger.copy(alpha = 0.35f), Offset(left, top), Size(right - left, h), rr)

    val borderColor = when {
        invalid -> Amiri.Danger
        selected || ghost -> accent
        else -> Color.White.copy(alpha = 0.08f)
    }
    drawRoundRect(borderColor, Offset(left, top), Size(right - left, h), rr, style = Stroke(if (selected || ghost) 2.5f else 1f))

    // Trim handles
    if (selected && !ghost) {
        val hw = geo.handleW
        val handleColor = if (clip.locked || track.locked) Amiri.TextTertiary else accent
        if (x0 > -hw) {
            drawRoundRect(handleColor, Offset(x0 - 1f, top), Size(hw, h), CornerRadius(6f, 6f))
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(x0 + hw / 2f - 1f, top + h * 0.3f), Offset(x0 + hw / 2f - 1f, top + h * 0.7f), 2f)
        }
        if (x1 < w + hw) {
            drawRoundRect(handleColor, Offset(x1 - hw + 1f, top), Size(hw, h), CornerRadius(6f, 6f))
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(x1 - hw / 2f + 1f, top + h * 0.3f), Offset(x1 - hw / 2f + 1f, top + h * 0.7f), 2f)
        }
    }
}

private fun DrawScope.drawRuler(geo: TimelineGeometry, fps: Int, measurer: androidx.compose.ui.text.TextMeasurer, rulerH: Float) {
    val w = size.width
    val pps = geo.pps
    val frameUs = FrameTime.frameDurationUs(fps)
    // Candidate major intervals: whole frames first, then seconds.
    val frameSteps = listOf(1, 2, 5, 10).filter { it < fps }.map { it * frameUs }
    val secSteps = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800).map { it * 1_000_000.0 }
    val minLabelPx = 74f
    val major = (frameSteps + secSteps).firstOrNull { it / 1_000_000.0 * pps >= minLabelPx } ?: secSteps.last()
    val majorIsFrames = major < 1_000_000.0
    val majorPx = major / 1_000_000.0 * pps
    val minorDiv = when {
        majorIsFrames -> (major / frameUs).roundToInt().coerceAtLeast(1)
        majorPx / 5 >= 8 -> 5
        majorPx / 2 >= 8 -> 2
        else -> 1
    }
    val minor = major / minorDiv

    val tStart = max(0.0, geo.usAt(0f).toDouble())
    val tEnd = geo.usAt(w).toDouble()
    var i = floor(tStart / minor).toLong()
    val style = TextStyle(color = Amiri.TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    while (true) {
        val t = i * minor
        if (t > tEnd) break
        if (t >= 0) {
            val x = geo.xOf(t.toLong())
            val isMajor = i % minorDiv == 0L
            val th = if (isMajor) rulerH * 0.42f else rulerH * 0.2f
            drawLine(Color.White.copy(alpha = if (isMajor) 0.35f else 0.14f), Offset(x, rulerH - th), Offset(x, rulerH), 1f)
            if (isMajor) {
                val us = t.roundToLong()
                val text = if (majorIsFrames) FrameTime.timecode(us, fps) else {
                    val s = us / 1_000_000
                    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
                }
                val layout = measurer.measure(text, style)
                drawText(layout, topLeft = Offset(x + 3f, 2f))
            }
        }
        i++
    }
}

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
