package com.wallisland.island

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pick which apps' notifications may open the island. Off means that app never pops up. */
class AppsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    private data class App(val pkg: String, val label: String, val icon: Drawable?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Look.accent = prefs.accent
        val scroll = ScrollView(this).apply {
            background = DotGridDrawable(dp(18f), dp(1f))
            isVerticalScrollBarEnabled = false
            fitsSystemWindows = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(TextView(this).apply {
            text = "APPS"
            typeface = Look.dot(context)
            fontVariationSettings = "'wght' 900, 'ROND' 100"
            textSize = 34f
            setTextColor(Look.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "Turn an app off to stop its notifications opening the island. Calls, music and timers still show."
            typeface = Look.mono(context)
            textSize = 12.5f
            setTextColor(Look.GREY)
            setPadding(0, dp(6), 0, dp(20))
        })
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Look.SURFACE)
                cornerRadius = dp(24f)
                setStroke(dp(1), Look.LINE)
            }
        }
        col.addView(list)
        list.addView(TextView(this).apply {
            text = "Loading apps…"
            typeface = Look.mono(context)
            setTextColor(Look.GREY)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        })

        Thread {
            val apps = loadApps()
            runOnUiThread { if (!isFinishing) show(apps) }
        }.start()
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        return infos.map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { pkg ->
                val ai = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: Exception) {
                    null
                }
                App(pkg, ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg, ai?.let { pm.getApplicationIcon(it) })
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun show(apps: List<App>) {
        list.removeAllViews()
        val blocked = prefs.blockedApps.toMutableSet()
        apps.forEachIndexed { i, app ->
            if (i > 0) list.addView(View(this).apply {
                setBackgroundColor(Look.LINE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(64); marginEnd = dp(20)
                }
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            row.addView(ImageView(this).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(12)
            })
            row.addView(TextView(this).apply {
                text = app.label
                typeface = Look.mono(context)
                textSize = 14f
                setTextColor(Look.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            val toggle = NToggle(this).apply {
                set(app.pkg !in blocked)
                onChange = { on ->
                    if (on) blocked -= app.pkg else blocked += app.pkg
                    prefs.blockedApps = blocked
                }
            }
            row.addView(toggle)
            row.setOnClickListener { toggle.performClick() }
            list.addView(row)
        }
    }
}
