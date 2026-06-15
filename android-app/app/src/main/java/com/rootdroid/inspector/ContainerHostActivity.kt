package com.rootdroid.inspector

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
        val denied = results.entries.filter { !it.value }.map { it.key.substringAfterLast('.') }
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Permissions denied by user: ${denied.joinToString()}", Toast.LENGTH_SHORT).show()
        }
        loadContainer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }

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
            Toast.makeText(this, "Container APK not found — remove and re-add the app", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val missingPerms = parseApkPermissions(apkFile.absolutePath).filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPerms.isNotEmpty()) {
            statusState.value = "Requesting ${missingPerms.size} permission(s)…"
            permLauncher.launch(missingPerms.toTypedArray())
        } else {
            loadContainer()
        }
    }

    private fun loadContainer() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apkFile = ContainerManager.apkFile(this@ContainerHostActivity, pkg)
            val optDir  = ContainerManager.optDir(this@ContainerHostActivity, pkg)
            val dataDir = ContainerManager.dataDir(this@ContainerHostActivity, pkg)

            System.setProperty("vs.data_dir.$pkg", dataDir.absolutePath)

            withContext(Dispatchers.Main) { statusState.value = "Loading APK into container…" }

            val nativeLib = try {
                packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir
            } catch (_: Exception) { null }

            val result = AppLoader.loadFromPath(
                apkPath      = apkFile.absolutePath,
                optDir       = optDir.absolutePath,
                nativeLibDir = nativeLib,
                parentLoader = classLoader,
            )

            if (result.classLoader == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ContainerHostActivity,
                        "Failed to load APK: ${result.error}",
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
                return@launch
            }

            val ourPid = Process.myPid()
            ContainerManager.registerSession(pkg, ourPid, result.classLoader, apkFile.absolutePath)

            withContext(Dispatchers.Main) { statusState.value = "Invoking app in container…" }

            val msg = AppLoader.invokeApplication(
                context     = this@ContainerHostActivity,
                packageName = pkg,
                loader      = result.classLoader,
                dataDir     = dataDir,
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
        }
    }

    private fun parseApkPermissions(apkPath: String): List<String> {
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)
            info?.requestedPermissions
                ?.filter { it.startsWith("android.permission.") }
                ?.filter { isDangerousPermission(it) }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun isDangerousPermission(perm: String): Boolean {
        return try {
            val info = packageManager.getPermissionInfo(perm, 0)
            (info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) != 0
        } catch (_: Exception) { false }
    }

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
                Text(pkg.split(".").last(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(pkg, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
            Box(
                modifier = Modifier
                    .background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(status, fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
