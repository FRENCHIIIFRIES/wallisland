package com.wallisland.island

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.AudioManager
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

    /** A window context, so window metrics and cut-out insets describe the real display. */
    private lateinit var windowCtx: Context
    private val main = Handler(Looper.getMainLooper())

    private var island: IslandView? = null
    private var islandParams: WindowManager.LayoutParams? = null
    private var tracker: View? = null

    private var fullscreen = false
    private var batteryLevel = -1
    private var charging = false
    private var receiversOn = false
    private var demoRestore: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        windowCtx = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
        wm = windowCtx.getSystemService(WindowManager::class.java)
        goForeground()
        if (!Settings.canDrawOverlays(this) || !prefs.enabled) {
            stopSelf()
            return
        }
        running = true
        attachIsland()
        attachTracker()
        registerReceivers()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        IslandHub.listener = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEMO_NOTICE -> island?.showNotice(demoNotice())
            ACTION_DEMO_CHARGE -> island?.showCharging(if (batteryLevel >= 0) batteryLevel else 76, true)
            ACTION_DEMO_MEDIA -> demoMedia()
            ACTION_DEMO_CALL -> demoCall()
            ACTION_PREVIEW -> island?.preview()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (IslandHub.listener === this) IslandHub.listener = null
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        if (receiversOn) {
            unregisterReceiver(receiver)
            receiversOn = false
        }
        main.removeCallbacksAndMessages(null)
        island?.let { it.release(); removeView(it) }
        tracker?.let { removeView(it) }
        island = null
        tracker = null
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

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        val v = View(windowCtx)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = overlayParams(1, WindowManager.LayoutParams.MATCH_PARENT, flags).apply {
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
            wm.addView(v, params)
            tracker = v
        } catch (_: Exception) {
        }
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
        island?.setHidden((prefs.hideFullscreen && fullscreen) || (prefs.hideLandscape && landscape))
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

    // ---- IslandHub.Listener ------------------------------------------------------------------------------

    override fun onNotice(notice: Notice) {
        island?.showNotice(notice)
    }

    override fun onNoticeRemoved(key: String) {
        island?.removeNotice(key)
    }

    override fun onCall(call: CallInfo?) {
        island?.setCall(call)
    }

    override fun onMedia(media: MediaInfo?) {
        if (demoRestore != null) return
        island?.setMedia(media)
    }

    // ---- System events -----------------------------------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> island?.setScreenOn(true)
                Intent.ACTION_SCREEN_OFF -> island?.setScreenOn(false)
                Intent.ACTION_POWER_CONNECTED -> {
                    charging = true
                    // Give the battery broadcast a beat to report the fresh level.
                    main.postDelayed({ island?.showCharging(batteryLevel.coerceAtLeast(0), true) }, 250)
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
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (isInitialStickyBroadcast) return
                    island?.showRinger(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL))
                }
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
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        receiversOn = true
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
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
        const val ACTION_PREVIEW = "com.wallisland.island.PREVIEW"

        @Volatile var running = false
            private set

        fun start(ctx: Context, action: String? = null) {
            if (!Prefs(ctx).enabled || !Settings.canDrawOverlays(ctx)) return
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
