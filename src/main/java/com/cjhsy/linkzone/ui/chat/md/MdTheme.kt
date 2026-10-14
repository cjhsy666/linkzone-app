package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
 * Markdown 渲染设计 token：白底极简、轻量卡片、克制用色。
 * 全部颜色由气泡文字色/链接色派生，AI 气泡（深字）与用户气泡（白字）自动适配。
 */

@Composable
fun mdColors(textColor: Color, linkColor: Color): MarkdownColors = markdownColor(
    text = textColor,
    codeBackground = textColor.copy(alpha = 0.05f),
    inlineCodeBackground = linkColor.copy(alpha = 0.10f),
    dividerColor = textColor.copy(alpha = 0.14f),
    tableBackground = textColor.copy(alpha = 0.03f),
)

@Composable
fun mdTypography(textColor: Color, linkColor: Color): MarkdownTypography {
    val body = MaterialTheme.typography.bodyMedium.copy(
        color = textColor, fontSize = 15.sp, lineHeight = 22.sp
    )
    return markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold
        ),
        h2 = MaterialTheme.typography.titleMedium.copy(
            color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold
        ),
        h3 = MaterialTheme.typography.titleSmall.copy(
            color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
        ),
        h4 = body.copy(fontWeight = FontWeight.SemiBold),
        h5 = body.copy(fontWeight = FontWeight.SemiBold),
        h6 = body.copy(fontWeight = FontWeight.SemiBold),
        code = MaterialTheme.typography.bodySmall.copy(
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp
        ),
        // 引用克制处理：不斜体，仅降透明度
        quote = body.copy(color = textColor.copy(alpha = 0.75f)),
        textLink = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        ),
        table = MaterialTheme.typography.bodySmall.copy(color = textColor),
    )
}

@Composable
fun mdPadding(): MarkdownPadding = markdownPadding(
    block = 6.dp,
    list = 4.dp,
    listItemTop = 2.dp,
    listItemBottom = 2.dp,
    listIndent = 10.dp,
    codeBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    blockQuote = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
)

@Composable
fun mdDimens(): MarkdownDimens = markdownDimens(
    dividerThickness = 1.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
    tableCornerSize = 10.dp,
    tableCellWidth = 120.dp,
    tableCellPadding = 12.dp,
)

/** 关闭文字尺寸动画：流式期间图片占位换正式图不抖动 */
@Composable
fun mdAnimations(): MarkdownAnimations = markdownAnimations(animateTextSize = { this })
