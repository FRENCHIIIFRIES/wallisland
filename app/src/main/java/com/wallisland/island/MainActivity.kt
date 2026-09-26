package com.wallisland.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Settings, built in code so every pixel follows the dot-matrix look. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var masterToggle: NToggle
    private var cameraText: TextView? = null
    private val permissionRows = mutableListOf<PermissionRow>()

    private class PermissionRow(val granted: () -> Boolean, val dot: View, val action: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 28) {
            // Lets this window's insets report the camera cut-out so we can measure it.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

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

        buildHeader(col)
        buildSetup(col)
        buildTry(col)
        buildShow(col)
        buildBehaviour(col)
        buildSize(col)
        buildFooter(col)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Insets are dispatched on the first layout pass, just after attach.
        window.decorView.post { measureCamera() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        IslandService.start(this)
    }

    // ---- Sections ----------------------------------------------------------------------------------------

    private fun buildHeader(col: LinearLayout) {
        col.addView(TextView(this).apply {
            text = "WALLISLAND"
            typeface = Look.dot(context)
            fontVariationSettings = "'wght' 900, 'ROND' 100"
            textSize = 40f
            setTextColor(Look.WHITE)
            includeFontPadding = false
        })
        col.addView(TextView(this).apply {
            text = "A dynamic island, in dots."
            typeface = Look.mono(context)
            textSize = 13f
            setTextColor(Look.GREY)
            setPadding(0, dp(6), 0, dp(22))
        })

        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label("ISLAND"))
        statusText = TextView(this).apply {
            typeface = Look.dot(context)
            fontVariationSettings = "'wght' 900, 'ROND' 100"
            textSize = 26f
            setTextColor(Look.WHITE)
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        }
        texts.addView(statusText)
        statusDetail = subView("").apply { setPadding(0, dp(6), 0, 0) }
        texts.addView(statusDetail)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        masterToggle = NToggle(this).apply {
            set(prefs.enabled)
            onChange = { on ->
                prefs.enabled = on
                if (on) IslandService.start(this@MainActivity) else IslandService.stop(this@MainActivity)
                refresh()
            }
        }
        row.addView(masterToggle)
        card.addView(row)
        col.addView(card)
    }

    private fun buildSetup(col: LinearLayout) {
        col.addView(sectionLabel("SETUP"))
        val card = card()
        card.addView(permissionRow(
            "Draw over apps", "Needed unless \"Show above status bar\" is on. Lets the island float over apps.",
            granted = { Settings.canDrawOverlays(this) },
        ) {
            startSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        card.addView(divider())
        card.addView(permissionRow(
            "Show above status bar",
            "Recommended. Accessibility → Wallisland. Puts the island over the status bar icons. " +
                "It doesn't read your screen.",
            granted = { IslandAccessibilityService.isEnabled(this) },
        ) { startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        card.addView(divider())
        card.addView(permissionRow(
            "Notification access",
            "For notifications and now playing. Greyed out? App info → ⋮ → Allow restricted settings.",
            granted = { IslandNotificationListener.isEnabled(this) },
        ) { openListenerSettings() })
        card.addView(divider())
        card.addView(permissionRow(
            "Unrestricted battery", "Stops the system from killing the island in the background.",
            granted = { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
        ) { requestBatteryExemption() })
        if (Build.VERSION.SDK_INT >= 33) {
            card.addView(divider())
            card.addView(permissionRow(
                "Status notification", "Optional. Shows a quiet \"Island is on\" notification.",
                granted = { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) })
        }
        col.addView(card)
    }

    private fun buildTry(col: LinearLayout) {
        col.addView(sectionLabel("TRY IT"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun demo(label: String, action: String) = pillButton(this, label) {
            if (!IslandService.canHost(this)) {
                toast("Allow \"Draw over apps\" first")
            } else {
                IslandService.start(this, action)
            }
        }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } }
        row.addView(demo("Notice", IslandService.ACTION_DEMO_NOTICE), lp())
        row.addView(demo("Music", IslandService.ACTION_DEMO_MEDIA), lp())
        row.addView(demo("Charge", IslandService.ACTION_DEMO_CHARGE), lp())
        row.addView(demo("Call", IslandService.ACTION_DEMO_CALL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(hint("Tap music in the island to expand it. Swipe up to dismiss, long-press the empty pill for settings."))
    }

    private fun buildShow(col: LinearLayout) {
        col.addView(sectionLabel("SHOW"))
        val card = card()
        card.addView(toggleRow("Now playing", "Album art, controls and a dot equaliser", prefs.showMedia) { prefs.showMedia = it })
        card.addView(divider())
        card.addView(toggleRow("Notifications", "Pops open for alerts that would make a sound", prefs.showNotifications) { prefs.showNotifications = it })
        card.addView(divider())
        card.addView(toggleRow("Charging & low battery", null, prefs.showCharging) { prefs.showCharging = it })
        card.addView(divider())
        card.addView(toggleRow("Ring / vibrate / silent", null, prefs.showRinger) { prefs.showRinger = it })
        card.addView(divider())
        card.addView(toggleRow("Idle pill", "Keep a small pill around the camera when nothing's happening", prefs.showIdle) { prefs.showIdle = it })
        col.addView(card)
    }

    private fun buildBehaviour(col: LinearLayout) {
        col.addView(sectionLabel("BEHAVIOUR"))
        val card = card()
        card.addView(toggleRow("Dot art", "Render album art and avatars as halftone dots", prefs.dotArt) { prefs.dotArt = it })
        card.addView(divider())
        card.addView(toggleRow("Hide in full screen", "Videos and games", prefs.hideFullscreen) { prefs.hideFullscreen = it })
        card.addView(divider())
        card.addView(toggleRow("Hide in landscape", null, prefs.hideLandscape) { prefs.hideLandscape = it })
        card.addView(divider())
        card.addView(toggleRow("Haptics", null, prefs.haptics) { prefs.haptics = it })
        col.addView(card)
    }

    private fun buildSize(col: LinearLayout) {
        col.addView(sectionLabel("SIZE & POSITION"))
        val card = card()
        card.addView(toggleRow("Snap to camera", "Centre on the front camera cut-out", prefs.autoAlign) { prefs.autoAlign = it; preview() })
        val camRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), dp(16))
        }
        cameraText = subView("Looking for the camera…")
        camRow.addView(cameraText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        camRow.addView(pillButton(this, "Fit to camera") { fitToCamera() })
        card.addView(camRow)
        card.addView(divider())
        card.addView(sliderRow("Width", 40, 220, prefs.width, "dp") { prefs.width = it })
        card.addView(sliderRow("Height", 18, 48, prefs.height, "dp") { prefs.height = it })
        card.addView(sliderRow("Move left / right", -60, 60, prefs.offsetX, "dp") { prefs.offsetX = it })
        card.addView(sliderRow("Move up / down", -30, 40, prefs.offsetY, "dp") { prefs.offsetY = it })
        card.addView(sliderRow("Notification time", 2, 10, prefs.noticeSeconds, "s", live = false) { prefs.noticeSeconds = it })
        val reset = pillButton(this, "Reset size") {
            prefs.width = Prefs.DEFAULT_WIDTH; prefs.height = Prefs.DEFAULT_HEIGHT; prefs.offsetX = 0; prefs.offsetY = 0; prefs.noticeSeconds = 4
            recreate()
        }
        card.addView(FrameLayout(this).apply {
            setPadding(dp(20), dp(4), dp(20), dp(18))
            addView(reset, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        col.addView(card)
    }

    private fun buildFooter(col: LinearLayout) {
        col.addView(TextView(this).apply {
            text = "Doto & Space Mono · SIL Open Font License\nv${packageManager.getPackageInfo(packageName, 0).versionName}"
            typeface = Look.mono(context)
            textSize = 11f
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
    }

    // ---- State -------------------------------------------------------------------------------------------

    private val refreshLater = Runnable {
        if (prefs.enabled && IslandService.canHost(this)) statusDetail.text = IslandService.status
    }

    private fun refresh() {
        val overlay = IslandService.canHost(this)
        statusText.text = when {
            !overlay -> "SETUP"
            prefs.enabled -> "ON"
            else -> "OFF"
        }
        statusText.setTextColor(if (!overlay) Look.RED else Look.WHITE)
        statusDetail.text = when {
            !overlay -> "Allow \"Show above status bar\" or \"Draw over apps\" below."
            !prefs.enabled -> "Turn it on with the switch."
            else -> IslandService.status
        }
        // The service reports back a moment after it starts; check again shortly.
        statusDetail.removeCallbacks(refreshLater)
        statusDetail.postDelayed(refreshLater, 800)
        masterToggle.set(prefs.enabled)
        for (row in permissionRows) {
            val ok = row.granted()
            (row.dot.background as GradientDrawable).setColor(if (ok) Look.WHITE else Look.RED)
            row.action.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    private fun preview() = IslandService.start(this, IslandService.ACTION_PREVIEW)

    /** The camera hole in this window, which spans the whole display in portrait. */
    private var camera: android.graphics.Rect? = null

    private fun measureCamera() {
        val screenW = resources.displayMetrics.widthPixels
        val cam = Camera.fromInsets(window.decorView.rootWindowInsets, screenW)
        camera = cam
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v / d).roundToInt()
        cameraText?.text = if (cam == null) {
            "No camera cut-out reported. Centre it with the sliders below."
        } else {
            prefs.cameraX = cam.centerX()
            prefs.cameraY = cam.centerY()
            prefs.cameraScreenW = screenW
            val off = px(cam.centerX() - screenW / 2)
            val side = when {
                off == 0 -> "dead centre"
                off < 0 -> "${-off}dp left of centre"
                else -> "${off}dp right of centre"
            }
            "Camera: $side, ${px(cam.centerY())}dp from the top, ${px(cam.width())}dp wide. Screen ${px(screenW)}dp."
        }
    }

    /** Sizes the idle pill to hug the camera with an even margin, and clears manual nudges. */
    private fun fitToCamera() {
        val cam = camera
        if (cam == null) {
            toast("No camera cut-out found on this screen")
            return
        }
        val d = resources.displayMetrics.density
        val hole = cam.height().coerceAtLeast(cam.width()) / d
        val h = (hole + 20f).roundToInt().coerceIn(18, 48)
        prefs.height = h
        prefs.width = (h * 2.1f).roundToInt().coerceIn(40, 220)
        prefs.offsetX = 0
        prefs.offsetY = 0
        prefs.autoAlign = true
        preview()
        recreate()
    }

    private fun openListenerSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, IslandNotificationListener::class.java).flattenToString(),
                )
            if (startSafely(i)) return
        }
        startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!startSafely(direct)) startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun startSafely(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (_: Exception) {
        false
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- Building blocks ---------------------------------------------------------------------------------

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Look.SURFACE)
            cornerRadius = dp(24f)
            setStroke(dp(1), Look.LINE)
        }
        clipToOutline = true
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        typeface = Look.dot(context)
        fontVariationSettings = "'wght' 800, 'ROND' 100"
        textSize = 13f
        letterSpacing = 0.1f
        setTextColor(Look.GREY)
        includeFontPadding = false
    }

    private fun sectionLabel(text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(30), 0, dp(12))
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.RED) }
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(10) })
        addView(label(text))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        typeface = Look.mono(context)
        textSize = 11.5f
        setTextColor(Look.GREY)
        setPadding(dp(6), dp(12), dp(6), 0)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Look.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(20); marginEnd = dp(20)
        }
    }

    private fun titleView(text: String) = TextView(this).apply {
        this.text = text
        typeface = Look.mono(context)
        textSize = 14.5f
        setTextColor(Look.WHITE)
    }

    private fun subView(text: String) = TextView(this).apply {
        this.text = text
        typeface = Look.mono(context)
        textSize = 11.5f
        setTextColor(Look.GREY)
        setPadding(0, dp(2), 0, 0)
    }

    private fun toggleRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(16), 0) }
        texts.addView(titleView(title))
        if (sub != null) texts.addView(subView(sub))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = NToggle(this).apply {
            set(value)
            this.onChange = onChange
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.performClick() }
        return row
    }

    private fun sliderRow(title: String, min: Int, max: Int, value: Int, unit: String, live: Boolean = true, onSet: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(10), dp(2))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        top.addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = TextView(this).apply {
            typeface = Look.dot(context)
            fontVariationSettings = "'wght' 900, 'ROND' 100"
            textSize = 16f
            setTextColor(Look.WHITE)
            text = "$value$unit"
        }
        top.addView(valueText)
        wrap.addView(top)
        val slider = NSlider(this, min, max).apply {
            set(value)
            onChange = { v ->
                valueText.text = "$v$unit"
                onSet(v)
                if (live) preview()
            }
        }
        wrap.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -dp(10)
        })
        return wrap
    }

    private fun permissionRow(title: String, sub: String, granted: () -> Boolean, onGrant: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.RED) }
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        texts.addView(titleView(title))
        texts.addView(subView(sub).apply { ellipsize = TextUtils.TruncateAt.END })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = pillButton(this, "Allow", filled = true, onClick = onGrant)
        row.addView(action)
        permissionRows += PermissionRow(granted, dot, action)
        return row
    }
}
