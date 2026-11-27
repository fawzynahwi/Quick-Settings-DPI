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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "QsDpi"

private fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
private fun log(msg: String, t: Throwable) = XposedBridge.log("[$TAG] $msg\n${t.stackTraceToString()}")

/**
 * HyperOS keeps the control center (quick settings) in a separate plugin APK that SystemUI
 * loads into its own process. Two layers keep that plugin at your DPI:
 *
 * 1. When SystemUI creates the plugin's Context, it is wrapped in a configuration override
 *    that only changes densityDpi.
 * 2. Every time Android (re)applies a configuration to any Resources that belong to the plugin
 *    APK (dark mode switch, theme change, rotation, system DPI change...) the density is forced
 *    back to your value, so nothing can silently reset it.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Prefs.SYSTEMUI || installed) return
        installed = true

        try {
            val contextImpl = XposedHelpers.findClass("android.app.ContextImpl", lpparam.classLoader)
            XposedBridge.hookAllMethods(contextImpl, "createApplicationContext", ContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createPackageContextAsUser", ContextHook)
            log("context hooks installed in ${lpparam.processName}")
        } catch (t: Throwable) {
            log("failed to install context hooks", t)
        }

        try {
            val resourcesImpl = XposedHelpers.findClass("android.content.res.ResourcesImpl", lpparam.classLoader)
            val hooks = XposedBridge.hookAllMethods(resourcesImpl, "updateConfiguration", ResourcesImplHook)
            if (hooks.isEmpty()) log("ResourcesImpl.updateConfiguration not found") else log("resources hook installed")
        } catch (t: Throwable) {
            log("failed to install resources hook", t)
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
            if (!p.file.canRead()) {
                // Not readable (yet): keep the last known values instead of falling back to "off".
                if (!warned) {
                    warned = true
                    log("settings file ${p.file} is not readable yet - keeping last known values")
                }
                return cached
            }
            warned = false
            if (p.hasFileChanged()) p.reload()
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

/** APK paths of the plugin, so we can recognise its Resources later. */
internal object PluginPaths {
    private val paths: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun isEmpty(): Boolean = paths.isEmpty()

    fun remember(info: ApplicationInfo?) {
        if (info == null) return
        info.sourceDir?.let { paths.add(it) }
        info.publicSourceDir?.let { paths.add(it) }
        info.splitSourceDirs?.forEach { paths.add(it) }
    }

    /** True if this ResourcesImpl loads the plugin APK. */
    fun matches(impl: Any?): Boolean {
        if (impl == null) return false
        return try {
            val assets = XposedHelpers.getObjectField(impl, "mAssets") ?: return false
            val apks = XposedHelpers.callMethod(assets, "getApkAssets") as? Array<*> ?: return false
            apks.any { apk ->
                val path = if (apk == null) null else XposedHelpers.callMethod(apk, "getAssetPath") as? String
                path != null && path in paths
            }
        } catch (t: Throwable) {
            false
        }
    }
}

private object ContextHook : XC_MethodHook() {
    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun beforeHookedMethod(param: MethodHookParam) {
        // Register the plugin's APK path before its resources get created.
        val info = param.args.getOrNull(0) as? ApplicationInfo ?: return
        if (info.packageName == Settings.get().target) PluginPaths.remember(info)
    }

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
        if (pkg != s.target) return

        PluginPaths.remember(base.applicationInfo)
        if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) return

        param.result = withDensity(base, pkg, s.dpi)
    }
}

/** Forces the chosen density whenever a configuration is applied to the plugin's resources. */
private object ResourcesImplHook : XC_MethodHook() {
    private val busy = ThreadLocal<Boolean>()
    private val logged = AtomicInteger(0)

    override fun beforeHookedMethod(param: MethodHookParam) {
        if (PluginPaths.isEmpty() || busy.get() == true) return
        busy.set(true)
        try {
            val idx = param.args.indexOfFirst { it is Configuration }
            if (idx < 0) return
            val cfg = param.args[idx] as Configuration

            val s = Settings.get()
            if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI || cfg.densityDpi == s.dpi) return
            if (!PluginPaths.matches(param.thisObject)) return

            param.args[idx] = Configuration(cfg).apply { densityDpi = s.dpi }
            if (logged.getAndIncrement() < 6) {
                log("kept ${s.dpi} dpi on plugin resources (config update carried ${cfg.densityDpi})")
            }
        } catch (t: Throwable) {
            log("resources hook failed", t)
        } finally {
            busy.set(false)
        }
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
