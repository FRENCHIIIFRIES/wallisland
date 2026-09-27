package com.wallisland.island

import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Typing your own reply from the island. An overlay can't take the keyboard, so this is a small see-through
 * screen with an island-style card at the top: who wrote, what they said, a text box and Send. The reply
 * goes back through the app's own Reply action, as the one-tap replies do.
 */
class ReplyActivity : Activity() {

    private var target: Pair<Notice, NoticeAction>? = null

    /** Writing the pinned note rather than a reply. */
    private var noteMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteMode = pendingNote
        pendingNote = false
        val t = pending
        pending = null
        if (!noteMode && t == null) {
            finish()
            return
        }
        target = t
        val current = Prefs(this).pinnedNote
        // The note card reuses the reply card's look: a stand-in "notice" for its header.
        val notice = t?.first ?: Notice(
            key = "note", pkg = packageName, appName = "Note", title = "Pin a note to the island",
            text = if (current.isBlank()) "Tick it off from the long-press panel when it's done" else "Clear the text to unpin it",
            icon = null, avatar = null, intent = null, autoCancel = false,
        )
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = if (notice.color != 0) notice.color or (0xFF shl 24) else Look.accent

        val root = FrameLayout(this).apply {
            // Tapping the dimmed space around the card cancels.
            setOnClickListener { finish() }
            setBackgroundColor(0x66000000)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Look.BLACK)
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), 0x22FFFFFF)
            }
            isClickable = true // swallow taps so they don't cancel
            elevation = dp(8).toFloat()
        }

        // Header: "● WHATSAPP", like the island's own.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(android.view.View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) })
        header.addView(TextView(this).apply {
            text = notice.appName.uppercase()
            typeface = Look.dot(context)
            textSize = 12f
            setTextColor(Look.GREY)
            letterSpacing = 0.08f
        })
        card.addView(header)

        card.addView(TextView(this).apply {
            text = notice.title.ifEmpty { notice.appName }
            typeface = Look.monoBold(context)
            textSize = 16f
            setTextColor(Look.WHITE)
            setPadding(0, dp(10), 0, 0)
        })
        if (notice.text.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = notice.text
                typeface = Look.mono(context)
                textSize = 13f
                setTextColor(Look.GREY)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, dp(12))
            })
        }

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = if (noteMode) "Buy milk, call mum…" else "Reply to ${notice.title.ifEmpty { notice.appName }}"
            if (noteMode) {
                setText(current)
                setSelection(current.length)
            }
            typeface = Look.mono(context)
            textSize = 14f
            setTextColor(Look.WHITE)
            setHintTextColor(0xFF6B6B6B.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Look.RAISED)
                cornerRadius = dp(22).toFloat()
            }
            if (Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply {
                setColor(accent)
                setSize(dp(2), dp(18))
            }
        }
        val send = TextView(this).apply {
            text = if (noteMode) "PIN" else "SEND"
            typeface = Look.monoBold(context)
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(Look.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(22).toFloat()
            }
            setOnClickListener { send(input.text.toString()) }
        }
        input.setOnEditorActionListener { _, id, ev ->
            val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_SEND || enter) {
                send(input.text.toString())
                true
            } else {
                false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
        card.addView(row)

        // Just under the status bar, where the island opens from.
        val top = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else dp(32) }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(10), top + dp(6), dp(10), 0)
        })
        setContentView(root)
        input.requestFocus()
        Haptics.tick(this)
    }

    private fun send(text: String) {
        val msg = text.trim()
        if (noteMode) {
            val prefs = Prefs(this)
            val had = prefs.pinnedNote.isNotBlank()
            prefs.pinnedNote = msg.take(80)
            IslandService.current?.showNoteChange(pinned = msg.isNotEmpty(), had = had)
            finish()
            return
        }
        if (msg.isEmpty()) return
        val (_, action) = target ?: return finish()
        val input = action.reply ?: return finish()
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, msg) })
        try {
            action.intent.send(this, 0, fill)
            IslandService.current?.showSent()
        } catch (_: PendingIntent.CanceledException) {
            toast("That notification has gone, so the reply couldn't be sent")
        }
        finish()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    companion object {
        /** The notification and its Reply action, handed over in-process (they don't need to survive a restart). */
        @Volatile var pending: Pair<Notice, NoticeAction>? = null
        @Volatile private var pendingNote = false

        /** The same card, for writing (or clearing) the note pinned to the island. */
        fun openNote(ctx: android.content.Context) {
            pendingNote = true
            ctx.startActivity(
                Intent(ctx, ReplyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }

        fun open(ctx: android.content.Context, notice: Notice, action: NoticeAction) {
            pending = notice to action
            ctx.startActivity(
                Intent(ctx, ReplyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }
    }
}
