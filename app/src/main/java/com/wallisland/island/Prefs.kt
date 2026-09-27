package com.wallisland.island

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. Every value has a sensible default so the island works out of the box. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)

    init {
        // Each default-size change (v2-v4) clears saved sizes once. Drop sizes saved by older builds once, so existing
        // installs pick up the new size too; the sliders still work as before afterwards.
        if (!sp.getBoolean("size_v4", false)) {
            sp.edit().remove("width").remove("height").putBoolean("size_v4", true).apply()
        }
    }

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
    var dotColor by bool("dot_color", true)
    var autoAlign by bool("auto_align", true)
    var hideWhileCapturing by bool("hide_capturing", true)
    var showLive by bool("show_live", true)
    var showUnlock by bool("show_unlock", true)
    var showBuds by bool("show_buds", true)
    var volumeInIsland by bool("volume_island", true)
    var showToggleChanges by bool("show_toggle_changes", true)
    var edgeLight by bool("edge_light", true)
    var showEvents by bool("show_events", true)

    /** Essential Key remap: the learned key code (-1 = not learned) and what short/long presses do. */
    var essentialKey by int("essential_key", -1)
    var essentialShort: String
        get() = sp.getString("essential_short", KeyAction.DEFAULT.name) ?: KeyAction.DEFAULT.name
        set(v) = sp.edit().putString("essential_short", v).apply()
    var essentialLong: String
        get() = sp.getString("essential_long", KeyAction.DEFAULT.name) ?: KeyAction.DEFAULT.name
        set(v) = sp.edit().putString("essential_long", v).apply()
    var essentialShortApp: String
        get() = sp.getString("essential_short_app", "") ?: ""
        set(v) = sp.edit().putString("essential_short_app", v).apply()
    var essentialLongApp: String
        get() = sp.getString("essential_long_app", "") ?: ""
        set(v) = sp.edit().putString("essential_long_app", v).apply()

    /** Wall-clock end of a running focus timer, or 0. */
    var focusEnd: Long
        get() = sp.getLong("focus_end", 0L)
        set(v) = sp.edit().putLong("focus_end", v).apply()

    /** Accent colour (ARGB). */
    var accent by int("accent", Look.RED)

    /** Packages whose notifications never open the island. */
    var blockedApps: Set<String>
        get() = sp.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet("blocked_apps", HashSet(value)).apply()

    /** Background opacity of the island, in percent. */
    var opacity by int("opacity", DEFAULT_OPACITY)

    /** Idle pill size in dp. */
    var width by int("width", DEFAULT_WIDTH)
    var height by int("height", DEFAULT_HEIGHT)

    /** Fine-tuning on top of the auto camera alignment, in dp. */
    var offsetX by int("offset_x", DEFAULT_OFFSET_X)
    var offsetY by int("offset_y", DEFAULT_OFFSET_Y)

    /**
     * The front camera as measured by the settings screen, in px of a display [cameraScreenW] wide.
     * A fallback for when the service can't read the cut-out itself.
     */
    var cameraX by int("camera_x", -1)
    var cameraY by int("camera_y", -1)
    var cameraScreenW by int("camera_screen_w", -1)

    /** How long a notification stays open, in seconds. */
    var noticeSeconds by int("notice_seconds", DEFAULT_NOTICE_SECONDS)

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

        /** Tuned by hand on a Nothing Phone (3a): a chunky pill that sits right on the camera. */
        const val DEFAULT_WIDTH = 101
        const val DEFAULT_HEIGHT = 33
        const val DEFAULT_OFFSET_X = 17
        const val DEFAULT_OFFSET_Y = 0
        const val DEFAULT_NOTICE_SECONDS = 5
        const val DEFAULT_OPACITY = 85
    }
}
