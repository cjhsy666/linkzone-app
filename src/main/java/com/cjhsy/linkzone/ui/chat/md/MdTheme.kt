package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Markdown 渲染设计 token：白底极简、轻量卡片、统一间距、克制用色。
 * 颜色由消息文字色派生（深字内容 → 中性浅灰卡片；浅字内容 → 白色提亮卡片）。
 */

/** 卡片底色：深字（浅底）用固定中性浅灰，浅字（彩色/暗底）用白提亮 */
fun mdNeutralCard(textColor: Color): Color =
    if (textColor.luminance() > 0.5f) Color.White.copy(alpha = 0.16f) else Color(0xFFF2F3F5)

/** 卡片上的次级文字/图标色（语言标签、按钮） */
fun mdSecondaryText(textColor: Color): Color =
    if (textColor.luminance() > 0.5f) Color.White.copy(alpha = 0.78f) else Color(0xFF6B7280)
