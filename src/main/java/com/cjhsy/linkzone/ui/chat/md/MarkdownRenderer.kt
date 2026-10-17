package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Markdown 渲染入口（RikkaHub 方案：intellij-markdown AST 后台解析 + 分块渲染；
 * 含 HTML 时自动切换 Jsoup DOM 渲染；LaTeX 走 jlatexmath 原生绘制）。
 *
 * 相比旧 mikepenz 实现：解析在 Dispatchers.Default，流式追加只有末块重组，长文滚动不卡。
 *
 * selectable/onLongClick/onTextClick 保留签名兼容（选择复制由外层 SelectionContainer 负责）。
 */
@Composable
fun MarkdownContent(
    markdown: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onTextClick: (() -> Unit)? = null,
    baseUrl: String = "",
    /**
     * true（AI 文档流默认）：占满父容器全宽；
     * false（用户消息）：宽度按内容收束并靠右——纯文本短消息贴右，
     * 代码块/表格/图片等块级元素仍会撑满父容器允许的最大宽度。
     */
    fillWidth: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val mergedScheme = remember(scheme, linkColor) { scheme.copy(primary = linkColor) }
    val widthModifier = if (fillWidth) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.wrapContentWidth(Alignment.End)
    }
    MaterialTheme(colorScheme = mergedScheme) {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalMdBaseUrl provides baseUrl,
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = textColor)) {
                MarkdownBlock(content = markdown, modifier = modifier.then(widthModifier))
            }
        }
    }
}
