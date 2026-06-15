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
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.LogLevel
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.ContainerApp
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.ContainerSession
import com.rootdroid.inspector.virtual.MethodEnumerator
import com.rootdroid.inspector.virtual.MethodInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

    // ── UI state ──────────────────────────────────────────────────────────────
    private val expanded       = mutableStateOf(false)
    private val selectedTab    = mutableStateOf(0)  // 0=APPS 1=LOGS 2=METHODS 3=MEM 4=FILES

    // Currently attached container session
    private val attachedPkg    = mutableStateOf("")
    private val attachedPid    = mutableStateOf(-1)
    private val attachedLoader = mutableStateOf<dalvik.system.DexClassLoader?>(null)

    // Data
    private val containerItems = mutableStateListOf<ContainerUI>()
    private val logs           = mutableStateListOf<LogEntry>()
    private val methods        = mutableStateListOf<MethodInfo>()
    private val memLines       = mutableStateListOf<String>()
    private val fdLines        = mutableStateListOf<String>()
    private val loadingMethods = mutableStateOf(false)
    private val attachStatus   = mutableStateOf("Waiting for container…")

    private var logJob:    Job? = null
    private var methodJob: Job? = null
    private var pollJob:   Job? = null
    private var monitorJob: Job? = null

    private var collapsedP = makeParams(false)
    private var expandedP  = makeParams(true)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        startContainerMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        val pid = intent?.getIntExtra(EXTRA_PID, -1) ?: -1

        if (overlayView == null) attachView()

        if (pkg.isNotEmpty()) {
            attachedPkg.value = pkg
            // Try to attach immediately if session already registered
            ContainerManager.activeSessions[pkg]?.let { attachToSession(it) }
                ?: run {
                    if (pid > 0) {
                        // Session may come later — set PID hint so we attach when session appears
                        attachedPid.value = pid
                    }
                    attachStatus.value = "Waiting for $pkg to load…"
                }
            expanded.value = true
            applyLayout(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }

    // ── Container monitor — polls ContainerManager every 2s ──────────────────

    private fun startContainerMonitor() {
        monitorJob = scope.launch {
            while (true) {
                val installed = ContainerManager.list(this@InspectorOverlayService)
                val sessions  = ContainerManager.activeSessions

                containerItems.clear()
                containerItems.addAll(installed.map { app ->
                    ContainerUI(app, sessions[app.packageName])
                })

                // Auto-attach if we have a target pkg and its session just appeared
                val target = attachedPkg.value
                if (target.isNotEmpty() && attachedPid.value <= 0) {
                    sessions[target]?.let { attachToSession(it) }
                }

                delay(2000)
            }
        }
    }

    // ── Attach to a container session ─────────────────────────────────────────

    private fun attachToSession(session: ContainerSession) {
        if (attachedPid.value == session.pid && attachedPkg.value == session.pkg) return

        attachedPkg.value    = session.pkg
        attachedPid.value    = session.pid
        attachedLoader.value = session.classLoader
        attachStatus.value   = "Attached to ${session.pkg.split(".").last()} [PID ${session.pid}]"

        logs.clear(); methods.clear(); memLines.clear(); fdLines.clear()

        // Stream logcat for THIS pid (= our PID for in-process, or system pid for fallback)
        logJob?.cancel()
        logJob = scope.launch {
            streamLogcat(session.pid).collect { entry ->
                if (logs.size >= 800) logs.removeAt(0)
                logs.add(entry)
            }
        }

        // Poll /proc for memory + fds
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val pid = session.pid
                if (pid > 0) {
                    try {
                        val fd = Runtime.getRuntime()
                            .exec(arrayOf("sh", "-c", "ls -la /proc/$pid/fd 2>/dev/null"))
                            .inputStream.bufferedReader().readText().trim()
                            .lines().drop(1).filter { it.isNotBlank() }
                        if (fd.isNotEmpty()) { fdLines.clear(); fdLines.addAll(fd) }
                    } catch (_: Exception) {}
                    try {
                        val mem = Runtime.getRuntime()
                            .exec(arrayOf("sh", "-c", "cat /proc/$pid/maps 2>/dev/null"))
                            .inputStream.bufferedReader().readText().trim()
                            .lines().filter { it.isNotBlank() }
                        if (mem.isNotEmpty()) { memLines.clear(); memLines.addAll(mem) }
                    } catch (_: Exception) {}
                }
                delay(4000)
            }
        }
    }

    // ── Method loading ────────────────────────────────────────────────────────

    private fun loadMethods() {
        if (loadingMethods.value) return
        val pkg    = attachedPkg.value
        val loader = attachedLoader.value
        val apk    = ContainerManager.apkFile(this, pkg).absolutePath

        methodJob?.cancel()
        methodJob = scope.launch {
            loadingMethods.value = true
            val result = if (loader != null) {
                MethodEnumerator.enumerateFromLoader(loader, apk)   // fast — reuse session loader
            } else {
                MethodEnumerator.enumerate(this@InspectorOverlayService, pkg) // slow fallback
            }
            methods.clear(); methods.addAll(result)
            loadingMethods.value = false
        }
    }

    // ── Logcat stream ─────────────────────────────────────────────────────────

    private fun streamLogcat(pid: Int) = flow {
        val proc = try {
            Runtime.getRuntime().exec(
                if (pid > 0) arrayOf("logcat", "--pid=$pid", "-v", "time")
                else         arrayOf("logcat", "-v", "time")
            )
        } catch (_: Exception) { return@flow }
        proc.inputStream.bufferedReader().use { r ->
            var line = r.readLine()
            while (line != null) {
                parseLog(line)?.let { emit(it) }
                line = r.readLine()
            }
        }
        proc.destroy()
    }.flowOn(Dispatchers.IO)

    private fun parseLog(line: String): LogEntry? {
        if (line.isBlank() || line.startsWith("-----")) return null
        val level = when {
            " V " in line -> LogLevel.V; " D " in line -> LogLevel.D
            " I " in line -> LogLevel.I; " W " in line -> LogLevel.W
            " E " in line -> LogLevel.E; " F " in line -> LogLevel.F
            else -> LogLevel.D
        }
        val colon = line.indexOf(':', 20)
        val tag = if (colon > 0) line.substring(0, colon).trim().takeLast(20) else "sys"
        val msg = if (colon > 0 && colon + 1 < line.length) line.substring(colon + 1).trim() else line
        return LogEntry(timestamp = line.take(18).trim(), level = level, tag = tag, message = msg)
    }

    // ── Window management ─────────────────────────────────────────────────────

    private fun makeParams(fullWidth: Boolean) = WindowManager.LayoutParams(
        if (fullWidth) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (fullWidth) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
        x = if (fullWidth) 0 else 16; y = 100
    }

    private fun applyLayout(expand: Boolean) {
        val view = overlayView ?: return
        try { windowManager.updateViewLayout(view, if (expand) expandedP else collapsedP) }
        catch (_: Exception) {}
    }

    private fun attachView() {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                RootDroidTheme {
                    OverlayRoot(
                        expanded       = expanded.value,
                        selectedTab    = selectedTab.value,
                        attachedPkg    = attachedPkg.value,
                        attachedPid    = attachedPid.value,
                        attachStatus   = attachStatus.value,
                        containerItems = containerItems,
                        logs           = logs,
                        methods        = methods,
                        memLines       = memLines,
                        fdLines        = fdLines,
                        loadingMethods = loadingMethods.value,
                        onToggle = { expand ->
                            expanded.value = expand; applyLayout(expand)
                        },
                        onTabSelect = { tab ->
                            selectedTab.value = tab
                            if (tab == 2 && methods.isEmpty() && attachedPkg.value.isNotEmpty()) loadMethods()
                        },
                        onAttach = { session ->
                            attachToSession(session)
                            selectedTab.value = 1
                            expanded.value = true; applyLayout(true)
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VS Overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Virtual Space — debugger active")
        .setContentText("Monitoring container processes")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentIntent(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .build()
}

// ── Data ──────────────────────────────────────────────────────────────────────

private data class ContainerUI(
    val app: ContainerApp,
    val session: ContainerSession?,
)

// ════════════════════════════════════════════════════════════════════════════
// Composables
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun OverlayRoot(
    expanded: Boolean,
    selectedTab: Int,
    attachedPkg: String,
    attachedPid: Int,
    attachStatus: String,
    containerItems: List<ContainerUI>,
    logs: List<LogEntry>,
    methods: List<MethodInfo>,
    memLines: List<String>,
    fdLines: List<String>,
    loadingMethods: Boolean,
    onToggle: (Boolean) -> Unit,
    onTabSelect: (Int) -> Unit,
    onAttach: (ContainerSession) -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    if (!expanded) {
        FloatingPill(
            attached  = attachedPid > 0,
            newCount  = containerItems.count { it.session != null },
            onExpand  = { onToggle(true) },
            onDrag    = onDrag,
        )
    } else {
        ExpandedPanel(
            selectedTab    = selectedTab,
            attachedPkg    = attachedPkg,
            attachedPid    = attachedPid,
            attachStatus   = attachStatus,
            containerItems = containerItems,
            logs           = logs,
            methods        = methods,
            memLines       = memLines,
            fdLines        = fdLines,
            loadingMethods = loadingMethods,
            onTabSelect    = onTabSelect,
            onAttach       = onAttach,
            onMinimise     = { onToggle(false) },
            onClose        = onClose,
            onDrag         = onDrag,
        )
    }
}

// ── Floating pill ─────────────────────────────────────────────────────────────

@Composable
private fun FloatingPill(
    attached: Boolean,
    newCount: Int,
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .pointerInput(Unit) { detectDragGestures { _, d -> onDrag(d.x, d.y) } }
            .clickable { onExpand() }
            .background(Surface, RoundedCornerShape(24.dp))
            .border(1.dp, if (attached) Accent.copy(alpha = 0.5f) else Border, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).background(if (attached) StatusGreen else TextMuted, CircleShape))
        Text("VS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (newCount > 0) {
            Box(Modifier.background(Accent, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text("$newCount", fontSize = 8.sp, color = Background, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Expanded panel ────────────────────────────────────────────────────────────

private val TABS = listOf("APPS", "LOGS", "METHODS", "MEMORY", "FILES")

@Composable
private fun ExpandedPanel(
    selectedTab: Int,
    attachedPkg: String,
    attachedPid: Int,
    attachStatus: String,
    containerItems: List<ContainerUI>,
    logs: List<LogEntry>,
    methods: List<MethodInfo>,
    memLines: List<String>,
    fdLines: List<String>,
    loadingMethods: Boolean,
    onTabSelect: (Int) -> Unit,
    onAttach: (ContainerSession) -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .background(Background.copy(alpha = 0.97f))
            .border(0.5.dp, Border),
    ) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .pointerInput(Unit) { detectDragGestures { _, d -> onDrag(d.x, d.y) } }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(6.dp).background(if (attachedPid > 0) StatusGreen else TextMuted, CircleShape))
                Column {
                    Text(
                        if (attachedPkg.isNotEmpty()) attachedPkg.split(".").last() else "Virtual Space",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    )
                    if (attachedPid > 0) {
                        Text("PID $attachedPid", fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Row {
                IconButton(onClick = onMinimise, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.UnfoldLess, null, tint = TextSecond, modifier = Modifier.size(13.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, null, tint = TextSecond, modifier = Modifier.size(13.dp))
                }
            }
        }

        // Status bar
        if (attachedPkg.isNotEmpty()) {
            Text(
                attachStatus,
                fontSize = 9.sp, color = Accent, fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(Accent.copy(alpha = 0.07f)).fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        // Tabs
        Row(modifier = Modifier.fillMaxWidth().background(SurfaceMid)) {
            TABS.forEachIndexed { i, label ->
                val active = selectedTab == i
                Column(
                    modifier = Modifier.weight(1f).clickable { onTabSelect(i) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, fontSize = 8.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Accent else TextMuted, letterSpacing = 0.3.sp)
                    if (active) Box(Modifier.padding(top = 2.dp).width(14.dp).height(1.5.dp)
                        .background(Accent, RoundedCornerShape(1.dp)))
                }
            }
        }
        HorizontalDivider(color = Border, thickness = 0.5.dp)

        // Content
        when (selectedTab) {
            0 -> AppsTab(containerItems, attachedPkg, onAttach)
            1 -> LogsTab(logs, attachedPid)
            2 -> MethodsTab(methods, loadingMethods, attachedPkg)
            3 -> TextTab(memLines, "No memory map — attach to a running container")
            4 -> TextTab(fdLines, "No file descriptors — attach to a running container")
        }
    }
}

// ── Tab: APPS (container sessions) ────────────────────────────────────────────

@Composable
private fun AppsTab(
    items: List<ContainerUI>,
    attachedPkg: String,
    onAttach: (ContainerSession) -> Unit,
) {
    if (items.isEmpty()) {
        CentreHint("No apps installed in container.\nAdd one from the main app.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.app.packageName }) { ui ->
                val isAttached = ui.app.packageName == attachedPkg
                val running    = ui.session != null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(when {
                            isAttached -> Accent.copy(alpha = 0.08f)
                            running    -> StatusGreen.copy(alpha = 0.05f)
                            else       -> Color.Transparent
                        })
                        .clickable(enabled = running) { ui.session?.let { onAttach(it) } }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Status dot
                    Box(Modifier.size(6.dp).background(when {
                        isAttached -> Accent
                        running    -> StatusGreen
                        else       -> TextMuted
                    }, CircleShape))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ui.app.appName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isAttached) Accent else TextPrimary)
                            if (running) Text("running", fontSize = 8.sp, color = StatusGreen, fontWeight = FontWeight.Bold)
                            if (isAttached) Text("attached", fontSize = 8.sp, color = Accent, fontWeight = FontWeight.Bold)
                        }
                        Text(ui.app.packageName, fontSize = 8.sp, color = TextMuted,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.session?.let {
                            Text("PID ${it.pid}", fontSize = 8.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (running && !isAttached) {
                        Text("ATTACH", fontSize = 8.sp, color = Accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

// ── Tab: LOGS ─────────────────────────────────────────────────────────────────

@Composable
private fun LogsTab(logs: List<LogEntry>, pid: Int) {
    if (pid <= 0) { CentreHint("Attach to a running container to stream logs"); return }
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1) }

    if (logs.isEmpty()) {
        CentreHint("Streaming logcat for PID $pid…")
    } else {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            items(logs.takeLast(500), key = { it.id }) { e ->
                val col = when (e.level) {
                    LogLevel.V -> LogVerbose; LogLevel.D -> LogDebug
                    LogLevel.I -> LogInfo;    LogLevel.W -> LogWarn
                    LogLevel.E -> LogError;   LogLevel.F -> LogFatal
                    LogLevel.FRIDA -> LogFrida
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(e.level.name, fontSize = 7.sp, color = col, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(9.dp))
                    Text(
                        "${e.tag}: ${e.message}",
                        fontSize = 9.sp, color = TextPrimary.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Monospace, lineHeight = 12.sp,
                    )
                }
            }
        }
    }
}

// ── Tab: METHODS ──────────────────────────────────────────────────────────────

@Composable
private fun MethodsTab(methods: List<MethodInfo>, loading: Boolean, pkg: String) {
    when {
        pkg.isEmpty() -> CentreHint("Attach to a container first, then open METHODS")
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Enumerating ${pkg.split(".").last()} methods…", fontSize = 9.sp, color = TextMuted)
            }
        }
        methods.isEmpty() -> CentreHint("No methods found")
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(methods, key = { "${it.fullClass}.${it.methodName}.${it.params}" }) { m ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (m.isNative) Text("NAT", fontSize = 7.sp, color = StatusYellow, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(StatusYellow.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 3.dp))
                            Text(m.shortClass, fontSize = 8.sp, color = Accent, fontFamily = FontFamily.Monospace)
                            Text(".${m.methodName}", fontSize = 8.sp, color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                        Text("(${m.params}) → ${m.returnType}", fontSize = 7.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

// ── Generic text list ─────────────────────────────────────────────────────────

@Composable
private fun TextTab(lines: List<String>, emptyHint: String) {
    if (lines.isEmpty()) CentreHint(emptyHint)
    else LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(lines) { line ->
            Text(line, fontSize = 8.sp, color = TextPrimary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp), lineHeight = 12.sp)
            HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun CentreHint(msg: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(msg, fontSize = 10.sp, color = TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 15.sp, modifier = Modifier.padding(20.dp))
    }
}
