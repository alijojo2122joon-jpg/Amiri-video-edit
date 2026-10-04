package com.amiri.cut.media

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.max

enum class RotoBrushMode(val label: String) { SMART("✨ Smart · snaps to edges"), SMART_CUT("✨ Smart remove"), ADD("Character · hard"), HAIR("Hair · soft"), SUBTRACT("Subtract"), LASSO("Lasso fill"), LASSO_CUT("Lasso cut") }

/** Rasterizes roto brush strokes into a mask (white + alpha; alpha 255 = keep). */
object RotoPainter {

    /**
     * @param points stroke points normalised to 0..1 in mask space
     * @param diameter brush diameter as a fraction of mask width
     * @param feather edge softness as a fraction of mask width
     */
    fun paint(
        base: Bitmap?,
        width: Int,
        height: Int,
        points: List<Pair<Float, Float>>,
        mode: RotoBrushMode,
        diameter: Float,
        feather: Float,
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (base != null) canvas.drawBitmap(base, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        if (points.isEmpty()) return out

        val path = Path()
        val (x0, y0) = points.first()
        path.moveTo(x0 * width, y0 * height)
        if (points.size == 1) path.lineTo(x0 * width + 0.5f, y0 * height)
        for (i in 1 until points.size) {
            val (x, y) = points[i]
            path.lineTo(x * width, y * height)
        }
        val lasso = mode == RotoBrushMode.LASSO || mode == RotoBrushMode.LASSO_CUT
        if (lasso) path.close()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = if (lasso) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = max(1f, diameter * width)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            val f = feather * width
            if (f >= 0.5f) maskFilter = BlurMaskFilter(f, BlurMaskFilter.Blur.NORMAL)
            if (mode == RotoBrushMode.SUBTRACT || mode == RotoBrushMode.LASSO_CUT) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
        }
        canvas.drawPath(path, paint)
        return out
    }

    fun empty(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}
