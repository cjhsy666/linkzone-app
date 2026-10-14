package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 块级渲染：AST 顶层块 → Compose 组件树。
 * 标题层级 / 引用与 GFM Alerts / 多级列表（真实序号）/ 任务列表 / 代码块 / 表格 / 分割线。
 */

/**
 * TOC 预留（Task 3 / Task 10）：一条消息内的标题锚点收集器。
 * - [entries]：文档顺序标题条目（h1–h6），AST 变化时刷新（流式中随标题增长实时更新）；
 * - [coords]：index → 标题节点的 LayoutCoordinates（onGloballyPositioned 持续回填），
 *   TOC 点击定位时由消息根坐标 `localBoundingBoxOf(anchorCoords).top` 计算像素偏移。
 * 传入 null 时渲染层不做任何锚点采集（零开销）。
 */
class MdHeadingAnchors {
    val entries = mutableStateOf<List<MdHeadingEntry>>(emptyList())
    val coords = mutableStateMapOf<Int, LayoutCoordinates>()
}

@Composable
internal fun MdBlockChildren(parent: ASTNode, ctx: MdRenderContext) {
    Column(verticalArrangement = Arrangement.spacedBy(MdSpace.blockGap)) {
        parent.children.forEach { child ->
            MdBlock(child, ctx)
        }
    }
}

@Composable
internal fun MdBlock(node: ASTNode, ctx: MdRenderContext) {
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> MdParagraph(node, ctx)

        // 列表 / 引用标记 token：块级渲染跳过（标记由列表层 / 引用层绘制）
        MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER, GFMTokenTypes.CHECK_BOX -> {}

        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val level = node.type.name.last().digitToInt()
            MdHeading(node, ctx, level)
        }
        MarkdownElementTypes.SETEXT_1 -> MdHeading(node, ctx, 1)
        MarkdownElementTypes.SETEXT_2 -> MdHeading(node, ctx, 2)

        MarkdownElementTypes.BLOCK_QUOTE -> MdQuoteOrAlert(node, ctx)

        MarkdownElementTypes.UNORDERED_LIST -> MdListBlock(node, ctx, ordered = false, depth = 0)
        MarkdownElementTypes.ORDERED_LIST -> MdListBlock(node, ctx, ordered = true, depth = 0)

        MarkdownElementTypes.CODE_FENCE -> {
            val lang = node.fenceLang(ctx.src)
            val code = node.fenceCode(ctx.src)
            if (lang.equals("mermaid", ignoreCase = true)) {
                MdMermaidBlock(code, ctx)
            } else {
                MdCodeBlock(lang, code, ctx)
            }
        }
        // 缩进代码块（CODE_LINE 序列）：按无语言代码块渲染
        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.children
                .filter { it.type == MarkdownTokenTypes.CODE_LINE || it.type == MarkdownTokenTypes.EOL }
                .joinToString("") {
                    if (it.type == MarkdownTokenTypes.EOL) "\n" else it.nodeText(ctx.src).removePrefix("    ")
                }
                .trimEnd('\n')
            MdCodeBlock("", code, ctx)
        }

        GFMElementTypes.TABLE -> node.tableModel(ctx.src)?.let { MdTableBlock(it, ctx) }

        MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalDivider(
            color = ctx.palette.divider,
            thickness = 0.8.dp,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        // 链接定义块不渲染（仅作为引用链接的解析表）
        MarkdownElementTypes.LINK_DEFINITION -> {}
        // HTML 块降级为原文（不支持内嵌 HTML）
        MarkdownElementTypes.HTML_BLOCK -> MdPlainText(node.nodeText(ctx.src).trim(), ctx)

        else -> {
            // 未知块级节点：容器递归，叶子原文兜底
            if (node is CompositeASTNode) MdBlockChildren(node, ctx)
            else MdPlainText(node.nodeText(ctx.src).trim(), ctx)
        }
    }
}

@Composable
private fun MdPlainText(text: String, ctx: MdRenderContext) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = mdBodyStyle,
        color = ctx.palette.text
    )
}

/** 段落：若整段仅含一个 BLOCK_MATH，则按块级公式渲染；否则行内文本 */
@Composable
private fun MdParagraph(node: ASTNode, ctx: MdRenderContext) {
    val meaningful = node.children.filter {
        it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownTokenTypes.EOL
    }
    if (meaningful.size == 1 && meaningful[0].type == GFMElementTypes.BLOCK_MATH) {
        val tex = meaningful[0].mathContent(ctx.src)
        if (tex.isNotBlank()) {
            MdLatexBlock(tex, ctx)
            return
        }
    }
    MdInlineText(node, ctx)
}

