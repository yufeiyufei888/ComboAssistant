package com.yufei.comboassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB9ACFF),
    onPrimary = Color(0xFF2F216D),
    secondary = Color(0xFF54C2FF),
    background = Color(0xFF11131D),
    surface = Color(0xFF1B1E2B),
    surfaceVariant = Color(0xFF292D3B),
    error = Color(0xFFFF6B7A),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B49D5),
    secondary = Color(0xFF006A92),
    background = Color(0xFFF8F7FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9E7F3),
    error = Color(0xFFBA1A1A),
)

@Composable
fun ComboAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
