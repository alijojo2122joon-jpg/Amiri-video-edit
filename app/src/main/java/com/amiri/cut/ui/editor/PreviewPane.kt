package com.amiri.cut.ui.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amiri.cut.core.effects.MaskSpec
import com.amiri.cut.core.effects.TransformSpec
import com.amiri.cut.core.model.Clip
import com.amiri.cut.core.model.MaskShape
import com.amiri.cut.core.model.Project
import com.amiri.cut.engine.PreviewRenderer
import com.amiri.cut.media.RotoBrushMode
import com.amiri.cut.render.Affine
import com.amiri.cut.render.LayerMath
import com.amiri.cut.ui.theme.Amiri
import com.amiri.cut.ui.theme.LocalAccent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Geometry of a layer on the overlay: canvas px (top-left) ↔ layer uv (top-left). */
private class LayerGeo(val inv: FloatArray, val ch: Int) {
    fun toCanvas(u: Float, v: Float): Offset = Affine.forward(inv, u, v, ch)?.let { Offset(it[0], it[1]) } ?: Offset.Zero
    fun toLayer(o: Offset): Pair<Float, Float> = Affine.toLayer(inv, o.x, o.y, ch).let { it[0] to it[1] }
}

private fun layerGeo(c: EditorController, p: Project, clip: Clip, t: Long, w: Int, h: Int): LayerGeo? {
    if (w <= 0 || h <= 0) return null
    val (bw, bh) = LayerMath.baseSize(p, clip, t, w, h, c.textMeasurer) ?: return null
    return LayerGeo(LayerMath.inverseOf(p, clip, t, bw, bh, w, h), h)
}

