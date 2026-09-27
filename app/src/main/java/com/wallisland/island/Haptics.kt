package com.wallisland.island

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    /** A short triple buzz, for a finished focus timer. */
    fun alert(ctx: Context) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Vibrator::class.java)
            } ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60, 90, 120), -1))
        } catch (_: Exception) {
        }
    }

    fun tick(ctx: Context) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Vibrator::class.java)
            } ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(12, 90))
            }
        } catch (_: Exception) {
        }
    }
}
