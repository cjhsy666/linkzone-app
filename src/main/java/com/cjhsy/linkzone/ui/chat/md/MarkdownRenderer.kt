package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import com.cjhsy.linkzone.ui.chat.resolveMedia
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * 整条消息一次性 parse + 单次 Markdown() 调用 —— 这是滚动流畅的根本保证：
 * LazyColumn 里每条消息只产生**一个** Markdown 组合，库内部处理标题/段落/
 * 代码/表格/公式/图片/列表/引用/分割线等所有元素的渲染与虚拟化，
 * 避免了此前拆块+N 次 Markdown() 导致的每帧 layout/draw 开销。
 *
 * 解析全部异步走 Dispatchers.Default（主线程零解析）：
 * 解析完成前以纯文本占位（毫秒级过渡，内容始终可见），
 * 流式增长时 markdown 参数变化触发 LaunchedEffect 重新解析（后台线程）。
 *
 * 自定义组件通过 markdownComponents 注入：
 * 代码围栏 → MdCodeCard（高亮+复制+下载+折叠）
 * mermaid 围栏 → MdMermaidIsland（离屏位图+全屏预览）
 * $$ 块级公式 → MdKatexIsland（离屏透明位图居中）
 * 表格 → MdAlignedTable（对齐语法+表头底色）
 * 含 $...$ 的段落 → MdInlineMathParagraph（行内透明位图）
 *
 * 手势语义：渲染产物为纯 Compose Text + LinkAnnotation，不消费非链接手势 ——
 * 长按/点击由气泡外层 combinedClickable 接收；自由复制由外层 SelectionContainer 接管；
 * 链接点击走 LocalUriHandler。
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
    // 异步解析：Dispatchers.Default，主线程零阻塞。
    // state 默认为 null（解析中），完成后更新为 State.Success 或保持 null（解析失败降级）
    var state by mutableStateOf<State.Success?>(null)
    LaunchedEffect(markdown) {
        state = withContext(Dispatchers.Default) {
            runCatching { parseMarkdown(markdown) as? State.Success }.getOrNull()
                ?: runCatching { parseMarkdown(escapeMarkdown(markdown)) as? State.Success }.getOrNull()
        }
    }

    // 所有 design token 与组件依赖 textColor/linkColor/highlightsBuilder
    val colors = mdColors(textColor, linkColor)
    val typography = mdTypography(textColor, linkColor)
    val highlightsBuilder = rememberMdHighlightsBuilder(dark = textColor.luminance() > 0.5f)
    val components = com.mikepenz.markdown.compose.components.markdownComponents(
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
        checkbox = { com.mikepenz.markdown.m3.elements.MarkdownCheckBox(it.content, it.node, it.typography.text) },
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
    val imageTransformer = remember(baseUrl) {
        if (baseUrl.isBlank()) Coil2ImageTransformerImpl else BaseUrlImageTransformer(baseUrl)
    }

    if (state == null) {
        // 解析中：纯文本占位（毫秒级，内容可见且不阻塞）
        Text(text = markdown, style = typography.text, modifier = modifier)
    } else {
        Markdown(
            state = state!!,
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
}

/** 防御性 escape：解析器遇到罕见输入（未闭合围栏/畸形 HTML）时转义后再试一次 */
private fun escapeMarkdown(raw: String): String =
    raw.replace("\n", "\n\\")
