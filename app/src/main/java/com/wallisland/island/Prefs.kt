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

    /** Vibration style per kind: names of Haptics.Style. */
    var hapticTouch: String
        get() = sp.getString("haptic_touch", "SOFT") ?: "SOFT"
        set(v) = sp.edit().putString("haptic_touch", v).apply()
    var hapticNotice: String
        get() = sp.getString("haptic_notice", "SOFT") ?: "SOFT"
        set(v) = sp.edit().putString("haptic_notice", v).apply()
    var hapticAlert: String
        get() = sp.getString("haptic_alert", "SHARP") ?: "SHARP"
        set(v) = sp.edit().putString("haptic_alert", v).apply()
    var dotArt by bool("dot_art", true)
    var dotColor by bool("dot_color", true)
    var autoAlign by bool("auto_align", true)
    var hideWhileCapturing by bool("hide_capturing", true)

    /** The island's own countdown length (ms, for its draining dots) and stopwatch start (wall clock, 0 = off). */
    var timerTotal by long("timer_total", 0L)
    var stopwatchStart by long("stopwatch_start", 0L)

    /** The long-press panel's buttons, in order (names of IslandView.Quick), five at most. */
    var quickButtons: String
        get() = sp.getString("quick_buttons", null) ?: IslandView.Quick.DEFAULT.joinToString(",") { it.name }
        set(v) = sp.edit().putString("quick_buttons", v).apply()

    /** Steps in the double-tap peek: the goal, and the counter reading today started from. */
    var showSteps by bool("show_steps", true)

    /** Car mode when connected to the car's Bluetooth or Android Auto. */
    var carMode by bool("car_mode", true)

    /** A short note pinned to the island (empty = none). */
    var pinnedNote: String
        get() = sp.getString("pinned_note", "") ?: ""
        set(v) = sp.edit().putString("pinned_note", v).apply()

    /** Two things at once: the second gets its own bubble beside the pill. */
    var splitIsland by bool("split_island", true)
    var stepGoal by int("step_goal", 10_000)
    var stepDay by int("step_day", 0)
    var stepBase by long("step_base", 0L)

    /** Weather beside the time in the double-tap peek, and the rough spot it's for. */
    var showWeather by bool("show_weather", true)
    var weatherLat: String
        get() = sp.getString("weather_lat", "") ?: ""
        set(v) = sp.edit().putString("weather_lat", v).apply()
    var weatherLon: String
        get() = sp.getString("weather_lon", "") ?: ""
        set(v) = sp.edit().putString("weather_lon", v).apply()

    /** Turn off the system's own pop-up banners while the island shows notifications (needs an adb grant). */
    var replacePopups by bool("replace_popups", false)

    /** Set while it was the island that turned system pop-ups off, so only it turns them back on. */
    var popupsOffByUs by bool("popups_off_by_us", false)
    var showLive by bool("show_live", true)
    var showUnlock by bool("show_unlock", true)
    var showBuds by bool("show_buds", true)
    var volumeInIsland by bool("volume_island", true)
    var showToggleChanges by bool("show_toggle_changes", true)
    var edgeLight by bool("edge_light", true)
    var showEvents by bool("show_events", true)

    /** Essential Key remap: the learned key code (-1 = not learned) and what short/long presses do. */
    var essentialKey by int("essential_key", -1)

    /**
     * The key's hardware scan code. Nothing's Essential Key has no standard key code (it arrives as
     * KEYCODE_UNKNOWN), so it's recognised by this instead. -1 = not learned.
     */
    var essentialScan by int("essential_scan", -1)

    /** Close Essential Space when the Essential Key opens it (Nothing OS opens it before apps can stop it). */
    var closeEssentialSpace by bool("close_essential_space", true)

    /** The app that opens when the key is pressed, spotted while learning. Empty = not seen yet. */
    var essentialSpacePkg: String
        get() = sp.getString("essential_space_pkg", "") ?: ""
        set(v) = sp.edit().putString("essential_space_pkg", v).apply()
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

    private fun long(key: String, def: Long) = object : kotlin.properties.ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long) {
            sp.edit().putLong(key, value).apply()
        }
    }

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
