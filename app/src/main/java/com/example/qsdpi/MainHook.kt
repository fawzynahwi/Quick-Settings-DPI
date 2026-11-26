package com.example.qsdpi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections

private const val TAG = "QsDpi"

private fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
private fun log(msg: String, t: Throwable) = XposedBridge.log("[$TAG] $msg\n${t.stackTraceToString()}")

/**
 * HyperOS keeps the control center (quick settings) in a separate plugin APK that SystemUI
 * loads into its own process. The plugin gets a Context from ContextImpl.createApplicationContext().
 * We wrap that Context in a configuration override that only changes densityDpi, so every
 * dp/sp value the plugin resolves (padding, tile size, text) uses the DPI you chose, while the
 * rest of the system keeps the normal DPI. Only densityDpi is overridden, so everything else
 * (night mode, rotation, fold state...) keeps following the real configuration.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Prefs.SYSTEMUI || installed) return
        installed = true
        try {
            val contextImpl = XposedHelpers.findClass("android.app.ContextImpl", lpparam.classLoader)
            XposedBridge.hookAllMethods(contextImpl, "createApplicationContext", ContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createPackageContextAsUser", ContextHook)
            log("hooks installed in ${lpparam.processName}")
        } catch (t: Throwable) {
            log("failed to install hooks", t)
        }
    }

    private companion object {
        @Volatile var installed = false
    }
}

internal data class Snapshot(val dpi: Int, val target: String, val debug: Boolean)

/** Reads the module's settings from inside SystemUI (re-checked at most every 2 s). */
internal object Settings {
    private val prefs by lazy { XSharedPreferences(Prefs.MODULE_PACKAGE, Prefs.FILE) }

    @Volatile private var cached = Snapshot(0, Prefs.DEFAULT_TARGET, false)
    @Volatile private var lastCheck = 0L
    @Volatile private var warned = false

    fun get(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck < 2_000L) return cached
        lastCheck = now
        try {
            val p = prefs
            if (p.hasFileChanged()) p.reload()
            if (!p.file.canRead() && !warned) {
                warned = true
                log("settings file ${p.file} is not readable - open the module app and press Save")
            }
            cached = Snapshot(
                dpi = p.getInt(Prefs.KEY_DPI, 0),
                target = (p.getString(Prefs.KEY_TARGET, Prefs.DEFAULT_TARGET) ?: "").trim()
                    .ifEmpty { Prefs.DEFAULT_TARGET },
                debug = p.getBoolean(Prefs.KEY_DEBUG, false),
            )
        } catch (t: Throwable) {
            log("failed to read settings", t)
        }
        return cached
    }
}

private object ContextHook : XC_MethodHook() {
    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun afterHookedMethod(param: MethodHookParam) {
        val base = param.result as? Context ?: return
        val pkg = when (val first = param.args.getOrNull(0)) {
            is ApplicationInfo -> first.packageName
            is String -> first
            else -> return
        }

        val s = Settings.get()
        if (s.debug && seen.add("${param.method.name}:$pkg")) {
            log("${param.method.name}($pkg)")
        }
        if (pkg != s.target || s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) return

        param.result = withDensity(base, pkg, s.dpi)
    }
}

private fun withDensity(base: Context, pkg: String, dpi: Int): Context {
    return try {
        val before = base.resources.configuration.densityDpi
        if (before == dpi) return base
        val wrapped = base.createConfigurationContext(Configuration().apply { densityDpi = dpi })
        log("$pkg: density $before -> ${wrapped.resources.configuration.densityDpi}")
        wrapped
    } catch (t: Throwable) {
        log("failed to override density for $pkg", t)
        base
    }
}
