package com.hcebox.driver.androidnative.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF0061A8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD2E4FF),
        onPrimaryContainer = Color(0xFF002B4D),
        secondary = Color(0xFF506176),
        secondaryContainer = Color(0xFFDAE6F5),
        onSecondaryContainer = Color(0xFF142C45),
        background = Color(0xFFF8FAFD),
        surface = Color(0xFFF8FAFD),
        surfaceContainer = Color(0xFFEDF1F7),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFA0CAFF),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF004B83),
        onPrimaryContainer = Color(0xFFD2E4FF),
        secondary = Color(0xFFB8C8DF),
        secondaryContainer = Color(0xFF3B4B60),
        onSecondaryContainer = Color(0xFFDAE6F5),
        background = Color(0xFF101419),
        surface = Color(0xFF101419),
        surfaceContainer = Color(0xFF1C2026),
    )

/** Native Reader family palette, following the existing driver's light/dark theme arrangement. */
@Composable
fun NativeDriverTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
