package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader
import java.io.File

object AppLoader {

    data class LoadResult(
        val classLoader: DexClassLoader?,
        val apkPath: String,
        val error: String? = null,
    )

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

    fun load(context: Context, packageName: String): LoadResult {
        return try {
            val info   = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
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

    /**
     * Invoke Application.onCreate() in-process using a ContainerContext so the
     * app's file/db operations redirect to the isolated data dir.
     *
     * Returns a status string — never throws.
     */
    fun invokeApplication(
        context: Context,
        packageName: String,
        loader: DexClassLoader,
        dataDir: File? = null,
    ): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val appClassName = info.className?.takeIf { it.isNotBlank() }
                ?: return "No Application class — skipped"

            val clazz = try {
                loader.loadClass(appClassName)
            } catch (e: ClassNotFoundException) {
                return "ClassNotFound: $appClassName"
            } catch (e: Exception) {
                return "LoadClass error: ${e.message?.take(60)}"
            }

            val appCtx: Context = if (dataDir != null) {
                ContainerContext(context, packageName, dataDir)
            } else {
                context
            }

            val instance = try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (e: Exception) {
                return "Instantiation error: ${e.message?.take(60)}"
            }

            try {
                clazz.superclass
                    ?.getDeclaredMethod("attach", Context::class.java)
                    ?.apply { isAccessible = true; invoke(instance, appCtx) }
            } catch (_: Exception) {}

            try {
                clazz.getDeclaredMethod("onCreate")
                    .apply { isAccessible = true; invoke(instance) }
            } catch (e: Exception) {
                return "onCreate threw: ${e.javaClass.simpleName}: ${e.message?.take(60)}"
            }

            "Loaded in container"
        } catch (e: SecurityException) {
            "Permission denied: ${e.message?.take(80)}"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message?.take(80)}"
        }
    }

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
