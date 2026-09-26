package com.wallisland.island

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
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
