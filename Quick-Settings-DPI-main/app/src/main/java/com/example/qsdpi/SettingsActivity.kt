package com.example.qsdpi

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var moduleActive = true
    private var enabled = false

    private lateinit var dial: DpiDial
    private lateinit var slider: GlowSlider
    private lateinit var enableSwitch: Switch
    private lateinit var enableDesc: TextView
    private lateinit var targetInput: EditText
    private lateinit var debugSwitch: Switch
    private lateinit var saveOrb: OrbButton
    private lateinit var restartOrb: OrbButton
    private lateinit var pill: TextView

    private var dotPulse: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hidePill = Runnable {
        pill.animate().alpha(0f).translationY(dp(10f)).setDuration(260).start()
    }

    private val systemDpi: Int get() = resources.displayMetrics.densityDpi

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    @SuppressLint("WorldReadableFiles")
    @Suppress("DEPRECATION")
    private fun openPrefs(): SharedPreferences = try {
        getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        moduleActive = false
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    }

    // ---------------------------------------------------------------- helpers

    private fun glass(radiusDp: Float, strong: Boolean = false) = Ui.glass(this, radiusDp, strong)

    private fun gap(topDp: Float) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(topDp).toInt() }

    private fun text(s: String, sp: Float, color: Int = Color.WHITE, weight: Int = 400): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        }

    private fun styledSwitch(): Switch = Switch(this).apply {
        val checkedState = intArrayOf(android.R.attr.state_checked)
        thumbTintList = ColorStateList(
            arrayOf(checkedState, intArrayOf()),
            intArrayOf(Color.WHITE, Ui.alpha(Color.WHITE, 0xC0)),
        )
        trackTintList = ColorStateList(
            arrayOf(checkedState, intArrayOf()),
            intArrayOf(Ui.VIOLET, Ui.alpha(Color.WHITE, 0x40)),
        )
    }

    private fun settingRow(title: String, desc: TextView, sw: Switch): LinearLayout {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, Color.WHITE, 600))
            addView(desc)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw)
        }
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        prefs = openPrefs()

        val saved = prefs.getInt(Prefs.KEY_DPI, 0)
        val last = prefs.getInt(Prefs.KEY_LAST_DPI, 0)
        enabled = saved > 0
        val raw = when {
            saved > 0 -> saved
            last > 0 -> last
            else -> (systemDpi * 1.1f).roundToInt()
        }
        val start = raw.coerceIn(Prefs.UI_MIN, Prefs.UI_MAX)

        val root = FrameLayout(this)
        root.addView(AuroraView(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(12f).toInt())
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // header
        content.addView(buildHeader(), gap(8f))

        // big dial
        dial = DpiDial(this).apply {
            minValue = Prefs.UI_MIN
            maxValue = Prefs.UI_MAX
            setActive(enabled, false)
            setValue(Prefs.UI_MIN, animate = false, bump = false)
        }
        content.addView(dial, gap(10f))

        // system DPI chip
        val chip = text("System DPI $systemDpi  ·  tap to match", 12f, Ui.alpha(Color.WHITE, 0xC8), 500).apply {
            background = glass(18f)
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
            isClickable = true
            setOnClickListener {
                val v = systemDpi.coerceIn(Prefs.UI_MIN, Prefs.UI_MAX)
                slider.value = v
                onSliderChanged(v)
            }
        }
        content.addView(
            chip,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2f).toInt()
            },
        )

        // slider card:  [-] ---o--- [+]
        val minus = OrbButton(
            this, R.drawable.ic_minus, Ui.INDIGO, Ui.VIOLET,
            filled = false, sizeDp = 60f, orbDp = 42f,
        ).apply {
            contentDescription = "Decrease DPI"
            onStep = { slider.step(-1) }
        }
        val plus = OrbButton(
            this, R.drawable.ic_plus, Ui.VIOLET, Ui.PINK,
            filled = false, sizeDp = 60f, orbDp = 42f,
        ).apply {
            contentDescription = "Increase DPI"
            onStep = { slider.step(1) }
        }
        slider = GlowSlider(this).apply {
            minValue = Prefs.UI_MIN
            maxValue = Prefs.UI_MAX
            marker = systemDpi
            value = start
            setActive(enabled, false)
            onChange = { v -> onSliderChanged(v) }
        }
        val sliderCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(28f)
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            addView(minus)
            addView(slider, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(plus)
        }
        content.addView(sliderCard, gap(14f))

        // enable switch card
        enableDesc = text("", 12f, Ui.alpha(Color.WHITE, 0x99))
        enableSwitch = styledSwitch().apply { isChecked = enabled }
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            enabled = checked
            dial.setActive(checked)
            slider.setActive(checked)
            refreshEnableText()
            updateSubtitle()
        }
        val enableCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(24f)
            setPadding(dp(18f).toInt(), dp(14f).toInt(), dp(18f).toInt(), dp(14f).toInt())
            addView(settingRow("Override active", enableDesc, enableSwitch))
        }
        content.addView(enableCard, gap(14f))

        content.addView(buildAdvanced(content), gap(14f))

        refreshEnableText()
        updateSubtitle()

        // bottom bar: fade + two round buttons
        val fade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.alpha(Ui.BG, 0), Ui.alpha(Ui.BG, 0xE6), Ui.BG),
            )
        }
        root.addView(fade, FrameLayout.LayoutParams(MATCH, dp(190f).toInt(), Gravity.BOTTOM))

        saveOrb = OrbButton(
            this, R.drawable.ic_save, Ui.CYAN, Ui.INDIGO,
            filled = true, sizeDp = 112f, orbDp = 68f,
        ).apply {
            contentDescription = "Save"
            tooltipText = "Save"
            setOnClickListener { onSave() }
        }
        restartOrb = OrbButton(
            this, R.drawable.ic_restart, Ui.VIOLET, Ui.PINK,
            filled = true, sizeDp = 112f, orbDp = 68f, breathing = true,
        ).apply {
            contentDescription = "Save and restart System UI"
            tooltipText = "Save and restart System UI"
            setOnClickListener { onSaveAndRestart() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(saveOrb)
            addView(restartOrb)
        }
        root.addView(buttons, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        pill = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(22f)
                setColor(Ui.alpha(0xFF14162B.toInt(), 0xF2))
                setStroke(dp(1f).toInt().coerceAtLeast(1), Ui.alpha(Color.WHITE, 0x33))
            }
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            alpha = 0f
        }
        root.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            scroll.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom) + dp(150f).toInt())
            buttons.setPadding(0, 0, 0, bars.bottom + dp(10f).toInt())
            (fade.layoutParams as FrameLayout.LayoutParams).height = dp(190f).toInt() + bars.bottom
            fade.requestLayout()
            (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(132f).toInt() + bars.bottom
            pill.requestLayout()
            WindowInsets.CONSUMED
        }

        setContentView(root)

        // entrance animation
        val decel = DecelerateInterpolator(1.8f)
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(30f)
            if (child === dial) {
                child.scaleX = 0.88f
                child.scaleY = 0.88f
            }
            val anim = child.animate().alpha(1f).translationY(0f)
            if (child === dial) anim.scaleX(1f).scaleY(1f)
            anim.setStartDelay(90L * i + 120L).setDuration(520).setInterpolator(decel).start()
        }
        buttons.alpha = 0f
        buttons.translationY = dp(60f)
        buttons.animate().alpha(1f).translationY(0f).setStartDelay(650).setDuration(520).setInterpolator(decel).start()

        // the number counts up from the minimum to the saved value
        dial.postDelayed({ dial.setValue(start, true, false, 900L) }, 500L)
    }

    private fun buildHeader(): View {
        val title = text("QS DPI", 34f, Color.WHITE, 800)
        title.post {
            title.paint.shader = LinearGradient(
                0f, 0f, title.paint.measureText("QS DPI"), 0f,
                Ui.RAMP, null, Shader.TileMode.CLAMP,
            )
            title.invalidate()
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(text("Control center density", 13f, Ui.alpha(Color.WHITE, 0x99)))
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (moduleActive) Ui.GREEN else Ui.RED)
            }
        }
        dotPulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val statusText = text(
            if (moduleActive) "Module active" else "Not active",
            12f, Ui.alpha(Color.WHITE, 0xDC), 500,
        )
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(20f)
            setPadding(dp(12f).toInt(), dp(7f).toInt(), dp(14f).toInt(), dp(7f).toInt())
            addView(dot, LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()))
            addView(statusText, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8f).toInt() })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(status)
        }
    }

    private fun buildAdvanced(transitionRoot: ViewGroup): View {
        targetInput = EditText(this).apply {
            setText(prefs.getString(Prefs.KEY_TARGET, Prefs.DEFAULT_TARGET))
            hint = Prefs.DEFAULT_TARGET
            setSingleLine()
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Ui.alpha(Color.WHITE, 0x70))
            background = glass(14f)
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        debugSwitch = styledSwitch().apply { isChecked = prefs.getBoolean(Prefs.KEY_DEBUG, false) }
        val debugDesc = text("Writes QsDpi lines to the LSPosed log", 12f, Ui.alpha(Color.WHITE, 0x99))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(text("Target package", 12f, Ui.alpha(Color.WHITE, 0xB0), 500))
            addView(targetInput, gap(6f))
            addView(settingRow("Verbose logging", debugDesc, debugSwitch), gap(14f))
        }
        val chevron = text("›", 24f, Ui.alpha(Color.WHITE, 0xB0)).apply { rotation = 90f }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(0, dp(12f).toInt(), 0, dp(12f).toInt())
            addView(text("Advanced", 15f, Color.WHITE, 600), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chevron)
            setOnClickListener {
                TransitionManager.beginDelayedTransition(transitionRoot)
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (open) 270f else 90f).setDuration(250).start()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(24f)
            setPadding(dp(18f).toInt(), dp(2f).toInt(), dp(18f).toInt(), dp(14f).toInt())
            addView(header)
            addView(body)
        }
    }

    // ---------------------------------------------------------------- state

    private fun onSliderChanged(v: Int) {
        dial.setValue(v)
        if (!enabled) enableSwitch.isChecked = true
        updateSubtitle()
    }

    private fun refreshEnableText() {
        enableDesc.text = if (enabled) "Panel is drawn at the DPI above" else "Panel follows the system DPI"
    }

    private fun updateSubtitle() {
        dial.subtitle = if (enabled) {
            "≈ ${(systemDpi * 100f / slider.value).roundToInt()}% size"
        } else {
            "override off"
        }
    }

    private fun save(): Boolean {
        val dpi = if (enabled) slider.value else 0
        return prefs.edit()
            .putInt(Prefs.KEY_DPI, dpi)
            .putInt(Prefs.KEY_LAST_DPI, slider.value)
            .putString(Prefs.KEY_TARGET, targetInput.text.toString().trim().ifEmpty { Prefs.DEFAULT_TARGET })
            .putBoolean(Prefs.KEY_DEBUG, debugSwitch.isChecked)
            .commit()
    }

    private fun onSave() {
        saveOrb.playBurst()
        if (save()) {
            showPill(if (enabled) "Saved  ·  restart System UI to apply" else "Saved  ·  override is off")
        } else {
            showPill("Couldn't save settings")
        }
    }

    private fun onSaveAndRestart() {
        restartOrb.playSpin()
        restartOrb.playBurst()
        if (!save()) {
            showPill("Couldn't save settings")
            return
        }
        showPill("Restarting System UI…")
        Thread {
            val ok = try {
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "killall com.android.systemui || pkill -f com.android.systemui"))
                    .waitFor() == 0
            } catch (e: Exception) {
                false
            }
            if (!ok) runOnUiThread { showPill("No root  ·  restart System UI or reboot manually") }
        }.start()
    }

    private fun showPill(msg: String) {
        handler.removeCallbacks(hidePill)
        pill.text = msg
        pill.translationY = dp(14f)
        pill.animate().alpha(1f).translationY(0f).setDuration(220).start()
        handler.postDelayed(hidePill, 2800L)
    }

    override fun onResume() {
        super.onResume()
        dotPulse?.start()
    }

    override fun onPause() {
        dotPulse?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
