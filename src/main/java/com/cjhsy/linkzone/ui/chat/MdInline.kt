package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 行内渲染：AST inline 节点 → AnnotatedString（粗体/斜体/删除线/链接/行内代码/行内公式）。
 * 行内代码 ≤24 字符走 InlineTextContent 圆角药丸；行内公式走 bitmap InlineTextContent。
 */

/** 行内渲染上下文（块级渲染共用）；颜色一律走 [palette] 令牌 */
internal class MdRenderContext(
    val src: String,
    val palette: MdPalette,
    val selectable: Boolean,
    val onLongClick: (() -> Unit)?,
    val onTextClick: (() -> Unit)?,
    val refLinks: Map<String, Pair<String, String?>>,
    val density: Density,
    val streaming: Boolean = false,
    val textMeasurer: TextMeasurer? = null,
    /** 主线程预算的行内药丸/键帽实测宽度（key = pillWidthKey(fontSize, text)） */
    val pillWidths: Map<String, Float> = emptyMap(),
    /** TOC 预留：标题节点 → 文档顺序索引（IdentityHashMap，节点身份稳定） */
    val headingIndex: Map<ASTNode, Int> = emptyMap(),
    /** TOC 预留：标题锚点收集器（null 时零开销，不做坐标采集） */
    val headingAnchors: MdHeadingAnchors? = null,
    /** 脚注模型（null 时 `[^id]` 按原文渲染） */
    val footnotes: MdFootnotes? = null,
    /** 脚注双向定位锚点（null 时上标/条目不可跳转） */
    val footnoteNav: MdFootnoteNav? = null,
    /** 脚注跳转协程作用域（bringIntoView 为挂起函数） */
    val footnoteScope: CoroutineScope? = null
) {
    // 兼容派生（组件迁移期间的统一取色入口，最终全部直接读 palette）
    val textColor: Color get() = palette.text
    val linkColor: Color get() = palette.link
    val dark: Boolean get() = palette.dark
    val container: MdContainer get() = palette.container

    val textSizePx: Float = with(density) { MdType.bodySize.toPx() }

    // <kbd> 键帽解析的瞬时状态：行内构建已移到 Default 线程，故用 ThreadLocal 隔离，
    // 同一 ctx 被多个段落协程并发构建时不会互相污染；出口复位
    internal val kbdActive: ThreadLocal<Boolean> = ThreadLocal()

    /** 派生渲染上下文：换源文本（脚注定义文本独立解析）、可选覆盖正文色、清空脚注（避免递归） */
    internal fun forSource(newSrc: String, textOverride: Color? = null): MdRenderContext =
        MdRenderContext(
            src = newSrc,
            palette = if (textOverride != null) palette.copy(text = textOverride) else palette,
            selectable = false,
            onLongClick = null,
            onTextClick = null,
            refLinks = refLinks,
            density = density,
            streaming = streaming,
            textMeasurer = textMeasurer,
            pillWidths = pillWidths,
            headingIndex = headingIndex,
            headingAnchors = headingAnchors,
            footnotes = null,
            footnoteNav = null,
            footnoteScope = null
        )
}

/** px → Placeholder 的 sp 宽度（Placeholder 以 sp 计，需补偿 fontScale） */
internal fun Density.pxToPlaceholderSp(px: Float): Float = px.toDp().value / fontScale

/** 兜底测量画笔（Paint 线程安全，等宽字型对中文/全角/emoji 均实测精确） */
private val pillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    typeface = android.graphics.Typeface.MONOSPACE
}

/**
 * 行内药丸/键帽宽度（单位 sp，供 Placeholder 使用）。
 * 优先查主线程预算表（TextMeasurer 实测）；未命中时用线程安全 Paint 实测，
 * 绝不退化为「拉丁字宽 × 字符数」估算，从而修复中文/全角截断（TR-4.1）。
 */
internal fun MdRenderContext.measureInlineWidthSp(
    text: String,
    fontSize: Int,
    padHPx: Float
): Float {
    pillWidths[pillWidthKey(fontSize, text)]?.let { return it }
    val px = synchronized(pillPaint) {
        pillPaint.textSize = with(density) { fontSize.sp.toPx() }
        pillPaint.measureText(text) + padHPx * 2f
    }
    return with(density) { pxToPlaceholderSp(px) }
}

