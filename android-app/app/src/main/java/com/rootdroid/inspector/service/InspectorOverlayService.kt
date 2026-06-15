package com.rootdroid.inspector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootdroid.inspector.MainActivity
import com.rootdroid.inspector.R
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.LogLevel
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.ContainerEngine
import com.rootdroid.inspector.virtual.MethodEnumerator
import com.rootdroid.inspector.virtual.MethodInfo
import com.rootdroid.inspector.virtual.ProcessScanner
import com.rootdroid.inspector.virtual.RunningProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class InspectorOverlayService : Service() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_PID     = "extra_pid"
        const val CHANNEL_ID    = "vs_overlay"
        const val NOTIF_ID      = 1001
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Overlay state (Compose snapshot state, readable from composables) ────
    private val expanded       = mutableStateOf(false)
    private val selectedTab    = mutableStateOf(0)   // 0=PROCS 1=LOGS 2=METHODS 3=MEM 4=FILES
    private val hookedPid      = mutableStateOf(-1)
    private val hookedPkg      = mutableStateOf("")
    private val processes      = mutableStateListOf<RunningProcess>()
    private val newPidSet      = mutableStateOf(emptySet<Int>())
    private val logs           = mutableStateListOf<LogEntry>()
    private val methods        = mutableStateListOf<MethodInfo>()
    private val memLines       = mutableStateListOf<String>()
    private val fdLines        = mutableStateListOf<String>()
    private val loadingMethods = mutableStateOf(false)

    private var logJob:    Job? = null
    private var methodJob: Job? = null
    private var pollJob:   Job? = null

    private var collapsedP = makeParams(false)
    private var expandedP  = makeParams(true)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        startProcessMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        val pid = intent?.getIntExtra(EXTRA_PID, -1) ?: -1
        if (overlayView == null) attachView()
        if (pkg.isNotEmpty() && pid > 0) hookProcess(pid, pkg)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }

    // ── Process monitor ───────────────────────────────────────────────────────

    private fun startProcessMonitor() {
        scope.launch {
            ProcessScanner.monitor(this@InspectorOverlayService).collect { (procs, newPids) ->
                processes.clear()
                processes.addAll(procs)
                if (newPids.isNotEmpty()) {
                    newPidSet.value = newPidSet.value + newPids
                    // Auto-expand + jump to PROCS when a new user app appears
                    if (procs.any { it.pid in newPids && it.isUserApp }) {
                        expanded.value = true
                        selectedTab.value = 0
                        applyLayout(true)
                    }
                    delay(8000)
                    newPidSet.value = newPidSet.value - newPids
                }
            }
        }
    }

    // ── Hooking ───────────────────────────────────────────────────────────────

    private fun hookProcess(pid: Int, pkg: String) {
        hookedPid.value = pid
        hookedPkg.value = pkg
        logs.clear(); methods.clear(); memLines.clear(); fdLines.clear()

        logJob?.cancel()
        logJob = scope.launch {
            ContainerEngine.streamLogcat(pid).collect { entry ->
                if (logs.size >= 600) logs.removeAt(0)
                logs.add(entry)
            }
        }

        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val fd = ContainerEngine.getOpenFiles(this@InspectorOverlayService, pid)
                if (fd.isNotEmpty()) { fdLines.clear(); fdLines.addAll(fd) }
                val mem = ContainerEngine.exec(this@InspectorOverlayService, "cat /proc/$pid/maps 2>/dev/null")
                if (mem.isNotBlank()) { memLines.clear(); memLines.addAll(mem.lines().filter { it.isNotBlank() }) }
                delay(4000)
            }
        }
    }

    private fun loadMethods(pkg: String) {
        if (loadingMethods.value) return
        methodJob?.cancel()
        methodJob = scope.launch {
            loadingMethods.value = true
            val result = MethodEnumerator.enumerate(this@InspectorOverlayService, pkg)
            methods.clear(); methods.addAll(result)
            loadingMethods.value = false
        }
    }

    // ── WindowManager helpers ─────────────────────────────────────────────────

    private fun makeParams(fullWidth: Boolean) = WindowManager.LayoutParams(
        if (fullWidth) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (fullWidth) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
        x = if (fullWidth) 0 else 16
        y = 120
    }

    private fun applyLayout(expand: Boolean) {
        val view = overlayView ?: return
        val p = if (expand) expandedP else collapsedP
        try { windowManager.updateViewLayout(view, p) } catch (_: Exception) {}
    }

    private fun attachView() {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                RootDroidTheme {
                    OverlayRoot(
                        expanded       = expanded.value,
                        selectedTab    = selectedTab.value,
                        hookedPid      = hookedPid.value,
                        hookedPkg      = hookedPkg.value,
                        processes      = processes,
                        newPids        = newPidSet.value,
                        logs           = logs,
                        methods        = methods,
                        memLines       = memLines,
                        fdLines        = fdLines,
                        loadingMethods = loadingMethods.value,
                        onToggle = { expand ->
                            expanded.value = expand
                            applyLayout(expand)
                        },
                        onTabSelect = { tab ->
                            selectedTab.value = tab
                            if (tab == 2 && methods.isEmpty() && hookedPkg.value.isNotEmpty()) {
                                loadMethods(hookedPkg.value)
                            }
                        },
                        onHook = { proc ->
                            hookProcess(proc.pid, proc.processName)
                            selectedTab.value = 1
                        },
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            val p = if (expanded.value) expandedP else collapsedP
                            if (!expanded.value) p.x = (p.x + dx.toInt()).coerceIn(0, 2000)
                            p.y = (p.y + dy.toInt()).coerceIn(0, 3000)
                            applyLayout(expanded.value)
                        },
                    )
                }
            }
        }
        overlayView = view
        windowManager.addView(view, collapsedP)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Virtual Space Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual Space active")
            .setContentText("Monitoring processes")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Composables
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun OverlayRoot(
    expanded: Boolean,
    selectedTab: Int,
    hookedPid: Int,
    hookedPkg: String,
    processes: List<RunningProcess>,
    newPids: Set<Int>,
    logs: List<LogEntry>,
    methods: List<MethodInfo>,
    memLines: List<String>,
    fdLines: List<String>,
    loadingMethods: Boolean,
    onToggle: (Boolean) -> Unit,
    onTabSelect: (Int) -> Unit,
    onHook: (RunningProcess) -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    if (!expanded) {
        FloatingPill(
            newCount = newPids.size,
            hooked = hookedPkg.isNotEmpty(),
            onExpand = { onToggle(true) },
            onDrag = onDrag,
        )
    } else {
        OverlayPanel(
            selectedTab = selectedTab,
            hookedPid = hookedPid,
            hookedPkg = hookedPkg,
            processes = processes,
            newPids = newPids,
            logs = logs,
            methods = methods,
            memLines = memLines,
            fdLines = fdLines,
            loadingMethods = loadingMethods,
            onTabSelect = onTabSelect,
            onHook = onHook,
            onMinimise = { onToggle(false) },
            onClose = onClose,
            onDrag = onDrag,
        )
    }
}

