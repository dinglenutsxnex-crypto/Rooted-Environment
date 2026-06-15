package com.rootdroid.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootdroid.inspector.ui.theme.*

data class PermissionItem(
    val title: String,
    val description: String,
    val granted: Boolean,
)

@Composable
fun PermissionsScreen(
    permissions: List<PermissionItem>,
    isRooted: Boolean,
    onRequestAll: () -> Unit,
    onContinue: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "ROOTDROID",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen,
                    letterSpacing = 6.sp,
                )
                Text(
                    "INSPECTOR",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextMuted,
                    letterSpacing = 4.sp,
                )
            }

            // Root status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isRooted) NeonGreen.copy(alpha = 0.08f) else LogError.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp),
                    )
                    .border(1.dp, if (isRooted) NeonGreen.copy(alpha = 0.3f) else LogError.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isRooted) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isRooted) NeonGreen else LogError,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        if (isRooted) "ROOT ACCESS GRANTED" else "ROOT NOT DETECTED",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRooted) NeonGreen else LogError,
                    )
                    Text(
                        if (isRooted) "Full instrumentation available" else "Grant root via Magisk / KernelSU",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted,
                    )
                }
            }

            // Permissions list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp)),
            ) {
                permissions.forEachIndexed { i, perm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (perm.granted) Icons.Default.Check else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (perm.granted) NeonGreen else LogWarn,
                            modifier = Modifier.size(16.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(perm.title, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(perm.description, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
                        }
                    }
                    if (i < permissions.size - 1) {
                        HorizontalDivider(color = Border, thickness = 0.5.dp)
                    }
                }
            }

            // Buttons
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestAll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Background),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("GRANT PERMISSIONS", fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
                }
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("CONTINUE ANYWAY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}
