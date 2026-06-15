package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader

/**
 * Loads a target APK into our own process via DexClassLoader.
 *
 * Because the loaded code runs INSIDE our process, any call to
 * Runtime.exec("su") uses OUR process environment — which already
 * has PATH prepended with the fake su binary written by FakeSuProvider.
 *
 * This is the same fundamental trick used by Rooted Parallel Space /
 * VirtualXposed: the app thinks it is calling real su, but it finds
 * our fake binary first in PATH and gets a fake-root response.
 */
object AppLoader {

    data class LoadResult(
        val classLoader: DexClassLoader?,
        val apkPath: String,
        val error: String? = null,
    )

    fun load(context: Context, packageName: String): LoadResult {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA,
            )
            val apkPath = appInfo.sourceDir
            val optDir = context.getDir(
                "opt_${packageName.replace('.', '_')}",
                Context.MODE_PRIVATE,
            )

            // Ensure our fake bin is injected into PATH before any app code
            // can execute inside this process.
            FakeSuProvider.install(context)

            val loader = DexClassLoader(
                apkPath,
                optDir.absolutePath,
                appInfo.nativeLibraryDir,
                context.classLoader,
            )

            LoadResult(classLoader = loader, apkPath = apkPath)
        } catch (e: PackageManager.NameNotFoundException) {
            LoadResult(classLoader = null, apkPath = "", error = "Package not found: $packageName")
        } catch (e: Exception) {
            LoadResult(classLoader = null, apkPath = "", error = e.message)
        }
    }

    /**
     * Attempt to invoke the app's Application class inside our process.
     * Many apps only do root checks inside Application.onCreate — calling
     * this alone is enough to verify the fake-root intercept works.
     */
    fun invokeApplication(context: Context, packageName: String, loader: DexClassLoader): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA,
            )
            val appClassName = appInfo.className?.takeIf { it.isNotBlank() }
                ?: return "No Application class declared"

            val clazz = loader.loadClass(appClassName)
            val instance = clazz.getDeclaredConstructor().newInstance()

            // Call attach(context) via reflection — required before onCreate
            try {
                val attachMethod = clazz.superclass?.getDeclaredMethod("attach", Context::class.java)
                attachMethod?.isAccessible = true
                attachMethod?.invoke(instance, context)
            } catch (_: Exception) {}

            // Call onCreate
            try {
                val onCreate = clazz.getDeclaredMethod("onCreate")
                onCreate.isAccessible = true
                onCreate.invoke(instance)
            } catch (_: Exception) {}

            "Application loaded — fake su active in process"
        } catch (e: Exception) {
            "Application invoke error: ${e.message}"
        }
    }

    /**
     * Probe which root-detection vectors the loaded app uses.
     * Returns a map of check-name → result using our fake environment.
     */
    fun probeRootChecks(context: Context, loader: DexClassLoader): Map<String, String> {
        val fakeBin = FakeSuProvider.fakeBinPath(context)
        val path    = "$fakeBin:${System.getenv("PATH") ?: "/system/bin:/system/xbin"}"
        val env     = arrayOf("PATH=$path")
        val results = mutableMapOf<String, String>()

        FakeSuProvider.fakeResponses.forEach { (cmd, expected) ->
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), env)
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            results[cmd] = if (out == expected) "✓ spoofed" else "✗ got: $out"
        }

        // Extra: check our fake su binary exists and is executable
        val suFile = java.io.File(fakeBin, "su")
        results["su binary exists"] = if (suFile.canExecute()) "✓" else "✗"

        return results
    }
}
