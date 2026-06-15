package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * ContextWrapper that redirects all file/db/cache paths to the app's isolated
 * container directory instead of the host app's private storage.
 *
 * Also spoofs getPackageName() so the loaded app thinks it is running as itself.
 *
 * Passes through everything else (resources, system services, etc.) to the real
 * host context — meaning the loaded code still runs inside our process with our
 * permissions.
 */
class ContainerContext(
    base: Context,
    val pkg: String,
    val containerDataDir: File,
) : ContextWrapper(base) {

    private fun sub(name: String) = File(containerDataDir, name).also { it.mkdirs() }

    override fun getFilesDir()         = sub("files")
    override fun getCacheDir()         = sub("cache")
    override fun getCodeCacheDir()     = sub("code_cache")
    override fun getNoBackupFilesDir() = sub("no_backup")
    override fun getDir(name: String, mode: Int) = sub("app_$name")

    override fun getDatabasePath(name: String): File =
        File(sub("databases"), name)

    override fun getExternalFilesDir(type: String?): File =
        File(containerDataDir, if (type != null) "external/$type" else "external").also { it.mkdirs() }

    override fun getPackageName(): String = pkg

    override fun getPackageCodePath(): String =
        File(containerDataDir.parentFile!!, "base.apk").absolutePath

    override fun getPackageResourcePath(): String =
        File(containerDataDir.parentFile!!, "base.apk").absolutePath

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("vs__${pkg}__$name", mode)
}