internal fun buildInlineString(
    node: ASTNode,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    // 标题 / 表格单元格的 inline 内容常带首尾空白 token（如 "# Title" 的 ATX_CONTENT
    // 子节点是 WHITE_SPACE + TEXT + WHITE_SPACE），渲染前裁掉首尾 WHITE_SPACE
    val kids = node.children
    var start = 0
    var end = kids.size
    while (start < end && kids[start].type == MarkdownTokenTypes.WHITE_SPACE) start++
    while (end > start && kids[end - 1].type == MarkdownTokenTypes.WHITE_SPACE) end--
    // kbd 状态在单次构建出口复位，畸形未闭合标签不污染后续段落
    val savedKbd = ctx.kbdActive.get() ?: false
    ctx.kbdActive.set(false)
    try {
        appendInlineSequence(kids.subList(start, end), ctx, out, inlineContent)
    } finally {
        ctx.kbdActive.set(savedKbd)
    }
}

/** 多节点行内构建（紧凑列表 / Alert 首段去掉 [!TAG] 后使用） */
internal fun buildInlineNodes(
    nodes: List<ASTNode>,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    val savedKbd = ctx.kbdActive.get() ?: false
    ctx.kbdActive.set(false)
    try {
        appendInlineSequence(nodes, ctx, out, inlineContent)
    } finally {
        ctx.kbdActive.set(savedKbd)
    }
}

/**
 * 顺序渲染一组兄弟节点：先识别跨 token 的脚注引用 `[^id]`（LBRACKET + TEXT + RBRACKET），
 * 命中则渲染为上标药丸并跳过后三个 token；否则交给 [appendInlineNode] 常规处理。
 */
private fun appendInlineSequence(
    nodes: List<ASTNode>,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    val fn = ctx.footnotes
    var i = 0
    while (i < nodes.size) {
        if (fn != null && fn.numbers.isNotEmpty()) {
            val ref = footnoteRefAt(nodes, i, ctx.src)
            val num = ref?.let { fn.numbers[it.label] }
            if (ref != null && num != null) {
                appendFootnoteRef(ref.label, num, ctx, out, inlineContent)
                i += ref.consumed
                continue
            }
        }
        appendInlineNode(nodes[i], ctx, out, inlineContent)
        i++
    }
}

/** TEXT token 内兜底扫描 `[^id]`（解析器将整段吞进单个 TEXT 时仍可识别） */
private fun appendTextWithFootnotes(
    text: String,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    val fn = ctx.footnotes ?: run { out.append(text); return }
    var last = 0
    for (m in footnoteInlineRegex.findAll(text)) {
        val num = fn.numbers[m.groupValues[1].lowercase()] ?: continue
        if (m.range.first > last) out.append(text.substring(last, m.range.first))
        appendFootnoteRef(m.groupValues[1].lowercase(), num, ctx, out, inlineContent)
        last = m.range.last + 1
    }
    if (last < text.length) out.append(text.substring(last))
}

