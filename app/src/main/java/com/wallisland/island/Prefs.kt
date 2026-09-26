package com.wallisland.island

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. Every value has a sensible default so the island works out of the box. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)

    var enabled by bool(KEY_ENABLED, true)

    var showMedia by bool("show_media", true)
    var showNotifications by bool("show_notifications", true)
    var showCharging by bool("show_charging", true)
    var showRinger by bool("show_ringer", true)
    var showIdle by bool("show_idle", true)

    var hideFullscreen by bool("hide_fullscreen", true)
    var hideLandscape by bool("hide_landscape", true)
    var haptics by bool("haptics", true)
    var dotArt by bool("dot_art", true)
    var autoAlign by bool("auto_align", true)

    /** Idle pill size in dp. */
    var width by int("width", 100)
    var height by int("height", 32)

    /** Fine-tuning on top of the auto camera alignment, in dp. */
    var offsetX by int("offset_x", 0)
    var offsetY by int("offset_y", 0)

    /**
     * The front camera as measured by the settings screen, in px of a display [cameraScreenW] wide.
     * A fallback for when the service can't read the cut-out itself.
     */
    var cameraX by int("camera_x", -1)
    var cameraY by int("camera_y", -1)
    var cameraScreenW by int("camera_screen_w", -1)

    /** How long a notification stays open, in seconds. */
    var noticeSeconds by int("notice_seconds", 4)

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    companion object {
        const val KEY_ENABLED = "enabled"
    }
}