// ── Collapsed floating pill ───────────────────────────────────────────────────

@Composable
private fun FloatingPill(
    newCount: Int,
    hooked: Boolean,
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .pointerInput(Unit) { detectDragGestures { _, d -> onDrag(d.x, d.y) } }
            .clickable { onExpand() }
            .background(Surface, RoundedCornerShape(24.dp))
            .border(1.dp, Border, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(if (hooked) StatusGreen else Accent, CircleShape))
            Text("VS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            if (newCount > 0) {
                Box(
                    modifier = Modifier.background(StatusRed, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                ) { Text("$newCount", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ── Expanded overlay panel ────────────────────────────────────────────────────

private val TABS = listOf("PROCS", "LOGS", "METHODS", "MEMORY", "FILES")

@Composable
private fun OverlayPanel(
    selectedTab: Int,
    hookedPid: Int,
    hookedPkg: String,
    processes: List<RunningProcess>,
    newPids: Set<Int>,
    logs: List<LogEntry>,
    methods: List<MethodInfo>,
    memLines: List<String>,
    fdLines: List<String>,
    loadingMethods: Boolean,
    onTabSelect: (Int) -> Unit,
    onHook: (RunningProcess) -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .background(Background.copy(alpha = 0.96f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .border(1.dp, Border, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
    ) {
        // ── Title bar (drag handle) ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .pointerInput(Unit) { detectDragGestures { _, d -> onDrag(d.x, d.y) } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(if (hookedPid > 0) StatusGreen else TextMuted, CircleShape))
                Text(
                    if (hookedPkg.isNotEmpty()) hookedPkg.split(".").last() else "Virtual Space",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                if (hookedPid > 0) {
                    Text("[$hookedPid]", fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
            Row {
                IconButton(onClick = onMinimise, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.UnfoldLess, contentDescription = "Minimise", tint = TextSecond, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecond, modifier = Modifier.size(14.dp))
                }
            }
        }

        // ── Tab strip ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceMid),
        ) {
            TABS.forEachIndexed { i, label ->
                val active = selectedTab == i
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTabSelect(i) }
                        .background(if (active) Surface else Color.Transparent)
                        .border(
                            width = if (active) 0.dp else 0.dp,
                            color = Color.Transparent,
                        )
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) Accent else TextMuted, letterSpacing = 0.5.sp)
                        if (active) Box(Modifier.padding(top = 3.dp).width(16.dp).height(2.dp).background(Accent, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }

        HorizontalDivider(color = Border, thickness = 0.5.dp)

        // ── Tab content ───────────────────────────────────────────────────────
        when (selectedTab) {
            0 -> ProcsTab(processes, newPids, hookedPid, onHook)
            1 -> LogsTab(logs, hookedPid)
            2 -> MethodsTab(methods, loadingMethods, hookedPkg)
            3 -> TextListTab(memLines, "No memory map — hook a process first")
            4 -> TextListTab(fdLines, "No file descriptors — hook a process first")
        }
    }
}

// ── Tab: PROCS ────────────────────────────────────────────────────────────────

@Composable
private fun ProcsTab(
    processes: List<RunningProcess>,
    newPids: Set<Int>,
    hookedPid: Int,
    onHook: (RunningProcess) -> Unit,
) {
    if (processes.isEmpty()) {
        CentreText("Scanning processes…")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(processes, key = { it.pid }) { proc ->
                val isHooked = proc.pid == hookedPid
                val isNew    = proc.pid in newPids
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(when { isHooked -> Accent.copy(alpha = 0.08f); isNew -> StatusGreen.copy(alpha = 0.06f); else -> Color.Transparent })
                        .clickable { onHook(proc) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).background(when { isHooked -> Accent; proc.isUserApp -> StatusGreen; else -> TextMuted }, CircleShape))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(proc.appLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isHooked) Accent else TextPrimary, maxLines = 1)
                            if (isNew) Text("NEW", fontSize = 8.sp, color = StatusGreen, fontWeight = FontWeight.Bold)
                        }
                        Text(proc.processName, fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${proc.pid}", fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

// ── Tab: LOGS ─────────────────────────────────────────────────────────────────

@Composable
private fun LogsTab(logs: List<LogEntry>, hookedPid: Int) {
    if (hookedPid <= 0) { CentreText("Tap a process in PROCS to start streaming logs"); return }
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1) }

    if (logs.isEmpty()) {
        CentreText("Waiting for logcat…")
    } else {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            items(logs.takeLast(400), key = { it.id }) { entry ->
                val col = when (entry.level) {
                    LogLevel.V -> LogVerbose; LogLevel.D -> LogDebug
                    LogLevel.I -> LogInfo;    LogLevel.W -> LogWarn
                    LogLevel.E -> LogError;   LogLevel.F -> LogFatal
                    LogLevel.FRIDA -> LogFrida
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(entry.level.name, fontSize = 8.sp, color = col, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(10.dp))
                    Text("${entry.tag}: ${entry.message}", fontSize = 9.sp, color = TextPrimary.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
                }
            }
        }
    }
}

// ── Tab: METHODS ──────────────────────────────────────────────────────────────

@Composable
private fun MethodsTab(methods: List<MethodInfo>, loading: Boolean, pkg: String) {
    when {
        pkg.isEmpty()   -> CentreText("Hook a process first, then open METHODS")
        loading         -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Text("Enumerating methods…", fontSize = 10.sp, color = TextMuted)
            }
        }
        methods.isEmpty() -> CentreText("No methods found")
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(methods, key = { "${it.fullClass}.${it.methodName}.${it.params}" }) { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (m.isNative) Text("NAT", fontSize = 8.sp, color = StatusYellow, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(m.shortClass, fontSize = 9.sp, color = Accent, fontFamily = FontFamily.Monospace)
                            Text(".${m.methodName}", fontSize = 9.sp, color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                        Text("(${m.params}) → ${m.returnType}", fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

// ── Tab: MEMORY / FILES (generic text list) ───────────────────────────────────

@Composable
private fun TextListTab(lines: List<String>, emptyHint: String) {
    if (lines.isEmpty()) {
        CentreText(emptyHint)
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(line, fontSize = 9.sp, color = TextPrimary, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), lineHeight = 13.sp)
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun CentreText(msg: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(msg, fontSize = 11.sp, color = TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(24.dp))
    }
}
