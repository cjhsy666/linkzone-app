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
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox

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
 * 完整 Markdown 渲染（multiplatform-markdown-renderer 驱动，纯 Compose）：
 *
 * 一条消息 = 一次 Markdown() 调用 —— 这是滚动流畅的根本保证：
 * LazyColumn 里每条消息只产生一个 Markdown 组合，库内部处理所有子元素的渲染。
 *
 * 异步解析由库内部 `rememberMarkdownState` 完成（Dispatchers.Default + conflate 防抖），
 * 流式增长时仅末尾 AST 变化，已稳定的前序 AST 不会被重解析。
 *
 * 自定义组件通过 markdownComponents 注入（只替换需要的，其余走库默认）：
 *   codeFence/codeBlock → MdCodeCard（高亮+复制+下载+折叠）
 *                         mermaid 围栏特殊处理 → MdMermaidIsland
 *   $$...$$ 整段       → MdKatexIsland（离屏透明位图居中）
 *   表格               → MdAlignedTable（GFM 对齐语法 + 表头底色）
 *   含 $...$ 段落       → MdInlineMathParagraph（行内透明位图）
 *
 * modifier 强制 fillMaxWidth：库默认 fillMaxSize，LazyColumn 气泡内必须限制宽度
 * 否则 item 测量异常。
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
            paragraph = { model ->
                val raw = model.content.substring(model.node.startOffset, model.node.endOffset).trim()
                if (raw.length > 4 && Regex("^\\$\\$[\\s\\S]+\\$\\$$").matches(raw)) {
                    MdKatexIsland(raw.removePrefix("$$").removeSuffix("$$").trim(), textColor)
                } else {
                    MdInlineMathParagraph(model)
                }
            },
        )
    }
    val imageTransformer = remember(baseUrl) {
        if (baseUrl.isBlank()) Coil2ImageTransformerImpl else BaseUrlImageTransformer(baseUrl)
    }

    // 用库的 String 重载：内部 rememberMarkdownState 自动异步解析，
    // loading 时空 Box（不占位），success 后渲染。一条消息 = 一个 Markdown 组合。
    Markdown(
        content = markdown,
        colors = colors,
        typography = typography,
        // 库默认 fillMaxSize，气泡内必须 fillMaxWidth，否则 LazyColumn item 测量异常
        modifier = modifier.fillMaxWidth(),
        padding = mdPadding(),
        dimens = mdDimens(),
        imageTransformer = imageTransformer,
        components = components,
        animations = mdAnimations(),
    )
}
