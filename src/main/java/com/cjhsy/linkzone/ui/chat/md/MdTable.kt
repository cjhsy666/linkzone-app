package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/**
 * 支持列对齐的 GFM 表格（库默认表格一律左对齐）。
 * 对齐信息从分隔行（`:---` 左 / `:---:` 中 / `---:` 右）解析，
 * 结构沿用库默认实现：圆角背景 + 超宽横向滚动；单元格行内样式走库 annotator 管线。
 */
@Composable
fun MdAlignedTable(content: String, node: ASTNode, style: TextStyle) {
    val tableMaxWidth = LocalMarkdownDimens.current.tableMaxWidth
    val tableCellWidth = LocalMarkdownDimens.current.tableCellWidth
    val tableCornerSize = LocalMarkdownDimens.current.tableCornerSize
    val tableCellPadding = LocalMarkdownDimens.current.tableCellPadding

    val columnsCount = remember(node) {
        node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
    }
    val tableWidth = columnsCount * tableCellWidth
    val aligns = remember(node) { parseColumnAligns(content, node, columnsCount) }

    BoxWithConstraints(
        modifier = Modifier
            .background(LocalMarkdownColors.current.tableBackground, RoundedCornerShape(tableCornerSize))
            .widthIn(max = tableMaxWidth)
    ) {
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = if (scrollable) {
                Modifier.horizontalScroll(rememberScrollState()).requiredWidth(tableWidth)
            } else Modifier.fillMaxWidth()
        ) {
            node.children.forEach { child ->
                when (child.type) {
                    HEADER -> MdTableRow(content, child, style.copy(fontWeight = FontWeight.Bold), tableWidth, tableCellPadding, aligns)
                    ROW -> MdTableRow(content, child, style, tableWidth, tableCellPadding, aligns)
                    TABLE_SEPARATOR -> MarkdownDivider()
                }
            }
        }
    }
}

@Composable
private fun MdTableRow(
    content: String,
    row: ASTNode,
    style: TextStyle,
    tableWidth: Dp,
    cellPadding: Dp,
    aligns: List<Alignment.Horizontal>,
) {
    Row(modifier = Modifier.widthIn(tableWidth)) {
        row.children.filter { it.type == CELL }.forEachIndexed { colIndex, cell ->
            Column(
                horizontalAlignment = aligns.getOrElse(colIndex) { Alignment.Start },
                modifier = Modifier
                    .weight(1f)
                    .padding(cellPadding),
            ) {
                MarkdownTableBasicText(content = content, cell = cell, style = style)
            }
        }
    }
}

/** 从 TABLE_SEPARATOR 节点文本解析每列对齐；缺省左对齐 */
private fun parseColumnAligns(content: String, node: ASTNode, columns: Int): List<Alignment.Horizontal> {
    val sep = node.children.firstOrNull { it.type == TABLE_SEPARATOR }
        ?: return List(columns) { Alignment.Start }
    val parts = content.substring(sep.startOffset, sep.endOffset)
        .split('|').map { it.trim() }.filter { it.isNotEmpty() }
    return List(columns) { i ->
        val p = parts.getOrNull(i) ?: ""
        when {
            p.length >= 3 && p.startsWith(":") && p.endsWith(":") -> Alignment.CenterHorizontally
            p.endsWith(":") -> Alignment.End
            else -> Alignment.Start
        }
    }
}
