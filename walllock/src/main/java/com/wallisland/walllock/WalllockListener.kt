package com.wallisland.walllock

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Watches the media sessions (notification access is what lets an app see them) and puts the cover
 * screen up whenever the screen turns off while music is playing, so it's there on the next wake.
 */
class WalllockListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var sessions: MediaSessionManager? = null
    private var receiverOn = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list.orEmpty())
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> maybeShow()
                // Unlocked by fingerprint or face straight past us: get out of the way.
                Intent.ACTION_USER_PRESENT -> LockActivity.close()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val msm = getSystemService(MediaSessionManager::class.java)
        sessions = msm
        val me = ComponentName(this, WalllockListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me, main)
            bind(msm.getActiveSessions(me))
        } catch (_: SecurityException) {
            // Access was revoked between connect and now.
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, f)
        receiverOn = true
    }

    override fun onListenerDisconnected() {
        cleanup()
        // Some skins drop the binding; ask for it back.
        try {
            requestRebind(ComponentName(this, WalllockListener::class.java))
        } catch (_: Exception) {
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        sessions = null
        unbind()
        if (receiverOn) {
            try {
                unregisterReceiver(screen)
            } catch (_: Exception) {
            }
            receiverOn = false
        }
        NowPlaying.post(null)
    }

    private fun maybeShow() {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        val t = NowPlaying.current ?: return
        val recent = SystemClock.elapsedRealtime() - NowPlaying.lastPlayingAt < PAUSED_GRACE_MS
        if (t.playing || (prefs.showPaused && recent)) LockActivity.show(this)
    }

    // ---- Media --------------------------------------------------------------------------------------

    private fun bind(list: List<MediaController>) {
        unbind()
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() = publish()
            }
            c.registerCallback(cb, main)
            controllers += c to cb
        }
        publish()
    }

    private fun unbind() {
        for ((c, cb) in controllers) c.unregisterCallback(cb)
        controllers.clear()
    }

    private fun publish() {
        // Whatever is actually playing wins; otherwise the most recent session that has a song.
        val candidates = controllers.map { it.first }.filter { it.metadata?.title() != null }
        val chosen = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        if (chosen == null) {
            NowPlaying.post(null)
            return
        }
        val md = chosen.metadata!!
        val st = chosen.playbackState
        NowPlaying.post(
            NowPlaying.Track(
                pkg = chosen.packageName,
                title = md.title().orEmpty(),
                artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty(),
                album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING,
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
        /** How long after pausing the cover still comes up, like the iPhone's lock screen player. */
        const val PAUSED_GRACE_MS = 10 * 60_000L

        fun hasAccess(ctx: Context): Boolean =
            android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.contains(ComponentName(ctx, WalllockListener::class.java).flattenToString()) == true
    }
}
