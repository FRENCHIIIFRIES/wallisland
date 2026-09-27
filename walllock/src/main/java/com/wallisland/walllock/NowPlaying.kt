package com.wallisland.walllock

import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** What's playing right now, handed from the notification listener to the lock screen. */
object NowPlaying {

    class Track(
        val pkg: String,
        val title: String,
        val artist: String,
        val album: String,
        val art: Bitmap?,
        val playing: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val positionAt: Long,
        val speed: Float,
        val controller: MediaController,
    ) {
        /** Same song and cover, so the blurred background doesn't need redoing. */
        val artKey: String get() = "$pkg|$title|$artist|$album|${art?.width}x${art?.height}"

        /** Where the song is now, carried forward from the last update while it plays. */
        fun positionNow(): Long {
            if (!playing || positionAt <= 0L) return positionMs
            val p = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
            return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val watchers = mutableListOf<(Track?) -> Unit>()

    var current: Track? = null
        private set

    /** When something was last playing, for "keep showing after pause". */
    var lastPlayingAt = 0L
        private set

    fun post(t: Track?) = main.post {
        current = t
        if (t?.playing == true) lastPlayingAt = SystemClock.elapsedRealtime()
        for (w in watchers.toList()) w(t)
    }

    fun watch(w: (Track?) -> Unit) {
        watchers += w
        w(current)
    }

    fun unwatch(w: (Track?) -> Unit) {
        watchers -= w
    }
}
