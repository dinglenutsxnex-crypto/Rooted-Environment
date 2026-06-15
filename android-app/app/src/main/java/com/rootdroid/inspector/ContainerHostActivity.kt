package com.rootdroid.inspector

import android.content.Intent
import android.os.Bundle
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
 * Hosts a containerized app launch:
 *  1. Starts the InspectorOverlayService immediately (auto-overlay).
 *  2. Installs fake su environment into the process.
 *  3. Loads the APK from the container copy (not the system APK) via DexClassLoader.
 *  4. Invokes Application.onCreate() in-process so the app's init code
 *     runs with our fake-root PATH and isolated data dir.
 *  5. Falls back to launching the system app if in-process load fails.
 */
class ContainerHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }

        // ── 1. Auto-launch overlay ────────────────────────────────────────────
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(
                Intent(this, InspectorOverlayService::class.java)
                    .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
            )
        }

        // ── 2. Show loading UI ────────────────────────────────────────────────
        var statusText by mutableStateOf("Preparing container…")

        setContent {
            RootDroidTheme {
                LaunchScreen(pkg = pkg, status = statusText)
            }
        }

        // ── 3. Setup + load in background ────────────────────────────────────
        lifecycleScope.launch(Dispatchers.IO) {
            FakeSuProvider.install(this@ContainerHostActivity)

            val apkFile  = ContainerManager.apkFile(this@ContainerHostActivity, pkg)
            val optDir   = ContainerManager.optDir(this@ContainerHostActivity, pkg)
            val dataDir  = ContainerManager.dataDir(this@ContainerHostActivity, pkg)

            // Inject data dir as a system property so app code can read it
            System.setProperty("vs.data_dir.$pkg", dataDir.absolutePath)

            if (!apkFile.exists()) {
                withContext(Dispatchers.Main) { statusText = "Container APK missing — reinstall" }
                delay(2000)
                finish()
                return@launch
            }

            withContext(Dispatchers.Main) { statusText = "Loading from container…" }

            val nativeLibDir = try {
                packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir
            } catch (_: Exception) { null }

            val result = AppLoader.loadFromPath(
                apkPath      = apkFile.absolutePath,
                optDir       = optDir.absolutePath,
                nativeLibDir = nativeLibDir,
                parentLoader = classLoader,
            )

            if (result.classLoader != null) {
                withContext(Dispatchers.Main) { statusText = "Initialising app…" }
                val msg = AppLoader.invokeApplication(this@ContainerHostActivity, pkg, result.classLoader)
                withContext(Dispatchers.Main) { statusText = msg }
                // Stay alive — the app is running in-process; overlay is streaming its logs
            } else {
                // ── Fallback: launch system app (overlay still runs) ──────────
                withContext(Dispatchers.Main) {
                    statusText = "In-process load failed — launching system app with overlay"
                }
                delay(800)
                packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                finish()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LaunchScreen(pkg: String, status: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    pkg.split(".").last(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    pkg,
                    fontSize = 10.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Box(
                modifier = Modifier
                    .background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    status,
                    fontSize = 11.sp,
                    color = TextSecond,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                "Overlay debugger is active",
                fontSize = 10.sp,
                color = Accent.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
