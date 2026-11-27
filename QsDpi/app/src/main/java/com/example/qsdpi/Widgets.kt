package com.example.qsdpi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val TAU: Float = (2.0 * PI).toFloat()

/** A View with a looping 0..1 [phase] clock that pauses when the window is hidden. */
internal abstract class LoopView(
    context: Context,
    periodMs: Long,
    private val autoRun: Boolean = true,
) : View(context) {

    protected var phase = 0f
        private set

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = periodMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedFraction
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (autoRun) resumeLoop()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!autoRun) return
        if (visibility == VISIBLE) resumeLoop() else loop.pause()
    }

    private fun resumeLoop() {
        if (loop.isPaused) loop.resume() else if (!loop.isStarted) loop.start()
    }
}

/** Slowly drifting colour blobs + twinkling stars on a dark background. */
internal class AuroraView(context: Context) : LoopView(context, 45_000L) {

    private class Blob(
        val color: Int,
        val size: Float,
        val x: Float,
        val y: Float,
        val ax: Float,
        val ay: Float,
        val fx: Int,
        val fy: Int,
        val ph: Float,
    ) {
        var shader: Shader? = null
        var radius = 0f
    }

    private val blobs = listOf(
        Blob(Ui.INDIGO, 0.85f, 0.15f, 0.12f, 0.22f, 0.06f, 1, 2, 0.0f),
        Blob(Ui.CYAN, 0.65f, 0.95f, 0.40f, 0.18f, 0.10f, 2, 1, 1.7f),
        Blob(Ui.VIOLET, 0.80f, 0.10f, 0.78f, 0.25f, 0.07f, 1, 1, 3.1f),
        Blob(Ui.PINK, 0.55f, 0.90f, 0.92f, 0.15f, 0.05f, 3, 2, 4.4f),
    )

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val rnd = kotlin.random.Random(7)
    private val starCount = 36
    private val starX = FloatArray(starCount) { rnd.nextFloat() }
    private val starY = FloatArray(starCount) { rnd.nextFloat() }
    private val starR = FloatArray(starCount) { 0.6f + rnd.nextFloat() * 1.4f }
    private val starPh = FloatArray(starCount) { rnd.nextFloat() * 6.28f }
    private val starSp = IntArray(starCount) { 1 + rnd.nextInt(3) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val base = max(w, h) * 0.5f
        for (b in blobs) {
            val r = base * b.size
            b.radius = r
            b.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(Ui.alpha(b.color, 0x73), Ui.alpha(b.color, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.BG)
        for (b in blobs) {
            val x = width * (b.x + b.ax * sin(TAU * phase * b.fx + b.ph))
            val y = height * (b.y + b.ay * cos(TAU * phase * b.fy + b.ph))
            blobPaint.shader = b.shader
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, b.radius, blobPaint)
            canvas.restore()
        }
        for (i in 0 until starCount) {
            val y = (((starY[i] - phase * starSp[i]) % 1f) + 1f) % 1f
            starPaint.alpha = (30 + 110 * (0.5f + 0.5f * sin(TAU * phase * 8f + starPh[i]))).toInt()
            canvas.drawCircle(width * starX[i], height * y, dp(starR[i]), starPaint)
        }
    }
}

/** The big gauge: ring + ticks + animated number in the middle. */
internal class DpiDial(context: Context) : LoopView(context, 24_000L) {

    var minValue = 400
    var maxValue = 600
    var subtitle: String = ""
        set(v) {
            field = v
            invalidate()
        }

    private var shown = 400f
    private var numScale = 1f
    private var on = 1f

    private val valueAnim = ValueAnimator().apply {
        interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
    }
    private val bumpAnim = ValueAnimator.ofFloat(1.07f, 1f).apply {
        duration = 240
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            numScale = it.animatedValue as Float
            invalidate()
        }
    }
    private val onAnim = ValueAnimator().apply {
        duration = 350
        addUpdateListener {
            on = it.animatedValue as Float
            invalidate()
        }
    }

