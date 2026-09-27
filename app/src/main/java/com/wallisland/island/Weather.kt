package com.wallisland.island

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Current weather for the double-tap peek, from Open-Meteo (free, no account or key). The location is a
 * rough one, rounded to about a kilometre, and only ever sent to Open-Meteo.
 */
object Weather {
    data class Now(val tempC: Double, val code: Int, val isDay: Boolean, val fetchedAt: Long)

    @Volatile var now: Now? = null
        private set

    /** What the last fetch did, for Troubleshoot. */
    @Volatile var log = "Not fetched yet"
        private set

    private const val FRESH_MS = 30 * 60_000L
    private const val RETRY_MS = 2 * 60_000L
    @Volatile private var lastTry = 0L
    @Volatile private var fetching = false
    private val main = Handler(Looper.getMainLooper())

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Remembers where the phone roughly is. Called while the settings screen is open, since Android only
     * hands out location to apps in use; the island reuses the saved spot afterwards.
     */
    @SuppressLint("MissingPermission")
    fun saveLocation(ctx: Context): Boolean {
        if (!hasLocationPermission(ctx)) return false
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        val best = try {
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        } ?: return false
        val prefs = Prefs(ctx)
        prefs.weatherLat = "%.2f".format(Locale.US, best.latitude)
        prefs.weatherLon = "%.2f".format(Locale.US, best.longitude)
        return true
    }

    /** Fetches in the background when the reading is stale; [done] runs on the main thread afterwards. */
    fun refresh(ctx: Context, done: () -> Unit = {}) {
        val prefs = Prefs(ctx)
        if (!prefs.showWeather) return
        val t = SystemClock.elapsedRealtime()
        val n = now
        if (fetching || (n != null && n.fetchedAt > 0 && t - n.fetchedAt < FRESH_MS) ||
            (lastTry > 0 && t - lastTry < RETRY_MS && n == null)
        ) return
        fetching = true
        lastTry = t
        Thread {
            try {
                var lat = prefs.weatherLat.toDoubleOrNull()
                var lon = prefs.weatherLon.toDoubleOrNull()
                if (lat == null || lon == null) {
                    // No location from the phone yet: your city, estimated from your internet connection.
                    val geo = JSONObject(get("https://get.geojs.io/v1/ip/geo.json"))
                    lat = geo.getString("latitude").toDouble()
                    lon = geo.getString("longitude").toDouble()
                }
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,is_day",
                )
                val cur = JSONObject(get(url.toString())).getJSONObject("current")
                now = Now(
                    tempC = cur.getDouble("temperature_2m"),
                    code = cur.getInt("weather_code"),
                    isDay = cur.optInt("is_day", 1) == 1,
                    fetchedAt = SystemClock.elapsedRealtime(),
                )
                log = "OK: ${text(now!!)}" + if (prefs.weatherLat.isEmpty()) " (city from your connection)" else ""
            } catch (e: Exception) {
                log = "Couldn't fetch: ${e.javaClass.simpleName}"
            } finally {
                fetching = false
                main.post(done)
            }
        }.start()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Asks for a fresh rough fix (Android often has no last-known location saved), then fetches the weather
     * for it. Only works while the settings screen is open.
     */
    @SuppressLint("MissingPermission")
    fun locate(ctx: Context, done: () -> Unit = {}) {
        if (saveLocation(ctx)) {
            forceNext()
            refresh(ctx, done)
        }
        if (!hasLocationPermission(ctx) || android.os.Build.VERSION.SDK_INT < 30) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        try {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc ->
                if (loc == null) return@getCurrentLocation
                val prefs = Prefs(ctx)
                prefs.weatherLat = "%.2f".format(Locale.US, loc.latitude)
                prefs.weatherLon = "%.2f".format(Locale.US, loc.longitude)
                forceNext()
                refresh(ctx, done)
            }
        } catch (_: Exception) {
        }
    }

    /** The next refresh fetches even if the last reading is fresh (the location changed). */
    private fun forceNext() {
        now = now?.copy(fetchedAt = 0L)
        lastTry = 0L
    }

    /** Fahrenheit where people expect it, Celsius everywhere else. */
    fun temperature(n: Now): String {
        val f = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "KY", "PW", "FM", "MH")
        val v = if (f) n.tempC * 9 / 5 + 32 else n.tempC
        return "${v.roundToInt()}°"
    }

    fun glyph(n: Now): Glyph = when (n.code) {
        0, 1 -> if (n.isDay) Glyph.SUN else Glyph.MOON
        45, 48 -> Glyph.FOG
        in 51..67, in 80..82 -> Glyph.RAIN
        in 71..77, 85, 86 -> Glyph.SNOW
        in 95..99 -> Glyph.STORM
        else -> Glyph.CLOUD
    }

    fun label(n: Now): String = when (n.code) {
        0 -> "CLEAR"
        1 -> if (n.isDay) "SUNNY" else "CLEAR"
        2 -> "PARTLY CLOUDY"
        3 -> "CLOUDY"
        45, 48 -> "FOG"
        in 51..57 -> "DRIZZLE"
        in 61..67, in 80..82 -> "RAIN"
        in 71..77, 85, 86 -> "SNOW"
        in 95..99 -> "STORM"
        else -> "CLOUDY"
    }

    fun text(n: Now) = "${temperature(n)} ${label(n)}"

    /** For previews and tests. */
    fun setForTest(n: Now?) {
        now = n
    }
}
