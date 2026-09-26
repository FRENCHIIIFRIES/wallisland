package com.wallisland.island

import android.graphics.Rect
import android.os.Build
import android.view.DisplayCutout
import android.view.WindowInsets

/** Finds the front-camera hole from the display cut-out. */
object Camera {
    fun fromInsets(insets: WindowInsets?, screenW: Int): Rect? {
        if (insets == null || Build.VERSION.SDK_INT < 28) return null
        return fromCutout(insets.displayCutout, screenW)
    }

    /**
     * The top cut-out, if it's a camera hole. Wide notches (half the screen or more) are ignored, since
     * centring a pill on those looks wrong.
     */
    fun fromCutout(cutout: DisplayCutout?, screenW: Int): Rect? {
        if (cutout == null || Build.VERSION.SDK_INT < 29) return null
        val r = cutout.boundingRectTop
        if (r.isEmpty || r.width() >= screenW / 2) return null
        return Rect(r)
    }
}