    private fun stroke(width: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = width
    }

    private val trackPaint = stroke().apply { color = Ui.alpha(Color.WHITE, 0x22) }
    private val arcPaint = stroke()
    private val grayArcPaint = stroke().apply { color = Ui.GRAY }
    private val tickPaint = stroke()
    private val dashPaint = stroke()
    private val dash2Paint = stroke()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        letterSpacing = 0.4f
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Ui.alpha(Color.WHITE, 0x70)
    }

    private val arcRect = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var tickColors = IntArray(TICKS + 1)

    private val tickCos = FloatArray(TICKS + 1) { cos((135.0 + 270.0 * it / TICKS) * PI / 180.0).toFloat() }
    private val tickSin = FloatArray(TICKS + 1) { sin((135.0 + 270.0 * it / TICKS) * PI / 180.0).toFloat() }

    fun setValue(v: Int, animate: Boolean = true, bump: Boolean = true, durationMs: Long = 240L) {
        val target = v.coerceIn(minValue, maxValue).toFloat()
        valueAnim.cancel()
        if (animate) {
            valueAnim.duration = durationMs
            valueAnim.setFloatValues(shown, target)
            valueAnim.start()
        } else {
            shown = target
            invalidate()
        }
        if (bump) {
            bumpAnim.cancel()
            bumpAnim.start()
        }
        contentDescription = "Panel DPI $v"
    }

    fun setActive(active: Boolean, animate: Boolean = true) {
        val target = if (active) 1f else 0f
        onAnim.cancel()
        if (animate) {
            onAnim.setFloatValues(on, target)
            onAnim.start()
        } else {
            on = target
            invalidate()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = min(MeasureSpec.getSize(widthSpec), dp(340f).toInt())
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f

        val arcR = radius * 0.80f
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        val sw = radius * 0.075f
        trackPaint.strokeWidth = sw
        arcPaint.strokeWidth = sw
        grayArcPaint.strokeWidth = sw
        arcPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Ui.CYAN, Ui.INDIGO, Ui.VIOLET, Ui.PINK, Ui.PINK),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        )
        arcPaint.setShadowLayer(radius * 0.08f, 0f, 0f, Ui.alpha(Ui.VIOLET, 0xB0))

        tickPaint.strokeWidth = dp(1.6f)
        dashPaint.strokeWidth = dp(2.4f)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(16f)), 0f)
        dash2Paint.strokeWidth = dp(1.8f)
        dash2Paint.pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(11f)), 0f)

        glowPaint.shader = RadialGradient(
            0f, 0f, radius * 0.95f,
            intArrayOf(Ui.alpha(Ui.VIOLET, 0x66), Ui.alpha(Ui.INDIGO, 0x22), Ui.alpha(Ui.INDIGO, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )

        numPaint.textSize = radius * 0.56f
        numPaint.shader = LinearGradient(
            0f, cy - radius * 0.40f, 0f, cy + radius * 0.20f,
            Color.WHITE, Ui.lerp(Color.WHITE, Ui.CYAN, 0.55f),
            Shader.TileMode.CLAMP,
        )
        labelPaint.textSize = radius * 0.095f
        subPaint.textSize = radius * 0.088f
        rangePaint.textSize = radius * 0.075f

        tickColors = IntArray(TICKS + 1) { Ui.ramp(it / TICKS.toFloat()) }
    }

    override fun onDraw(c: Canvas) {
        val e = on
        val frac = ((shown - minValue) / (maxValue - minValue).toFloat()).coerceIn(0f, 1f)

        // breathing glow behind everything
        val breathe = 0.5f + 0.5f * sin(TAU * phase * 3f)
        glowPaint.alpha = ((70 + 90 * breathe) * (0.25f + 0.75f * e)).toInt()
        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, radius * 0.95f, glowPaint)
        c.restore()

        // slowly rotating dotted rings
        dashPaint.color = Ui.alpha(Color.WHITE, (60 + 40 * e).toInt())
        c.save()
        c.rotate(phase * 360f, cx, cy)
        c.drawCircle(cx, cy, radius * 0.965f, dashPaint)
        c.restore()
        dash2Paint.color = Ui.alpha(Color.WHITE, 0x2A)
        c.save()
        c.rotate(-phase * 720f, cx, cy)
        c.drawCircle(cx, cy, radius * 0.56f, dash2Paint)
        c.restore()

        // tick marks
        for (i in 0..TICKS) {
            val long = i % 2 == 0
            val r1 = radius * (if (long) 0.615f else 0.65f)
            val r2 = radius * 0.69f
            val lit = i.toFloat() / TICKS <= frac + 0.0001f
            tickPaint.color =
                if (lit) Ui.lerp(Ui.GRAY, tickColors[i], e) else Ui.alpha(Color.WHITE, 0x30)
            c.drawLine(
                cx + r1 * tickCos[i], cy + r1 * tickSin[i],
                cx + r2 * tickCos[i], cy + r2 * tickSin[i],
                tickPaint,
            )
        }

        // gauge track + progress
        c.drawArc(arcRect, 135f, 270f, false, trackPaint)
        val sweep = max(270f * frac, 0.5f)
        c.save()
        c.rotate(135f, cx, cy)
        if (e < 1f) {
            grayArcPaint.alpha = (255 * (1f - e)).toInt()
            c.drawArc(arcRect, 0f, sweep, false, grayArcPaint)
        }
        if (e > 0f) {
            arcPaint.alpha = (255 * e).toInt()
            c.drawArc(arcRect, 0f, sweep, false, arcPaint)
        }
        c.restore()

        // knob at the end of the arc
        val ang = (135.0 + 270.0 * frac) * PI / 180.0
        val arcR = arcRect.width() / 2f
        val kx = cx + arcR * cos(ang).toFloat()
        val ky = cy + arcR * sin(ang).toFloat()
        val knobColor = Ui.lerp(Ui.GRAY, Ui.ramp(frac), e)
        knobPaint.color = Color.WHITE
        knobPaint.setShadowLayer(radius * 0.07f, 0f, 0f, Ui.alpha(knobColor, 0xE0))
        c.drawCircle(kx, ky, radius * 0.062f, knobPaint)

        // min / max labels under the open end of the gauge
        val ex = arcR * 0.7071f
        c.drawText("$minValue", cx - ex, cy + ex + radius * 0.2f, rangePaint)
        c.drawText("$maxValue", cx + ex, cy + ex + radius * 0.2f, rangePaint)

        // the number
        numPaint.alpha = (255 * (0.5f + 0.5f * e)).toInt()
        c.save()
        c.scale(numScale, numScale, cx, cy - radius * 0.02f)
        c.drawText(shown.roundToInt().toString(), cx, cy + radius * 0.16f, numPaint)
        c.restore()

        labelPaint.color = Ui.alpha(Color.WHITE, 0x96)
        c.drawText("DPI", cx, cy + radius * 0.37f, labelPaint)

        subPaint.color = Ui.alpha(Ui.lerp(Ui.GRAY, Ui.CYAN, e), 0xE6)
        c.drawText(subtitle, cx, cy + radius * 0.54f, subPaint)
    }

    private companion object {
        const val TICKS = 40
    }
}

