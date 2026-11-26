package com.example.qsdpi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var moduleActive = true

    private lateinit var dpiInput: EditText
    private lateinit var targetInput: EditText
    private lateinit var debugBox: CheckBox
    private lateinit var preview: TextView

    private val systemDpi get() = resources.displayMetrics.densityDpi
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("WorldReadableFiles")
    @Suppress("DEPRECATION")
    private fun openPrefs(): SharedPreferences = try {
        getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        moduleActive = false
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = openPrefs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(android.graphics.Color.rgb(12, 19, 38))
        }
        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(label("◈  QUICK SETTINGS DPI", 19f))
        root.addView(label("SYSTEM  /  DISPLAY TUNER", 11f))
        dpiInput = EditText(this).apply {
            val saved = prefs.getInt(Prefs.KEY_DPI, 500).coerceIn(400, 600)
            setText(saved.toString())
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(100, 225, 255))
            isFocusable = false
            background = null
        }
        root.addView(dpiInput)
        val slider = android.widget.SeekBar(this).apply {
            max = 200
            progress = dpiInput.text.toString().toInt() - 400
            progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setPadding(dp(8), dp(20), dp(8), dp(20))
        }
        root.addView(slider)
        root.addView(label("400                 ◇                 600", 13f))
        preview = label("")
        root.addView(preview)
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, user: Boolean) {
                dpiInput.setText((400 + p).toString())
                updatePreview()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        updatePreview()
        root.addView(label("Target package · advanced"))
        targetInput = EditText(this).apply {
            setText(prefs.getString(Prefs.KEY_TARGET, Prefs.DEFAULT_TARGET))
            setTextColor(android.graphics.Color.WHITE)
            setSingleLine()
        }
        root.addView(targetInput)
        debugBox = CheckBox(this).apply {
            text = "Verbose logging"
            setTextColor(android.graphics.Color.WHITE)
            isChecked = prefs.getBoolean(Prefs.KEY_DEBUG, false)
        }
        root.addView(debugBox)
        val actions = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER }
        fun circle(symbol: String, description: String, action: () -> Unit) =
            Button(this).apply {
                text = symbol
                textSize = 26f
                contentDescription = description
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.rgb(32, 112, 180))
                    setStroke(dp(2), android.graphics.Color.CYAN)
                }
                setOnClickListener { action() }
            }
        actions.addView(circle("▣", "Save") {
            if (save()) toast("Saved. Restart System UI to apply.")
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(12), dp(12), dp(12), dp(12) })
        actions.addView(circle("↻", "Save and restart System UI") {
            if (save()) restartSystemUi()
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(12), dp(12), dp(12), dp(12) })
        root.addView(actions)
        if (!moduleActive) root.addView(label("Enable the module and select System UI in LSPosed."))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun parseDpi(): Int {
        val raw = dpiInput.text.toString().trim()
        return if (raw.isEmpty()) 0 else raw.toIntOrNull() ?: -1
    }

    private fun updatePreview() {
        val dpi = parseDpi()
        preview.text = when {
            dpi == 0 -> "Override disabled."
            dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI -> "DPI must be ${Prefs.MIN_DPI}-${Prefs.MAX_DPI}."
            else -> "Panel will be drawn at about ${systemDpi * 100 / dpi}% of its current size."
        }
    }

    private fun save(): Boolean {
        val dpi = parseDpi()
        if (dpi != 0 && dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) {
            toast("DPI must be 0 (off) or ${Prefs.MIN_DPI}-${Prefs.MAX_DPI}")
            return false
        }
        prefs.edit()
            .putInt(Prefs.KEY_DPI, dpi)
            .putString(Prefs.KEY_TARGET, targetInput.text.toString().trim().ifEmpty { Prefs.DEFAULT_TARGET })
            .putBoolean(Prefs.KEY_DEBUG, debugBox.isChecked)
            .commit()
        return true
    }

    private fun restartSystemUi() {
        Thread {
            val ok = try {
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "killall com.android.systemui || pkill -f com.android.systemui"))
                    .waitFor() == 0
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                toast(if (ok) "System UI restarting..." else "Root restart failed - restart System UI or reboot manually")
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
