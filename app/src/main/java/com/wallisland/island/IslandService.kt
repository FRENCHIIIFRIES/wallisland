package com.wallisland.island

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import kotlin.math.roundToInt
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Owns the overlay window. Runs in the foreground and restarts itself so the island keeps working after
 * reboots, updates, swipes from recents and OEM task killers.
 */
class IslandService : Service(), IslandHub.Listener, IslandView.Host,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager

    /**
     * Where the island's windows live. Normally a window context for app overlays; when the accessibility
     * service is on, the service itself, whose overlays sit above the status bar.
     */
    private lateinit var windowCtx: Context
    private var windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val main = Handler(Looper.getMainLooper())

    private var island: IslandView? = null
    private var islandParams: WindowManager.LayoutParams? = null
    private var tracker: View? = null
    private var trackerWm: WindowManager? = null

    private var fullscreen = false
    private var batteryLevel = -1
    private var charging = false
    private var receiversOn = false
    private var demoRestore: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Look.accent = prefs.accent
        Weather.refresh(this)
        Steps.start(this)
        chooseHost()
        goForeground()
        if (!canHost(this) || !prefs.enabled) {
            stopSelf()
            return
        }
        running = true
        current = this
        attachIsland()
        attachTracker()
        registerReceivers()
        registerCaptureWatchers()
        registerAudioDevices()
        main.postDelayed(calendarPoll, 3000)
        if (prefs.focusEnd > System.currentTimeMillis()) postFocus(prefs.focusEnd) else prefs.focusEnd = 0
        if (prefs.stopwatchStart > 0) postStopwatch(prefs.stopwatchStart)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        IslandHub.listener = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEMO_NOTICE -> island?.showNotice(demoNotice())
            ACTION_DEMO_CHARGE -> island?.showCharging(if (batteryLevel >= 0) batteryLevel else 76, true, plugIn = true)
            ACTION_DEMO_MEDIA -> demoMedia()
            ACTION_DEMO_CALL -> demoCall()
            ACTION_DEMO_TIMER -> demoLive(LiveInfo.Kind.TIMER)
            ACTION_DEMO_NAV -> demoLive(LiveInfo.Kind.NAV)
            ACTION_DEMO_PROGRESS -> demoLive(LiveInfo.Kind.PROGRESS)
            ACTION_DEMO_UNLOCK -> { IslandHub.unlockLog = "Demo"; island?.showUnlock() }
            ACTION_DEMO_BUDS -> island?.showBuds(78, "Nothing Ear")
            ACTION_DEMO_VOLUME -> openVolumeBar()
            ACTION_SAVER_ON -> enableSaver()
            ACTION_DEMO_DND -> island?.showStatus(Glyph.MOON, "ON", true)
            ACTION_DEMO_REPLY -> island?.showNotice(demoReplyNotice())
            ACTION_DEMO_EVENT -> { demoLive(LiveInfo.Kind.EVENT); announceEvent("demo:event", "Standup", System.currentTimeMillis() + 10 * 60_000, null) }
            ACTION_DEMO_PEEK -> island?.showPeek()
            ACTION_PREVIEW -> island?.preview()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        applyPopups(islandShowsNotices = false)
        Steps.stop(this)
        running = false
        if (current === this) current = null
        if (IslandHub.listener === this) IslandHub.listener = null
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        if (receiversOn) {
            unregisterReceiver(receiver)
            receiversOn = false
            unregisterCaptureWatchers()
            getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioDevices)
        }
        main.removeCallbacksAndMessages(null)
        island?.let { it.release(); removeView(it) }
        removeTracker()
        island = null
        if (!status.startsWith("Couldn't")) status = "Not running"
        super.onDestroy()
    }

    /** Swiping the app away from recents shouldn't kill the island. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!prefs.enabled) return
        val pi = PendingIntent.getForegroundService(
            this, 1, Intent(this, IslandService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(AlarmManager::class.java)
            ?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        position()
        updateHidden()
    }

    // ---- Foreground --------------------------------------------------------------------------------------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Island running", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the island alive. You can hide this channel."
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Island is on")
            .setContentText("Tap to customise")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---- Windows -----------------------------------------------------------------------------------------

    private fun chooseHost() {
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            windowCtx = a11y
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            windowCtx = if (Build.VERSION.SDK_INT >= 30) {
                val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                this
            }
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        wm = windowCtx.getSystemService(WindowManager::class.java)
    }

    /** Moves the island between the app-overlay layer and the above-status-bar accessibility layer. */
    private fun reattach() {
        island?.let { it.release(); removeView(it) }
        removeTracker()
        island = null
        if (!canHost(this)) {
            stopSelf()
            return
        }
        chooseHost()
        attachIsland()
        attachTracker()
        // Re-feed the new view with what's going on right now.
        IslandHub.listener = this
    }

    private fun overlayParams(w: Int, h: Int, flags: Int, type: Int = windowType) = WindowManager.LayoutParams(
        w, h,
        type,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun attachIsland() {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val params = overlayParams(dp(200), dp(60), flags).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "Island"
        }
        islandParams = params
        val view = IslandView(windowCtx, prefs, this)
        island = view
        position()
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            island = null
            status = "Couldn't create the island window: ${e.javaClass.simpleName}"
            stopSelf()
            return
        }
        updateHidden()
    }

    /**
     * An invisible 1px column that only exists to hear about status-bar visibility, so the island can get
     * out of the way of full-screen video and games.
     */
    private fun attachTracker() {
        // The tracker must live in the normal app-overlay layer, below the status bar: a window above it
        // (the accessibility layer) is told the status bar is hidden and would hide the island for good.
        fullscreen = false
        if (!Settings.canDrawOverlays(this)) return
        val ctx: Context = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
        val twm = ctx.getSystemService(WindowManager::class.java)
        val v = View(ctx)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = overlayParams(
            1, WindowManager.LayoutParams.MATCH_PARENT, flags, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            alpha = 0f
            title = "IslandTracker"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener { _, insets ->
                setFullscreen(!insets.isVisible(WindowInsets.Type.statusBars()))
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            v.setOnSystemUiVisibilityChangeListener { vis ->
                setFullscreen(vis and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            }
        }
        try {
            twm.addView(v, params)
            tracker = v
            trackerWm = twm
        } catch (_: Exception) {
        }
    }

    private fun removeTracker() {
        val v = tracker ?: return
        try {
            trackerWm?.removeViewImmediate(v)
        } catch (_: Exception) {
        }
        tracker = null
        trackerWm = null
    }

    private fun removeView(v: View) {
        try {
            wm.removeViewImmediate(v)
        } catch (_: Exception) {
        }
    }

    private fun setFullscreen(fs: Boolean) {
        if (fullscreen == fs) return
        fullscreen = fs
        updateHidden()
    }

    private fun updateHidden() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hideFs = prefs.hideFullscreen && fullscreen
        val hideLand = prefs.hideLandscape && landscape
        val hideCap = prefs.hideWhileCapturing && capturing && !inCall()
        island?.setHidden(hideFs || hideLand || hideCap)
        applyPopups(islandShowsNotices = island != null && prefs.enabled && !(hideFs || hideLand || hideCap))
        val layer = if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            "above the status bar"
        } else {
            "below the status bar"
        }
        status = when {
            island == null -> "Couldn't create the island window"
            !prefs.enabled -> "Off"
            hideFs -> "Hidden: a full-screen app is open"
            hideLand -> "Hidden: landscape"
            hideCap -> "Hidden: the camera or a recorder is in use"
            else -> "Showing, $layer"
        }
    }

    /** Centres the pill on the front camera, then applies the user's fine-tuning. */
    private fun position() {
        val params = islandParams ?: return
        val screenW: Int
        var camera: Rect? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            screenW = metrics.bounds.width()
            camera = Camera.fromInsets(metrics.windowInsets, screenW)
        } else {
            screenW = windowCtx.resources.displayMetrics.widthPixels
            if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                camera = Camera.fromCutout(wm.defaultDisplay.cutout, screenW)
            }
        }
        val idleH = dp(prefs.height)
        var cx: Int? = camera?.centerX()
        var cy: Int? = camera?.centerY()
        if (cx == null && prefs.cameraScreenW == screenW && prefs.cameraX >= 0) {
            // Fall back to what the settings screen measured on this same display.
            cx = prefs.cameraX
            cy = prefs.cameraY
        }
        var x = 0
        var y = ((statusBarHeight() - idleH) / 2).coerceAtLeast(dp(4))
        if (prefs.autoAlign && cx != null && cy != null) {
            // Gravity is centre-horizontal, so x is an offset from the middle of the display.
            x = cx - screenW / 2
            y = cy - idleH / 2
        }
        x += dp(prefs.offsetX)
        y = (y + dp(prefs.offsetY)).coerceAtLeast(0)
        params.x = x
        params.y = y
        island?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, params) }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    // ---- IslandView.Host ---------------------------------------------------------------------------------

    override fun onWindowSize(width: Int, height: Int) {
        val p = islandParams ?: return
        if (p.width == width && p.height == height) return
        p.width = width
        p.height = height
        island?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, p) }
    }

    private val fadeWindow = Runnable {
        val p = islandParams ?: return@Runnable
        // A fully transparent window lets touches through on Android 12+ untrusted-touch rules.
        p.alpha = 0f
        island?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, p) }
    }

    override fun onTouchable(touchable: Boolean) {
        val p = islandParams ?: return
        val notTouch = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val was = p.flags and notTouch == 0
        if (was == touchable && (touchable == (p.alpha > 0f))) return
        main.removeCallbacks(fadeWindow)
        if (touchable) {
            p.flags = p.flags and notTouch.inv()
            p.alpha = 1f
        } else {
            p.flags = p.flags or notTouch
            main.postDelayed(fadeWindow, 450)
        }
        island?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, p) }
    }

    override fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** For calls whose notification has no Answer / Hang up button: ask the phone app directly. */
    @SuppressLint("MissingPermission")
    override fun callAction(answer: Boolean) {
        if (Build.VERSION.SDK_INT < 28 ||
            checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Allow \"Phone calls\" in Wallisland's Setup to answer and hang up from the island")
            return
        }
        val tm = getSystemService(android.telecom.TelecomManager::class.java) ?: return
        try {
            if (answer) tm.acceptRingingCall() else @Suppress("DEPRECATION") tm.endCall()
        } catch (_: Exception) {
        }
    }

    // ---- Battery Saver ---------------------------------------------------------------------------------------

    private var saverOffered = false

    /**
     * Once per discharge, at 15%: a card offering Battery Saver in one tap. Reset by charging or climbing
     * back above 20%.
     */
    private fun offerSaver(pct: Int) {
        if (charging || pct > 20) {
            saverOffered = false
            return
        }
        if (saverOffered || pct < 0 || pct > SAVER_AT) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return
        saverOffered = true
        val on = PendingIntent.getService(
            this, 7, Intent(this, IslandService::class.java).setAction(ACTION_SAVER_ON),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        island?.showNotice(
            Notice(
                key = "saver:${SystemClock.uptimeMillis()}", pkg = packageName, appName = "Battery",
                title = "$pct% left", text = "Turn on Battery Saver to make it last longer",
                icon = getDrawable(R.drawable.ic_stat_island), avatar = null, intent = on, autoCancel = false,
                actions = listOf(NoticeAction("Saver on", on, null)), color = Look.RED,
            ),
        )
    }

    /** Battery Saver on: directly with the adb grant (same one as pop-ups), else via its settings screen. */
    private fun enableSaver() {
        if (canReplacePopups(this)) {
            try {
                Settings.Global.putInt(contentResolver, "low_power", 1)
                island?.showStatus(Glyph.BOLT, "SAVER ON", true)
                return
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    /** Settings changed or the adb grant just arrived: apply the pop-up choice now. */
    fun refreshPopups() = updateHidden()

    override fun openShade() {
        val a11y = IslandAccessibilityService.instance
        if (a11y != null && a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return
        try {
            @SuppressLint("WrongConstant")
            val bar = getSystemService("statusbar") ?: return
            bar.javaClass.getMethod("expandNotificationsPanel").invoke(bar)
        } catch (_: Exception) {
        }
    }

    /**
     * With "Island replaces pop-ups" on, the system's own banners are switched off while the island is
     * there to show notifications, and back on whenever it isn't (hidden, off, or stopped).
     */
    private fun applyPopups(islandShowsNotices: Boolean) {
        if (!canReplacePopups(this)) return
        val off = prefs.replacePopups && prefs.showNotifications && islandShowsNotices
        val cr = contentResolver
        val current = Settings.Global.getInt(cr, HEADS_UP, 1)
        try {
            if (off && current != 0) {
                Settings.Global.putInt(cr, HEADS_UP, 0)
                prefs.popupsOffByUs = true
            } else if (!off && prefs.popupsOffByUs) {
                if (current == 0) Settings.Global.putInt(cr, HEADS_UP, 1)
                prefs.popupsOffByUs = false
            }
        } catch (_: SecurityException) {
        }
    }

    // ---- Quick toggles -------------------------------------------------------------------------------------

    private var torchId: String? = null
    private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            island?.refreshQuick()
        }
    }

    private fun findTorch(): String? = try {
        val cm = getSystemService(CameraManager::class.java)
        cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: Exception) {
        null
    }

    private fun autoRotate(): Boolean =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1

    override fun quickState(): IslandView.QuickState {
        val am = getSystemService(AudioManager::class.java)
        return IslandView.QuickState(
            torch = torchOn,
            torchAvailable = torchId != null,
            ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            autoRotate = autoRotate(),
            focusOn = prefs.focusEnd > System.currentTimeMillis(),
            stopwatchOn = prefs.stopwatchStart > 0,
        )
    }

    /** 0..100 on a perceptual curve, so the middle of the bar looks like half brightness. */
    override fun setBrightness(level: Int): Int {
        if (!Settings.System.canWrite(this)) {
            toast("Allow Wallisland to change system settings, then try again")
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return -1
        }
        val raw = (255.0 * Math.pow(level.coerceIn(0, 100) / 100.0, 2.2)).toInt().coerceIn(1, 255)
        try {
            // Dragging takes over from adaptive brightness, as the system slider does.
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        } catch (_: Exception) {
        }
        return level.coerceIn(0, 100)
    }

    private fun brightnessLevel(): Int {
        val raw = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
        return (100 * Math.pow(raw / 255.0, 1 / 2.2)).roundToInt().coerceIn(0, 100)
    }

    override fun startTimer(ms: Long) {
        stopStopwatch(announce = false)
        val end = System.currentTimeMillis() + ms
        prefs.focusEnd = end
        prefs.timerTotal = ms
        postFocus(end)
    }

    override fun startStopwatch() {
        if (prefs.focusEnd > System.currentTimeMillis()) stopFocus()
        prefs.stopwatchStart = System.currentTimeMillis()
        postStopwatch(prefs.stopwatchStart)
    }

    override fun stopTimers() {
        if (prefs.focusEnd > System.currentTimeMillis()) stopFocus()
        stopStopwatch(announce = true)
    }

    private fun postStopwatch(start: Long) {
        IslandHub.putLive(
            LiveInfo(
                key = STOPWATCH_KEY, kind = LiveInfo.Kind.TIMER, pkg = packageName, appName = "Stopwatch", title = "Stopwatch",
                text = "", icon = null, chronoBase = start, countDown = false, staticTime = null, progress = 0,
                progressMax = 0, indeterminate = false, postedAt = System.currentTimeMillis(), intent = null,
            )
        )
    }

    private fun stopStopwatch(announce: Boolean) {
        val start = prefs.stopwatchStart
        if (start <= 0) return
        prefs.stopwatchStart = 0
        IslandHub.removeLive(STOPWATCH_KEY)
        if (announce) {
            val s = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
            island?.showStatus(Glyph.TIMER, "%d:%02d".format(s / 60, s % 60), true)
        }
    }

    override fun batteryLevel(): Int = batteryLevel

    // ---- Volume in the island ----------------------------------------------------------------------------

    /** Handle the volume keys ourselves only when the island can show the result (not while ringing). */
    fun canShowVolume(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        return am.mode != AudioManager.MODE_RINGTONE && island?.canShowTransient() == true
    }

    /** Until when the volume keys skip the system's routing, after it was seen not to move anything. */
    private var directVolumeUntil = 0L
    private var volumeFallbacks = 0

    /**
     * A volume key, routed the way Android routes the hardware keys (media, calls, Cast), then shown on the
     * island. If nothing moved although it could have, the stream is adjusted directly instead (and for a
     * while after), so the keys never go dead.
     */
    fun stepVolume(up: Boolean) {
        val am = getSystemService(AudioManager::class.java) ?: return
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // Casting: the keys drive the speaker or TV the music is playing on.
        val cast = IslandHub.media?.takeIf { it.remote }?.controller
        if (cast != null && !inVoiceCall(am)) {
            try {
                cast.adjustVolume(dir, 0)
            } catch (_: Exception) {
            }
            main.postDelayed({
                val pi = cast.playbackInfo ?: return@postDelayed
                lastVolume = "cast device ${pi.currentVolume}/${pi.maxVolume}"
                island?.showVolume(pi.currentVolume, pi.maxVolume, IslandView.REMOTE_STREAM)
            }, VOLUME_READBACK_MS)
            return
        }
        // An app left the phone in call mode with no call going on: the system would move the call
        // volume, so move what's playing instead.
        if (SystemClock.uptimeMillis() < directVolumeUntil || staleCallMode(am)) {
            adjustDirectly(am, likelyStream(am), dir)
            return
        }
        val before = VOLUME_STREAMS.map { am.getStreamVolume(it) }
        val fallbacks = volumeFallbacks
        val routed = try {
            // No FLAG_SHOW_UI: the island is the volume panel.
            am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
            true
        } catch (_: Exception) {
            false
        }
        // The system applies it a moment later, on its own thread.
        main.postDelayed({
            // Routing was found not to work since this press: this one didn't move anything either.
            if (volumeFallbacks != fallbacks) {
                adjustDirectly(am, likelyStream(am), dir)
                return@postDelayed
            }
            val moved = VOLUME_STREAMS.indices.firstOrNull { am.getStreamVolume(VOLUME_STREAMS[it]) != before[it] }
            val stream = moved?.let { VOLUME_STREAMS[it] } ?: likelyStream(am)
            when {
                moved != null -> {
                    lastVolume = "${streamName(stream)} ${before[moved]} → ${am.getStreamVolume(stream)}"
                    island?.showVolume(am.getStreamVolume(stream), am.getStreamMaxVolume(stream), stream)
                }
                canMove(am, stream, up) -> {
                    volumeFallbacks++
                    directVolumeUntil = SystemClock.uptimeMillis() + DIRECT_VOLUME_MS
                    adjustDirectly(am, stream, dir)
                    lastVolume += if (routed) " (system routing didn't move it)" else " (routing failed)"
                }
                else -> {
                    lastVolume = "${streamName(stream)} already at its ${if (up) "maximum" else "minimum"}"
                    island?.showVolume(am.getStreamVolume(stream), am.getStreamMaxVolume(stream), stream)
                }
            }
        }, VOLUME_READBACK_MS)
    }

    private fun adjustDirectly(am: AudioManager, stream: Int, dir: Int) {
        val was = am.getStreamVolume(stream)
        try {
            am.adjustStreamVolume(stream, dir, 0)
        } catch (_: SecurityException) {
        }
        val now = am.getStreamVolume(stream)
        lastVolume = "${streamName(stream)} $was → $now, set directly"
        island?.showVolume(now, am.getStreamMaxVolume(stream), stream)
    }

    private fun likelyStream(am: AudioManager) =
        if (inVoiceCall(am)) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    /** A call that's really happening: the phone's own, or an app playing call audio right now. */
    private fun inVoiceCall(am: AudioManager): Boolean = when (am.mode) {
        AudioManager.MODE_IN_CALL -> true
        AudioManager.MODE_IN_COMMUNICATION -> IslandHub.call != null || am.activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
        }
        else -> false
    }

    private fun staleCallMode(am: AudioManager) =
        am.mode == AudioManager.MODE_IN_COMMUNICATION && !inVoiceCall(am)

    /** Dragging the island's volume bar. */
    override fun setVolume(stream: Int, level: Int): Int {
        if (stream == IslandView.REMOTE_STREAM) {
            val cast = IslandHub.media?.controller ?: return level
            try {
                cast.setVolumeTo(level, 0)
            } catch (_: Exception) {
            }
            lastVolume = "cast device dragged to $level"
            return level
        }
        val am = getSystemService(AudioManager::class.java) ?: return level
        try {
            am.setStreamVolume(stream, level, 0)
        } catch (_: SecurityException) {
            // Ring and notification can't reach zero without Do Not Disturb access.
        }
        val now = am.getStreamVolume(stream)
        lastVolume = "${streamName(stream)} dragged to $now"
        return now
    }

    /** The long-press panel's Volume button: the bar for whatever is playing, held open to drag. */
    private fun openVolumeBar() {
        IslandHub.media?.takeIf { it.remote }?.controller?.playbackInfo?.let { pi ->
            island?.showVolume(pi.currentVolume, pi.maxVolume, IslandView.REMOTE_STREAM, linger = true)
            return
        }
        val am = getSystemService(AudioManager::class.java) ?: return
        val stream = likelyStream(am)
        island?.showVolume(am.getStreamVolume(stream), am.getStreamMaxVolume(stream), stream, linger = true)
    }

    private fun canMove(am: AudioManager, stream: Int, up: Boolean): Boolean {
        val now = am.getStreamVolume(stream)
        if (up) return now < am.getStreamMaxVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
        return now > min
    }

    private fun streamName(stream: Int) = when (stream) {
        AudioManager.STREAM_VOICE_CALL -> "call"
        AudioManager.STREAM_MUSIC -> "media"
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_ALARM -> "alarm"
        else -> "stream $stream"
    }

    fun openToggles() {
        island?.openTogglesPanel()
    }

    // ---- Focus timer ---------------------------------------------------------------------------------------

    private val focusDone = Runnable { finishFocus() }

    fun toggleFocus() {
        if (prefs.focusEnd > System.currentTimeMillis()) stopFocus() else startFocus()
        island?.refreshQuick()
    }

    private fun startFocus() = startTimer(FOCUS_MS)

    private fun postFocus(end: Long) {
        IslandHub.putLive(
            LiveInfo(
                key = FOCUS_KEY, kind = LiveInfo.Kind.TIMER, pkg = packageName, appName = "Timer", title = "Timer",
                text = "", icon = null, chronoBase = end, countDown = true, staticTime = null, progress = 0,
                // Total seconds, so the island can drain its row of dots.
                progressMax = (prefs.timerTotal.takeIf { it > 0 } ?: FOCUS_MS).div(1000).toInt(),
                indeterminate = false, postedAt = System.currentTimeMillis(), intent = null,
            )
        )
        main.removeCallbacks(focusDone)
        main.postDelayed(focusDone, (end - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun stopFocus() {
        main.removeCallbacks(focusDone)
        prefs.focusEnd = 0
        IslandHub.removeLive(FOCUS_KEY)
        island?.showStatus(Glyph.TIMER, "OFF", false)
    }

    private fun finishFocus() {
        prefs.focusEnd = 0
        IslandHub.removeLive(FOCUS_KEY)
        island?.showStatus(Glyph.CHECK, "DONE", true)
        Haptics.alert(this)
    }

    // ---- Calendar ------------------------------------------------------------------------------------------

    private val announcedEvents = HashSet<String>()

    private val calendarPoll = object : Runnable {
        override fun run() {
            checkCalendar()
            main.postDelayed(this, 60_000)
        }
    }

    /** Events starting in the next 10 minutes become a live countdown, announced once with a banner. */
    @SuppressLint("MissingPermission") // Checked into `allowed` just below.
    private fun checkCalendar() {
        val allowed = prefs.showEvents &&
            checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val keep = HashSet<String>()
        if (allowed) {
            val now = System.currentTimeMillis()
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, now - EVENT_AFTER_MS)
                android.content.ContentUris.appendId(it, now + EVENT_BEFORE_MS + 60_000)
            }.build()
            val cols = arrayOf(
                android.provider.CalendarContract.Instances.EVENT_ID,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.TITLE,
            )
            try {
                contentResolver.query(
                    uri, cols,
                    "${android.provider.CalendarContract.Instances.ALL_DAY} = 0 AND " +
                        "${android.provider.CalendarContract.Instances.VISIBLE} = 1",
                    null, "${android.provider.CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val begin = c.getLong(1)
                        if (begin < now - EVENT_AFTER_MS || begin > now + EVENT_BEFORE_MS) continue
                        val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "Event"
                        val key = "event:$id:$begin"
                        keep += key
                        val open = PendingIntent.getActivity(
                            this, id.toInt(),
                            Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(
                                android.provider.CalendarContract.Events.CONTENT_URI, id,
                            )),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        IslandHub.putLive(
                            LiveInfo(
                                key = key, kind = LiveInfo.Kind.EVENT, pkg = "calendar", appName = "Calendar",
                                title = title, text = "", icon = null, chronoBase = begin, countDown = true,
                                staticTime = null, progress = 0, progressMax = 0, indeterminate = false,
                                postedAt = begin, intent = open,
                            )
                        )
                        if (announcedEvents.add(key)) announceEvent(key, title, begin, open)
                    }
                }
            } catch (_: Exception) {
            }
        }
        for (l in IslandHub.currentLive()) {
            if (l.kind == LiveInfo.Kind.EVENT && l.key !in keep) IslandHub.removeLive(l.key)
        }
    }

    private fun announceEvent(key: String, title: String, begin: Long, open: PendingIntent?) {
        val mins = Math.ceil((begin - System.currentTimeMillis()) / 60_000.0).toInt()
        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(begin))
        island?.showNotice(
            Notice(
                key = key, pkg = packageName, appName = "Calendar", title = title,
                text = if (mins > 0) "In $mins min · $at" else "Now · $at",
                icon = getDrawable(R.drawable.ic_calendar), avatar = null, intent = open, autoCancel = false,
            )
        )
    }

    override fun quickAction(action: IslandView.Quick) {
        when (action) {
            IslandView.Quick.TORCH -> torchId?.let { id ->
                try {
                    getSystemService(CameraManager::class.java).setTorchMode(id, !torchOn)
                } catch (_: Exception) {
                    toast("The torch is busy")
                }
            }
            IslandView.Quick.RINGER -> {
                val am = getSystemService(AudioManager::class.java) ?: return
                val next = when (am.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                try {
                    am.ringerMode = next
                } catch (_: SecurityException) {
                    // Silent needs Do Not Disturb access; skip straight back to ring without it.
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    } catch (_: SecurityException) {
                    }
                }
            }
            IslandView.Quick.ROTATE -> {
                if (!Settings.System.canWrite(this)) {
                    toast("Allow Wallisland to change system settings, then try again")
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                }
                Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (autoRotate()) 0 else 1)
            }
            IslandView.Quick.VOLUME -> openVolumeBar()
            IslandView.Quick.BRIGHTNESS -> if (Settings.System.canWrite(this)) {
                island?.showBrightness(brightnessLevel())
            } else {
                setBrightness(0)
            }
            IslandView.Quick.TIMER -> Unit // the island opens its timer panel itself
            IslandView.Quick.RECENT -> Unit // handled by the island itself
        }
        island?.refreshQuick()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- Unlock ---------------------------------------------------------------------------------------------

    private var wasLocked = false
    private var lastUnlockAt = 0L
    private var lockWatchStart = 0L

    /**
     * Backup for phones where the "user present" broadcast is late or missing: after the screen turns
     * on, watch the lock state and treat locked -> unlocked as an unlock.
     */
    private val lockWatch: Runnable = object : Runnable {
        override fun run() {
            val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
            if (wasLocked && !locked) onUnlocked("lock-state watch")
            wasLocked = locked
            if (locked && SystemClock.uptimeMillis() - lockWatchStart < 60_000) main.postDelayed(this, 150)
        }
    }

    private fun startLockWatch() {
        main.removeCallbacks(lockWatch)
        wasLocked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        lockWatchStart = SystemClock.uptimeMillis()
        if (wasLocked) main.postDelayed(lockWatch, 150)
    }

    private fun onUnlocked(source: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastUnlockAt < 2000) return // Both detectors fire; play it once.
        lastUnlockAt = now
        wasLocked = false
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        IslandHub.unlockLog = "Last unlock $time (via $source)"
        // Face unlock has let go of the camera now; re-check before deciding whether to hide.
        recheckCapture()
        val view = island
        if (view == null) IslandHub.unlockLog += ": skipped, island window not created" else view.showUnlock()
    }

    // ---- Earbuds ---------------------------------------------------------------------------------------------

    private var budsShownAt = 0L

    @SuppressLint("MissingPermission")
    private fun onBluetooth(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            ?: return
        val audio = try {
            device.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (_: Exception) {
            false
        }
        if (!audio) return
        when (intent.action) {
            android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // The audio-device callback shows the pop-up; if it already did, add the battery.
                val level = budsBattery(device)
                if (level >= 0 && SystemClock.uptimeMillis() - budsShownAt < 6000) island?.showBuds(level, null)
            }
            ACTION_BT_BATTERY -> {
                // Earbuds report battery a moment after connecting; update the pop-up if it's still fresh.
                val level = intent.getIntExtra(EXTRA_BT_BATTERY, -1)
                if (level >= 0 && SystemClock.uptimeMillis() - budsShownAt < 6000) island?.showBuds(level, null)
            }
        }
    }

    /**
     * Headphones via the audio system: works without any Bluetooth permission. Battery still needs
     * "Nearby devices"; without it the pop-up shows the headphones' name instead.
     */
    private var audioReady = false

    private val audioDevices = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
            // The first callback lists devices already connected when we registered; skip those.
            if (!audioReady) return
            val d = added.firstOrNull { it.isSink && it.type in BT_AUDIO_TYPES } ?: return
            val name = d.productName?.toString()?.trim().orEmpty()
            val battery = if (Build.VERSION.SDK_INT >= 28) budsBatteryFor(d.address) else -1
            IslandHub.budsLog = "Last: ${name.ifEmpty { "Bluetooth audio" }}, battery " +
                (if (battery >= 0) "$battery%" else "unknown" + if (!hasBtPermission()) " (Nearby devices not allowed)" else "")
            budsShownAt = SystemClock.uptimeMillis()
            island?.showBuds(battery, name)
        }
    }

    private fun registerAudioDevices() {
        val am = getSystemService(AudioManager::class.java) ?: return
        audioReady = false
        am.registerAudioDeviceCallback(audioDevices, main)
        main.postDelayed({ audioReady = true }, 1500)
    }

    private fun hasBtPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun budsBatteryFor(address: String?): Int {
        if (address.isNullOrEmpty() || !hasBtPermission()) return -1
        return try {
            val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return -1
            budsBattery(adapter.getRemoteDevice(address))
        } catch (_: Exception) {
            -1
        }
    }

    /** Battery level of a connected Bluetooth device, when the system knows it (hidden API, best effort). */
    private fun budsBattery(device: android.bluetooth.BluetoothDevice): Int = try {
        device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
    } catch (_: Throwable) {
        -1
    }

    // ---- IslandHub.Listener ------------------------------------------------------------------------------

    override fun onNotice(notice: Notice) {
        island?.showNotice(notice)
    }

    override fun onNoticeRemoved(key: String) {
        island?.removeNotice(key)
    }

    override fun onLive(live: List<LiveInfo>) {
        if (demoLiveRestore != null) return
        island?.setLive(live)
    }

    override fun onCall(call: CallInfo?) {
        island?.setCall(call)
        updateHidden()
    }

    // ---- Camera / recorder detection -----------------------------------------------------------------------

    /** Camera IDs another app currently has open. */
    private val camerasInUse = HashSet<String>()
    private var recordingsInUse = emptyList<AudioRecordingConfiguration>()
    private var capturing = false

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            camerasInUse += cameraId
            recheckCapture()
        }

        override fun onCameraAvailable(cameraId: String) {
            camerasInUse -= cameraId
            recheckCapture()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            recordingsInUse = configs.orEmpty().toList()
            recheckCapture()
        }
    }

    private val applyCapture = Runnable {
        capturing = true
        updateHidden()
    }

    // ---- Wi-Fi and hotspot ----------------------------------------------------------------------------------

    private var announcedWifi: android.net.Network? = null

    /** Joining a Wi-Fi network: its name (with location permission) and signal, once per connection. */
    private val wifiCallback: android.net.ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) {
            object : android.net.ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = onWifi(network, caps)
                override fun onLost(network: android.net.Network) { if (announcedWifi == network) announcedWifi = null }
            }
        } else {
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = onWifi(network, caps)
                override fun onLost(network: android.net.Network) { if (announcedWifi == network) announcedWifi = null }
            }
        }

    private fun onWifi(network: android.net.Network, caps: android.net.NetworkCapabilities) {
        if (announcedWifi == network || !prefs.showToggleChanges) return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        announcedWifi = network
        val info = if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? android.net.wifi.WifiInfo else null
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Wi-Fi"
        @Suppress("DEPRECATION")
        val bars = info?.rssi?.let { android.net.wifi.WifiManager.calculateSignalLevel(it, 5) } ?: 3
        main.post { island?.showNet(ssid, bars) }
    }

    /** Hotspot: on/off from the system broadcast, then data used since it came on, as a live activity. */
    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("wifi_state", -1)) {
                13 -> startHotspotWatch()
                11 -> stopHotspotWatch()
            }
        }
    }

    private var hotspotBase = -1L

    private val hotspotTick = object : Runnable {
        override fun run() {
            if (hotspotBase < 0) return
            val used = (android.net.TrafficStats.getMobileRxBytes() + android.net.TrafficStats.getMobileTxBytes() - hotspotBase)
                .coerceAtLeast(0)
            val tether = PendingIntent.getActivity(
                this@IslandService, 8, Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            IslandHub.putLive(
                LiveInfo(
                    key = HOTSPOT_KEY, kind = LiveInfo.Kind.HOTSPOT, pkg = packageName, appName = "Hotspot",
                    title = bytesShort(used), text = "", icon = null, chronoBase = 0L, countDown = false, staticTime = null,
                    progress = 0, progressMax = 0, indeterminate = false, postedAt = System.currentTimeMillis(), intent = tether,
                )
            )
            main.postDelayed(this, 10_000)
        }
    }

    private fun startHotspotWatch() {
        if (hotspotBase >= 0) return
        hotspotBase = android.net.TrafficStats.getMobileRxBytes() + android.net.TrafficStats.getMobileTxBytes()
        if (prefs.showToggleChanges) island?.showStatus(Glyph.HOTSPOT, "ON", true)
        hotspotTick.run()
    }

    private fun stopHotspotWatch() {
        if (hotspotBase < 0) return
        hotspotBase = -1L
        main.removeCallbacks(hotspotTick)
        IslandHub.removeLive(HOTSPOT_KEY)
        if (prefs.showToggleChanges) island?.showStatus(Glyph.HOTSPOT, "OFF", false)
    }

    private fun bytesShort(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "${b shr 20} MB"
        else -> "${b shr 10} KB"
    }

    private fun registerNetWatchers() {
        try {
            val req = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
            getSystemService(android.net.ConnectivityManager::class.java)?.registerNetworkCallback(req, wifiCallback)
        } catch (_: Exception) {
        }
        val f = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(hotspotReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(hotspotReceiver, f)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetWatchers() {
        try {
            getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiCallback)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(hotspotReceiver)
        } catch (_: Exception) {
        }
        main.removeCallbacks(hotspotTick)
    }

    private fun registerCaptureWatchers() {
        registerNetWatchers()
        try {
            getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(cameraCallback, main)
        } catch (_: Exception) {
        }
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            am.registerAudioRecordingCallback(recordingCallback, main)
            recordingsInUse = am.activeRecordingConfigurations
        } catch (_: Exception) {
        }
    }

    private fun unregisterCaptureWatchers() {
        unregisterNetWatchers()
        try {
            getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(cameraCallback)
            getSystemService(CameraManager::class.java)?.unregisterTorchCallback(torchCallback)
            getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {
        }
    }

    /**
     * Camera open, or the mic recording as a camcorder/voice recorder would. Hiding waits a moment so
     * a quick face-unlock blink doesn't flash the island away; showing again is immediate.
     */
    private fun recheckCapture() {
        val recorder = recordingsInUse.any {
            it.clientAudioSource == MediaRecorder.AudioSource.MIC ||
                it.clientAudioSource == MediaRecorder.AudioSource.CAMCORDER ||
                it.clientAudioSource == MediaRecorder.AudioSource.UNPROCESSED
        }
        // The torch holds the back camera too; that isn't "recording". Nor is face unlock, which uses
        // the front camera while the phone is still locked.
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        val cams = when {
            locked -> emptySet()
            torchOn -> camerasInUse - setOfNotNull(torchId)
            else -> camerasInUse
        }
        val now = cams.isNotEmpty() || recorder
        // Privacy dot: any recording at all counts for the mic; the app is whichever is on screen.
        val app = IslandAccessibilityService.foreground?.let { IslandNotificationListener.appLabel(this, it) }
        island?.setPrivacy(mic = recordingsInUse.isNotEmpty(), cam = cams.isNotEmpty(), app = app)
        main.removeCallbacks(applyCapture)
        if (now) {
            if (!capturing) main.postDelayed(applyCapture, 800)
        } else if (capturing) {
            capturing = false
            updateHidden()
        }
    }

    /** Video and voice calls keep the island: a call notification, call audio mode, or a call-style mic. */
    private fun inCall(): Boolean {
        if (IslandHub.call != null) return true
        if (recordingsInUse.any { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION }) return true
        val mode = getSystemService(AudioManager::class.java)?.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    override fun onMedia(media: MediaInfo?) {
        if (demoRestore != null) return
        island?.setMedia(media)
    }

    // ---- System events -----------------------------------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    island?.setScreenOn(true)
                    Steps.start(this@IslandService)
                    // Have the weather ready before the double-tap asks for it.
                    Weather.refresh(this@IslandService)
                    startLockWatch()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    island?.setScreenOn(false)
                    main.removeCallbacks(lockWatch)
                    wasLocked = true
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    charging = true
                    // Give the battery broadcast a beat to report the fresh level.
                    main.postDelayed({ island?.showCharging(batteryLevel.coerceAtLeast(0), true, chargeTimeMs(), plugIn = true) }, 250)
                    // The estimate often isn't ready at plug-in; try once more.
                    main.postDelayed({
                        val t = chargeTimeMs()
                        if (t > 0) island?.showCharging(batteryLevel.coerceAtLeast(0), true, t)
                    }, 2500)
                }
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val pct = if (level >= 0) level * 100 / scale else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    val prev = batteryLevel
                    batteryLevel = pct
                    if (!isInitialStickyBroadcast && !charging && prev > pct && (pct == 20 || pct == 10 || pct == 5)) {
                        island?.showCharging(pct, false)
                    }
                    offerSaver(pct)
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (isInitialStickyBroadcast) return
                    island?.showRinger(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL))
                }
                Intent.ACTION_USER_PRESENT -> onUnlocked("system signal")
                android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> if (prefs.showToggleChanges) {
                    val f = getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
                    val on = f != null && f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                        f != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    island?.showStatus(Glyph.MOON, if (on) "ON" else "OFF", on)
                }
                android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION -> if (prefs.showToggleChanges && !isInitialStickyBroadcast) {
                    when (intent.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, -1)) {
                        android.net.wifi.WifiManager.WIFI_STATE_ENABLED -> island?.showStatus(Glyph.WIFI, "ON", true)
                        android.net.wifi.WifiManager.WIFI_STATE_DISABLED -> island?.showStatus(Glyph.WIFI, "OFF", false)
                    }
                }
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> if (prefs.showToggleChanges && !isInitialStickyBroadcast) {
                    when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                        android.bluetooth.BluetoothAdapter.STATE_ON -> island?.showStatus(Glyph.BLUETOOTH, "ON", true)
                        android.bluetooth.BluetoothAdapter.STATE_OFF -> island?.showStatus(Glyph.BLUETOOTH, "OFF", false)
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED, ACTION_BT_BATTERY -> onBluetooth(intent)
            }
        }
    }

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(ACTION_BT_BATTERY)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        torchId = findTorch()
        try {
            getSystemService(CameraManager::class.java)?.registerTorchCallback(torchCallback, main)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        receiversOn = true
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        Look.accent = prefs.accent
        if (key == Prefs.KEY_ENABLED && !prefs.enabled) {
            stopSelf()
            return
        }
        position()
        updateHidden()
        island?.applyPrefs()
    }

    // ---- Demos (from the settings screen) ----------------------------------------------------------------

    private fun demoNotice() = Notice(
        key = "demo:${SystemClock.uptimeMillis()}",
        pkg = packageName,
        appName = "Wallisland",
        title = "Hello from the island",
        text = "Swipe up to dismiss, or tap to close.",
        icon = getDrawable(R.drawable.ic_tile),
        avatar = null,
        intent = null,
        autoCancel = false,
    )

    /** A notification with a Reply and a button, to preview quick replies and actions. */
    private fun demoReplyNotice(): Notice {
        val pi = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val input = android.app.RemoteInput.Builder("reply").setLabel("Reply").build()
        return Notice(
            key = "demo:reply:${SystemClock.uptimeMillis()}", pkg = packageName, appName = "Messages",
            title = "Maya", text = "are we still on for tonight?", icon = getDrawable(R.drawable.ic_tile),
            avatar = null, intent = null, autoCancel = false,
            actions = listOf(NoticeAction("Reply", pi, input), NoticeAction("Mark as read", pi, null)),
            color = 0xFF25D366.toInt(),
        )
    }

    private fun chargeTimeMs(): Long = if (Build.VERSION.SDK_INT >= 28) {
        try {
            getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L
        } catch (_: Exception) {
            -1L
        }
    } else {
        -1L
    }

    private var demoLiveRestore: Runnable? = null

    private fun demoLive(kind: LiveInfo.Kind) {
        demoLiveRestore?.let { main.removeCallbacks(it) }
        val now = System.currentTimeMillis()
        val demo = LiveInfo(
            key = "demo:live", kind = kind, pkg = packageName, appName = "Demo",
            title = when (kind) {
                LiveInfo.Kind.NAV -> "200 m"
                LiveInfo.Kind.TIMER -> "Timer"
                LiveInfo.Kind.PROGRESS -> "Downloading"
                LiveInfo.Kind.EVENT -> "Standup"
                LiveInfo.Kind.DELIVERY -> "8 MIN"
                LiveInfo.Kind.HOTSPOT -> "1.2 GB"
            },
            text = "", icon = null,
            chronoBase = when (kind) {
                LiveInfo.Kind.TIMER -> now + 5 * 60_000
                LiveInfo.Kind.EVENT -> now + 10 * 60_000
                else -> 0L
            },
            countDown = true,
            staticTime = null, progress = 43, progressMax = if (kind == LiveInfo.Kind.TIMER) 600 else 100, indeterminate = false,
            postedAt = now, intent = null,
        )
        island?.setLive(listOf(demo))
        val restore = Runnable {
            demoLiveRestore = null
            island?.setLive(IslandHub.currentLive())
        }
        demoLiveRestore = restore
        main.postDelayed(restore, 12_000)
    }

    private fun demoCall() {
        val demo = CallInfo(
            key = "demo:call", pkg = packageName, appName = "Demo", name = "Demo call",
            startedAt = System.currentTimeMillis() - 83_000, intent = null,
        )
        island?.setCall(demo)
        main.postDelayed({ island?.setCall(IslandHub.call) }, 12_000)
    }

    private fun demoMedia() {
        demoRestore?.let { main.removeCallbacks(it) }
        val start = SystemClock.elapsedRealtime()
        island?.setMedia(
            MediaInfo(
                pkg = packageName, appName = "Demo player", title = "Glyph Interface", artist = "Dot Matrix",
                art = null, playing = true, durationMs = 214_000, positionMs = 61_000, positionAt = start,
                speed = 1f, controller = null,
            )
        )
        val restore = Runnable {
            demoRestore = null
            island?.setMedia(IslandHub.media)
        }
        demoRestore = restore
        main.postDelayed(restore, 15_000)
    }

    companion object {
        private const val CHANNEL = "island"
        private const val NOTIF_ID = 7
        const val ACTION_DEMO_NOTICE = "com.wallisland.island.DEMO_NOTICE"
        const val ACTION_DEMO_CHARGE = "com.wallisland.island.DEMO_CHARGE"
        const val ACTION_DEMO_MEDIA = "com.wallisland.island.DEMO_MEDIA"
        const val ACTION_DEMO_CALL = "com.wallisland.island.DEMO_CALL"
        const val ACTION_DEMO_TIMER = "com.wallisland.island.DEMO_TIMER"
        const val ACTION_DEMO_NAV = "com.wallisland.island.DEMO_NAV"
        const val ACTION_DEMO_PROGRESS = "com.wallisland.island.DEMO_PROGRESS"
        const val ACTION_DEMO_UNLOCK = "com.wallisland.island.DEMO_UNLOCK"
        const val ACTION_DEMO_BUDS = "com.wallisland.island.DEMO_BUDS"
        const val ACTION_DEMO_VOLUME = "com.wallisland.island.DEMO_VOLUME"
        const val ACTION_DEMO_DND = "com.wallisland.island.DEMO_DND"
        const val ACTION_DEMO_REPLY = "com.wallisland.island.DEMO_REPLY"
        const val ACTION_DEMO_EVENT = "com.wallisland.island.DEMO_EVENT"
        const val ACTION_DEMO_PEEK = "com.wallisland.island.DEMO_PEEK"

        /** Streams the volume keys can end up moving, checked in this order. */
        private val VOLUME_STREAMS = intArrayOf(
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM,
        )
        private const val VOLUME_READBACK_MS = 150L
        private const val DIRECT_VOLUME_MS = 30_000L

        /** What the last volume key press did, for Troubleshoot. */
        @Volatile var lastVolume = "no volume key handled yet"
            private set

        private const val FOCUS_KEY = "focus"
        private const val STOPWATCH_KEY = "stopwatch"
        private const val HOTSPOT_KEY = "hotspot"
        const val ACTION_SAVER_ON = "com.wallisland.island.SAVER_ON"
        private const val SAVER_AT = 15
        private const val FOCUS_MS = 25 * 60_000L
        private const val EVENT_BEFORE_MS = 10 * 60_000L
        private const val EVENT_AFTER_MS = 5 * 60_000L

        /** Hidden-API broadcast the Bluetooth stack sends when a device reports its battery. */
        private val BT_AUDIO_TYPES = buildSet {
            add(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= 31) {
                add(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }

        private const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val ACTION_PREVIEW = "com.wallisland.island.PREVIEW"

        @Volatile var running = false
            private set

        private const val HEADS_UP = "heads_up_notifications_enabled"

        /** Granted once with adb; lets the island switch the system's pop-up banners off and on. */
        fun canReplacePopups(ctx: Context) =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** Whether the system shows its own pop-up banners right now. */
        fun systemPopupsOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, HEADS_UP, 1) != 0

        @Volatile var current: IslandService? = null
            private set

        /** A one-line description of what the island is doing, for the settings screen. */
        @Volatile var status = "Not running"
            private set

        /** The island can be drawn either as an app overlay or through the accessibility service. */
        fun canHost(ctx: Context) = Settings.canDrawOverlays(ctx) || IslandAccessibilityService.instance != null

        /** Called when the accessibility service connects or goes away. */
        fun onHostChanged(ctx: Context) {
            val svc = current
            if (svc != null) svc.reattach() else start(ctx)
        }

        fun start(ctx: Context, action: String? = null) {
            if (!Prefs(ctx).enabled || !canHost(ctx)) return
            val i = Intent(ctx, IslandService::class.java).setAction(action)
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
                // Background-start restrictions; the notification listener or next boot will bring us back.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, IslandService::class.java))
        }
    }
}