/** Slider with gradient fill, glowing thumb and a marker for the system DPI. */
internal class GlowSlider(context: Context) : View(context) {

    var minValue = 400
    var maxValue = 600
    var marker = -1
        set(v) {
            field = v
            invalidate()
        }
    var onChange: ((Int) -> Unit)? = null
    var value = 500
        set(v) {
            val c = v.coerceIn(minValue, maxValue)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    private var on = 1f
    private var press = 0f
    private var lastTick = 0L

    private val padX: Float get() = dp(24f)
    private val trackH: Float get() = dp(8f)

    private val onAnim = ValueAnimator().apply {
        duration = 300
        addUpdateListener {
            on = it.animatedValue as Float
            invalidate()
        }
    }
    private val pressAnim = ValueAnimator().apply {
        duration = 140
        addUpdateListener {
            press = it.animatedValue as Float
            invalidate()
        }
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(Color.WHITE, 0x26) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grayFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GRAY }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.alpha(Color.WHITE, 0xB0)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.alpha(Color.WHITE, 0x90)
        textAlign = Paint.Align.CENTER
        textSize = dp(10.5f)
    }
    private val rangeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.alpha(Color.WHITE, 0x70)
        textSize = dp(11f)
    }

    init {
        contentDescription = "Panel DPI slider"
    }

    fun setActive(active: Boolean, animate: Boolean = true) {
        val target = if (active) 1f else 0f
        onAnim.cancel()
        if (animate) {
            onAnim.setFloatValues(on, target)
            onAnim.start()
        } else {
            on = target
            invalidate()
        }
    }

    fun step(d: Int) = commit((value + d).coerceIn(minValue, maxValue))

    private fun pressTo(target: Float) {
        pressAnim.cancel()
        pressAnim.setFloatValues(press, target)
        pressAnim.start()
    }

    private fun commit(v: Int) {
        if (v == value) return
        value = v
        val now = SystemClock.uptimeMillis()
        if (v == marker) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else if (now - lastTick > 20) {
            lastTick = now
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onChange?.invoke(v)
    }

    private fun moveTo(x: Float) {
        val l = padX
        val r = width - padX
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        commit((minValue + f * (maxValue - minValue)).roundToInt())
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(76f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(padX, 0f, w - padX, 0f, Ui.RAMP, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val l = padX
        val r = width - padX
        val cy = height * 0.45f
        val half = trackH / 2f
        val frac = (value - minValue).toFloat() / (maxValue - minValue)
        val tx = l + (r - l) * frac

        c.drawRoundRect(l, cy - half, r, cy + half, half, half, trackPaint)
        if (on < 1f) {
            grayFillPaint.alpha = (190 * (1f - on)).toInt()
            c.drawRoundRect(l, cy - half, tx, cy + half, half, half, grayFillPaint)
        }
        if (on > 0f) {
            fillPaint.alpha = (255 * on).toInt()
            c.drawRoundRect(l, cy - half, tx, cy + half, half, half, fillPaint)
        }

        if (marker in minValue..maxValue) {
            val mf = (marker - minValue).toFloat() / (maxValue - minValue)
            val mx = l + (r - l) * mf
            c.drawLine(mx, cy - dp(17f), mx, cy - dp(9f), markerPaint)
            val label = "system $marker"
            val tw = markerText.measureText(label)
            c.drawText(label, mx.coerceIn(l + tw / 2f, r - tw / 2f), cy - dp(22f), markerText)
        }

        rangeText.textAlign = Paint.Align.LEFT
        c.drawText("$minValue", l, cy + dp(32f), rangeText)
        rangeText.textAlign = Paint.Align.RIGHT
        c.drawText("$maxValue", r, cy + dp(32f), rangeText)

        val rad = dp(12f) * (1f + 0.28f * press)
        val col = Ui.lerp(Ui.GRAY, Ui.ramp(frac), on)
        thumbPaint.color = Color.WHITE
        thumbPaint.setShadowLayer(dp(10f) + dp(6f) * press, 0f, 0f, Ui.alpha(col, 0xE6))
        c.drawCircle(tx, cy, rad, thumbPaint)
        innerPaint.color = col
        c.drawCircle(tx, cy, rad * 0.45f, innerPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressTo(1f)
                moveTo(e.x)
            }
            MotionEvent.ACTION_MOVE -> moveTo(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressTo(0f)
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            minValue.toFloat(), maxValue.toFloat(), value.toFloat(),
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (super.performAccessibilityAction(action, arguments)) return true
        val d = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return false
        }
        step(d)
        return true
    }
}

/**
 * Round glowing icon-only button. With [onStep] set it behaves as a press-and-hold repeater.
 */
internal class OrbButton(
    context: Context,
    iconRes: Int,
    private val colorA: Int,
    private val colorB: Int,
    private val filled: Boolean,
    private val sizeDp: Float,
    private val orbDp: Float,
    breathing: Boolean = false,
) : LoopView(context, 3_200L, breathing) {

    var onStep: (() -> Unit)? = null

    private val icon: Drawable = context.getDrawable(iconRes)!!.mutate().apply { setTint(Color.WHITE) }

    private var iconRot = 0f
    private var burstT = 1f

    private val spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 800
        interpolator = OvershootInterpolator(0.8f)
        addUpdateListener {
            iconRot = it.animatedValue as Float
            invalidate()
        }
    }
    private val burstAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 650
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            burstT = it.animatedValue as Float
            invalidate()
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(Color.BLACK, 0x40) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var bodyShader: Shader? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun playSpin() {
        spinAnim.cancel()
        spinAnim.start()
    }

    fun playBurst() {
        burstAnim.cancel()
        burstAnim.start()
    }

    private val repeater = object : Runnable {
        override fun run() {
            onStep?.invoke()
            postDelayed(this, 55L)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.9f else 1f
        animate().scaleX(s).scaleY(s).setDuration(110).start()
    }

    override fun performClick(): Boolean {
        onStep?.invoke()
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val step = onStep ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                step()
                removeCallbacks(repeater)
                postDelayed(repeater, 380L)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                removeCallbacks(repeater)
            }
        }
        return true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val s = dp(sizeDp).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = dp(orbDp) / 2f
        bodyShader = LinearGradient(cx - r, cy - r, cx + r, cy + r, colorA, colorB, Shader.TileMode.CLAMP)
        glossPaint.shader = LinearGradient(
            cx, cy - r, cx, cy + r * 0.1f,
            Ui.alpha(Color.WHITE, 0x70), Ui.alpha(Color.WHITE, 0),
            Shader.TileMode.CLAMP,
        )
        strokePaint.strokeWidth = dp(2f)
        strokePaint.shader = bodyShader
    }


    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(orbDp) / 2f
        val pulse = 0.5f + 0.5f * sin(TAU * phase)

        if (burstT < 1f) {
            ringPaint.color = Ui.alpha(Color.WHITE, ((1f - burstT) * 200).toInt())
            ringPaint.strokeWidth = dp(3f) * (1f - burstT) + dp(1f)
            c.drawCircle(cx, cy, r * (1f + 0.55f * burstT), ringPaint)
        }

        val glow = dp(18f) * (0.8f + 0.4f * pulse)
        if (filled) {
            fillPaint.shader = bodyShader
            fillPaint.setShadowLayer(glow, 0f, dp(3f), Ui.alpha(colorB, 0xA8))
            c.drawCircle(cx, cy, r, fillPaint)
        } else {
            fillPaint.shader = null
            fillPaint.color = Ui.alpha(Color.WHITE, 0x26)
            fillPaint.setShadowLayer(glow * 0.6f, 0f, dp(2f), Ui.alpha(colorB, 0x70))
            c.drawCircle(cx, cy, r, fillPaint)
            c.drawCircle(cx, cy, r - dp(1f), strokePaint)
        }
        c.drawCircle(cx, cy, r * 0.96f, glossPaint)
        if (isPressed) c.drawCircle(cx, cy, r, overlayPaint)

        val half = (dp(orbDp) * 0.21f).toInt()
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        c.save()
        c.rotate(iconRot, cx, cy)
        icon.draw(c)
        c.restore()
    }
}
