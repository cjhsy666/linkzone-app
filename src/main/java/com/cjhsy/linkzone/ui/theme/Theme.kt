package com.cjhsy.linkzone.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 品牌渐变（随明暗模式切换）：
 * 浅色 #667eea → #764ba2；暗色 #00F5FF → #B47FFF。
 * 用法：Box(Modifier.background(LocalBrandGradient.current))
 */
val LocalBrandGradient = staticCompositionLocalOf<Brush> { BrandGradient }

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E5F9),      // primary 8% 同源浅底
    onPrimaryContainer = Color(0xFF4F46B8),
    secondary = BrandDeepPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE7F6),
    onSecondaryContainer = Color(0xFF5B3A80),
    tertiary = CyanAccent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F4FA),
    onTertiaryContainer = Color(0xFF075E6E),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFDEAEA),
    onErrorContainer = Color(0xFFB91C1C)
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF0A1A33),
    primaryContainer = Color(0xFF223E77),
    onPrimaryContainer = Color(0xFFBBD0FF),
    secondary = BrandPurple,
    onSecondary = Color(0xFF2A1045),
    secondaryContainer = Color(0xFF4A2A70),
    onSecondaryContainer = Color(0xFFE3D3FF),
    tertiary = Color(0xFF22D3EE),
    onTertiary = Color(0xFF00344A),
    tertiaryContainer = Color(0xFF0E4C58),
    onTertiaryContainer = Color(0xFFA5F3FC),
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = Color(0xFF243044),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA)
)

/**
 * 应用主题：Material 3，配色对齐管理端 design-tokens。
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统
 * @param dynamicColor 是否启用 Android 12+ 动态取色，默认关闭以保证品牌一致性
 */
@Composable
fun LinkZoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 沉浸式：系统栏透明，内容绘制到其下方（enableEdgeToEdge 已开启，
            // 这里兜底覆盖 XML 主题可能带入的不透明底色）
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalBrandGradient provides (if (darkTheme) BrandGradientDark else BrandGradient)
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LinkZoneTypography,
            content = content
        )
    }
}