@Composable
fun PreviewPane(c: EditorController, modifier: Modifier = Modifier) {
    val p = c.project ?: return
    val pos by c.engine.position.collectAsState()
    val renderer = remember(c) { PreviewRenderer(c.app, c.projectId, c.engine) }
    val aspect = p.settings.aspect

    LaunchedEffect(c.app.settings.previewQuality) { renderer.quality = c.app.settings.previewQuality.scale; c.engine.refreshFrame() }
    LaunchedEffect(c.app.settings.proxyMode) { c.engine.useProxies = c.app.settings.proxyMode; c.engine.refreshFrame() }
    LaunchedEffect(c.colorBefore, c.selectedClipId, c.activeTool, c.rotoShowResult) { c.updateRenderOptions() }

    val scopes = remember { MutableStateFlow<ScopeData?>(null) }
    DisposableEffect(c.showScopes) {
        renderer.scopeSink = if (c.showScopes) { bytes, w, h -> scopes.value = ScopeData.compute(bytes, w, h) } else null
        onDispose { renderer.scopeSink = null }
    }

    LaunchedEffect(c.viewZoom, c.viewPanX, c.viewPanY) {
        renderer.viewXform = floatArrayOf(c.viewZoom, c.viewPanX, c.viewPanY)
        renderer.view?.requestRender()
    }
    val tool = c.activeTool
    // Two-finger view zoom/pan wherever two fingers aren't already used to transform a layer.
    val viewerGestures = when (tool) {
        EditorTool.SHAPE -> c.shapePen || c.pathEdit
        EditorTool.TRANSFORM, EditorTool.TEXT, EditorTool.KEYS -> false
        EditorTool.MASK -> c.penActive
        else -> true
    }

    Box(modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(aspect).clipToBounds()
                .pointerInput(viewerGestures) {
                    if (!viewerGestures) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val z = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val cen = ev.calculateCentroid()
                                if (z.isFinite() && z != 1f) c.zoomViewAt(z, cen.x / w, cen.y / h)
                                c.setView(c.viewZoom, c.viewPanX + pan.x / w, c.viewPanY + pan.y / h)
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        preserveEGLContextOnPause = true
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        renderer.view = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v ->
                    v.queueEvent { renderer.releaseGl() }
                    c.engine.detachSurfaces()
                    renderer.view = null
                },
            )

            val picking = c.pickColorFor
            // Overlays follow the same zoom/pan as the GL picture.
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = c.viewZoom; scaleY = c.viewZoom
                translationX = c.viewPanX * size.width; translationY = c.viewPanY * size.height
            }) {
            when {
                picking != null -> EyedropperOverlay(c, p, pos)
                c.activeTool == EditorTool.ROTO -> c.rotoTarget()?.let { RotoOverlay(c, p, it, pos) } ?: TapToPlay(c)
                c.activeTool == EditorTool.SHAPE && c.shapePen -> ShapePenOverlay(c)
                c.activeTool == EditorTool.SHAPE && c.pathEdit && c.selectedClip()?.shape?.kind == com.amiri.cut.core.model.ShapeKind.PATH -> PathEditOverlay(c, p, pos)
                c.activeTool == EditorTool.TRANSFORM || c.activeTool == EditorTool.TEXT || c.activeTool == EditorTool.SHAPE || c.activeTool == EditorTool.KEYS -> TransformGizmo(c, p, pos)
                c.activeTool == EditorTool.MASK -> MaskGizmo(c, p, pos)
                c.activeTool == EditorTool.TRACK -> TrackGizmo(c, p, pos)
                else -> TapToPlay(c)
            }
            GuidesOverlay(c, aspect)
            }
            ViewerControls(c, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (c.showScopes) {
                val data by scopes.collectAsState()
                ScopesView(data, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
}

@Composable
private fun ViewerControls(c: EditorController, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("−", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { c.zoomViewAt(1f / 1.5f, 0.5f, 0.5f) }.padding(horizontal = 10.dp, vertical = 4.dp))
        Text("${(c.viewZoom * 100).toInt()}%", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable { c.resetView() }.padding(horizontal = 4.dp, vertical = 4.dp))
        Text("+", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { c.zoomViewAt(1.5f, 0.5f, 0.5f) }.padding(horizontal = 10.dp, vertical = 4.dp))
        if (c.viewZoom != 1f || c.viewPanX != 0f || c.viewPanY != 0f) {
            Text("Fit", color = LocalAccent.current, fontSize = 11.sp, modifier = Modifier.clickable { c.resetView() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun TapToPlay(c: EditorController) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            val up = waitForUpOrCancellation()
            if (up != null) c.engine.togglePlay()
        }
    })
}

// ───────────────────────────── Transform gizmo ─────────────────────────────

@Composable
private fun TransformGizmo(c: EditorController, p: Project, pos: Long) {
    val accent = LocalAccent.current
    val clip = c.selectedClip()?.takeIf { it.contains(pos) && p.trackOfClip(it.id)?.acceptsVisual == true }
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(clip?.id) {
                if (clip == null) {
                    awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) c.engine.togglePlay() }
                    return@pointerInput
                }
                val t = EditorController.PTarget.Transform(clip.id)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    c.engine.pause()
                    val w0 = this.size.width
                    val h0 = this.size.height
                    val fresh = c.selectedClip()?.takeIf { it.id == clip.id } ?: clip
                    val g0 = layerGeo(c, p, fresh, c.engine.position.value, w0, h0)
                    val lt0 = c.localTime(clip.id)
                    val anchorPt = g0?.toCanvas(fresh.transform.at("ax", lt0, 0.5f), fresh.transform.at("ay", lt0, 0.5f))
                    val near = anchorPt != null && (down.position - anchorPt).getDistance() < 30.dp.toPx() / c.viewZoom
                    var released = false
                    var anchorMode = false
                    if (near) {
                        val r = withTimeoutOrNull(320L) {
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed) { released = true; return@withTimeoutOrNull true }
                                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                            }
                            @Suppress("UNREACHABLE_CODE") true
                        }
                        anchorMode = r == null
                    }
                    if (released) return@awaitEachGesture
                    c.beginEdit()
                    if (anchorMode && g0 != null) {
                        c.anchorDragging = true
                        do {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            val (u, v) = g0.toLayer(ch.position)
                            c.setAnchorKeepingPlace(clip.id, u, v, ch.position.x / w0, ch.position.y / h0)
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        c.anchorDragging = false
                        c.endEdit("Anchor point")
                        return@awaitEachGesture
                    }
                    do {
                        val ev = awaitPointerEvent()
                        val pan = ev.calculatePan()
                        val zoom = ev.calculateZoom()
                        val rot = ev.calculateRotation()
                        val w = this.size.width.toFloat().coerceAtLeast(1f)
                        val h = this.size.height.toFloat().coerceAtLeast(1f)
                        if (pan != Offset.Zero) {
                            c.setParam(t, "px", c.paramValue(t, "px", 0f) + pan.x / w, 0f)
                            c.setParam(t, "py", c.paramValue(t, "py", 0f) + pan.y / h, 0f)
                        }
                        if (zoom != 1f && zoom.isFinite()) c.setParam(t, "scale", (c.paramValue(t, "scale", 1f) * zoom).coerceIn(0.02f, 20f), 1f)
                        if (rot != 0f && rot.isFinite()) c.setParam(t, "rot", c.paramValue(t, "rot", 0f) + rot, 0f)
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    c.endEdit("Transform")
                }
            },
    ) {
        if (clip == null) return@Canvas
        val g = layerGeo(c, p, clip, pos, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        val cl = clip.transform
        val lt = pos - clip.startUs
        val l = cl.at("cropL", lt, 0f); val tp = cl.at("cropT", lt, 0f); val r = 1f - cl.at("cropR", lt, 0f); val b = 1f - cl.at("cropB", lt, 0f)
        val pts = listOf(g.toCanvas(l, tp), g.toCanvas(r, tp), g.toCanvas(r, b), g.toCanvas(l, b))
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, accent, style = Stroke(2.dp.toPx()))
        pts.forEach { drawCircle(Color.White, 5.dp.toPx(), it); drawCircle(accent, 5.dp.toPx(), it, style = Stroke(1.5f)) }
        val anchor = g.toCanvas(cl.at("ax", lt, TransformSpec.def("ax")), cl.at("ay", lt, TransformSpec.def("ay")))
        val ar = (if (c.anchorDragging) 9.dp.toPx() else 6.dp.toPx()) / c.viewZoom
        drawCircle(Color.Black.copy(alpha = 0.4f), ar + 2f, anchor)
        drawCircle(if (c.anchorDragging) Color(0xFFFFD27A) else accent, ar, anchor, style = Stroke(2.dp.toPx() / c.viewZoom))
        drawLine(accent, anchor - Offset(10.dp.toPx(), 0f), anchor + Offset(10.dp.toPx(), 0f), 1.5f)
        drawLine(accent, anchor - Offset(0f, 10.dp.toPx()), anchor + Offset(0f, 10.dp.toPx()), 1.5f)
    }
}


