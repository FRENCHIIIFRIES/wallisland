package com.wallisland.walllock

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("walllock", Context.MODE_PRIVATE)

    /** Show the cover screen on the lock screen at all. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Keep showing it for a while after the music is paused, like the iPhone does. */
    var showPaused: Boolean
        get() = sp.getBoolean("show_paused", true)
        set(v) = sp.edit().putBoolean("show_paused", v).apply()

    /** Whether the cover was last left big; the next lock screen opens the same way. */
    var expanded: Boolean
        get() = sp.getBoolean("expanded", false)
        set(v) = sp.edit().putBoolean("expanded", v).apply()
}
