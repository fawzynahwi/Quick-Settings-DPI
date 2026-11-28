package com.example.qsdpi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.LayoutInflater
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
private const val BUILD = "build 5 (late audit + inflate/metrics probes)"

private fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
private fun log(msg: String, t: Throwable) = XposedBridge.log("[$TAG] $msg\n${t.stackTraceToString()}")

internal fun dayNight(cfg: Configuration): String =
    if ((cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) "night" else "day"

/** Short "who called this" string for the log (framework / hook frames removed). */
private fun callers(): String =
    Throwable().stackTrace
        .map { "${it.className}.${it.methodName}" }
        .filterNot { n ->
            n.startsWith("com.example.qsdpi") || n.startsWith("de.robv") || n.startsWith("org.lsposed") ||
                n.startsWith("org.matrix") || n.startsWith("LSP") || n.startsWith("java.lang.reflect") ||
                n.startsWith("android.app.ContextImpl") || n.startsWith("android.content.ContextWrapper") ||
                n.contains("XposedBridge") || n.startsWith("e2.") || n.startsWith("c3.") || n.startsWith("g.a")
        }
        .take(6)
        .joinToString(" < ")

/**
 * HyperOS keeps the control center (quick settings) in a separate plugin APK that SystemUI
 * loads into its own process. Layers that keep that plugin at your DPI:
 *
 * 1. When SystemUI creates the plugin's Context, it is wrapped in a configuration override
 *    that only changes densityDpi.
 * 2. Contexts derived from it with createConfigurationContext() (which would otherwise drop the
 *    override) get the density injected as well. createDisplayContext() results are wrapped too.
 * 3. Every time Android (re)applies a configuration to Resources that belong to the plugin APK,
 *    the density is forced back to your value (ResourcesImpl.updateConfiguration hook).
 * 4. NEW in build 4: after every global configuration change (dark mode, rotation, ...) all plugin
 *    ResourcesImpl objects are audited and, if their density drifted, re-applied directly.
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
            XposedBridge.hookAllMethods(contextImpl, "createDisplayContext", DerivedContextHook)
            XposedBridge.hookAllMethods(contextImpl, "createWindowContext", DerivedContextHook)
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

        try {
            val resourcesManager = XposedHelpers.findClass("android.app.ResourcesManager", lpparam.classLoader)
            val hooks = XposedBridge.hookAllMethods(resourcesManager, "applyConfigurationToResources", ReapplyHook)
            if (hooks.isEmpty()) log("ResourcesManager.applyConfigurationToResources not found") else log("reapply hook installed")
            val h2 = XposedBridge.hookAllMethods(resourcesManager, "updateResourcesForActivity", ActivityResourcesHook)
            log(if (h2.isEmpty()) "ResourcesManager.updateResourcesForActivity not found" else "activity resources hook installed")
        } catch (t: Throwable) {
            log("failed to install reapply hook", t)
        }

        try {
            XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", InflateHook)
            XposedBridge.hookAllMethods(Resources::class.java, "getDisplayMetrics", MetricsHook)
            log("probes installed")
        } catch (t: Throwable) {
            log("failed to install probes", t)
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

/**
 * Log budgets. They are refilled on every global configuration change, so the lines around a
 * dark-mode switch are never starved by the startup noise.
 */
internal object Diag {
    private val ctxBudget = AtomicInteger(80)
    private val resBudget = AtomicInteger(15)
    private val inflBudget = AtomicInteger(40)
    private val metricsBudget = AtomicInteger(25)

    fun takeCtx(): Boolean = ctxBudget.getAndDecrement() > 0
    fun takeRes(): Boolean = resBudget.getAndDecrement() > 0
    fun takeInflate(): Boolean = inflBudget.getAndDecrement() > 0
    fun takeMetrics(): Boolean = metricsBudget.getAndDecrement() > 0

    fun newWindow() {
        ctxBudget.set(80)
        resBudget.set(15)
        inflBudget.set(40)
        metricsBudget.set(25)
    }
}

/** Knows which Resources belong to the plugin APK. */
internal object PluginPaths {
    private val paths: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val known: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    @Volatile private var announced = false
    @Volatile private var reflectionWarned = false

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
            if (!reflectionWarned) {
                reflectionWarned = true
                log("cannot read Resources.mResourcesImpl", t)
            }
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
            if (!reflectionWarned) {
                reflectionWarned = true
                log("cannot read apk paths from ResourcesImpl", t)
            }
            emptyList()
        }
    }

    /** True if this ResourcesImpl loads the plugin APK. */
    fun matches(impl: Any?): Boolean {
        if (impl == null) return false
        if (known.containsKey(impl)) return true
        return assetPaths(impl).any { p ->
            p in paths || try {
                File(p).canonicalPath in paths
            } catch (t: Throwable) {
                false
            }
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
        if (s.debug) {
            if (pkg == s.target) {
                if (Diag.takeCtx()) log("${param.method.name}($pkg) via ${callers()}")
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
 * In verbose mode every call is logged (any package), with day/night, so the dark-mode path is visible.
 */
private object ConfigContextHook : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        val ctx = param.thisObject as? Context ?: return
        val cfg = param.args.getOrNull(0) as? Configuration ?: return
        val s = Settings.get()
        val pkg = try {
            ctx.packageName
        } catch (t: Throwable) {
            return
        }
        val isTarget = pkg == s.target
        val inRange = s.dpi in Prefs.MIN_DPI..Prefs.MAX_DPI
        val needsFix = isTarget && inRange && cfg.densityDpi != s.dpi

        if (s.debug && Diag.takeCtx()) {
            log("createConfigurationContext on $pkg${if (isTarget) "" else " (other)"}: " +
                "density ${cfg.densityDpi} ${dayNight(cfg)}" +
                (if (needsFix) " -> ${s.dpi}" else if (isTarget) " (ok)" else "") + " via ${callers()}")
        }
        if (needsFix) param.args[0] = Configuration(cfg).apply { densityDpi = s.dpi }
    }
}

/**
 * createDisplayContext()/createWindowContext() build Resources without the density override of the
 * context they are called on. Display contexts of the plugin are wrapped; window contexts are only
 * logged (wrapping them could break the window token handling).
 */
private object DerivedContextHook : XC_MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam) {
        val src = param.thisObject as? Context ?: return
        val res = param.result as? Context ?: return
        val s = Settings.get()
        val pkg = try {
            src.packageName
        } catch (t: Throwable) {
            return
        }
        if (pkg != s.target) return

        val before = try {
            res.resources.configuration.densityDpi
        } catch (t: Throwable) {
            return
        }
        if (s.debug && Diag.takeCtx()) {
            log("${param.method.name} on $pkg -> density $before (want ${s.dpi}) via ${callers()}")
        }
        if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI || before == s.dpi) return
        if (param.method.name != "createDisplayContext") return

        val wrapped = withDensity(res, pkg, s.dpi)
        if (wrapped !== res) {
            PluginPaths.markImpl(wrapped.resources)
            param.result = wrapped
        }
    }
}