// ───────────────────────────── Pen tool (shape paths) ─────────────────────────────

/**
 * Draws a new pen path in canvas space. Tap = corner point, tap-and-drag = smooth point
 * (drag pulls the bezier handle), tap the first point = close. Freehand mode records the
 * finger and smooths it into bezier vertices. Two fingers zoom/pan the view instead.
 */
@Composable
private fun ShapePenOverlay(c: EditorController) {
    val accent = LocalAccent.current
    val free = remember { mutableStateListOf<Offset>() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(c.penFreehand) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                val touch = 22.dp.toPx() / c.viewZoom
                var multi = false
                if (c.penFreehand) {
                    free.clear(); free += down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) multi = true
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) { if (multi && ev.changes.any { it.pressed }) continue; break }
                        if (!multi && (ch.position - free.last()).getDistance() > 4.dp.toPx() / c.viewZoom) free += ch.position
                        ch.consume()
                    }
                    if (!multi && free.size >= 2) {
                        val pts = smoothPath(free.map { it.x / w to it.y / h })
                        c.shapePts.clear(); c.shapePts.addAll(pts)
                        c.finishPen(false)
                    }
                    free.clear()
                    return@awaitEachGesture
                }
                // Click-to-add pen.
                val n = c.shapePts.size / 6
                if (n >= 3) {
                    val first = Offset(c.shapePts[0] * w, c.shapePts[1] * h)
                    if ((down.position - first).getDistance() < touch) {
                        if (waitForUpOrCancellation() != null) c.finishPen(true)
                        return@awaitEachGesture
                    }
                }
                val base = c.shapePts.size
                c.shapePts.addAll(listOf(down.position.x / w, down.position.y / h, 0f, 0f, 0f, 0f))
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } >= 2) multi = true
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) { if (multi && ev.changes.any { it.pressed }) continue; break }
                    if (!multi && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        val ox = (ch.position.x - down.position.x) / w
                        val oy = (ch.position.y - down.position.y) / h
                        if (c.shapePts.size >= base + 6) {
                            c.shapePts[base + 4] = ox; c.shapePts[base + 5] = oy
                            c.shapePts[base + 2] = -ox; c.shapePts[base + 3] = -oy
                        }
                    }
                    ch.consume()
                }
                if (multi) { repeat(6) { if (c.shapePts.size > base) c.shapePts.removeAt(c.shapePts.lastIndex) } }
            }
        },
    ) {
        val w = size.width; val h = size.height
        val z = c.viewZoom
        val pts = c.shapePts.toList()
        val n = pts.size / 6
        if (n >= 1) {
            val path = Path()
            fun x(i: Int) = pts[i * 6] * w
            fun y(i: Int) = pts[i * 6 + 1] * h
            path.moveTo(x(0), y(0))
            for (k in 1 until n) path.cubicTo(
                x(k - 1) + pts[(k - 1) * 6 + 4] * w, y(k - 1) + pts[(k - 1) * 6 + 5] * h,
                x(k) + pts[k * 6 + 2] * w, y(k) + pts[k * 6 + 3] * h, x(k), y(k),
            )
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(4f / z))
            drawPath(path, accent, style = Stroke(2f / z))
            for (i in 0 until n) {
                val o = Offset(x(i), y(i))
                val hx = pts[i * 6 + 4]; val hy = pts[i * 6 + 5]
                if (hx != 0f || hy != 0f) {
                    val a = Offset(o.x + hx * w, o.y + hy * h); val b = Offset(o.x - hx * w, o.y - hy * h)
                    drawLine(Color.White.copy(alpha = 0.6f), a, b, 1f / z)
                    drawCircle(Color.White, 3.dp.toPx() / z, a); drawCircle(Color.White, 3.dp.toPx() / z, b)
                }
                drawRect(if (i == 0) Color(0xFFFFD27A) else Color.White, o - Offset(4.dp.toPx() / z, 4.dp.toPx() / z), Size(8.dp.toPx() / z, 8.dp.toPx() / z))
            }
        }
        if (free.size >= 2) {
            val path = Path().apply { moveTo(free[0].x, free[0].y); for (o in free.drop(1)) lineTo(o.x, o.y) }
            drawPath(path, accent, style = Stroke(2.5f / z, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Turns a dense finger polyline into smooth bezier vertices (Catmull-Rom handles). */
private fun smoothPath(raw: List<Pair<Float, Float>>): List<Float> {
    // Thin the points (keep roughly every 1.5% of the canvas).
    val pts = ArrayList<Pair<Float, Float>>()
    for (q in raw) {
        val l = pts.lastOrNull()
        if (l == null || kotlin.math.hypot(q.first - l.first, q.second - l.second) > 0.015f) pts += q
    }
    if (pts.last() != raw.last()) pts += raw.last()
    val out = ArrayList<Float>(pts.size * 6)
    for (i in pts.indices) {
        val a = pts[max(0, i - 1)]; val b = pts[minOf(pts.lastIndex, i + 1)]
        val tx = (b.first - a.first) / 6f; val ty = (b.second - a.second) / 6f
        out += listOf(pts[i].first, pts[i].second, -tx, -ty, tx, ty)
    }
    return out
}

/** Drag the vertices / bezier handles of the selected pen path. */
@Composable
private fun PathEditOverlay(c: EditorController, p: Project, pos: Long) {
    val accent = LocalAccent.current
    val clip = c.selectedClip() ?: return
    val spec = clip.shape ?: return
    Canvas(
        Modifier.fillMaxSize().pointerInput(clip.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                c.engine.pause()
                val cl = c.selectedClip() ?: return@awaitEachGesture
                val sp = cl.shape ?: return@awaitEachGesture
                val g = layerGeo(c, p, cl, c.engine.position.value, size.width, size.height) ?: return@awaitEachGesture
                val touch = 24.dp.toPx() / c.viewZoom
                // Find the nearest vertex or handle.
                var best = -1; var part = 0; var bestD = touch
                for (i in 0 until sp.vertexCount) {
                    val b = i * 6
                    val cands = listOf(
                        0 to (sp.path[b] to sp.path[b + 1]),
                        1 to (sp.path[b] + sp.path[b + 2] to sp.path[b + 1] + sp.path[b + 3]),
                        2 to (sp.path[b] + sp.path[b + 4] to sp.path[b + 1] + sp.path[b + 5]),
                    )
                    for ((pt, uv) in cands) {
                        if (pt != 0 && sp.path[b + if (pt == 1) 2 else 4] == 0f && sp.path[b + if (pt == 1) 3 else 5] == 0f) continue
                        val d = (g.toCanvas(uv.first, uv.second) - down.position).getDistance()
                        if (d < bestD) { bestD = d; best = i; part = pt }
                    }
                }
                if (best < 0) return@awaitEachGesture
                c.beginEdit()
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } >= 2) break
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    val (u, v) = g.toLayer(ch.position)
                    c.movePathPoint(cl.id, best, part, u, v)
                    ch.consume()
                } while (ch.pressed)
                c.endEdit("Edit path")
            }
        },
    ) {
        val g = layerGeo(c, p, clip, pos, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        val z = c.viewZoom
        val n = spec.vertexCount
        for (i in 0 until n) {
            val b = i * 6
            val o = g.toCanvas(spec.path[b], spec.path[b + 1])
            for (hp in listOf(2, 4)) {
                if (spec.path[b + hp] == 0f && spec.path[b + hp + 1] == 0f) continue
                val hpt = g.toCanvas(spec.path[b] + spec.path[b + hp], spec.path[b + 1] + spec.path[b + hp + 1])
                drawLine(Color.White.copy(alpha = 0.6f), o, hpt, 1f / z)
                drawCircle(accent, 4.dp.toPx() / z, hpt)
            }
            drawRect(Color.White, o - Offset(5.dp.toPx() / z, 5.dp.toPx() / z), Size(10.dp.toPx() / z, 10.dp.toPx() / z))
            drawRect(Color.Black, o - Offset(5.dp.toPx() / z, 5.dp.toPx() / z), Size(10.dp.toPx() / z, 10.dp.toPx() / z), style = Stroke(1f / z))
        }
    }
}

