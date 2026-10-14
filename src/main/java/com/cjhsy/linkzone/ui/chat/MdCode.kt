package com.cjhsy.linkzone.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer

/**
 * 代码块卡片：主题化卡面（[MdPalette.codeSurface]，明暗 × 文档流/气泡四套）+
 * 顶栏（语言标签 + 弹性复制按钮）+ Prism4j 语法高亮。
 * ≥8 行显示行号 gutter；>50 行默认折叠（底部渐变遮罩 + 展开按钮）；长行横向滚动；
 * 代码区独立 SelectionContainer 支持片段选择（不与气泡长按菜单冲突）；
 * 流式输出期间（streaming=true）强制展开，不折叠半截代码。
 */

private const val LINE_NUMBER_THRESHOLD = 8
private const val COLLAPSE_THRESHOLD = 50
private const val COLLAPSED_MAX_HEIGHT_DP = 320

@Composable
internal fun MdCodeBlock(lang: String, code: String, ctx: MdRenderContext) {
    val p = ctx.palette
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    // 复制按钮弹性缩放反馈
    val copyScale by animateFloatAsState(
        targetValue = if (copied) 1.25f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "copyScale"
    )

    val lines = remember(code) { code.lines() }
    val lineCount = lines.size
    val showLineNumbers = lineCount >= LINE_NUMBER_THRESHOLD
    // 流式期间代码不断增长：禁止折叠，避免半截代码被遮罩盖住
    val collapsible = lineCount > COLLAPSE_THRESHOLD && !ctx.streaming
    var expanded by remember { mutableStateOf(false) }

    // 高亮结果后台 tokenize + 缓存（key 含 内容+语言+调色板，主题切换自动重算）；
    // 流式中防抖：半截代码先纯等宽兜底，停止后一次性最终高亮
    val highlighted = rememberHighlightedCode(code, lang, p.code, ctx.streaming)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(MdSpace.codeRadius))
            .border(1.dp, p.codeBorder, RoundedCornerShape(MdSpace.codeRadius))
            .background(p.codeSurface)
    ) {
        // 顶栏：语言名 + 复制
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.codeHeaderBg)
                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                // 无语法或未知语言统一显示"代码"（TR-5.2）
                text = if (lang.isBlank() || !hasPrismGrammar(lang)) "代码" else lang,
                style = MaterialTheme.typography.labelSmall,
                color = p.dim,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(30.dp).scale(copyScale)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = if (copied) "已复制" else "复制",
                    tint = p.dim,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = p.dim
                )
            }
        }
        HorizontalDivider(color = p.codeHeaderDivider, thickness = 0.7.dp)

        // 代码区（可折叠；独立 SelectionContainer 支持片段选择）
        Box {
            val codeScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible && !expanded)
                            Modifier.heightIn(max = COLLAPSED_MAX_HEIGHT_DP.dp) else Modifier
                    )
                    .horizontalScroll(codeScroll)
                    .padding(vertical = MdSpace.codePadV)
            ) {
                if (showLineNumbers) {
                    // 行号 gutter（与代码行高严格一致，不参与选择）
                    Column(
                        modifier = Modifier.padding(start = MdSpace.codePadH, end = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        repeat(lineCount) { i ->
                            Text(
                                text = "${i + 1}",
                                style = mdCodeStyle,
                                color = p.faint,
                                fontSize = MdType.codeSize,
                                lineHeight = MdType.codeLineHeight,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                SelectionContainer(modifier = Modifier.padding(end = MdSpace.codePadH)) {
                    Text(
                        text = highlighted ?: AnnotatedString(code),
                        style = mdCodeStyle.copy(color = p.code.codeText),
                        fontSize = MdType.codeSize,
                        lineHeight = MdType.codeLineHeight
                    )
                }
            }
            // 折叠态底部渐变遮罩（渐隐到代码卡面色，非纯色硬边）
            if (collapsible && !expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(androidx.compose.ui.graphics.Color.Transparent, p.codeFade)
                            )
                        )
                )
            }
        }

        // 展开/收起条
        if (collapsible) {
            HorizontalDivider(color = p.codeHeaderDivider, thickness = 0.7.dp)
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier.fillMaxWidth().height(30.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = p.dim,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (expanded) "收起" else "展开全部 $lineCount 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = p.dim
                )
            }
        }
    }
}
