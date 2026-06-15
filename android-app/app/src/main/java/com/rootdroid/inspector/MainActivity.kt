package com.rootdroid.inspector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.rootdroid.inspector.model.InstalledApp
import com.rootdroid.inspector.model.ManagedApp
import com.rootdroid.inspector.model.FunctionCall
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.MemoryRegion
import com.rootdroid.inspector.model.ProcessInfo
import com.rootdroid.inspector.repository.InstalledAppsRepository
import com.rootdroid.inspector.root.RootBridge
import com.rootdroid.inspector.service.InspectorOverlayService
import com.rootdroid.inspector.ui.*
import com.rootdroid.inspector.ui.theme.RootDroidTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : ComponentActivity() {

    private lateinit var repo: InstalledAppsRepository

    // Permission launcher — internal so composables receiving `activity` can call it
    val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Refresh state after permissions granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = InstalledAppsRepository(this)

        setContent {
            RootDroidTheme {
                RootDroidApp(repo = repo, activity = this)
            }
        }
    }
}

@Composable
fun RootDroidApp(repo: InstalledAppsRepository, activity: MainActivity) {
    val scope = rememberCoroutineScope()

    // State
    var showPermissions by remember { mutableStateOf(true) }
    var isRooted by remember { mutableStateOf(false) }
    var managedApps by remember { mutableStateOf<List<ManagedApp>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingInstalled by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    // Inspector state
    var inspectedApp by remember { mutableStateOf<ManagedApp?>(null) }
    var inspectorLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var inspectorCalls by remember { mutableStateOf<List<FunctionCall>>(emptyList()) }
    var inspectorMemory by remember { mutableStateOf<List<MemoryRegion>>(emptyList()) }
    var inspectorFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var inspectorPid by remember { mutableIntStateOf(-1) }
    var inspectorRunning by remember { mutableStateOf(false) }
    var inspectorProcessInfo by remember { mutableStateOf<ProcessInfo?>(null) }
    var overlayActive by remember { mutableStateOf(false) }
    var logCollectJob by remember { mutableStateOf<Job?>(null) }
    var callCollectJob by remember { mutableStateOf<Job?>(null) }

    // Permissions state
    val permissions = buildPermissionItems(activity)

    // Boot: check root + load managed apps
    LaunchedEffect(Unit) {
        isRooted = RootBridge.isRooted()
        managedApps = repo.loadManagedApps()
        // Auto-skip permissions if all granted and rooted
        if (isRooted && permissions.all { it.granted }) {
            showPermissions = false
        }
    }

    fun requestPermissions() {
        val toRequest = mutableListOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        activity.permLauncher.launch(toRequest.toTypedArray())

        // SYSTEM_ALERT_WINDOW needs special intent
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
        }
    }

    fun openInspector(app: ManagedApp) {
        inspectedApp = app
        inspectorLogs = emptyList()
        inspectorCalls = emptyList()
        inspectorMemory = emptyList()
        inspectorFiles = emptyList()
        inspectorPid = -1
        inspectorRunning = false
        inspectorProcessInfo = null
        logCollectJob?.cancel()
        callCollectJob?.cancel()

        // Try to find existing PID
        scope.launch {
            val pid = RootBridge.findPid(app.packageName)
            if (pid > 0) {
                inspectorPid = pid
                inspectorRunning = true
                loadInspectorData(pid, app, scope,
                    onLog = { inspectorLogs = inspectorLogs + it },
                    onCall = { inspectorCalls = inspectorCalls + it },
                    onMemory = { inspectorMemory = it },
                    onFiles = { inspectorFiles = it },
                    onProcess = { inspectorProcessInfo = it },
                ).also { jobs ->
                    logCollectJob = jobs.first
                    callCollectJob = jobs.second
                }
            }
        }
    }

    fun launchApp(app: ManagedApp) {
        scope.launch {
            RootBridge.launchApp(app.packageName)
            delay(1500)
            val pid = RootBridge.findPid(app.packageName)
            if (pid > 0) {
                inspectorPid = pid
                inspectorRunning = true
                logCollectJob?.cancel()
                callCollectJob?.cancel()
                loadInspectorData(pid, app, scope,
                    onLog = { inspectorLogs = inspectorLogs + it },
                    onCall = { inspectorCalls = inspectorCalls + it },
                    onMemory = { inspectorMemory = it },
                    onFiles = { inspectorFiles = it },
                    onProcess = { inspectorProcessInfo = it },
                ).also { jobs ->
                    logCollectJob = jobs.first
                    callCollectJob = jobs.second
                }
            }
        }
    }

    fun killApp() {
        scope.launch {
            if (inspectorPid > 0) {
                RootBridge.killProcess(inspectorPid)
                inspectorRunning = false
                inspectorPid = -1
                logCollectJob?.cancel()
                callCollectJob?.cancel()
            }
        }
    }

    fun toggleOverlay(app: ManagedApp) {
        if (overlayActive) {
            activity.stopService(Intent(activity, InspectorOverlayService::class.java))
            overlayActive = false
        } else {
            if (Settings.canDrawOverlays(activity)) {
                val intent = Intent(activity, InspectorOverlayService::class.java).apply {
                    putExtra(InspectorOverlayService.EXTRA_PACKAGE, app.packageName)
                    putExtra(InspectorOverlayService.EXTRA_PID, inspectorPid)
                }
                activity.startForegroundService(intent)
                overlayActive = true
            } else {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(i)
            }
        }
    }

    // --- Screens ---

    when {
        showPermissions -> {
            PermissionsScreen(
                permissions = permissions,
                isRooted = isRooted,
                onRequestAll = { requestPermissions() },
                onContinue = { showPermissions = false },
            )
        }

        inspectedApp != null -> {
            val app = inspectedApp!!
            InspectorScreen(
                app = app,
                icon = repo.getIcon(app.packageName),
                pid = inspectorPid,
                isRunning = inspectorRunning,
                logs = inspectorLogs,
                calls = inspectorCalls,
                memoryRegions = inspectorMemory,
                openFiles = inspectorFiles,
                processInfo = inspectorProcessInfo,
                onBack = {
                    logCollectJob?.cancel()
                    callCollectJob?.cancel()
                    inspectedApp = null
                    if (overlayActive) {
                        activity.stopService(Intent(activity, InspectorOverlayService::class.java))
                        overlayActive = false
                    }
                },
                onLaunch = { launchApp(app) },
                onKill = { killApp() },
                onToggleOverlay = { toggleOverlay(app) },
                overlayActive = overlayActive,
            )
        }

        else -> {
            HomeScreen(
                managedApps = managedApps,
                isRooted = isRooted,
                onAddApp = {
                    showPicker = true
                    isLoadingInstalled = true
                    scope.launch {
                        installedApps = repo.getAllInstalledApps()
                        isLoadingInstalled = false
                    }
                },
                onInspect = { openInspector(it) },
                onRemove = { app ->
                    managedApps = managedApps.filter { it.packageName != app.packageName }
                    scope.launch { repo.saveManagedApps(managedApps) }
                },
                getIcon = { repo.getIcon(it) },
            )

            if (showPicker) {
                AppPickerSheet(
                    apps = installedApps,
                    isLoading = isLoadingInstalled,
                    onSelect = { app ->
                        val already = managedApps.any { it.packageName == app.packageName }
                        if (!already) {
                            val newApp = ManagedApp(app.packageName, app.appName)
                            managedApps = managedApps + newApp
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

private fun buildPermissionItems(activity: MainActivity): List<PermissionItem> {
    fun granted(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return listOf(
        PermissionItem("Overlay (SYSTEM_ALERT_WINDOW)", "Floating inspector overlay", Settings.canDrawOverlays(activity)),
        PermissionItem("Storage", "Dump memory / write logs", granted(Manifest.permission.READ_EXTERNAL_STORAGE)),
        PermissionItem("Usage Stats", "App usage tracking", granted(Manifest.permission.PACKAGE_USAGE_STATS)),
        PermissionItem("Query Packages", "List installed apps", granted(Manifest.permission.QUERY_ALL_PACKAGES)),
    )
}

private fun loadInspectorData(
    pid: Int,
    app: ManagedApp,
    scope: CoroutineScope,
    onLog: (LogEntry) -> Unit,
    onCall: (FunctionCall) -> Unit,
    onMemory: (List<MemoryRegion>) -> Unit,
    onFiles: (List<String>) -> Unit,
    onProcess: (ProcessInfo?) -> Unit,
): Pair<Job, Job> {
    // Memory + files: poll every 3s
    scope.launch {
        while (isActive) {
            onMemory(RootBridge.getMemoryMaps(pid))
            onFiles(RootBridge.getOpenFiles(pid))
            onProcess(RootBridge.getProcessInfo(pid))
            delay(3000)
        }
    }

    // Log stream
    val logJob = RootBridge.streamLogcat(pid)
        .onEach { onLog(it) }
        .launchIn(scope)

    // Syscall stream
    val callJob = RootBridge.streamSyscalls(pid)
        .onEach { onCall(it) }
        .launchIn(scope)

    return Pair(logJob, callJob)
}