private fun appendInlineNode(
    node: ASTNode,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    when (node.type) {
        MarkdownTokenTypes.URL -> out.append(node.nodeText(ctx.src))
        MarkdownTokenTypes.TEXT -> {
            // <kbd> 键帽模式：纯文本走键帽卡片；标签不闭合时不影响后续普通段落（出口复位）
            val text = node.nodeText(ctx.src)
            when {
                ctx.kbdActive.get() == true -> appendKbdSpan(text, ctx, out, inlineContent)
                ctx.footnotes != null && text.contains("[^") ->
                    appendTextWithFootnotes(text, ctx, out, inlineContent)
                else -> out.append(text)
            }
        }
        MarkdownTokenTypes.WHITE_SPACE -> out.append(node.nodeText(ctx.src))
        MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> out.append('\n')
        MarkdownTokenTypes.HTML_TAG -> {
            // 仅识别 <kbd> 键帽；其余内联 HTML 标签一律吞掉，尖括号不泄露到正文
            when (node.nodeText(ctx.src).trim().lowercase()) {
                "<kbd>" -> ctx.kbdActive.set(true)
                "</kbd>" -> ctx.kbdActive.set(false)
                else -> {}
            }
        }

        // 列表标记 / 任务勾选框 / 强调定界符 token：行内渲染时跳过
        // （列表标记由列表层绘制；EMPH 定界符 * 由 EMPH/STRONG 层统一加样式，不能当普通文本输出）
        MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER, GFMTokenTypes.CHECK_BOX,
        MarkdownTokenTypes.EMPH -> {}

        MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic), out) {
            appendInlineSequence(node.children, ctx, out, inlineContent)
        }
        MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.Bold), out) {
            appendInlineSequence(node.children, ctx, out, inlineContent)
        }
        GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), out) {
            appendInlineSequence(node.children, ctx, out, inlineContent)
        }

        MarkdownElementTypes.CODE_SPAN -> appendCodeSpan(node, ctx, out, inlineContent)

        MarkdownElementTypes.INLINE_LINK -> {
            val text = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            val dest = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                ?.nodeText(ctx.src)?.trim().orEmpty()
            appendLink(text, dest, node, ctx, out, inlineContent)
        }
        MarkdownElementTypes.FULL_REFERENCE_LINK -> {
            val text = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                ?.nodeText(ctx.src)?.removeSurrounding("[", "]")?.trim()?.lowercase().orEmpty()
            val dest = ctx.refLinks[label]?.first
            appendLink(text, dest, node, ctx, out, inlineContent)
        }
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                ?.nodeText(ctx.src)?.removeSurrounding("[", "]")?.trim()?.lowercase().orEmpty()
            val dest = ctx.refLinks[label]?.first
            appendLink(null, dest, node, ctx, out, inlineContent)
        }
        MarkdownElementTypes.AUTOLINK -> {
            val raw = node.nodeText(ctx.src).removeSurrounding("<", ">")
            appendPlainLink(raw, raw, ctx, out)
        }
        GFMTokenTypes.GFM_AUTOLINK -> {
            val raw = node.nodeText(ctx.src)
            appendPlainLink(raw, raw, ctx, out)
        }

        MarkdownElementTypes.IMAGE -> {
            // 聊天气泡内不内嵌渲染 md 图片：alt 文本胶囊徽章（Image 图标 + 名），仅样式
            val link = node.children.firstOrNull {
                it.type == MarkdownElementTypes.INLINE_LINK
            } ?: node
            val alt = link.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                ?.nodeText(ctx.src)?.removeSurrounding("[", "]").orEmpty()
            val label = if (alt.isBlank()) "图片" else alt
            val id = "img_${inlineContent.size}"
            // 徽章宽度：label 实测 + 固定开销 28dp（图标13 + 间距3 + 左右内边距6×2）→ 单侧 padHPx=14dp
            val wSp = ctx.measureInlineWidthSp(
                label, fontSize = 12,
                padHPx = with(ctx.density) { 14.dp.toPx() }
            )
            inlineContent.add(id to InlineTextContent(
                Placeholder(
                    width = wSp.sp,
                    height = 20.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(ctx.palette.inlineCodeBg)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = ctx.palette.dim,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = ctx.palette.dim,
                        maxLines = 1
                    )
                }
            })
            out.appendInlineContent(id, label)
        }

        GFMElementTypes.INLINE_MATH -> {
            val tex = node.mathContent(ctx.src)
            if (tex.isNotBlank() && looksLikeLatex(tex)) {
                val bmp = renderLatexBitmap(
                    tex, ctx.textSizePx,
                    android.graphics.Color.argb(
                        (ctx.textColor.alpha * 255).toInt(),
                        (ctx.textColor.red * 255).toInt(),
                        (ctx.textColor.green * 255).toInt(),
                        (ctx.textColor.blue * 255).toInt()
                    )
                )
                if (bmp != null) {
                    val id = "math_${inlineContent.size}"
                    // px → dp → sp（占位尺寸单位是 sp，需补偿字体缩放）
                    val wSp = with(ctx.density) { bmp.width.toDp().value / fontScale }
                    val hSp = with(ctx.density) { bmp.height.toDp().value / fontScale }
                    inlineContent.add(id to InlineTextContent(
                        Placeholder(
                            width = wSp.sp,
                            height = hSp.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                        )
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = tex
                        )
                    })
                    out.appendInlineContent(id, tex)
                } else {
                    out.append(node.nodeText(ctx.src)) // 解析失败原文兜底
                }
            } else {
                out.append(node.nodeText(ctx.src)) // 货币等误判场景：原文
            }
        }

        else -> {
            // 复合节点（LINK_TEXT 等）递归；叶子兜底原文
            if (node.children.isEmpty()) out.append(node.nodeText(ctx.src))
            else appendInlineSequence(node.children, ctx, out, inlineContent)
        }
    }
}

