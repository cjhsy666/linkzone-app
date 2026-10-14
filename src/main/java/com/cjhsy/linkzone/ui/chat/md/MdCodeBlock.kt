package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- 语法高亮主题（迁移自旧正则高亮调色板，9 色位均为 Int RGB） ----------

private val LIGHT_SYNTAX = SyntaxTheme(
    key = "lz-light",
    code = 0x1F2328, keyword = 0x7C4DFF, string = 0x2E7D32,
    literal = 0xB25000, comment = 0x9E9E9E, metadata = 0x00695C,
    multilineComment = 0x9E9E9E, punctuation = 0x5D4037, mark = 0x0B7285,
)

private val DARK_SYNTAX = SyntaxTheme(
    key = "lz-dark",
    code = 0xE6EDF3, keyword = 0xC792EA, string = 0xC3E88D,
    literal = 0xF78C6C, comment = 0x8B949E, metadata = 0x4EC9B0,
    multilineComment = 0x8B949E, punctuation = 0x89DDFF, mark = 0x82AAFF,
)

/** 用户气泡白字 → 深色高亮板；AI 气泡深字 → 浅色高亮板 */
@Composable
fun rememberMdHighlightsBuilder(dark: Boolean): Highlights.Builder =
    remember(dark) { Highlights.Builder().theme(if (dark) DARK_SYNTAX else LIGHT_SYNTAX) }

/** highlights 结果转 AnnotatedString（与库内 MarkdownHighlightedCode 同一配方） */
private fun buildHighlighted(code: String, language: String?, builder: Highlights.Builder): AnnotatedString {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
    val codeHighlights = builder
        .code(code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()
    return buildAnnotatedString {
        append(code)
        codeHighlights.forEach {
            val style = when (it) {
                is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(style = style, start = it.location.start, end = it.location.end)
        }
    }
}

/**
 * 现代代码卡片：顶栏（语言标签 + 一键复制）、0.7dp 分隔线、等宽代码区横向滚动。
 * 视觉参数迁移自旧自绘卡片（圆角 12dp、padding 12/10）；高亮异步计算，先显纯文本。
 */
@Composable
fun MdCodeCard(
    code: String,
    language: String?,
    style: TextStyle,
    textColor: Color,
    highlightsBuilder: Highlights.Builder,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val bg = textColor.copy(alpha = 0.06f)
    val border = textColor.copy(alpha = 0.14f)
    val dim = textColor.copy(alpha = 0.65f)
    val highlighted by produceState(initialValue = AnnotatedString(code), code, language) {
        val job = launch(Dispatchers.Default) {
            value = buildHighlighted(code, language, highlightsBuilder)
        }
        awaitDispose { job.cancel() }
    }

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
                text = language?.ifBlank { null } ?: "代码",
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
                modifier = Modifier.height(30.dp)
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
        // 代码区：等宽 + 横向滚动（长行不折断，滑出查看）
        Text(
            text = highlighted,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}
