package com.legado.drama.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============ 1. M3 ColorScheme（暗色 AI 科技感，UI-BEAUTIFY-SPEC §1.1） ============ */

private val DramaDarkColors = darkColorScheme(
    background = Color(0xFF0D0A1A),
    onBackground = Color(0xFFE8E2F5),
    surface = Color(0xFF151022),
    onSurface = Color(0xFFE8E2F5),
    surfaceVariant = Color(0xFF221A38),
    onSurfaceVariant = Color(0xFFB8AECF),
    surfaceContainerLowest = Color(0xFF0A0714),
    surfaceContainerLow = Color(0xFF120D1F),
    surfaceContainer = Color(0xFF171126),
    surfaceContainerHigh = Color(0xFF1D1630),
    surfaceContainerHighest = Color(0xFF241C38),
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF1E1140),
    primaryContainer = Color(0xFF4A2E85),
    onPrimaryContainer = Color(0xFFE8DDF5),
    secondary = Color(0xFF64E3FF),
    onSecondary = Color(0xFF00343D),
    secondaryContainer = Color(0xFF0E3A47),
    onSecondaryContainer = Color(0xFFC5F4FF),
    tertiary = Color(0xFFFF7AD9),
    onTertiary = Color(0xFF3A0A2E),
    tertiaryContainer = Color(0xFF5A1B4C),
    onTertiaryContainer = Color(0xFFFFD9F1),
    error = Color(0xFFFF6B7A),
    onError = Color(0xFF3B0A12),
    errorContainer = Color(0xFF5C1A24),
    onErrorContainer = Color(0xFFFFDADD),
    outline = Color(0xFF5E5478),
    outlineVariant = Color(0xFF3A3157),
    surfaceTint = Color(0xFFB388FF),
    scrim = Color(0xFF000000),
)

/* ============ 2. 霓虹/渐变/玻璃常量（UI-BEAUTIFY-SPEC §1.2，全局取色唯一来源） ============ */

/** 霓虹点缀色与玻璃拟态常量：页面一律引用本对象，禁止硬编码色值 */
object DramaNeon {
    val NeonPurple = Color(0xFFC77DFF)
    val NeonCyan = Color(0xFF22D3EE)
    val NeonMagenta = Color(0xFFFF4FD8)
    val NeonGreen = Color(0xFF57E8A0)
    val NeonAmber = Color(0xFFFFC24D)
    val GlassStroke = Color(0x1AFFFFFF)
    val GlassFill = Color(0x0DFFFFFF)
    val GlowShadow = Color(0x59B388FF)

    /** 开屏渐变双端（与 Theme 底色视觉连贯） */
    val SplashTop = Color(0xFF1A1030)
    val SplashBottom = Color(0xFF0D0A1A)
    val SplashText = Color(0xFFE8DDF5)
}

/** 渐变 Brush（UI-BEAUTIFY-SPEC §1.2：GradHero / GradAI） */
object DramaGradients {
    /** 标题渐变字、CTA 主按钮、进度渐变：紫 → 青 */
    val Hero = Brush.linearGradient(listOf(Color(0xFFB388FF), Color(0xFF64E3FF)))
    /** AI 悬浮球、AI 面板头：紫 → 品红 */
    val Ai = Brush.linearGradient(listOf(Color(0xFFB388FF), Color(0xFFFF7AD9)))
    /** 卡片缩略图渐变底（GradHero 低饱和 15%） */
    val HeroSoft = Brush.linearGradient(
        listOf(Color(0x26B388FF), Color(0x2664E3FF)),
    )
    /** 成片卡缩略图深色渐变底（LIBRARY） */
    val Film = Brush.linearGradient(listOf(Color(0xFF0D0A1A), Color(0xFF1D1630)))
    /** 场次分隔渐变细线（分镜页） */
    val Divider = Brush.horizontalGradient(
        listOf(Color(0x4DB388FF), Color.Transparent),
    )
}

/* ============ 3. Typography（UI-BEAUTIFY-SPEC §2：正文基准 bodyMedium 14sp） ============ */

private val DramaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp,
    ),
)

/* ============ 4. Shapes（UI-BEAUTIFY-SPEC §3 圆角体系） ============ */

private val DramaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

/**
 * drama 模块独立主题（UI-BEAUTIFY-SPEC §0-3）：
 * 全局暗色 AI 科技感；全部颜色取色自 colorScheme / DramaNeon / DramaGradients。
 * 深色为唯一目标形态（规格 §7 验收：暗色为主，浅色可延后），故不跟随系统亮色。
 */
@Composable
fun DramaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DramaDarkColors,
        typography = DramaTypography,
        shapes = DramaShapes,
        content = content,
    )
}