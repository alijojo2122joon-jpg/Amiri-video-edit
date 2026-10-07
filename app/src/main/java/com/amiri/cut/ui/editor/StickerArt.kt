package com.amiri.cut.ui.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Amiri Cut's own cat sticker pack, drawn in code (no image files, no licences): die-cut
 * style with a white border, so they read on any video. The same drawing is used for the
 * panel thumbnails and for the transparent PNG that goes on the timeline.
 */
object StickerArt {
    data class Item(val id: String, val label: String)

    val ITEMS = listOf(
        Item("paw", "Paw"), Item("ginger", "Ginger"), Item("blackCat", "Black cat"), Item("sleepy", "Sleepy"),
        Item("peek", "Peek-a-boo"), Item("meow", "Meow!"), Item("lovePaw", "Love paw"), Item("ears", "Cat ears"),
        Item("whiskers", "Whiskers"), Item("fish", "Fish"), Item("yarn", "Yarn"), Item("trail", "Paw trail"),
    )

    fun label(id: String) = ITEMS.firstOrNull { it.id == id }?.label ?: "Sticker"

    /** Renders a sticker to a transparent square bitmap. */
    fun render(id: String, px: Int): Bitmap {
        val img = ImageBitmap(px, px)
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) { drawSticker(id) }
        return img.asAndroidBitmap()
    }

    // ───────────────────────── shared pieces ─────────────────────────

    private val WHITE = Color.White
    private val INK = Color(0xFF1C1917)
    private val PINK = Color(0xFFFF8FAB)
    private val PINK_DEEP = Color(0xFFF26B8A)

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }
    private fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = Path().apply { moveTo(ax, ay); lineTo(bx, by); lineTo(cx, cy); close() }
    private fun heart(cx: Float, cy: Float, w: Float) = Path().apply {
        val h = w * 0.9f
        moveTo(cx, cy + h * 0.45f)
        cubicTo(cx - w * 0.75f, cy - h * 0.05f, cx - w * 0.45f, cy - h * 0.65f, cx, cy - h * 0.22f)
        cubicTo(cx + w * 0.45f, cy - h * 0.65f, cx + w * 0.75f, cy - h * 0.05f, cx, cy + h * 0.45f)
        close()
    }

    /** Die-cut look: every silhouette path gets a fat white border and a soft drop shadow. */
    private fun DrawScope.dieCut(paths: List<Path>) {
        val s = size.minDimension
        translate(0f, s * 0.018f) { paths.forEach { drawPath(it, Color.Black.copy(alpha = 0.22f), style = Stroke(s * 0.075f, join = StrokeJoin.Round)); drawPath(it, Color.Black.copy(alpha = 0.22f)) } }
        paths.forEach { drawPath(it, WHITE, style = Stroke(s * 0.075f, join = StrokeJoin.Round, cap = StrokeCap.Round)); drawPath(it, WHITE) }
    }

    private fun DrawScope.catHead(cx: Float, cy: Float, r: Float, fur: Color, inner: Color): List<Path> {
        val head = oval(cx, cy, r, r * 0.84f)
        val earL = tri(cx - r * 0.92f, cy - r * 0.15f, cx - r * 0.8f, cy - r * 1.12f, cx - r * 0.18f, cy - r * 0.72f)
        val earR = tri(cx + r * 0.92f, cy - r * 0.15f, cx + r * 0.8f, cy - r * 1.12f, cx + r * 0.18f, cy - r * 0.72f)
        return listOf(earL, earR, head).also {
            drawPath(earL, fur, style = Stroke(r * 0.12f, join = StrokeJoin.Round)); drawPath(earL, fur)
            drawPath(earR, fur, style = Stroke(r * 0.12f, join = StrokeJoin.Round)); drawPath(earR, fur)
            drawPath(tri(cx - r * 0.78f, cy - r * 0.32f, cx - r * 0.72f, cy - r * 0.92f, cx - r * 0.32f, cy - r * 0.66f), inner)
            drawPath(tri(cx + r * 0.78f, cy - r * 0.32f, cx + r * 0.72f, cy - r * 0.92f, cx + r * 0.32f, cy - r * 0.66f), inner)
            drawPath(head, fur)
        }
    }

    private fun DrawScope.catFace(cx: Float, cy: Float, r: Float, eye: Color, pupil: Color, whisker: Color, slit: Boolean = false) {
        for (sx in listOf(-1f, 1f)) {
            val ex = cx + sx * r * 0.38f; val ey = cy - r * 0.02f
            drawOval(eye, Offset(ex - r * 0.15f, ey - r * 0.19f), Size(r * 0.3f, r * 0.38f))
            if (slit) drawOval(pupil, Offset(ex - r * 0.04f, ey - r * 0.16f), Size(r * 0.08f, r * 0.32f))
            else drawOval(pupil, Offset(ex - r * 0.11f, ey - r * 0.14f), Size(r * 0.22f, r * 0.3f))
            drawCircle(Color.White, r * 0.055f, Offset(ex + r * 0.04f, ey - r * 0.08f))
            drawCircle(PINK.copy(alpha = 0.55f), r * 0.11f, Offset(cx + sx * r * 0.58f, cy + r * 0.24f))
            for (k in 0..2) {
                val y0 = cy + r * (0.2f + k * 0.08f)
                drawLine(whisker, Offset(cx + sx * r * 0.3f, y0), Offset(cx + sx * r * 1.18f, y0 + r * (k - 1) * 0.12f), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
            }
        }
        drawPath(tri(cx - r * 0.09f, cy + r * 0.14f, cx + r * 0.09f, cy + r * 0.14f, cx, cy + r * 0.25f), PINK_DEEP)
        val mouth = Path().apply {
            moveTo(cx - r * 0.16f, cy + r * 0.3f)
            cubicTo(cx - r * 0.12f, cy + r * 0.38f, cx - r * 0.02f, cy + r * 0.36f, cx, cy + r * 0.26f)
            cubicTo(cx + r * 0.02f, cy + r * 0.36f, cx + r * 0.12f, cy + r * 0.38f, cx + r * 0.16f, cy + r * 0.3f)
        }
        drawPath(mouth, INK, style = Stroke(r * 0.04f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun pawPaths(cx: Float, cy: Float, s: Float, heartPad: Boolean = false): List<Path> = buildList {
        add(if (heartPad) heart(cx, cy + s * 0.12f, s * 0.62f) else Path().apply {
            moveTo(cx, cy - s * 0.12f)
            cubicTo(cx + s * 0.3f, cy - s * 0.12f, cx + s * 0.36f, cy + s * 0.3f, cx + s * 0.2f, cy + s * 0.34f)
            cubicTo(cx + s * 0.08f, cy + s * 0.37f, cx - s * 0.08f, cy + s * 0.37f, cx - s * 0.2f, cy + s * 0.34f)
            cubicTo(cx - s * 0.36f, cy + s * 0.3f, cx - s * 0.3f, cy - s * 0.12f, cx, cy - s * 0.12f)
            close()
        })
        add(oval(cx - s * 0.33f, cy - s * 0.22f, s * 0.1f, s * 0.13f))
        add(oval(cx - s * 0.12f, cy - s * 0.38f, s * 0.1f, s * 0.13f))
        add(oval(cx + s * 0.12f, cy - s * 0.38f, s * 0.1f, s * 0.13f))
        add(oval(cx + s * 0.33f, cy - s * 0.22f, s * 0.1f, s * 0.13f))
    }

    // ───────────────────────── the pack ─────────────────────────

    fun DrawScope.drawSticker(id: String) {
        val s = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        when (id) {
            "paw" -> {
                val p = pawPaths(cx, cy + s * 0.05f, s * 0.9f)
                dieCut(p)
                p.forEachIndexed { i, path -> drawPath(path, if (i == 0) PINK_DEEP else PINK) }
                drawCircle(Color.White.copy(alpha = 0.45f), s * 0.035f, Offset(cx - s * 0.08f, cy + s * 0.06f))
            }
            "lovePaw" -> {
                val p = pawPaths(cx, cy + s * 0.04f, s * 0.9f, heartPad = true)
                dieCut(p)
                p.forEachIndexed { i, path -> drawPath(path, if (i == 0) Color(0xFFFF4D6A) else PINK) }
                drawCircle(Color.White.copy(alpha = 0.5f), s * 0.04f, Offset(cx - s * 0.12f, cy + s * 0.08f))
            }
            "ginger", "blackCat" -> {
                val black = id == "blackCat"
                val r = s * 0.36f
                val fur = if (black) Color(0xFF2A2A30) else Color(0xFFF4A261)
                val outline = listOf(
                    oval(cx, cy + s * 0.06f, r, r * 0.84f),
                    tri(cx - r * 0.92f, cy + s * 0.06f - r * 0.15f, cx - r * 0.8f, cy + s * 0.06f - r * 1.12f, cx - r * 0.18f, cy + s * 0.06f - r * 0.72f),
                    tri(cx + r * 0.92f, cy + s * 0.06f - r * 0.15f, cx + r * 0.8f, cy + s * 0.06f - r * 1.12f, cx + r * 0.18f, cy + s * 0.06f - r * 0.72f),
                )
                dieCut(outline)
                catHead(cx, cy + s * 0.06f, r, fur, PINK)
                if (!black) for (k in -1..1) {
                    val x = cx + k * r * 0.2f
                    drawLine(Color(0xFFD9844A), Offset(x, cy + s * 0.06f - r * 0.78f), Offset(x, cy + s * 0.06f - r * 0.5f), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
                }
                catFace(cx, cy + s * 0.06f, r, if (black) Color(0xFFC6F35E) else Color.White, if (black) INK else INK, if (black) Color.White.copy(alpha = 0.85f) else INK.copy(alpha = 0.7f), slit = black)
            }
            "sleepy" -> {
                val body = oval(cx + s * 0.05f, cy + s * 0.14f, s * 0.36f, s * 0.2f)
                val head = oval(cx - s * 0.2f, cy + s * 0.06f, s * 0.16f, s * 0.14f)
                val earL = tri(cx - s * 0.34f, cy + s * 0.0f, cx - s * 0.33f, cy - s * 0.14f, cx - s * 0.22f, cy - s * 0.07f)
                val earR = tri(cx - s * 0.16f, cy - s * 0.07f, cx - s * 0.08f, cy - s * 0.15f, cx - s * 0.05f, cy - s * 0.0f)
                val tail = Path().apply {
                    moveTo(cx + s * 0.38f, cy + s * 0.2f)
                    cubicTo(cx + s * 0.4f, cy + s * 0.42f, cx - s * 0.1f, cy + s * 0.42f, cx - s * 0.25f, cy + s * 0.3f)
                }
                dieCut(listOf(body, head, earL, earR))
                drawPath(tail, WHITE, style = Stroke(s * 0.15f, cap = StrokeCap.Round))
                val fur = Color(0xFFCBD0D8)
                drawPath(body, fur); drawPath(earL, fur); drawPath(earR, fur); drawPath(head, fur)
                drawPath(tail, Color(0xFFB7BDC7), style = Stroke(s * 0.07f, cap = StrokeCap.Round))
                for (sx in listOf(-1f, 1f)) {
                    val ex = cx - s * 0.2f + sx * s * 0.06f
                    drawArc(INK, 10f, 160f, false, Offset(ex - s * 0.035f, cy + s * 0.03f), Size(s * 0.07f, s * 0.05f), style = Stroke(s * 0.014f, cap = StrokeCap.Round))
                }
                drawCircle(PINK_DEEP, s * 0.014f, Offset(cx - s * 0.2f, cy + s * 0.1f))
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF7C5CFF.toInt(); textSize = s * 0.16f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setShadowLayer(s * 0.02f, 0f, 0f, android.graphics.Color.WHITE)
                    }
                    c.nativeCanvas.drawText("z", cx + s * 0.12f, cy - s * 0.12f, paint)
                    paint.textSize = s * 0.22f
                    c.nativeCanvas.drawText("Z", cx + s * 0.22f, cy - s * 0.24f, paint)
                }
            }
            "peek" -> {
                val r = s * 0.3f
                val hy = cy + s * 0.2f
                val head = oval(cx, hy, r, r * 0.84f)
                val earL = tri(cx - r * 0.92f, hy - r * 0.15f, cx - r * 0.8f, hy - r * 1.12f, cx - r * 0.18f, hy - r * 0.72f)
                val earR = tri(cx + r * 0.92f, hy - r * 0.15f, cx + r * 0.8f, hy - r * 1.12f, cx + r * 0.18f, hy - r * 0.72f)
                val pawL = oval(cx - r * 0.75f, hy + r * 0.72f, r * 0.3f, r * 0.2f)
                val pawR = oval(cx + r * 0.75f, hy + r * 0.72f, r * 0.3f, r * 0.2f)
                val ledge = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - s * 0.44f, hy + r * 0.62f, cx + s * 0.44f, hy + r * 0.62f + s * 0.1f, s * 0.04f, s * 0.04f)) }
                dieCut(listOf(head, earL, earR, ledge))
                catHead(cx, hy, r, Color(0xFF9CA3AF), PINK)
                for (k in -1..1) drawLine(Color(0xFF6B7280), Offset(cx + k * r * 0.22f, hy - r * 0.8f), Offset(cx + k * r * 0.22f, hy - r * 0.52f), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
                catFace(cx, hy, r, Color.White, INK, INK.copy(alpha = 0.7f))
                drawPath(ledge, Color(0xFF7C5CFF))
                drawPath(pawL, Color(0xFF9CA3AF)); drawPath(pawR, Color(0xFF9CA3AF))
                for (sx in listOf(-1f, 1f)) for (k in -1..1) {
                    val px = cx + sx * r * 0.75f + k * r * 0.1f
                    drawLine(Color(0xFF6B7280), Offset(px, hy + r * 0.62f), Offset(px, hy + r * 0.74f), strokeWidth = r * 0.03f, cap = StrokeCap.Round)
                }
            }
            "meow" -> {
                val bubble = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - s * 0.42f, cy - s * 0.26f, cx + s * 0.42f, cy + s * 0.16f, s * 0.13f, s * 0.13f))
                    moveTo(cx - s * 0.22f, cy + s * 0.12f); lineTo(cx - s * 0.3f, cy + s * 0.34f); lineTo(cx - s * 0.02f, cy + s * 0.14f); close()
                }
                dieCut(listOf(bubble))
                drawPath(bubble, Color(0xFFFFF7ED))
                drawPath(bubble, INK, style = Stroke(s * 0.022f, join = StrokeJoin.Round))
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF1C1917.toInt(); textSize = s * 0.17f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    c.nativeCanvas.drawText("Meow!", cx, cy + s * 0.0f, paint)
                }
                pawPaths(cx + s * 0.3f, cy - s * 0.22f, s * 0.16f).forEach { drawPath(it, PINK_DEEP) }
            }
            "ears" -> {
                val band = Path().apply {
                    moveTo(cx - s * 0.42f, cy + s * 0.22f)
                    cubicTo(cx - s * 0.36f, cy - s * 0.2f, cx + s * 0.36f, cy - s * 0.2f, cx + s * 0.42f, cy + s * 0.22f)
                }
                val earL = tri(cx - s * 0.36f, cy - s * 0.02f, cx - s * 0.32f, cy - s * 0.42f, cx - s * 0.08f, cy - s * 0.12f)
                val earR = tri(cx + s * 0.36f, cy - s * 0.02f, cx + s * 0.32f, cy - s * 0.42f, cx + s * 0.08f, cy - s * 0.12f)
                dieCut(listOf(earL, earR))
                drawPath(band, WHITE, style = Stroke(s * 0.13f, cap = StrokeCap.Round))
                drawPath(band, PINK_DEEP, style = Stroke(s * 0.06f, cap = StrokeCap.Round))
                listOf(earL, earR).forEach { drawPath(it, Color(0xFF2A2A30), style = Stroke(s * 0.05f, join = StrokeJoin.Round)); drawPath(it, Color(0xFF2A2A30)) }
                drawPath(tri(cx - s * 0.32f, cy - s * 0.06f, cx - s * 0.3f, cy - s * 0.33f, cx - s * 0.14f, cy - s * 0.13f), PINK)
                drawPath(tri(cx + s * 0.32f, cy - s * 0.06f, cx + s * 0.3f, cy - s * 0.33f, cx + s * 0.14f, cy - s * 0.13f), PINK)
            }
            "whiskers" -> {
                val nose = heart(cx, cy, s * 0.16f)
                for (sx in listOf(-1f, 1f)) for (k in 0..2) {
                    val y = cy + s * (k - 1) * 0.08f
                    val l = Path().apply { moveTo(cx + sx * s * 0.12f, y); cubicTo(cx + sx * s * 0.25f, y - s * 0.03f, cx + sx * s * 0.36f, y + s * (k - 1) * 0.04f, cx + sx * s * 0.46f, y + s * (k - 1) * 0.09f) }
                    drawPath(l, WHITE, style = Stroke(s * 0.06f, cap = StrokeCap.Round))
                    drawPath(l, INK, style = Stroke(s * 0.022f, cap = StrokeCap.Round))
                }
                dieCut(listOf(nose))
                drawPath(nose, PINK_DEEP)
                for (sx in listOf(-1f, 1f)) drawCircle(PINK.copy(alpha = 0.7f), s * 0.07f, Offset(cx + sx * s * 0.2f, cy + s * 0.2f))
            }
            "fish" -> {
                val body = oval(cx - s * 0.04f, cy, s * 0.3f, s * 0.18f)
                val tail = tri(cx + s * 0.2f, cy, cx + s * 0.44f, cy - s * 0.18f, cx + s * 0.44f, cy + s * 0.18f)
                dieCut(listOf(body, tail))
                drawPath(tail, Color(0xFF3B82F6)); drawPath(body, Color(0xFF60A5FA))
                drawArc(Color(0xFF2563EB), -60f, 120f, false, Offset(cx - s * 0.2f, cy - s * 0.12f), Size(s * 0.12f, s * 0.24f), style = Stroke(s * 0.02f, cap = StrokeCap.Round))
                for (i in 0..2) for (j in 0..1) drawArc(Color.White.copy(alpha = 0.5f), 0f, 180f, false, Offset(cx - s * 0.02f + i * s * 0.08f, cy - s * 0.08f + j * s * 0.08f), Size(s * 0.07f, s * 0.06f), style = Stroke(s * 0.012f))
                drawCircle(Color.White, s * 0.05f, Offset(cx - s * 0.2f, cy - s * 0.04f)); drawCircle(INK, s * 0.028f, Offset(cx - s * 0.2f, cy - s * 0.04f))
            }
            "yarn" -> {
                val ball = oval(cx - s * 0.05f, cy - s * 0.03f, s * 0.3f, s * 0.3f)
                val thread = Path().apply {
                    moveTo(cx + s * 0.18f, cy + s * 0.2f)
                    cubicTo(cx + s * 0.4f, cy + s * 0.3f, cx + s * 0.1f, cy + s * 0.42f, cx + s * 0.42f, cy + s * 0.42f)
                }
                dieCut(listOf(ball))
                drawPath(thread, WHITE, style = Stroke(s * 0.08f, cap = StrokeCap.Round))
                drawPath(ball, Color(0xFFF472B6))
                for (k in 0..3) drawArc(Color(0xFFDB2777), -40f + k * 25f, 140f, false, Offset(cx - s * 0.32f + k * s * 0.05f, cy - s * 0.28f + k * s * 0.03f), Size(s * 0.5f, s * 0.46f), style = Stroke(s * 0.018f, cap = StrokeCap.Round))
                drawPath(thread, Color(0xFFDB2777), style = Stroke(s * 0.025f, cap = StrokeCap.Round))
            }
            "trail" -> {
                val ps = listOf(Offset(0.28f, 0.78f) to -20f, Offset(0.46f, 0.6f) to 10f, Offset(0.58f, 0.38f) to -20f, Offset(0.76f, 0.2f) to 10f)
                val paths = ps.flatMap { (o, _) -> pawPaths(o.x * size.width, o.y * size.height, s * 0.26f) }
                dieCut(paths)
                paths.forEach { drawPath(it, Color(0xFF7C5CFF)) }
            }
            else -> drawCircle(PINK, s * 0.3f, Offset(cx, cy), style = Fill)
        }
    }
}
