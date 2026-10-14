package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 块级渲染：AST 顶层块 → Compose 组件树。
 * 标题层级 + 底部分隔线 / 引用块圆角卡片 + 品牌色左竖条 / 列表缩进 / 任务列表勾选图标。
 */

/** 段落正文样式：15sp、1.45 比例行高（长文阅读不局促） */
internal val bodyTextStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = (15 * 1.45f).sp
    )

private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    2 -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
    3 -> TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
    4 -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 23.sp)
    5 -> TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
    else -> TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
}

/** 标题文字内容：ATX 取 ATX_CONTENT，SETEXT 取 SETEXT_CONTENT */
private fun ASTNode.headingContent(): ASTNode? = children.firstOrNull {
    it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
}

@Composable
internal fun MdBlockChildren(parent: ASTNode, ctx: MdRenderContext) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        MarkdownElementTypes.BLOCK_QUOTE -> MdBlockQuote(node, ctx)

        MarkdownElementTypes.UNORDERED_LIST -> MdListBlock(node, ctx, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> MdListBlock(node, ctx, ordered = true)

        MarkdownElementTypes.CODE_FENCE -> {
            val lang = node.fenceLang(ctx.src)
            val code = node.fenceCode(ctx.src)
            if (lang.equals("mermaid", ignoreCase = true)) {
                MdMermaidBlock(code, ctx.textColor)
            } else {
                MdCodeBlock(lang, code, ctx.textColor)
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
            MdCodeBlock("", code, ctx.textColor)
        }

        GFMElementTypes.TABLE -> node.tableModel()?.let { MdTableBlock(it, ctx) }

        MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalDivider(
            color = ctx.textColor.copy(alpha = 0.16f),
            thickness = 0.8.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // 链接定义块不渲染（仅作为引用链接的解析表）
        MarkdownElementTypes.LINK_DEFINITION -> {}
        // HTML 块降级为原文（不支持内嵌 HTML）
        MarkdownElementTypes.HTML_BLOCK -> MdPlainText(node.nodeText(ctx.src).trim(), ctx)

        else -> {
            // 未知块级节点：容器递归，叶子原文兜底
            if (node is org.intellij.markdown.ast.CompositeASTNode) MdBlockChildren(node, ctx)
            else MdPlainText(node.nodeText(ctx.src).trim(), ctx)
        }
    }
}

@Composable
private fun MdPlainText(text: String, ctx: MdRenderContext) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = bodyTextStyle,
        color = ctx.textColor
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
            MdLatexBlock(tex, ctx.textColor)
            return
        }
    }
    MdInlineText(node, ctx)
}

@Composable
private fun MdHeading(node: ASTNode, ctx: MdRenderContext, level: Int) {
    val content = node.headingContent() ?: return
    Column {
        MdInlineText(content, ctx, style = headingStyle(level))
        if (level <= 2) {
            HorizontalDivider(
                color = ctx.textColor.copy(alpha = 0.12f),
                thickness = 0.8.dp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 引用块：圆角卡片 + 品牌色左竖条（IntrinsicSize.Min 使竖条与内容等高），内部块递归 */
@Composable
private fun MdBlockQuote(node: ASTNode, ctx: MdRenderContext) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(ctx.textColor.copy(alpha = 0.05f))
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(ctx.linkColor)
        )
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            node.children.forEach { MdBlock(it, ctx) }
        }
    }
}

/** 列表：有序序号 / 无序圆点 / 任务勾选框；子列表递归缩进 */
@Composable
private fun MdListBlock(node: ASTNode, ctx: MdRenderContext, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var index = 0
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { item ->
            index++
            MdListItem(item, ctx, ordered, index)
        }
    }
}

@Composable
private fun MdListItem(item: ASTNode, ctx: MdRenderContext, ordered: Boolean, index: Int) {
    val checked = item.taskChecked(ctx.src)
    Row {
        // 标记列：固定宽度对齐
        Box(
            modifier = Modifier
                .width(22.dp)
                .padding(top = 1.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                checked != null -> Icon(
                    imageVector = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = if (checked) "已完成" else "未完成",
                    tint = if (checked) ctx.linkColor else ctx.textColor.copy(alpha = 0.5f),
                    modifier = Modifier.width(16.dp).height(16.dp)
                )
                ordered -> Text(
                    text = "$index.",
                    style = bodyTextStyle,
                    color = ctx.textColor
                )
                else -> Text(
                    text = "•",
                    style = bodyTextStyle,
                    color = ctx.linkColor
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // LIST_ITEM 的内容：宽松列表为 PARAGRAPH/嵌套列表等块级节点（走 MdBlock），
            // 紧凑列表为直接行内 token（走 MdInlineText 整体渲染，标记 token 已被行内层跳过）
            val blocks = item.children.filter {
                it.type != GFMTokenTypes.CHECK_BOX &&
                    it.type != MarkdownTokenTypes.LIST_BULLET &&
                    it.type != MarkdownTokenTypes.LIST_NUMBER &&
                    it.type != MarkdownTokenTypes.WHITE_SPACE &&
                    it.type != MarkdownTokenTypes.EOL
            }
            val hasComposite = blocks.any { it is org.intellij.markdown.ast.CompositeASTNode }
            if (hasComposite) {
                blocks.forEach { b -> MdBlock(b, ctx) }
            } else {
                MdInlineText(item, ctx)
            }
        }
    }
}
