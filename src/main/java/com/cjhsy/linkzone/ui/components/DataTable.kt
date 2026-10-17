@file:Suppress("unused")

package com.cjhsy.linkzone.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** 列宽收缩时的硬下限（即便调用方未指定 columnMinWidths），避免列被压到不可读 */
private val HARD_MIN_COLUMN_WIDTH = 56.dp

/**
 * 等比收缩列宽到 [target]：触达下限的列固定，剩余列继续承担收缩；
 * 若下限总和已超过 [target] 则放弃（调用方保留横向滚动）。返回是否完成收缩。
 */
private fun shrinkColumnWidths(widths: IntArray, floors: IntArray, target: Int): Boolean {
    var floorSum = 0
    for (f in floors) floorSum += f
    if (floorSum >= target) return false

    val fixed = BooleanArray(widths.size)
    var remain = target
    repeat(widths.size) {
        var activeSum = 0
        for (i in widths.indices) if (!fixed[i]) activeSum += widths[i]
        if (activeSum <= remain) return true
        var hitFloor = false
        for (i in widths.indices) {
            if (fixed[i]) continue
            val scaled = widths[i].toLong() * remain / activeSum
            if (scaled <= floors[i]) {
                fixed[i] = true
                widths[i] = floors[i]
                remain -= floors[i]
                hitFloor = true
            }
        }
        if (!hitFloor) {
            var assigned = 0
            var last = -1
            for (i in widths.indices) if (!fixed[i]) last = i
            for (i in widths.indices) {
                if (fixed[i] || i == last) continue
                widths[i] = (widths[i].toLong() * remain / activeSum).toInt()
                assigned += widths[i]
            }
            if (last >= 0) widths[last] = (remain - assigned).coerceAtLeast(floors[last])
            return true
        }
    }
    return true
}

/**
 * DataTable（自定义布局 + 横向滚动 + 行内等高）—— 移植自 RikkaHub (me.rerere.ui.table.DataTable)
 * - 使用 SubcomposeLayout 两阶段测量，避免 Lookahead 下的重复测量异常
 * - 高度自适应内容（不提供纵向滚动）
 * - 宽度可超出视口，外层内置 horizontalScroll
 */
