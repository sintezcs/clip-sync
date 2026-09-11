package com.clipsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF07506B), onPrimary = Color.White,
    secondary = Color(0xFF386581), onSecondary = Color.White,
    background = Color(0xFFF0F1F3), onBackground = Color(0xFF191C20),
    surface = Color.White, onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE6EDF1), onSurfaceVariant = Color(0xFF535D66),
    outlineVariant = Color(0xFFDDE1E5), error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9DD0E7), onPrimary = Color(0xFF003449),
    secondary = Color(0xFF9BC6DF), onSecondary = Color(0xFF123747),
    background = Color(0xFF111316), onBackground = Color(0xFFE4E6EB),
    surface = Color(0xFF202327), onSurface = Color(0xFFE4E6EB),
    surfaceVariant = Color(0xFF29343B), onSurfaceVariant = Color(0xFFB8C1C9),
    outlineVariant = Color(0xFF3A4147), error = Color(0xFFFFB4AB),
)

@Composable
fun ClipSyncTheme(isDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark) Dark else Light,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
        ), content = content)
}
