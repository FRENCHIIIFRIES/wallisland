package com.wallisland.walllock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Setup: the permissions Walllock needs, a couple of switches and a preview. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val rows = mutableListOf<Pair<() -> Boolean, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        })

        col.addView(text("Walllock", 34f, bold = true))
        col.addView(text(
            "Full-screen album art on your lock screen while music plays. Tap the cover to make it " +
                "big, tap again to shrink it, swipe up to unlock.",
            15f, grey = true
        ).apply { setPadding(0, dp(8), 0, dp(24)) })

        col.addView(header("Permissions"))
        permission(col, "Notification access", "Lets Walllock see what's playing.", {
            WalllockListener.hasAccess(this)
        }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permission(col, "Notifications", "Used to open the cover screen when the screen turns off.", {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            permission(col, "Full-screen alerts", "Android 14 and newer: lets it appear over the lock screen.", {
                LockActivity.canUseFullScreen(this)
            }) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                )
            }
        }
        if (Build.VERSION.SDK_INT < 35) {
            permission(col, "Display over other apps", "Opens the cover screen instantly on Android 14 and older.", {
                Settings.canDrawOverlays(this)
            }) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        col.addView(text(
            "On Android 13+, if a switch is greyed out: Settings → Apps → Walllock → ⋮ → Allow restricted settings.",
            13f, grey = true
        ).apply { setPadding(0, dp(4), 0, dp(20)) })

        col.addView(header("Options"))
        toggle(col, "Show on lock screen", prefs.enabled) { prefs.enabled = it }
        toggle(col, "Keep showing for 10 minutes after pause", prefs.showPaused) { prefs.showPaused = it }
        toggle(col, "Open with the big cover", prefs.expanded) { prefs.expanded = it }

        col.addView(button("Preview") {
            if (NowPlaying.current == null) {
                toastLine("Play some music first, then try again.")
            } else {
                startActivity(Intent(this, LockActivity::class.java))
            }
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
    }

    override fun onResume() {
        super.onResume()
        for ((granted, action) in rows) {
            val ok = granted()
            action.text = if (ok) "ON" else "ALLOW"
            action.alpha = if (ok) 0.5f else 1f
            action.isEnabled = !ok
        }
    }

    // ---- Little builders. ------------------------------------------------------------------------

    private fun permission(col: LinearLayout, title: String, detail: String, granted: () -> Boolean, ask: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 17f))
        texts.addView(text(detail, 13f, grey = true))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = button("ALLOW") { ask() }
        row.addView(action)
        col.addView(row)
        rows += granted to action
    }

    private fun toggle(col: LinearLayout, title: String, on: Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = title
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = on
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        col.addView(sw)
    }

    private fun header(s: String) = text(s.uppercase(), 13f, bold = true, grey = true).apply {
        setPadding(0, dp(16), 0, dp(4))
        letterSpacing = 0.08f
    }

    private fun text(s: String, size: Float, bold: Boolean = false, grey: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(if (grey) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(s: String, click: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(0xFF000000.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xFFFFFFFF.toInt())
        }
        setPadding(dp(18), dp(10), dp(18), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { click() }
    }

    private fun toastLine(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
