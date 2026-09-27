package com.wallisland.walllock

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Colour work on the album cover: the blurred wallpaper and the colour the big cover fades into. */
object Art {

    private const val SMALL = 48

    /** A soft, heavily blurred copy of the cover, small; it's drawn scaled up with filtering. */
    fun blurred(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src.soft(), SMALL, SMALL, true)
        val px = IntArray(SMALL * SMALL)
        small.getPixels(px, 0, SMALL, 0, 0, SMALL, SMALL)
        repeat(3) {
            boxBlur(px, SMALL, SMALL, 4, horizontal = true)
            boxBlur(px, SMALL, SMALL, 4, horizontal = false)
        }
        val out = Bitmap.createBitmap(SMALL, SMALL, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, SMALL, 0, 0, SMALL, SMALL)
        return out
    }

    /**
     * The average colour of the bottom edge of the cover, darkened enough for white text, so the
     * big cover can melt into the background below it.
     */
    fun edgeColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src.soft(), SMALL, SMALL, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in SMALL - SMALL / 6 until SMALL) for (x in 0 until SMALL) {
            val c = small.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[2] = min(hsv[2], 0.42f)
        return Color.HSVToColor(hsv)
    }

    /** Hardware bitmaps can't be read pixel by pixel. */
    private fun Bitmap.soft(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val line = IntArray(len)
        for (l in 0 until lines) {
            for (i in 0 until len) line[i] = px[if (horizontal) l * w + i else i * w + l]
            for (i in 0 until len) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in max(0, i - radius)..min(len - 1, i + radius)) {
                    val c = line[k]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                px[if (horizontal) l * w + i else i * w + l] =
                    (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
