package com.cjhsy.linkzone.ui.chat

import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * 完整 Markdown 渲染（Markwon 驱动 TextView）：
 * 标题 / 段落 / 有序无序列表 / 引用 / 分割线 / 代码块 / 行内代码、加粗、斜体、删除线 /
 * 链接（点击打开浏览器）/ 表格 / 任务列表复选框 / 自动链接 / 基础内联 HTML /
 * LaTeX 数学公式（块级 $$...$$ 与行内 $...$）。
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
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val latexTextSize = with(density) { 14.sp.toPx() }

    val markwon = remember(linkColor, textColor) {
        // 代码块 / 行内代码配色（随气泡文字色派生，无新依赖）
        val inlineCodeBg = linkColor.copy(alpha = 0.12f).toArgb()
        val blockCodeBg = textColor.copy(alpha = 0.06f).toArgb()
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            // 行内解析交给 inline-parser（行内 LaTeX 需要），同时启用行内 LaTeX
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(latexTextSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().blockFitCanvas(true)
                }
            )
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(linkColor.toArgb())
                        .codeBackgroundColor(inlineCodeBg)
                        .codeBlockTextColor(textColor.toArgb())
                        .codeBlockBackgroundColor(blockCodeBg)
                }
            })
            .build()
    }
    // 仅在内容变化时解析+渲染，避免列表重组反复解析整段 Markdown。
    // 注意 markwon.parse() 返回的是 CommonMark 的 Node，
    // setParsedMarkdown 需要的是渲染后的 Spanned，故直接用 toMarkdown()。
    val rendered = remember(markwon, markdown) { markwon.toMarkdown(preprocessLatex(markdown)) }

    // 长按/点击回调可能随重组变化，用稳定引用持有最新值（TextView 监听器只设置一次）
    val latestLongClick = rememberUpdatedState(onLongClick)
    val latestClick = rememberUpdatedState(onTextClick)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                // 链接由 Markwon 的 LinkResolver 统一以 ACTION_VIEW 打开浏览器
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                // 自由复制模式由外层切换（菜单点"自由复制"进入，点气泡背景退出）
                setTextIsSelectable(false)
                setLineSpacing(4f, 1f)
                val pad = (6 * ctx.resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
                // 长按主动上报给外层弹消息菜单：
                // TextView 会吞掉触摸流，外层 Compose 的 combinedClickable 收不到长按，
                // 必须在这里桥接。返回 true 消费掉，避免触发系统的文本选择；
                // 自由复制模式下外层传 null，返回 false 让位给系统选择
                setOnLongClickListener {
                    val cb = latestLongClick.value
                    if (cb != null && !isTextSelectable) {
                        cb()
                        true
                    } else false
                }
                // 设置 OnLongClickListener 后 TextView 会消费掉所有触摸事件（含点击），
                // 外层气泡的点击（编辑重发/退出自由复制）收不到，这里桥接点击；
                // 命中链接时 LinkMovementMethod 优先处理，不会触发该回调
                setOnClickListener { latestClick.value?.invoke() }
            }
        },
        update = { tv ->
            tv.setTextColor(textColor.toArgb())
            tv.setLinkTextColor(linkColor.toArgb())
            tv.setTextIsSelectable(selectable)
            tv.textSize = 14f
            tv.text = rendered
        }
    )
}

// ---------- 行内 LaTeX 预处理：把单美元 $...$ 转为 Markwon 的 $$...$$ ----------
// Markwon 行内公式语法是 $$...$$，而模型通常输出 $...$；
// 仅当内容具备 LaTeX 特征（反斜杠命令/上下标/花括号/等号/frac）才转换，避免误伤货币金额。
private val SINGLE_DOLLAR_LATEX = Regex("""(?<!\$)\$(?!\$)(?!\s)([^\$\n]{1,200}?)\$(?!\$)""")

private fun preprocessLatex(markdown: String): String =
    SINGLE_DOLLAR_LATEX.replace(markdown) { m ->
        val inner = m.groupValues[1]
        val looksLikeLatex = inner.contains('\\') || inner.contains('^') || inner.contains('_') ||
            inner.contains('{') || inner.contains('=') || inner.contains("frac")
        if (looksLikeLatex) "\$\$$inner\$\$" else m.value
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
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
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
