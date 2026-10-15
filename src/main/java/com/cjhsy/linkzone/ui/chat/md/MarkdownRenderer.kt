package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.ui.chat.resolveMedia
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown

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
 * 标题 / 段落 / 列表 / 任务列表 / 引用 / 分割线 / 代码块（高亮+复制）/ 行内样式 /
 * 链接 / 表格 / GFM alert / 图片（Coil2，相对地址拼 baseUrl）。
 *
 * 手势语义：渲染产物为纯 Compose Text + LinkAnnotation，不消费非链接手势——
 * 长按/点击由气泡外层 combinedClickable 接收；自由复制由外层 SelectionContainer 接管；
 * 链接点击走 LocalUriHandler。
 *
 * 流式性能：按顶层块拆分 + 逐块 remember 解析缓存，流式增长仅尾部块重解析。
 *
 * @param selectable 兼容旧签名保留（新实现无需使用，外层 SelectionContainer 已接管）
 * @param onLongClick 兼容旧签名保留（新手势路径不消费，外层 combinedClickable 自然收到）
 * @param onTextClick 兼容旧签名保留（同 onLongClick）
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
    val blocks = remember(markdown) {
        runCatching { splitTopLevelBlocks(markdown) }.getOrElse { listOf(markdown) }
    }
    val colors = mdColors(textColor, linkColor)
    val typography = mdTypography(textColor, linkColor)
    // 用户气泡白字（luminance 高）→ 深色高亮板
    val highlightsBuilder = rememberMdHighlightsBuilder(dark = textColor.luminance() > 0.5f)
    val components = remember(textColor, highlightsBuilder) {
        mdComponents(textColor, highlightsBuilder)
    }
    val imageTransformer = remember(baseUrl) {
        if (baseUrl.isBlank()) Coil2ImageTransformerImpl else BaseUrlImageTransformer(baseUrl)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            // 逐块解析缓存：前序块字符串不变 → remember 命中零开销，仅尾部增长块重解析；
            // 解析失败（罕见，parser 自身容错极高）→ 转义后按纯文本再解析兜底
            val state = remember(block) {
                val parsed = parseMarkdown(block)
                if (parsed is State.Error) parseMarkdown(escapeMarkdown(block)) else parsed
            }
            if (state is State.Error) {
                // 兜底也失败：绝不让内容不可见，直接纯文本展示
                Text(text = block, style = typography.text)
            } else {
                Markdown(
                    state = state,
                    colors = colors,
                    typography = typography,
                    // 库默认 fillMaxSize，气泡内必须 fillMaxWidth，否则 LazyColumn item 测量异常
                    modifier = Modifier.fillMaxWidth(),
                    padding = mdPadding(),
                    dimens = mdDimens(),
                    imageTransformer = imageTransformer,
                    components = components,
                    animations = mdAnimations(),
                )
            }
        }
    }
}
