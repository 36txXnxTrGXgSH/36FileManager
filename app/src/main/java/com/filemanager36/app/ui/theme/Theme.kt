package com.filemanager36.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E9B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FA),
    onPrimaryContainer = Color(0xFF06263F),
    secondary = Color(0xFF4F6076),
    secondaryContainer = Color(0xFFD9E3F1),
    tertiary = Color(0xFF6A5B9B),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC9F5),
    onPrimary = Color(0xFF00335A),
    primaryContainer = Color(0xFF124A7E),
    onPrimaryContainer = Color(0xFFD3E4FA),
    secondary = Color(0xFFB9C8DC),
    secondaryContainer = Color(0xFF37485C),
    tertiary = Color(0xFFCFC0FF),
    background = Color(0xFF11151A),
    surface = Color(0xFF171C22),
    surfaceVariant = Color(0xFF2A323C),
    error = Color(0xFFFFB4AB)
)

@Composable
fun FileManager36Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
