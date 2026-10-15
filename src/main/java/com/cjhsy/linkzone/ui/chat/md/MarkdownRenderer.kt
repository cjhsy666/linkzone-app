package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
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
 * 标题 / 段落 / 列表 / 任务列表 / 引用 / 分割线 / 代码块（高亮+复制）/ 行内样式 /
 * 链接 / 表格 / GFM alert / 图片（Coil2，相对地址拼 baseUrl）。
 *
 * 手势语义：渲染产物为纯 Compose Text + LinkAnnotation，不消费非链接手势——
 * 长按/点击由气泡外层 combinedClickable 接收；自由复制由外层 SelectionContainer 接管；
 * 链接点击走 LocalUriHandler。
 *
 * 流式性能：按顶层块拆分 + 异步解析（Dispatchers.Default，主线程零解析），
 * 块级缓存跨流式更新复用，流式增长仅新增块解析；滑动进入长消息同样不阻塞 UI 线程。
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
    // 解析全部异步（Dispatchers.Default）：主线程零解析 —— LazyColumn 滑动进入新消息、
    // 流式增长均不再阻塞 UI 线程。块缓存跨流式更新复用（前序块字符串不变直接命中），
    // 仅新增块解析；解析完成前以纯文本占位（渲染快且内容可见），失败转义重解析，
    // 再失败保持纯文本 —— 内容绝不可不可见
    val stateCache = remember { mutableStateMapOf<String, State?>() }
    LaunchedEffect(blocks) {
        val missing = blocks.filter { !stateCache.containsKey(it) }
        if (missing.isEmpty()) return@LaunchedEffect
        val parsed = withContext(Dispatchers.Default) {
            missing.map { block ->
                runCatching { parseMarkdown(block) as? State.Success }
                    .getOrElse {
                        runCatching { parseMarkdown(escapeMarkdown(block)) as? State.Success }.getOrNull()
                    }
            }
        }
        missing.forEachIndexed { i, block -> stateCache[block] = parsed[i] }
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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (val state = stateCache[block]) {
                is State.Success -> Markdown(
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
                // 解析中 / 解析失败：纯文本展示（解析仅数毫秒，长消息短暂渐进无碍）
                else -> Text(text = block, style = typography.text)
            }
        }
    }
}
