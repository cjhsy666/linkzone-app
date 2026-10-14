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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 代码块卡片：圆角卡片 + 顶栏（语言标签 + 弹性复制按钮）+ Prism4j 语法高亮。
 * ≥8 行显示行号 gutter；>50 行默认折叠（底部渐变遮罩 + 展开按钮）；长行横向滚动。
 */

private const val LINE_NUMBER_THRESHOLD = 8
private const val COLLAPSE_THRESHOLD = 50

@Composable
internal fun MdCodeBlock(
    lang: String,
    code: String,
    textColor: Color
) {
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

    val dark = textColor.luminance() > 0.5f
    val bg = textColor.copy(alpha = 0.06f)
    val border = textColor.copy(alpha = 0.14f)
    val dim = textColor.copy(alpha = 0.65f)

    val lines = remember(code) { code.lines() }
    val lineCount = lines.size
    val showLineNumbers = lineCount >= LINE_NUMBER_THRESHOLD
    val collapsible = lineCount > COLLAPSE_THRESHOLD
    var expanded by remember { mutableStateOf(false) }

    val highlighted = remember(code, lang, dark) { highlightWithPrism(code, lang, dark) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .background(bg)
    ) {
        // 顶栏：语言名 + 复制
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.ifBlank { "代码" },
                style = MaterialTheme.typography.labelSmall,
                color = dim,
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
                    tint = dim,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim
                )
            }
        }
        HorizontalDivider(color = border, thickness = 0.7.dp)

        // 代码区（可折叠）
        Box {
            val codeScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible && !expanded) Modifier.heightIn(max = 320.dp) else Modifier
                    )
                    .horizontalScroll(codeScroll)
                    .padding(vertical = 10.dp)
            ) {
                if (showLineNumbers) {
                    // 行号 gutter（随代码区一起横向滚动，行高与代码行高严格一致 19sp）
                    Column(
                        modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        repeat(lineCount) { i ->
                            Text(
                                text = "${i + 1}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 19.sp
                                ),
                                color = dim.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = highlighted ?: AnnotatedString(code),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp
                    ),
                    color = textColor,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            // 折叠态底部渐变遮罩
            if (collapsible && !expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, bg.copy(alpha = 0.95f))
                            )
                        )
                )
            }
        }

        // 展开/收起条
        if (collapsible) {
            HorizontalDivider(color = border, thickness = 0.7.dp)
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier.fillMaxWidth().height(30.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = dim,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (expanded) "收起" else "展开全部 $lineCount 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim
                )
            }
        }
    }
}
