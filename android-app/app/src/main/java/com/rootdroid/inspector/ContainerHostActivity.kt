package com.rootdroid.inspector

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.lifecycle.lifecycleScope
import com.rootdroid.inspector.service.InspectorOverlayService
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.AppLoader
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.FakeSuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts a containerized app.
 *
 * Flow:
 *  1. Installs fake su env into this process.
 *  2. Loads the APK from our private container copy via DexClassLoader → the app runs
 *     inside THIS process (same PID = Process.myPid()).
 *  3. Registers the session in ContainerManager.activeSessions so the overlay
 *     can discover it and attach logcat/method enumeration.
 *  4. Fires InspectorOverlayService with pkg + our PID → overlay auto-attaches.
 */
class ContainerHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }

        // Show loading screen
        val statusState = mutableStateOf("Setting up container…")
        setContent {
            RootDroidTheme {
                val status by statusState
                LaunchScreen(pkg = pkg, status = status)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Fake su + env
            FakeSuProvider.install(this@ContainerHostActivity)
            System.setProperty("vs.fake_bin_path",
                FakeSuProvider.fakeBinPath(this@ContainerHostActivity))
            System.setProperty("vs.data_dir.$pkg",
                ContainerManager.dataDir(this@ContainerHostActivity, pkg).absolutePath)

            // 2. Load APK from container copy
            val apkFile = ContainerManager.apkFile(this@ContainerHostActivity, pkg)
            val optDir  = ContainerManager.optDir(this@ContainerHostActivity, pkg)

            if (!apkFile.exists()) {
                withContext(Dispatchers.Main) {
                    statusState.value = "Container APK missing — remove and re-install"
                }
                delay(2500); finish(); return@launch
            }

            withContext(Dispatchers.Main) { statusState.value = "Loading from container…" }

            val nativeLib = try {
                packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir
            } catch (_: Exception) { null }

            val result = AppLoader.loadFromPath(
                apkPath      = apkFile.absolutePath,
                optDir       = optDir.absolutePath,
                nativeLibDir = nativeLib,
                parentLoader = classLoader,
            )

            val ourPid = Process.myPid()

            if (result.classLoader != null) {
                // 3. Register session — overlay will detect this and attach
                ContainerManager.registerSession(pkg, ourPid, result.classLoader, apkFile.absolutePath)

                withContext(Dispatchers.Main) { statusState.value = "Initialising app in container…" }
                val msg = AppLoader.invokeApplication(this@ContainerHostActivity, pkg, result.classLoader)
                withContext(Dispatchers.Main) { statusState.value = msg }

                // 4. Start / update overlay with our PID
                if (Settings.canDrawOverlays(this@ContainerHostActivity)) {
                    withContext(Dispatchers.Main) {
                        startForegroundService(
                            Intent(this@ContainerHostActivity, InspectorOverlayService::class.java)
                                .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
                                .putExtra(InspectorOverlayService.EXTRA_PID, ourPid)
                        )
                    }
                }
                // Stay alive — app running in-process, overlay streams our logcat

            } else {
                // Fallback: launch system app, register with system PID
                withContext(Dispatchers.Main) {
                    statusState.value = "In-process load failed — launching system app + overlay"
                }
                delay(600)

                // Start overlay before launching so it's visible over the system app
                if (Settings.canDrawOverlays(this@ContainerHostActivity)) {
                    withContext(Dispatchers.Main) {
                        startForegroundService(
                            Intent(this@ContainerHostActivity, InspectorOverlayService::class.java)
                                .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
                        )
                    }
                }

                packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { withContext(Dispatchers.Main) { startActivity(it) } }

                // Register with system PID (found after launch)
                delay(1500)
                val sysPid = try {
                    Runtime.getRuntime().exec(arrayOf("pidof", pkg))
                        .inputStream.bufferedReader().readText().trim()
                        .split(" ").lastOrNull()?.toIntOrNull() ?: -1
                } catch (_: Exception) { -1 }

                ContainerManager.registerSession(pkg, sysPid, null, apkFile.absolutePath)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        ContainerManager.unregisterSession(pkg)
    }
}

// ── Loading screen ────────────────────────────────────────────────────────────

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
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pkg.split(".").last(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(pkg, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
            Box(
                modifier = Modifier.background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(status, fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
