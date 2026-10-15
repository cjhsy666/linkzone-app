package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownTypography

/** 块级离屏渲染工作宽（物理px）：@3x 约 480dp，覆盖常规气泡宽度且保证清晰度 */
private const val ISLAND_MAX_WIDTH_PX = 1440

/** 失败回退：复用源码卡片（等宽样式取当前 Markdown 排版，高亮板按气泡明暗选择） */
@Composable
private fun MdIslandFallback(code: String, language: String, textColor: Color) {
    val style = LocalMarkdownTypography.current.code
    val highlights = rememberMdHighlightsBuilder(dark = textColor.luminance() > 0.5f)
    MdCodeCard(code, language, style, textColor, highlights)
}

/** 渲染中占位：固定最小高度的淡色区域，避免位图就绪后布局剧烈跳动 */
@Composable
private fun MdIslandPlaceholder(label: String, minHeight: Dp, textColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = mdSecondaryText(textColor),
        )
    }
}

/**
 * Mermaid 图表：离屏渲染白底位图（轻卡片包裹，与豆包同款中性卡片），
 * 点击弹全屏预览；失败回退源码卡片。不挂载任何 WebView（离屏共享实例渲染），
 * LazyColumn 滑动进出组合仅是 Image 显示/消失，零 WebView 创建销毁开销。
 */
@Composable
fun MdMermaidIsland(code: String, textColor: Color) {
    var showPreview by remember { mutableStateOf(false) }
    val result = rememberOffscreenRender("mermaid.html", "renderDiagram", code, ISLAND_MAX_WIDTH_PX)
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(cardShape)
            .border(1.dp, textColor.copy(alpha = 0.10f), cardShape)
            .background(textColor.copy(alpha = 0.03f))
            .padding(10.dp)
    ) {
        when (result) {
            is MdOffscreen.Result.Ready -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
            ) {
                Image(
                    bitmap = result.bitmap.asImageBitmap(),
                    contentDescription = "图表",
                    modifier = Modifier.fillMaxWidth(),
                )
                // 全屏预览点击层：统一接管点击（WebView 已不挂载，不再消费触摸），
                // 不拦截外层气泡的长按/自由复制等其余手势语义
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showPreview = true }
                )
                // 可点击提示：右上角小图标
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "全屏预览",
                    tint = mdSecondaryText(textColor),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
            is MdOffscreen.Result.Failed -> MdIslandFallback(code, "mermaid", textColor)
            null -> MdIslandPlaceholder("渲染图表…", 120.dp, textColor)
        }
    }
    if (showPreview) {
        MdMermaidPreviewDialog(code = code, onDismiss = { showPreview = false })
    }
}

/**
 * LaTeX 块级公式（$$...$$）：离屏渲染白底位图直接呈现（不着卡片，数学式本体即
 * 视觉主体，居中由页面 CSS text-align:center 保证）；失败回退源码卡片。
 */
@Composable
fun MdKatexIsland(formula: String, textColor: Color) {
    val result = rememberOffscreenRender("katex.html", "renderBlock", formula, ISLAND_MAX_WIDTH_PX)
    when (result) {
        is MdOffscreen.Result.Ready -> Image(
            bitmap = result.bitmap.asImageBitmap(),
            contentDescription = formula,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        is MdOffscreen.Result.Failed -> MdIslandFallback(formula, "latex", textColor)
        null -> MdIslandPlaceholder("渲染公式…", 56.dp, textColor)
    }
}