private inline fun withStyle(
    style: SpanStyle,
    out: AnnotatedString.Builder,
    crossinline block: AnnotatedString.Builder.() -> Unit
) {
    out.pushStyle(style)
    out.block()
    out.pop()
}

/** 行内链接 / 引用链接：标签走递归行内渲染（标签内可含粗体等），整体挂 link 注解 */
private fun appendLink(
    linkText: ASTNode?,
    dest: String?,
    rawNode: ASTNode,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    if (dest.isNullOrBlank()) {
        // 未解析到目标：按原文渲染
        out.append(rawNode.nodeText(ctx.src))
        return
    }
    val start = out.length
    // 链接仅靠颜色表达（无下划线），与 DeepSeek / GitHub 现代风格一致
    out.pushStyle(SpanStyle(color = ctx.linkColor))
    if (linkText != null) {
        // LINK_TEXT 节点文本含 []，其子节点首尾为 LBRACKET/RBRACKET，跳过
        linkText.children.forEach { c ->
            if (c.type != MarkdownTokenTypes.LBRACKET && c.type != MarkdownTokenTypes.RBRACKET) {
                appendInlineNode(c, ctx, out, inlineContent)
            }
        }
    } else {
        // 短引用链接：标签即文本
        val label = rawNode.nodeText(ctx.src).removeSurrounding("[", "]")
        out.append(label)
    }
    out.pop()
    if (out.length > start) {
        out.addStringAnnotation(tag = "link", annotation = dest, start = start, end = out.length)
    }
}

private fun appendPlainLink(text: String, dest: String, ctx: MdRenderContext, out: AnnotatedString.Builder) {
    val start = out.length
    out.pushStyle(SpanStyle(color = ctx.linkColor))
    out.append(text)
    out.pop()
    out.addStringAnnotation(tag = "link", annotation = dest, start = start, end = out.length)
}

/** <kbd> 键帽：小型凸起卡片（InlineTextContent 实测宽度），表达快捷键/按键 */
private fun appendKbdSpan(
    text: String,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    if (text.isEmpty()) return
    val id = "kbd_${inlineContent.size}"
    val padHPx = with(ctx.density) { 7.dp.toPx() }
    val wSp = ctx.measureInlineWidthSp(text, fontSize = 12, padHPx = padHPx)
    inlineContent.add(id to InlineTextContent(
        Placeholder(
            width = wSp.sp,
            height = 20.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
        )
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(ctx.palette.codeHeaderBg)
                .padding(horizontal = 7.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = ctx.palette.text,
                maxLines = 1
            )
        }
    })
    out.appendInlineContent(id, text)
}

/** CODE_SPAN：剥 backtick（CommonMark 规则：内容两侧各一个空格且非全空格时剥掉） */
internal fun codeSpanText(node: ASTNode, src: String): String {
    var t = node.nodeText(src)
    val ticks = t.takeWhile { it == '`' }.length.coerceAtLeast(1)
    t = t.removePrefix("`".repeat(ticks)).removeSuffix("`".repeat(ticks))
    if (t.length >= 2 && t.first() == ' ' && t.last() == ' ' && t.any { it != ' ' }) {
        t = t.substring(1, t.length - 1)
    }
    return t
}