@Composable
private fun MdHeading(node: ASTNode, ctx: MdRenderContext, level: Int) {
    val content = node.headingContent() ?: return
    val topPad = when (level) {
        1 -> 10.dp; 2 -> 8.dp; 3 -> 6.dp; else -> 4.dp
    }
    // TOC 预留：有锚点收集器时回填标题节点坐标（相对消息根的偏移由 Task 10 计算）
    val anchors = ctx.headingAnchors
    val anchorIndex = ctx.headingIndex[node]
    val anchorMod = if (anchors != null && anchorIndex != null) {
        Modifier.onGloballyPositioned { coords -> anchors.coords[anchorIndex] = coords }
    } else Modifier
    Column(modifier = anchorMod.padding(top = topPad)) {
        MdInlineText(content, ctx, style = mdHeadingStyle(level))
        if (level <= 2) {
            HorizontalDivider(
                color = ctx.palette.faintDivider,
                thickness = 0.8.dp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/** 标题文字内容：ATX 取 ATX_CONTENT，SETEXT 取 SETEXT_CONTENT */
private fun ASTNode.headingContent(): ASTNode? = children.firstOrNull {
    it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
}

// ---------- 引用块与 GFM Alerts ----------

@Composable
private fun MdQuoteOrAlert(node: ASTNode, ctx: MdRenderContext, depth: Int = 0) {
    val alert = node.alertKind(ctx.src)
    if (alert != null) MdAlertBlock(node, ctx, alert) else MdBlockQuote(node, ctx, depth)
}

/** 识别 GFM Alert：引用首段首个 TEXT 为 [!NOTE]/[!TIP]/[!IMPORTANT]/[!WARNING]/[!CAUTION] */
private fun ASTNode.alertKind(src: String): MdAlertKind? {
    val firstPara = children.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH } ?: return null
    val firstText = firstPara.children
        .firstOrNull { it.type == MarkdownTokenTypes.TEXT }
        ?.nodeText(src)?.trim()?.uppercase() ?: return null
    return when (firstText) {
        "[!NOTE]" -> MdAlertKind.NOTE
        "[!TIP]" -> MdAlertKind.TIP
        "[!IMPORTANT]" -> MdAlertKind.IMPORTANT
        "[!WARNING]" -> MdAlertKind.WARNING
        "[!CAUTION]" -> MdAlertKind.CAUTION
        else -> null
    }
}

@Composable
private fun MdAlertBlock(node: ASTNode, ctx: MdRenderContext, kind: MdAlertKind) {
    val colors = ctx.palette.alert(kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(MdSpace.cardRadius))
            .background(colors.bg)
    ) {
        Box(
            modifier = Modifier
                .width(MdSpace.quoteBar)
                .fillMaxHeight()
                .background(colors.bar)
        )
        Column(
            modifier = Modifier.padding(
                start = 10.dp, end = MdSpace.quotePadH,
                top = MdSpace.quotePadV, bottom = MdSpace.quotePadV
            ),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // 标题行：语义图标 + 中文类型名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (kind) {
                        MdAlertKind.NOTE -> Icons.Filled.Info
                        MdAlertKind.TIP -> Icons.Filled.Lightbulb
                        MdAlertKind.IMPORTANT -> Icons.Filled.PushPin
                        MdAlertKind.WARNING -> Icons.Filled.Warning
                        MdAlertKind.CAUTION -> Icons.Filled.Dangerous
                    },
                    contentDescription = kind.title,
                    tint = colors.fg,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = kind.title,
                    style = mdBodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                    color = colors.fg
                )
            }
            // 内容：首段去掉 [!TAG] 标记，其余块正常渲染
            var markerConsumed = false
            node.children.forEach { child ->
                if (child.type == MarkdownElementTypes.PARAGRAPH && !markerConsumed) {
                    markerConsumed = true
                    val kids = child.children.dropWhile {
                        it.type == MarkdownTokenTypes.WHITE_SPACE
                    }.drop(1) // 去掉 [!TAG] TEXT
                        .dropWhile { it.type == MarkdownTokenTypes.EOL || it.type == MarkdownTokenTypes.WHITE_SPACE }
                    val meaningful = kids.filter {
                        it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownTokenTypes.EOL
                    }
                    if (meaningful.isNotEmpty()) {
                        MdInlineTextList(kids, ctx, cacheKey = child)
                    }
                } else {
                    MdBlock(child, ctx)
                }
            }
        }
    }
}

/** 普通引用块：圆角浅底卡片 + 品牌色左竖条（IntrinsicSize.Min 使竖条与内容等高）
 *  [depth] 为嵌套层级：越深竖条/底色越淡，配合内容起始缩进体现层次（TR-3.3） */
