package com.legado.drama.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A5BF6),
    secondary = Color(0xFF7C4DFF),
    tertiary = Color(0xFF00BFA5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C9AFF),
    secondary = Color(0xFFB388FF),
    tertiary = Color(0xFF64FFDA),
)

/** drama 模块独立主题（不依赖 Legado 主题资源，独立可编译） */
@Composable
fun DramaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}