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
    dividerColor = textColor.copy(alpha = 0.08f),
    tableBackground = textColor.copy(alpha = 0.03f),
)

@Composable
fun mdTypography(textColor: Color, linkColor: Color): MarkdownTypography {
    // 正文：16sp / 24sp 行高（1.5x），主流对话应用标准
    val body = MaterialTheme.typography.bodyLarge.copy(
        color = textColor,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    return markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        // 标题阶梯：h1 22sp / h2 20sp / h3 18sp，Bold，行高 1.35x
        h1 = body.copy(
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
        ),
        h2 = body.copy(
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
        ),
        h3 = body.copy(
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        ),
        h4 = body.copy(
            fontSize = 17.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h5 = body.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h6 = body.copy(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        // 代码：13sp / 20sp，等宽字体
        code = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        ),
        // 引用：降透明度，不斜体
        quote = body.copy(color = textColor.copy(alpha = 0.72f)),
        textLink = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        ),
        table = body.copy(fontSize = 14.sp, lineHeight = 21.sp),
    )
}

@Composable
fun mdPadding(): MarkdownPadding = markdownPadding(
    // 块间距 12dp：标题/段落/代码块/表格之间统一呼吸感
    block = 12.dp,
    list = 8.dp,
    listItemTop = 4.dp,
    listItemBottom = 4.dp,
    listIndent = 16.dp,
    codeBlock = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    blockQuote = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
)

@Composable
fun mdDimens(): MarkdownDimens = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 12.dp,
    blockQuoteThickness = 3.dp,
    tableCornerSize = 10.dp,
    tableCellWidth = 120.dp,
    tableCellPadding = 10.dp,
)

/** 关闭文字尺寸动画：流式期间图片占位换正式图不抖动 */
@Composable
fun mdAnimations(): MarkdownAnimations = markdownAnimations(animateTextSize = { this })