// ───────────────────────────── Mask gizmo ─────────────────────────────

@Composable
private fun MaskGizmo(c: EditorController, p: Project, pos: Long) {
    val accent = LocalAccent.current
    val clip = c.selectedClip()?.takeIf { it.contains(pos) }
    val mask = clip?.masks?.firstOrNull { it.id == c.selectedMaskId } ?: clip?.masks?.lastOrNull()
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(clip?.id, mask?.id, c.penActive) {
                if (clip == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val g = layerGeo(c, p, clip, c.engine.position.value, this.size.width, this.size.height) ?: return@awaitEachGesture
                    if (c.penActive) {
                        if (waitForUpOrCancellation() != null) {
                            val (u, v) = g.toLayer(down.position)
                            c.penPoints = c.penPoints + (u to v)
                        }
                        return@awaitEachGesture
                    }
                    if (mask == null) return@awaitEachGesture
                    val t = EditorController.PTarget.Mask(clip.id, mask.id)
                    c.engine.pause()
                    c.beginEdit()
                    var last = down.position
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = ev.calculateZoom()
                            val rot = ev.calculateRotation()
                            if (zoom != 1f && zoom.isFinite()) {
                                c.setParam(t, "w", (c.paramValue(t, "w", MaskSpec.def("w")) * zoom).coerceIn(0.01f, 3f), MaskSpec.def("w"))
                                c.setParam(t, "h", (c.paramValue(t, "h", MaskSpec.def("h")) * zoom).coerceIn(0.01f, 3f), MaskSpec.def("h"))
                            }
                            if (rot.isFinite() && rot != 0f) c.setParam(t, "rot", c.paramValue(t, "rot", 0f) + rot, 0f)
                        } else if (pressed.size == 1) {
                            val now = pressed[0].position
                            val a = g.toLayer(last)
                            val b = g.toLayer(now)
                            last = now
                            c.setParam(t, "x", c.paramValue(t, "x", 0.5f) + (b.first - a.first), 0.5f)
                            c.setParam(t, "y", c.paramValue(t, "y", 0.5f) + (b.second - a.second), 0.5f)
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    c.endEdit("Move mask")
                }
            },
    ) {
        if (clip == null) return@Canvas
        val g = layerGeo(c, p, clip, pos, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        val lt = pos - clip.startUs
        val a = p.asset(clip.assetId)
        val aspect = if (a != null && a.displayHeight > 0) a.displayWidth.toFloat() / a.displayHeight else 1f
        for (m in clip.masks) {
            val sel = m.id == mask?.id
            fun v(id: String) = m.props.at(id, lt, MaskSpec.def(id))
            val cx = v("x"); val cy = v("y"); val hw = v("w") / 2 + v("expand") / aspect; val hh = v("h") / 2 + v("expand")
            val r = Math.toRadians(v("rot").toDouble())
            fun local(lx: Float, ly: Float): Offset {
                // Rotate in aspect-corrected space, then back to uv.
                val x = lx * aspect; val y = ly
                val rx = (x * cos(r) - y * sin(r)).toFloat() / aspect
                val ry = (x * sin(r) + y * cos(r)).toFloat()
                return g.toCanvas(cx + rx, cy + ry)
            }
            val path = Path()
            when (m.shape) {
                MaskShape.RECT -> { val q = listOf(local(-hw, -hh), local(hw, -hh), local(hw, hh), local(-hw, hh)); path.moveTo(q[0].x, q[0].y); q.drop(1).forEach { path.lineTo(it.x, it.y) }; path.close() }
                MaskShape.ELLIPSE -> { for (k in 0..48) { val th = k / 48.0 * 2 * Math.PI; val o = local((cos(th) * hw).toFloat(), (sin(th) * hh).toFloat()); if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }; path.close() }
                MaskShape.PATH -> {
                    var i = 0
                    while (i + 1 < m.path.size) {
                        val o = local((m.path[i] - 0.5f) * 2 * hw, (m.path[i + 1] - 0.5f) * 2 * hh)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        i += 2
                    }
                    path.close()
                }
            }
            drawPath(path, if (sel) accent else Color.White.copy(alpha = 0.5f), style = Stroke(if (sel) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (m.invert) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
            if (sel) drawCircle(accent, 5.dp.toPx(), g.toCanvas(cx, cy))
        }
        if (c.penActive && c.penPoints.isNotEmpty()) {
            val pts = c.penPoints.map { g.toCanvas(it.first, it.second) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, accent, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            pts.forEach { drawCircle(Color.White, 4.dp.toPx(), it) }
        }
    }
}

// ───────────────────────────── Track gizmo ─────────────────────────────

@Composable
private fun TrackGizmo(c: EditorController, p: Project, pos: Long) {
    val accent = LocalAccent.current
    val clip = c.selectedClip()?.takeIf { it.contains(pos) }
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(clip?.id) {
                if (clip == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val g = layerGeo(c, p, clip, c.engine.position.value, this.size.width, this.size.height) ?: return@awaitEachGesture
                    val (u0, v0) = g.toLayer(down.position)
                    val regions = c.trackRegions.map { android.graphics.RectF(it) }
                    val idx = regions.indices.minByOrNull { val r = regions[it]; abs(r.centerX() - u0) + abs(r.centerY() - v0) } ?: return@awaitEachGesture
                    var last = down.position
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        val r = regions[idx]
                        if (pressed.size >= 2) {
                            val z = ev.calculateZoom()
                            if (z.isFinite() && z != 1f) {
                                val cx = r.centerX(); val cy = r.centerY()
                                val hw = (r.width() / 2 * z).coerceIn(0.02f, 0.3f); val hh = (r.height() / 2 * z).coerceIn(0.02f, 0.3f)
                                r.set(cx - hw, cy - hh, cx + hw, cy + hh)
                            }
                        } else if (pressed.size == 1) {
                            val a = g.toLayer(last); val b = g.toLayer(pressed[0].position)
                            last = pressed[0].position
                            r.offset(b.first - a.first, b.second - a.second)
                        }
                        c.trackRegions = regions.map { android.graphics.RectF(it) }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        if (clip == null) return@Canvas
        val g = layerGeo(c, p, clip, pos, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        // Existing track path
        clip.tracking?.samples?.let { s ->
            if (s.size > 1) {
                val path = Path()
                s.forEachIndexed { i, smp -> val o = g.toCanvas(smp.x, smp.y); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, Color(0xFFFFD27A).copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
                clip.tracking?.at(clip.sourceTimeAt(pos))?.let { now -> drawCircle(Color(0xFFFFD27A), 5.dp.toPx(), g.toCanvas(now.x, now.y)) }
            }
        }
        c.trackRegions.forEachIndexed { i, r ->
            val q = listOf(g.toCanvas(r.left, r.top), g.toCanvas(r.right, r.top), g.toCanvas(r.right, r.bottom), g.toCanvas(r.left, r.bottom))
            val path = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, accent, style = Stroke(2.dp.toPx()))
            drawCircle(accent, 3.dp.toPx(), g.toCanvas(r.centerX(), r.centerY()))
            if (c.trackRegions.size > 1) drawCircle(Color.White, 2.dp.toPx(), q[0] + Offset(6f + i * 0f, 6f))
        }
    }
}

// ───────────────────────────── Eyedropper ─────────────────────────────

@Composable
private fun EyedropperOverlay(c: EditorController, p: Project, pos: Long) {
    val clip = c.selectedClip()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f))
            .pointerInput(clip?.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (waitForUpOrCancellation() == null) return@awaitEachGesture
                    val cb = c.pickColorFor ?: return@awaitEachGesture
                    c.pickColorFor = null
                    val cl = clip ?: return@awaitEachGesture
                    val g = layerGeo(c, p, cl, pos, this.size.width, this.size.height) ?: return@awaitEachGesture
                    val (u, v) = g.toLayer(down.position)
                    c.pickColor(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), cb)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Text("Tap the color to key", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

// ───────────────────────────── Roto ─────────────────────────────

@Composable
private fun RotoOverlay(c: EditorController, p: Project, t: EditorController.RotoTarget, pos: Long) {
    val accent = LocalAccent.current
    val stroke = remember { mutableStateListOf<Offset>() }
    var touching by remember { mutableStateOf(false) }
    val mask = remember(t.clip.roto, c.rotoVersion, c.rotoSourceUs(t)) { c.currentRotoMask(t) }
    val showTint = !c.rotoShowResult
    val subtract = c.rotoMode == RotoBrushMode.SUBTRACT || c.rotoMode == RotoBrushMode.LASSO_CUT
    val lasso = c.rotoMode == RotoBrushMode.LASSO || c.rotoMode == RotoBrushMode.LASSO_CUT
    val inkColor = if (subtract) Color(0xFFFF5C6C) else accent

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(t.clip.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    stroke.clear()
                    stroke.add(down.position)
                    touching = true
                    var aborted = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) { aborted = true }
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) {
                            if (aborted && ev.changes.any { it.pressed }) continue
                            break
                        }
                        if (!aborted && (ch.position - stroke.last()).getDistance() > 1.5f / c.viewZoom) stroke.add(ch.position)
                        ch.consume()
                    }
                    touching = false
                    if (aborted) { stroke.clear(); return@awaitEachGesture }
                    val g = layerGeo(c, p, t.clip, c.engine.position.value, this.size.width, this.size.height)
                    if (g != null) c.applyRotoStroke(stroke.map { g.toLayer(it) })
                }
            },
    ) {
        val g = layerGeo(c, p, t.clip, pos, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        val q = listOf(g.toCanvas(0f, 0f), g.toCanvas(1f, 0f), g.toCanvas(1f, 1f), g.toCanvas(0f, 1f))
        if (showTint && mask != null) {
            drawIntoCanvas { canvas ->
                val m = Matrix()
                val w = mask.width.toFloat(); val h = mask.height.toFloat()
                m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, floatArrayOf(q[0].x, q[0].y, q[1].x, q[1].y, q[2].x, q[2].y, q[3].x, q[3].y), 0, 4)
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(accent.copy(alpha = 0.5f).toArgb(), PorterDuff.Mode.SRC_IN)
                }
                canvas.nativeCanvas.drawBitmap(mask, m, paint)
            }
        }
        if (stroke.isNotEmpty()) {
            val path = Path().apply {
                moveTo(stroke[0].x, stroke[0].y)
                for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                if (lasso && !touching) close()
            }
            // Brush width on screen = brush fraction × layer width on screen.
            val layerW = (q[1] - q[0]).getDistance()
            if (lasso) drawPath(path, inkColor, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            else drawPath(path, inkColor.copy(alpha = 0.55f), style = Stroke(width = c.rotoBrush * layerW, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (touching && !lasso) drawCircle(Color.White.copy(alpha = 0.9f), c.rotoBrush * layerW / 2f, stroke.last(), style = Stroke(1.5f))
        }
        val outline = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(outline, accent.copy(alpha = 0.6f), style = Stroke(1.5f))
    }
    LaunchedEffect(c.rotoVersion) { if (!touching) stroke.clear() }
}

// ───────────────────────────── Scopes ─────────────────────────────

/** Histogram (RGB + luma), waveform and vectorscope computed from a 256-px canvas copy. */
class ScopeData(val hist: Array<IntArray>, val histMax: Int, val waveform: ImageBitmap, val vector: ImageBitmap) {
    companion object {
        fun compute(bytes: ByteArray, w: Int, h: Int): ScopeData {
            val hist = Array(4) { IntArray(256) }
            val wf = IntArray(w * 128)
            val vs = IntArray(128 * 128)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 4
                val r = bytes[i].toInt() and 0xFF; val g = bytes[i + 1].toInt() and 0xFF; val b = bytes[i + 2].toInt() and 0xFF
                val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                hist[0][r]++; hist[1][g]++; hist[2][b]++; hist[3][l]++
                wf[(127 - l / 2) * w + x]++
                val cb = (-0.1687f * r - 0.3313f * g + 0.5f * b) / 255f
                val cr = (0.5f * r - 0.4187f * g - 0.0813f * b) / 255f
                val vx = ((cb + 0.5f) * 127).toInt().coerceIn(0, 127)
                val vy = ((0.5f - cr) * 127).toInt().coerceIn(0, 127)
                vs[vy * 128 + vx]++
            }
            val hm = max(1, (0 until 4).maxOf { k -> hist[k].drop(2).dropLast(2).maxOrNull() ?: 1 })
            fun toBmp(data: IntArray, bw: Int, bh: Int, tint: Int): ImageBitmap {
                val px = IntArray(data.size) { k ->
                    val v = (kotlin.math.sqrt(data[k].toFloat()) * 40f).toInt().coerceIn(0, 255)
                    if (v == 0) 0 else (v shl 24) or tint
                }
                return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
            return ScopeData(hist, hm, toBmp(wf, w, 128, 0x9FE8B0), toBmp(vs, 128, 128, 0xFFFFFF))
        }
    }
}

@Composable
private fun ScopesView(d: ScopeData?, modifier: Modifier) {
    if (d == null) return
    val measurer = rememberTextMeasurer()
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(4.dp)) {
        Canvas(Modifier.size(120.dp, 54.dp)) {
            val cols = listOf(Color(0xFFFF6B6B), Color(0xFF6BFF8F), Color(0xFF6BA8FF), Color.White)
            for (k in 0 until 4) {
                val path = Path()
                for (i in 0 until 256) {
                    val x = i / 255f * size.width
                    val y = size.height - (d.hist[k][i].toFloat() / d.histMax).coerceAtMost(1f) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, cols[k].copy(alpha = if (k == 3) 0.9f else 0.6f), style = Stroke(1f))
            }
            label(measurer, "Histogram")
        }
        Row {
            Image(d.waveform, null, Modifier.size(84.dp, 54.dp))
            Image(d.vector, null, Modifier.size(54.dp).padding(start = 4.dp))
        }
    }
}

private fun DrawScope.label(m: androidx.compose.ui.text.TextMeasurer, s: String) {
    drawText(m.measure(s, TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 7.sp)), topLeft = Offset(2f, 1f))
}

