package com.rootdroid.inspector.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.rootdroid.inspector.model.ManagedApp
import com.rootdroid.inspector.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    managedApps: List<ManagedApp>,
    isRooted: Boolean,
    onAddApp: () -> Unit,
    onInspect: (ManagedApp) -> Unit,
    onRemove: (ManagedApp) -> Unit,
    getIcon: (String) -> Drawable?,
) {
    var showRootWarning by remember { mutableStateOf(!isRooted) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isRooted) NeonGreen else LogError,
                                    CircleShape
                                )
                        )
                        Text(
                            "ROOTDROID",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen,
                            letterSpacing = 4.sp,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddApp,
                containerColor = NeonGreen,
                contentColor = Background,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add app")
            }
        },
        containerColor = Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Root warning banner
            AnimatedVisibility(visible = showRootWarning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LogError.copy(alpha = 0.15f))
                        .border(1.dp, LogError.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = LogError, modifier = Modifier.size(16.dp))
                        Text("ROOT NOT DETECTED — some features unavailable", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LogError)
                    }
                    TextButton(onClick = { showRootWarning = false }) {
                        Text("DISMISS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
                    }
                }
            }

            if (managedApps.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(managedApps, key = { it.packageName }) { app ->
                        AppCard(
                            app = app,
                            icon = getIcon(app.packageName),
                            onInspect = { onInspect(app) },
                            onRemove = { onRemove(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "[ NO TARGETS ]",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = TextDim,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap + to add an app to inspect",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun AppCard(
    app: ManagedApp,
    icon: Drawable?,
    onInspect: () -> Unit,
    onRemove: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .background(SurfaceHigh, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickable { onInspect() }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // App icon
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon.toBitmap(52, 52).asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("?", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                app.appName,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                app.packageName.split(".").last(),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Top-right delete button
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = TextDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Bottom: inspect button on hover
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(NeonGreen.copy(alpha = 0.08f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(2.dp))
            Text("INSPECT", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = NeonGreen, letterSpacing = 1.sp)
        }
    }
}
