package com.rootdroid.inspector

import android.app.AppOpsManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rootdroid.inspector.model.InstalledApp
import com.rootdroid.inspector.repository.InstalledAppsRepository
import com.rootdroid.inspector.ui.AppPickerSheet
import com.rootdroid.inspector.ui.HomeScreen
import com.rootdroid.inspector.ui.PermissionsScreen
import com.rootdroid.inspector.ui.theme.RootDroidTheme
import com.rootdroid.inspector.virtual.ContainerApp
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.FakeSuProvider
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private lateinit var repo: InstalledAppsRepository

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* re-check fires via onResume lifecycle observer */ }

    private val usagePermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    fun requestOverlayPermission() {
        overlayPermLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    fun requestUsagePermission() {
        usagePermLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    fun hasUsageStatsPerm(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
                AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = InstalledAppsRepository(this)
        FakeSuProvider.install(this)

        setContent {
            RootDroidTheme {
                VirtualSpaceApp(activity = this, repo = repo)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun VirtualSpaceApp(activity: MainActivity, repo: InstalledAppsRepository) {
    val scope = rememberCoroutineScope()

    // ── Permission state (re-checked on every resume) ─────────────────────────
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(activity)) }
    var usageOk   by remember { mutableStateOf(activity.hasUsageStatsPerm()) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOk = Settings.canDrawOverlays(activity)
                usageOk   = activity.hasUsageStatsPerm()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // ── Permission gate ───────────────────────────────────────────────────────
    if (!overlayOk) {
        PermissionsScreen(
            overlayGranted = overlayOk,
            usageGranted   = usageOk,
            onGrantOverlay = { activity.requestOverlayPermission() },
            onGrantUsage   = { activity.requestUsagePermission() },
            onContinue     = { /* disabled until overlay granted */ },
        )
        return
    }

    // ── Main app state ────────────────────────────────────────────────────────
    var containerApps  by remember { mutableStateOf<List<ContainerApp>>(emptyList()) }
    var installedApps  by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loadingApps    by remember { mutableStateOf(false) }
    var showPicker     by remember { mutableStateOf(false) }
    var installingPkg  by remember { mutableStateOf<String?>(null) }

    // Load container app list once
    LaunchedEffect(overlayOk) {
        containerApps = ContainerManager.list(activity)
    }

    fun refresh() { containerApps = ContainerManager.list(activity) }

    fun installApp(app: InstalledApp) {
        if (containerApps.any { it.packageName == app.packageName }) return
        showPicker    = false
        installingPkg = app.packageName
        scope.launch {
            ContainerManager.install(activity, app.packageName)
            installingPkg = null
            refresh()
        }
    }

    fun removeApp(app: ContainerApp) {
        scope.launch {
            withContext(Dispatchers.IO) { ContainerManager.uninstall(activity, app.packageName) }
            refresh()
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    HomeScreen(
        containerApps  = containerApps,
        installingPkg  = installingPkg,
        onAddApp = {
            showPicker = true
            loadingApps = true
            scope.launch {
                installedApps = repo.getAllInstalledApps()
                loadingApps = false
            }
        },
        onLaunch = { app -> ContainerManager.launch(activity, app.packageName) },
        onRemove = { app -> removeApp(app) },
        getIcon  = { pkg -> repo.getIcon(pkg) },
    )

    if (showPicker) {
        AppPickerSheet(
            apps      = installedApps,
            isLoading = loadingApps,
            onSelect  = { app -> installApp(app) },
            onDismiss = { showPicker = false },
        )
    }
}