@Composable
fun DataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 4.dp,
    cellBorder: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    headerBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    zebraStriping: Boolean = false,
    columnMinWidths: List<Dp> = emptyList(),
    columnMaxWidths: List<Dp> = emptyList(),
    cellAlignment: Alignment = Alignment.CenterStart,
    outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shape: Shape = MaterialTheme.shapes.small,
    stretchToFillWidth: Boolean = true,
    /** 表格内容标识：流式更新/列表复用导致槽位漂移时，滚动位置按内容隔离，避免初始停在中段 */
    scrollKey: Any? = null,
) {
    val hScroll = remember(scrollKey) { ScrollState(initial = 0) }
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .then(
                if (outerBorder != null) Modifier.border(outerBorder, shape) else Modifier
            )
    ) {
        // 捕获滚动视口的可用宽度，用于在内容较窄时把列宽拉伸铺满
        val viewportMaxWidth = constraints.maxWidth

        Box(modifier = Modifier.horizontalScroll(hScroll)) {
            SubcomposeLayout { constraints ->
            val columnCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
            val rowCount = rows.size
            if (columnCount == 0) return@SubcomposeLayout layout(0, 0) {}

            // ---------- 参数 & 中间结果容器 ----------
            val infinity = Constraints.Infinity
            val unbounded = Constraints(0, infinity, 0, infinity)
            val hardMinPx = HARD_MIN_COLUMN_WIDTH.roundToPx()
            val minWidthsPx = IntArray(columnCount) { i ->
                max(columnMinWidths.getOrNull(i)?.roundToPx() ?: 0, hardMinPx)
            }
            val maxWidthsPx = IntArray(columnCount) { i -> columnMaxWidths.getOrNull(i)?.roundToPx() ?: Int.MAX_VALUE }
            val colWidths = IntArray(columnCount) { 0 }

            // ---------- 第一阶段：自然尺寸测量（估列宽） ----------
            fun subcomposeHeaderOnce(c: Int) {
                val measurables = subcompose("h1_$c") {
                    CellBox(
                        padding = cellPadding,
                        border = cellBorder,
                        background = headerBackground,
                        alignment = cellAlignment
                    ) {
                        headers.getOrNull(c)?.invoke()
                    }
                }
                val cs = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                    Constraints(0, maxWidthsPx[c], 0, infinity)
                } else {
                    unbounded
                }
                val p = measurables.first().measure(cs)
                colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
            }

            fun subcomposeBodyOnce(r: Int, c: Int) {
                val bg = if (zebraStriping && r % 2 == 1) surfaceContainer else Color.Transparent
                val measurables = subcompose("b1_${r}_$c") {
                    CellBox(padding = cellPadding, border = cellBorder, background = bg, alignment = cellAlignment) {
                        rows[r].getOrNull(c)?.invoke()
                    }
                }
                val cs = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                    Constraints(0, maxWidthsPx[c], 0, infinity)
                } else {
                    unbounded
                }
                val p = measurables.first().measure(cs)
                colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
            }

            for (c in 0 until columnCount) subcomposeHeaderOnce(c)
            for (r in 0 until rowCount) for (c in 0 until columnCount) subcomposeBodyOnce(r, c)

            // ---------- 列宽适配视口：窄则等比铺满，宽则等比收缩（单元格内换行），
            //            连列下限总和都放不下时才保留横向滚动 ----------
            val naturalWidth = colWidths.sum()
            if (stretchToFillWidth &&
                viewportMaxWidth != Constraints.Infinity &&
                naturalWidth > 0
            ) {
                if (viewportMaxWidth > naturalWidth) {
                    val extra = viewportMaxWidth - naturalWidth
                    var distributed = 0
                    for (c in 0 until columnCount) {
                        val add = if (c == columnCount - 1) {
                            extra - distributed
                        } else {
                            (extra.toLong() * colWidths[c] / naturalWidth).toInt()
                        }
                        colWidths[c] += add
                        distributed += add
                    }
                } else if (viewportMaxWidth < naturalWidth) {
                    shrinkColumnWidths(colWidths, minWidthsPx, viewportMaxWidth)
                }
            }

            // ---------- 第二阶段：按最终列宽测量（不限制高度），拿到收缩换行后的真实行高 ----------
            fun widthConstraints(colWidth: Int): Constraints = Constraints(
                minWidth = colWidth.coerceAtLeast(0),
                maxWidth = colWidth.coerceAtLeast(0),
                minHeight = 0,
                maxHeight = infinity,
            )

            val headerP2 = Array(columnCount) { c ->
                val measurables = subcompose("h2_$c") {
                    CellBox(
                        padding = cellPadding,
                        border = cellBorder,
                        background = headerBackground,
                        alignment = cellAlignment
                    ) {
                        headers.getOrNull(c)?.invoke()
                    }
                }
                measurables.first().measure(widthConstraints(colWidths[c]))
            }

            val bodyP2 = Array(rowCount * columnCount) { i ->
                val r = i / columnCount
                val c = i % columnCount
                val bg = if (zebraStriping && r % 2 == 1) surfaceContainer else Color.Transparent
                val measurables = subcompose("b2_${r}_$c") {
                    CellBox(padding = cellPadding, border = cellBorder, background = bg, alignment = cellAlignment) {
                        rows[r].getOrNull(c)?.invoke()
                    }
                }
                measurables.first().measure(widthConstraints(colWidths[c]))
            }

            val rowHeights = IntArray(rowCount) { r ->
                var h = 0
                for (c in 0 until columnCount) {
                    h = max(h, bodyP2[r * columnCount + c].height)
                }
                h
            }
            val headerHeight = headerP2.maxOf { it.height }

            // ---------- 第三阶段：固定列宽 + 统一行高重新测量（背景/边框铺满整行） ----------
            fun constraintsFor(colWidth: Int, minH: Int): Constraints {
                val safeColWidth = colWidth.coerceAtLeast(0)
                val safeMinH = minH.coerceAtLeast(0)
                return Constraints(
                    minWidth = safeColWidth,
                    maxWidth = safeColWidth,
                    minHeight = safeMinH,
                    maxHeight = infinity,
                )
            }

            val headerPlaceables = Array(columnCount) { c ->
                val measurables = subcompose("h3_$c") {
                    CellBox(
                        padding = cellPadding,
                        border = cellBorder,
                        background = headerBackground,
                        alignment = cellAlignment
                    ) {
                        headers.getOrNull(c)?.invoke()
                    }
                }
                measurables.first().measure(constraintsFor(colWidths[c], headerHeight))
            }

            val bodyPlaceables = Array(rowCount * columnCount) { i ->
                val r = i / columnCount
                val c = i % columnCount
                val bg =
                    if (zebraStriping && r % 2 == 1) surfaceContainer else Color.Transparent
                val measurables = subcompose("b3_${r}_$c") {
                    CellBox(padding = cellPadding, border = cellBorder, background = bg, alignment = cellAlignment) {
                        rows[r].getOrNull(c)?.invoke()
                    }
                }
                measurables.first().measure(constraintsFor(colWidths[c], rowHeights[r]))
            }

            val tableWidth = colWidths.sum()
            val tableHeight = headerHeight + rowHeights.sum()
            val finalWidth = tableWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            val finalHeight = tableHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

            // ---------- 放置 ----------
            layout(finalWidth, finalHeight) {
                var x = 0
                for (c in 0 until columnCount) {
                    headerPlaceables[c].placeRelative(x, 0)
                    x += colWidths[c]
                }
                var y = headerHeight
                for (r in 0 until rowCount) {
                    x = 0
                    for (c in 0 until columnCount) {
                        bodyPlaceables[r * columnCount + c].placeRelative(x, y)
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
            }
        }
    }
}

