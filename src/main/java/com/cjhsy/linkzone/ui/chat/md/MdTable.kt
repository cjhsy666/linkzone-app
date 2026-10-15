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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/**
 * 表格：表头带底色、行间细分隔线、支持列对齐（库默认表格全左对齐且无表头底色）。
 * 对齐从分隔行解析（`:---` 左 / `:---:` 中 / `---:` 右）；超宽时整表横向滚动。
 */
@Composable
fun MdAlignedTable(content: String, node: ASTNode, style: TextStyle) {
    val tableMaxWidth = LocalMarkdownDimens.current.tableMaxWidth
    val tableCellWidth = LocalMarkdownDimens.current.tableCellWidth
    val tableCornerSize = LocalMarkdownDimens.current.tableCornerSize
    val tableCellPadding = LocalMarkdownDimens.current.tableCellPadding
    val textColor = LocalMarkdownColors.current.text
    val tableBg = LocalMarkdownColors.current.tableBackground

    val columnsCount = remember(node) {
        node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
    }
    val tableWidth = columnsCount * tableCellWidth
    val aligns = remember(node) { parseColumnAligns(content, node, columnsCount) }
    val bodyRows = remember(node) { node.children.filter { it.type == ROW } }

    BoxWithConstraints(
        modifier = Modifier
            .clip(RoundedCornerShape(tableCornerSize))
            .background(tableBg)
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
                    HEADER -> {
                        MdTableRow(
                            content = content,
                            row = child,
                            style = style.copy(fontWeight = FontWeight.Bold),
                            background = textColor.copy(alpha = 0.06f),
                            topCorner = tableCornerSize,
                            tableWidth = tableWidth,
                            cellPadding = tableCellPadding,
                            aligns = aligns,
                        )
                        // 表头下分隔线更明显，区分表头与数据区
                        HorizontalDivider(color = textColor.copy(alpha = 0.12f), thickness = 0.8.dp)
                    }

                    ROW -> {
                        MdTableRow(
                            content = content,
                            row = child,
                            style = style,
                            background = Color.Transparent,
                            topCorner = 0.dp,
                            tableWidth = tableWidth,
                            cellPadding = tableCellPadding,
                            aligns = aligns,
                        )
                        if (child !== bodyRows.lastOrNull()) {
                            HorizontalDivider(color = textColor.copy(alpha = 0.08f), thickness = 0.6.dp)
                        }
                    }

                    TABLE_SEPARATOR -> Unit // 由自家行分隔线替代库默认分割线
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
    background: Color,
    topCorner: Dp,
    tableWidth: Dp,
    cellPadding: Dp,
    aligns: List<Alignment.Horizontal>,
) {
    Row(
        modifier = Modifier
            .widthIn(tableWidth)
            .clip(RoundedCornerShape(topStart = topCorner, topEnd = topCorner))
            .background(background)
    ) {
        row.children.filter { it.type == CELL }.forEachIndexed { colIndex, cell ->
            Column(
                horizontalAlignment = aligns.getOrElse(colIndex) { Alignment.Start },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = cellPadding, vertical = 9.dp),
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
