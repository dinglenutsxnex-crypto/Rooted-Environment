package com.rootdroid.inspector

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rootdroid.inspector.service.InspectorOverlayService
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.AppLoader
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.FakeSuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContainerHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var pkg: String
    private val statusState = mutableStateOf("Setting up container…")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys.map { it.substringAfterLast('.') }
        if (denied.isNotEmpty()) {
            showToast("Permissions denied: ${denied.joinToString()} — container may be limited")
        }
        loadContainer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }

        installCrashNet()

        setContent {
            RootDroidTheme {
                val status by statusState
                LaunchScreen(pkg = pkg, status = status)
            }
        }

        FakeSuProvider.install(this)
        System.setProperty("vs.fake_bin_path", FakeSuProvider.fakeBinPath(this))

        val apkFile = ContainerManager.apkFile(this, pkg)
        if (!apkFile.exists()) {
            showToast("Container APK not found — remove and re-add the app")
            finish()
            return
        }

        val missingPerms = parseApkPermissions(apkFile.absolutePath)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missingPerms.isNotEmpty()) {
            statusState.value = "Requesting ${missingPerms.size} permission(s)…"
            permLauncher.launch(missingPerms.toTypedArray())
        } else {
            loadContainer()
        }
    }

    private fun loadContainer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apkFile = ContainerManager.apkFile(this@ContainerHostActivity, pkg)
                val optDir  = ContainerManager.optDir(this@ContainerHostActivity, pkg)
                val dataDir = ContainerManager.dataDir(this@ContainerHostActivity, pkg)
                System.setProperty("vs.data_dir.$pkg", dataDir.absolutePath)

                withContext(Dispatchers.Main) { statusState.value = "Loading APK…" }

                // Ensure APK is read-only — Android 8+ rejects DexClassLoader on writable paths.
                if (apkFile.canWrite()) {
                    apkFile.setWritable(false, false)
                    apkFile.setReadable(true, false)
                }

                val result = AppLoader.loadFromPath(
                    apkPath      = apkFile.absolutePath,
                    optDir       = optDir.absolutePath,
                    nativeLibDir = null,
                    parentLoader = classLoader,
                )

                if (result.classLoader == null) {
                    withContext(Dispatchers.Main) {
                        showToast("APK load failed: ${result.error}")
                        finish()
                    }
                    return@launch
                }

                val ourPid = Process.myPid()
                ContainerManager.registerSession(pkg, ourPid, result.classLoader, apkFile.absolutePath)

                withContext(Dispatchers.Main) { statusState.value = "Invoking Application…" }

                val msg = AppLoader.invokeApplication(
                    context     = this@ContainerHostActivity,
                    packageName = pkg,
                    loader      = result.classLoader,
                    dataDir     = dataDir,
                    apkPath     = apkFile.absolutePath,
                )

                withContext(Dispatchers.Main) {
                    statusState.value = msg

                    if (Settings.canDrawOverlays(this@ContainerHostActivity)) {
                        startForegroundService(
                            Intent(this@ContainerHostActivity, InspectorOverlayService::class.java)
                                .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
                                .putExtra(InspectorOverlayService.EXTRA_PID, ourPid)
                        )
                    }
                }

            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast("Container error: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                    finish()
                }
            }
        }
    }

    /**
     * Sets a per-thread uncaught exception handler so any Throwable that escapes
     * the coroutine (e.g. from reflection callbacks on pool threads) shows as Toast
     * instead of an ANR / crash dialog.
     */
    private fun installCrashNet() {
        val main = Handler(Looper.getMainLooper())
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            main.post {
                if (!isFinishing) {
                    showToast("Crash: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                }
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun parseApkPermissions(apkPath: String): List<String> {
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)
            info?.requestedPermissions
                ?.filter { it.startsWith("android.permission.") && isDangerous(it) }
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
    }

    private fun isDangerous(perm: String): Boolean = try {
        (packageManager.getPermissionInfo(perm, 0).protectionLevel
                and PermissionInfo.PROTECTION_DANGEROUS) != 0
    } catch (_: Throwable) { false }

    override fun onDestroy() {
        super.onDestroy()
        ContainerManager.unregisterSession(pkg)
    }
}

@Composable
private fun LaunchScreen(pkg: String, status: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    pkg.split(".").last(),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                )
                Text(pkg, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
            Box(
                modifier = Modifier
                    .background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    status,
                    fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
