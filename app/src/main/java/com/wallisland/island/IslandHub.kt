package com.wallisland.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.SystemClock

/** A notification worth surfacing in the island. */
data class Notice(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Drawable?,
    val avatar: Bitmap?,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
)

/** Whatever is currently playing. */
data class MediaInfo(
    val pkg: String,
    val appName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,
    val speed: Float,
    val controller: MediaController?,
) {
    /** Extrapolates the playhead from the last reported position, like the system media controls do. */
    fun currentPosition(): Long {
        if (!playing) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - positionAt
        val pos = positionMs + (elapsed * speed).toLong()
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }

    /** Same track, ignoring play state and position. */
    fun sameTrack(other: MediaInfo?): Boolean =
        other != null && other.pkg == pkg && other.title == title && other.artist == artist
}

/**
 * In-process bridge between the notification listener (which the system keeps bound) and the overlay
 * service. Everything here runs on the main thread.
 */
object IslandHub {
    interface Listener {
        fun onNotice(notice: Notice)
        fun onNoticeRemoved(key: String)
        fun onMedia(media: MediaInfo?)
    }

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onMedia(media)
        }

    var media: MediaInfo? = null
        private set

    /** Set by the notification listener so the island can clear an auto-cancel notification it opened. */
    var canceller: ((String) -> Unit)? = null

    fun postNotice(notice: Notice) {
        listener?.onNotice(notice)
    }

    fun removeNotice(key: String) {
        listener?.onNoticeRemoved(key)
    }

    fun postMedia(info: MediaInfo?) {
        media = info
        listener?.onMedia(info)
    }
}
