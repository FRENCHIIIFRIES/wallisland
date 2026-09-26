package com.wallisland.island

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The island itself: a black pill that springs between shapes and draws its own content.
 * Everything is painted on one canvas so the morph between states stays a single continuous shape.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val prefs: Prefs, private val host: Host) : View(context) {

    interface Host {
        /** The window should be at least this big (px) so the pill never clips. */
        fun onWindowSize(width: Int, height: Int)
        fun onTouchable(touchable: Boolean)
        fun openSettings()
    }

    private enum class Mode { IDLE, CALL, MEDIA, MEDIA_EXPANDED, NOTICE, CHARGING, RINGER }

    private sealed class Transient {
        data class NoticeT(val notice: Notice) : Transient()
        data class ChargeT(val level: Int, val charging: Boolean) : Transient()
        data class RingerT(val mode: Int) : Transient()
    }

    // ---- State -----------------------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaInfo? = null
    private var call: CallInfo? = null
    private var callDismissed: String? = null
    private var mediaArt: DotArt? = null
    private var mediaArtSmall: DotArt? = null
    private var mediaPicture: PictureArt? = null
    private var mediaDismissed: MediaInfo? = null
    private var mediaExpanded = false
    private var pausedAt = 0L
    private var transient: Transient? = null
    private var noticeAvatar: PictureArt? = null
    private var hidden = false
    private var screenOn = true
    private var previewUntil = 0L
    private var pressed = false

    private var shownMode = Mode.IDLE
    private var shownToken: Any? = null
    private var shownTransient: Transient? = null

    // ---- Animation -------------------------------------------------------------------------------------

    private class Spring(var value: Float, private val stiffness: Float, private val damping: Float, private val eps: Float) {
        var target = value
        private var velocity = 0f
        val moving get() = value != target || velocity != 0f

        fun step(dtSec: Float) {
            if (!moving) return
            val omega = sqrt(stiffness)
            var left = dtSec
            while (left > 0f) {
                val dt = min(left, 1f / 240f)
                val force = -stiffness * (value - target) - 2f * damping * omega * velocity
                velocity += force * dt
                value += velocity * dt
                left -= dt
            }
            if (abs(value - target) < eps && abs(velocity) < eps * 10f) {
                value = target
                velocity = 0f
            }
        }

        fun snap(v: Float) {
            value = v
            target = v
            velocity = 0f
        }
    }

    private val springW = Spring(0f, 340f, 0.74f, 0.5f)
    private val springH = Spring(0f, 340f, 0.78f, 0.5f)
    private val springAlpha = Spring(0f, 260f, 1f, 0.004f)
    private val springScale = Spring(1f, 600f, 0.7f, 0.001f)

    /** Content fades out, swaps, then fades back in while the shape is still settling. */
    private var fadeStart = 0L
    private var fadingOut = false
    private var contentAlpha = 1f
    private var pendingMode = Mode.IDLE
    private var pendingToken: Any? = null
    private var pendingTransient: Transient? = null
    private var lastFrame = 0L
    private var frameScheduled = false
    private var windowW = 0
    private var windowH = 0

    // ---- Paint -----------------------------------------------------------------------------------------

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.BLACK }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = textPaint(Look.dot(context), 11f, Look.GREY).apply {
        fontVariationSettings = "'wght' 800, 'ROND' 100"
        letterSpacing = 0.08f
    }
    private val bigDotPaint = textPaint(Look.dot(context), 15f, Look.WHITE).apply {
        fontVariationSettings = "'wght' 900, 'ROND' 100"
    }
    private val titlePaint = textPaint(Look.monoBold(context), 14f, Look.WHITE)
    private val bodyPaint = textPaint(Look.mono(context), 12f, Look.GREY)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val pillRect = RectF()

    private val hitPrev = RectF()
    private val hitPlay = RectF()
    private val hitNext = RectF()
    private val hitBar = RectF()
    private val hitOpen = RectF()
    private val hitArt = RectF()
    private val hitRewind = RectF()
    private val hitForward = RectF()
    private var barLeft = 0f
    private var barRight = 0f
    private var scrubbing = false
    private var scrubFrac = 0f
    private var seekHoldPos = 0L
    private var seekHoldUntil = 0L

    private fun textPaint(face: android.graphics.Typeface, sizeSp: Float, color: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = context.sp(sizeSp)
            this.color = color
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        applyPrefs()
        springW.snap(springW.target)
        springH.snap(springH.target)
    }

    // ---- Public API ------------------------------------------------------------------------------------

    fun applyPrefs() {
        resolve()
    }

    /** Briefly force the pill visible so size/position sliders give live feedback. */
    fun preview() {
        previewUntil = SystemClock.uptimeMillis() + 1800
        handler.postDelayed({ resolve() }, 1900)
        resolve()
    }

    fun setCall(info: CallInfo?) {
        call = info
        if (info == null) callDismissed = null
        resolve()
    }

    fun setMedia(info: MediaInfo?) {
        val old = media
        media = info
        if (info != null && old != null && info.positionAt != old.positionAt) seekHoldUntil = 0L
        if (info == null) {
            mediaArt = null; mediaArtSmall = null; mediaPicture = null
            mediaExpanded = false
        } else if (old?.art !== info.art || !info.sameTrack(old)) {
            val art = info.art
            mediaArt = art?.let { runCatching { DotArt(it, 18) }.getOrNull() }
            mediaArtSmall = art?.let { runCatching { DotArt(it, 9) }.getOrNull() }
            mediaPicture = art?.let { PictureArt(it, grayscale = false) }
        }
        if (info != null && !info.sameTrack(mediaDismissed)) mediaDismissed = null
        if (info != null && !info.playing) {
            if (old?.playing != false) pausedAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(pauseTimeout)
            handler.postDelayed(pauseTimeout, PAUSE_LINGER_MS + 50)
        }
        resolve()
    }

    fun showNotice(notice: Notice) {
        if (!prefs.showNotifications || hidden || !screenOn) return
        noticeAvatar = notice.avatar?.let { PictureArt(it, grayscale = prefs.dotArt) }
        showTransient(Transient.NoticeT(notice), prefs.noticeSeconds * 1000L)
        buzz()
    }

    fun removeNotice(key: String) {
        val t = transient
        if (t is Transient.NoticeT && t.notice.key == key) endTransient()
    }

    fun showCharging(level: Int, charging: Boolean) {
        if (!prefs.showCharging || hidden || !screenOn) return
        if (transient is Transient.NoticeT) return
        showTransient(Transient.ChargeT(level, charging), 3200)
    }

    fun showRinger(mode: Int) {
        if (!prefs.showRinger || hidden || !screenOn) return
        if (transient is Transient.NoticeT) return
        showTransient(Transient.RingerT(mode), 2200)
    }

    fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        if (hide) {
            mediaExpanded = false
            transient = null
        }
        resolve()
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (!on) {
            mediaExpanded = false
            transient = null
        }
        resolve()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    // ---- State machine ---------------------------------------------------------------------------------

    private val transientTimeout = Runnable { endTransient() }
    private val pauseTimeout = Runnable { resolve() }

    private fun showTransient(t: Transient, durationMs: Long) {
        transient = t
        handler.removeCallbacks(transientTimeout)
        handler.postDelayed(transientTimeout, durationMs)
        resolve()
    }

    private fun endTransient() {
        handler.removeCallbacks(transientTimeout)
        transient = null
        resolve()
    }

    private fun mediaVisible(): Boolean {
        val m = media ?: return false
        if (!prefs.showMedia || m.sameTrack(mediaDismissed)) return false
        return m.playing || SystemClock.uptimeMillis() - pausedAt < PAUSE_LINGER_MS || mediaExpanded
    }

    private fun resolve() {
        val t = transient
        val mode = when {
            t is Transient.NoticeT -> Mode.NOTICE
            t is Transient.ChargeT -> Mode.CHARGING
            t is Transient.RingerT -> Mode.RINGER
            mediaExpanded && mediaVisible() -> Mode.MEDIA_EXPANDED
            call != null && call?.key != callDismissed -> Mode.CALL
            mediaVisible() -> Mode.MEDIA
            else -> Mode.IDLE
        }
        if (mode == Mode.IDLE) mediaExpanded = false

        val idleW = context.dp(prefs.width.toFloat())
        val idleH = context.dp(prefs.height.toFloat())
        val bigW = min(resources.displayMetrics.widthPixels - context.dp(24f), context.dp(360f))
        val (w, h) = when (mode) {
            Mode.IDLE -> idleW to idleH
            Mode.MEDIA, Mode.CHARGING, Mode.RINGER, Mode.CALL -> idleW + 2 * context.dp(SIDE_DP) to idleH
            Mode.NOTICE -> bigW to idleH + context.dp(60f)
            Mode.MEDIA_EXPANDED -> bigW to idleH + context.dp(172f)
        }
        val previewing = SystemClock.uptimeMillis() < previewUntil
        val visible = !hidden && prefs.enabled && (screenOn || previewing) &&
            (mode != Mode.IDLE || prefs.showIdle || previewing)

        springW.target = w
        springH.target = h
        springAlpha.target = if (visible) 1f else 0f
        host.onTouchable(visible)

        val token: Any? = when (mode) {
            Mode.NOTICE -> (t as Transient.NoticeT).notice.key + t.notice.title + t.notice.text
            Mode.MEDIA, Mode.MEDIA_EXPANDED -> mode.name + media?.title
            Mode.CHARGING, Mode.RINGER -> t
            Mode.CALL -> call?.key
            Mode.IDLE -> Mode.IDLE
        }
        if (mode != pendingMode || token != pendingToken) {
            pendingMode = mode
            pendingToken = token
            if ((mode != shownMode || token != shownToken) && !fadingOut) {
                fadingOut = true
                // Start the fade-out from wherever the fade-in currently is, so there's no flash.
                fadeStart = SystemClock.uptimeMillis() - ((1f - contentAlpha) * FADE_OUT_MS).toLong()
            }
        }
        pendingTransient = t
        if (!fadingOut) shownTransient = t
        ensureWindow()
        kick()
    }

    // ---- Frame loop ------------------------------------------------------------------------------------

    private val frame = Runnable {
        frameScheduled = false
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        springW.step(dt); springH.step(dt); springAlpha.step(dt); springScale.step(dt)
        stepFade(now)
        invalidate()
        if (!springW.moving && !springH.moving) ensureWindow()
        if (animating()) kick() else {
            lastFrame = 0L
            if (needsTicker()) {
                frameScheduled = true
                // Idle ambient motion (visualizer, playhead) doesn't need 120 fps.
                handler.postDelayed({ frameScheduled = false; lastFrame = 0L; invalidate(); kick() }, 50)
            }
        }
    }

    private fun stepFade(now: Long) {
        val el = now - fadeStart
        if (fadingOut) {
            contentAlpha = (1f - el / FADE_OUT_MS).coerceIn(0f, 1f)
            if (el >= FADE_OUT_MS) {
                fadingOut = false
                shownMode = pendingMode
                shownToken = pendingToken
                shownTransient = pendingTransient
                fadeStart = now
                contentAlpha = 0f
            }
        } else if (contentAlpha < 1f) {
            contentAlpha = ((el - FADE_IN_DELAY_MS) / FADE_IN_MS).coerceIn(0f, 1f)
        }
    }

    private fun animating() =
        springW.moving || springH.moving || springAlpha.moving || springScale.moving || fadingOut || contentAlpha < 1f

    private fun needsTicker() = screenOn && springAlpha.value > 0f && (
        ((shownMode == Mode.MEDIA || shownMode == Mode.MEDIA_EXPANDED) && media?.playing == true) ||
            shownMode == Mode.CALL
        )

    private fun kick() {
        if (frameScheduled || !isAttachedToWindow) {
            if (!isAttachedToWindow) {
                // Not attached yet: jump straight to the end state.
                springW.snap(springW.target); springH.snap(springH.target); springAlpha.snap(springAlpha.target)
                fadingOut = false; shownMode = pendingMode; shownToken = pendingToken; shownTransient = pendingTransient
                contentAlpha = 1f
            }
            return
        }
        if (!animating() && !needsTicker()) return
        frameScheduled = true
        postOnAnimation(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frameScheduled = false
        kick()
    }

    /** Grow the window before growing the pill; shrink it only once the pill has settled. */
    private fun ensureWindow() {
        val slack = context.dp(12f)
        val settled = !springW.moving && !springH.moving
        val needW = (max(springW.target, if (settled) 0f else springW.value) * 1.08f + slack).roundToInt()
        val needH = (max(springH.target, if (settled) 0f else springH.value) * 1.08f + slack).roundToInt()
        // An overlay wider than the display gets shoved sideways by the window manager, which knocks the
        // pill off-centre. Cap at the display width; the pill itself always leaves a margin.
        val screenW = resources.displayMetrics.widthPixels
        val w = min(if (settled) needW else max(needW, windowW), screenW)
        val h = if (settled) needH else max(needH, windowH)
        if (w != windowW || h != windowH) {
            windowW = w
            windowH = h
            host.onWindowSize(w, h)
        }
    }

    // ---- Drawing ---------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val a = springAlpha.value.coerceIn(0f, 1f)
        if (a <= 0.001f) return
        val scale = springScale.value
        val w = springW.value * scale
        val h = springH.value * (1f - (1f - scale) * 0.6f)
        val cx = width / 2f
        pillRect.set(cx - w / 2f, 0f, cx + w / 2f, h)
        val r = min(h / 2f, context.dp(38f))
        pill.alpha = (255 * a).roundToInt()
        canvas.drawRoundRect(pillRect, r, r, pill)

        val ca = contentAlpha * a
        if (ca <= 0.01f) return
        // Content only draws once the shape is close enough to fit it.
        val alpha = (255 * ca).roundToInt()
        canvas.save()
        canvas.clipRect(pillRect)
        when (shownMode) {
            Mode.IDLE -> Unit
            Mode.MEDIA -> drawMediaCompact(canvas, alpha)
            Mode.CALL -> drawCall(canvas, alpha)
            Mode.MEDIA_EXPANDED -> drawMediaExpanded(canvas, alpha)
            Mode.NOTICE -> (shownTransient as? Transient.NoticeT)?.let { drawNotice(canvas, it.notice, alpha) }
            Mode.CHARGING -> (shownTransient as? Transient.ChargeT)?.let { drawCharging(canvas, it, alpha) }
            Mode.RINGER -> (shownTransient as? Transient.RingerT)?.let { drawRinger(canvas, it.mode, alpha) }
        }
        canvas.restore()
    }

    private val idleHalf get() = context.dp(prefs.width.toFloat()) / 2f
    private val topZone get() = context.dp(prefs.height.toFloat())

    /** Left compact slot spans from the pill edge to just before the camera. */
    private fun leftSlotStart() = pillRect.left + context.dp(14f)
    private fun rightSlotEnd() = pillRect.right - context.dp(14f)

    private fun drawMediaCompact(canvas: Canvas, alpha: Int) {
        val m = media ?: return
        val cy = pillRect.top + topZone / 2f
        val s = compactSize(18f)
        box.set(leftSlotStart(), cy - s / 2f, leftSlotStart() + s, cy + s / 2f)
        drawArt(canvas, box, small = true, alpha = alpha)
        drawVisualizer(canvas, rightSlotEnd(), cy, compactSize(15f) / 4f, 4, 4, m.playing, alpha)
    }

    /** A compact-state element size: its natural size, shrunk to fit a slimmer pill. */
    private fun compactSize(naturalDp: Float) = min(context.dp(naturalDp), topZone * 0.64f)

    /** Call in progress: a breathing red dot on the left, the running timer on the right. */
    private fun drawCall(canvas: Canvas, alpha: Int) {
        val c = call ?: return
        val cy = pillRect.top + topZone / 2f
        val t = SystemClock.uptimeMillis() / 1000f
        val pulse = 0.55f + 0.45f * ((sin(t * 3.2f) + 1f) / 2f)
        val r = compactSize(10f) / 2f
        val x0 = leftSlotStart() + r
        dotPaint.color = Look.RED
        dotPaint.alpha = (alpha * 0.35f * pulse).roundToInt()
        canvas.drawCircle(x0, cy, r * (1f + 0.6f * pulse), dotPaint)
        dotPaint.alpha = alpha
        canvas.drawCircle(x0, cy, r * 0.7f, dotPaint)

        val secs = ((System.currentTimeMillis() - c.startedAt) / 1000).coerceAtLeast(0)
        val txt = fmt(secs * 1000)
        bigDotPaint.color = Look.WHITE
        bigDotPaint.alpha = alpha
        val size = bigDotPaint.textSize
        bigDotPaint.textSize = min(size, compactSize(15f) * 1.05f)
        val tw = bigDotPaint.measureText(txt)
        drawText(canvas, txt, rightSlotEnd() - tw, cy, tw + 1f, bigDotPaint, centerY = true)
        bigDotPaint.textSize = size
    }

    private fun drawArt(canvas: Canvas, b: RectF, small: Boolean, alpha: Int) {
        val dotted = prefs.dotArt
        val dots = if (small) mediaArtSmall else mediaArt
        when {
            dotted && dots != null -> dots.draw(canvas, b, dotPaint, round = small, alpha = alpha)
            !dotted && mediaPicture != null ->
                mediaPicture!!.draw(canvas, b, if (small) b.width() / 2f else context.dp(12f), alpha)
            else -> {
                // No artwork: a dotted ring with a red centre.
                dotPaint.color = Look.DOT_OFF; dotPaint.alpha = alpha
                val n = 12
                val rr = b.width() / 2f - context.dp(1.5f)
                for (i in 0 until n) {
                    val ang = Math.PI * 2 * i / n
                    canvas.drawCircle(
                        b.centerX() + (rr * kotlin.math.cos(ang)).toFloat(),
                        b.centerY() + (rr * kotlin.math.sin(ang)).toFloat(),
                        context.dp(if (small) 1.3f else 2.4f), dotPaint,
                    )
                }
                dotPaint.color = Look.RED; dotPaint.alpha = alpha
                canvas.drawCircle(b.centerX(), b.centerY(), context.dp(if (small) 2.2f else 5f), dotPaint)
            }
        }
    }

    /** A fake-but-convincing equaliser: dot columns breathing on offset sine waves. */
    private fun drawVisualizer(canvas: Canvas, right: Float, cy: Float, pitch: Float, cols: Int, rows: Int, playing: Boolean, alpha: Int) {
        val t = SystemClock.uptimeMillis() / 1000f
        val r = pitch * 0.34f
        val left = right - pitch * cols
        val top = cy - pitch * rows / 2f
        for (c in 0 until cols) {
            val level = if (playing) {
                val v = 0.5f + 0.3f * sin(t * (5.1f + c * 1.7f) + c * 1.3f) + 0.2f * sin(t * (8.3f - c) + c * 2.1f)
                1 + (v.coerceIn(0f, 1f) * (rows - 1)).roundToInt()
            } else 1
            for (row in 0 until rows) {
                val lit = rows - row <= level
                dotPaint.color = when {
                    !lit -> Look.DOT_OFF
                    !playing -> Look.GREY
                    row == rows - level && level == rows -> Look.RED
                    else -> Look.WHITE
                }
                dotPaint.alpha = alpha
                canvas.drawCircle(left + pitch * (c + 0.5f), top + pitch * (row + 0.5f), r, dotPaint)
            }
        }
    }

    /** The "● APP NAME" label that sits left of the camera in expanded states. */
    private fun drawHeader(canvas: Canvas, label: String, right: String?, alpha: Int, redDot: Boolean = true) {
        val cy = pillRect.top + topZone / 2f
        val x0 = pillRect.left + context.dp(22f)
        val cameraLeft = pillRect.centerX() - idleHalf - context.dp(8f)
        if (redDot) {
            dotPaint.color = Look.RED; dotPaint.alpha = alpha
            canvas.drawCircle(x0 + context.dp(3f), cy, context.dp(3f), dotPaint)
        }
        val tx = x0 + if (redDot) context.dp(12f) else 0f
        labelPaint.alpha = alpha
        drawText(canvas, label.uppercase(), tx, cy, cameraLeft - tx, labelPaint, centerY = true)
        if (right != null) {
            val rw = labelPaint.measureText(right)
            drawText(canvas, right, pillRect.right - context.dp(22f) - rw, cy, rw + 1f, labelPaint, centerY = true)
        }
    }

    private fun drawNotice(canvas: Canvas, n: Notice, alpha: Int) {
        drawHeader(canvas, n.appName, "NOW", alpha)
        val cy = pillRect.top + topZone + context.dp(26f)
        val s = context.dp(38f)
        box.set(pillRect.left + context.dp(16f), cy - s / 2f, pillRect.left + context.dp(16f) + s, cy + s / 2f)
        val avatar = noticeAvatar
        if (avatar != null) {
            avatar.draw(canvas, box, s / 2f, alpha)
        } else {
            dotPaint.color = Look.RAISED; dotPaint.alpha = alpha
            canvas.drawCircle(box.centerX(), box.centerY(), s / 2f, dotPaint)
            n.icon?.let { d ->
                val i = context.dp(11f)
                d.mutate()
                d.setTint(Look.WHITE)
                d.alpha = alpha
                d.setBounds((box.left + i).toInt(), (box.top + i).toInt(), (box.right - i).toInt(), (box.bottom - i).toInt())
                d.draw(canvas)
            }
        }
        val tx = box.right + context.dp(12f)
        val avail = pillRect.right - context.dp(20f) - tx
        titlePaint.alpha = alpha
        bodyPaint.alpha = alpha
        val title = n.title.ifEmpty { n.appName }
        if (n.text.isEmpty()) {
            drawText(canvas, title, tx, cy, avail, titlePaint, centerY = true)
        } else {
            drawText(canvas, title, tx, cy - context.dp(4f), avail, titlePaint)
            drawText(canvas, n.text.replace('\n', ' '), tx, cy + context.dp(14f), avail, bodyPaint)
        }
    }

    private fun drawMediaExpanded(canvas: Canvas, alpha: Int) {
        val m = media ?: return
        drawHeader(canvas, m.appName, null, alpha, redDot = m.playing)
        drawVisualizer(canvas, pillRect.right - context.dp(22f), pillRect.top + topZone / 2f, context.dp(3.4f), 5, 4, m.playing, alpha)

        // Artwork: tap to flip between the real cover and the dot-matrix version.
        val pad = context.dp(18f)
        val s = context.dp(ART_DP)
        val top = pillRect.top + topZone + context.dp(4f)
        box.set(pillRect.left + pad, top, pillRect.left + pad + s, top + s)
        drawArt(canvas, box, small = false, alpha = alpha)
        hitArt.set(box)

        val tx = box.right + context.dp(14f)
        val avail = pillRect.right - pad - tx
        titlePaint.alpha = alpha
        bodyPaint.alpha = alpha
        drawText(canvas, m.title, tx, top + s / 2f - context.dp(3f), avail, titlePaint)
        drawText(canvas, m.artist, tx, top + s / 2f + context.dp(17f), avail, bodyPaint)
        hitOpen.set(tx, box.top, pillRect.right - pad, box.bottom)

        // Progress: a row of dots, played in white, the playhead in red. Drag it to scrub.
        val barY = box.bottom + context.dp(22f)
        labelPaint.alpha = alpha
        val dur = m.durationMs
        val pos = shownPosition(m)
        val barL: Float
        val barR: Float
        if (dur > 0) {
            val lt = fmt(pos)
            val rt = "-" + fmt(dur - pos)
            val tw = labelPaint.measureText("-00:00")
            labelPaint.color = if (scrubbing) Look.WHITE else Look.GREY
            labelPaint.alpha = alpha
            drawText(canvas, lt, pillRect.left + pad, barY, tw, labelPaint, centerY = true)
            val rw = labelPaint.measureText(rt)
            drawText(canvas, rt, pillRect.right - pad - rw, barY, rw + 1f, labelPaint, centerY = true)
            labelPaint.color = Look.GREY
            labelPaint.alpha = alpha
            barL = pillRect.left + pad + tw + context.dp(8f)
            barR = pillRect.right - pad - tw - context.dp(8f)
        } else {
            barL = pillRect.left + pad
            barR = pillRect.right - pad
        }
        val pitch = context.dp(5.5f)
        val count = max(2, ((barR - barL) / pitch).toInt())
        val step = (barR - barL) / (count - 1)
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val head = (frac * (count - 1)).roundToInt()
        for (i in 0 until count) {
            val x = barL + i * step
            val isHead = dur > 0 && i == head
            dotPaint.color = when {
                isHead -> Look.RED
                dur > 0 && i < head -> Look.WHITE
                else -> Look.DOT_OFF
            }
            dotPaint.alpha = alpha
            val r = when {
                isHead && scrubbing -> 4.6f
                isHead -> 3.2f
                else -> 1.5f
            }
            canvas.drawCircle(x, barY, context.dp(r), dotPaint)
        }
        // Generous touch target so the bar is easy to grab.
        hitBar.set(barL - context.dp(8f), barY - context.dp(16f), barR + context.dp(8f), barY + context.dp(16f))
        barLeft = barL
        barRight = barR

        // Transport, all dot-matrix: -10s, previous, play/pause, next, +10s.
        val cy = barY + context.dp(34f)
        val cx = pillRect.centerX()
        val gap = min(pillRect.width() / 5.4f, context.dp(70f))
        glyphPaint.color = Look.WHITE
        glyphPaint.alpha = alpha
        val gs = context.dp(16f)
        val ss = context.dp(13f)
        val ps = context.dp(22f)
        fun glyph(g: Glyph, x: Float, size: Float) = g.draw(canvas, x - g.width(size) / 2f, cy, size, glyphPaint)
        glyph(Glyph.REWIND, cx - 2 * gap, ss)
        glyph(Glyph.PREV, cx - gap, gs)
        glyph(if (m.playing) Glyph.PAUSE else Glyph.PLAY, cx, ps)
        glyph(Glyph.NEXT, cx + gap, gs)
        glyph(Glyph.FORWARD, cx + 2 * gap, ss)
        labelPaint.alpha = alpha
        val tenW = labelPaint.measureText("10")
        drawText(canvas, "10", cx - 2 * gap - tenW / 2f, cy + context.dp(17f), tenW + 1f, labelPaint, centerY = true)
        drawText(canvas, "10", cx + 2 * gap - tenW / 2f, cy + context.dp(17f), tenW + 1f, labelPaint, centerY = true)

        val hs = min(gap / 2f, context.dp(26f))
        hitRewind.set(cx - 2 * gap - hs, cy - hs, cx - 2 * gap + hs, cy + hs)
        hitPrev.set(cx - gap - hs, cy - hs, cx - gap + hs, cy + hs)
        hitPlay.set(cx - hs, cy - hs, cx + hs, cy + hs)
        hitNext.set(cx + gap - hs, cy - hs, cx + gap + hs, cy + hs)
        hitForward.set(cx + 2 * gap - hs, cy - hs, cx + 2 * gap + hs, cy + hs)
    }

    /** What the playhead should show: the finger while scrubbing, the requested spot right after a seek. */
    private fun shownPosition(m: MediaInfo): Long {
        if (scrubbing && m.durationMs > 0) return (scrubFrac * m.durationMs).toLong()
        if (SystemClock.uptimeMillis() < seekHoldUntil) return seekHoldPos
        return m.currentPosition()
    }

    private fun drawCharging(canvas: Canvas, c: Transient.ChargeT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val low = c.level <= 20
        val tint = if (low) Look.RED else Look.WHITE
        var x = leftSlotStart()
        if (c.charging) {
            glyphPaint.color = tint; glyphPaint.alpha = alpha
            val gs = compactSize(15f)
            Glyph.BOLT.draw(canvas, x, cy, gs, glyphPaint)
            x += Glyph.BOLT.width(gs) + context.dp(6f)
        }
        // Five-dot battery gauge.
        val filled = ((c.level + 10) / 20).coerceIn(if (c.level > 0) 1 else 0, 5)
        val pitch = context.dp(6f)
        for (i in 0 until 5) {
            dotPaint.color = if (i < filled) tint else Look.DOT_OFF
            dotPaint.alpha = alpha
            canvas.drawCircle(x + pitch * (i + 0.5f), cy, context.dp(2f), dotPaint)
        }
        bigDotPaint.color = tint
        bigDotPaint.alpha = alpha
        val txt = "${c.level}%"
        val size = bigDotPaint.textSize
        bigDotPaint.textSize = min(size, compactSize(15f) * 1.05f)
        val tw = bigDotPaint.measureText(txt)
        drawText(canvas, txt, rightSlotEnd() - tw, cy, tw + 1f, bigDotPaint, centerY = true)
        bigDotPaint.textSize = size
    }

    private fun drawRinger(canvas: Canvas, mode: Int, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val gs = compactSize(14f)
        val x = leftSlotStart()
        glyphPaint.alpha = alpha
        val label = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> {
                glyphPaint.color = Look.RED; glyphPaint.alpha = alpha
                Glyph.BELL_OFF.draw(canvas, x, cy, gs, glyphPaint)
                "SILENT"
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                glyphPaint.color = Look.WHITE; glyphPaint.alpha = alpha
                Glyph.VIBRATE.draw(canvas, x, cy, gs, glyphPaint)
                "VIBRATE"
            }
            else -> {
                glyphPaint.color = Look.WHITE; glyphPaint.alpha = alpha
                Glyph.BELL.draw(canvas, x, cy, gs, glyphPaint)
                "RING"
            }
        }
        bigDotPaint.color = if (mode == AudioManager.RINGER_MODE_SILENT) Look.RED else Look.WHITE
        bigDotPaint.alpha = alpha
        val slot = rightSlotEnd() - (pillRect.centerX() + context.dp(CAMERA_CLEAR_DP))
        val size = bigDotPaint.textSize
        bigDotPaint.textSize = min(size, compactSize(15f) * 1.05f)
        bigDotPaint.textSize = min(bigDotPaint.textSize, bigDotPaint.textSize * slot / max(1f, bigDotPaint.measureText(label)))
        val tw = bigDotPaint.measureText(label)
        drawText(canvas, label, rightSlotEnd() - tw, cy, tw + 1f, bigDotPaint, centerY = true)
        bigDotPaint.textSize = size
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, maxW: Float, p: TextPaint, centerY: Boolean = false) {
        if (maxW <= 0f || text.isEmpty()) return
        val baseline = if (centerY) y - (p.descent() + p.ascent()) / 2f else y
        canvas.drawText(fit(text, p, maxW), x, baseline, p)
    }

    /** Trims to width with a trailing ellipsis. Done by hand so it behaves the same on every OEM build. */
    private fun fit(text: String, p: TextPaint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        val room = maxW - p.measureText(ELLIPSIS)
        if (room <= 0f) return ""
        val n = p.breakText(text, true, room, null)
        return text.substring(0, n).trimEnd() + ELLIPSIS
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---- Touch -----------------------------------------------------------------------------------------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            when (shownMode) {
                Mode.IDLE -> host.openSettings()
                Mode.MEDIA -> { mediaExpanded = true; buzz(); resolve() }
                // During a call, long-press opens the music player if something's playing.
                Mode.CALL -> if (media != null && prefs.showMedia) { mediaExpanded = true; buzz(); resolve() }
                else -> Unit
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vy) < abs(vx)) return false
            if (vy < 0) swipeUp() else swipeDown()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (mediaExpanded) { mediaExpanded = false; resolve() }
            return false
        }
        if (springAlpha.target == 0f) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !pillRect.contains(event.x, event.y)) return false
        if (scrubTouch(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressed(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setPressed(false)
        }
        return gestures.onTouchEvent(event)
    }

    private fun setPressedState(p: Boolean) {
        if (pressed == p) return
        pressed = p
        springScale.target = if (p) 0.965f else 1f
        kick()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        setPressedState(pressed)
    }

    private fun onTap(x: Float, y: Float) {
        when (shownMode) {
            Mode.IDLE -> { springScale.target = 1.06f; kick(); handler.postDelayed({ springScale.target = 1f; kick() }, 120) }
            Mode.MEDIA -> { mediaExpanded = true; buzz(); resolve() }
            Mode.CALL -> { call?.intent?.let { send(it) }; buzz() }
            Mode.MEDIA_EXPANDED -> tapExpandedMedia(x, y)
            Mode.NOTICE -> (shownTransient as? Transient.NoticeT)?.notice?.let { n ->
                n.intent?.let { send(it) }
                if (n.autoCancel) IslandHub.canceller?.invoke(n.key)
                endTransient()
            }
            Mode.CHARGING, Mode.RINGER -> endTransient()
        }
    }

    /** Drag along the progress dots to scrub; the seek is sent when the finger lifts. */
    private fun scrubTouch(e: MotionEvent): Boolean {
        val m = media
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shownMode != Mode.MEDIA_EXPANDED || m == null || m.durationMs <= 0) return false
                if (!hitBar.contains(e.x, e.y)) return false
                scrubbing = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateScrub(e.x)
                buzz()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                updateScrub(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                scrubbing = false
                if (e.actionMasked == MotionEvent.ACTION_UP && m != null) seekTo((scrubFrac * m.durationMs).toLong())
                invalidate()
                return true
            }
        }
        return scrubbing
    }

    private fun updateScrub(x: Float) {
        val w = (barRight - barLeft).coerceAtLeast(1f)
        val f = ((x - barLeft) / w).coerceIn(0f, 1f)
        // A light tick every ~5% so scrubbing feels like a dial.
        if ((f * 20).toInt() != (scrubFrac * 20).toInt()) buzz()
        scrubFrac = f
        invalidate()
    }

    private fun seekTo(ms: Long) {
        val m = media ?: return
        val target = if (m.durationMs > 0) ms.coerceIn(0, m.durationMs) else ms.coerceAtLeast(0)
        seekHoldPos = target
        seekHoldUntil = SystemClock.uptimeMillis() + 1500
        val c = m.controller
        if (c != null) {
            c.transportControls.seekTo(target)
        } else {
            // Demo player (no real session): move the playhead ourselves.
            media = m.copy(positionMs = target, positionAt = SystemClock.elapsedRealtime())
            seekHoldUntil = 0L
        }
        invalidate()
    }

    private fun tapExpandedMedia(x: Float, y: Float) {
        val m = media ?: return
        val tc = m.controller?.transportControls
        when {
            hitPlay.contains(x, y) -> {
                if (tc != null) {
                    if (m.playing) tc.pause() else tc.play()
                } else {
                    media = m.copy(playing = !m.playing, positionMs = m.currentPosition(), positionAt = SystemClock.elapsedRealtime())
                    resolve()
                }
                buzz()
            }
            hitRewind.contains(x, y) -> { seekTo(shownPosition(m) - SKIP_MS); buzz() }
            hitForward.contains(x, y) -> { seekTo(shownPosition(m) + SKIP_MS); buzz() }
            hitPrev.contains(x, y) -> {
                // Like most players: restart the song unless it only just started.
                if (tc != null) tc.skipToPrevious() else seekTo(0)
                buzz()
            }
            hitNext.contains(x, y) -> { if (tc != null) tc.skipToNext() else seekTo(0); buzz() }
            hitBar.contains(x, y) && m.durationMs > 0 -> {
                val f = ((x - barLeft) / (barRight - barLeft).coerceAtLeast(1f)).coerceIn(0f, 1f)
                seekTo((f * m.durationMs).toLong())
                buzz()
            }
            hitArt.contains(x, y) -> {
                prefs.dotArt = !prefs.dotArt
                buzz()
                invalidate()
            }
            hitOpen.contains(x, y) -> {
                m.controller?.sessionActivity?.let { send(it) }
                mediaExpanded = false
                resolve()
            }
            else -> { mediaExpanded = false; resolve() }
        }
    }

    private fun swipeUp() {
        when (shownMode) {
            Mode.MEDIA_EXPANDED -> mediaExpanded = false
            Mode.MEDIA -> mediaDismissed = media
            Mode.CALL -> callDismissed = call?.key
            Mode.NOTICE, Mode.CHARGING, Mode.RINGER -> { endTransient(); return }
            Mode.IDLE -> Unit
        }
        resolve()
    }

    private fun swipeDown() {
        if (shownMode == Mode.MEDIA) {
            mediaExpanded = true
            buzz()
            resolve()
        } else if (shownMode == Mode.NOTICE) {
            // Pull down to keep it open a little longer.
            handler.removeCallbacks(transientTimeout)
            handler.postDelayed(transientTimeout, 8000)
        }
    }

    private fun send(pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        } catch (_: PendingIntent.CanceledException) {
        }
    }

    private fun buzz() {
        if (!prefs.haptics) return
        Haptics.tick(context)
    }

    companion object {
        private const val SKIP_MS = 10_000L
        private const val ELLIPSIS = "\u2026"
        private const val SIDE_DP = 46f
        private const val ART_DP = 76f

        /** Half-width kept clear around the camera hole in compact states. */
        private const val CAMERA_CLEAR_DP = 16f
        private const val PAUSE_LINGER_MS = 60_000L
        private const val FADE_OUT_MS = 90f
        private const val FADE_IN_MS = 200f
        private const val FADE_IN_DELAY_MS = 40f
    }
}
