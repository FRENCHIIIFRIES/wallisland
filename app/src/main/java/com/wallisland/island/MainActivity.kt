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
    private var unlockLogText: TextView? = null
    private lateinit var masterToggle: NToggle
    private var cameraText: TextView? = null
    private val permissionRows = mutableListOf<PermissionRow>()

    private class PermissionRow(val granted: () -> Boolean, val dot: View, val action: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Look.accent = prefs.accent
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
        buildEssentialKey(col)
        buildTroubleshoot(col)
        buildUpdates(col)
        buildFooter(col)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Insets are dispatched on the first layout pass, just after attach.
        window.decorView.post { measureCamera() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
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
        if (Build.VERSION.SDK_INT >= 31) {
            card.addView(divider())
            card.addView(permissionRow(
                "Nearby devices", "Optional. Lets the island show your earbuds' battery when they connect.",
                granted = { checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED },
            ) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2) })
        }
        card.addView(divider())
        card.addView(permissionRow(
            "Calendar", "Optional. Lets the island count down to your next event.",
            granted = { checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        ) { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 3) })
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
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun small(label: String, action: String) = demo(label, action).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) }
        row2.addView(small("Timer", IslandService.ACTION_DEMO_TIMER), lp())
        row2.addView(small("Maps", IslandService.ACTION_DEMO_NAV), lp())
        row2.addView(small("Files", IslandService.ACTION_DEMO_PROGRESS), lp())
        row2.addView(small("Unlock", IslandService.ACTION_DEMO_UNLOCK), lp())
        row2.addView(small("Buds", IslandService.ACTION_DEMO_BUDS),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row2)
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row3.addView(small("Volume", IslandService.ACTION_DEMO_VOLUME), lp())
        row3.addView(small("DND", IslandService.ACTION_DEMO_DND), lp())
        row3.addView(small("Reply", IslandService.ACTION_DEMO_REPLY), lp())
        row3.addView(small("Event", IslandService.ACTION_DEMO_EVENT), lp())
        row3.addView(small("Peek", IslandService.ACTION_DEMO_PEEK),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row3)
        col.addView(hint(
            "Tap music to open the player; swipe it left or right to skip. Long-press the island for quick " +
                "toggles (torch, sound, rotation, focus). Double-tap the empty pill for the time and battery. " +
                "Swipe up to dismiss."
        ))
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
        card.addView(toggleRow("Live activities", "Timers, Maps directions and downloads", prefs.showLive) { prefs.showLive = it })
        card.addView(divider())
        card.addView(toggleRow("Unlock animation", "A dot padlock opens when you unlock", prefs.showUnlock) { prefs.showUnlock = it })
        unlockLogText = subView(IslandHub.unlockLog).apply {
            setPadding(dp(20), 0, dp(20), dp(14))
            textSize = 10.5f
        }
        card.addView(unlockLogText)
        card.addView(divider())
        card.addView(toggleRow("Earbuds", "Battery when Bluetooth headphones connect", prefs.showBuds) { prefs.showBuds = it })
        card.addView(divider())
        card.addView(toggleRow("Volume in the island", "Replaces the volume panel. Needs \"Show above status bar\"", prefs.volumeInIsland) { prefs.volumeInIsland = it })
        card.addView(divider())
        card.addView(toggleRow("Toggle confirmations", "Do Not Disturb, Wi-Fi and Bluetooth on/off", prefs.showToggleChanges) { prefs.showToggleChanges = it })
        card.addView(divider())
        card.addView(toggleRow("Edge light", "Dots race around the island when a notification arrives", prefs.edgeLight) { prefs.edgeLight = it })
        card.addView(divider())
        card.addView(toggleRow("Calendar events", "A countdown 10 minutes before each event", prefs.showEvents) { prefs.showEvents = it })
        card.addView(divider())
        card.addView(toggleRow("Idle pill", "Keep a small pill around the camera when nothing's happening", prefs.showIdle) { prefs.showIdle = it })
        card.addView(divider())
        card.addView(linkRow("Apps that can pop up", "Choose which apps' notifications open the island") {
            startActivity(Intent(this, AppsActivity::class.java))
        })
        col.addView(card)
    }

    /** A tappable row with a chevron, for opening another screen. */
    private fun linkRow(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(16), 0) }
        texts.addView(titleView(title))
        texts.addView(subView(sub))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(Look.GREY)
        })
        return row
    }

    /** Accent colour: a row of dot swatches; the chosen one gets a ring. */
    private fun accentRow(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        wrap.addView(titleView("Accent colour"))
        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        for ((name, color) in Look.ACCENTS) {
            val chosen = prefs.accent == color
            swatches.addView(View(this).apply {
                contentDescription = name
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    if (chosen) setStroke(dp(3), Look.BLACK)
                }
                foreground = if (chosen) android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(dp(2), Look.WHITE)
                } else null
                setOnClickListener {
                    prefs.accent = color
                    Look.accent = color
                    preview()
                    recreate()
                }
            }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(8) })
        }
        wrap.addView(swatches)
        return wrap
    }

    private fun buildBehaviour(col: LinearLayout) {
        col.addView(sectionLabel("BEHAVIOUR"))
        val card = card()
        card.addView(accentRow())
        card.addView(divider())
        card.addView(toggleRow("Dot art", "Render album art and avatars as halftone dots", prefs.dotArt) { prefs.dotArt = it })
        card.addView(divider())
        card.addView(toggleRow("Colour dots", "Dot art and the equaliser take their colours from the album cover", prefs.dotColor) { prefs.dotColor = it })
        card.addView(divider())
        card.addView(toggleRow("Hide in full screen", "Videos and games", prefs.hideFullscreen) { prefs.hideFullscreen = it })
        card.addView(divider())
        card.addView(toggleRow("Hide in landscape", null, prefs.hideLandscape) { prefs.hideLandscape = it })
        card.addView(divider())
        card.addView(toggleRow("Hide while recording", "Camera and recorder apps. Stays during video calls", prefs.hideWhileCapturing) { prefs.hideWhileCapturing = it })
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
        card.addView(sliderRow("Opacity", 40, 100, prefs.opacity, "%") { prefs.opacity = it })
        card.addView(sliderRow("Notification time", 2, 10, prefs.noticeSeconds, "s", live = false) { prefs.noticeSeconds = it })
        val reset = pillButton(this, "Reset size") {
            prefs.width = Prefs.DEFAULT_WIDTH; prefs.height = Prefs.DEFAULT_HEIGHT
            prefs.offsetX = Prefs.DEFAULT_OFFSET_X; prefs.offsetY = Prefs.DEFAULT_OFFSET_Y
            prefs.noticeSeconds = Prefs.DEFAULT_NOTICE_SECONDS
            prefs.opacity = Prefs.DEFAULT_OPACITY
            recreate()
        }
        card.addView(FrameLayout(this).apply {
            setPadding(dp(20), dp(4), dp(20), dp(18))
            addView(reset, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        col.addView(card)
    }

    // ---- Essential Key ---------------------------------------------------------------------------------------

    private lateinit var keyStatus: TextView

    private fun keyStatusText(): String {
        val code = prefs.essentialKey
        return if (code < 0) "Not set up. Tap Learn, then press your Essential Key."
        else "Learned: ${android.view.KeyEvent.keyCodeToString(code)} ($code)"
    }

    private fun buildEssentialKey(col: LinearLayout) {
        col.addView(sectionLabel("ESSENTIAL KEY"))
        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        texts.addView(titleView("Remap the key"))
        keyStatus = subView(keyStatusText())
        texts.addView(keyStatus)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pillButton(this, "Learn") { learnKey() })
        card.addView(row)
        card.addView(divider())
        card.addView(keyActionRow("Short press", short = true))
        card.addView(divider())
        card.addView(keyActionRow("Long press", short = false))
        col.addView(card)
        col.addView(hint(
            "Uses \"Show above status bar\" (Accessibility). If Learn never sees the key, Nothing OS handles it " +
                "before apps can, and it can't be remapped. Leave both on Default to keep Essential Space."
        ))
    }

    private val learnTimeout = Runnable {
        if (!IslandAccessibilityService.learning) return@Runnable
        IslandAccessibilityService.learning = false
        keyStatus.text = "Didn't see a key press. Your phone may not pass the Essential Key to apps."
        keyStatus.setTextColor(Look.accent)
    }

    private fun learnKey() {
        if (IslandAccessibilityService.instance == null) {
            toast("Turn on \"Show above status bar\" (Accessibility) first")
            startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        IslandAccessibilityService.onLearned = { code ->
            keyStatus.removeCallbacks(learnTimeout)
            keyStatus.text = keyStatusText()
            keyStatus.setTextColor(Look.GREY)
            toast("Got it: ${android.view.KeyEvent.keyCodeToString(code)}")
        }
        IslandAccessibilityService.learning = true
        keyStatus.text = "Press your Essential Key now…"
        keyStatus.setTextColor(Look.WHITE)
        keyStatus.removeCallbacks(learnTimeout)
        keyStatus.postDelayed(learnTimeout, 10_000)
    }

    private fun actionLabel(short: Boolean): String {
        val a = KeyAction.of(if (short) prefs.essentialShort else prefs.essentialLong)
        if (a != KeyAction.APP) return a.label
        val pkg = if (short) prefs.essentialShortApp else prefs.essentialLongApp
        return "Open " + IslandNotificationListener.appLabel(this, pkg)
    }

    private fun keyActionRow(title: String, short: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleView(title))
        val sub = subView(actionLabel(short))
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(Look.GREY) })
        row.setOnClickListener { chooseKeyAction(short) { sub.text = actionLabel(short) } }
        return row
    }

    private fun chooseKeyAction(short: Boolean, done: () -> Unit) {
        val actions = KeyAction.values()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (short) "Short press" else "Long press")
            .setItems(actions.map { it.label }.toTypedArray()) { _, i ->
                val a = actions[i]
                if (a == KeyAction.APP) {
                    chooseApp { pkg ->
                        if (short) { prefs.essentialShort = a.name; prefs.essentialShortApp = pkg }
                        else { prefs.essentialLong = a.name; prefs.essentialLongApp = pkg }
                        done()
                    }
                } else {
                    if (short) prefs.essentialShort = a.name else prefs.essentialLong = a.name
                    done()
                }
            }
            .show()
    }

    private fun chooseApp(picked: (String) -> Unit) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != packageName }
            .map { it to IslandNotificationListener.appLabel(this, it) }
            .sortedBy { it.second.lowercase() }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Open which app?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> picked(apps[i].first) }
            .show()
    }

    /** Shows exactly what the island sees, so a screenshot is enough to fix detection problems. */
    private fun buildTroubleshoot(col: LinearLayout) {
        col.addView(sectionLabel("TROUBLESHOOT"))
        val card = card()
        val report = TextView(this).apply {
            typeface = Look.mono(context)
            textSize = 10.5f
            setTextColor(Look.GREY)
            setTextIsSelectable(true)
            setPadding(dp(20), 0, dp(20), dp(16))
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        texts.addView(titleView("What the island sees"))
        texts.addView(subView("Start a timer or Maps directions, then tap Check and screenshot the result."))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pillButton(this, "Check") {
            report.text = troubleshootReport()
            report.visibility = View.VISIBLE
        })
        card.addView(row)
        card.addView(report)
        col.addView(card)
    }

    private fun troubleshootReport(): String = buildString {
        append("ISLAND\n").append(if (prefs.enabled) IslandService.status else "Off").append("\n\n")
        append("LIVE ACTIVITIES (timers, Maps, downloads)\n")
        val listener = IslandNotificationListener.instance
        append(listener?.describeOngoing() ?: "Notification access is off, or the listener isn't connected yet.")
        append("\n\nUNLOCK\n").append(IslandHub.unlockLog)
        append("\n\nHEADPHONES\n").append(IslandHub.budsLog)
        append("\n\nKEYS\nLast key seen: ").append(IslandAccessibilityService.lastKey)
        append(" · Essential Key: ").append(
            if (prefs.essentialKey < 0) "not learned" else android.view.KeyEvent.keyCodeToString(prefs.essentialKey)
        )
        append(" · Accessibility: ").append(if (IslandAccessibilityService.instance != null) "on" else "off")
        append("\n\nAndroid ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER)
            .append(' ').append(Build.MODEL).append(" · build ").append(Updater.currentBuild(this@MainActivity))
    }

    private lateinit var updateText: TextView
    private lateinit var updateButton: TextView
    private var pendingRelease: Updater.Release? = null
    private var updating = false

    private fun buildUpdates(col: LinearLayout) {
        col.addView(sectionLabel("UPDATES"))
        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        texts.addView(titleView("Build ${Updater.currentBuild(this)}"))
        updateText = subView("Checking for updates…")
        texts.addView(updateText)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updateButton = pillButton(this, "Check") { onUpdateButton() }
        row.addView(updateButton)
        card.addView(row)
        col.addView(card)
        checkForUpdate()
    }

    private fun checkForUpdate() {
        if (updating) return
        updateText.text = "Checking for updates…"
        Updater.check(this) { result ->
            if (isFinishing || updating) return@check
            when (result) {
                is Updater.Check.Available -> {
                    pendingRelease = result.release
                    updateText.text = "Build ${result.release.build} is out."
                    updateText.setTextColor(Look.WHITE)
                    setUpdateButton("Update", filled = true)
                }
                Updater.Check.UpToDate -> {
                    pendingRelease = null
                    updateText.text = "You're on the latest build."
                    updateText.setTextColor(Look.GREY)
                    setUpdateButton("Check", filled = false)
                }
                is Updater.Check.Failed -> {
                    pendingRelease = null
                    updateText.text = "Couldn't check: ${result.reason}."
                    updateText.setTextColor(Look.GREY)
                    setUpdateButton("Retry", filled = false)
                }
            }
        }
    }

    private fun onUpdateButton() {
        val release = pendingRelease
        if (release == null) {
            checkForUpdate()
            return
        }
        if (updating) return
        if (!Updater.ensureCanInstall(this)) {
            toast("Allow Wallisland to install updates, then tap Update again")
            return
        }
        updating = true
        updateButton.isEnabled = false
        updateText.text = "Downloading…"
        Updater.install(this, release,
            progress = { pct ->
                updateText.text = if (pct >= 0) "Downloading… $pct%" else "Downloading…"
            },
            committed = {
                // The system now asks to confirm; if it's cancelled, the button works again.
                updating = false
                updateButton.isEnabled = true
                updateText.text = "Build ${release.build} is ready. Confirm the install."
            },
            failed = { reason ->
                updating = false
                updateButton.isEnabled = true
                updateText.text = "Update failed: $reason"
            },
        )
    }

    private fun setUpdateButton(label: String, filled: Boolean) {
        val fresh = pillButton(this, label, filled) { onUpdateButton() }
        val parent = updateButton.parent as ViewGroup
        val idx = parent.indexOfChild(updateButton)
        parent.removeViewAt(idx)
        parent.addView(fresh, idx)
        updateButton = fresh
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
        statusText.setTextColor(if (!overlay) Look.accent else Look.WHITE)
        statusDetail.text = when {
            !overlay -> "Allow \"Show above status bar\" or \"Draw over apps\" below."
            !prefs.enabled -> "Turn it on with the switch."
            else -> IslandService.status
        }
        // The service reports back a moment after it starts; check again shortly.
        unlockLogText?.text = IslandHub.unlockLog
        statusDetail.removeCallbacks(refreshLater)
        statusDetail.postDelayed(refreshLater, 800)
        masterToggle.set(prefs.enabled)
        for (row in permissionRows) {
            val ok = row.granted()
            (row.dot.background as GradientDrawable).setColor(if (ok) Look.WHITE else Look.accent)
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
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.accent) }
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
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.accent) }
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
