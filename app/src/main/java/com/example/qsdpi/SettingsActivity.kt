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
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        fun label(text: String, size: Float = 14f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(12), 0, dp(4))
        }

        root.addView(label("Quick settings DPI", 22f))
        root.addView(label(
            "System DPI right now: $systemDpi\n" +
                "Higher DPI = smaller padding, buttons and text in the panel. " +
                "Set 0 (or leave empty) to disable the override."
        ))

        dpiInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Panel DPI, e.g. ${(systemDpi * 1.1).toInt()}"
            val saved = prefs.getInt(Prefs.KEY_DPI, 0)
            if (saved > 0) setText(saved.toString())
        }
        root.addView(dpiInput)

        preview = TextView(this)
        root.addView(preview)
        dpiInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })
        updatePreview()

        root.addView(label("Target package (advanced)"))
        targetInput = EditText(this).apply {
            setText(prefs.getString(Prefs.KEY_TARGET, Prefs.DEFAULT_TARGET))
            hint = Prefs.DEFAULT_TARGET
            setSingleLine()
        }
        root.addView(targetInput)

        debugBox = CheckBox(this).apply {
            text = "Verbose logging (LSPosed log, search for QsDpi)"
            isChecked = prefs.getBoolean(Prefs.KEY_DEBUG, false)
        }
        root.addView(debugBox)

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                if (save()) toast("Saved. Restart System UI to apply.")
            }
        })
        root.addView(Button(this).apply {
            text = "Save and restart System UI (root)"
            setOnClickListener { if (save()) restartSystemUi() }
        })

        if (!moduleActive) {
            root.addView(label(
                "Module is not active in LSPosed yet: enable it, tick System UI " +
                    "(com.android.systemui) in its scope, then reopen this app."
            ))
        }

        setContentView(
            ScrollView(this).apply {
                addView(root, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        )
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
