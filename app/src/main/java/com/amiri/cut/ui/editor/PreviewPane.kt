package com.amiri.cut.ui.editor

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amiri.cut.core.model.CanvasBackground
import com.amiri.cut.engine.PreviewEngine
import com.amiri.cut.media.BitmapLoader

/**
 * Project canvas: the frame at the project's aspect ratio, black (or checkerboard
 * for transparent projects). Media is fitted inside it, exactly as it will be
 * composed for export until Transform (Stage 2) adds position/scale.
 */
@Composable
fun PreviewPane(c: EditorController, modifier: Modifier = Modifier) {
    val p = c.project ?: return
    val context = LocalContext.current
    val visual by c.engine.visual.collectAsState()
    val aspect = p.settings.aspect

    // Keep the last known video aspect so the TextureView doesn't jump when hidden.
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    val v = visual
    if (v is PreviewEngine.Visual.Video) {
        p.asset(v.assetId)?.let { a ->
            if (a.displayWidth > 0 && a.displayHeight > 0) videoAspect = a.displayWidth.toFloat() / a.displayHeight
        }
    }

    Box(modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(aspect)
                .clipToBounds()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { c.engine.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (p.settings.background == CanvasBackground.TRANSPARENT) Checkerboard() else Box(Modifier.fillMaxSize().background(Color.Black))

            // Video layer — a TextureView so it composes and clips like any other layer.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv -> c.engine.videoPlayer.setVideoTextureView(tv) }
                    },
                    modifier = Modifier
                        .aspectRatio(videoAspect)
                        .alpha(if (v is PreviewEngine.Visual.Video) 1f else 0f),
                    onRelease = { tv -> c.engine.videoPlayer.clearVideoTextureView(tv) },
                )
            }

            // Still image layer
            if (v is PreviewEngine.Visual.Image) {
                val bmp by produceState<Bitmap?>(null, v.asset.id) {
                    value = BitmapLoader.previewImage(context, v.asset)
                }
                bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }

            GuidesOverlay(c, aspect)
        }
    }
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
                // Approximate overlay zones of vertical short-form apps (Reels / Shorts / TikTok):
                // caption + buttons at the bottom, action column on the right, top bar.
                drawRect(shade, Offset(0f, h * 0.80f), Size(w, h * 0.20f))
                drawRect(shade, Offset(w * 0.86f, h * 0.40f), Size(w * 0.14f, h * 0.40f))
                drawRect(shade, Offset(0f, 0f), Size(w, h * 0.08f))
                drawText(measurer.measure("Captions / UI", style), topLeft = Offset(8f, h * 0.80f + 6f))
                drawText(measurer.measure("Buttons", style), topLeft = Offset(w * 0.86f + 2f, h * 0.40f + 4f))
            } else {
                // Horizontal (YouTube): progress bar / controls band.
                drawRect(shade, Offset(0f, h * 0.88f), Size(w, h * 0.12f))
                drawText(measurer.measure("Player controls", style), topLeft = Offset(8f, h * 0.88f + 4f))
            }
        }
    }
}
