package com.amiri.cut.ui.editor

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.engine.PreviewEngine
import com.amiri.cut.media.BitmapLoader
import com.amiri.cut.media.RotoBrushMode
import com.amiri.cut.ui.theme.LocalAccent

/**
 * Project canvas: the frame at the project's aspect ratio, black (or checkerboard
 * for transparent projects). Media is fitted inside it, exactly as it will be
 * composed for export until Transform adds position/scale.
 */
@Composable
fun PreviewPane(c: EditorController, modifier: Modifier = Modifier) {
    val p = c.project ?: return
    val context = LocalContext.current
    val visual by c.engine.visual.collectAsState()
    val pos by c.engine.position.collectAsState()
    val aspect = p.settings.aspect
    val rotoActive = c.activeTool == EditorTool.ROTO

    // Roto: show the untouched frame while painting unless "Result" is on.
    LaunchedEffect(rotoActive, c.rotoShowResult) {
        c.engine.rotoBypass = rotoActive && !c.rotoShowResult
    }

    // Aspect of the media box (keeps the last video aspect so the TextureView doesn't jump).
    var mediaAspect by remember { mutableStateOf(16f / 9f) }
    val v = visual
    val activeAsset = when (v) {
        is PreviewEngine.Visual.Video -> p.asset(v.assetId)
        is PreviewEngine.Visual.Image -> v.asset
        PreviewEngine.Visual.None -> null
    }
    if (activeAsset != null && activeAsset.displayWidth > 0 && activeAsset.displayHeight > 0) {
        val a = activeAsset.displayWidth.toFloat() / activeAsset.displayHeight
        if (a != mediaAspect) mediaAspect = a
    }
    val rotoTarget = if (rotoActive) remember(p, pos, c.selectedClipId) { c.rotoTarget() } else null

    Box(modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(aspect)
                .clipToBounds()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = !rotoActive,
                ) { c.engine.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (p.settings.background == CanvasBackground.TRANSPARENT) Checkerboard() else Box(Modifier.fillMaxSize().background(Color.Black))

            Box(Modifier.aspectRatio(mediaAspect)) {
                // Video layer — a TextureView (non-opaque so roto-removed areas show the canvas).
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            tv.isOpaque = false
                            c.engine.videoPlayer.setVideoTextureView(tv)
                        }
                    },
                    modifier = Modifier.fillMaxSize().alpha(if (v is PreviewEngine.Visual.Video) 1f else 0f),
                    onRelease = { tv -> c.engine.videoPlayer.clearVideoTextureView(tv) },
                )

                // Still image layer (roto applied in Compose with the same mask).
                if (v is PreviewEngine.Visual.Image) {
                    val bmp by produceState<Bitmap?>(null, v.asset.id) {
                        value = BitmapLoader.previewImage(context, v.asset)
                    }
                    val clip = p.clip(v.clipId)
                    val roto = clip?.roto?.takeIf { it.enabled && it.keys.isNotEmpty() }
                    val applyMask = roto != null && !(rotoActive && !c.rotoShowResult)
                    val mask = if (applyMask && clip != null) {
                        remember(roto, c.rotoVersion) { roto!!.keyAt(clip.sourceTimeAt(pos))?.let { c.app.roto.load(c.projectId, it.file) } }
                    } else null
                    bmp?.let { b ->
                        Image(
                            b.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    if (mask != null) {
                                        drawImage(
                                            mask.asImageBitmap(),
                                            srcOffset = IntOffset.Zero,
                                            srcSize = IntSize(mask.width, mask.height),
                                            dstOffset = IntOffset.Zero,
                                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                            blendMode = if (roto!!.invert) BlendMode.DstOut else BlendMode.DstIn,
                                        )
                                    }
                                },
                        )
                    }
                }

                if (rotoActive && rotoTarget != null) RotoOverlay(c, rotoTarget)
            }

            GuidesOverlay(c, aspect)
        }
    }
}

/** Finger painting surface for the roto brush, aligned to the media frame. */
@Composable
private fun RotoOverlay(c: EditorController, t: EditorController.RotoTarget) {
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
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val last = stroke.last()
                        if ((ch.position - last).getDistance() > 2f) stroke.add(ch.position)
                        ch.consume()
                    }
                    touching = false
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val pts = stroke.map { (it.x / w).coerceIn(-0.1f, 1.1f) to (it.y / h).coerceIn(-0.1f, 1.1f) }
                    c.applyRotoStroke(pts)
                }
            },
    ) {
        // Committed mask, tinted (AE-style overlay) while painting.
        if (showTint && mask != null) {
            drawImage(
                mask.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mask.width, mask.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                colorFilter = ColorFilter.tint(accent.copy(alpha = 0.5f)),
            )
        }
        // Live stroke preview
        if (stroke.isNotEmpty()) {
            val path = Path().apply {
                moveTo(stroke[0].x, stroke[0].y)
                for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                if (lasso && !touching) close()
            }
            if (lasso) {
                drawPath(path, inkColor, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            } else {
                drawPath(
                    path, inkColor.copy(alpha = 0.55f),
                    style = Stroke(width = c.rotoBrush * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            if (touching && !lasso) {
                drawCircle(Color.White.copy(alpha = 0.9f), c.rotoBrush * size.width / 2f, stroke.last(), style = Stroke(1.5f))
            }
        }
        // Frame outline so the paintable area is obvious.
        drawRect(accent.copy(alpha = 0.6f), Offset.Zero, Size(size.width, size.height), style = Stroke(1.5f))
    }
    // Clear the drawn stroke once the new mask has been committed.
    LaunchedEffect(c.rotoVersion) { if (!touching) stroke.clear() }
}

@Composable
private fun Checkerboard() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 12.dp.toPx()
        drawRect(Color(0xFF2B2B30))
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < size.width) {
                drawRect(Color(0xFF38383E), Offset(x, y), Size(cell, cell))
                x += cell * 2
            }
            y += cell
            row++
        }
    }
}

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
