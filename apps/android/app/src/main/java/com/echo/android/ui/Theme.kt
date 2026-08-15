package com.echo.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Echo 品牌主题：主色与桌面图标/悬浮球统一（#2F6FED，Tachiyomi 系阅读蓝的同族色）。
 * 覆盖 Compose 默认紫色 colorScheme 的全部主要槽位。
 */

private val Blue = Color(0xFF2F6FED)
private val BlueContainer = Color(0xFFDBE3FF)
private val OnBlueContainer = Color(0xFF0E2A6B)

private val LightScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = OnBlueContainer,
    secondary = Color(0xFF595E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF75546F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F1),
    onTertiaryContainer = Color(0xFF2C1229),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF454652),
    outline = Color(0xFF757680),
    outlineVariant = Color(0xFFC5C6D0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF1B2F77),
    primaryContainer = Color(0xFF2B4290),
    onPrimaryContainer = BlueContainer,
    secondary = Color(0xFFC2C5D4),
    onSecondary = Color(0xFF2C3040),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFFE4BAD7),
    onTertiary = Color(0xFF43273F),
    tertiaryContainer = Color(0xFF5B3D56),
    onTertiaryContainer = Color(0xFFFFD7F1),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE2E1EC),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE2E1EC),
    surfaceVariant = Color(0xFF454652),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF454652),
)

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
