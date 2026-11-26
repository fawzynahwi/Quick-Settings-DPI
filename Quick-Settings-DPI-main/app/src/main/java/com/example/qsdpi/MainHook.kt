package com.example.qsdpi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.util.DisplayMetrics
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "QsDpi"

private fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
private fun log(msg: String, t: Throwable) = XposedBridge.log("[$TAG] $msg\n${t.stackTraceToString()}")

/**
 * HyperOS keeps the control center (quick settings) in a separate plugin APK that SystemUI
 * loads into its own process. We force a custom densityDpi onto that plugin's Resources so every
 * dp/sp value it resolves uses the DPI you chose, while the rest of the system keeps the normal DPI.
 *
 * Three layers keep the density from being lost (it used to reset on dark mode toggle):
 *  1. Context creation: the plugin Context is wrapped in a density-only configuration override.
 *  2. createConfigurationContext(): when SystemUI/plugin derives a new Context from the plugin
 *     context with a fresh override (e.g. a new uiMode on a dark mode change), the new override
 *     did not carry our density, so we add densityDpi to that override.
 *  3. updateConfiguration(): when the plugin's Resources are refreshed with the system
 *     Configuration (which has the system densityDpi), we rewrite densityDpi back to our value.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Prefs.SYSTEMUI || installed) return
        installed = true
        val cl = lpparam.classLoader

        install("context hooks") {
            val contextImpl = XposedHelpers.findClass("android.app.ContextImpl", cl)
            XposedBridge.hookAllMethods(contextImpl, "createApplicationContext", ContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createPackageContextAsUser", ContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createConfigurationContext", ConfigContextHook)
        }
        install("resources hooks") {
            val resources = XposedHelpers.findClass("android.content.res.Resources", cl)
            val resourcesImpl = XposedHelpers.findClass("android.content.res.ResourcesImpl", cl)
            XposedBridge.hookAllMethods(resources, "updateConfiguration", ResourcesUpdateHook)
            XposedBridge.hookAllMethods(resources, "setImpl", SetImplHook)
            XposedBridge.hookAllMethods(resourcesImpl, "updateConfiguration", ResourcesUpdateHook)
        }
        log("hooks installed in ${lpparam.processName}")
    }

    private fun install(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("failed to install $name", t)
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

/** Remembers which Resources / ResourcesImpl objects belong to the target plugin. */
internal object Tracker {
    private val resources: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())
    private val impls: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    fun track(res: Resources) {
        resources[res] = true
        implOf(res)?.let { impls[it] = true }
    }

    fun trackImpl(impl: Any) {
        impls[impl] = true
    }

    fun isTracked(o: Any?): Boolean = o != null && (resources.containsKey(o) || impls.containsKey(o))

    private fun implOf(res: Resources): Any? =
        try { XposedHelpers.callMethod(res, "getImpl") } catch (_: Throwable) { null }
}

private fun validDpi(dpi: Int) = dpi in Prefs.MIN_DPI..Prefs.MAX_DPI

/** Context creation by package: wrap the target plugin's Context in a density-only override. */
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
        if (pkg != s.target || !validDpi(s.dpi)) return

        param.result = withDensity(base, pkg, s.dpi)
    }
}

/**
 * Context.createConfigurationContext(override) on a target-package context. This is what yields a
 * fresh Resources when e.g. the night mode changes. The new override only contains what the caller
 * passed (uiMode, ...), so densityDpi would fall back to the system value. Add ours to it.
 */
private object ConfigContextHook : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        val ctx = param.thisObject as? Context ?: return
        val s = Settings.get()
        if (!validDpi(s.dpi) || ctx.packageName != s.target) return
        val override = param.args.getOrNull(0) as? Configuration ?: return
        if (override.densityDpi == s.dpi) return
        param.args[0] = Configuration(override).apply { densityDpi = s.dpi }
        if (s.debug) log("${s.target}: createConfigurationContext density ${override.densityDpi} -> ${s.dpi}")
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val result = param.result as? Context ?: return
        val s = Settings.get()
        if (!validDpi(s.dpi) || result.packageName != s.target) return
        Tracker.track(result.resources)
    }
}

/** Resources.updateConfiguration / ResourcesImpl.updateConfiguration for tracked objects. */
private object ResourcesUpdateHook : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        if (!Tracker.isTracked(param.thisObject)) return
        val s = Settings.get()
        if (!validDpi(s.dpi)) return
        val args = param.args
        for (i in args.indices) {
            when (val a = args[i]) {
                is Configuration -> if (a.densityDpi != s.dpi) {
                    if (s.debug) log("keeping density ${s.dpi} (system pushed ${a.densityDpi})")
                    args[i] = Configuration(a).apply { densityDpi = s.dpi }
                }
                is DisplayMetrics -> if (a.densityDpi != s.dpi) {
                    // copy: this object may be shared with other Resources
                    val ratio = if (a.density > 0f) a.scaledDensity / a.density else 1f
                    args[i] = DisplayMetrics().apply {
                        setTo(a)
                        densityDpi = s.dpi
                        density = s.dpi / 160f
                        scaledDensity = density * ratio
                    }
                }
            }
        }
    }
}

/** If a tracked Resources gets a new ResourcesImpl, keep tracking the new one. */
private object SetImplHook : XC_MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam) {
        if (!Tracker.isTracked(param.thisObject)) return
        param.args.getOrNull(0)?.let { Tracker.trackImpl(it) }
    }
}

private fun withDensity(base: Context, pkg: String, dpi: Int): Context {
    return try {
        val before = base.resources.configuration.densityDpi
        if (before == dpi) {
            Tracker.track(base.resources)
            return base
        }
        val wrapped = base.createConfigurationContext(Configuration().apply { densityDpi = dpi })
        Tracker.track(wrapped.resources)
        log("$pkg: density $before -> ${wrapped.resources.configuration.densityDpi}")
        wrapped
    } catch (t: Throwable) {
        log("failed to override density for $pkg", t)
        base
    }
}
