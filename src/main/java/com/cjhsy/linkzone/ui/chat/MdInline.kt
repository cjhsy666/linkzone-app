package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
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

/** 行内渲染上下文（块级渲染共用） */
internal class MdRenderContext(
    val src: String,
    val textColor: androidx.compose.ui.graphics.Color,
    val linkColor: androidx.compose.ui.graphics.Color,
    val selectable: Boolean,
    val onLongClick: (() -> Unit)?,
    val onTextClick: (() -> Unit)?,
    val refLinks: Map<String, Pair<String, String?>>,
    val density: Density
) {
    val textSizePx: Float = with(density) { 15.sp.toPx() }
}

/** 行内代码药丸宽度估算：等宽字体字宽 ≈ 0.6em，加上药丸水平内边距 */
private fun inlineCodeWidthSp(len: Int): Float = len * 13f * 0.6f + 24f

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
    for (i in start until end) {
        appendInlineNode(kids[i], ctx, out, inlineContent)
    }
}

private fun appendInlineNode(
    node: ASTNode,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    when (node.type) {
        MarkdownTokenTypes.TEXT, MarkdownTokenTypes.URL -> out.append(node.nodeText(ctx.src))
        MarkdownTokenTypes.WHITE_SPACE -> out.append(node.nodeText(ctx.src))
        MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> out.append('\n')
        MarkdownTokenTypes.HTML_TAG -> out.append(node.nodeText(ctx.src)) // 内联 HTML 降级原文

        // 列表标记 / 任务勾选框 / 强调定界符 token：行内渲染时跳过
        // （列表标记由列表层绘制；EMPH 定界符 * 由 EMPH/STRONG 层统一加样式，不能当普通文本输出）
        MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER, GFMTokenTypes.CHECK_BOX,
        MarkdownTokenTypes.EMPH -> {}

        MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic), out) {
            node.children.forEach { appendInlineNode(it, ctx, out, inlineContent) }
        }
        MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.Bold), out) {
            node.children.forEach { appendInlineNode(it, ctx, out, inlineContent) }
        }
        GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), out) {
            node.children.forEach { appendInlineNode(it, ctx, out, inlineContent) }
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
            // 聊天气泡内不内嵌渲染 md 图片，显示 alt 文本占位
            val link = node.children.firstOrNull {
                it.type == MarkdownElementTypes.INLINE_LINK
            } ?: node
            val alt = link.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                ?.nodeText(ctx.src)?.removeSurrounding("[", "]").orEmpty()
            withStyle(SpanStyle(color = ctx.textColor.copy(alpha = 0.65f)), out) {
                append(if (alt.isBlank()) "[图片]" else "[$alt]")
            }
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
            else node.children.forEach { appendInlineNode(it, ctx, out, inlineContent) }
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
    out.pushStyle(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline))
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
    out.pushStyle(SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline))
    out.append(text)
    out.pop()
    out.addStringAnnotation(tag = "link", annotation = dest, start = start, end = out.length)
}

/** CODE_SPAN：剥 backtick（CommonMark 规则：内容两侧各一个空格且非全空格时剥掉） */
private fun codeSpanText(node: ASTNode, src: String): String {
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
        // 圆角药丸：InlineTextContent 内嵌 Surface
        val id = "code_${inlineContent.size}"
        val bg = ctx.linkColor.copy(alpha = 0.12f)
        inlineContent.add(id to InlineTextContent(
            Placeholder(
                width = inlineCodeWidthSp(code.length).sp,
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
                color = ctx.textColor,
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
 * 行内文本渲染出口：buildInlineString 构建 AnnotatedString + inlineContent，
 * 挂到 Compose Text；手势桥接与 AutoLinkText 同款（detectTapGestures 会吞按压事件，
 * 长按/点击必须在此检测并主动上抛；selectable 自由复制模式下不挂手势，让位选区手柄）。
 */
@Composable
internal fun MdInlineText(
    node: ASTNode,
    ctx: MdRenderContext,
    style: androidx.compose.ui.text.TextStyle = bodyTextStyle
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var layoutResult by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
    }
    // 回调可能随重组变化，pointerInput 不重启——用稳定引用持有最新值
    val latestLongClick = androidx.compose.runtime.rememberUpdatedState(ctx.onLongClick)
    val latestTextClick = androidx.compose.runtime.rememberUpdatedState(ctx.onTextClick)

    // 解析 + 构建仅在内容变化时执行（remember 按 src 缓存，历史气泡不重复解析）
    val built = androidx.compose.runtime.remember(node, ctx.src, ctx.textColor, ctx.linkColor) {
        val inlineContent = mutableListOf<Pair<String, InlineTextContent>>()
        val annotated = androidx.compose.ui.text.buildAnnotatedString {
            // 段落内联公式渲染需要 contentDescription 等，直接在构建期完成
            buildInlineString(node, ctx, this, inlineContent)
        }
        annotated to inlineContent.toMap()
    }
    val (annotated, inlineContent) = built

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
