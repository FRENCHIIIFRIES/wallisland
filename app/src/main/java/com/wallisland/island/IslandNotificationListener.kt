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
            // Fill the recent list with what's already in the shade, without popping any of it up.
            val ranking = currentRanking
            IslandHub.seedHistory(active.filter { !isCall(it) && toLive(it) == null }.mapNotNull { toNotice(it, ranking)?.first })
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
        val (notice, popUp) = toNotice(sbn, rankingMap) ?: return
        // Muted apps and silent notifications don't pop up, but still land in the recent list.
        if (popUp) IslandHub.postNotice(notice) else IslandHub.addQuiet(notice)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        if (IslandHub.call?.key == sbn.key) IslandHub.postCall(null)
        IslandHub.removeLive(sbn.key)
        IslandHub.removeNotice(sbn.key)
    }

    /** The notification, and whether it should pop up (false: muted app, silent channel, or a repeat). */
    private fun toNotice(sbn: StatusBarNotification, rankingMap: RankingMap?): Pair<Notice, Boolean>? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        var popUp = sbn.packageName !in prefs.blockedApps
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        val extras = n.extras ?: return null
        if (extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null

        // Respect the user's channel settings: silent notifications stay silent.
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (android.os.Build.VERSION.SDK_INT >= 29 && ranking.isSuspended) return null
            if (ranking.importance in 1 until NotificationManager.IMPORTANCE_DEFAULT) popUp = false
        }

        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (alertOnce && seen.containsKey(sbn.key)) popUp = false
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
            color = iconColor(sbn.packageName) ?: n.color,
            postedAt = sbn.postTime,
        ) to popUp
    }

    private val iconColors = HashMap<String, Int?>()

    /**
     * The app's own colour, taken from its launcher icon: WhatsApp green, Instagram pink, Gmail red. Many
     * apps leave their notification colour on the system's default blue, so that one is only a fallback.
     */
    private fun iconColor(pkg: String): Int? = iconColors.getOrPut(pkg) {
        try {
            val d = packageManager.getApplicationIcon(pkg)
            val size = 48
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            dominantColor(bmp).also { bmp.recycle() }
        } catch (_: Exception) {
            null
        }
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
        val deliveryApp = pkg in DELIVERY_APPS || pkg in RIDE_APPS
        // Order and ride tracking: ongoing, or at least showing progress (promotions are neither).
        if (deliveryApp && (f.persistent || f.progressMax > 0)) return LiveInfo.Kind.DELIVERY to f
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
        val headline = when (kind) {
            LiveInfo.Kind.NAV -> DISTANCE.find(title)?.value ?: DISTANCE.find(text)?.value ?: title.ifEmpty { text }
            // Deliveries and rides: the arrival ("8 MIN", "8-12 MIN", "7:45"), else the status line.
            LiveInfo.Kind.DELIVERY -> eta(title) ?: eta(text) ?: title.ifEmpty { text }
            else -> title
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

    private fun eta(s: String): String? {
        MINUTES.find(s)?.let { m ->
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            return if (b.isNotEmpty()) "$a-$b MIN" else "$a MIN"
        }
        return CLOCK_TIME.find(s)?.value?.uppercase()
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
        // Ringing calls are sometimes posted without the ongoing flag, but always with a full-screen intent.
        return n.category == Notification.CATEGORY_CALL && (sbn.isOngoing || n.fullScreenIntent != null)
    }

    private fun toCall(sbn: StatusBarNotification): CallInfo {
        val n = sbn.notification
        val extras = n.extras
        val name = (extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()).orEmpty()
        // Apps with a running call timer set `when` to the call start; otherwise count from the post time.
        val usesChrono = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true
        val start = if (usesChrono && n.`when` > 0) n.`when` else sbn.postTime
        val previous = IslandHub.call

        // Call-style notifications carry their buttons as extras; older ones only as titled actions.
        @Suppress("DEPRECATION")
        fun extra(key: String) = extras?.getParcelable<android.os.Parcelable>(key) as? android.app.PendingIntent
        fun action(re: Regex) = n.actions.orEmpty()
            .firstOrNull { a -> a.title?.toString()?.let { re.containsMatchIn(it) } == true }?.actionIntent
        val type = extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0
        val answer = extra(EXTRA_ANSWER) ?: action(ANSWER_WORDS)
        val ringing = type == CALL_TYPE_INCOMING || (type == 0 && answer != null)
        val hangUp = if (ringing) extra(EXTRA_DECLINE) ?: extra(EXTRA_HANG_UP) ?: action(END_WORDS)
        else extra(EXTRA_HANG_UP) ?: extra(EXTRA_DECLINE) ?: action(END_WORDS)

        // The timer starts when the call is picked up, not when it started ringing.
        val sameCall = previous?.key == sbn.key
        val startedAt = when {
            usesChrono -> start
            sameCall && previous!!.ringing && !ringing -> System.currentTimeMillis()
            sameCall -> previous!!.startedAt
            else -> start
        }
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appLabel(this, sbn.packageName),
            name = name,
            startedAt = startedAt,
            intent = n.contentIntent,
            ringing = ringing,
            answer = if (ringing) answer else null,
            hangUp = hangUp,
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
        /**
         * The most prominent vivid colour: pixels are grouped by hue and weighted by how saturated and
         * bright they are; greys, near-black and near-white don't count. Null for a colourless icon.
         */
        fun dominantColor(bmp: Bitmap): Int? {
            val buckets = 24
            val weight = FloatArray(buckets)
            val r = FloatArray(buckets); val g = FloatArray(buckets); val b = FloatArray(buckets)
            val hsv = FloatArray(3)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (p in px) {
                if (android.graphics.Color.alpha(p) < 200) continue
                android.graphics.Color.colorToHSV(p, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue
                val w = hsv[1] * hsv[2]
                val i = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
                weight[i] += w
                r[i] += android.graphics.Color.red(p) * w
                g[i] += android.graphics.Color.green(p) * w
                b[i] += android.graphics.Color.blue(p) * w
            }
            val best = weight.indices.maxByOrNull { weight[it] } ?: return null
            // Needs a real presence in the icon, not a few stray pixels.
            if (weight[best] < px.size * 0.04f) return null
            val w = weight[best]
            return android.graphics.Color.rgb((r[best] / w).toInt(), (g[best] / w).toInt(), (b[best] / w).toInt())
        }

        // Notification.CallStyle extras (API 31), read by name so older releases compile against them too.
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_ANSWER = "android.answerIntent"
        private const val EXTRA_DECLINE = "android.declineIntent"
        private const val EXTRA_HANG_UP = "android.hangUpIntent"
        private const val CALL_TYPE_INCOMING = 1
        private val ANSWER_WORDS = Regex("answer|accept|pick up", RegexOption.IGNORE_CASE)
        private val END_WORDS = Regex("decline|reject|hang ?up|\\bend\\b|leave|dismiss", RegexOption.IGNORE_CASE)

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

        /** Food, grocery and parcel delivery apps that post a live order tracker. */
        val DELIVERY_APPS = setOf(
            "com.ubercab.eats", "com.deliveroo.orderapp", "com.justeat.app.uk", "com.justeat.app", "com.takeaway.android",
            "com.dd.doordash", "com.grubhub.android", "com.postmates.android", "com.instacart.client",
            "com.global.foodpanda.android", "com.talabat", "com.glovoapp", "com.wolt.android", "com.getir",
            "in.swiggy.android", "com.application.zomato", "com.zeptoconsumerapp", "com.grofers.customerapp",
            "com.bigbasket.mobileapp", "com.amazon.mShop.android.shopping", "com.gopuff.consumer", "com.bolt.deliveryclient",
            "uk.co.dominos.android", "com.dominos", "com.menulog.m", "com.skipthedishes.android", "com.sainsburys.gol",
            "com.tesco.grocery.view", "com.ocadoretail.android", "com.royalmail.app", "com.dpd.yourdpd",
            "com.evri.android", "com.ups.mobile.android", "com.fedex.ida.android",
        )

        /** Ride-hailing apps. */
        val RIDE_APPS = setOf(
            "com.ubercab", "me.lyft.android", "ee.mtakso.client", "com.olacabs.customer", "com.rapido.passenger",
            "com.careem.acma", "com.grabtaxi.passenger", "com.gojek.app", "com.addisonlee.android",
            "com.freenow.passenger", "taxi.android.client", "com.didiglobal.passenger", "com.indriver",
        )

        /** "8 min", "8-12 mins", "8 – 12 minutes". */
        private val MINUTES = Regex("""\b(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?\s*(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)

        /** "7:45", "7:45 PM", "19:45". */
        private val CLOCK_TIME = Regex("""\b\d{1,2}:\d{2}(?:\s?[AaPp][Mm])?\b""")

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
