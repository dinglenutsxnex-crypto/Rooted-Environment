package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.Intent
import com.rootdroid.inspector.ContainerHostActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ContainerApp(
    val packageName: String,
    val appName: String,
    val installedAt: Long = System.currentTimeMillis(),
    val apkSizeBytes: Long = 0L,
)

object ContainerManager {

    private const val PREFS = "vs_container_registry"
    private const val KEY   = "apps_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Paths ─────────────────────────────────────────────────────────────────

    private fun root(ctx: Context)           = File(ctx.filesDir, "containers")
    fun apkFile(ctx: Context, pkg: String)   = File(root(ctx), "$pkg/base.apk")
    fun dataDir(ctx: Context, pkg: String)   = File(root(ctx), "$pkg/data").also { it.mkdirs() }
    fun optDir(ctx: Context, pkg: String)    = File(root(ctx), "$pkg/opt").also  { it.mkdirs() }
    fun isInstalled(ctx: Context, pkg: String) = apkFile(ctx, pkg).exists()

    // ── Install ───────────────────────────────────────────────────────────────

    /** Copies APK into private container dir. Returns true on success. */
    suspend fun install(ctx: Context, pkg: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val pm    = ctx.packageManager
            val info  = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()

            val dst = apkFile(ctx, pkg).also { it.parentFile!!.mkdirs() }
            val src = File(info.sourceDir)
            src.copyTo(dst, overwrite = true)

            // Copy split APKs if present
            info.splitSourceDirs?.forEachIndexed { i, split ->
                File(split).copyTo(File(dst.parentFile!!, "split_$i.apk"), overwrite = true)
            }

            // Init isolated data + opt dirs
            dataDir(ctx, pkg)
            optDir(ctx, pkg)

            // Register
            val apps = list(ctx).toMutableList()
            if (apps.none { it.packageName == pkg }) {
                apps += ContainerApp(
                    packageName  = pkg,
                    appName      = label,
                    apkSizeBytes = dst.length(),
                )
                saveList(ctx, apps)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    fun uninstall(ctx: Context, pkg: String) {
        File(root(ctx), pkg).deleteRecursively()
        saveList(ctx, list(ctx).filter { it.packageName != pkg })
    }

    // ── List ──────────────────────────────────────────────────────────────────

    fun list(ctx: Context): List<ContainerApp> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    }

    // ── Launch via ContainerHostActivity ──────────────────────────────────────

    fun launch(ctx: Context, pkg: String) {
        ctx.startActivity(
            Intent(ctx, ContainerHostActivity::class.java)
                .putExtra(ContainerHostActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun saveList(ctx: Context, apps: List<ContainerApp>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.encodeToString(apps)).apply()
    }
}
