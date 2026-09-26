package com.wallisland.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Exists only so the island can use accessibility overlays, the one window type an app may place above
 * the status bar. It reads no screen content and handles no events.
 */
class IslandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
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

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, IslandAccessibilityService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
