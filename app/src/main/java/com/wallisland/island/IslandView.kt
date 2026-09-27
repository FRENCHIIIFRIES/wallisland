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

        /** Current state of the quick toggles (long-press panel). */
        fun quickState(): QuickState
        fun quickAction(action: Quick)

        /** Battery percent for the double-tap peek, or -1. */
        fun batteryLevel(): Int
    }

    data class QuickState(
        val torch: Boolean,
        val torchAvailable: Boolean,
        val ringerMode: Int,
        val autoRotate: Boolean,
        val focusOn: Boolean = false,
    )

    enum class Quick { TORCH, RINGER, ROTATE, FOCUS, SETTINGS }

    private enum class Mode {
        IDLE, CALL, LIVE, MEDIA, MEDIA_EXPANDED, NOTICE, CHARGING, RINGER, UNLOCK, BUDS, TOGGLES, VOLUME, STATUS, PEEK,
    }

    private sealed class Transient {
        data class NoticeT(val notice: Notice) : Transient()
        data class ChargeT(val level: Int, val charging: Boolean, val fullInMs: Long = -1, val at: Long = 0L) : Transient()
        data class RingerT(val mode: Int) : Transient()
        data class UnlockT(val startedAt: Long) : Transient()
        data class BudsT(val battery: Int, val name: String?) : Transient()
        data class VolumeT(val level: Int, val max: Int) : Transient()

        /** A one-line confirmation: a glyph and a word (DND ON, WI-FI OFF, SENT, DONE…). */
        data class StatusT(val glyph: Glyph, val label: String, val lit: Boolean) : Transient()
        data class PeekT(val at: Long) : Transient()
    }

    // ---- State -----------------------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaInfo? = null
    private var call: CallInfo? = null
    private var callDismissed: String? = null
    private var live: List<LiveInfo> = emptyList()
    private val liveDismissed = HashSet<String>()
    private var togglesOpen = false
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

    // Near-critically damped: a soft settle with the faintest overshoot, rather than a wobble.
    private val springW = Spring(0f, 300f, 0.86f, 0.5f)
    private val springH = Spring(0f, 300f, 0.9f, 0.5f)
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
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Look.WHITE
    }
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

    fun setLive(list: List<LiveInfo>) {
        live = list
        liveDismissed.retainAll(list.map { it.key }.toSet())
        resolve()
        invalidate()
    }

    /**
     * Unlocking fires while the island may still be briefly hidden (lock screen, face unlock using the
     * camera). Remember the request and play the animation as soon as the island can show, within 2 s.
     */
    fun showUnlock(): Boolean {
        if (!prefs.showUnlock) {
            IslandHub.unlockLog += ": skipped, turned off"
            return false
        }
        unlockRequestedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(tryUnlock)
        handler.removeCallbacks(unlockGaveUp)
        handler.postDelayed(tryUnlock, 120)
        handler.postDelayed(unlockGaveUp, UNLOCK_WAIT_MS + 50)
        return true
    }

    private var unlockRequestedAt = 0L
    private var unlockBlocker = ""

    private val tryUnlock: Runnable = Runnable {
        val now = SystemClock.uptimeMillis()
        if (unlockRequestedAt == 0L || now - unlockRequestedAt > UNLOCK_WAIT_MS) return@Runnable
        unlockBlocker = when {
            hidden -> "island hidden (full screen, landscape or recording)"
            !screenOn -> "screen off"
            transient is Transient.NoticeT -> "a notification was showing"
            else -> ""
        }
        if (unlockBlocker.isNotEmpty()) {
            // Keep checking; the reason usually clears within a second of unlocking.
            handler.postDelayed(tryUnlockAgain, 150)
            return@Runnable
        }
        unlockRequestedAt = 0L
        handler.removeCallbacks(unlockGaveUp)
        IslandHub.unlockLog += ": played"
        showTransient(Transient.UnlockT(now), 1500)
    }
    private val tryUnlockAgain: Runnable = Runnable { tryUnlock.run() }

    private val unlockGaveUp = Runnable {
        if (unlockRequestedAt == 0L) return@Runnable
        unlockRequestedAt = 0L
        IslandHub.unlockLog += ": skipped, $unlockBlocker"
    }

    /** [name] null keeps the name from the pop-up already showing (a later battery update). */
    fun showBuds(battery: Int, name: String?) {
        if (!prefs.showBuds || hidden || !screenOn) return
        if (transient is Transient.NoticeT) return
        val keepName = name ?: (transient as? Transient.BudsT)?.name
        showTransient(Transient.BudsT(battery, keepName), 3500)
    }

    /** Refresh the quick-toggle panel after its state changed (torch, ringer, rotation). */
    fun refreshQuick() {
        if (togglesOpen) invalidate()
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
            mediaArt = art?.let { runCatching { DotArt(it, 12) }.getOrNull() }
            mediaArtSmall = art?.let { runCatching { DotArt(it, 7) }.getOrNull() }
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
        // Buttons need a moment longer to reach.
        val extra = if (notice.actions.isNotEmpty()) 3000L else 0L
        showTransient(Transient.NoticeT(notice), prefs.noticeSeconds * 1000L + extra)
        if (prefs.edgeLight) {
            edgeStart = SystemClock.uptimeMillis()
            edgeColor = if (notice.color != 0 && android.graphics.Color.alpha(notice.color) > 0) brighten(notice.color) else Look.accent
        }
        buzz()
    }

    fun removeNotice(key: String) {
        val t = transient
        if (t is Transient.NoticeT && t.notice.key == key) endTransient()
    }

    /** [fullInMs] > 0 alternates the percentage with "FULL · 42M". */
    fun showCharging(level: Int, charging: Boolean, fullInMs: Long = -1) {
        if (!prefs.showCharging || hidden || !screenOn) return
        if (transient is Transient.NoticeT) return
        val at = (transient as? Transient.ChargeT)?.at ?: SystemClock.uptimeMillis()
        showTransient(Transient.ChargeT(level, charging, fullInMs, at), if (fullInMs > 0) 4400 else 3200)
    }

    /** True when a transient (volume, confirmations) would actually be seen right now. */
    fun canShowTransient() = prefs.enabled && !hidden && screenOn

    fun showVolume(level: Int, max: Int) {
        if (!canShowTransient()) return
        showTransient(Transient.VolumeT(level, max), 1600)
    }

    fun showStatus(glyph: Glyph, label: String, lit: Boolean) {
        if (!canShowTransient() || transient is Transient.NoticeT || togglesOpen) return
        showTransient(Transient.StatusT(glyph, label, lit), 2200)
    }

    fun showPeek() {
        if (!canShowTransient()) return
        showTransient(Transient.PeekT(SystemClock.uptimeMillis()), 3000)
    }

    /** Opens the quick-toggle panel (from the Essential Key). */
    fun openTogglesPanel() = openToggles()

    fun showRinger(mode: Int) {
        if (togglesOpen) { invalidate(); return }
        if (!prefs.showRinger || hidden || !screenOn) return
        if (transient is Transient.NoticeT) return
        showTransient(Transient.RingerT(mode), 2200)
    }

    fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        if (hide) {
            mediaExpanded = false
            togglesOpen = false
            transient = null
        }
        resolve()
        if (!hide) handler.post(tryUnlock)
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (!on) {
            mediaExpanded = false
            togglesOpen = false
            transient = null
        }
        resolve()
        if (on) handler.post(tryUnlock)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    // ---- State machine ---------------------------------------------------------------------------------

    private val transientTimeout = Runnable { endTransient() }
    private val pauseTimeout = Runnable { resolve() }
    private val togglesTimeout = Runnable { closeToggles() }

    private fun openToggles() {
        togglesOpen = true
        mediaExpanded = false
        handler.removeCallbacks(togglesTimeout)
        handler.postDelayed(togglesTimeout, 6000)
        buzz()
        resolve()
    }

    private fun closeToggles() {
        handler.removeCallbacks(togglesTimeout)
        if (!togglesOpen) return
        togglesOpen = false
        resolve()
    }

    /** The live activity worth showing: navigation first, then timers, then progress; newest wins ties. */
    private fun currentLive(): LiveInfo? {
        if (!prefs.showLive) return null
        return live.filter { it.key !in liveDismissed }
            .sortedWith(compareBy<LiveInfo> { it.kind.ordinal }.thenByDescending { it.postedAt })
            .firstOrNull()
    }

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
            t is Transient.UnlockT -> Mode.UNLOCK
            t is Transient.BudsT -> Mode.BUDS
            t is Transient.VolumeT -> Mode.VOLUME
            t is Transient.StatusT -> Mode.STATUS
            t is Transient.PeekT -> Mode.PEEK
            togglesOpen -> Mode.TOGGLES
            mediaExpanded && mediaVisible() -> Mode.MEDIA_EXPANDED
            call != null && call?.key != callDismissed -> Mode.CALL
            currentLive() != null -> Mode.LIVE
            mediaVisible() -> Mode.MEDIA
            else -> Mode.IDLE
        }
        if (mode == Mode.IDLE) mediaExpanded = false

        val idleW = context.dp(prefs.width.toFloat())
        val idleH = context.dp(prefs.height.toFloat())
        val bigW = min(resources.displayMetrics.widthPixels - context.dp(24f), context.dp(360f))
        val (w, h) = when (mode) {
            Mode.IDLE -> idleW to idleH
            Mode.MEDIA, Mode.CHARGING, Mode.RINGER, Mode.CALL, Mode.LIVE, Mode.UNLOCK, Mode.BUDS, Mode.STATUS, Mode.PEEK ->
                idleW + 2 * context.dp(SIDE_DP) to idleH
            Mode.TOGGLES -> bigW to idleH + context.dp(84f)
            Mode.NOTICE -> bigW to idleH + context.dp(
                if ((t as? Transient.NoticeT)?.notice?.actions?.isNotEmpty() == true) 104f else 60f,
            )
            Mode.VOLUME -> idleW + 2 * context.dp(SIDE_DP) + context.dp(24f) to idleH + context.dp(22f)
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
            Mode.LIVE -> currentLive()?.key
            Mode.UNLOCK, Mode.BUDS, Mode.STATUS, Mode.PEEK -> t
            // Volume steps update in place rather than cross-fading on every press.
            Mode.VOLUME -> Mode.VOLUME
            Mode.TOGGLES -> Mode.TOGGLES
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
                // Ambient motion (equaliser, playhead): full frame rate when the player is open, 30 fps
                // in the small pill to save battery.
                val delay = if (shownMode == Mode.MEDIA_EXPANDED) 16L else 33L
                handler.postDelayed({ frameScheduled = false; lastFrame = 0L; invalidate(); kick() }, delay)
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
        springW.moving || springH.moving || springAlpha.moving || springScale.moving || fadingOut || contentAlpha < 1f ||
            edgeActive()

    private fun needsTicker() = screenOn && springAlpha.value > 0f && (
        ((shownMode == Mode.MEDIA || shownMode == Mode.MEDIA_EXPANDED) && media?.playing == true) ||
            shownMode == Mode.CALL || shownMode == Mode.UNLOCK || shownMode == Mode.CHARGING || shownMode == Mode.PEEK ||
            (shownMode == Mode.LIVE && currentLive()?.let {
                it.kind == LiveInfo.Kind.TIMER || it.kind == LiveInfo.Kind.EVENT || it.indeterminate
            } == true)
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
        val opacity = prefs.opacity.coerceIn(40, 100) / 100f
        pill.alpha = (255 * a).roundToInt()
        pill.shader = if (opacity < 1f) glassShader(opacity) else null
        canvas.drawRoundRect(pillRect, r, r, pill)
        if (opacity < 1f) {
            // A faint hairline keeps a see-through island's edge crisp over busy backgrounds.
            rim.strokeWidth = context.dp(0.8f)
            rim.alpha = (255 * a * 0.14f).roundToInt()
            val inset = rim.strokeWidth / 2f
            box.set(pillRect.left + inset, pillRect.top + inset, pillRect.right - inset, pillRect.bottom - inset)
            canvas.drawRoundRect(box, r - inset, r - inset, rim)
        }
        if (edgeActive()) drawEdgeLight(canvas, r, a)

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
            Mode.LIVE -> currentLive()?.let { drawLive(canvas, it, alpha) }
            Mode.UNLOCK -> (shownTransient as? Transient.UnlockT)?.let { drawUnlock(canvas, it, alpha) }
            Mode.BUDS -> (shownTransient as? Transient.BudsT)?.let { drawBuds(canvas, it.battery, it.name, alpha) }
            Mode.TOGGLES -> drawToggles(canvas, alpha)
            Mode.VOLUME -> (shownTransient as? Transient.VolumeT)?.let { drawVolume(canvas, it, alpha) }
            Mode.STATUS -> (shownTransient as? Transient.StatusT)?.let { drawStatus(canvas, it, alpha) }
            Mode.PEEK -> drawPeek(canvas, alpha)
        }
        canvas.restore()
    }

    private var glass: android.graphics.LinearGradient? = null
    private var glassKey = 0L

    /**
     * See-through below the status-bar strip, nearly solid across it, so status-bar icons don't bleed
     * through behind the island's own header. Cached while the geometry stays the same.
     */
    private fun glassShader(opacity: Float): android.graphics.Shader {
        val top = pillRect.top
        val solidTo = top + topZone
        val fadeTo = solidTo + context.dp(14f)
        val key = (solidTo.toLong() shl 20) xor (fadeTo.toLong() shl 8) xor (opacity * 100).toLong()
        glass?.let { if (key == glassKey) return it }
        val solid = (0.97f * 255).roundToInt() shl 24
        val clear = (opacity * 255).roundToInt() shl 24
        return android.graphics.LinearGradient(
            0f, solidTo - context.dp(4f), 0f, fadeTo, solid, clear, android.graphics.Shader.TileMode.CLAMP,
        ).also {
            glass = it
            glassKey = key
        }
    }

    private val idleHalf get() = context.dp(prefs.width.toFloat()) / 2f
    private val topZone get() = context.dp(prefs.height.toFloat())

    /** Left compact slot spans from the pill edge to just before the camera. */
    private fun leftSlotStart() = pillRect.left + context.dp(12f)
    private fun rightSlotEnd() = pillRect.right - context.dp(12f)

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
        dotPaint.color = Look.accent
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
            dotted && dots != null -> dots.draw(canvas, b, dotPaint, round = small, alpha = alpha, colored = prefs.dotColor)
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
                dotPaint.color = Look.accent; dotPaint.alpha = alpha
                canvas.drawCircle(b.centerX(), b.centerY(), context.dp(if (small) 2.2f else 5f), dotPaint)
            }
        }
    }

    /** Equaliser dots take the cover's accent colour when colour dots are on. */
    private fun eqColor(): Int = if (prefs.dotColor) mediaArt?.accent ?: Look.WHITE else Look.WHITE

    /** The peak dot: red on a white equaliser, white on a coloured one. */
    private fun eqPeak(): Int = if (eqColor() == Look.WHITE) Look.accent else Look.WHITE

    /** A fake-but-convincing equaliser: dot columns breathing on offset sine waves. */
    private fun drawVisualizer(canvas: Canvas, right: Float, cy: Float, pitch: Float, cols: Int, rows: Int, playing: Boolean, alpha: Int) {
        val t = SystemClock.uptimeMillis() / 1000f
        val r = pitch * 0.34f
        val left = right - pitch * cols
        val top = cy - pitch * rows / 2f
        for (c in 0 until cols) {
            // A continuous level, so the top dot of each column fades in and out instead of popping.
            val level = if (playing) {
                val v = 0.5f + 0.3f * sin(t * (5.1f + c * 1.7f) + c * 1.3f) + 0.2f * sin(t * (8.3f - c) + c * 2.1f)
                1f + v.coerceIn(0f, 1f) * (rows - 1)
            } else 1f
            for (row in 0 until rows) {
                val x = left + pitch * (c + 0.5f)
                val y = top + pitch * (row + 0.5f)
                val fromBottom = rows - row // 1 = bottom dot
                val fill = (level - (fromBottom - 1)).coerceIn(0f, 1f)
                dotPaint.color = Look.DOT_OFF
                dotPaint.alpha = alpha
                if (fill < 1f) canvas.drawCircle(x, y, r, dotPaint)
                if (fill <= 0f) continue
                dotPaint.color = when {
                    !playing -> Look.GREY
                    fromBottom == rows -> eqPeak()
                    else -> eqColor()
                }
                dotPaint.alpha = (alpha * fill).roundToInt()
                canvas.drawCircle(x, y, r, dotPaint)
            }
        }
    }

    /** The "● APP NAME" label that sits left of the camera in expanded states. */
    private fun drawHeader(canvas: Canvas, label: String, right: String?, alpha: Int, redDot: Boolean = true) {
        val cy = pillRect.top + topZone / 2f
        val x0 = pillRect.left + context.dp(22f)
        val cameraLeft = pillRect.centerX() - idleHalf - context.dp(8f)
        if (redDot) {
            dotPaint.color = Look.accent; dotPaint.alpha = alpha
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
        drawChips(canvas, n, alpha)
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
        // The playhead glides continuously; the dots it has passed light up behind it.
        val headX = barL + (barR - barL) * frac
        for (i in 0 until count) {
            val x = barL + i * step
            dotPaint.color = if (dur > 0 && x < headX) eqColor() else Look.DOT_OFF
            dotPaint.alpha = alpha
            canvas.drawCircle(x, barY, context.dp(1.5f), dotPaint)
        }
        if (dur > 0) {
            dotPaint.color = Look.accent
            dotPaint.alpha = alpha
            canvas.drawCircle(headX, barY, context.dp(if (scrubbing) 4.6f else 3.2f), dotPaint)
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

    private val hitQuick = Array(5) { RectF() }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconSrc = android.graphics.Rect()

    /** Right-hand compact text, shrunk to fit between the camera and the pill's edge. */
    private fun drawRightText(canvas: Canvas, text: String, color: Int, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val slot = rightSlotEnd() - (pillRect.centerX() + context.dp(CAMERA_CLEAR_DP))
        val size = bigDotPaint.textSize
        bigDotPaint.textSize = min(size, compactSize(15f) * 1.05f)
        bigDotPaint.textSize = min(bigDotPaint.textSize, bigDotPaint.textSize * slot / max(1f, bigDotPaint.measureText(text)))
        bigDotPaint.color = color
        bigDotPaint.alpha = alpha
        val tw = bigDotPaint.measureText(text)
        drawText(canvas, text, rightSlotEnd() - tw, cy, tw + 1f, bigDotPaint, centerY = true)
        bigDotPaint.textSize = size
    }

    /** Timer, navigation or progress, compact: an icon left of the camera, the number right of it. */
    private fun drawLive(canvas: Canvas, l: LiveInfo, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val gs = compactSize(15f)
        val x = leftSlotStart()
        glyphPaint.alpha = alpha
        when (l.kind) {
            LiveInfo.Kind.NAV -> {
                val icon = l.icon
                if (icon != null) {
                    // Maps' manoeuvre arrow, recoloured white.
                    tint.colorFilter = android.graphics.PorterDuffColorFilter(Look.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                    tint.alpha = alpha
                    iconSrc.set(0, 0, icon.width, icon.height)
                    box.set(x, cy - gs / 2f, x + gs, cy + gs / 2f)
                    canvas.drawBitmap(icon, iconSrc, box, tint)
                } else {
                    glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
                    Glyph.ARROW.draw(canvas, x, cy, gs, glyphPaint)
                }
                drawRightText(canvas, l.title.ifEmpty { l.text }, Look.WHITE, alpha)
            }
            LiveInfo.Kind.TIMER -> {
                glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
                Glyph.TIMER.draw(canvas, x, cy, gs, glyphPaint)
                drawRightText(canvas, l.timeText() ?: l.title.ifEmpty { "ON" }, Look.WHITE, alpha)
            }
            LiveInfo.Kind.EVENT -> {
                glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
                Glyph.CALENDAR.draw(canvas, x, cy, gs, glyphPaint)
                drawRightText(canvas, l.eventText(), Look.WHITE, alpha)
            }
            LiveInfo.Kind.PROGRESS -> {
                // A ring of twelve dots that fills up; spins while the size is unknown.
                val n = 12
                val rr = gs / 2f - context.dp(1f)
                val ccx = x + gs / 2f
                val lit = if (l.indeterminate) -1 else (l.percent * n + 50) / 100
                val spin = ((SystemClock.uptimeMillis() / 90) % n).toInt()
                for (i in 0 until n) {
                    val on = if (lit < 0) ((i - spin + n) % n) < 3 else i < lit
                    dotPaint.color = if (on) Look.accent else Look.DOT_OFF
                    dotPaint.alpha = alpha
                    val ang = -Math.PI / 2 + Math.PI * 2 * i / n
                    canvas.drawCircle(
                        ccx + (rr * kotlin.math.cos(ang)).toFloat(),
                        cy + (rr * kotlin.math.sin(ang)).toFloat(),
                        context.dp(1.3f), dotPaint,
                    )
                }
                drawRightText(canvas, if (l.indeterminate) "···" else "${l.percent}%", Look.WHITE, alpha)
            }
        }
    }

    /** Unlocked: the padlock opens, then a tick lands on the right. */
    private fun drawUnlock(canvas: Canvas, t: Transient.UnlockT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val gs = compactSize(15f)
        val el = SystemClock.uptimeMillis() - t.startedAt
        glyphPaint.color = Look.WHITE
        glyphPaint.alpha = alpha
        (if (el < 380) Glyph.LOCK else Glyph.UNLOCK).draw(canvas, leftSlotStart(), cy, gs, glyphPaint)
        val tick = ((el - 380) / 220f).coerceIn(0f, 1f)
        if (tick > 0f) {
            glyphPaint.color = Look.accent
            glyphPaint.alpha = (alpha * tick).roundToInt()
            Glyph.CHECK.draw(canvas, rightSlotEnd() - Glyph.CHECK.width(gs), cy, gs, glyphPaint)
        }
    }

    /** Earbuds connected: headphones on the left, their battery on the right. */
    private fun drawBuds(canvas: Canvas, battery: Int, name: String?, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        glyphPaint.color = Look.WHITE
        glyphPaint.alpha = alpha
        Glyph.HEADPHONES.draw(canvas, leftSlotStart(), cy, compactSize(15f), glyphPaint)
        // Battery when we know it, else the headphones' name (shrunk to fit), else just ON.
        val text = if (battery in 0..100) "$battery%" else name?.takeIf { it.isNotBlank() }?.uppercase() ?: "ON"
        drawRightText(canvas, text, if (battery in 0..20) Look.accent else Look.WHITE, alpha)
    }

    /** Long-press panel: torch, sound mode, rotation and settings, as dot-matrix buttons. */
    private fun drawToggles(canvas: Canvas, alpha: Int) {
        drawHeader(canvas, "Quick", null, alpha)
        val q = host.quickState()
        val cy = pillRect.top + topZone + context.dp(26f)
        val r = context.dp(21f)
        val gs = context.dp(15f)
        val step = pillRect.width() / 5f
        labelPaint.alpha = alpha
        for (i in 0 until 5) {
            val cx = pillRect.left + step * (i + 0.5f)
            val (glyph, label, active) = when (Quick.values()[i]) {
                Quick.TORCH -> Triple(Glyph.TORCH, "TORCH", q.torch)
                Quick.RINGER -> when (q.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> Triple(Glyph.BELL_OFF, "SILENT", true)
                    AudioManager.RINGER_MODE_VIBRATE -> Triple(Glyph.VIBRATE, "VIBRATE", true)
                    else -> Triple(Glyph.BELL, "RING", false)
                }
                Quick.ROTATE -> Triple(Glyph.ROTATE, if (q.autoRotate) "ROTATE" else "LOCKED", q.autoRotate)
                Quick.FOCUS -> Triple(Glyph.TIMER, if (q.focusOn) "STOP" else "FOCUS", q.focusOn)
                Quick.SETTINGS -> Triple(Glyph.GEAR, "SETUP", false)
            }
            val dim = i == 0 && !q.torchAvailable
            dotPaint.color = if (active) Look.WHITE else Look.RAISED
            dotPaint.alpha = alpha
            canvas.drawCircle(cx, cy, r, dotPaint)
            glyphPaint.color = if (active) Look.BLACK else if (dim) Look.DOT_OFF else Look.WHITE
            glyphPaint.alpha = alpha
            glyph.draw(canvas, cx - glyph.width(gs) / 2f, cy, gs, glyphPaint)
            val lw = labelPaint.measureText(label)
            drawText(canvas, label, cx - lw / 2f, cy + r + context.dp(12f), lw + 1f, labelPaint, centerY = true)
            hitQuick[i].set(cx - step / 2f, cy - r - context.dp(6f), cx + step / 2f, cy + r + context.dp(20f))
        }
    }

    private fun drawCharging(canvas: Canvas, c: Transient.ChargeT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val low = c.level <= 20
        val tint = if (low) Look.accent else Look.WHITE
        var x = leftSlotStart()
        if (c.charging) {
            glyphPaint.color = tint; glyphPaint.alpha = alpha
            val gs = compactSize(15f)
            Glyph.BOLT.draw(canvas, x, cy, gs, glyphPaint)
            x += Glyph.BOLT.width(gs) + context.dp(4f)
        }
        // Five-dot battery gauge.
        val filled = ((c.level + 10) / 20).coerceIn(if (c.level > 0) 1 else 0, 5)
        val pitch = context.dp(5f)
        for (i in 0 until 5) {
            dotPaint.color = if (i < filled) tint else Look.DOT_OFF
            dotPaint.alpha = alpha
            canvas.drawCircle(x + pitch * (i + 0.5f), cy, context.dp(1.7f), dotPaint)
        }
        // With a time-to-full estimate, alternate the percentage with "FULL 42M".
        val phase2 = c.fullInMs > 0 && ((SystemClock.uptimeMillis() - c.at) / 2000) % 2 == 1L
        if (phase2) {
            drawRightText(canvas, "FULL ${durationShort(c.fullInMs)}", tint, alpha)
            return
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
                glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
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
        bigDotPaint.color = if (mode == AudioManager.RINGER_MODE_SILENT) Look.accent else Look.WHITE
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

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (shownMode == Mode.IDLE) {
                buzz()
                showTransient(Transient.PeekT(SystemClock.uptimeMillis()), 3000)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            when (shownMode) {
                // Long-press the pill (idle, music, call or a live activity) for quick toggles.
                Mode.IDLE, Mode.MEDIA, Mode.CALL, Mode.LIVE -> openToggles()
                else -> Unit
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vy) < abs(vx)) {
                // Swipe sideways on music to skip: left for next, right for previous.
                if (shownMode == Mode.MEDIA || shownMode == Mode.MEDIA_EXPANDED) {
                    skip(next = vx < 0)
                    return true
                }
                return false
            }
            if (vy < 0) swipeUp() else swipeDown()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (mediaExpanded) { mediaExpanded = false; resolve() }
            closeToggles()
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
                if (tapNoticeChip(n, x, y)) return
                n.intent?.let { send(it) }
                if (n.autoCancel) IslandHub.canceller?.invoke(n.key)
                endTransient()
            }
            Mode.CHARGING, Mode.RINGER, Mode.UNLOCK, Mode.BUDS, Mode.VOLUME, Mode.STATUS, Mode.PEEK -> endTransient()
            Mode.LIVE -> { currentLive()?.intent?.let { send(it) }; buzz() }
            Mode.TOGGLES -> tapToggles(x, y)
        }
    }

    private fun tapToggles(x: Float, y: Float) {
        val hit = hitQuick.indexOfFirst { it.contains(x, y) }
        if (hit < 0) {
            closeToggles()
            return
        }
        val action = Quick.values()[hit]
        buzz()
        if (action == Quick.SETTINGS) {
            closeToggles()
            host.openSettings()
            return
        }
        host.quickAction(action)
        handler.removeCallbacks(togglesTimeout)
        handler.postDelayed(togglesTimeout, 6000)
        invalidate()
    }

    private fun skip(next: Boolean) {
        val m = media ?: return
        val tc = m.controller?.transportControls
        if (tc != null) {
            if (next) tc.skipToNext() else tc.skipToPrevious()
        } else {
            seekTo(0)
        }
        buzz()
        // A small nudge in the swipe direction.
        springScale.target = 0.94f
        kick()
        handler.postDelayed({ springScale.target = 1f; kick() }, 90)
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
            Mode.LIVE -> currentLive()?.let { liveDismissed += it.key }
            Mode.NOTICE, Mode.CHARGING, Mode.RINGER, Mode.UNLOCK, Mode.BUDS, Mode.VOLUME, Mode.STATUS, Mode.PEEK -> {
                endTransient(); return
            }
            Mode.TOGGLES -> { closeToggles(); return }
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

    private fun send(pi: PendingIntent, fillIn: android.content.Intent? = null) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(context, 0, fillIn, null, null, null, opts.toBundle())
            } else {
                pi.send(context, 0, fillIn)
            }
        } catch (_: PendingIntent.CanceledException) {
        }
    }

    // ---- Notification buttons and quick replies ------------------------------------------------------------

    /** A chip under a notification: one of the app's own buttons, or a canned reply sent through its Reply. */
    private class Chip(val label: String, val action: NoticeAction, val replyText: String?)

    private val chipHits = ArrayList<Pair<RectF, Chip>>()
    private val chipPaint by lazy { textPaint(Look.monoBold(context), 11.5f, Look.WHITE) }

    private fun chipsFor(n: Notice): List<Chip> {
        val out = ArrayList<Chip>()
        val reply = n.actions.firstOrNull { it.reply != null }
        if (reply != null) QUICK_REPLIES.take(2).forEach { out += Chip(it, reply, it) }
        for (a in n.actions) {
            if (out.size >= 3) break
            if (a.reply != null) continue
            out += Chip(a.title, a, null)
        }
        // No quick replies fitted? Offer the Reply button itself to open the app.
        if (out.isEmpty() && reply != null) out += Chip(reply.title, reply, null)
        return out
    }

    private fun drawChips(canvas: Canvas, n: Notice, alpha: Int) {
        chipHits.clear()
        val chips = chipsFor(n)
        if (chips.isEmpty()) return
        val cy = pillRect.top + topZone + context.dp(80f)
        val h = context.dp(30f)
        var x = pillRect.left + context.dp(16f)
        val limit = pillRect.right - context.dp(16f)
        chipPaint.alpha = alpha
        for (c in chips) {
            val label = fit(c.label.uppercase(), chipPaint, context.dp(110f))
            val w = chipPaint.measureText(label) + context.dp(24f)
            if (x + w > limit) break
            val rect = RectF(x, cy - h / 2f, x + w, cy + h / 2f)
            dotPaint.color = if (c.replyText != null) Look.RAISED else Look.LINE
            dotPaint.alpha = alpha
            canvas.drawRoundRect(rect, h / 2f, h / 2f, dotPaint)
            drawText(canvas, label, x + context.dp(12f), cy, w, chipPaint, centerY = true)
            chipHits += rect to c
            x += w + context.dp(8f)
        }
    }

    /** True if the tap landed on a chip (and it was handled). */
    private fun tapNoticeChip(n: Notice, x: Float, y: Float): Boolean {
        val chip = chipHits.firstOrNull { it.first.contains(x, y) }?.second ?: return false
        buzz()
        val a = chip.action
        val input = a.reply
        if (chip.replyText != null && input != null) {
            val fill = android.content.Intent()
            val results = android.os.Bundle().apply { putCharSequence(input.resultKey, chip.replyText) }
            android.app.RemoteInput.addResultsToIntent(arrayOf(input), fill, results)
            send(a.intent, fill)
            showTransient(Transient.StatusT(Glyph.CHECK, "SENT", true), 1600)
        } else {
            send(a.intent)
            endTransient()
        }
        return true
    }

    // ---- Edge light ----------------------------------------------------------------------------------------

    private var edgeStart = 0L
    private var edgeColor = 0
    private val edgePos = FloatArray(2)

    private fun edgeActive() = edgeStart > 0 && SystemClock.uptimeMillis() - edgeStart < EDGE_MS

    /**
     * The point [d] along the outline of the rounded rect [b] (corner radius [rr]), measured clockwise
     * from the top centre. Plain geometry, so it behaves the same on every device.
     */
    private fun outlinePoint(b: RectF, rr: Float, d: Float, out: FloatArray) {
        val w = b.width() - 2 * rr
        val h = b.height() - 2 * rr
        val arc = (Math.PI * rr / 2).toFloat()
        val segs = floatArrayOf(w / 2f, arc, h, arc, w, arc, h, arc, w / 2f)
        val len = segs.sum()
        var t = ((d % len) + len) % len
        var i = 0
        while (i < segs.size - 1 && t > segs[i]) { t -= segs[i]; i++ }
        val f = if (segs[i] > 0f) t / segs[i] else 0f
        fun corner(cx: Float, cy: Float, startDeg: Double) {
            val ang = Math.toRadians(startDeg + 90.0 * f)
            out[0] = cx + (rr * Math.cos(ang)).toFloat()
            out[1] = cy + (rr * Math.sin(ang)).toFloat()
        }
        when (i) {
            0 -> { out[0] = b.centerX() + t; out[1] = b.top }
            1 -> corner(b.right - rr, b.top + rr, -90.0)
            2 -> { out[0] = b.right; out[1] = b.top + rr + t }
            3 -> corner(b.right - rr, b.bottom - rr, 0.0)
            4 -> { out[0] = b.right - rr - t; out[1] = b.bottom }
            5 -> corner(b.left + rr, b.bottom - rr, 90.0)
            6 -> { out[0] = b.left; out[1] = b.bottom - rr - t }
            7 -> corner(b.left + rr, b.top + rr, 180.0)
            else -> { out[0] = b.left + rr + t; out[1] = b.top }
        }
    }

    /** Two comets of dots chase once around the island's outline, like a Glyph light, then fade. */
    private fun drawEdgeLight(canvas: Canvas, r: Float, a: Float) {
        val t = ((SystemClock.uptimeMillis() - edgeStart) / EDGE_MS.toFloat()).coerceIn(0f, 1f)
        val inset = context.dp(2.2f)
        box.set(pillRect.left + inset, pillRect.top + inset, pillRect.right - inset, pillRect.bottom - inset)
        val rr = (r - inset).coerceIn(0f, min(box.width(), box.height()) / 2f)
        val len = 2 * (box.width() - 2 * rr) + 2 * (box.height() - 2 * rr) + (2 * Math.PI * rr).toFloat()
        if (len <= 0f) return
        val eased = 1f - (1f - t) * (1f - t)
        val fade = if (t > 0.75f) (1f - t) / 0.25f else 1f
        val step = context.dp(5f)
        for (comet in 0..1) {
            val head = comet * len / 2f + eased * len
            for (i in 0 until 16) {
                outlinePoint(box, rr, head - i * step, edgePos)
                dotPaint.color = edgeColor
                dotPaint.alpha = (255 * a * fade * (1f - i / 16f)).roundToInt().coerceIn(0, 255)
                canvas.drawCircle(edgePos[0], edgePos[1], context.dp(1.9f) * (1f - i / 28f), dotPaint)
            }
        }
    }

    private fun brighten(c: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c or (0xFF shl 24), hsv)
        if (hsv[1] < 0.12f) return Look.accent
        hsv[2] = hsv[2].coerceAtLeast(0.8f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    // ---- Volume, confirmations, peek ------------------------------------------------------------------------

    private fun drawVolume(canvas: Canvas, v: Transient.VolumeT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val pct = if (v.max > 0) v.level * 100 / v.max else 0
        glyphPaint.color = Look.WHITE
        glyphPaint.alpha = alpha
        (if (v.level == 0) Glyph.MUTE else Glyph.SPEAKER).draw(canvas, leftSlotStart(), cy, compactSize(14f), glyphPaint)
        drawRightText(canvas, "$pct%", Look.WHITE, alpha)
        // A row of dots under the camera line, filling left to right.
        val barY = pillRect.top + topZone + context.dp(8f)
        val left = pillRect.left + context.dp(20f)
        val right = pillRect.right - context.dp(20f)
        val n = 20
        val lit = ((pct / 100f) * n).roundToInt()
        val step = (right - left) / (n - 1)
        for (i in 0 until n) {
            dotPaint.color = when {
                i >= lit -> Look.DOT_OFF
                i == lit - 1 -> Look.accent
                else -> Look.WHITE
            }
            dotPaint.alpha = alpha
            canvas.drawCircle(left + i * step, barY, context.dp(1.9f), dotPaint)
        }
    }

    private fun drawStatus(canvas: Canvas, s: Transient.StatusT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        glyphPaint.color = if (s.lit) Look.WHITE else Look.GREY
        glyphPaint.alpha = alpha
        s.glyph.draw(canvas, leftSlotStart(), cy, compactSize(14f), glyphPaint)
        drawRightText(canvas, s.label, if (s.lit) Look.accent else Look.GREY, alpha)
    }

    /** Double-tap: the time on the left of the camera, battery on the right. */
    private fun drawPeek(canvas: Canvas, alpha: Int) {
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
            .replace(" AM", "").replace(" PM", "").replace(" am", "").replace(" pm", "")
        drawLeftText(canvas, time, Look.WHITE, alpha)
        val b = host.batteryLevel()
        drawRightText(canvas, if (b >= 0) "$b%" else "--", if (b in 0..20) Look.accent else Look.WHITE, alpha)
    }

    /** Left-hand compact text, shrunk to fit between the pill's edge and the camera. */
    private fun drawLeftText(canvas: Canvas, text: String, color: Int, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val slot = (pillRect.centerX() - context.dp(CAMERA_CLEAR_DP)) - leftSlotStart()
        val size = bigDotPaint.textSize
        bigDotPaint.textSize = min(size, compactSize(15f) * 1.05f)
        bigDotPaint.textSize = min(bigDotPaint.textSize, bigDotPaint.textSize * slot / max(1f, bigDotPaint.measureText(text)))
        bigDotPaint.color = color
        bigDotPaint.alpha = alpha
        drawText(canvas, text, leftSlotStart(), cy, slot + 1f, bigDotPaint, centerY = true)
        bigDotPaint.textSize = size
    }

    private fun durationShort(ms: Long): String {
        val m = Math.ceil(ms / 60_000.0).toInt().coerceAtLeast(1)
        return if (m >= 60) "${m / 60}H${"%02d".format(m % 60)}" else "${m}M"
    }

    private fun buzz() {
        if (!prefs.haptics) return
        Haptics.tick(context)
    }

    companion object {
        private const val SKIP_MS = 10_000L
        private const val EDGE_MS = 1400L
        private val QUICK_REPLIES = listOf("👍", "On my way")
        private const val UNLOCK_WAIT_MS = 4_000L
        private const val ELLIPSIS = "\u2026"
        /**
         * How far compact states (music, call, charging, ringer) reach past the idle pill on each side.
         * Kept small so they fit in the gap between the status-bar icons.
         */
        private const val SIDE_DP = 20f
        private const val ART_DP = 76f

        /** Half-width kept clear around the camera hole in compact states. */
        private const val CAMERA_CLEAR_DP = 16f
        /** How long the music island stays after you pause. */
        private const val PAUSE_LINGER_MS = 5_000L
        private const val FADE_OUT_MS = 90f
        private const val FADE_IN_MS = 200f
        private const val FADE_IN_DELAY_MS = 40f
    }
}