/** Forces the chosen density whenever a configuration is applied to the plugin's resources. */
private object ResourcesImplHook : XC_MethodHook() {
    private val busy = ThreadLocal<Boolean>()
    private val kept = AtomicInteger(0)
    private val calls = AtomicInteger(0)

    override fun beforeHookedMethod(param: MethodHookParam) {
        if (busy.get() == true) return
        busy.set(true)
        try {
            val idx = param.args.indexOfFirst { it is Configuration }
            if (idx < 0) return
            val cfg = param.args[idx] as Configuration

            val s = Settings.get()
            if (s.debug && calls.getAndIncrement() == 0) log("ResourcesImpl hook is being invoked")
            if (PluginPaths.isEmpty()) return
            if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI) return

            val matched = PluginPaths.matches(param.thisObject)

            if (s.debug && Diag.takeRes()) {
                val apks = PluginPaths.assetPaths(param.thisObject).map { it.substringAfterLast('/') }
                log("ResourcesImpl.updateConfiguration density=${cfg.densityDpi} ${dayNight(cfg)} " +
                    "matched=$matched apks=$apks")
            }

            if (!matched || cfg.densityDpi == s.dpi) return
            param.args[idx] = Configuration(cfg).apply { densityDpi = s.dpi }
            if (kept.getAndIncrement() < 20) {
                log("kept ${s.dpi} dpi on plugin resources (config update carried ${cfg.densityDpi}) via ${callers()}")
            }
        } catch (t: Throwable) {
            log("resources hook failed", t)
        } finally {
            busy.set(false)
        }
    }
}

/**
 * Audits every plugin ResourcesImpl and pushes the chosen density back where it drifted.
 * Does not depend on ResourcesImpl.updateConfiguration being hookable.
 */
internal object Auditor {
    private val busy = ThreadLocal<Boolean>()
    private val audits = AtomicInteger(0)
    private val fixes = AtomicInteger(0)

