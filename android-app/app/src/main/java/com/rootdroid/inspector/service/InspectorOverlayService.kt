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
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import com.rootdroid.inspector.MainActivity
import com.rootdroid.inspector.R
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.LogLevel
import com.rootdroid.inspector.root.RootBridge
import com.rootdroid.inspector.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class InspectorOverlayService : Service() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_PID = "extra_pid"
        const val CHANNEL_ID = "rootdroid_inspector"
        const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val logs = mutableStateListOf<LogEntry>()
    private var targetPackage = ""
    private var targetPid = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPackage = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        targetPid = intent?.getIntExtra(EXTRA_PID, -1) ?: -1

        startForeground(NOTIF_ID, buildNotification(targetPackage))
        showOverlay()
        startLogStreaming()
        return START_STICKY
    }

    private fun startLogStreaming() {
        serviceScope.launch {
            if (targetPid > 0) {
                RootBridge.streamLogcat(targetPid).collect { entry ->
                    if (logs.size > 500) logs.removeAt(0)
                    logs.add(entry)
                }
            } else {
                // Stream all logcat and filter by package string
                RootBridge.streamAllLogcat().collect { entry ->
                    if (entry.tag.contains(targetPackage.split(".").last(), ignoreCase = true) ||
                        entry.message.contains(targetPackage, ignoreCase = true)) {
                        if (logs.size > 500) logs.removeAt(0)
                        logs.add(entry)
                    }
                }
            }
        }
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                RootDroidTheme {
                    OverlayPanel(
                        logs = logs,
                        packageName = targetPackage,
                        pid = targetPid,
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            params.x = (params.x + dx.toInt()).coerceIn(0, 2000)
                            params.y = (params.y + dy.toInt()).coerceIn(0, 3000)
                            windowManager.updateViewLayout(this, params)
                        },
                    )
                }
            }
        }

        composeView = view
        windowManager.addView(view, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        composeView?.let { windowManager.removeView(it) }
        composeView = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(pkg: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, pkg))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

@Composable
private fun OverlayPanel(
    logs: List<LogEntry>,
    packageName: String,
    pid: Int,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    var collapsed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (collapsed) 36.dp else 260.dp)
            .background(Background.copy(alpha = 0.94f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
            .border(1.dp, NeonGreen.copy(alpha = 0.3f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) }
            },
    ) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceHigh)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(NeonGreen, androidx.compose.foundation.shape.CircleShape))
                Text(
                    packageName.split(".").takeLast(2).joinToString("."),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = NeonGreen,
                    fontWeight = FontWeight.Bold,
                )
                if (pid > 0) {
                    Text("[$pid]", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
                }
            }
            Row {
                IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (collapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }

        if (!collapsed) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text("waiting for logs...", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    items(logs.takeLast(100), key = { it.id }) { entry ->
                        val color = when (entry.level) {
                            LogLevel.V -> LogVerbose
                            LogLevel.D -> LogDebug
                            LogLevel.I -> LogInfo
                            LogLevel.W -> LogWarn
                            LogLevel.E -> LogError
                            LogLevel.F -> LogFatal
                            LogLevel.FRIDA -> LogFrida
                        }
                        Text(
                            "${entry.level.name} ${entry.tag}: ${entry.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = color,
                            lineHeight = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