@Composable
private fun CellBox(
    padding: Dp,
    border: BorderStroke?,
    background: Color,
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .then(if (background != Color.Transparent) Modifier.background(background) else Modifier)
            .then(if (border != null) Modifier.border(border) else Modifier)
            .padding(padding),
        contentAlignment = alignment,
    ) {
        content()
    }
}

// -------------------- 示例 --------------------
@Preview(showBackground = true)
@Composable
private fun DataTablePreview() {
    Surface {
        val headers = listOf<@Composable () -> Unit>(
            { Text("Semester", style = MaterialTheme.typography.labelLarge) },
            { Text("Attendance", style = MaterialTheme.typography.labelLarge) },
            { Text("Notes / Example", style = MaterialTheme.typography.labelLarge) },
        )

        val rows = listOf<List<@Composable () -> Unit>>(
            listOf<@Composable () -> Unit>(
                { Text("Fall 2024") },
                { Text("Excellent", style = MaterialTheme.typography.bodyMedium) },
                { Text("x² + y² = 1") },
            ),
            listOf(
                { Text("Fall 2024") },
                { Text("Good", style = MaterialTheme.typography.bodyMedium) },
                { Text("∑ k = n(n+1)/2", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            ),
            listOf(
                { Text("Fall 2024") },
                { Text("Fair", style = MaterialTheme.typography.bodyMedium) },
                { Text("这行更高会把整行拉齐! 这是一个很长的文本用来测试换行功能!\nhaha") },
            ),
        )

        DataTable(
            headers = headers,
            rows = rows,
            columnMinWidths = listOf(60.dp, 100.dp, 80.dp),
            columnMaxWidths = listOf(120.dp, 100.dp, 200.dp),
            zebraStriping = false,
        )
    }
}
