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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
            if (event.action == KeyEvent.ACTION_UP) {
                learning = false
                prefs.essentialKey = code
                prefs.essentialScan = event.scanCode
                main.post { onLearned?.invoke(code) }
            }
            return true
        }

        if (isEssential(event)) return essential(event)

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
