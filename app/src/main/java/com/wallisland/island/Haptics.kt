package com.wallisland.island

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The island's vibrations, in three kinds the user styles separately: touches (buttons, drags),
 * notifications arriving, and alerts (a timer finishing). Each is Soft, Sharp or Off.
 */
object Haptics {
    enum class Style(val label: String) { SOFT("Soft"), SHARP("Sharp"), OFF("Off") }

    fun style(name: String) = Style.values().firstOrNull { it.name == name } ?: Style.SOFT

    private fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }
    } catch (_: Exception) {
        null
    }?.takeIf { it.hasVibrator() }

    private fun play(ctx: Context, effect: () -> VibrationEffect) {
        try {
            vibrator(ctx)?.vibrate(effect())
        } catch (_: Exception) {
        }
    }

    /** Buttons, taps and drag steps. */
    fun tick(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.haptics) return
        when (style(p.hapticTouch)) {
            Style.OFF -> Unit
            Style.SOFT -> play(ctx) {
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                else VibrationEffect.createOneShot(10, 70)
            }
            Style.SHARP -> play(ctx) {
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                else VibrationEffect.createOneShot(22, 255)
            }
        }
    }

    /** A notification landing on the island. */
    fun notice(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.haptics) return
        when (style(p.hapticNotice)) {
            Style.OFF -> Unit
            Style.SOFT -> play(ctx) {
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                else VibrationEffect.createOneShot(14, 110)
            }
            Style.SHARP -> play(ctx) {
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                else VibrationEffect.createWaveform(longArrayOf(0, 20, 70, 20), intArrayOf(0, 255, 0, 255), -1)
            }
        }
    }

    /** Something finished or needs you: a timer, Battery Saver's offer. */
    fun alert(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.haptics) return
        when (style(p.hapticAlert)) {
            Style.OFF -> Unit
            Style.SOFT -> play(ctx) { VibrationEffect.createWaveform(longArrayOf(0, 40, 110, 40, 110, 60), intArrayOf(0, 110, 0, 110, 0, 140), -1) }
            Style.SHARP -> play(ctx) { VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60, 90, 120), intArrayOf(0, 255, 0, 255, 0, 255), -1) }
        }
    }
}
