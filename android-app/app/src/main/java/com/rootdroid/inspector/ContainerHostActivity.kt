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
import com.rootdroid.inspector.virtual.UserSpaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContainerHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var pkg: String
    private val statusState = mutableStateOf("Starting container…")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> loadContainer() }

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
            showToast("Container APK missing — remove and re-add the app")
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

                // ── Step 1: Load dex for static inspection / method hooking ──
                withContext(Dispatchers.Main) { statusState.value = "Loading dex…" }

                // Android 8+ requires dex path to be read-only for DexClassLoader
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

                // Register the dex loader for method enumeration in the overlay
                if (result.classLoader != null) {
                    ContainerManager.registerSession(
                        pkg     = pkg,
                        pid     = -1, // PID not known yet
                        loader  = result.classLoader,
                        apkPath = apkFile.absolutePath,
                    )
                }

                // ── Step 2: Launch inside isolated container user via root ──
                val rooted = UserSpaceManager.isRooted()

                val appPid: Int
                if (rooted) {
                    withContext(Dispatchers.Main) { statusState.value = "Launching in container user…" }
                    appPid = UserSpaceManager.launchInContainer(this@ContainerHostActivity, pkg)
                    if (appPid < 0) {
                        withContext(Dispatchers.Main) {
                            showToast("Failed to launch $pkg in container. Is it installed?")
                            finish()
                        }
                        return@launch
                    }
                } else {
                    // No root — can only do dex inspection, no real container isolation
                    withContext(Dispatchers.Main) {
                        showToast("No root detected — container isolation unavailable. Dex inspection only.")
                    }
                    appPid = Process.myPid()
                }

                // ── Step 3: Update session with real PID ──
                if (result.classLoader != null) {
                    ContainerManager.registerSession(
                        pkg     = pkg,
                        pid     = appPid,
                        loader  = result.classLoader,
                        apkPath = apkFile.absolutePath,
                    )
                }

                // ── Step 4: Start overlay inspector ──
                withContext(Dispatchers.Main) {
                    statusState.value = "Container running · PID $appPid"

                    if (Settings.canDrawOverlays(this@ContainerHostActivity)) {
                        startForegroundService(
                            Intent(this@ContainerHostActivity, InspectorOverlayService::class.java)
                                .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
                                .putExtra(InspectorOverlayService.EXTRA_PID, appPid)
                        )
                    }

                    // Short delay so the user sees the "running" status, then close
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 800)
                }

            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast("Container error: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                    finish()
                }
            }
        }
    }

    private fun installCrashNet() {
        val main = Handler(Looper.getMainLooper())
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            main.post {
                if (!isFinishing) showToast("Crash: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
        // Don't unregister — the container user keeps running in the background
        // and the overlay service stays attached to it
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