@Composable
private fun MdBlockQuote(node: ASTNode, ctx: MdRenderContext, depth: Int = 0) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(MdSpace.cardRadius))
            .background(ctx.palette.quoteBgAt(depth))
    ) {
        Box(
            modifier = Modifier
                .width(MdSpace.quoteBar)
                .fillMaxHeight()
                .background(ctx.palette.quoteBarAt(depth))
        )
        Column(
            modifier = Modifier.padding(
                start = 10.dp, end = MdSpace.quotePadH,
                top = MdSpace.quotePadV, bottom = MdSpace.quotePadV
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            node.children.forEach { child ->
                // 嵌套引用递归：层级 +1（缩进由父级内边距自然产生，竖条/底色递减）
                if (child.type == MarkdownElementTypes.BLOCK_QUOTE) {
                    MdQuoteOrAlert(child, ctx, depth + 1)
                } else {
                    MdBlock(child, ctx)
                }
            }
        }
    }
}

// ---------- 列表 ----------

private val BULLET_MARKS = listOf("•", "◦", "▪")

/** 列表：有序序号（取源码真实值）/ 无序多级符号 / 任务勾选框；子列表递归缩进 */
@Composable
private fun MdListBlock(node: ASTNode, ctx: MdRenderContext, ordered: Boolean, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var autoIndex = 0
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { item ->
            autoIndex++
            MdListItem(item, ctx, ordered, autoIndex, depth)
        }
    }
}

/** 从 LIST_NUMBER token（形如 "3." / "3)"）提取真实起始序号；失败回退自动序号 */
private fun ASTNode.listOrdinal(src: String, fallback: Int): Int {
    val raw = findChildOfType(MarkdownTokenTypes.LIST_NUMBER)?.nodeText(src)
    val n = raw?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    return n ?: fallback
}

@Composable
private fun MdListItem(item: ASTNode, ctx: MdRenderContext, ordered: Boolean, autoIndex: Int, depth: Int) {
    val checked = item.taskChecked(ctx.src)
    val ordinal = if (ordered) item.listOrdinal(ctx.src, autoIndex) else 0
    Row {
        // 标记列：固定宽度对齐
        Box(
            modifier = Modifier
                .width(22.dp)
                .padding(top = 2.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                checked != null -> Icon(
                    imageVector = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = if (checked) "已完成" else "未完成",
                    tint = if (checked) ctx.palette.checkChecked else ctx.palette.checkUnchecked,
                    modifier = Modifier.size(18.dp)
                )
                ordered -> Text(
                    text = "$ordinal.",
                    style = mdBodyStyle,
                    color = ctx.palette.text,
                    fontSize = 14.sp
                )
                else -> Text(
                    text = BULLET_MARKS[depth.coerceAtMost(BULLET_MARKS.lastIndex)],
                    style = mdBodyStyle,
                    color = ctx.palette.link
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // LIST_ITEM 的内容：宽松列表为 PARAGRAPH/嵌套列表等块级节点（走 MdBlock），
            // 紧凑列表为直接行内 token（走 MdInlineTextList，跳过标记/EOL）
            val blocks = item.children.filter {
                it.type != GFMTokenTypes.CHECK_BOX &&
                    it.type != MarkdownTokenTypes.LIST_BULLET &&
                    it.type != MarkdownTokenTypes.LIST_NUMBER &&
                    it.type != MarkdownTokenTypes.WHITE_SPACE &&
                    it.type != MarkdownTokenTypes.EOL
            }
            val hasComposite = blocks.any { it is CompositeASTNode }
            if (hasComposite) {
                blocks.forEach { b ->
                    // 嵌套列表：深度 +1，保证二级 ◦ / 三级 ▪ 与递归缩进
                    when (b.type) {
                        MarkdownElementTypes.UNORDERED_LIST ->
                            MdListBlock(b, ctx, ordered = false, depth = depth + 1)
                        MarkdownElementTypes.ORDERED_LIST ->
                            MdListBlock(b, ctx, ordered = true, depth = depth + 1)
                        else -> MdBlock(b, ctx)
                    }
                }
            } else {
                // 紧凑列表：标记/EOL/空白全部不参与行内渲染（修复尾部多余空行）
                val inlineKids = item.children.filter {
                    it.type != GFMTokenTypes.CHECK_BOX &&
                        it.type != MarkdownTokenTypes.LIST_BULLET &&
                        it.type != MarkdownTokenTypes.LIST_NUMBER &&
                        it.type != MarkdownTokenTypes.EOL &&
                        it.type != MarkdownTokenTypes.WHITE_SPACE
                }
                if (inlineKids.isNotEmpty()) MdInlineTextList(inlineKids, ctx, cacheKey = item)
            }
        }
    }
}
