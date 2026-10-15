package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import com.cjhsy.linkzone.ui.chat.resolveMedia
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

/** markdown 内相对图片地址经 [resolveMedia] 拼接 baseUrl 后走 Coil2 */
private class BaseUrlImageTransformer(private val baseUrl: String) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? =
        Coil2ImageTransformerImpl.transform(resolveMedia(link, baseUrl))

    @Composable
    override fun intrinsicSize(painter: Painter) =
        Coil2ImageTransformerImpl.intrinsicSize(painter)
}

/**
 * Markdown 渲染（multiplatform-markdown-renderer 驱动，纯 Compose）。
 *
 * 架构原则：尽量用库默认组件保证排版一致性，只替换必须自定义的：
 *   - 代码围栏/缩进代码块 → MdCodeCard（高亮+复制+折叠）
 *   - mermaid 围栏 → MdMermaidIsland（离屏位图+全屏预览）
 *   - 表格 → MdAlignedTable（GFM 对齐语法 + 表头底色）
 *   - checkbox 显式保持 m3（传自定义 components 时库默认退回 core 版）
 *
 * 标题/段落/列表/引用/分割线/图片/行内样式全部走库默认 + 设计 token，
 * 避免自定义组件与库默认组件间距/样式互相冲突导致的排版错乱。
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
) {
    val colors = mdColors(textColor, linkColor)
    val typography = mdTypography(textColor, linkColor)
    val highlightsBuilder = rememberMdHighlightsBuilder(dark = textColor.luminance() > 0.5f)

    val components = remember(textColor, highlightsBuilder) {
        markdownComponents(
            codeFence = { model ->
                com.mikepenz.markdown.compose.elements.MarkdownCodeFence(
                    model.content, model.node, style = model.typography.code
                ) { code, language, style ->
                    if (language?.trim()?.lowercase() == "mermaid") MdMermaidIsland(code, textColor)
                    else MdCodeCard(code, language, style, textColor, highlightsBuilder)
                }
            },
            codeBlock = { model ->
                com.mikepenz.markdown.compose.elements.MarkdownCodeBlock(
                    model.content, model.node, style = model.typography.code
                ) { code, language, style ->
                    if (language?.trim()?.lowercase() == "mermaid") MdMermaidIsland(code, textColor)
                    else MdCodeCard(code, language, style, textColor, highlightsBuilder)
                }
            },
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            table = { MdAlignedTable(it.content, it.node, it.typography.table) },
        )
    }
    val imageTransformer = remember(baseUrl) {
        if (baseUrl.isBlank()) Coil2ImageTransformerImpl else BaseUrlImageTransformer(baseUrl)
    }

    Markdown(
        content = markdown,
        colors = colors,
        typography = typography,
        modifier = modifier.fillMaxWidth(),
        padding = mdPadding(),
        dimens = mdDimens(),
        imageTransformer = imageTransformer,
        components = components,
        animations = mdAnimations(),
    )
}
