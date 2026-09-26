package com.wallisland.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.roundToInt

/** Faint dot grid, the Nothing wallpaper staple. Drawn once per bounds change by the framework. */
class DotGridDrawable(private val pitch: Float, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Look.BLACK)
        val b = bounds
        var y = b.top + pitch / 2f
        while (y < b.bottom) {
            var x = b.left + pitch / 2f
            while (x < b.right) {
                canvas.drawCircle(x, y, radius, paint)
                x += pitch
            }
            y += pitch
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

/** Pill switch: grey outline when off, red with a white dot when on. */
class NToggle(context: Context) : View(context) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener { set(!checked, animate = true, fromUser = true) }
    }

    fun set(value: Boolean, animate: Boolean = false, fromUser: Boolean = false) {
        if (checked == value && !fromUser) {
            t = if (value) 1f else 0f
            invalidate()
            return
        }
        checked = value
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(t, if (value) 1f else 0f).apply {
                duration = 220
                interpolator = EASE
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            t = if (value) 1f else 0f
            invalidate()
        }
        if (fromUser) onChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(52), context.dp(30))
    }

    override fun onDraw(canvas: Canvas) {
        val s = context.dp(1.5f)
        rect.set(s, s, width - s, height - s)
        val r = rect.height() / 2f
        paint.style = Paint.Style.FILL
        paint.color = blend(Look.BLACK, Look.accent, t)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s
        paint.color = blend(Look.GREY, Look.accent, t)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = blend(Look.GREY, Look.WHITE, t)
        val thumbR = r - context.dp(5f)
        val x = rect.left + r + (rect.width() - 2 * r) * t
        canvas.drawCircle(x, rect.centerY(), thumbR, paint)
    }
}

/** A slider made of dots. Filled dots are white, the thumb is the red one. */
class NSlider(context: Context, private val min: Int, private val max: Int) : View(context) {
    var value = min
        private set
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(v: Int) {
        value = v.coerceIn(min, max)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(36))
    }

    private val pad get() = context.dp(10f)

    override fun onDraw(canvas: Canvas) {
        val left = pad
        val right = width - pad
        val cy = height / 2f
        val pitch = context.dp(7f)
        val count = ((right - left) / pitch).toInt().coerceAtLeast(2)
        val step = (right - left) / (count - 1)
        val frac = (value - min).toFloat() / (max - min).coerceAtLeast(1)
        val thumbX = left + (right - left) * frac
        for (i in 0 until count) {
            val x = left + i * step
            paint.color = if (x <= thumbX) Look.WHITE else Look.DOT_OFF
            canvas.drawCircle(x, cy, context.dp(1.8f), paint)
        }
        paint.color = Look.accent
        canvas.drawCircle(thumbX, cy, context.dp(8f), paint)
        paint.color = Look.BLACK
        canvas.drawCircle(thumbX, cy, context.dp(2.5f), paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke(value)
            }
        }
        return true
    }

    private fun update(x: Float) {
        val frac = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val v = (min + frac * (max - min)).roundToInt()
        if (v != value) {
            value = v
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(v)
        }
    }
}

/** Outlined (or filled) capsule button in Space Mono caps. */
fun pillButton(ctx: Context, label: String, filled: Boolean = false, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        text = label.uppercase()
        typeface = Look.monoBold(ctx)
        textSize = 12f
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        setTextColor(if (filled) Look.BLACK else Look.WHITE)
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        maxLines = 1
        val shape = GradientDrawable().apply {
            cornerRadius = ctx.dp(100f)
            if (filled) setColor(Look.WHITE) else {
                setColor(Look.BLACK)
                setStroke(ctx.dp(1), Look.WHITE)
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        isClickable = true
        setOnClickListener { onClick() }
    }

private fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).roundToInt() shl s
    return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
}
