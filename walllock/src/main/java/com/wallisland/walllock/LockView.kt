package com.wallisland.walllock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole lock screen player, drawn by hand so the cover can grow smoothly.
 *
 * Small: a frosted card near the bottom with a thumbnail, over a blurred copy of the cover.
 * Big (tap the cover): the cover springs up to fill the top of the screen under the clock and melts
 * into its own colour below, like the iPhone's full-screen artwork. Tap it again to shrink it.
 */
@SuppressLint("ViewConstructor")
class LockView(ctx: Context, startExpanded: Boolean) : View(ctx) {

    var onExpandedChanged: ((Boolean) -> Unit)? = null
    var onUnlock: (() -> Unit)? = null
    var onOpenApp: ((NowPlaying.Track) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var track: NowPlaying.Track? = null
    private var artKey: String? = null
    private var blur: Bitmap? = null
    private var edge = 0xFF202020.toInt()

    // ---- Expansion spring: p is 0 (small card) to 1 (big cover), and may overshoot a little. -------
    private var expanded = startExpanded
    private var p = if (startExpanded) 1f else 0f
    private var pv = 0f
    private var lastFrame = 0L
    private var springing = false

    private val spring = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.032f)
            lastFrame = now
            val target = if (expanded) 1f else 0f
            val k = 260f
            val c = 2f * sqrt(k) * 0.72f
            pv += (-k * (p - target) - c * pv) * dt
            p += pv * dt
            if (abs(p - target) < 0.001f && abs(pv) < 0.01f) {
                p = target
                pv = 0f
                springing = false
            } else {
                postOnAnimation(this)
            }
            invalidate()
        }
    }

    // ---- Once a second for the clock and progress. ------------------------------------------------
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 500)
        }
    }

    // ---- Insets and layout. -----------------------------------------------------------------------
    private var insetTop = 0
    private var insetBottom = 0

    private val card = RectF()
    private val small = RectF()
    private val big = RectF()
    private val art = RectF()
    private val bar = RectF()
    private val prevHit = RectF()
    private val playHit = RectF()
    private val nextHit = RectF()
    private val textHit = RectF()

    // ---- Paints. ---------------------------------------------------------------------------------
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(86f)
        typeface = weight(600)
        setShadowLayer(dp(12f), 0f, dp(1f), 0x40000000)
    }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(19f)
        typeface = weight(600)
        setShadowLayer(dp(8f), 0f, dp(1f), 0x40000000)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(17f)
        typeface = weight(600)
    }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dp(16f)
        typeface = weight(400)
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = dp(12f)
        typeface = weight(500)
        isFakeBoldText = false
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
        typeface = weight(500)
    }
    private val glyph = Path()
    private val clip = Path()
    private val src = Rect()

    private val timeFmt = SimpleDateFormat(
        if (DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm", Locale.getDefault()
    )
    private val dateFmt = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault())

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    fun setTrack(t: NowPlaying.Track) {
        track = t
        if (t.artKey != artKey) {
            artKey = t.artKey
            val a = t.art
            blur = a?.let { runCatching { Art.blurred(it) }.getOrNull() }
            edge = a?.let { runCatching { Art.edgeColor(it) }.getOrNull() } ?: 0xFF202020.toInt()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(spring)
        super.onDetachedFromWindow()
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        insetTop = insets.systemWindowInsetTop
        insetBottom = insets.systemWindowInsetBottom
        requestLayout()
        return insets
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = width.toFloat()
        val h = height.toFloat()
        val side = dp(16f)
        val cardH = dp(196f)
        val cardBottom = h - insetBottom - dp(72f)
        card.set(side, cardBottom - cardH, w - side, cardBottom)

        val thumb = dp(64f)
        small.set(card.left + dp(16f), card.top + dp(16f), card.left + dp(16f) + thumb, card.top + dp(16f) + thumb)

        // Big: as wide as the screen (capped on wide or short screens), from the very top.
        val size = min(w, min(h * 0.62f, card.top + dp(24f)))
        big.set((w - size) / 2f, 0f, (w + size) / 2f, size)

        val barY = small.bottom + dp(22f)
        bar.set(card.left + dp(16f), barY, card.right - dp(16f), barY + dp(6f))

        val cy = card.bottom - dp(36f)
        val cx = card.centerX()
        val hit = dp(30f)
        playHit.set(cx - hit, cy - hit, cx + hit, cy + hit)
        prevHit.set(cx - dp(84f) - hit, cy - hit, cx - dp(84f) + hit, cy + hit)
        nextHit.set(cx + dp(84f) - hit, cy - hit, cx + dp(84f) + hit, cy + hit)
    }

    // ---- Drawing. --------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = track
        val q = p.coerceIn(0f, 1f)

        canvas.save()
        canvas.translate(0f, dragY)

        // 1. Wallpaper: the blurred cover, dimmed; then the cover's own colour as it grows.
        val b = blur
        if (b != null) {
            src.set(0, 0, b.width, b.height)
            val scale = max(w / b.width, h / b.height)
            val bw = b.width * scale
            val bh = b.height * scale
            art.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            canvas.drawBitmap(b, src, art, bmpPaint)
            canvas.drawColor(0x66000000)
        } else {
            canvas.drawColor(0xFF1A1A1A.toInt())
        }
        if (q > 0f) {
            fill.color = edge
            fill.alpha = (255 * q).toInt()
            canvas.drawRect(0f, 0f, w, h, fill)
        }

        // 2. The frosted card, fading out as the cover leaves it.
        fill.color = 0x2EFFFFFF
        fill.alpha = (0x2E * (1f - q)).toInt()
        if (fill.alpha > 0) canvas.drawRoundRect(card, dp(26f), dp(26f), fill)

        // 3. The cover, on its way between the thumbnail and the top of the screen.
        lerp(small, big, p, art)
        val radius = dp(10f) * (1f - q)
        drawCover(canvas, t?.art, art, radius)
        if (q > 0f) {
            // Melt the bottom of the big cover into the background colour.
            val fadeTop = art.top + art.height() * 0.62f
            fade.shader = LinearGradient(
                0f, fadeTop, 0f, art.bottom + 1f,
                edge and 0x00FFFFFF, edge, Shader.TileMode.CLAMP
            )
            fade.alpha = (255 * q).toInt()
            canvas.drawRect(art.left - 1f, fadeTop, art.right + 1f, art.bottom + 1f, fade)
            if (art.width() < w) {
                // On wide screens the sides blend too.
                fill.color = edge
                fill.alpha = (255 * q).toInt()
                canvas.drawRect(0f, 0f, art.left, h, fill)
                canvas.drawRect(art.right, 0f, w, h, fill)
            }
            // A little shade behind the clock so it reads on bright covers.
            fade.shader = LinearGradient(0f, 0f, 0f, dp(260f), 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
            fade.alpha = (255 * q).toInt()
            canvas.drawRect(0f, 0f, w, dp(260f), fade)
        }

        // 4. Date and time.
        val now = Date()
        val dateY = insetTop + dp(58f)
        canvas.drawText(dateFmt.format(now), w / 2f, dateY, datePaint)
        canvas.drawText(timeFmt.format(now), w / 2f, dateY + dp(84f), clockPaint)

        // 5. Song, progress and buttons.
        if (t != null) drawPlayer(canvas, t, q)

        // 6. How to leave.
        hintPaint.alpha = (0x99 * (1f + dragY / dp(160f)).coerceIn(0f, 1f)).toInt()
        canvas.drawText("Swipe up to open", w / 2f, h - insetBottom - dp(28f), hintPaint)
        canvas.restore()
    }

    private fun drawCover(canvas: Canvas, a: Bitmap?, r: RectF, radius: Float) {
        canvas.save()
        clip.reset()
        clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        if (a != null) {
            // Centre-crop in case the app hands over a cover that isn't square.
            val s = min(a.width, a.height)
            src.set((a.width - s) / 2, (a.height - s) / 2, (a.width + s) / 2, (a.height + s) / 2)
            canvas.drawBitmap(a, src, r, bmpPaint)
        } else {
            fill.color = 0xFF3A3A3C.toInt()
            fill.alpha = 255
            canvas.drawRect(r, fill)
            // A simple note when there's no cover.
            val cx = r.centerX()
            val cy = r.centerY()
            val u = r.width() / 12f
            fill.color = 0xFF8E8E93.toInt()
            canvas.drawCircle(cx - u, cy + u * 2f, u * 1.2f, fill)
            canvas.drawRect(cx - u + u * 0.8f, cy - u * 3f, cx + u * 0.4f, cy + u * 2f, fill)
            canvas.drawRect(cx - u + u * 0.8f, cy - u * 3f, cx + u * 2.4f, cy - u * 1.8f, fill)
        }
        canvas.restore()
    }

    private fun drawPlayer(canvas: Canvas, t: NowPlaying.Track, q: Float) {
        // Title and artist sit beside the thumbnail, then slide left into the space it leaves.
        val textLeft = lerp(small.right + dp(14f), card.left + dp(16f), q)
        val textRight = card.right - dp(16f)
        val titleY = lerp(small.top + dp(26f), small.top + dp(20f), q)
        val avail = textRight - textLeft
        canvas.drawText(ellipsize(t.title, titlePaint, avail), textLeft, titleY, titlePaint)
        canvas.drawText(ellipsize(t.artist, artistPaint, avail), textLeft, titleY + dp(24f), artistPaint)
        textHit.set(textLeft, titleY - dp(22f), textRight, titleY + dp(32f))

        // Progress.
        val dur = t.durationMs
        val pos = scrubMs ?: t.positionNow()
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val thick = if (scrubMs != null) dp(10f) else dp(6f)
        val cy = bar.centerY()
        val r = thick / 2f
        fill.color = 0x40FFFFFF
        fill.alpha = 0x40
        canvas.drawRoundRect(bar.left, cy - r, bar.right, cy + r, r, r, fill)
        fill.color = if (scrubMs != null) Color.WHITE else 0xCCFFFFFF.toInt()
        canvas.drawRoundRect(bar.left, cy - r, bar.left + bar.width() * frac, cy + r, r, r, fill)
        val ty = cy + dp(22f)
        timePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(fmt(pos), bar.left, ty, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (dur > 0) "-" + fmt(dur - pos) else "", bar.right, ty, timePaint)

        // Buttons.
        fill.color = Color.WHITE
        fill.alpha = if (pressed === playHit) 0x80 else 0xFF
        if (t.playing) drawPause(canvas, playHit.centerX(), playHit.centerY(), dp(15f))
        else drawPlay(canvas, playHit.centerX(), playHit.centerY(), dp(15f))
        fill.alpha = if (pressed === prevHit) 0x80 else 0xFF
        drawSkip(canvas, prevHit.centerX(), prevHit.centerY(), dp(11f), forward = false)
        fill.alpha = if (pressed === nextHit) 0x80 else 0xFF
        drawSkip(canvas, nextHit.centerX(), nextHit.centerY(), dp(11f), forward = true)
    }

    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        glyph.reset()
        glyph.moveTo(cx - s * 0.7f, cy - s)
        glyph.lineTo(cx + s, cy)
        glyph.lineTo(cx - s * 0.7f, cy + s)
        glyph.close()
        canvas.drawPath(glyph, fill)
    }

    private fun drawPause(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val bw = s * 0.52f
        val gap = s * 0.34f
        val rr = bw * 0.3f
        canvas.drawRoundRect(cx - gap - bw, cy - s, cx - gap, cy + s, rr, rr, fill)
        canvas.drawRoundRect(cx + gap, cy - s, cx + gap + bw, cy + s, rr, rr, fill)
    }

    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, s: Float, forward: Boolean) {
        val dir = if (forward) 1f else -1f
        glyph.reset()
        for (k in 0..1) {
            val x0 = cx + dir * (if (k == 0) -s * 1.4f else 0f)
            glyph.moveTo(x0, cy - s)
            glyph.lineTo(x0 + dir * s * 1.4f, cy)
            glyph.lineTo(x0, cy + s)
            glyph.close()
        }
        canvas.drawPath(glyph, fill)
    }

    // ---- Touch. ----------------------------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragY = 0f
    private var mode = Mode.NONE
    private var pressed: RectF? = null
    private var scrubMs: Long? = null

    private enum class Mode { NONE, TAP, SCRUB, SWIPE }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downAt = SystemClock.uptimeMillis()
                mode = Mode.TAP
                pressed = listOf(playHit, prevHit, nextHit).firstOrNull { it.contains(e.x, e.y) }
                if (pressed == null && track?.durationMs?.let { it > 0 } == true &&
                    e.x in bar.left - dp(8f)..bar.right + dp(8f) && abs(e.y - bar.centerY()) < dp(20f)
                ) {
                    mode = Mode.SCRUB
                    scrubTo(e.x)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.SCRUB -> scrubTo(e.x)
                Mode.TAP -> if (e.y - downY < -dp(12f) && abs(e.y - downY) > abs(e.x - downX)) {
                    mode = Mode.SWIPE
                    pressed = null
                } else if (abs(e.x - downX) > dp(16f) || abs(e.y - downY) > dp(16f)) {
                    // Wandered off: not a tap any more.
                    mode = Mode.NONE
                    pressed = null
                    invalidate()
                }
                Mode.SWIPE -> {
                    dragY = min(0f, e.y - downY)
                    invalidate()
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.SCRUB -> {
                        scrubMs?.let { track?.controller?.transportControls?.seekTo(it) }
                        scrubMs = null
                    }
                    Mode.SWIPE -> {
                        val speed = dragY / max(1L, SystemClock.uptimeMillis() - downAt) * 1000f
                        if (dragY < -dp(110f) || speed < -dp(900f)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            onUnlock?.invoke()
                        }
                        settleDrag()
                    }
                    Mode.TAP -> tap(e.x, e.y)
                    Mode.NONE -> Unit
                }
                pressed = null
                mode = Mode.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubMs = null
                pressed = null
                mode = Mode.NONE
                settleDrag()
                invalidate()
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val t = track ?: return
        val controls = t.controller.transportControls
        when {
            playHit.contains(x, y) -> if (t.playing) controls.pause() else controls.play()
            prevHit.contains(x, y) -> controls.skipToPrevious()
            nextHit.contains(x, y) -> controls.skipToNext()
            art.contains(x, y) -> toggle()
            textHit.contains(x, y) -> onOpenApp?.invoke(t)
            else -> return
        }
        performClick()
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun toggle() {
        expanded = !expanded
        onExpandedChanged?.invoke(expanded)
        if (!springing) {
            springing = true
            lastFrame = SystemClock.uptimeMillis()
            postOnAnimation(spring)
        }
    }

    private fun scrubTo(x: Float) {
        val dur = track?.durationMs ?: return
        val f = ((x - bar.left) / bar.width()).coerceIn(0f, 1f)
        scrubMs = (dur * f).toLong()
        invalidate()
    }

    private fun settleDrag() {
        if (dragY == 0f) return
        val start = dragY
        val anim = android.animation.ValueAnimator.ofFloat(1f, 0f).setDuration(260)
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            dragY = start * (it.animatedValue as Float)
            invalidate()
        }
        anim.start()
    }

    // ---- Helpers. --------------------------------------------------------------------------------

    private fun weight(w: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, w, false)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun ellipsize(s: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(s, paint, max(0f, width), TextUtils.TruncateAt.END).toString()

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun lerp(a: RectF, b: RectF, f: Float, out: RectF) {
        out.set(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))
    }
}
