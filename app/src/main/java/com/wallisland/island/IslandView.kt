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

        /** Sets [stream] to [level] (dragging the volume bar) and returns the level it ended up at. */
        fun setVolume(stream: Int, level: Int): Int

        /** Answers or ends the call through the phone app, for calls whose notification has no such button. */
        fun callAction(answer: Boolean)

        /** Pulls down the notification shade. */
        fun openShade()

        /** Sets screen brightness (0..100, perceptual) and returns where it ended up; -1 without permission. */
        fun setBrightness(level: Int): Int

        /** The island's own timers: a countdown of [ms], a stopwatch, or stop whichever is running. */
        fun startTimer(ms: Long)
        fun startStopwatch()
        fun stopTimers()
    }

    data class QuickState(
        val torch: Boolean,
        val torchAvailable: Boolean,
        val ringerMode: Int,
        val autoRotate: Boolean,
        /** The island's own countdown is running. */
        val focusOn: Boolean = false,
        val stopwatchOn: Boolean = false,
    )

    enum class Quick(val title: String) {
        TORCH("Torch"), RINGER("Sound mode"), VOLUME("Volume"), BRIGHTNESS("Brightness"), TIMER("Timers and stopwatch"),
        RECENT("Recent notifications"), ROTATE("Rotation lock");

        companion object {
            const val MAX = 5
            val DEFAULT = listOf(TORCH, VOLUME, BRIGHTNESS, TIMER, RECENT)

            fun parse(csv: String): List<Quick> =
                csv.split(',').mapNotNull { raw ->
                    // The old focus button became the timer panel.
                    val n = raw.trim().let { if (it == "FOCUS") "TIMER" else it }
                    values().firstOrNull { it.name == n }
                }.distinct().take(MAX).ifEmpty { DEFAULT }
        }
    }

    private enum class Mode {
        IDLE, CALL, CALL_CARD, LIVE, MEDIA, MEDIA_EXPANDED, NOTICE, CHARGING, RINGER, UNLOCK, BUDS, TOGGLES, VOLUME,
        STATUS, PEEK, HISTORY, TIMERS, NET, CAR,
    }

    private sealed class Transient {
        data class NoticeT(val notice: Notice) : Transient()
        data class ChargeT(
            val level: Int, val charging: Boolean, val fullInMs: Long = -1, val at: Long = 0L,
            /** Just plugged in: a wave of dots sweeps up to the level. */
            val plugIn: Boolean = false,
        ) : Transient()
        data class RingerT(val mode: Int) : Transient()
        data class UnlockT(val startedAt: Long) : Transient()
        data class BudsT(val battery: Int, val name: String?) : Transient()
        /** Joined a Wi-Fi network: its name and signal (0..4 bars). */
        data class NetT(val name: String, val bars: Int) : Transient()
        data class VolumeT(
            val level: Int, val max: Int, val stream: Int = AudioManager.STREAM_MUSIC,
            /** The same bar, driving screen brightness instead (level 0..100). */
            val brightness: Boolean = false,
        ) : Transient()

        /** A one-line confirmation: a glyph and a word (DND ON, WI-FI OFF, SENT, DONE…). */
        data class StatusT(val glyph: Glyph, val label: String, val lit: Boolean) : Transient()
        data class PeekT(val at: Long) : Transient()
    }

    // ---- State -----------------------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaInfo? = null
    private var call: CallInfo? = null
    private var callDismissed: String? = null

    /** An ongoing call tapped open to show its hang-up button. */
    private var callExpanded = false
    private val callCollapse = Runnable { callExpanded = false; resolve() }
    private val hitAnswer = RectF()
    private val hitHangUp = RectF()

    /** Swipe-down history of recent notifications. */
    private var historyOpen = false
    private var historyItems: List<Notice> = emptyList()
    private val historyIcons = HashMap<String, PictureArt?>()
    private val hitHistory = Array(IslandHub.HISTORY_SIZE) { RectF() }
    private val historyTimeout = Runnable { closeHistory() }

    /** The timer panel: 1, 5, 10, 25 minutes and a stopwatch (or Stop while one runs). */
    private var timersOpen = false
    private val hitTimer = Array(TIMER_CHOICES.size + 1) { RectF() }
    private val timersTimeout = Runnable { closeTimers() }

    /** Car mode: long-press (or tapping music) opens three big buttons instead of the quick panel. */
    private var carMode = false
    private var carOpen = false
    private val hitCar = Array(3) { RectF() }
    private val carTimeout = Runnable { closeCar() }

    /** The pinned note's row in the quick panel: tick it off, or tap the header to edit. */
    private val hitNoteCheck = RectF()
    private val hitNoteEdit = RectF()
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
    private var noticeExtra = 0f
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

    /** The split island's second bubble: what it shows, and how far it has popped out (0..1). */
    private sealed class Bubble {
        object Media : Bubble()
        data class Live(val live: LiveInfo) : Bubble()
    }

    private var bubble: Bubble? = null
    private var shownBubble: Bubble? = null
    private val springBubble = Spring(0f, 380f, 0.62f, 0.004f)
    private val bubbleRect = RectF()

    private fun bubbleSize() = context.dp(prefs.height.toFloat())
    private fun bubbleGap() = context.dp(7f)

    /**
     * The bubble pops out to the left of the pill (clear of the status icons on the right) with a springy overshoot, a black circle like the pill
     * itself: album art for music, or the glyph of the live activity.
     */
    private fun drawBubble(canvas: Canvas, a: Float) {
        val p = springBubble.value
        val b = shownBubble ?: return
        if (p <= 0.01f) return
        val d = bubbleSize()
        val cx = pillRect.left - bubbleGap() - d / 2f
        val cy = pillRect.top + d / 2f
        val r = d / 2f * p.coerceIn(0f, 1.15f)
        bubbleRect.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
        pill.shader = null
        pill.alpha = (255 * a * p.coerceIn(0f, 1f)).roundToInt()
        canvas.drawCircle(cx, cy, r, pill)
        val alpha = (255 * a * ((p - 0.5f) * 2f).coerceIn(0f, 1f)).roundToInt()
        if (alpha <= 0) return
        val gs = d * 0.5f
        when (b) {
            Bubble.Media -> {
                box.set(cx - gs / 2f, cy - gs / 2f, cx + gs / 2f, cy + gs / 2f)
                if (media?.art != null) drawArt(canvas, box, small = true, alpha = alpha)
                else drawVisualizer(canvas, cx + gs / 2f, cy, gs / 4f, 3, 4, media?.playing == true, alpha)
            }
            is Bubble.Live -> {
                val g = when (b.live.kind) {
                    LiveInfo.Kind.NAV -> Glyph.ARROW
                    LiveInfo.Kind.DELIVERY -> if (b.live.pkg in IslandNotificationListener.RIDE_APPS) Glyph.CAR else Glyph.BAG
                    LiveInfo.Kind.TIMER -> Glyph.TIMER
                    LiveInfo.Kind.EVENT -> Glyph.CALENDAR
                    LiveInfo.Kind.PROGRESS -> Glyph.DOWNLOAD
                    LiveInfo.Kind.HOTSPOT -> Glyph.HOTSPOT
                }
                glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
                val sz = gs * 0.9f
                g.draw(canvas, cx - g.width(sz) / 2f, cy, sz, glyphPaint)
            }
        }
    }

    /** Tapping the bubble opens its thing: the music player, or the live activity. */
    private fun tapBubble() {
        buzz()
        when (val b = bubble ?: return) {
            Bubble.Media -> { mediaExpanded = true; resolve() }
            is Bubble.Live -> {
                val l = b.live
                if (l.pkg == context.packageName && l.kind == LiveInfo.Kind.TIMER) openTimers() else l.intent?.let { send(it) }
            }
        }
    }

    /** The volume bar's fill (0..1), gliding to each new level. */
    private val springVolume = Spring(0f, 420f, 0.92f, 0.001f)

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
    private val hitUpNext = RectF()
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
        showTransient(Transient.UnlockT(now), 1700)
        // A little hop as the lock springs open.
        handler.postDelayed({ springScale.target = 1.07f; kick() }, 380)
        handler.postDelayed({ springScale.target = 1f; kick() }, 520)
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
        val wasRinging = call?.ringing == true
        call = info
        if (info == null) {
            callDismissed = null
            callExpanded = false
        } else if (wasRinging && !info.ringing) {
            // Picked up: settle into the compact timer.
            callExpanded = false
        }
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
        // Room for a second line of message when it needs one.
        val bigW = min(resources.displayMetrics.widthPixels - context.dp(24f), context.dp(360f))
        val textW = bigW - context.dp(16f + 40f + 12f + 20f).toFloat()
        noticeExtra = if (wrap(notice.text.replace('\n', ' '), bodyPaint, textW, 2).size > 1) context.dp(16f).toFloat() else 0f
        // Buttons need a moment longer to reach.
        val extra = if (notice.actions.isNotEmpty()) 3000L else 0L
        showTransient(Transient.NoticeT(notice), prefs.noticeSeconds * 1000L + extra)
        if (prefs.edgeLight) {
            edgeStart = SystemClock.uptimeMillis()
            edgeColor = if (notice.color != 0 && android.graphics.Color.alpha(notice.color) > 0) brighten(notice.color) else Look.accent
        }
        Haptics.notice(context)
    }

    fun removeNotice(key: String) {
        val t = transient
        if (t is Transient.NoticeT && t.notice.key == key) endTransient()
    }

    /** [fullInMs] > 0 alternates the percentage with "FULL · 42M". */
    fun showCharging(level: Int, charging: Boolean, fullInMs: Long = -1, plugIn: Boolean = false) {
        if (!prefs.showCharging || hidden || !screenOn) return
        if (transient is Transient.NoticeT) return
        val prev = transient as? Transient.ChargeT
        val at = prev?.at ?: SystemClock.uptimeMillis()
        val wave = plugIn || (prev?.plugIn == true && charging)
        val ms = if (fullInMs > 0 || wave) 4400L else 3200L
        showTransient(Transient.ChargeT(level, charging, fullInMs, at, wave), ms)
    }

    /** True when a transient (volume, confirmations) would actually be seen right now. */
    fun canShowTransient() = prefs.enabled && !hidden && screenOn

    fun showNet(name: String, bars: Int) {
        if (!canShowTransient() || transient is Transient.NoticeT) return
        showTransient(Transient.NetT(name, bars.coerceIn(0, 4)), 2600)
    }

    /** Mic or camera in use by some app: a coloured dot in the idle pill, and a note when it starts. */
    fun setPrivacy(mic: Boolean, cam: Boolean, app: String?) {
        val started = (mic && !micOn) || (cam && !camOn)
        micOn = mic
        camOn = cam
        if (started && canShowTransient() && transient !is Transient.NoticeT) {
            showTransient(Transient.StatusT(if (cam) Glyph.CAMERA else Glyph.MIC, app?.uppercase() ?: if (cam) "CAMERA" else "MIC", true), 2200)
        }
        invalidate()
    }

    private var micOn = false
    private var camOn = false

    /** The brightness bar (0..100), opened from the quick panel and dragged like the volume bar. */
    fun showBrightness(level: Int) {
        if (!canShowTransient() || volumeDragging) return
        val fill = level / 100f
        if (transient !is Transient.VolumeT) springVolume.snap(fill) else springVolume.target = fill
        showTransient(Transient.VolumeT(level, 100, brightness = true), VOLUME_LINGER_MS)
    }

    /** The volume bar; [linger] longer when it was opened by touch, so there's time to drag it. */
    fun showVolume(level: Int, max: Int, stream: Int = AudioManager.STREAM_MUSIC, linger: Boolean = false) {
        if (!canShowTransient() || volumeDragging) return
        val fill = if (max > 0) level.toFloat() / max else 0f
        // Fresh bar: start full-grown; already showing: glide from where it is.
        if (transient !is Transient.VolumeT) springVolume.snap(fill) else springVolume.target = fill
        showTransient(Transient.VolumeT(level, max, stream), if (linger) VOLUME_LINGER_MS else VOLUME_MS)
    }

    fun showStatus(glyph: Glyph, label: String, lit: Boolean) {
        if (!canShowTransient() || transient is Transient.NoticeT || togglesOpen) return
        showTransient(Transient.StatusT(glyph, label, lit), 2200)
    }

    fun showPeek() {
        if (!canShowTransient()) return
        peek()
    }

    /** Time and battery; weather joins as a second row once there's a reading (fetched if stale). */
    private fun peek() {
        showTransient(Transient.PeekT(SystemClock.uptimeMillis()), 3500)
        Weather.refresh(context) { if (transient is Transient.PeekT) resolve() }
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
        val c = call?.takeIf { it.key != callDismissed }
        val mode = when {
            // A ringing call outranks everything: it's the one thing that can't wait.
            c?.ringing == true -> Mode.CALL_CARD
            t is Transient.NoticeT -> Mode.NOTICE
            t is Transient.ChargeT -> Mode.CHARGING
            t is Transient.RingerT -> Mode.RINGER
            t is Transient.UnlockT -> Mode.UNLOCK
            t is Transient.BudsT -> Mode.BUDS
            t is Transient.VolumeT -> Mode.VOLUME
            t is Transient.StatusT -> Mode.STATUS
            t is Transient.PeekT -> Mode.PEEK
            t is Transient.NetT -> Mode.NET
            historyOpen -> Mode.HISTORY
            timersOpen -> Mode.TIMERS
            carOpen -> Mode.CAR
            togglesOpen -> Mode.TOGGLES
            mediaExpanded && mediaVisible() -> Mode.MEDIA_EXPANDED
            c != null -> if (callExpanded) Mode.CALL_CARD else Mode.CALL
            currentLive() != null -> Mode.LIVE
            mediaVisible() -> Mode.MEDIA
            else -> Mode.IDLE
        }
        if (mode == Mode.IDLE) mediaExpanded = false

        val idleW = context.dp(prefs.width.toFloat())
        val idleH = context.dp(prefs.height.toFloat())
        val bigW = min(resources.displayMetrics.widthPixels - context.dp(24f), context.dp(360f))
        // Plug-in wave and weather peek add a row of dots under the camera line.
        val twoRow = mode == Mode.NET || (mode == Mode.CHARGING && (t as? Transient.ChargeT)?.plugIn == true) ||
            (mode == Mode.PEEK && (peekWeather() != null || peekSteps() >= 0))
        val (w, h) = if (twoRow) {
            idleW + 2 * context.dp(SIDE_DP) + context.dp(24f) to idleH + context.dp(22f)
        } else when (mode) {
            Mode.IDLE -> idleW to idleH
            Mode.MEDIA, Mode.CHARGING, Mode.RINGER, Mode.CALL, Mode.LIVE, Mode.UNLOCK, Mode.BUDS, Mode.STATUS, Mode.PEEK ->
                idleW + 2 * context.dp(SIDE_DP) to idleH
            Mode.TOGGLES -> bigW to idleH + context.dp(if (prefs.pinnedNote.isNotBlank()) 124f else 84f)
            Mode.TIMERS -> bigW to idleH + context.dp(84f)
            Mode.CAR -> bigW to idleH + context.dp(96f)
            Mode.NOTICE -> bigW to idleH + noticeExtra.roundToInt() + context.dp(
                if ((t as? Transient.NoticeT)?.notice?.actions?.isNotEmpty() == true) 104f else 60f,
            )
            Mode.VOLUME, Mode.NET -> idleW + 2 * context.dp(SIDE_DP) + context.dp(24f) to idleH + context.dp(22f)
            Mode.MEDIA_EXPANDED -> bigW to idleH + context.dp(172f)
            Mode.CALL_CARD -> bigW to idleH + context.dp(64f)
            Mode.HISTORY -> bigW to idleH + context.dp(14f + HISTORY_ROW_DP * historyItems.size.coerceAtLeast(1))
        }
        val previewing = SystemClock.uptimeMillis() < previewUntil
        val visible = !hidden && prefs.enabled && (screenOn || previewing) &&
            (mode != Mode.IDLE || prefs.showIdle || previewing)

        springW.target = w
        springH.target = h
        springAlpha.target = if (visible) 1f else 0f
        host.onTouchable(visible)

        // Split island: a second thing going on at the same time gets its own little bubble.
        val second = if (!prefs.splitIsland) null else when (mode) {
            Mode.CALL -> if (mediaVisible()) Bubble.Media else currentLive()?.let { Bubble.Live(it) }
            Mode.LIVE -> if (mediaVisible()) Bubble.Media else null
            Mode.MEDIA -> currentLive()?.let { Bubble.Live(it) }
            else -> null
        }
        bubble = second
        if (second != null) shownBubble = second
        springBubble.target = if (second != null && visible) 1f else 0f

        val token: Any? = when (mode) {
            Mode.NOTICE -> (t as Transient.NoticeT).notice.key + t.notice.title + t.notice.text
            Mode.MEDIA, Mode.MEDIA_EXPANDED -> mode.name + media?.title
            Mode.CHARGING, Mode.RINGER -> t
            Mode.CALL -> call?.key
            Mode.CALL_CARD -> "card" + c?.key + c?.ringing
            Mode.HISTORY -> Mode.HISTORY.name + historyItems.size
            Mode.LIVE -> currentLive()?.key
            Mode.UNLOCK, Mode.BUDS, Mode.STATUS, Mode.PEEK, Mode.NET -> t
            // Volume steps update in place rather than cross-fading on every press.
            Mode.VOLUME -> Mode.VOLUME
            Mode.TOGGLES -> Mode.TOGGLES
            Mode.TIMERS -> Mode.TIMERS
            Mode.CAR -> "car" + media?.playing
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
        springW.step(dt); springH.step(dt); springAlpha.step(dt); springScale.step(dt); springVolume.step(dt); springBubble.step(dt)
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
            edgeActive() || springVolume.moving || springBubble.moving

    private fun needsTicker() = screenOn && springAlpha.value > 0f && (
        ((shownMode == Mode.MEDIA || shownMode == Mode.MEDIA_EXPANDED) && media?.playing == true) ||
            shownMode == Mode.CALL || shownMode == Mode.CALL_CARD || shownMode == Mode.UNLOCK || shownMode == Mode.CHARGING || shownMode == Mode.PEEK ||
            (shownMode == Mode.LIVE && currentLive()?.let {
                it.kind == LiveInfo.Kind.TIMER || it.kind == LiveInfo.Kind.DELIVERY || it.kind == LiveInfo.Kind.EVENT || it.indeterminate
            } == true)
        )

    private fun kick() {
        if (frameScheduled || !isAttachedToWindow) {
            if (!isAttachedToWindow) {
                // Not attached yet: jump straight to the end state.
                springW.snap(springW.target); springH.snap(springH.target); springAlpha.snap(springAlpha.target)
                springVolume.snap(springVolume.target)
                springBubble.snap(springBubble.target)
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
        // Room either side for the split bubble, so the pill stays centred on the camera.
        val bubbleRoom = if (springBubble.target > 0f || springBubble.value > 0.01f) 2 * (bubbleGap() + bubbleSize()) else 0f
        val needW = (max(springW.target, if (settled) 0f else springW.value) * 1.08f + slack + bubbleRoom).roundToInt()
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
        drawBubble(canvas, a)

        val ca = contentAlpha * a
        if (ca <= 0.01f) return
        // Content only draws once the shape is close enough to fit it.
        val alpha = (255 * ca).roundToInt()
        canvas.save()
        canvas.clipRect(pillRect)
        when (shownMode) {
            Mode.IDLE -> { drawPrivacyDot(canvas, alpha); drawNoteDot(canvas, alpha) }
            Mode.MEDIA -> drawMediaCompact(canvas, alpha)
            Mode.CALL -> drawCall(canvas, alpha)
            Mode.CALL_CARD -> drawCallCard(canvas, alpha)
            Mode.HISTORY -> drawHistory(canvas, alpha)
            Mode.MEDIA_EXPANDED -> drawMediaExpanded(canvas, alpha)
            Mode.NOTICE -> (shownTransient as? Transient.NoticeT)?.let { drawNotice(canvas, it.notice, alpha) }
            Mode.CHARGING -> (shownTransient as? Transient.ChargeT)?.let { drawCharging(canvas, it, alpha) }
            Mode.RINGER -> (shownTransient as? Transient.RingerT)?.let { drawRinger(canvas, it.mode, alpha) }
            Mode.LIVE -> currentLive()?.let { drawLive(canvas, it, alpha) }
            Mode.UNLOCK -> (shownTransient as? Transient.UnlockT)?.let { drawUnlock(canvas, it, alpha) }
            Mode.BUDS -> (shownTransient as? Transient.BudsT)?.let { drawBuds(canvas, it.battery, it.name, alpha) }
            Mode.TOGGLES -> drawToggles(canvas, alpha)
            Mode.TIMERS -> drawTimers(canvas, alpha)
            Mode.CAR -> drawCar(canvas, alpha)
            Mode.VOLUME -> (shownTransient as? Transient.VolumeT)?.let { drawVolume(canvas, it, alpha) }
            Mode.STATUS -> (shownTransient as? Transient.StatusT)?.let { drawStatus(canvas, it, alpha) }
            Mode.PEEK -> drawPeek(canvas, alpha)
            Mode.NET -> (shownTransient as? Transient.NetT)?.let { drawNet(canvas, it, alpha) }
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
        if (m.remote) {
            // Playing elsewhere: the cast glyph instead of the equaliser.
            val gs = compactSize(15f)
            glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
            Glyph.CAST.draw(canvas, rightSlotEnd() - Glyph.CAST.width(gs), cy, gs, glyphPaint)
            return
        }
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

    /**
     * A ringing call, or an ongoing one tapped open: who's calling, and big round buttons. Red hangs up
     * (or declines), green answers.
     */
    private fun drawCallCard(canvas: Canvas, alpha: Int) {
        val c = call ?: return
        val secs = ((System.currentTimeMillis() - c.startedAt) / 1000).coerceAtLeast(0)
        drawHeader(canvas, c.appName, if (c.ringing) "INCOMING" else fmt(secs * 1000), alpha)
        val cy = pillRect.top + topZone + context.dp(30f)

        // Phone badge; while ringing a soft accent ring pulses around it.
        val s = context.dp(40f)
        val left = pillRect.left + context.dp(16f)
        box.set(left, cy - s / 2f, left + s, cy + s / 2f)
        if (c.ringing) {
            val t = SystemClock.uptimeMillis() / 1000f
            val pulse = (sin(t * 5f) + 1f) / 2f
            dotPaint.color = ANSWER_GREEN
            dotPaint.alpha = (alpha * 0.28f * (1f - pulse)).roundToInt()
            canvas.drawCircle(box.centerX(), cy, s / 2f + context.dp(2f + 6f * pulse), dotPaint)
        }
        dotPaint.color = Look.RAISED; dotPaint.alpha = alpha
        canvas.drawCircle(box.centerX(), cy, s / 2f, dotPaint)
        glyphPaint.color = Look.WHITE; glyphPaint.alpha = alpha
        val gs = context.dp(16f)
        Glyph.PHONE.draw(canvas, box.centerX() - Glyph.PHONE.width(gs) / 2f, cy, gs, glyphPaint)

        // Buttons, from the right: hang up, then answer.
        val br = context.dp(20f)
        val pad = context.dp(6f)
        var bx = pillRect.right - context.dp(18f) - br
        drawCallButton(canvas, bx, cy, br, Look.RED, Glyph.HANG_UP, alpha)
        hitHangUp.set(bx - br - pad, cy - br - pad, bx + br + pad, cy + br + pad)
        hitAnswer.setEmpty()
        if (c.ringing) {
            bx -= br * 2f + context.dp(12f)
            drawCallButton(canvas, bx, cy, br, ANSWER_GREEN, Glyph.PHONE, alpha)
            hitAnswer.set(bx - br - pad, cy - br - pad, bx + br + pad, cy + br + pad)
        }

        val tx = box.right + context.dp(12f)
        val avail = bx - br - context.dp(10f) - tx
        titlePaint.alpha = alpha
        bodyPaint.alpha = alpha
        drawText(canvas, c.name.ifEmpty { c.appName }, tx, cy - context.dp(4f), avail, titlePaint)
        drawText(canvas, if (c.ringing) "Incoming call" else "Tap red to hang up", tx, cy + context.dp(14f), avail, bodyPaint)
    }

    private fun drawCallButton(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, glyph: Glyph, alpha: Int) {
        dotPaint.color = color; dotPaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, dotPaint)
        glyphPaint.color = Look.WHITE; glyphPaint.alpha = alpha
        val gs = context.dp(if (glyph == Glyph.HANG_UP) 13f else 14f)
        glyph.draw(canvas, cx - glyph.width(gs) / 2f, cy, gs, glyphPaint)
    }

    private fun tapCallCard(x: Float, y: Float) {
        val c = call ?: return
        when {
            hitHangUp.contains(x, y) -> {
                buzz()
                c.hangUp?.let { send(it) } ?: host.callAction(answer = false)
                callExpanded = false
                handler.removeCallbacks(callCollapse)
                resolve()
            }
            c.ringing && hitAnswer.contains(x, y) -> {
                buzz()
                c.answer?.let { send(it) } ?: host.callAction(answer = true)
            }
            else -> {
                c.intent?.let { send(it) }
                callExpanded = false
                resolve()
            }
        }
    }

    // ---- Notification history ----------------------------------------------------------------------------

    private fun openHistory(fromTap: Boolean = false) {
        val items = IslandHub.history.toList()
        if (items.isEmpty() && !fromTap) {
            // Pulled down with nothing of ours to show: go straight to the system's.
            host.openShade()
            return
        }
        historyItems = items
        historyIcons.keys.retainAll(items.map { it.key }.toSet())
        for (n in items) if (n.key !in historyIcons) {
            historyIcons[n.key] = n.avatar?.let { PictureArt(it, grayscale = prefs.dotArt) }
        }
        historyOpen = true
        togglesOpen = false
        mediaExpanded = false
        buzz()
        handler.removeCallbacks(historyTimeout)
        handler.postDelayed(historyTimeout, HISTORY_MS)
        resolve()
    }

    private fun closeHistory() {
        handler.removeCallbacks(historyTimeout)
        if (!historyOpen) return
        historyOpen = false
        resolve()
    }

    /** Recent notifications, newest first: avatar or app icon, who, what, and how long ago. */
    private fun drawHistory(canvas: Canvas, alpha: Int) {
        drawHeader(canvas, "Recent", "ALL ›", alpha)
        val rowH = context.dp(HISTORY_ROW_DP)
        var top = pillRect.top + topZone + context.dp(4f)
        val s = context.dp(30f)
        val left = pillRect.left + context.dp(18f)
        titlePaint.alpha = alpha
        bodyPaint.alpha = alpha
        labelPaint.alpha = alpha
        hitHistory.forEach { it.setEmpty() }
        if (historyItems.isEmpty()) {
            bodyPaint.alpha = alpha
            drawText(canvas, "No notifications right now", left, top + rowH / 2f, pillRect.width(), bodyPaint, centerY = true)
            return
        }
        for ((i, n) in historyItems.withIndex()) {
            val cy = top + rowH / 2f
            box.set(left, cy - s / 2f, left + s, cy + s / 2f)
            drawNoticeIcon(canvas, n, historyIcons[n.key], box, alpha)
            val ago = ago(n.postedAt)
            val aw = labelPaint.measureText(ago)
            val right = pillRect.right - context.dp(20f)
            drawText(canvas, ago, right - aw, cy - context.dp(7f), aw + 1f, labelPaint, centerY = true)
            val tx = box.right + context.dp(10f)
            val title = n.title.ifEmpty { n.appName }
            drawText(canvas, title, tx, cy - context.dp(12f), right - aw - context.dp(8f) - tx, titlePaint)
            drawText(canvas, n.text.replace('\n', ' ').ifEmpty { n.appName }, tx, cy + context.dp(5f), right - tx, bodyPaint)
            if (i < historyItems.size - 1) {
                dotPaint.color = Look.LINE; dotPaint.alpha = alpha
                canvas.drawRect(tx, top + rowH - 0.5f, right, top + rowH + 0.5f, dotPaint)
            }
            hitHistory[i].set(pillRect.left, top, pillRect.right, top + rowH)
            top += rowH
        }
    }

    private fun tapHistory(x: Float, y: Float) {
        val i = hitHistory.indexOfFirst { it.contains(x, y) }
        val n = historyItems.getOrNull(i)
        if (n == null) {
            closeHistory()
            // The header strip ("ALL ›") opens the full notification shade.
            if (y < pillRect.top + topZone) host.openShade()
            return
        }
        buzz()
        n.intent?.let { send(it) }
        if (n.autoCancel) IslandHub.canceller?.invoke(n.key)
        IslandHub.history.removeAll { it.key == n.key }
        closeHistory()
    }

    private fun ago(at: Long): String {
        val mins = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> "NOW"
            mins < 60 -> "${mins}M"
            mins < 24 * 60 -> "${mins / 60}H"
            else -> "${mins / (24 * 60)}D"
        }
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
    private fun drawHeader(
        canvas: Canvas, label: String, right: String?, alpha: Int, redDot: Boolean = true, dotColor: Int = Look.accent,
    ) {
        val cy = pillRect.top + topZone / 2f
        val x0 = pillRect.left + context.dp(22f)
        val cameraLeft = pillRect.centerX() - idleHalf - context.dp(8f)
        if (redDot) {
            dotPaint.color = dotColor; dotPaint.alpha = alpha
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

    /**
     * A notification: the app's name beside a dot in the app's own colour, the sender's picture with the
     * app's icon tucked into its corner, and up to two lines of the message.
     */
    private fun drawNotice(canvas: Canvas, n: Notice, alpha: Int) {
        drawHeader(canvas, n.appName, ago(n.postedAt), alpha, dotColor = appColor(n))
        val cy = pillRect.top + topZone + context.dp(26f)
        val s = context.dp(40f)
        box.set(pillRect.left + context.dp(16f), cy - s / 2f, pillRect.left + context.dp(16f) + s, cy + s / 2f)
        drawNoticeIcon(canvas, n, noticeAvatar, box, alpha)
        val tx = box.right + context.dp(12f)
        val avail = pillRect.right - context.dp(20f) - tx
        titlePaint.alpha = alpha
        bodyPaint.alpha = alpha
        val title = n.title.ifEmpty { n.appName }
        val lines = wrap(n.text.replace('\n', ' '), bodyPaint, avail, 2)
        when (lines.size) {
            0 -> drawText(canvas, title, tx, cy, avail, titlePaint, centerY = true)
            1 -> {
                drawText(canvas, title, tx, cy - context.dp(4f), avail, titlePaint)
                drawText(canvas, lines[0], tx, cy + context.dp(14f), avail, bodyPaint)
            }
            else -> {
                drawText(canvas, title, tx, cy - context.dp(10f), avail, titlePaint)
                drawText(canvas, lines[0], tx, cy + context.dp(8f), avail, bodyPaint)
                drawText(canvas, lines[1], tx, cy + context.dp(25f), avail, bodyPaint)
            }
        }
        drawChips(canvas, n, alpha)
    }

    /** The app's notification colour made readable on black, or the accent when it has none. */
    private fun appColor(n: Notice) =
        if (n.color != 0 && android.graphics.Color.alpha(n.color) > 0) brighten(n.color) else Look.accent

    /** The sender's picture with the app icon as a corner badge, or the app icon on its own. */
    private fun drawNoticeIcon(canvas: Canvas, n: Notice, avatar: PictureArt?, b: RectF, alpha: Int) {
        val s = b.width()
        if (avatar != null) {
            avatar.draw(canvas, b, s / 2f, alpha)
            val icon = n.icon ?: return
            val bs = s * 0.42f
            val bx = b.right - bs / 2f
            val by = b.bottom - bs / 2f
            dotPaint.color = Look.BLACK; dotPaint.alpha = alpha
            canvas.drawCircle(bx, by, bs / 2f + context.dp(1.5f), dotPaint)
            dotPaint.color = appColor(n); dotPaint.alpha = alpha
            canvas.drawCircle(bx, by, bs / 2f, dotPaint)
            drawTinted(canvas, icon, bx, by, bs * 0.30f, Look.BLACK, alpha)
            return
        }
        dotPaint.color = Look.RAISED; dotPaint.alpha = alpha
        canvas.drawCircle(b.centerX(), b.centerY(), s / 2f, dotPaint)
        n.icon?.let { drawTinted(canvas, it, b.centerX(), b.centerY(), s * 0.24f, appColor(n), alpha) }
    }

    private fun drawTinted(canvas: Canvas, d: android.graphics.drawable.Drawable, cx: Float, cy: Float, half: Float, tint: Int, alpha: Int) {
        val m = d.constantState?.newDrawable()?.mutate() ?: d.mutate()
        m.setTint(tint)
        m.alpha = alpha
        m.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        m.draw(canvas)
    }

    /** Greedy word wrap into at most [max] lines; the last line keeps the rest (trimmed with an ellipsis). */
    private fun wrap(text: String, p: TextPaint, width: Float, max: Int): List<String> {
        val t = text.trim()
        if (t.isEmpty() || width <= 0f) return emptyList()
        val out = ArrayList<String>()
        var rest = t
        while (rest.isNotEmpty() && out.size < max - 1 && p.measureText(rest) > width) {
            var n = p.breakText(rest, true, width, null).coerceAtLeast(1)
            val space = rest.lastIndexOf(' ', n)
            if (space > 0 && n < rest.length) n = space
            out += rest.substring(0, n).trimEnd()
            rest = rest.substring(n).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun drawMediaExpanded(canvas: Canvas, alpha: Int) {
        val m = media ?: return
        if (m.remote) {
            // "ON LIVING ROOM TV" with the cast glyph, in place of the equaliser.
            val where = "ON " + (m.device ?: "ANOTHER DEVICE").uppercase()
            drawHeader(canvas, m.appName, null, alpha, redDot = m.playing)
            val gs = context.dp(11f)
            val right = pillRect.right - context.dp(22f)
            val lw = min(labelPaint.measureText(where), pillRect.width() / 2f - idleHalf - context.dp(30f))
            labelPaint.alpha = alpha
            drawText(canvas, where, right - lw, pillRect.top + topZone / 2f, lw + 1f, labelPaint, centerY = true)
            glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
            Glyph.CAST.draw(canvas, right - lw - context.dp(6f) - Glyph.CAST.width(gs), pillRect.top + topZone / 2f, gs, glyphPaint)
        } else {
            drawHeader(canvas, m.appName, null, alpha, redDot = m.playing)
            drawVisualizer(canvas, pillRect.right - context.dp(22f), pillRect.top + topZone / 2f, context.dp(3.4f), 5, 4, m.playing, alpha)
        }

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
        // Up next, when the app shares its queue: tap it to jump there.
        hitUpNext.setEmpty()
        m.upNext?.let { next ->
            val y = top + s / 2f + context.dp(37f)
            labelPaint.alpha = alpha
            val tag = "NEXT "
            val tagW = labelPaint.measureText(tag)
            drawText(canvas, tag, tx, y, tagW + 1f, labelPaint, centerY = true)
            val size = bodyPaint.textSize
            bodyPaint.textSize = size * 0.86f
            drawText(canvas, next, tx + tagW + context.dp(2f), y, avail - tagW - context.dp(2f), bodyPaint, centerY = true)
            bodyPaint.textSize = size
            hitUpNext.set(tx - context.dp(4f), y - context.dp(12f), tx + avail, y + context.dp(12f))
        }
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

    private val hitQuick = Array(Quick.values().size) { RectF() }
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
                // The island's own countdown drains a row of dots along the bottom of the pill.
                if (l.pkg == context.packageName && l.countDown && l.progressMax > 0) {
                    val left = (l.chronoBase - System.currentTimeMillis()).coerceAtLeast(0) / 1000f
                    drawBottomDots(canvas, (left / l.progressMax).coerceIn(0f, 1f), moving = false, alpha = alpha)
                }
            }
            LiveInfo.Kind.HOTSPOT -> {
                glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
                Glyph.HOTSPOT.draw(canvas, x, cy, gs, glyphPaint)
                drawRightText(canvas, l.title.ifEmpty { "ON" }, Look.WHITE, alpha)
            }
            LiveInfo.Kind.DELIVERY -> {
                val ride = l.pkg in IslandNotificationListener.RIDE_APPS &&
                    !Regex("order|food|deliver|courier|parcel|package", RegexOption.IGNORE_CASE).containsMatchIn(l.text + l.title)
                val g = if (ride) Glyph.CAR else Glyph.BAG
                glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
                g.draw(canvas, x, cy, gs, glyphPaint)
                drawRightText(canvas, l.title.ifEmpty { l.appName }.uppercase(), Look.WHITE, alpha)
                // On its way: progress when the app reports it, else a dot travelling along the route.
                drawBottomDots(canvas, if (l.progressMax > 0) l.percent / 100f else -1f, moving = true, alpha = alpha)
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

    /**
     * A thin row of dots along the bottom of the compact pill. [fraction] of it is lit; with [moving], the
     * lit end is a bright "vehicle" dot, and with no known fraction (< 0) it travels back and forth.
     */
    private fun drawBottomDots(canvas: Canvas, fraction: Float, moving: Boolean, alpha: Int) {
        val y = pillRect.bottom - context.dp(4.5f)
        val left = pillRect.left + context.dp(18f)
        val right = pillRect.right - context.dp(18f)
        val n = 24
        val step = (right - left) / (n - 1)
        val headAt = if (fraction >= 0f) {
            fraction * (n - 1)
        } else {
            val t = (SystemClock.uptimeMillis() % 2400) / 2400f
            (if (t < 0.5f) t * 2 else 2 - t * 2) * (n - 1)
        }
        for (i in 0 until n) {
            val on = if (fraction >= 0f) i <= headAt else abs(i - headAt) < 1.5f
            val head = moving && abs(i - headAt) < 0.75f
            dotPaint.color = when {
                head -> Look.accent
                on && fraction >= 0f -> Look.WHITE
                on -> Look.GREY
                else -> Look.DOT_OFF
            }
            dotPaint.alpha = if (on && !head && fraction >= 0f) (alpha * 0.8f).roundToInt() else alpha
            canvas.drawCircle(left + i * step, y, context.dp(if (head) 1.8f else 1.1f), dotPaint)
        }
    }

    /**
     * Unlocked, in dots: the shackle lifts and swings open on its left leg, a ring of dots bursts out of the
     * lock, and a tick draws itself in on the right.
     */
    private fun drawUnlock(canvas: Canvas, t: Transient.UnlockT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        val gs = compactSize(16f)
        val el = (SystemClock.uptimeMillis() - t.startedAt).toFloat()
        val pitch = gs / 7f
        val r = pitch * 0.42f
        val x0 = leftSlotStart()
        val cx = x0 + pitch * 2.5f
        // Body: 5 x 3 dots in the lower half.
        dotPaint.color = Look.WHITE
        dotPaint.alpha = alpha
        val bodyTop = cy + pitch * 0.5f
        for (row in 0 until 3) for (col in 0 until 5) {
            canvas.drawCircle(x0 + pitch * (col + 0.5f), bodyTop + pitch * row, r, dotPaint)
        }
        // Shackle: an arch of dots above the body that rises, then swings open around its left leg.
        val lift = ease(((el - 120f) / 200f).coerceIn(0f, 1f)) * pitch * 1.2f
        val swing = ease(((el - 260f) / 220f).coerceIn(0f, 1f)) * -38f
        val pivotX = x0 + pitch * 0.5f
        val pivotY = bodyTop - pitch * 0.5f - lift
        val arch = listOf(0f to 0f, 0f to -1f, 0f to -2f, 1f to -3f, 2f to -3.3f, 3f to -3f, 4f to -2f, 4f to -1f, 4f to 0f)
        canvas.save()
        canvas.rotate(swing, pivotX, pivotY)
        for ((dx, dy) in arch) canvas.drawCircle(pivotX + dx * pitch, pivotY + dy * pitch * 0.9f, r, dotPaint)
        canvas.restore()
        // Burst: eight dots fly out of the lock and fade.
        val burst = ((el - 380f) / 420f).coerceIn(0f, 1f)
        if (burst > 0f && burst < 1f) {
            dotPaint.color = Look.accent
            dotPaint.alpha = (alpha * (1f - burst)).roundToInt()
            val rad = gs * (0.4f + 0.9f * ease(burst))
            for (i in 0 until 8) {
                val a = Math.PI * 2 * i / 8
                canvas.drawCircle(cx + (rad * kotlin.math.cos(a)).toFloat(), cy + (rad * kotlin.math.sin(a)).toFloat(), r * 1.1f, dotPaint)
            }
        }
        // The tick writes itself in, dot by dot.
        val tick = ((el - 420f) / 300f).coerceIn(0f, 1f)
        if (tick > 0f) {
            glyphPaint.color = Look.accent
            glyphPaint.alpha = (alpha * tick).roundToInt()
            val cw = Glyph.CHECK.width(gs)
            canvas.save()
            canvas.clipRect(rightSlotEnd() - cw, pillRect.top, rightSlotEnd() - cw + cw * tick, pillRect.bottom)
            Glyph.CHECK.draw(canvas, rightSlotEnd() - cw, cy, gs, glyphPaint)
            canvas.restore()
        }
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    // ---- Car mode ------------------------------------------------------------------------------------------

    fun setCarMode(on: Boolean) {
        if (carMode == on) return
        carMode = on
        if (!on) closeCar()
        if (canShowTransient()) showTransient(Transient.StatusT(Glyph.CAR, if (on) "CAR MODE" else "CAR OFF", on), 2000)
    }

    private fun openCar() {
        carOpen = true
        togglesOpen = false
        historyOpen = false
        timersOpen = false
        mediaExpanded = false
        buzz()
        handler.removeCallbacks(carTimeout)
        handler.postDelayed(carTimeout, 8000)
        resolve()
    }

    private fun closeCar() {
        handler.removeCallbacks(carTimeout)
        if (!carOpen) return
        carOpen = false
        resolve()
    }

    /** Three big round buttons you can hit without looking: previous, play / pause, next. */
    private fun drawCar(canvas: Canvas, alpha: Int) {
        val m = media
        drawHeader(canvas, m?.title?.ifEmpty { null } ?: "Car", "CAR", alpha)
        val cy = pillRect.top + topZone + context.dp(46f)
        val step = (pillRect.width() - context.dp(40f)) / 3f
        val r = min(context.dp(32f), step / 2f - context.dp(6f))
        val playing = m?.playing == true
        for (i in 0 until 3) {
            val cx = pillRect.left + context.dp(20f) + step * (i + 0.5f)
            val main = i == 1
            dotPaint.color = if (main) Look.WHITE else Look.RAISED
            dotPaint.alpha = alpha
            canvas.drawCircle(cx, cy, if (main) r else r * 0.82f, dotPaint)
            val g = when (i) {
                0 -> Glyph.PREV
                1 -> if (playing) Glyph.PAUSE else Glyph.PLAY
                else -> Glyph.NEXT
            }
            glyphPaint.color = if (main) Look.BLACK else Look.WHITE
            glyphPaint.alpha = alpha
            val gs = context.dp(if (main) 20f else 16f)
            g.draw(canvas, cx - g.width(gs) / 2f, cy, gs, glyphPaint)
            hitCar[i].set(cx - step / 2f, cy - r - context.dp(10f), cx + step / 2f, cy + r + context.dp(10f))
        }
    }

    private fun tapCar(x: Float, y: Float) {
        val i = hitCar.indexOfFirst { it.contains(x, y) }
        val tc = media?.controller?.transportControls
        if (i < 0 || tc == null) {
            closeCar()
            return
        }
        buzz()
        when (i) {
            0 -> tc.skipToPrevious()
            1 -> if (media?.playing == true) tc.pause() else tc.play()
            else -> tc.skipToNext()
        }
        handler.removeCallbacks(carTimeout)
        handler.postDelayed(carTimeout, 8000)
        invalidate()
    }

    /** A pinned note waits: a small dot inside the idle pill's left end. */
    private fun drawNoteDot(canvas: Canvas, alpha: Int) {
        if (prefs.pinnedNote.isBlank()) return
        dotPaint.color = Look.WHITE
        dotPaint.alpha = alpha
        canvas.drawCircle(pillRect.left + pillRect.height() / 2f, pillRect.top + topZone / 2f, context.dp(2.2f), dotPaint)
    }

    // ---- Timers --------------------------------------------------------------------------------------------

    private fun openTimers() {
        timersOpen = true
        togglesOpen = false
        historyOpen = false
        mediaExpanded = false
        buzz()
        handler.removeCallbacks(timersTimeout)
        handler.postDelayed(timersTimeout, 6000)
        resolve()
    }

    private fun closeTimers() {
        handler.removeCallbacks(timersTimeout)
        if (!timersOpen) return
        timersOpen = false
        resolve()
    }

    /** Four countdowns and a stopwatch as round buttons; while one runs, the last button is a red Stop. */
    private fun drawTimers(canvas: Canvas, alpha: Int) {
        val q = host.quickState()
        val running = q.focusOn || q.stopwatchOn
        drawHeader(canvas, "Timer", if (running) "RUNNING" else null, alpha)
        val cy = pillRect.top + topZone + context.dp(26f)
        val count = TIMER_CHOICES.size + 1
        val inset = context.dp(14f)
        val step = (pillRect.width() - inset * 2) / count
        val r = min(context.dp(22f), step / 2f - context.dp(8f))
        labelPaint.alpha = alpha
        for (i in 0 until count) {
            val cx = pillRect.left + inset + step * (i + 0.5f)
            val last = i == count - 1
            val label: String
            if (!last) {
                dotPaint.color = Look.RAISED; dotPaint.alpha = alpha
                canvas.drawCircle(cx, cy, r, dotPaint)
                val num = "${TIMER_CHOICES[i]}"
                val size = bigDotPaint.textSize
                bigDotPaint.textSize = min(size, r * 1.05f)
                bigDotPaint.color = Look.WHITE; bigDotPaint.alpha = alpha
                val w = bigDotPaint.measureText(num)
                drawText(canvas, num, cx - w / 2f, cy, w + 1f, bigDotPaint, centerY = true)
                bigDotPaint.textSize = size
                label = "MIN"
            } else {
                dotPaint.color = if (running) Look.RED else Look.RAISED; dotPaint.alpha = alpha
                canvas.drawCircle(cx, cy, r, dotPaint)
                glyphPaint.color = Look.WHITE; glyphPaint.alpha = alpha
                val gs = context.dp(15f)
                val g = if (running) Glyph.PAUSE else Glyph.TIMER
                g.draw(canvas, cx - g.width(gs) / 2f, cy, gs, glyphPaint)
                label = if (running) "STOP" else "STOPWATCH"
            }
            val base = labelPaint.textSize
            val room = step - context.dp(4f)
            if (labelPaint.measureText(label) > room) labelPaint.textSize = base * room / labelPaint.measureText(label)
            val lw = labelPaint.measureText(label)
            drawText(canvas, label, cx - lw / 2f, cy + r + context.dp(12f), lw + 1f, labelPaint, centerY = true)
            labelPaint.textSize = base
            hitTimer[i].set(cx - step / 2f, cy - r - context.dp(6f), cx + step / 2f, cy + r + context.dp(20f))
        }
    }

    private fun tapTimers(x: Float, y: Float) {
        val i = hitTimer.indexOfFirst { it.contains(x, y) }
        if (i < 0) {
            closeTimers()
            return
        }
        buzz()
        val q = host.quickState()
        when {
            i < TIMER_CHOICES.size -> host.startTimer(TIMER_CHOICES[i] * 60_000L)
            q.focusOn || q.stopwatchOn -> host.stopTimers()
            else -> host.startStopwatch()
        }
        closeTimers()
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
        val note = prefs.pinnedNote.trim()
        drawHeader(canvas, "Quick", if (note.isEmpty()) "+ NOTE" else "EDIT NOTE", alpha)
        hitNoteEdit.set(pillRect.centerX() + idleHalf, pillRect.top, pillRect.right, pillRect.top + topZone)
        val q = host.quickState()
        var cy = pillRect.top + topZone + context.dp(26f)
        hitNoteCheck.setEmpty()
        if (note.isNotEmpty()) {
            // The pinned note, with a ring to tick it off.
            val ny = pillRect.top + topZone + context.dp(18f)
            val left = pillRect.left + context.dp(22f)
            glyphPaint.color = Look.accent; glyphPaint.alpha = alpha
            val gs = context.dp(11f)
            Glyph.LIST.draw(canvas, left, ny, gs, glyphPaint)
            val cr = context.dp(10f)
            val ccx = pillRect.right - context.dp(26f)
            dotPaint.color = Look.RAISED; dotPaint.alpha = alpha
            canvas.drawCircle(ccx, ny, cr, dotPaint)
            glyphPaint.color = Look.WHITE
            Glyph.CHECK.draw(canvas, ccx - Glyph.CHECK.width(gs * 0.8f) / 2f, ny, gs * 0.8f, glyphPaint)
            hitNoteCheck.set(ccx - cr * 2f, ny - cr * 2f, ccx + cr * 2f, ny + cr * 2f)
            titlePaint.alpha = alpha
            val tx = left + Glyph.LIST.width(gs) + context.dp(10f)
            drawText(canvas, note, tx, ny, ccx - cr - context.dp(10f) - tx, titlePaint, centerY = true)
            cy += context.dp(40f)
        }
        val buttons = quickButtons()
        val count = buttons.size
        // Five at most, spread with room to breathe inside the pill's rounded ends.
        val inset = context.dp(14f)
        val step = (pillRect.width() - inset * 2) / count
        val r = min(context.dp(22f), step / 2f - context.dp(8f))
        val gs = context.dp(15f)
        labelPaint.alpha = alpha
        hitQuick.forEach { it.setEmpty() }
        for (i in 0 until count) {
            val cx = pillRect.left + inset + step * (i + 0.5f)
            val (glyph, label, active) = when (buttons[i]) {
                Quick.TORCH -> Triple(Glyph.TORCH, "TORCH", q.torch)
                Quick.RINGER -> when (q.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> Triple(Glyph.BELL_OFF, "SILENT", true)
                    AudioManager.RINGER_MODE_VIBRATE -> Triple(Glyph.VIBRATE, "VIBRATE", true)
                    else -> Triple(Glyph.BELL, "RING", false)
                }
                Quick.VOLUME -> Triple(Glyph.SPEAKER, "VOLUME", false)
                Quick.RECENT -> Triple(Glyph.LIST, "RECENT", false)
                Quick.ROTATE -> Triple(Glyph.ROTATE, if (q.autoRotate) "ROTATE" else "LOCKED", q.autoRotate)
                Quick.BRIGHTNESS -> Triple(Glyph.SUN, "BRIGHT", false)
                Quick.TIMER -> Triple(Glyph.TIMER, "TIMER", q.focusOn || q.stopwatchOn)
            }
            val dim = buttons[i] == Quick.TORCH && !q.torchAvailable
            dotPaint.color = if (active) Look.WHITE else Look.RAISED
            dotPaint.alpha = alpha
            canvas.drawCircle(cx, cy, r, dotPaint)
            glyphPaint.color = if (active) Look.BLACK else if (dim) Look.DOT_OFF else Look.WHITE
            glyphPaint.alpha = alpha
            glyph.draw(canvas, cx - glyph.width(gs) / 2f, cy, gs, glyphPaint)
            // Shrink a long label ("VIBRATE") to its slot rather than let neighbours touch.
            val baseSize = labelPaint.textSize
            val room = step - context.dp(4f)
            if (labelPaint.measureText(label) > room) labelPaint.textSize = baseSize * room / labelPaint.measureText(label)
            val lw = labelPaint.measureText(label)
            drawText(canvas, label, cx - lw / 2f, cy + r + context.dp(12f), lw + 1f, labelPaint, centerY = true)
            labelPaint.textSize = baseSize
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
        if (c.plugIn) drawChargeWave(canvas, c, tint, alpha)
    }

    /**
     * Plugged in: a row of dots under the camera fills up to the battery level with an ease-out sweep, then
     * a bright pulse keeps running along the filled part while the pill is open.
     */
    private fun drawChargeWave(canvas: Canvas, c: Transient.ChargeT, tint: Int, alpha: Int) {
        val barY = pillRect.top + topZone + context.dp(8f)
        val left = pillRect.left + context.dp(20f)
        val right = pillRect.right - context.dp(20f)
        val n = 20
        val step = (right - left) / (n - 1)
        val elapsed = (SystemClock.uptimeMillis() - c.at).coerceAtLeast(0)
        val sweep = (elapsed / 900f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
        val lit = c.level.coerceIn(0, 100) / 100f * n * sweep
        val litCount = kotlin.math.ceil(lit).toInt()
        // The pulse starts once the sweep lands, travelling left to right.
        val pulse = if (sweep >= 1f && litCount > 0) ((elapsed - 900) % 1100) / 1100f * (litCount + 3) - 1.5f else -99f
        val r = context.dp(1.9f)
        for (i in 0 until n) {
            val fill = (lit - i).coerceIn(0f, 1f)
            val near = (1f - abs(i - pulse) / 2f).coerceIn(0f, 1f)
            dotPaint.color = when {
                fill <= 0f -> Look.DOT_OFF
                i == litCount - 1 || near > 0.5f -> Look.accent
                else -> tint
            }
            dotPaint.alpha = if (fill <= 0f) alpha else (alpha * (0.35f + 0.65f * fill)).roundToInt()
            canvas.drawCircle(left + i * step, barY, r * (1f + 0.7f * near * fill), dotPaint)
        }
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
            // The empty pill waits to be sure it isn't a double-tap (peek) before opening recents.
            if (shownMode == Mode.IDLE) return true
            onTap(e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (shownMode == Mode.IDLE) openHistory(fromTap = true)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (shownMode == Mode.IDLE) {
                buzz()
                peek()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            when (shownMode) {
                // Long-press the pill (idle, music, call or a live activity) for quick toggles.
                Mode.IDLE, Mode.MEDIA, Mode.CALL, Mode.LIVE -> if (carMode && media != null) openCar() else openToggles()
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
            if (callExpanded) { callExpanded = false; resolve() }
            closeHistory()
            closeTimers()
            closeCar()
            closeToggles()
            return false
        }
        if (springAlpha.target == 0f) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !pillRect.contains(event.x, event.y) &&
            !(bubbleRect.contains(event.x, event.y) && springBubble.value > 0.5f)
        ) return false
        if (scrubTouch(event)) return true
        if (volumeTouch(event)) return true
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
        if (springBubble.value > 0.5f && bubbleRect.contains(x, y)) {
            tapBubble()
            return
        }
        when (shownMode) {
            Mode.IDLE -> { springScale.target = 1.06f; kick(); handler.postDelayed({ springScale.target = 1f; kick() }, 120) }
            Mode.MEDIA -> if (carMode) openCar() else { mediaExpanded = true; buzz(); resolve() }
            Mode.CALL -> {
                // Open it up to reach the hang-up button.
                callExpanded = true
                buzz()
                handler.removeCallbacks(callCollapse)
                handler.postDelayed(callCollapse, CALL_CARD_MS)
                resolve()
            }
            Mode.CALL_CARD -> tapCallCard(x, y)
            Mode.HISTORY -> tapHistory(x, y)
            Mode.MEDIA_EXPANDED -> tapExpandedMedia(x, y)
            Mode.NOTICE -> (shownTransient as? Transient.NoticeT)?.notice?.let { n ->
                if (tapNoticeChip(n, x, y)) return
                n.intent?.let { send(it) }
                if (n.autoCancel) IslandHub.canceller?.invoke(n.key)
                endTransient()
            }
            Mode.CHARGING, Mode.RINGER, Mode.UNLOCK, Mode.BUDS, Mode.VOLUME, Mode.STATUS, Mode.PEEK, Mode.NET -> endTransient()
            Mode.LIVE -> {
                val l = currentLive()
                buzz()
                // The island's own timer or stopwatch: open the panel to stop or change it.
                if (l != null && l.pkg == context.packageName && l.kind == LiveInfo.Kind.TIMER) openTimers()
                else l?.intent?.let { send(it) }
            }
            Mode.TOGGLES -> tapToggles(x, y)
            Mode.TIMERS -> tapTimers(x, y)
            Mode.CAR -> tapCar(x, y)
        }
    }

    private fun quickButtons() = Quick.parse(prefs.quickButtons)

    private fun tapToggles(x: Float, y: Float) {
        if (hitNoteEdit.contains(x, y)) {
            buzz()
            closeToggles()
            ReplyActivity.openNote(context)
            return
        }
        if (!hitNoteCheck.isEmpty && hitNoteCheck.contains(x, y)) {
            // Ticked off: gone, with a little DONE.
            buzz()
            prefs.pinnedNote = ""
            closeToggles()
            showTransient(Transient.StatusT(Glyph.CHECK, "DONE", true), 1600)
            return
        }
        val hit = hitQuick.indexOfFirst { it.contains(x, y) }
        if (hit < 0) {
            closeToggles()
            return
        }
        val action = quickButtons().getOrNull(hit) ?: return closeToggles()
        buzz()
        if (action == Quick.RECENT) {
            closeToggles()
            openHistory(fromTap = true)
            return
        }
        if (action == Quick.TIMER) {
            closeToggles()
            openTimers()
            return
        }
        if (action == Quick.VOLUME || action == Quick.BRIGHTNESS) {
            // Swap the panel for the volume bar, ready to drag.
            closeToggles()
            host.quickAction(action)
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

    // ---- Dragging the volume bar ----

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var volumeDragging = false
    private var volumeDownX = 0f
    private var volumeDownY = 0f
    private var volumeStart = 0

    /**
     * Slide sideways on the volume bar to set the volume: relative to where the finger lands, so a tap
     * never jumps it (a tap still closes the bar, a swipe up still dismisses it).
     */
    private fun volumeTouch(e: MotionEvent): Boolean {
        val v = shownTransient as? Transient.VolumeT
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                volumeDragging = false
                if (shownMode != Mode.VOLUME || v == null) return false
                volumeDownX = e.x
                volumeDownY = e.y
                volumeStart = v.level
                // Held open while the finger is down.
                handler.removeCallbacks(transientTimeout)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!volumeDragging) {
                    if (shownMode != Mode.VOLUME || v == null) return false
                    val dx = e.x - volumeDownX
                    if (abs(dx) < touchSlop || abs(dx) < abs(e.y - volumeDownY)) return false
                    volumeDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // The gesture detector saw the press; stop it turning into a tap or long-press.
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel)
                    cancel.recycle()
                    setPressed(false)
                }
                if (v != null) dragVolume(v, e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (shownMode == Mode.VOLUME && transient is Transient.VolumeT) {
                    handler.removeCallbacks(transientTimeout)
                    handler.postDelayed(transientTimeout, VOLUME_MS)
                }
                if (!volumeDragging) return false
                volumeDragging = false
                return true
            }
        }
        return volumeDragging
    }

    private fun dragVolume(v: Transient.VolumeT, x: Float) {
        if (v.max <= 0) return
        val span = (pillRect.width() - context.dp(40f)).coerceAtLeast(1f)
        val want = (volumeStart + (x - volumeDownX) / span * v.max).roundToInt().coerceIn(0, v.max)
        if (want == v.level) return
        val got = if (v.brightness) host.setBrightness(want).let { if (it < 0) return else it } else host.setVolume(v.stream, want)
        if (got == v.level) return
        buzz()
        val next = v.copy(level = got)
        transient = next
        pendingTransient = next
        shownTransient = next
        springVolume.target = got.toFloat() / v.max
        kick()
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
            m.upNextId >= 0 && hitUpNext.contains(x, y) -> {
                buzz()
                try {
                    tc?.skipToQueueItem(m.upNextId)
                } catch (_: Exception) {
                    tc?.skipToNext()
                }
            }
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
            Mode.CALL_CARD -> if (call?.ringing == true) callDismissed = call?.key else callExpanded = false
            Mode.HISTORY -> { closeHistory(); return }
            Mode.LIVE -> currentLive()?.let { liveDismissed += it.key }
            Mode.NOTICE, Mode.CHARGING, Mode.RINGER, Mode.UNLOCK, Mode.BUDS, Mode.VOLUME, Mode.STATUS, Mode.PEEK, Mode.NET -> {
                endTransient(); return
            }
            Mode.TOGGLES -> { closeToggles(); return }
            Mode.TIMERS -> { closeTimers(); return }
            Mode.CAR -> { closeCar(); return }
            Mode.IDLE -> Unit
        }
        resolve()
    }

    private fun swipeDown() {
        if (shownMode == Mode.HISTORY) {
            // Pull again for the full notification shade.
            closeHistory()
            host.openShade()
            return
        }
        if (shownMode == Mode.IDLE || shownMode == Mode.LIVE || shownMode == Mode.CALL) {
            openHistory()
            return
        }
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
    /** A button under a notification: a canned reply, the typed-reply chip, or one of the app's own actions. */
    private class Chip(val label: String, val action: NoticeAction, val replyText: String?, val typed: Boolean = false)

    private val chipHits = ArrayList<Pair<RectF, Chip>>()
    private val chipPaint by lazy { textPaint(Look.monoBold(context), 11.5f, Look.WHITE) }

    private fun chipsFor(n: Notice): List<Chip> {
        val out = ArrayList<Chip>()
        val reply = n.actions.firstOrNull { it.reply != null }
        if (reply != null) {
            // Your own words first, then the one-tap replies.
            out += Chip("✎ Reply", reply, null, typed = true)
            QUICK_REPLIES.take(2).forEach { out += Chip(it, reply, it) }
        }
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
        val cy = pillRect.top + topZone + context.dp(80f) + noticeExtra
        val h = context.dp(30f)
        var x = pillRect.left + context.dp(16f)
        val limit = pillRect.right - context.dp(16f)
        chipPaint.alpha = alpha
        for (c in chips) {
            val label = fit(c.label.uppercase(), chipPaint, context.dp(110f))
            val w = chipPaint.measureText(label) + context.dp(24f)
            if (x + w > limit) break
            val rect = RectF(x, cy - h / 2f, x + w, cy + h / 2f)
            dotPaint.color = when {
                c.typed -> appColor(n)
                c.replyText != null -> Look.RAISED
                else -> Look.LINE
            }
            dotPaint.alpha = alpha
            canvas.drawRoundRect(rect, h / 2f, h / 2f, dotPaint)
            // Dark text on the coloured typed-reply chip, white on the others.
            chipPaint.color = if (c.typed) Look.BLACK else Look.WHITE
            chipPaint.alpha = alpha
            drawText(canvas, label, x + context.dp(12f), cy, w, chipPaint, centerY = true)
            chipPaint.color = Look.WHITE
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
        if (chip.typed) {
            ReplyActivity.open(context, n, a)
            endTransient()
            return true
        }
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
        val icon = when {
            v.brightness -> Glyph.SUN
            v.stream == REMOTE_STREAM -> Glyph.CAST
            v.level == 0 -> Glyph.MUTE
            else -> Glyph.SPEAKER
        }
        icon.draw(canvas, leftSlotStart(), cy, compactSize(14f), glyphPaint)
        drawRightText(canvas, "$pct%", Look.WHITE, alpha)
        // A row of dots under the camera line, filling left to right and gliding between levels. The lit
        // end is the accent colour, and swells while it's being dragged.
        val barY = pillRect.top + topZone + context.dp(8f)
        val left = pillRect.left + context.dp(20f)
        val right = pillRect.right - context.dp(20f)
        val n = 20
        val lit = springVolume.value.coerceIn(0f, 1f) * n
        val head = kotlin.math.ceil(lit).toInt() - 1
        val step = (right - left) / (n - 1)
        val r = context.dp(1.9f)
        for (i in 0 until n) {
            val fill = (lit - i).coerceIn(0f, 1f)
            dotPaint.color = when {
                fill <= 0f -> Look.DOT_OFF
                i == head -> Look.accent
                else -> Look.WHITE
            }
            dotPaint.alpha = if (fill <= 0f) alpha else (alpha * (0.35f + 0.65f * fill)).toInt()
            val radius = if (i == head && volumeDragging) r * 1.8f else r
            canvas.drawCircle(left + i * step, barY, radius, dotPaint)
        }
    }

    /** Green while a camera is open, orange while the mic records, just inside the idle pill's right end. */
    private fun drawPrivacyDot(canvas: Canvas, alpha: Int) {
        if (!micOn && !camOn) return
        dotPaint.color = if (camOn) 0xFF2BD16B.toInt() else 0xFFFF9F0A.toInt()
        dotPaint.alpha = alpha
        canvas.drawCircle(pillRect.right - pillRect.height() / 2f, pillRect.top + topZone / 2f, context.dp(2.6f), dotPaint)
    }

    /** Wi-Fi joined: the glyph and signal bars on top, the network's name under the camera. */
    private fun drawNet(canvas: Canvas, n: Transient.NetT, alpha: Int) {
        val cy = pillRect.top + topZone / 2f
        glyphPaint.color = Look.WHITE; glyphPaint.alpha = alpha
        Glyph.WIFI.draw(canvas, leftSlotStart(), cy, compactSize(14f), glyphPaint)
        // Four columns of dots, taller to the right, lit up to the signal strength.
        val pitch = context.dp(3.2f)
        val right = rightSlotEnd()
        for (col in 0 until 4) for (row in 0..col) {
            dotPaint.color = if (col < n.bars) Look.WHITE else Look.DOT_OFF
            dotPaint.alpha = alpha
            canvas.drawCircle(right - (3 - col) * pitch * 1.3f - pitch / 2f, cy + pitch * 1.5f - row * pitch, context.dp(1.2f), dotPaint)
        }
        val y = pillRect.top + topZone + context.dp(9f)
        titlePaint.alpha = alpha
        val room = pillRect.width() - context.dp(32f)
        val name = fit(n.name, titlePaint, room)
        val w = titlePaint.measureText(name)
        drawText(canvas, name, pillRect.centerX() - w / 2f, y, w + 1f, titlePaint, centerY = true)
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
        val w = peekWeather()
        val steps = peekSteps()
        if (w == null && steps < 0) return
        // Second row, centred under the camera: steps as a filling ring of dots, then the sky and "21°".
        val cy = pillRect.top + topZone + context.dp(9f)
        val gs = context.dp(11f)
        val gap = context.dp(6f)
        val stepText = if (steps >= 0) Steps.text(steps) else null
        val ringW = if (steps >= 0) gs else 0f
        val glyph = w?.let { Weather.glyph(it) }
        val temp = w?.let { Weather.temperature(it) }
        titlePaint.alpha = alpha
        labelPaint.alpha = alpha
        val stepW = stepText?.let { ringW + gap * 0.6f + titlePaint.measureText(it) } ?: 0f
        val tempW = temp?.let { titlePaint.measureText(it) } ?: 0f
        val weatherW = if (w != null) glyph!!.width(gs) + gap * 0.6f + tempW else 0f
        val between = if (stepText != null && w != null) gap * 2.5f else 0f
        // The condition word only when there's room: steps and weather together keep to the numbers.
        val room = pillRect.width() - context.dp(32f) - stepW - weatherW - between - gap
        val labelFit = if (w != null && stepText == null) fit(Weather.label(w), labelPaint, room) else ""
        val labelW = if (labelFit.isNotEmpty()) gap + labelPaint.measureText(labelFit) else 0f
        var x = pillRect.centerX() - (stepW + between + weatherW + labelW) / 2f
        if (stepText != null) {
            drawStepRing(canvas, x + ringW / 2f, cy, ringW / 2f, steps / prefs.stepGoal.coerceAtLeast(1).toFloat(), alpha)
            x += ringW + gap * 0.6f
            drawText(canvas, stepText, x, cy, titlePaint.measureText(stepText) + 1f, titlePaint, centerY = true)
            x += titlePaint.measureText(stepText) + between
        }
        if (w != null) {
            glyphPaint.color = if (glyph == Glyph.SUN) Look.accent else Look.WHITE
            glyphPaint.alpha = alpha
            glyph!!.draw(canvas, x, cy, gs, glyphPaint)
            x += glyph.width(gs) + gap * 0.6f
            drawText(canvas, temp!!, x, cy, tempW + 1f, titlePaint, centerY = true)
            x += tempW
            if (labelFit.isNotEmpty()) drawText(canvas, labelFit, x + gap, cy, room + 1f, labelPaint, centerY = true)
        }
    }

    /** Twelve dots in a circle, lit clockwise toward the step goal; all red once it's reached. */
    private fun drawStepRing(canvas: Canvas, cx: Float, cy: Float, r: Float, fraction: Float, alpha: Int) {
        val n = 12
        val lit = (fraction.coerceIn(0f, 1f) * n).roundToInt()
        for (i in 0 until n) {
            dotPaint.color = when {
                fraction >= 1f -> Look.accent
                i < lit -> Look.WHITE
                else -> Look.DOT_OFF
            }
            dotPaint.alpha = alpha
            val a = -Math.PI / 2 + Math.PI * 2 * i / n
            canvas.drawCircle(cx + (r * kotlin.math.cos(a)).toFloat(), cy + (r * kotlin.math.sin(a)).toFloat(), context.dp(1.1f), dotPaint)
        }
    }

    private fun peekSteps(): Int = if (prefs.showSteps) Steps.today else -1

    private fun peekWeather(): Weather.Now? = if (prefs.showWeather) Weather.now else null

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

    private fun buzz() = Haptics.tick(context)

    companion object {
        private const val SKIP_MS = 10_000L
        private const val CALL_CARD_MS = 6_000L

        /** The volume bar's "stream" when it drives a Cast / Connect device instead of the phone. */
        const val REMOTE_STREAM = -100
        private val TIMER_CHOICES = intArrayOf(1, 5, 10, 25)
        private const val HISTORY_MS = 8_000L
        private const val HISTORY_ROW_DP = 46f
        private val ANSWER_GREEN = 0xFF2BD16B.toInt()
        private const val VOLUME_MS = 1600L
        private const val VOLUME_LINGER_MS = 4000L
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
