package com.example.qsdpi

object Prefs {
    const val MODULE_PACKAGE = "com.example.qsdpi"
    const val FILE = "settings"

    const val KEY_DPI = "panel_dpi"            // Int, 0 = disabled
    const val KEY_TARGET = "target_package"    // String
    const val KEY_DEBUG = "debug_log"          // Boolean

    const val SYSTEMUI = "com.android.systemui"

    /** On HyperOS the control center / quick settings UI lives in this plugin APK. */
    const val DEFAULT_TARGET = "miui.systemui.plugin"

    const val MIN_DPI = 120
    const val MAX_DPI = 1000
}
