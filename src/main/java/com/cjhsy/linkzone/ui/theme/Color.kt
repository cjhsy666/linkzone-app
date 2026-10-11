package com.cjhsy.linkzone.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ============================================================
// 设计令牌 —— 与管理端 linkzone-admin design-tokens.scss 完全对齐
// ============================================================

// ---------- 品牌色 ----------
val BrandCyan = Color(0xFF00F5FF)        // --brand-cyan
val BrandPurple = Color(0xFFB47FFF)      // --brand-purple（暗色）
val BrandDeepPurple = Color(0xFF764BA2)  // --brand-deep-purple

// ---------- 浅色模式 ----------
val LightPrimary = Color(0xFF5B6BF0)          // 高饱和蓝紫（现代感，白字对比 4.5:1+）
val LightPrimaryLight = Color(0xFF818cf8)     // --color-primary-light
val LightBackground = Color(0xFFF6F7FB)       // 页面背景（略带冷灰，衬托悬浮卡片）
val LightSurface = Color(0xFFFFFFFF)          // 卡片 / 悬浮胶囊
val LightSurfaceVariant = Color(0xFFF1F5F9)   // --color-border-light 同源的浅填充
val LightOnBackground = Color(0xFF0F172A)     // --color-text-primary
val LightOnSurfaceVariant = Color(0xFF64748B) // --color-text-secondary
val LightOnSurfaceTertiary = Color(0xFF94A3B8)// --color-text-tertiary
val LightOutline = Color(0xFFE2E8F0)          // --color-border

// ---------- 深色模式 ----------
val DarkPrimary = Color(0xFF5B8CFF)           // 暗色主色（柔和蓝，避免荧光青的刺眼感）
val DarkBackground = Color(0xFF0B1120)        // 页面背景（深于 surface，拉开层级）
val DarkSurface = Color(0xFF151E31)           // 卡片 / 悬浮胶囊
val DarkSurfaceVariant = Color(0xFF1E293B)    // 浅填充
val DarkOnBackground = Color(0xFFF1F5F9)      // --color-text-primary
val DarkOnSurfaceVariant = Color(0xFFCBD5E1)  // --color-text-secondary
val DarkOutline = Color(0xFF2A3648)           // --color-border

// ---------- 功能色 ----------
val SuccessGreen = Color(0xFF10B981)   // --color-success
val WarningAmber = Color(0xFFF59E0B)   // --color-warning
val ErrorRed = Color(0xFFEF4444)       // --color-error
val InfoBlue = Color(0xFF3B82F6)       // --color-info
val CyanAccent = Color(0xFF06B6D4)     // --gradient-cyan 起点

// ---------- 品牌渐变（--theme-gradient）----------
// 浅色：linear-gradient(135deg, #667eea 0%, #764ba2 100%)
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF667eea), Color(0xFF764BA2)))

// 暗色：linear-gradient(135deg, #00F5FF 0%, #B47FFF 100%)
val BrandGradientDark = Brush.linearGradient(listOf(BrandCyan, BrandPurple))

// ---------- 功能色渐变（--gradient-*）----------
val SuccessGradient = Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
val WarningGradient = Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFD97706)))
val DangerGradient = Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))
val PurpleGradient = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF7C3AED)))
val CyanGradient = Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFF0891B2)))
