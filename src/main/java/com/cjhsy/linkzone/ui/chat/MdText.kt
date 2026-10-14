package com.cjhsy.linkzone.ui.chat

import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.intellij.markdown.MarkdownElementTypes

/**
 * 完整 Markdown 渲染（intellij-markdown AST 解析 + Compose 原生渲染）：
 * 标题层级（H1/H2 带分隔线）/ 段落 / 有序无序列表 / 任务列表 / 引用块（品牌色竖条卡片）/
 * 代码块（Prism4j 语法高亮 + 行号 + 折叠 + 复制）/ 行内代码（圆角药丸）/ 加粗、斜体、删除线 /
 * 链接（点击打开浏览器）/ 表格 / 行内与块级 LaTeX 公式 / Mermaid 图表（懒加载 WebView）。
 *
 * 解析按内容 remember 缓存：流式期间历史气泡不重复解析；解析异常降级为纯文本，
 * 绝不在 composition 内抛出。
 *
 * 颜色随气泡适配：右侧品牌气泡传白色文字，左侧白气泡传深色文字。
 */
@Composable
fun MarkdownContent(
    markdown: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    /** 自由复制模式：开启后长按可直接选择文本（配合外层 SelectionContainer 使用） */
    selectable: Boolean = false,
    /** 长按 Markdown 文本时回调（弹出消息操作菜单）；自由复制模式下传 null 让位给文本选择 */
    onLongClick: (() -> Unit)? = null,
    /** 点击 Markdown 文本（非链接处）回调（编辑重发 / 退出自由复制） */
    onTextClick: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    // 回调可能随重组变化（气泡菜单重建等），ctx 按内容缓存——回调经稳定引用传入
    val latestLongClick = androidx.compose.runtime.rememberUpdatedState(onLongClick)
    val latestTextClick = androidx.compose.runtime.rememberUpdatedState(onTextClick)

    // 解析 AST：按内容缓存；失败（畸形输入）整体降级为纯文本渲染
    val ast = remember(markdown) {
        runCatching { parseMarkdownAst(markdown) }.getOrNull()
    }

    if (ast == null) {
        Text(
            text = markdown,
            style = bodyTextStyle,
            color = textColor,
            modifier = modifier
        )
        return
    }

    val ctx = remember(markdown, textColor, linkColor, selectable) {
        MdRenderContext(
            src = markdown,
            textColor = textColor,
            linkColor = linkColor,
            selectable = selectable,
            onLongClick = { latestLongClick.value?.invoke() },
            onTextClick = { latestTextClick.value?.invoke() },
            refLinks = collectLinkDefinitions(ast, markdown),
            density = density
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 顶层 MARKDOWN_FILE：直接渲染其子块
        if (ast.type == MarkdownElementTypes.MARKDOWN_FILE) {
            MdBlockChildren(ast, ctx)
        } else {
            MdBlock(ast, ctx)
        }
    }
}

// ---------- 纯文本消息：网址 / 邮箱 / 电话 自动识别为可点链接（纯 Compose 实现） ----------

private val LINKIFY_PATTERNS = listOf(
    // 网址（http/https/ftp 开头，或 www. 开头）
    Regex("""(?:https?|ftp)://[^\s<>"')\]]+|www\.[^\s<>"')\]]+""") to "url",
    // 邮箱
    Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "email",
    // 手机号（中国大陆 11 位）
    Regex("""1[3-9]\d{9}""") to "phone"
)

/**
 * 纯文本消息渲染：不做 Markdown 解析，仅把网址 / 邮箱 / 电话
 * 自动识别为可点链接（QQ 同款效果：网址主题色下划线、点击打开浏览器，
 * 邮箱拉起邮件客户端，电话拉起拨号盘）。
 *
 * 手势策略：单击命中链接注解打开链接；长按主动上报 [onLongClick] 弹消息菜单。
 * 自由复制模式下外层不传 onLongClick，长按交给外层 SelectionContainer 出选区手柄。
 */
@Composable
fun AutoLinkText(
    text: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    /** 长按文本（非链接处）回调（弹消息菜单）；自由复制模式下传 null 让位给外层选择手势 */
    onLongClick: (() -> Unit)? = null,
    /** 点击非链接区域回调（编辑重发 / 退出自由复制）；点链接仍优先打开链接 */
    onTextClick: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 构建带链接注解的 AnnotatedString，只在文本变化时重新扫描
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            val matches = mutableListOf<Triple<IntRange, String, String>>()
            for ((regex, type) in LINKIFY_PATTERNS) {
                for (m in regex.findAll(text)) {
                    matches.add(Triple(m.range, type, m.value))
                }
            }
            matches.sortBy { it.first.first }
            // 去重：跳过与已选区间重叠的匹配
            val selected = mutableListOf<Triple<IntRange, String, String>>()
            for (m in matches) {
                if (selected.none { it.first.overlaps(m.first) }) selected.add(m)
            }
            for ((range, type, raw) in selected) {
                val target = when (type) {
                    "url" -> if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
                    "email" -> "mailto:$raw"
                    "phone" -> "tel:$raw"
                    else -> raw
                }
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    range.first, range.last + 1
                )
                addStringAnnotation(tag = "link", annotation = target, start = range.first, end = range.last + 1)
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor, lineHeight = 21.sp),
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotated, onLongClick != null) {
            detectTapGestures(
                // detectTapGestures 会消费按压事件，外层气泡的 combinedClickable 收不到长按，
                // 因此长按必须在这里检测并主动上报
                onLongPress = { onLongClick?.invoke() },
                onTap = { pos ->
                    val offset = layoutResult?.getOffsetForPosition(pos)
                    val ann = offset?.let {
                        annotated.getStringAnnotations(tag = "link", start = it, end = it).firstOrNull()
                    }
                    if (ann == null) {
                        // 点击非链接区域：上抛给外层气泡（编辑重发 / 退出自由复制），
                        // 否则 detectTapGestures 吞掉点击后气泡永远收不到
                        onTextClick?.invoke()
                        return@detectTapGestures
                    }
                    when {
                        ann.item.startsWith("mailto:") -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                                data = android.net.Uri.parse(ann.item)
                            })
                        }
                        ann.item.startsWith("tel:") -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                                data = android.net.Uri.parse(ann.item)
                            })
                        }
                        else -> runCatching { uriHandler.openUri(ann.item) }
                    }
                }
            )
        }
    )
}

/** 判断两个 IntRange 是否重叠 */
private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last
