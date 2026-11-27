# QS DPI (LSPosed module)

Gives the HyperOS control center / quick settings panel its own DPI, independent of the system DPI.
Higher DPI = smaller padding, tiles and text. Example: system 440 -> panel 480 makes the panel ~92% of its size.

## Build
- Open this folder in Android Studio (JDK 17) and run **Build > Build APK(s)**, or `gradle assembleDebug`.
- If `api.xposed.info` can't be reached, download `api-82.jar`, put it in `app/libs/` and replace the
  dependency in `app/build.gradle.kts` with `compileOnly(files("libs/api-82.jar"))`.
- Rename the package (`com.example.qsdpi`) if you like; keep `xposed_init`, `Prefs.MODULE_PACKAGE`
  and the manifest/namespace in sync.

## Install
1. Install the APK, enable it in LSPosed, tick **System UI (com.android.systemui)** in its scope.
2. Open the app, enter the panel DPI, press **Save and restart System UI** (needs root), or restart SystemUI/reboot yourself.
3. Pull down the control center.

## Check it works
LSPosed > Logs (Modules), search for `QsDpi`. You should see:
```
[QsDpi] hooks installed in com.android.systemui
[QsDpi] miui.systemui.plugin: density 440 -> 480
```
No second line? Tick "Verbose logging", restart SystemUI again and look at which package contexts are created.

## App
Big animated DPI dial, 400-600 slider (with a marker for your system DPI), +/- fine steps, an on/off switch,
and two round buttons: **Save** and **Save + restart System UI** (root). "Advanced" has the target package and verbose logging.

## How it works
Three layers keep the plugin at your DPI:
0. (build 4) Every `ResourcesKey` for the plugin APK gets the density baked into its override configuration, so
   ResourcesManager re-applies it on every global configuration change (dark mode included).
1. The plugin's Context is wrapped in a density-only configuration override when SystemUI creates it.
2. `ResourcesImpl.updateConfiguration` is hooked: whenever Android applies a configuration (dark mode, theme, rotation...)
   to Resources that load the plugin APK, the density is forced back to your value.

Original description:
SystemUI creates the plugin's Context through `ContextImpl.createApplicationContext()` (or
`createPackageContextAsUser()`). The module wraps the result of that call for `miui.systemui.plugin` in
`createConfigurationContext(Configuration().apply { densityDpi = X })`. Only the density is overridden, everything else keeps tracking the real configuration.

## Known limits
- Everything that the plugin builds from its own Context is affected, not strictly the QS tiles.
- Text sizes follow the DPI too (sp is density-scaled).
- Untested on 3.0.305: class/loader details can differ between builds.