// ───────────────────────────── Guides ─────────────────────────────

/** Grid, center, title/action safe and short-form platform UI zones. Never rendered into export. */
@Composable
private fun GuidesOverlay(c: EditorController, aspect: Float) {
    val any = c.showGrid || c.showCenter || c.showActionSafe || c.showTitleSafe || c.showPlatformZones
    if (!any) return
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val line = Color.White.copy(alpha = 0.35f)
        if (c.showGrid) {
            for (k in 1..2) {
                drawLine(line, Offset(w * k / 3f, 0f), Offset(w * k / 3f, h), 1f)
                drawLine(line, Offset(0f, h * k / 3f), Offset(w, h * k / 3f), 1f)
            }
        }
        if (c.showCenter) {
            val s = minOf(w, h) * 0.05f
            drawLine(Color.White.copy(alpha = 0.6f), Offset(w / 2 - s, h / 2), Offset(w / 2 + s, h / 2), 1.5f)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(w / 2, h / 2 - s), Offset(w / 2, h / 2 + s), 1.5f)
        }
        if (c.showActionSafe) {
            val m = 0.035f
            drawRect(Color(0xFF8FE3C4).copy(alpha = 0.7f), Offset(w * m, h * m), Size(w * (1 - 2 * m), h * (1 - 2 * m)), style = Stroke(1.2f))
        }
        if (c.showTitleSafe) {
            val m = 0.05f
            drawRect(Color(0xFFF2C47E).copy(alpha = 0.7f), Offset(w * m, h * m), Size(w * (1 - 2 * m), h * (1 - 2 * m)),
                style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
        if (c.showPlatformZones) {
            val shade = Color(0xFFE5737A).copy(alpha = 0.16f)
            val style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
            if (aspect < 0.8f) {
                drawRect(shade, Offset(0f, h * 0.80f), Size(w, h * 0.20f))
                drawRect(shade, Offset(w * 0.86f, h * 0.40f), Size(w * 0.14f, h * 0.40f))
                drawRect(shade, Offset(0f, 0f), Size(w, h * 0.08f))
                drawText(measurer.measure("Captions / UI", style), topLeft = Offset(8f, h * 0.80f + 6f))
                drawText(measurer.measure("Buttons", style), topLeft = Offset(w * 0.86f + 2f, h * 0.40f + 4f))
            } else {
                drawRect(shade, Offset(0f, h * 0.88f), Size(w, h * 0.12f))
                drawText(measurer.measure("Player controls", style), topLeft = Offset(8f, h * 0.88f + 4f))
            }
        }
    }
}
