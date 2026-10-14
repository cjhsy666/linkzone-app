package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode

/**
 * GFM 表格：CELL 节点内容走行内渲染（粗体/行内代码/链接可用）。
 * - 分隔行列对齐（:--- / :---: / ---:）
 * - 卡片化：圆角边框 + 表头底色分层 + 行分隔线，横向可滚动（窄列 80dp 起，允许内部撑开）
 * - 点击卡片进入全屏 Dialog（宽表/长表舒适阅读），Dialog 内双轴滚动
 */

private val MIN_CELL_WIDTH = 80.dp
private val CELL_PAD_H = MdSpace.tablePadH

@Composable
internal fun MdTableBlock(table: MdTable, ctx: MdRenderContext) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }  // 旋转后保留全屏态（AC-17）
    val p = ctx.palette

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // 整表（表头+数据行）一起横向滚动，保证列宽对齐；放大按钮固定在末尾
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                // 卡片外观 + 单元格网格（点击非链接区进入全屏，TR-6.2）
                TableGrid(table, ctx, onCellTap = { fullscreen = true })
            }
            // 放大入口（不随表横向滚动）
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { fullscreen = true }
                    .padding(5.dp)
            ) {
                Icon(
                    Icons.Filled.OpenInFull,
                    contentDescription = "全屏查看表格",
                    tint = p.dim,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    if (fullscreen) {
        MdTableFullscreen(table, ctx) { fullscreen = false }
    }
}

@Composable
private fun MdTableFullscreen(table: MdTable, ctx: MdRenderContext, onDismiss: () -> Unit) {
    val p = ctx.palette
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(0.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "表格",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = p.text
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(6.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = p.dim)
                    }
                }
                // 双轴滚动：横向宽表 + 纵向长表
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        TableGrid(table, ctx, onCellTap = {})
                    }
                }
            }
        }
    }
}

/** 表头 + 数据行网格（内嵌卡片与全屏共用）；点击单元格非链接区触发 [onCellTap] */
@Composable
private fun TableGrid(table: MdTable, ctx: MdRenderContext, onCellTap: () -> Unit) {
    val p = ctx.palette
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(MdSpace.cardRadius))
            .border(1.dp, p.tableBorder, RoundedCornerShape(MdSpace.cardRadius))
            .background(p.tableSurface)
    ) {
        Row(modifier = Modifier.background(p.tableHeaderBg)) {
            table.header.forEachIndexed { i, cell ->
                MdTableCell(
                    cell, ctx, bold = true,
                    align = table.alignments.getOrNull(i) ?: MdColumnAlign.LEFT,
                    onCellTap = onCellTap
                )
            }
        }
        HorizontalDivider(color = p.tableBorder, thickness = 1.dp)
        table.rows.forEachIndexed { ri, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (ci in table.header.indices) {
                    val cell = row.getOrNull(ci)
                    val align = table.alignments.getOrNull(ci) ?: MdColumnAlign.LEFT
                    if (cell != null) MdTableCell(cell, ctx, bold = false, align = align, onCellTap = onCellTap)
                    else MdEmptyCell(align, ctx, onCellTap)
                }
            }
            if (ri < table.rows.lastIndex) {
                HorizontalDivider(color = p.tableRowDivider, thickness = 0.7.dp)
            }
        }
    }
}

@Composable
private fun MdTableCell(
    cell: ASTNode,
    ctx: MdRenderContext,
    bold: Boolean,
    align: MdColumnAlign,
    onCellTap: () -> Unit
) {
    // 后台构建（NFR-1）：单元格可能含行内公式，主线程同步生成位图会拖慢长表滚动；
    // produceState 首次为 null（同版式纯文本兜底），就绪后替换为富文本（key 变化保留旧值不闪空）
    val builtState by produceState<Pair<AnnotatedString, Map<String, InlineTextContent>>?>(
        null, cell, ctx.src, ctx.palette, bold
    ) {
        value = withContext(Dispatchers.Default) {
            val inline = mutableListOf<Pair<String, InlineTextContent>>()
            val text = buildAnnotatedString {
                buildInlineString(cell, ctx, this, inline)
                if (bold) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, length)
            }
            text to inline.toMap()
        }
    }
    val built = builtState
    // 兜底：未就绪时以原文纯文本渲染，命中链接/全屏手势仍可用
    val builtText = built?.first ?: AnnotatedString(cell.nodeText(ctx.src))
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 自由复制模式让位选区手柄；否则命中链接打开 URL，其余区域触发全屏（TR-6.2）
    val tapMod = if (ctx.selectable) Modifier else Modifier.pointerInput(builtText) {
        detectTapGestures(onTap = { pos ->
            val offset = layout?.getOffsetForPosition(pos)
            val ann = offset?.let {
                builtText.getStringAnnotations(tag = "link", start = it, end = it).firstOrNull()
            }
            if (ann == null) {
                onCellTap()
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
        })
    }
    Text(
        text = builtText,
        inlineContent = built?.second ?: emptyMap(),
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = MdType.tableSize,
            lineHeight = 19.sp
        ),
        color = ctx.palette.text,
        textAlign = when (align) {
            MdColumnAlign.LEFT -> TextAlign.Left
            MdColumnAlign.CENTER -> TextAlign.Center
            MdColumnAlign.RIGHT -> TextAlign.Right
        },
        onTextLayout = { layout = it },
        modifier = tapMod
            .widthIn(min = MIN_CELL_WIDTH)
            .padding(
                horizontal = CELL_PAD_H,
                vertical = if (bold) MdSpace.tablePadV.plus(1.dp) else MdSpace.tablePadV
            )
    )
}

@Composable
private fun MdEmptyCell(align: MdColumnAlign, ctx: MdRenderContext, onCellTap: () -> Unit) {
    Text(
        text = "",
        style = MaterialTheme.typography.bodySmall,
        textAlign = when (align) {
            MdColumnAlign.LEFT -> TextAlign.Left
            MdColumnAlign.CENTER -> TextAlign.Center
            MdColumnAlign.RIGHT -> TextAlign.Right
        },
        modifier = Modifier
            .widthIn(min = MIN_CELL_WIDTH)
            .then(if (ctx.selectable) Modifier else Modifier.clickable { onCellTap() })
            .padding(horizontal = CELL_PAD_H, vertical = MdSpace.tablePadV)
    )
}