    fun run(rm: Any, tag: String) {
        if (busy.get() == true) return
        busy.set(true)
        try {
            val s = Settings.get()
            if (s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI || PluginPaths.isEmpty()) return

            val lock: Any = try {
                XposedHelpers.getObjectField(rm, "mLock") ?: rm
            } catch (t: Throwable) {
                rm
            }
            val impls = ArrayList<Any>()
            synchronized(lock) {
                val map = XposedHelpers.getObjectField(rm, "mResourceImpls") as? Map<*, *> ?: return
                for (v in map.values) {
                    val impl = (v as? java.lang.ref.WeakReference<*>)?.get() ?: continue
                    impls.add(impl)
                }
            }

            val compatClass = Class.forName("android.content.res.CompatibilityInfo")
            val types = arrayOf<Class<*>>(Configuration::class.java, DisplayMetrics::class.java, compatClass)
            val summary = LinkedHashMap<String, Int>()
            var matchedCount = 0
            var fixed = 0

            for (impl in impls) {
                val matched = PluginPaths.matches(impl)
                val cfg = XposedHelpers.callMethod(impl, "getConfiguration") as? Configuration ?: continue
                if (s.debug) {
                    val name = if (matched) "PLUGIN" else
                        (PluginPaths.assetPaths(impl).firstOrNull()?.substringAfterLast('/') ?: "?")
                    val key = "$name:${cfg.densityDpi}"
                    summary[key] = (summary[key] ?: 0) + 1
                }
                if (!matched) continue
                matchedCount++
                if (cfg.densityDpi == s.dpi) continue

                val fixedCfg = Configuration(cfg).apply { densityDpi = s.dpi }
                val dm = XposedHelpers.callMethod(impl, "getDisplayMetrics") as? DisplayMetrics ?: continue
                val compat = try {
                    XposedHelpers.callMethod(
                        XposedHelpers.callMethod(impl, "getDisplayAdjustments"),
                        "getCompatibilityInfo",
                    )
                } catch (t: Throwable) {
                    null
                }
                XposedHelpers.callMethod(impl, "updateConfiguration", types, fixedCfg, dm, compat)
                fixed++
            }

            if (fixed > 0 && fixes.getAndIncrement() < 60) {
                log("reapply[$tag]: pushed ${s.dpi} dpi back onto $fixed of $matchedCount plugin resource sets")
            }
            if (s.debug && audits.getAndIncrement() < 100) {
                log("audit[$tag]: plugin sets=$matchedCount fixed=$fixed total sets=${impls.size} $summary")
            }
        } catch (t: Throwable) {
            log("audit[$tag] failed", t)
        } finally {
            busy.set(false)
        }
    }
}

private object ReapplyHook : XC_MethodHook() {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun beforeHookedMethod(param: MethodHookParam) {
        Diag.newWindow()
        val s = Settings.get()
        if (s.debug) {
            val cfg = param.args.getOrNull(0) as? Configuration
            if (cfg != null) log("config change: density ${cfg.densityDpi} ${dayNight(cfg)}")
        }
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        val rm = param.thisObject ?: return
        Auditor.run(rm, "cfg")
        // Catch anything that re-applies density after the dispatch (plugin code, theme switch, ...).
        try {
            handler.postDelayed({ Auditor.run(rm, "late300") }, 300L)
            handler.postDelayed({ Auditor.run(rm, "late1500") }, 1500L)
        } catch (t: Throwable) {
            // ignore
        }
    }
}

/** Window-token resources are updated through a different ResourcesManager entry point. */
private object ActivityResourcesHook : XC_MethodHook() {
    @Volatile private var last = 0L

    override fun afterHookedMethod(param: MethodHookParam) {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 30L) return
        last = now
        val rm = param.thisObject ?: return
        Auditor.run(rm, "activity")
    }
}

/** Verbose only: which density do plugin layouts get inflated with? */
private object InflateHook : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        val s = Settings.get()
        if (!s.debug) return
        val id = param.args.getOrNull(0) as? Int ?: return
        val inflater = param.thisObject as? LayoutInflater ?: return
        try {
            val ctx = inflater.context
            if (ctx.packageName != s.target) return
            if (!Diag.takeInflate()) return
            val res = ctx.resources
            val cfg = res.configuration
            val name = try {
                res.getResourceEntryName(id)
            } catch (t: Throwable) {
                "0x${Integer.toHexString(id)}"
            }
            log("inflate $name ctx=${ctx.javaClass.simpleName} cfgDensity=${cfg.densityDpi} " +
                "metricsDpi=${res.displayMetrics.densityDpi} ${dayNight(cfg)} via ${callers()}")
        } catch (t: Throwable) {
            // ignore
        }
    }
}

/**
 * Verbose only, sampled: plugin classes that read a DisplayMetrics whose density is not the chosen
 * one (which would make the panel look "reset" even though the plugin resources are right).
 */
private object MetricsHook : XC_MethodHook() {
    private val n = AtomicInteger(0)

    override fun afterHookedMethod(param: MethodHookParam) {
        val dm = param.result as? DisplayMetrics ?: return
        val s = Settings.get()
        if (!s.debug || s.dpi !in Prefs.MIN_DPI..Prefs.MAX_DPI || dm.densityDpi == s.dpi) return
        if (n.incrementAndGet() % 40 != 0) return
        if (!Diag.takeMetrics()) return
        val frame = Throwable().stackTrace.firstOrNull { it.className.startsWith("miui.systemui.") } ?: return
        log("getDisplayMetrics gave ${dm.densityDpi} (want ${s.dpi}) to ${frame.className}.${frame.methodName} via ${callers()}")
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
