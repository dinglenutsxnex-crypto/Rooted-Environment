package com.rootdroid.inspector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val MonoFamily = FontFamily.Monospace

private val DarkColors = darkColorScheme(
    primary           = NeonGreen,
    onPrimary         = Color.Black,
    secondary         = NeonGreenDim,
    onSecondary       = Color.Black,
    background        = Background,
    onBackground      = TextPrimary,
    surface           = Surface,
    onSurface         = TextPrimary,
    surfaceVariant    = SurfaceHigh,
    onSurfaceVariant  = TextMuted,
    outline           = Border,
    error             = LogError,
    onError           = Color.White,
)

@Composable
fun RootDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
