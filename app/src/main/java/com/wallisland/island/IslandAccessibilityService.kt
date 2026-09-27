package com.wallisland.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Lends the island the one window type an app may place above the status bar, and (when turned on)
 * handles the volume keys and a remapped Essential Key. It reads no screen content.
 */
class IslandAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        IslandService.onHostChanged(this)
    }

    // ---- Keeping Essential Space closed -----------------------------------------------------------------

    /** When the Essential Key was last pressed or released, and until when opening apps are noted (Learn). */
    private var essentialPressedAt = 0L
    private var learnAppUntil = 0L
    private var learnedApp: String? = null
    private var closedThisPress = 0

    /**
     * Nothing OS opens Essential Space itself before any app sees the key. When the key is remapped, close
     * it the moment its window appears (only within a couple of seconds of a press, so opening Essential
     * Space any other way still works). Only the package name is read, never window content.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg != "com.android.systemui" && pkg != "android") foreground = pkg
        val now = SystemClock.uptimeMillis()

        if (now < learnAppUntil) noteLearnedApp(pkg)

        if (!prefs.closeEssentialSpace || !remapped()) return
        if (now - essentialPressedAt > CLOSE_WINDOW_MS || closedThisPress >= 3) return
        if (!isEssentialSpace(pkg)) return
        closedThisPress++
        performGlobalAction(GLOBAL_ACTION_BACK)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())
        lastClosed = "closed $pkg at $time"
    }

    /** The first app that opens after Learn, unless a later one is plainly Essential Space. */
    private fun noteLearnedApp(pkg: String) {
        if (!learnable(pkg)) return
        val current = learnedApp
        if (current != null && (isEssentialName(current) || !isEssentialName(pkg))) return
        learnedApp = pkg
        prefs.essentialSpacePkg = pkg
        main.post { onLearnedApp?.invoke(pkg) }
    }

    /** The home screen and the system UI open around a key press too; they're never Essential Space. */
    private fun learnable(pkg: String): Boolean {
        if (pkg == "android" || pkg == "com.android.systemui" || "launcher" in pkg.lowercase()) return false
        val home = try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
        return pkg != home
    }

    private fun remapped() =
        KeyAction.of(prefs.essentialShort) != KeyAction.DEFAULT || KeyAction.of(prefs.essentialLong) != KeyAction.DEFAULT

    private fun isEssentialSpace(pkg: String): Boolean {
        val learned = prefs.essentialSpacePkg
        return (learned.isNotEmpty() && pkg == learned) || isEssentialName(pkg)
    }

    private fun isEssentialName(pkg: String) = pkg in ESSENTIAL_APPS || "essential" in pkg.lowercase()

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance !== this) return
        instance = null
        IslandService.onHostChanged(applicationContext)
    }

    // ---- Keys --------------------------------------------------------------------------------------------

    private var volumeHandled = false
    private var essentialDown = false
    private var longFired = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastKey = "${KeyEvent.keyCodeToString(code)} ($code), scan code ${event.scanCode}"
        }

        // "Learn key": the next key that isn't a standard button becomes the Essential Key.
        if (learning && code !in SYSTEM_KEYS) {
            // Whatever opens around this press is the app the key launches (Essential Space).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) learnedApp = null
            learnAppUntil = SystemClock.uptimeMillis() + CLOSE_WINDOW_MS
            if (event.action == KeyEvent.ACTION_UP) {
                learning = false
                prefs.essentialKey = code
                prefs.essentialScan = event.scanCode
                main.post { onLearned?.invoke(code) }
            }
            return true
        }

        if (isEssential(event)) {
            if (event.repeatCount == 0) {
                if (event.action == KeyEvent.ACTION_DOWN) closedThisPress = 0
                // Timed from release too, so a long hold still gets Essential Space closed.
                essentialPressedAt = SystemClock.uptimeMillis()
            }
            return essential(event)
        }

        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return volume(event)
        return false
    }

    /**
     * The learned key: by key code when it has a real one, otherwise (KEYCODE_UNKNOWN, as Nothing's
     * Essential Key reports) by its hardware scan code.
     */
    private fun isEssential(e: KeyEvent): Boolean {
        val code = prefs.essentialKey
        if (code > 0) return e.keyCode == code
        val scan = prefs.essentialScan
        return code == KeyEvent.KEYCODE_UNKNOWN && scan > 0 &&
            e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode == scan
    }

    /** Volume keys drive the island's own volume bar instead of the system panel, when it's showing. */
    private fun volume(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                volumeHandled = prefs.volumeInIsland && IslandService.current?.canShowVolume() == true
            }
            if (!volumeHandled) return false
            IslandService.current?.stepVolume(up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return volumeHandled
    }

    private val longPress = Runnable {
        longFired = true
        perform(KeyAction.of(prefs.essentialLong), prefs.essentialLongApp)
    }

    private fun essential(event: KeyEvent): Boolean {
        val short = KeyAction.of(prefs.essentialShort)
        val long = KeyAction.of(prefs.essentialLong)
        // Nothing assigned: leave the key alone so Essential Space keeps working.
        if (short == KeyAction.DEFAULT && long == KeyAction.DEFAULT) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                essentialDown = true
                longFired = false
                if (long != KeyAction.DEFAULT) main.postDelayed(longPress, LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (essentialDown && !longFired) perform(short, prefs.essentialShortApp)
                essentialDown = false
            }
        }
        return true
    }

    fun perform(action: KeyAction, app: String) {
        val svc = IslandService.current
        if (action != KeyAction.DEFAULT) Haptics.tick(this)
        try {
            when (action) {
                KeyAction.DEFAULT -> Unit
                KeyAction.TORCH -> svc?.quickAction(IslandView.Quick.TORCH)
                KeyAction.RINGER -> svc?.quickAction(IslandView.Quick.RINGER)
                KeyAction.TOGGLES -> svc?.openToggles()
                KeyAction.FOCUS -> svc?.toggleFocus()
                KeyAction.PLAY_PAUSE -> getSystemService(AudioManager::class.java)?.let { am ->
                    val t = SystemClock.uptimeMillis()
                    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
                    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
                }
                KeyAction.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                KeyAction.CAMERA -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                KeyAction.ASSISTANT -> launch(Intent(Intent.ACTION_VOICE_COMMAND))
                KeyAction.APP -> packageManager.getLaunchIntentForPackage(app)?.let { launch(it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun launch(i: Intent) {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val LONG_PRESS_MS = 500L

        /** How long after a key press a newly opened Essential Space window is closed. */
        private const val CLOSE_WINDOW_MS = 2500L

        /** Nothing OS's Essential Space and its voice recorder. */
        val ESSENTIAL_APPS = setOf("com.nothing.ntessentialspace", "com.nothing.ntessentialrecorder")

        /** Keys never taken as the Essential Key while learning. */
        private val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU,
        )

        @Volatile var instance: IslandAccessibilityService? = null
            private set

        /** Set by settings while waiting for the user to press the Essential Key. */
        @Volatile var learning = false
        var onLearned: ((Int) -> Unit)? = null
        var onLearnedApp: ((String) -> Unit)? = null

        /** The app whose window last came to the front (package name only), for the mic/camera dot. */
        @Volatile var foreground: String? = null

        /** What the Essential Space blocker last did, for Troubleshoot. */
        @Volatile var lastClosed = "nothing closed yet"

        /** The last key the service saw, for Troubleshoot. */
        @Volatile var lastKey = "none yet"

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, IslandAccessibilityService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
