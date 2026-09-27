package com.wallisland.island

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Feeds notifications and media sessions into the island. The system keeps this service bound once the
 * user grants notification access, which also makes it a reliable place to (re)start the overlay.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    /** Keys already shown, so progress updates and "alert once" re-posts don't reopen the island. */
    private val seen = LinkedHashMap<String, Long>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bindControllers(list.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        IslandService.start(this)
        IslandHub.canceller = { key ->
            try {
                cancelNotification(key)
            } catch (_: Exception) {
            }
        }
        val msm = getSystemService(MediaSessionManager::class.java)
        sessions = msm
        val me = ComponentName(this, IslandNotificationListener::class.java)
        // Pick up a call that was already going when we (re)connected.
        try {
            val active = activeNotifications.orEmpty()
            active.firstOrNull { isCall(it) }?.let { IslandHub.postCall(toCall(it)) }
            for (sbn in active) toLive(sbn)?.let { IslandHub.putLive(it) }
        } catch (_: Exception) {
        }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
            bindControllers(msm.getActiveSessions(me))
        } catch (_: SecurityException) {
            // Access was revoked between connect and now; nothing to track.
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        IslandHub.canceller = null
        unbindControllers()
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        IslandHub.postMedia(null)
        // Ask the system to bring us back; some OEM skins drop the binding under memory pressure.
        requestRebind(ComponentName(this, IslandNotificationListener::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (isCall(sbn)) {
            IslandHub.postCall(toCall(sbn))
            return
        }
        val live = toLive(sbn)
        if (live != null) {
            IslandHub.putLive(live)
            return
        }
        IslandHub.removeLive(sbn.key)
        val notice = toNotice(sbn, rankingMap) ?: return
        IslandHub.postNotice(notice)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        if (IslandHub.call?.key == sbn.key) IslandHub.postCall(null)
        IslandHub.removeLive(sbn.key)
        IslandHub.removeNotice(sbn.key)
    }

    private fun toNotice(sbn: StatusBarNotification, rankingMap: RankingMap?): Notice? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        if (sbn.packageName in prefs.blockedApps) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        val extras = n.extras ?: return null
        if (extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null

        // Respect the user's channel settings: silent notifications stay silent.
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance in 1 until NotificationManager.IMPORTANCE_DEFAULT) return null
            if (android.os.Build.VERSION.SDK_INT >= 29 && ranking.isSuspended) return null
        }

        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (alertOnce && seen.containsKey(sbn.key)) return null
        remember(sbn.key)

        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = lastMessage(extras)
            ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null

        val appCtx = try {
            createPackageContext(sbn.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            this
        }
        val icon = try {
            n.smallIcon?.loadDrawable(appCtx)
        } catch (_: Exception) {
            null
        }
        val avatar = try {
            n.getLargeIcon()?.let { iconToBitmap(it) }
        } catch (_: Exception) {
            null
        }

        return Notice(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appLabel(this, sbn.packageName),
            title = title,
            text = text,
            icon = icon,
            avatar = avatar,
            intent = n.contentIntent,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            actions = actionsOf(n),
            color = n.color,
        )
    }

    private val prefs by lazy { Prefs(this) }

    /**
     * Ongoing notifications that deserve a live activity: navigation (Maps, Waze…), timers and
     * stopwatches (clock apps), and anything showing a progress bar (downloads, uploads, updates).
     */
    private fun toLive(sbn: StatusBarNotification): LiveInfo? = classify(sbn)?.let { (kind, facts) -> buildLive(sbn, kind, facts) }

    /** The raw fields we look at, kept together so the Troubleshoot screen can show them. */
    private data class Facts(
        val title: String,
        val text: String,
        val chrono: Boolean,
        val countDown: Boolean,
        val whenMs: Long,
        val progress: Int,
        val progressMax: Int,
        val indeterminate: Boolean,
        val persistent: Boolean,
        val clockApp: Boolean,
    )

    private fun facts(sbn: StatusBarNotification): Facts? {
        val n = sbn.notification ?: return null
        val ex = n.extras ?: return null
        val flags = n.flags
        val lower = sbn.packageName.lowercase()
        return Facts(
            title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty(),
            text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
                ?.toString()?.trim().orEmpty(),
            chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            whenMs = n.`when`,
            progress = ex.getInt(Notification.EXTRA_PROGRESS, 0),
            progressMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            // Since Android 14 many "permanent" notifications no longer carry the ongoing flag.
            persistent = sbn.isOngoing || flags and Notification.FLAG_FOREGROUND_SERVICE != 0 ||
                flags and Notification.FLAG_NO_CLEAR != 0,
            clockApp = CLOCK_HINTS.any { lower.contains(it) },
        )
    }

    private fun classify(sbn: StatusBarNotification): Pair<LiveInfo.Kind, Facts>? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == packageName) return null
        if (n.extras?.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return null
        if (n.category == Notification.CATEGORY_CALL) return null
        val f = facts(sbn) ?: return null
        val navApp = pkg in NAV_APPS || n.category == "navigation"
        if (!f.persistent && !navApp) return null
        val kind = when {
            navApp -> LiveInfo.Kind.NAV
            n.category == "stopwatch" -> LiveInfo.Kind.TIMER
            // Any live countdown (timers, cooking, workout apps).
            f.chrono && f.countDown -> LiveInfo.Kind.TIMER
            // Clock apps: a chronometer, a time in the text, or an end time in the future.
            f.clockApp && (f.chrono || TIME.containsMatchIn(f.title) || TIME.containsMatchIn(f.text) ||
                f.whenMs > System.currentTimeMillis() + 1500 || n.category == Notification.CATEGORY_ALARM ||
                n.category == Notification.CATEGORY_STATUS) -> LiveInfo.Kind.TIMER
            f.progressMax > 0 || f.indeterminate -> LiveInfo.Kind.PROGRESS
            else -> return null
        }
        return kind to f
    }

    private fun buildLive(sbn: StatusBarNotification, kind: LiveInfo.Kind, f: Facts): LiveInfo {
        val n = sbn.notification
        val pkg = sbn.packageName
        val title = f.title
        val text = f.text
        val now = System.currentTimeMillis()
        val icon = if (kind == LiveInfo.Kind.NAV) {
            try {
                n.getLargeIcon()?.let { iconToBitmap(it) }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val staticTime = TIME.find(title)?.value ?: TIME.find(text)?.value
        // Live base: the app's chronometer, else (clock apps only) a future end time as a countdown.
        val (base, down) = when {
            f.chrono -> f.whenMs to f.countDown
            kind == LiveInfo.Kind.TIMER && staticTime == null && f.whenMs > now + 1500 -> f.whenMs to true
            else -> 0L to false
        }
        // Navigation: show just the distance ("200 m", "0.3 mi") when there is one.
        val headline = if (kind == LiveInfo.Kind.NAV) {
            DISTANCE.find(title)?.value ?: DISTANCE.find(text)?.value ?: title.ifEmpty { text }
        } else {
            title
        }
        return LiveInfo(
            key = sbn.key,
            kind = kind,
            pkg = pkg,
            appName = appLabel(this, pkg),
            title = headline,
            text = text,
            icon = icon,
            chronoBase = base,
            countDown = down,
            staticTime = staticTime,
            progress = f.progress,
            progressMax = f.progressMax,
            indeterminate = f.indeterminate,
            postedAt = sbn.postTime,
            intent = n.contentIntent,
        )
    }

    /** One line per persistent notification: what we read from it and what we decided. For Troubleshoot. */
    fun describeOngoing(): String {
        val all = try {
            activeNotifications.orEmpty()
        } catch (_: Exception) {
            return "Couldn't read notifications."
        }
        val lines = all.filter { it.packageName != packageName }.mapNotNull { sbn ->
            val f = facts(sbn) ?: return@mapNotNull null
            val nav = sbn.packageName in NAV_APPS || sbn.notification.category == "navigation"
            if (!f.persistent && !nav) return@mapNotNull null
            val verdict = classify(sbn)?.first?.name ?: "ignored"
            val whenRel = (f.whenMs - System.currentTimeMillis()) / 1000
            buildString {
                append("• ").append(appLabel(this@IslandNotificationListener, sbn.packageName))
                append("  →  ").append(verdict).append('\n')
                append("  cat=").append(sbn.notification.category ?: "-")
                append(" chrono=").append(f.chrono).append(if (f.countDown) "↓" else "")
                append(" when=").append(if (whenRel >= 0) "+" else "").append(whenRel).append("s")
                if (f.progressMax > 0 || f.indeterminate) append(" progress=").append(f.progress).append('/').append(f.progressMax)
                append('\n').append("  \"").append(f.title.take(40)).append("\" / \"").append(f.text.take(40)).append('"')
            }
        }
        return if (lines.isEmpty()) "No ongoing notifications right now. Start a timer or Maps directions, then tap Check again."
        else lines.joinToString("\n")
    }

    /** Ongoing call notifications (phone, WhatsApp, Instagram, Meet…) use the "call" category. */
    private fun isCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName == packageName) return false
        return n.category == Notification.CATEGORY_CALL && sbn.isOngoing
    }

    private fun toCall(sbn: StatusBarNotification): CallInfo {
        val n = sbn.notification
        val extras = n.extras
        val name = (extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()).orEmpty()
        // Apps with a running call timer set `when` to the call start; otherwise count from the post time.
        val usesChrono = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true
        val start = if (usesChrono && n.`when` > 0) n.`when` else sbn.postTime
        val previous = IslandHub.call
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appLabel(this, sbn.packageName),
            name = name,
            startedAt = if (previous?.key == sbn.key && !usesChrono) previous.startedAt else start,
            intent = n.contentIntent,
        )
    }

    /** The notification's own buttons, keeping any that take a typed reply (for quick replies). */
    private fun actionsOf(n: Notification): List<NoticeAction> = n.actions.orEmpty().mapNotNull { a ->
        val pi = a.actionIntent ?: return@mapNotNull null
        val title = a.title?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        val reply = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
        NoticeAction(title, pi, reply)
    }.take(3)

    /** For messaging-style notifications, show the newest message rather than "3 new messages". */
    private fun lastMessage(extras: android.os.Bundle): String? {
        @Suppress("DEPRECATION")
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = msgs.lastOrNull() as? android.os.Bundle ?: return null
        val text = last.getCharSequence("text")?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val sender = last.getCharSequence("sender")?.toString()?.trim()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return if (isGroup && !sender.isNullOrEmpty()) "$sender: $text" else text
    }

    private fun iconToBitmap(icon: Icon): Bitmap? {
        val d = icon.loadDrawable(this) ?: return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val size = dp(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    private fun remember(key: String) {
        seen[key] = System.currentTimeMillis()
        if (seen.size > 200) seen.remove(seen.keys.first())
    }

    // ---- Media --------------------------------------------------------------------------------------

    private fun bindControllers(list: List<MediaController>) {
        unbindControllers()
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
                override fun onSessionDestroyed() = publishMedia()
            }
            c.registerCallback(cb, main)
            controllers += c to cb
        }
        publishMedia()
    }

    private fun unbindControllers() {
        for ((c, cb) in controllers) c.unregisterCallback(cb)
        controllers.clear()
    }

    private fun publishMedia() {
        // Prefer whatever is actually playing; otherwise fall back to the most recent session with a title.
        val candidates = controllers.map { it.first }.filter { it.metadata?.title() != null }
        val chosen = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        if (chosen == null) {
            IslandHub.postMedia(null)
            return
        }
        val md = chosen.metadata!!
        val st = chosen.playbackState
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
        IslandHub.postMedia(
            MediaInfo(
                pkg = chosen.packageName,
                appName = appLabel(this, chosen.packageName),
                title = md.title().orEmpty(),
                artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty(),
                art = art,
                playing = playing,
                durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = st?.position ?: 0L,
                positionAt = st?.lastPositionUpdateTime ?: 0L,
                speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                controller = chosen,
            )
        )
    }

    private fun MediaMetadata.title(): String? =
        (getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?.takeIf { it.isNotBlank() }

    companion object {
        private val NAV_APPS = setOf(
            "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "net.osmand", "net.osmand.plus",
            "com.sygic.aura", "ru.yandex.yandexnavi",
        )

        /** The connected listener, for the Troubleshoot screen. */
        @Volatile var instance: IslandNotificationListener? = null
            private set

        private val CLOCK_HINTS = listOf("clock", "timer", "alarm", "stopwatch")

        /** "200 m", "1.2 km", "500 ft", "0.3 mi". */
        private val DISTANCE = Regex("""\b\d+([.,]\d+)?\s?(m|km|ft|mi|yd|metres|meters|miles|feet)\b""", RegexOption.IGNORE_CASE)

        /** "4:32", "12:05", "1:02:33". */
        private val TIME = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, IslandNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        private val labels = HashMap<String, String>()

        fun appLabel(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
            try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast('.')
            }
        }
    }
}
