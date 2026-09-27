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
    /** The app's own buttons ("Reply", "Mark as read"…), at most three. */
    val actions: List<NoticeAction> = emptyList(),
    /** The app's notification colour, for the edge light; 0 when it has none. */
    val color: Int = 0,
    val postedAt: Long = System.currentTimeMillis(),
)

/** A notification button. [reply] is set when it takes typed input (quick replies use it). */
data class NoticeAction(val title: String, val intent: PendingIntent, val reply: android.app.RemoteInput?)

/** A call in progress (or ringing), from a call-style notification. */
data class CallInfo(
    val key: String,
    val pkg: String,
    val appName: String,
    val name: String,
    /** Wall-clock start of the call, for the timer. */
    val startedAt: Long,
    val intent: PendingIntent?,
    /** Still ringing: not answered yet. */
    val ringing: Boolean = false,
    /** The call's own Answer button, when it has one. */
    val answer: PendingIntent? = null,
    /** The call's own Decline / Hang up button, when it has one. */
    val hangUp: PendingIntent? = null,
)

/** An ongoing "live activity" read from a notification: a timer, turn-by-turn navigation, or progress. */
data class LiveInfo(
    val key: String,
    val kind: Kind,
    val pkg: String,
    val appName: String,
    /** Headline: the distance for navigation, otherwise the notification title. */
    val title: String,
    val text: String,
    /** Navigation's manoeuvre arrow, from the notification's large icon. */
    val icon: Bitmap?,
    /** Chronometer base (wall clock) when the app runs a live timer; 0 when it doesn't. */
    val chronoBase: Long,
    val countDown: Boolean,
    /** A time read out of the notification text ("4:32") when there's no live chronometer. */
    val staticTime: String?,
    val progress: Int,
    val progressMax: Int,
    val indeterminate: Boolean,
    val postedAt: Long,
    val intent: PendingIntent?,
) {
    /** Ordered by priority: navigation beats deliveries and rides beats timers beats events beats progress. */
    enum class Kind { NAV, DELIVERY, TIMER, EVENT, PROGRESS }

    /** The time to show for a timer or stopwatch, if any. */
    fun timeText(now: Long = System.currentTimeMillis()): String? {
        if (chronoBase > 0) {
            val ms = if (countDown) chronoBase - now else now - chronoBase
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
        return staticTime
    }

    /** For calendar events: minutes until it starts ("10m"), or NOW once it has. */
    fun eventText(now: Long = System.currentTimeMillis()): String {
        val min = Math.ceil((chronoBase - now) / 60_000.0).toInt()
        return if (min <= 0) "NOW" else "${min}m"
    }

    val percent: Int get() = if (progressMax > 0) (progress * 100 / progressMax).coerceIn(0, 100) else 0
}

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
        fun onCall(call: CallInfo?)
        fun onLive(live: List<LiveInfo>)
    }

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onMedia(media)
            value?.onCall(call)
            value?.onLive(live.values.toList())
        }

    private val live = LinkedHashMap<String, LiveInfo>()

    fun putLive(info: LiveInfo) {
        live[info.key] = info
        listener?.onLive(live.values.toList())
    }

    fun removeLive(key: String) {
        if (live.remove(key) != null) listener?.onLive(live.values.toList())
    }

    fun currentLive(): List<LiveInfo> = live.values.toList()

    var media: MediaInfo? = null
        private set

    /** Set by the notification listener so the island can clear an auto-cancel notification it opened. */
    var canceller: ((String) -> Unit)? = null

    /** The last few notifications, newest first, for the swipe-down history. */
    val history = ArrayList<Notice>()

    fun postNotice(notice: Notice) {
        history.removeAll { it.key == notice.key }
        history.add(0, notice)
        while (history.size > HISTORY_SIZE) history.removeAt(history.size - 1)
        listener?.onNotice(notice)
    }

    /** A notification that doesn't pop up (muted app, silent channel): only kept in the recent list. */
    fun addQuiet(notice: Notice) {
        history.removeAll { it.key == notice.key }
        history.add(0, notice)
        while (history.size > HISTORY_SIZE) history.removeAt(history.size - 1)
    }

    /** Notifications already in the shade when the listener connects; newest five kept. */
    fun seedHistory(notices: List<Notice>) {
        val keys = history.map { it.key }.toSet()
        history += notices.filter { it.key !in keys }
        history.sortByDescending { it.postedAt }
        while (history.size > HISTORY_SIZE) history.removeAt(history.size - 1)
    }

    const val HISTORY_SIZE = 5

    fun removeNotice(key: String) {
        listener?.onNoticeRemoved(key)
    }

    var call: CallInfo? = null
        private set

    /** What happened on the last unlock, shown in settings to help debug the unlock animation. */
    @Volatile var unlockLog: String = "No unlock seen yet"

    /** What happened when headphones last connected, for Troubleshoot. */
    @Volatile var budsLog: String = "No headphones connected since the island started"

    fun postCall(info: CallInfo?) {
        call = info
        listener?.onCall(info)
    }

    fun postMedia(info: MediaInfo?) {
        media = info
        listener?.onMedia(info)
    }
}
