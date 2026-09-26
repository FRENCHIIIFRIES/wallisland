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
        )
    }

    private val prefs by lazy { Prefs(this) }

    /**
     * Ongoing notifications that deserve a live activity: navigation (Maps, Waze…), timers and
     * stopwatches (clock apps), and anything showing a progress bar (downloads, uploads, updates).
     */
    private fun toLive(sbn: StatusBarNotification): LiveInfo? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == packageName || !sbn.isOngoing) return null
        val ex = n.extras ?: return null
        if (ex.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return null
        if (n.category == Notification.CATEGORY_CALL) return null

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            ?.toString()?.trim().orEmpty()
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val progressMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val lower = pkg.lowercase()

        val kind = when {
            n.category == "navigation" || pkg in NAV_APPS -> LiveInfo.Kind.NAV
            n.category == "stopwatch" || ((lower.contains("clock") || lower.contains("timer")) &&
                (chrono || TIME.containsMatchIn(title) || TIME.containsMatchIn(text))) -> LiveInfo.Kind.TIMER
            progressMax > 0 || indeterminate -> LiveInfo.Kind.PROGRESS
            else -> return null
        }
        val icon = if (kind == LiveInfo.Kind.NAV) {
            try {
                n.getLargeIcon()?.let { iconToBitmap(it) }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return LiveInfo(
            key = sbn.key,
            kind = kind,
            pkg = pkg,
            appName = appLabel(this, pkg),
            title = title,
            text = text,
            icon = icon,
            chronoBase = if (chrono) n.`when` else 0L,
            countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            staticTime = TIME.find(title)?.value ?: TIME.find(text)?.value,
            progress = ex.getInt(Notification.EXTRA_PROGRESS, 0),
            progressMax = progressMax,
            indeterminate = indeterminate,
            postedAt = sbn.postTime,
            intent = n.contentIntent,
        )
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
