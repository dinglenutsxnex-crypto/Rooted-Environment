package com.rootdroid.inspector.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.rootdroid.inspector.model.InstalledApp
import com.rootdroid.inspector.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<InstalledApp>,
    isLoading: Boolean,
    onSelect: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Border, RoundedCornerShape(2.dp))
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SELECT TARGET",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = NeonGreen,
                    letterSpacing = 3.sp,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(NeonGreen),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("filter packages...", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextDim)
                        }
                        inner()
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Count
            Text(
                "${filtered.size} packages",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(4.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NeonGreen, modifier = Modifier.size(24.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppListRow(app = app, onClick = { onSelect(app) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppListRow(app: InstalledApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon.toBitmap(40, 40).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("?", color = TextMuted, fontFamily = FontFamily.Monospace)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary)
            Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
        }
    }
    HorizontalDivider(color = Border, thickness = 0.5.dp)
}
