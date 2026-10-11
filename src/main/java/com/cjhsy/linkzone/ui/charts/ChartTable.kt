package com.cjhsy.linkzone.ui.charts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

private class ChartTableColumn(
    val title: String,
    val numeric: Boolean,
    val color: Color? = null,
)

private class ChartTableData(
    val columns: List<ChartTableColumn>,
    val rows: List<List<String>>,
)

private fun ChartSpec.buildTableData(
    colors: List<Color>,
    seriesNames: List<String>,
    seriesHeader: String,
): ChartTableData = when (style) {
    ChartStyle.Scatter -> ChartTableData(
        columns = listOf(
            ChartTableColumn(seriesHeader, numeric = false),
            ChartTableColumn(xAxis.title ?: "x", numeric = true),
            ChartTableColumn(yAxis.title ?: "y", numeric = true),
        ),
        rows = series.flatMapIndexed { index, s ->
            val name = seriesNames[index]
            s.points.map { point ->
                listOf(name, formatChartNumber(point.x, xAxis.format), formatChartNumber(point.y, yAxis.format))
            }
        },
    )

    else -> ChartTableData(
        columns = buildList {
            add(ChartTableColumn(xAxis.title.orEmpty(), numeric = false))
            series.indices.forEach { index ->
                add(ChartTableColumn(seriesNames[index], numeric = true, color = colors[index]))
            }
        },
        rows = (0 until categoryCount).map { index ->
            buildList {
                add(categoryLabel(index))
                series.forEach { s ->
                    add(s.values.getOrNull(index)?.let { formatChartNumber(it, yAxis.format) } ?: "-")
                }
            }
        },
    )
}

/**
 * 图表的表格视图, 基于 [DataTable]; 行数较多时在限定高度内纵向滚动
 */
@Composable
internal fun ChartTable(
    spec: ChartSpec,
    colors: List<Color>,
    seriesNames: List<String>,
    modifier: Modifier = Modifier,
) {
    val seriesHeader = "系列"
    val table = remember(spec, colors, seriesNames, seriesHeader) {
        spec.buildTableData(colors, seriesNames, seriesHeader)
    }

    Box(
        modifier = modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        DataTable(
            headers = table.columns.map { column ->
                @Composable {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (column.color != null) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(column.color, RoundedCornerShape(2.dp))
                            )
                        }
                        Text(
                            text = column.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            rows = table.rows.map { row ->
                row.mapIndexed { columnIndex, cell ->
                    val numeric = table.columns[columnIndex].numeric
                    @Composable {
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = if (numeric) Modifier.fillMaxWidth() else Modifier,
                        )
                    }
                }
            },
            cellPadding = 6.dp,
            columnMinWidths = table.columns.map { if (it.numeric) 64.dp else 48.dp },
            // 数值列不设上限: 首轮测量为无界约束, fillMaxWidth 不会把列撑大, 次轮固定列宽后才右对齐
            columnMaxWidths = table.columns.map { if (it.numeric) Dp.Infinity else 200.dp },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * DataTable（自定义布局 + 横向滚动 + 行内等高, 自 RikkaHub 内联）
 * - 使用 SubcomposeLayout 两阶段测量，避免 Lookahead 下的重复测量异常
 * - 高度自适应内容（不提供纵向滚动）
 * - 宽度可超出视口，外层内置 horizontalScroll
 */
@Composable
private fun DataTable(
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
) {
    val hScroll = rememberScrollState()
    val stripeBackground = MaterialTheme.colorScheme.surfaceVariant

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
                val minWidthsPx = IntArray(columnCount) { i -> columnMinWidths.getOrNull(i)?.roundToPx() ?: 0 }
                val maxWidthsPx = IntArray(columnCount) { i -> columnMaxWidths.getOrNull(i)?.roundToPx() ?: Int.MAX_VALUE }
                val colWidths = IntArray(columnCount) { 0 }
                val headerP1 = arrayOfNulls<Placeable>(columnCount)
                val bodyP1 = arrayOfNulls<Placeable>(rowCount * columnCount)

                // ---------- 第一阶段：自然尺寸测量（估列宽、算行高） ----------
                fun subcomposeHeaderOnce(c: Int): Placeable {
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
                    val constraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                        Constraints(0, maxWidthsPx[c], 0, infinity)
                    } else {
                        unbounded
                    }
                    val p = measurables.first().measure(constraints)
                    colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                    return p
                }

                fun subcomposeBodyOnce(r: Int, c: Int): Placeable {
                    val bg = if (zebraStriping && r % 2 == 1) stripeBackground else Color.Transparent
                    val measurables = subcompose("b1_${r}_$c") {
                        CellBox(padding = cellPadding, border = cellBorder, background = bg, alignment = cellAlignment) {
                            rows[r].getOrNull(c)?.invoke()
                        }
                    }
                    val constraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                        Constraints(0, maxWidthsPx[c], 0, infinity)
                    } else {
                        unbounded
                    }
                    val p = measurables.first().measure(constraints)
                    colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                    return p
                }

                for (c in 0 until columnCount) headerP1[c] = subcomposeHeaderOnce(c)
                for (r in 0 until rowCount) for (c in 0 until columnCount) bodyP1[r * columnCount + c] =
                    subcomposeBodyOnce(r, c)

                val rowHeights = IntArray(rowCount) { r ->
                    var h = 0
                    for (c in 0 until columnCount) {
                        h = max(h, bodyP1[r * columnCount + c]!!.height)
                    }
                    h
                }
                val headerHeight = headerP1.maxOf { it?.height ?: 0 }

                // ---------- 列宽拉伸：内容较窄时按比例铺满视口宽度 ----------
                val naturalWidth = colWidths.sum()
                if (stretchToFillWidth &&
                    viewportMaxWidth != Constraints.Infinity &&
                    viewportMaxWidth > naturalWidth &&
                    naturalWidth > 0
                ) {
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
                }

                // ---------- 第二阶段：固定列宽 + 统一行高重新测量 ----------
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
                    measurables.first().measure(constraintsFor(colWidths[c], headerHeight))
                }

                val bodyPlaceables = Array(rowCount * columnCount) { i ->
                    val r = i / columnCount
                    val c = i % columnCount
                    val bg =
                        if (zebraStriping && r % 2 == 1) stripeBackground else Color.Transparent
                    val measurables = subcompose("b2_${r}_$c") {
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
