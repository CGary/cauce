package com.cauce.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CauceGreen = Color(0xFF2F6B4F)
private val CauceGreenLight = Color(0xFF8FD3AE)
private val CauceSand = Color(0xFFF6F2EA)
private val CauceInk = Color(0xFF1B1C1A)

private val LightColors = lightColorScheme(
    primary = CauceGreen,
    onPrimary = Color.White,
    primaryContainer = CauceGreenLight,
    onPrimaryContainer = CauceInk,
    background = CauceSand,
    onBackground = CauceInk,
    surface = Color.White,
    onSurface = CauceInk,
)

private val DarkColors = darkColorScheme(
    primary = CauceGreenLight,
    onPrimary = CauceInk,
    primaryContainer = CauceGreen,
    onPrimaryContainer = Color.White,
    background = Color(0xFF141512),
    onBackground = Color(0xFFECEAE3),
    surface = Color(0xFF1E201C),
    onSurface = Color(0xFFECEAE3),
)

@Composable
fun CauceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
