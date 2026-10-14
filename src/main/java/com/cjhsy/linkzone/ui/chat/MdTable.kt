package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.unit.dp
import org.intellij.markdown.ast.ASTNode

/**
 * GFM 表格：AST 提取的 CELL 节点内容走行内渲染（粗体/行内代码/链接可用），
 * 卡片样式沿用原实现（圆角边框、表头浅底色分层、横向滚动、单元格最小宽 104dp）。
 */

@Composable
internal fun MdTableBlock(table: MdTable, ctx: MdRenderContext) {
    val divider = ctx.textColor.copy(alpha = 0.2f)
    val cellPad = 10.dp
    val cols = table.header.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, divider, RoundedCornerShape(10.dp))
    ) {
        // 表头（浅底色区分层级）
        Row(modifier = Modifier.background(ctx.textColor.copy(alpha = 0.05f))) {
            table.header.forEach { cell ->
                MdTableCell(cell, ctx, bold = true, cellPad = cellPad)
            }
        }
        HorizontalDivider(color = divider, thickness = 1.dp)
        // 数据行
        table.rows.forEachIndexed { ri, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 缺列补空（畸形表格容错）
                for (ci in 0 until cols) {
                    val cell = row.getOrNull(ci)
                    if (cell != null) MdTableCell(cell, ctx, bold = false, cellPad = cellPad)
                    else MdEmptyCell(cellPad)
                }
            }
            if (ri < table.rows.lastIndex) {
                HorizontalDivider(color = divider.copy(alpha = 0.6f), thickness = 0.7.dp)
            }
        }
    }
}

@Composable
private fun MdTableCell(cell: ASTNode, ctx: MdRenderContext, bold: Boolean, cellPad: androidx.compose.ui.unit.Dp) {
    val built = androidx.compose.runtime.remember(cell, ctx.src, ctx.textColor, ctx.linkColor) {
        val inline = mutableListOf<Pair<String, InlineTextContent>>()
        val text = buildAnnotatedString {
            buildInlineString(cell, ctx, this, inline)
            if (bold) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, length)
        }
        text to inline.toMap()
    }
    androidx.compose.material3.Text(
        text = built.first,
        inlineContent = built.second,
        style = MaterialTheme.typography.bodySmall,
        color = ctx.textColor,
        modifier = Modifier
            .widthIn(min = 104.dp)
            .padding(horizontal = cellPad, vertical = if (bold) 8.dp else 7.dp)
    )
}

@Composable
private fun MdEmptyCell(cellPad: androidx.compose.ui.unit.Dp) {
    androidx.compose.material3.Text(
        text = "",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .widthIn(min = 104.dp)
            .padding(horizontal = cellPad, vertical = 7.dp)
    )
}
