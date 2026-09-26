package com.wallisland.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue

/**
 * The palette and type of the whole app. Monochrome, one red, dots everywhere.
 */
object Look {
    const val BLACK = 0xFF000000.toInt()
    const val SURFACE = 0xFF0E0E0E.toInt()
    const val RAISED = 0xFF161616.toInt()
    const val LINE = 0xFF262626.toInt()
    const val DOT_OFF = 0xFF2B2B2B.toInt()
    const val GREY = 0xFF8C8C8C.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val RED = 0xFFD71921.toInt()

    @Volatile private var dotFace: Typeface? = null
    @Volatile private var monoFace: Typeface? = null
    @Volatile private var monoBoldFace: Typeface? = null

    /** Doto: an open-source dot-matrix face, the closest free cousin of NDot. */
    fun dot(ctx: Context): Typeface =
        dotFace ?: loadFont(ctx, R.font.doto, Typeface.MONOSPACE).also { dotFace = it }

    fun mono(ctx: Context): Typeface =
        monoFace ?: loadFont(ctx, R.font.space_mono, Typeface.MONOSPACE).also { monoFace = it }

    fun monoBold(ctx: Context): Typeface =
        monoBoldFace ?: loadFont(ctx, R.font.space_mono_bold, Typeface.MONOSPACE).also { monoBoldFace = it }

    private fun loadFont(ctx: Context, id: Int, fallback: Typeface): Typeface =
        try {
            ctx.resources.getFont(id)
        } catch (_: Exception) {
            fallback
        }

    /** A paint set up for dot-matrix text; heavier weight so the dots read at small sizes. */
    fun dotPaint(ctx: Context, sizePx: Float, color: Int = WHITE, weight: Int = 800): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = dot(ctx)
            textSize = sizePx
            this.color = color
            fontVariationSettings = "'wght' $weight, 'ROND' 100"
        }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * Tiny dot-matrix icons. Each glyph is a grid of rows; '#' is a lit dot, anything else is off.
 * They're drawn as circles so they sit in the same visual language as the Doto type.
 */
enum class Glyph(vararg rows: String) {
    PLAY(
        "##.....",
        "####...",
        "######.",
        "#######",
        "######.",
        "####...",
        "##.....",
    ),
    PAUSE(
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
    ),
    NEXT(
        "#....##",
        "##...##",
        "###..##",
        "####.##",
        "###..##",
        "##...##",
        "#....##",
    ),
    PREV(
        "##....#",
        "##...##",
        "##..###",
        "##.####",
        "##..###",
        "##...##",
        "##....#",
    ),
    FORWARD(
        "#...#...",
        "##..##..",
        "###.###.",
        "########",
        "###.###.",
        "##..##..",
        "#...#...",
    ),
    REWIND(
        "...#...#",
        "..##..##",
        ".###.###",
        "########",
        ".###.###",
        "..##..##",
        "...#...#",
    ),
    BOLT(
        "..###",
        ".###.",
        "###..",
        "#####",
        "..###",
        ".###.",
        "###..",
    ),
    BELL(
        "...#...",
        "..###..",
        ".#####.",
        ".#####.",
        ".#####.",
        "#######",
        "...#...",
    ),
    VIBRATE(
        "..###..",
        "#.#.#.#",
        "#.#.#.#",
        "#.#.#.#",
        "#.#.#.#",
        "#.#.#.#",
        "..###..",
    ),
    BELL_OFF(
        "...#...",
        "..#.#..",
        ".#...#.",
        ".#...#.",
        ".#...#.",
        "#######",
        "...#...",
    );

    val cols = rows.maxOf { it.length }
    val rowCount = rows.size
    private val lit: BooleanArray = BooleanArray(cols * rowCount).also { arr ->
        rows.forEachIndexed { r, line -> line.forEachIndexed { c, ch -> arr[r * cols + c] = ch == '#' } }
    }

    /** Draws the glyph so its dot grid fills a box of [size] height, left edge at [left], centred on [cy]. */
    fun draw(canvas: Canvas, left: Float, cy: Float, size: Float, paint: Paint, offColor: Int? = null) {
        val pitch = size / rowCount
        val r = pitch * 0.42f
        val top = cy - size / 2f
        val on = paint.color
        for (row in 0 until rowCount) for (col in 0 until cols) {
            val isLit = lit[row * cols + col]
            if (!isLit && offColor == null) continue
            paint.color = if (isLit) on else offColor!!
            canvas.drawCircle(left + pitch * (col + 0.5f), top + pitch * (row + 0.5f), r, paint)
        }
        paint.color = on
    }

    fun width(size: Float): Float = size / rowCount * cols
}
