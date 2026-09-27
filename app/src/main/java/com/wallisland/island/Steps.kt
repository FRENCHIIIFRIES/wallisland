package com.wallisland.island

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import java.util.Calendar

/**
 * Today's steps from the phone's own step counter, which counts since boot. The first reading of each
 * day becomes that day's starting point; readings arrive batched, so it costs next to no battery.
 */
object Steps {
    /** Steps so far today, or -1 before the first reading. */
    @Volatile var today = -1
        private set

    private var listener: SensorEventListener? = null

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (listener != null || !hasPermission(ctx)) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val prefs = Prefs(ctx)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = record(prefs, e.values[0].toLong())
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        listener = l
        try {
            // Batched up to a minute: the island shows a daily total, not a live count.
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        } catch (_: Exception) {
            listener = null
        }
    }

    fun stop(ctx: Context) {
        val l = listener ?: return
        listener = null
        try {
            ctx.getSystemService(SensorManager::class.java)?.unregisterListener(l)
        } catch (_: Exception) {
        }
    }

    /** Raw counter value (steps since boot) to today's total, rolling over at midnight and after a reboot. */
    fun record(prefs: Prefs, raw: Long) {
        val c = Calendar.getInstance()
        val day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        if (prefs.stepDay != day) {
            prefs.stepDay = day
            prefs.stepBase = raw
        }
        // The counter restarts from zero after a reboot.
        if (raw < prefs.stepBase) prefs.stepBase = 0
        today = (raw - prefs.stepBase).toInt()
    }

    fun text(n: Int): String = if (n >= 10_000) "%.1fK".format(n / 1000f) else "%,d".format(n)
}
