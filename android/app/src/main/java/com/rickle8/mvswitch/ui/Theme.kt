package com.rickle8.mvswitch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val bg = Color(0xFF0C1015)
    val surface = Color(0xFF151B23)
    val surface2 = Color(0xFF1D2530)
    val line = Color(0xFF2A3441)
    val text = Color(0xFFE8EDF3)
    val muted = Color(0xFF8A96A6)
    val accent = Color(0xFF2BD9A0)   // audio / live
    val onAccent = Color(0xFF04281C)
    val amber = Color(0xFFF5A524)    // break / commercial
    val red = Color(0xFFFF6B6B)
    val blue = Color(0xFF6CB4FF)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = C.onAccent,
            secondary = C.blue,
            background = C.bg,
            onBackground = C.text,
            surface = C.surface,
            onSurface = C.text,
            surfaceVariant = C.surface2,
            onSurfaceVariant = C.muted,
            outline = C.line,
            error = C.red,
        ),
        content = content,
    )
}
