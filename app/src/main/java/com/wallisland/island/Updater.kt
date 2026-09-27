package com.wallisland.island

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks this app's GitHub releases for a newer build and installs it in place.
 * Every CI run publishes a release tagged `build-<run number>`, and the app's versionCode is that same
 * number, so "newer" is a plain integer comparison.
 */
object Updater {
    private const val REPO = "FRENCHIIIFRIES/wallisland"
    private const val RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=50"

    /**
     * Written into the notes of every release the main branch publishes. Other branches in the repo can
     * publish their own `build-N` releases (and win "latest"); only releases carrying this mark count.
     */
    private const val CHANNEL_MARK = "channel: wallisland-main"
    private const val ASSET = "Wallisland.apk"

    data class Release(val build: Int, val apkUrl: String, val sizeBytes: Long)

    sealed class Check {
        object UpToDate : Check()
        data class Available(val release: Release) : Check()
        data class Failed(val reason: String) : Check()
    }

    private val main = Handler(Looper.getMainLooper())

    fun currentBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    /**
     * Finds the newest main-branch release on a background thread; [done] runs on the main thread.
     * Picks the highest build number among marked releases rather than trusting GitHub's "latest".
     */
    fun check(ctx: Context, done: (Check) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val result = try {
                val newest = newestRelease(org.json.JSONArray(get(RELEASES)))
                when {
                    newest == null -> Check.Failed("No release with an APK yet")
                    newest.build > currentBuild(app) -> Check.Available(newest)
                    else -> Check.UpToDate
                }
            } catch (e: Exception) {
                Check.Failed(friendly(e))
            }
            main.post { done(result) }
        }.start()
    }

    /** The highest-numbered `build-N` release that carries [CHANNEL_MARK] and has the APK attached. */
    fun newestRelease(releases: org.json.JSONArray): Release? {
        var best: Release? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            if (!r.optString("body").contains(CHANNEL_MARK)) continue
            val build = r.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") != ASSET) continue
                if (best == null || build > best.build) {
                    best = Release(build, a.getString("browser_download_url"), a.optLong("size", -1))
                }
            }
        }
        return best
    }

    /** True once the user has let Wallisland install apps; otherwise opens that settings page. */
    fun ensureCanInstall(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Streams the APK straight into a PackageInstaller session, then hands over to the system's
     * "Update this app?" prompt. [progress] gets 0..100 (or -1 when the size is unknown) on the main thread.
     */
    fun install(
        ctx: Context,
        release: Release,
        progress: (Int) -> Unit,
        committed: () -> Unit,
        failed: (String) -> Unit,
    ) {
        val app = ctx.applicationContext
        Thread {
            var session: PackageInstaller.Session? = null
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(app.packageName) }
                val id = installer.createSession(params)
                val s = installer.openSession(id)
                session = s
                val conn = open(release.apkUrl)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                conn.inputStream.use { input ->
                    s.openWrite("base.apk", 0, total.coerceAtLeast(-1)).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -2
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val pct = if (total > 0) (read * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                main.post { progress(pct) }
                            }
                        }
                        s.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = PendingIntent.getBroadcast(
                    app, 3, Intent(app, InstallResultReceiver::class.java), flags,
                )
                s.commit(status.intentSender)
                s.close()
                session = null
                main.post { committed() }
            } catch (e: Exception) {
                session?.abandon()
                main.post { failed(friendly(e)) }
            }
        }.start()
    }

    private fun open(url: String, json: Boolean = false): HttpURLConnection {
        var target = url
        // Follow redirects by hand: GitHub sends release downloads to another host.
        repeat(5) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Wallisland-updater")
            if (json) c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            if (code in 300..399) {
                target = c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect")
                c.disconnect()
            } else if (code in 200..299) {
                return c
            } else {
                c.disconnect()
                throw IllegalStateException("GitHub answered $code")
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun get(url: String): String = open(url, json = true).let { c -> c.inputStream.bufferedReader().use { it.readText() } }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "GitHub took too long to answer"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** Receives the installer's verdict and shows the system confirmation when it's needed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (_: Exception) {
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The app restarts into the new version.
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(
                    context,
                    "This copy was signed differently. Uninstall Wallisland once, then install the new build.",
                    Toast.LENGTH_LONG,
                ).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // User tapped Cancel.
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update failed"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
