package com.rootdroid.inspector.ui

import android.graphics.drawable.Drawable
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
    rootSimActive: Boolean,
    onAddApp: () -> Unit,
    onLaunch: (ManagedApp) -> Unit,
    onRemove: (ManagedApp) -> Unit,
    getIcon: (String) -> Drawable?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Virtual Space",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = TextPrimary,
                    )
                },
                actions = {
                    StatusPill(active = rootSimActive)
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddApp,
                containerColor = Accent,
                contentColor = Background,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add app")
            }
        },
        containerColor = Background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalDivider(color = Border, thickness = 0.5.dp)
            if (managedApps.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(managedApps, key = { it.packageName }) { app ->
                        AppCard(
                            app = app,
                            icon = getIcon(app.packageName),
                            onLaunch = { onLaunch(app) },
                            onRemove = { onRemove(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(active: Boolean) {
    Row(
        modifier = Modifier
            .background(
                if (active) StatusGreen.copy(alpha = 0.12f) else SurfaceHigh,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (active) StatusGreen else TextMuted, CircleShape)
        )
        Text(
            if (active) "Root sim on" else "Basic mode",
            fontSize = 11.sp,
            color = if (active) StatusGreen else TextSecond,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No apps added yet", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextSecond)
        Spacer(Modifier.height(6.dp))
        Text("Tap + to add an app to the virtual space", fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center)
    }
}

@Composable
fun AppCard(
    app: ManagedApp,
    icon: Drawable?,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .background(Surface, RoundedCornerShape(12.dp))
            .border(0.5.dp, Border, RoundedCornerShape(12.dp))
            .clickable { onLaunch() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon.toBitmap(48, 48).asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("?", color = TextMuted, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                app.appName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                app.packageName.split(".").last(),
                fontSize = 9.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Remove button top-right
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(14.dp))
        }

        // Launch strip bottom
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Accent.copy(alpha = 0.10f),
                    RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                )
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Accent, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text("Launch", fontSize = 10.sp, color = Accent, fontWeight = FontWeight.Medium)
        }
    }
}
