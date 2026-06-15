package com.rootdroid.inspector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.rootdroid.inspector.model.InstalledApp
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.ManagedApp
import com.rootdroid.inspector.model.ProcessInfo
import com.rootdroid.inspector.repository.InstalledAppsRepository
import com.rootdroid.inspector.service.InspectorOverlayService
import com.rootdroid.inspector.ui.AppPickerSheet
import com.rootdroid.inspector.ui.HomeScreen
import com.rootdroid.inspector.ui.InspectorScreen
import com.rootdroid.inspector.ui.theme.RootDroidTheme
import com.rootdroid.inspector.virtual.ContainerEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : ComponentActivity() {

    private lateinit var repo: InstalledAppsRepository

    val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = InstalledAppsRepository(this)
        ContainerEngine.init(this)   // install fake su + env

        setContent {
            RootDroidTheme {
                VirtualSpaceApp(repo = repo, activity = this)
            }
        }
    }

    fun requestOverlayPermission() {
        overlayPermLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }
}

@Composable
fun VirtualSpaceApp(repo: InstalledAppsRepository, activity: MainActivity) {
    val scope = rememberCoroutineScope()

    var managedApps       by remember { mutableStateOf<List<ManagedApp>>(emptyList()) }
    var installedApps     by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingInstalled by remember { mutableStateOf(false) }
    var showPicker        by remember { mutableStateOf(false) }
    var inspectedApp      by remember { mutableStateOf<ManagedApp?>(null) }
    var rootSimActive     by remember { mutableStateOf(false) }

    var logs        by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var processInfo by remember { mutableStateOf<ProcessInfo?>(null) }
    var openFiles   by remember { mutableStateOf<List<String>>(emptyList()) }
    var pid         by remember { mutableIntStateOf(-1) }
    var isRunning   by remember { mutableStateOf(false) }
    var overlayActive by remember { mutableStateOf(false) }
    var logJob      by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        managedApps   = repo.loadManagedApps()
        rootSimActive = ContainerEngine.rootSimulationActive(activity)
    }

    fun refreshPid(app: ManagedApp) {
        scope.launch {
            val found = ContainerEngine.findPid(activity, app.packageName)
            if (found > 0) {
                pid       = found
                isRunning = true
                processInfo = ContainerEngine.getProcessInfo(activity, found)
                openFiles   = ContainerEngine.getOpenFiles(activity, found)
                logJob?.cancel()
                logJob = ContainerEngine.streamLogcat(found)
                    .onEach { logs = logs + it }
                    .launchIn(scope)
            }
        }
    }

    fun openSpace(app: ManagedApp) {
        inspectedApp = app
        logs = emptyList(); processInfo = null; openFiles = emptyList()
        pid = -1; isRunning = false
        logJob?.cancel()
        refreshPid(app)
    }

    fun launchInSpace(app: ManagedApp) {
        ContainerEngine.launchInSpace(activity, app.packageName)
        scope.launch { delay(1500); refreshPid(app) }
    }

    fun killInSpace() {
        scope.launch {
            ContainerEngine.killProcess(activity, pid)
            isRunning = false; pid = -1
            logJob?.cancel()
        }
    }

    fun toggleOverlay(app: ManagedApp) {
        if (!Settings.canDrawOverlays(activity)) {
            activity.requestOverlayPermission(); return
        }
        if (overlayActive) {
            activity.stopService(Intent(activity, InspectorOverlayService::class.java))
            overlayActive = false
        } else {
            activity.startForegroundService(
                Intent(activity, InspectorOverlayService::class.java).apply {
                    putExtra(InspectorOverlayService.EXTRA_PACKAGE, app.packageName)
                    putExtra(InspectorOverlayService.EXTRA_PID, pid)
                }
            )
            overlayActive = true
        }
    }

    when {
        inspectedApp != null -> {
            val app = inspectedApp!!
            InspectorScreen(
                app = app,
                icon = repo.getIcon(app.packageName),
                pid = pid,
                isRunning = isRunning,
                logs = logs,
                calls = emptyList(),
                memoryRegions = emptyList(),
                openFiles = openFiles,
                processInfo = processInfo,
                onBack = {
                    logJob?.cancel(); inspectedApp = null
                    if (overlayActive) {
                        activity.stopService(Intent(activity, InspectorOverlayService::class.java))
                        overlayActive = false
                    }
                },
                onLaunch  = { launchInSpace(app) },
                onKill    = { killInSpace() },
                onToggleOverlay = { toggleOverlay(app) },
                overlayActive = overlayActive,
            )
        }
        else -> {
            HomeScreen(
                managedApps   = managedApps,
                rootSimActive = rootSimActive,
                onAddApp = {
                    showPicker = true; isLoadingInstalled = true
                    scope.launch { installedApps = repo.getAllInstalledApps(); isLoadingInstalled = false }
                },
                onLaunch  = { app -> openSpace(app); launchInSpace(app) },
                onRemove  = { app ->
                    managedApps = managedApps.filter { it.packageName != app.packageName }
                    scope.launch { repo.saveManagedApps(managedApps) }
                },
                getIcon   = { repo.getIcon(it) },
            )
            if (showPicker) {
                AppPickerSheet(
                    apps = installedApps,
                    isLoading = isLoadingInstalled,
                    onSelect = { app ->
                        if (managedApps.none { it.packageName == app.packageName }) {
                            val new = ManagedApp(app.packageName, app.appName)
                            managedApps = managedApps + new
                            scope.launch { repo.saveManagedApps(managedApps) }
                        }
                        showPicker = false
                    },
                    onDismiss = { showPicker = false },
                )
            }
        }
    }
}
