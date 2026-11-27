package com.example.qsdpi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "QsDpi"
private const val BUILD = "build 3 (diagnostics)"

private fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
private fun log(msg: String, t: Throwable) = XposedBridge.log("[$TAG] $msg\n${t.stackTraceToString()}")

/** Short "who called this" string for the log (framework / hook frames removed). */
private fun callers(): String =
    Throwable().stackTrace
        .map { "${it.className}.${it.methodName}" }
        .filterNot { n ->
            n.startsWith("com.example.qsdpi") || n.startsWith("de.robv") || n.startsWith("org.lsposed") ||
                n.startsWith("org.matrix") || n.startsWith("LSP") || n.startsWith("java.lang.reflect") ||
                n.startsWith("android.app.ContextImpl") || n.startsWith("android.content.ContextWrapper")
        }
        .take(8)
        .joinToString(" < ")

/**
 * HyperOS keeps the control center (quick settings) in a separate plugin APK that SystemUI
 * loads into its own process. Three layers keep that plugin at your DPI:
 *
 * 1. When SystemUI creates the plugin's Context, it is wrapped in a configuration override
 *    that only changes densityDpi.
 * 2. Contexts derived from it with createConfigurationContext() (which would otherwise drop the
 *    override) get the density injected as well.
 * 3. Every time Android (re)applies a configuration to Resources that belong to the plugin APK,
 *    the density is forced back to your value.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Prefs.SYSTEMUI || installed) return
        installed = true

        try {
            val contextImpl = XposedHelpers.findClass("android.app.ContextImpl", lpparam.classLoader)
            XposedBridge.hookAllMethods(contextImpl, "createApplicationContext", ContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createPackageContextAsUser", ContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createConfigurationContext", ConfigContextHook)
            log("context hooks installed in ${lpparam.processName} - $BUILD")
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

/** Knows which Resources belong to the plugin APK. */
internal object PluginPaths {
    private val paths: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val known: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    @Volatile private var announced = false

    fun isEmpty(): Boolean = paths.isEmpty() && known.isEmpty()

    private fun add(p: String?) {
        if (p.isNullOrEmpty()) return
        paths.add(p)
        try {
            paths.add(File(p).canonicalPath)
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun remember(info: ApplicationInfo?) {
        if (info == null) return
        add(info.sourceDir)
        add(info.publicSourceDir)
        info.splitSourceDirs?.forEach { add(it) }
        if (!announced) {
            announced = true
            log("plugin apk paths: $paths")
        }
    }

    /** Remember the exact ResourcesImpl behind a Resources object we wrapped ourselves. */
    fun markImpl(resources: Resources?) {
        if (resources == null) return
        try {
            val impl = XposedHelpers.getObjectField(resources, "mResourcesImpl") ?: return
            known[impl] = true
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun assetPaths(impl: Any?): List<String> {
        if (impl == null) return emptyList()
        return try {
            val assets = XposedHelpers.getObjectField(impl, "mAssets") ?: return emptyList()
            val apks = XposedHelpers.callMethod(assets, "getApkAssets") as? Array<*> ?: return emptyList()
            apks.mapNotNull { apk ->
                if (apk == null) null else XposedHelpers.callMethod(apk, "getAssetPath") as? String
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** True if this ResourcesImpl loads the plugin APK. */
    fun matches(impl: Any?): Boolean {
        if (impl == null) return false
        if (known.containsKey(impl)) return true
        return assetPaths(impl).any { it in paths }
    }
}

private object ContextHook : XC_MethodHook() {
    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val created = AtomicInteger(0)

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
        if (s.debug) {
            if (pkg == s.target) {
                if (created.getAndIncrement() < 60) log("${param.method.name}($pkg) via ${callers()}")
            } else if (seen.add("${param.method.name}:$pkg")) {
                log("${param.method.name}($pkg)")
            }
        }
        if (pkg != s.target) return

        PluginPaths.remember(base.applicationInfo)
        if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) return

        val wrapped = withDensity(base, pkg, s.dpi)
        if (wrapped !== base) PluginPaths.markImpl(wrapped.resources)
        param.result = wrapped
    }
}

/**
 * createConfigurationContext() builds its Resources from the given configuration only, so it would
 * silently drop the density override of the context it is called on. Inject the density again.
 */
private object ConfigContextHook : XC_MethodHook() {
    private val logged = AtomicInteger(0)

    override fun beforeHookedMethod(param: MethodHookParam) {
        val ctx = param.thisObject as? Context ?: return
        val s = Settings.get()
        if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) return
        if (ctx.packageName != s.target) return
        val cfg = param.args.getOrNull(0) as? Configuration ?: return

        val needsFix = cfg.densityDpi != s.dpi
        if (s.debug && logged.getAndIncrement() < 60) {
            log("createConfigurationContext on ${ctx.packageName}: density ${cfg.densityDpi}" +
                (if (needsFix) " -> ${s.dpi}" else " (ok)") + " via ${callers()}")
        }
        if (needsFix) param.args[0] = Configuration(cfg).apply { densityDpi = s.dpi }
    }
}

/** Forces the chosen density whenever a configuration is applied to the plugin's resources. */
private object ResourcesImplHook : XC_MethodHook() {
    private val busy = ThreadLocal<Boolean>()
    private val kept = AtomicInteger(0)
    private val traced = AtomicInteger(0)

    override fun beforeHookedMethod(param: MethodHookParam) {
        if (PluginPaths.isEmpty() || busy.get() == true) return
        busy.set(true)
        try {
            val idx = param.args.indexOfFirst { it is Configuration }
            if (idx < 0) return
            val cfg = param.args[idx] as Configuration

            val s = Settings.get()
            if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) return

            val matched = PluginPaths.matches(param.thisObject)

            if (s.debug && traced.get() < 80) {
                val apks = if (matched) emptyList<String>() else PluginPaths.assetPaths(param.thisObject)
                val interesting = matched || apks.any { it.contains("plugin", ignoreCase = true) }
                if (interesting && traced.getAndIncrement() < 80) {
                    log("ResourcesImpl.updateConfiguration density=${cfg.densityDpi} matched=$matched" +
                        (if (matched) "" else " apks=$apks"))
                }
            }

            if (!matched || cfg.densityDpi == s.dpi) return
            param.args[idx] = Configuration(cfg).apply { densityDpi = s.dpi }
            if (kept.getAndIncrement() < 10) {
                log("kept ${s.dpi} dpi on plugin resources (config update carried ${cfg.densityDpi}) via ${callers()}")
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