private fun appendCodeSpan(
    node: ASTNode,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    val code = codeSpanText(node, ctx.src)
    if (code.isEmpty()) return
    if (code.length <= 24) {
        // 圆角药丸：InlineTextContent 内嵌 Surface，宽度 TextMeasurer 实测（中文不截断）
        val id = "code_${inlineContent.size}"
        val bg = ctx.palette.inlineCodeBg
        val padHPx = with(ctx.density) { MdSpace.inlineCodePadH.toPx() }
        val wSp = ctx.measureInlineWidthSp(code, fontSize = 13, padHPx = padHPx)
        inlineContent.add(id to InlineTextContent(
            Placeholder(
                width = wSp.sp,
                height = 20.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
            )
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = ctx.palette.inlineCodeText,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
            )
        })
        out.appendInlineContent(id, code)
    } else {
        // 长代码退化：等宽 + 底色（矩形）
        out.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
        out.append(code)
        out.pop()
    }
}

/**
 * 行内文本渲染出口（单节点）：裁首尾空白后委托 [MdInlineTextList]。
 * cacheKey 用节点身份（ASTNode 稳定），避免 subList 新列表导致后台构建反复重算。
 */
@Composable
internal fun MdInlineText(
    node: ASTNode,
    ctx: MdRenderContext,
    style: androidx.compose.ui.text.TextStyle = mdBodyStyle
) {
    val kids = node.children
    var start = 0
    var end = kids.size
    while (start < end && kids[start].type == MarkdownTokenTypes.WHITE_SPACE) start++
    while (end > start && kids[end - 1].type == MarkdownTokenTypes.WHITE_SPACE) end--
    val trimmed = if (start == 0 && end == kids.size) kids else kids.subList(start, end)
    MdInlineTextList(trimmed, ctx, style, cacheKey = node)
}

/**
 * 行内文本渲染出口（多节点，紧凑列表 / Alert 首段使用）：
 * 富产物（AnnotatedString + InlineTextContent，含行内 LaTeX 位图）在 Dispatchers.Default
 * 后台构建，按 (cacheKey/palette/src) 缓存；未就绪时同版式纯文本兜底（TR-2.1/TR-2.2）。
 * 手势桥接与 AutoLinkText 同款（detectTapGestures 会吞按压事件，
 * 长按/点击必须在此检测并主动上抛；selectable 自由复制模式下不挂手势，让位选区手柄）。
 *
 * @param cacheKey 稳定身份（ASTNode 身份）；默认取 nodes 自身，但 filter/subList 产物应显式传入源节点
 */
@Composable
internal fun MdInlineTextList(
    nodes: List<ASTNode>,
    ctx: MdRenderContext,
    style: androidx.compose.ui.text.TextStyle = mdBodyStyle,
    cacheKey: Any? = nodes
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var layoutResult by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
    }
    // 回调可能随重组变化，pointerInput 不重启——用稳定引用持有最新值
    val latestLongClick = androidx.compose.runtime.rememberUpdatedState(ctx.onLongClick)
    val latestTextClick = androidx.compose.runtime.rememberUpdatedState(ctx.onTextClick)

    // 后台构建，未就绪返回 null
    val built = rememberInlineBuild(nodes, ctx, cacheKey)
    val annotated = built?.first
    val inlineContent = built?.second ?: emptyMap()

    if (annotated == null) {
        // 兜底：同版式纯文本（解析极快，通常不足一帧即切换），无手势交互
        Text(
            text = plainTextOf(nodes, ctx.src),
            style = style.copy(color = ctx.textColor)
        )
        return
    }

    Text(
        text = annotated,
        style = style.copy(color = ctx.textColor),
        inlineContent = inlineContent,
        onTextLayout = { layoutResult = it },
        modifier = if (ctx.selectable) Modifier else Modifier.pointerInput(annotated) {
            detectTapGestures(
                onLongPress = { latestLongClick.value?.invoke() },
                onTap = { pos ->
                    val offset = layoutResult?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    val ann = annotated
                        .getStringAnnotations(tag = "link", start = offset, end = offset)
                        .firstOrNull()
                    if (ann == null) {
                        // 非链接区域点击上抛（编辑重发 / 退出自由复制）
                        latestTextClick.value?.invoke()
                        return@detectTapGestures
                    }
                    val url = ann.item
                    when {
                        url.startsWith("mailto:") -> runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse(url)
                                })
                        }
                        url.startsWith("tel:") -> runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                                    data = android.net.Uri.parse(url)
                                })
                        }
                        else -> runCatching { uriHandler.openUri(url) }
                    }
                }
            )
        }
    )
}
