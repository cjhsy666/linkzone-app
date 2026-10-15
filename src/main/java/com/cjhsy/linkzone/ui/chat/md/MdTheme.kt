package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding

/**
 * Markdown 渲染设计 token：白底极简、轻量卡片、统一间距、克制用色。
 * 文字/分割线颜色由气泡文字色派生，AI 气泡（深字）与用户气泡（白字）自动适配。
 */

/** 卡片底色：深字气泡（浅底）用固定中性浅灰，浅字气泡（彩色/暗底）用白提亮 */
fun mdNeutralCard(textColor: Color): Color =
    if (textColor.luminance() > 0.5f) Color.White.copy(alpha = 0.16f) else Color(0xFFF2F3F5)

/** 卡片上的次级文字/图标色（语言标签、按钮） */
fun mdSecondaryText(textColor: Color): Color =
    if (textColor.luminance() > 0.5f) Color.White.copy(alpha = 0.78f) else Color(0xFF6B7280)

@Composable
fun mdColors(textColor: Color, linkColor: Color): MarkdownColors = markdownColor(
    text = textColor,
    codeBackground = mdNeutralCard(textColor),
    inlineCodeBackground = mdNeutralCard(textColor),
    // 分割线克制：仅作行/块之间的极细提示
    dividerColor = textColor.copy(alpha = 0.10f),
    tableBackground = textColor.copy(alpha = 0.03f),
)

@Composable
fun mdTypography(textColor: Color, linkColor: Color): MarkdownTypography {
    val body = MaterialTheme.typography.bodyMedium.copy(
        color = textColor, fontSize = 16.sp, lineHeight = 24.sp
    )
    return markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        // 标题阶梯：比正文明显更黑更大，拉开层级
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold
        ),
        h2 = MaterialTheme.typography.titleMedium.copy(
            color = textColor, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold
        ),
        h3 = MaterialTheme.typography.titleSmall.copy(
            color = textColor, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold
        ),
        h4 = body.copy(fontWeight = FontWeight.SemiBold),
        h5 = body.copy(fontWeight = FontWeight.SemiBold),
        h6 = body.copy(fontWeight = FontWeight.SemiBold),
        code = MaterialTheme.typography.bodySmall.copy(
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp
        ),
        // 引用克制处理：不斜体，仅降透明度
        quote = body.copy(color = textColor.copy(alpha = 0.72f)),
        textLink = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        ),
        table = body.copy(fontSize = 14.sp, lineHeight = 21.sp),
    )
}

@Composable
fun mdPadding(): MarkdownPadding = markdownPadding(
    // 排版节奏放宽：块间距 10dp，标题/段落呼吸感更接近主流对话应用
    block = 10.dp,
    list = 6.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 12.dp,
    codeBlock = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    blockQuote = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
)

@Composable
fun mdDimens(): MarkdownDimens = markdownDimens(
    dividerThickness = 1.dp,
    codeBackgroundCornerSize = 14.dp,
    blockQuoteThickness = 3.dp,
    tableCornerSize = 12.dp,
    tableCellWidth = 110.dp,
    tableCellPadding = 12.dp,
)

/** 关闭文字尺寸动画：流式期间图片占位换正式图不抖动 */
@Composable
fun mdAnimations(): MarkdownAnimations = markdownAnimations(animateTextSize = { this })
