package com.rootdroid.inspector.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.rootdroid.inspector.model.*
import com.rootdroid.inspector.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(
    app: ManagedApp,
    icon: Drawable?,
    pid: Int,
    isRunning: Boolean,
    logs: List<LogEntry>,
    calls: List<FunctionCall>,
    memoryRegions: List<MemoryRegion>,
    openFiles: List<String>,
    processInfo: ProcessInfo?,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    onKill: () -> Unit,
    onToggleOverlay: () -> Unit,
    overlayActive: Boolean,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("LOGS", "CALLS", "MEMORY", "FILES")

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (icon != null) {
                            Image(
                                bitmap = icon.toBitmap(32, 32).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        Column {
                            Text(app.appName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    // PID badge
                    if (pid > 0) {
                        Box(
                            modifier = Modifier
                                .background(SurfaceHigh, RoundedCornerShape(4.dp))
                                .border(1.dp, Border, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("PID $pid", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = NeonGreen)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    // Running indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (isRunning) NeonGreen else TextDim, CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .border(1.dp, Border)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = onKill,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LogError),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("KILL", fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                } else {
                    Button(
                        onClick = onLaunch,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Background),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("LAUNCH", fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }

                Button(
                    onClick = onToggleOverlay,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (overlayActive) NeonGreen.copy(alpha = 0.2f) else SurfaceHigh,
                        contentColor = if (overlayActive) NeonGreen else TextMuted,
                    ),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("OVERLAY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Process info bar
            if (processInfo != null) {
                ProcessInfoBar(processInfo)
            }

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Surface,
                contentColor = NeonGreen,
                edgePadding = 0.dp,
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = {
                            Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
                        },
                        selectedContentColor = NeonGreen,
                        unselectedContentColor = TextMuted,
                    )
                }
            }

            // Tab content
            when (selectedTab) {
                0 -> LogsTab(logs)
                1 -> CallsTab(calls)
                2 -> MemoryTab(memoryRegions, processInfo)
                3 -> FilesTab(openFiles)
            }
        }
    }
}

@Composable
private fun ProcessInfoBar(info: ProcessInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceHigh)
            .border(1.dp, Border)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatChip("RSS", "${info.vmRssKb / 1024} MB")
        StatChip("VSZ", "${info.vmSizeKb / 1024} MB")
        StatChip("THR", info.threads.toString())
        StatChip("ST", info.state)
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = NeonGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LogsTab(logs: List<LogEntry>) {
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(logs.size) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    if (logs.isEmpty()) {
        EmptySectionPlaceholder("Waiting for logcat output...")
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            items(logs, key = { it.id }) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.V -> LogVerbose
        LogLevel.D -> LogDebug
        LogLevel.I -> LogInfo
        LogLevel.W -> LogWarn
        LogLevel.E -> LogError
        LogLevel.F -> LogFatal
        LogLevel.FRIDA -> LogFrida
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            entry.timestamp.takeLast(12),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = TextDim,
            modifier = Modifier.width(76.dp),
        )
        Text(
            entry.level.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp),
        )
        Text(
            "${entry.tag}: ${entry.message}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TextPrimary.copy(alpha = if (entry.level == LogLevel.V) 0.5f else 1f),
        )
    }
}

@Composable
private fun CallsTab(calls: List<FunctionCall>) {
    if (calls.isEmpty()) {
        EmptySectionPlaceholder("No intercepted calls yet.\nLaunch app and strace will capture syscalls.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(calls, key = { it.id }) { call ->
                CallEntryRow(call)
            }
        }
    }
}

@Composable
private fun CallEntryRow(call: FunctionCall) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(SurfaceHigh, RoundedCornerShape(4.dp))
            .border(1.dp, Border, RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("→", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = NeonGreen)
            Text(
                "${call.className}.${call.methodName}()",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = NeonGreen,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(call.timestamp, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextDim)
        }
        call.args.forEachIndexed { i, arg ->
            Text("  arg[$i]: $arg", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
        }
        Text("  ret: ${call.returnValue}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = LogInfo)
    }
}

@Composable
private fun MemoryTab(regions: List<MemoryRegion>, info: ProcessInfo?) {
    if (regions.isEmpty()) {
        EmptySectionPlaceholder("Launch app to read /proc/PID/maps")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(regions, key = { it.address }) { region ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(region.address, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = NeonGreenDim, modifier = Modifier.width(120.dp))
                    Text(region.perms, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = when {
                        region.perms.contains("x") -> LogWarn
                        region.perms.contains("w") -> LogInfo
                        else -> TextMuted
                    }, modifier = Modifier.width(32.dp))
                    Text("${region.sizeKb}K", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextDim, modifier = Modifier.width(48.dp))
                    Text(region.name, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextPrimary, overflow = TextOverflow.Ellipsis, maxLines = 1)
                }
                HorizontalDivider(color = Border.copy(alpha = 0.3f), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun FilesTab(files: List<String>) {
    if (files.isEmpty()) {
        EmptySectionPlaceholder("Launch app to read open file descriptors\nfrom /proc/PID/fd")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(files) { file ->
                Text(
                    file,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
                HorizontalDivider(color = Border.copy(alpha = 0.3f), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun EmptySectionPlaceholder(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextDim, lineHeight = 18.sp)
    }
}
