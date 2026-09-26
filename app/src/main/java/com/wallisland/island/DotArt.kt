package com.wallisland.island

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.sqrt

/**
 * Turns a picture into a halftone grid of dots, dot size following brightness. Dots are white, or
 * (when [draw] is asked for colour) tinted with the picture's own colours, like a colour halftone print.
 * Album art and avatars become part of the dot-matrix look instead of fighting it.
 */
class DotArt(source: Bitmap, private val grid: Int) {
    private val levels = FloatArray(grid * grid)
    private val colors = IntArray(grid * grid)

    /** The cover's most vivid colour, brightened to read on black. Used to tint the equaliser. */
    val accent: Int

    init {
        val small = Bitmap.createScaledBitmap(source.copyIfHardware(), grid, grid, true)
        val px = IntArray(grid * grid)
        small.getPixels(px, 0, grid, 0, 0, grid, grid)
        var lo = 1f
        var hi = 0f
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24 and 0xFF) / 255f
            val l = (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f * a
            levels[i] = l
            if (l < lo) lo = l
            if (l > hi) hi = l
            colors[i] = vivid(c)
        }
        accent = pickAccent(px)
        // Stretch contrast so dark covers still produce a readable pattern.
        val span = (hi - lo).coerceAtLeast(0.15f)
        for (i in levels.indices) levels[i] = ((levels[i] - lo) / span).coerceIn(0f, 1f)
        if (small !== source) small.recycle()
    }

    fun draw(canvas: Canvas, box: RectF, paint: Paint, round: Boolean, alpha: Int, colored: Boolean = false) {
        val pitch = box.width() / grid
        val maxR = pitch * 0.5f
        val cx0 = box.centerX()
        val cy0 = box.centerY()
        val radius = box.width() / 2f
        val saved = paint.color
        for (row in 0 until grid) for (col in 0 until grid) {
            val x = box.left + pitch * (col + 0.5f)
            val y = box.top + pitch * (row + 0.5f)
            if (round) {
                val dx = x - cx0
                val dy = y - cy0
                if (sqrt(dx * dx + dy * dy) > radius - pitch * 0.3f) continue
            }
            val v = levels[row * grid + col]
            if (v < 0.08f) {
                paint.color = Look.DOT_OFF
                paint.alpha = alpha
                canvas.drawCircle(x, y, maxR * 0.28f, paint)
            } else {
                paint.color = if (colored) colors[row * grid + col] else Look.WHITE
                paint.alpha = alpha
                canvas.drawCircle(x, y, maxR * (0.3f + 0.62f * v), paint)
            }
        }
        paint.color = saved
    }

    companion object {
        /** Keeps the hue, lifts saturation a touch and brightness a lot, so colours glow on black. */
        private fun vivid(c: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(c or (0xFF shl 24), hsv)
            hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
            hsv[2] = hsv[2].coerceAtLeast(0.72f)
            // Near-grey pixels stay a clean white rather than a muddy tint.
            if (hsv[1] < 0.12f) return Look.WHITE
            return Color.HSVToColor(hsv)
        }

        private fun pickAccent(px: IntArray): Int {
            val hsv = FloatArray(3)
            var best = Look.WHITE
            var bestScore = 0.18f
            for (c in px) {
                Color.colorToHSV(c or (0xFF shl 24), hsv)
                val score = hsv[1] * (0.35f + 0.65f * hsv[2])
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            return if (best == Look.WHITE) Look.WHITE else vivid(best)
        }

        private fun Bitmap.copyIfHardware(): Bitmap =
            if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    }
}

/** A plain picture clipped to a rounded box, optionally grayscale. */
class PictureArt(source: Bitmap, grayscale: Boolean) {
    private val bmp = source
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (grayscale) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    private val m = Matrix()

    fun draw(canvas: Canvas, box: RectF, corner: Float, alpha: Int) {
        val scale = maxOf(box.width() / bmp.width, box.height() / bmp.height)
        m.setScale(scale, scale)
        m.postTranslate(
            box.left + (box.width() - bmp.width * scale) / 2f,
            box.top + (box.height() - bmp.height * scale) / 2f,
        )
        paint.shader.setLocalMatrix(m)
        paint.alpha = alpha
        canvas.drawRoundRect(box, corner, corner, paint)
    }
}
