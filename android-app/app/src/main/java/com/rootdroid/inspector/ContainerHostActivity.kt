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
import com.rootdroid.inspector.virtual.ContainerEngine
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.FakeSuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    ) { _ ->
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

                withContext(Dispatchers.Main) { statusState.value = "Inspecting APK…" }

                // Android 8+ (API 26+): dex path must not be writable or DexClassLoader throws SecurityException.
                if (apkFile.canWrite()) {
                    apkFile.setWritable(false, false)
                    apkFile.setReadable(true, false)
                }

                // Load dex for static analysis / class enumeration.
                // We deliberately do NOT call invokeApplication() — running a foreign
                // app's Application.onCreate() in-process crashes for any real app
                // because it hits Binder, ContentProviders, and native libs that
                // can't bootstrap inside a foreign process without a full VA framework.
                val result = AppLoader.loadFromPath(
                    apkPath      = apkFile.absolutePath,
                    optDir       = optDir.absolutePath,
                    nativeLibDir = null,
                    parentLoader = classLoader,
                )

                if (result.classLoader != null) {
                    ContainerManager.registerSession(
                        pkg       = pkg,
                        pid       = Process.myPid(),
                        loader    = result.classLoader,
                        apkPath   = apkFile.absolutePath,
                    )
                }

                withContext(Dispatchers.Main) { statusState.value = "Launching…" }

                // Launch the real system-installed app. The OS handles activity
                // management, process isolation, and permission grants for the app's
                // own UID. We then track its PID and attach the overlay inspector.
                val launched = tryLaunchViaSystem()

                if (!launched) {
                    withContext(Dispatchers.Main) {
                        showToast("No launcher activity found for $pkg")
                        finish()
                    }
                    return@launch
                }

                // Give the app process a moment to start, then find its PID.
                delay(1500)
                val appPid = ContainerEngine.findPid(this@ContainerHostActivity, pkg)

                // Update session with real PID if we found it.
                if (result.classLoader != null && appPid > 0) {
                    ContainerManager.registerSession(
                        pkg     = pkg,
                        pid     = appPid,
                        loader  = result.classLoader,
                        apkPath = apkFile.absolutePath,
                    )
                }

                withContext(Dispatchers.Main) {
                    if (Settings.canDrawOverlays(this@ContainerHostActivity)) {
                        val overlayPid = if (appPid > 0) appPid else Process.myPid()
                        startForegroundService(
                            Intent(this@ContainerHostActivity, InspectorOverlayService::class.java)
                                .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
                                .putExtra(InspectorOverlayService.EXTRA_PID, overlayPid)
                        )
                    }
                    // Close the loading screen — the target app is now in the foreground.
                    finish()
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
     * Launch the system-installed app normally.
     * Returns true if an intent was found and fired.
     */
    private fun tryLaunchViaSystem(): Boolean {
        val intent = packageManager
            .getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return false
        startActivity(intent)
        return true
    }

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
