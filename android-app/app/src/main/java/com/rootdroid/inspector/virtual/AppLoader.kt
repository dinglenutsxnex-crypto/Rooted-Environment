package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader

/**
 * Loads a target APK into our own process via DexClassLoader.
 *
 * Because the loaded code runs inside our process, any call to
 * Runtime.exec("su") uses OUR process environment — which already
 * has PATH prepended with the fake su binary from FakeSuProvider.
 *
 * loadFromPath() is the primary entry point when launching from a
 * container copy (not the system APK). load() falls back to the
 * system APK path (used for probing).
 */
object AppLoader {

    data class LoadResult(
        val classLoader: DexClassLoader?,
        val apkPath: String,
        val error: String? = null,
    )

    // ── Load from an explicit APK path (container copy) ───────────────────────

    fun loadFromPath(
        apkPath: String,
        optDir: String,
        nativeLibDir: String?,
        parentLoader: ClassLoader,
    ): LoadResult {
        return try {
            val loader = DexClassLoader(apkPath, optDir, nativeLibDir, parentLoader)
            LoadResult(classLoader = loader, apkPath = apkPath)
        } catch (e: Exception) {
            LoadResult(classLoader = null, apkPath = apkPath, error = e.message)
        }
    }

    // ── Load from system APK (probe / fallback) ───────────────────────────────

    fun load(context: Context, packageName: String): LoadResult {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val optDir = context.getDir("opt_${packageName.replace('.', '_')}", Context.MODE_PRIVATE)
            FakeSuProvider.install(context)
            loadFromPath(
                apkPath      = info.sourceDir,
                optDir       = optDir.absolutePath,
                nativeLibDir = info.nativeLibraryDir,
                parentLoader = context.classLoader,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            LoadResult(classLoader = null, apkPath = "", error = "Package not found: $packageName")
        } catch (e: Exception) {
            LoadResult(classLoader = null, apkPath = "", error = e.message)
        }
    }

    // ── Invoke Application.onCreate in-process ────────────────────────────────

    fun invokeApplication(context: Context, packageName: String, loader: DexClassLoader): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val appClassName = info.className?.takeIf { it.isNotBlank() }
                ?: return "No Application class declared"

            val clazz    = loader.loadClass(appClassName)
            val instance = clazz.getDeclaredConstructor().newInstance()

            // attach(Context) must be called before onCreate
            try {
                clazz.superclass?.getDeclaredMethod("attach", Context::class.java)?.apply {
                    isAccessible = true; invoke(instance, context)
                }
            } catch (_: Exception) {}

            try {
                clazz.getDeclaredMethod("onCreate").apply { isAccessible = true; invoke(instance) }
            } catch (_: Exception) {}

            "Application loaded — fake su active in process"
        } catch (e: Exception) {
            "Application invoke error: ${e.message}"
        }
    }

    // ── Root-check probe ──────────────────────────────────────────────────────

    fun probeRootChecks(context: Context, loader: DexClassLoader): Map<String, String> {
        val fakeBin = FakeSuProvider.fakeBinPath(context)
        val path    = "$fakeBin:${System.getenv("PATH") ?: "/system/bin:/system/xbin"}"
        val env     = arrayOf("PATH=$path")
        val results = mutableMapOf<String, String>()

        FakeSuProvider.fakeResponses.forEach { (cmd, expected) ->
            val p   = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), env)
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            results[cmd] = if (out == expected) "✓ spoofed" else "✗ got: $out"
        }

        val suFile = java.io.File(fakeBin, "su")
        results["su binary exists"] = if (suFile.canExecute()) "✓" else "✗"
        return results
    }
}
