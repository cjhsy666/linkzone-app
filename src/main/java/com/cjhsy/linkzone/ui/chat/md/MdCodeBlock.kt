package com.cjhsy.linkzone.ui.chat.md

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import java.io.File

// ---------- 语法高亮主题（深字卡片走浅色板，浅字卡片走 VS Code Dark+） ----------

private val LIGHT_SYNTAX = SyntaxTheme(
    key = "lz-light",
    code = 0x24292F, keyword = 0xAF00DB, string = 0xA31515,
    literal = 0x098658, comment = 0x008000, metadata = 0x267F99,
    multilineComment = 0x008000, punctuation = 0x24292F, mark = 0x0000FF,
)

private val DARK_SYNTAX = SyntaxTheme(
    key = "lz-dark",
    code = 0xD4D4D4, keyword = 0xC586C0, string = 0xCE9178,
    literal = 0xB5CEA8, comment = 0x6A9955, metadata = 0x4EC9B0,
    multilineComment = 0x6A9955, punctuation = 0xD4D4D4, mark = 0x569CD6,
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

/** 超过该行数默认折叠，底部给出「查看完整内容」入口 */
private const val COLLAPSE_LINES = 12

/**
 * 代码卡片：中性底色圆角卡片（无边框、无分割线），顶栏语言标签 + 图形按钮（复制/下载/展开），
 * 等宽代码区横向滚动；超长代码默认限高折叠，底部「查看完整内容」展开（带尺寸动画反馈）。
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
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val collapsible = remember(code) { code.count { it == '\n' } + 1 > COLLAPSE_LINES }
    var expanded by remember { mutableStateOf(false) }
    val cardBg = mdNeutralCard(textColor)
    val subColor = mdSecondaryText(textColor)
    val highlighted by produceState(initialValue = AnnotatedString(code), code, language) {
        val job = launch(Dispatchers.Default) {
            value = buildHighlighted(code, language, highlightsBuilder)
        }
        awaitDispose { job.cancel() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
    ) {
        // 顶栏：语言名 + 图形按钮（无文字标签）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language?.ifBlank { null } ?: "代码",
                style = MaterialTheme.typography.labelMedium,
                color = subColor,
                modifier = Modifier.weight(1f)
            )
            MdCodeAction(
                icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                description = if (copied) "已复制" else "复制",
                tint = subColor,
            ) {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }
            MdCodeAction(icon = Icons.Filled.Download, description = "下载", tint = subColor) {
                Toast.makeText(context, saveCodeToFile(context, code, language), Toast.LENGTH_LONG).show()
            }
            if (collapsible) {
                MdCodeAction(
                    icon = if (expanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                    description = if (expanded) "收起" else "展开",
                    tint = subColor,
                ) { expanded = !expanded }
            }
        }
        // 代码区：等宽 + 横向滚动（长行不折断，滑出查看）；超长代码默认限高折叠。
        // 滚动状态按代码内容隔离：流式增量/列表槽位复用不会让新代码块继承陈旧偏移而停在行中段。
        val hScroll = remember(code) { ScrollState(initial = 0) }
        Box {
            Text(
                text = highlighted,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (collapsible && !expanded) Modifier.heightIn(max = 300.dp) else Modifier)
                    .horizontalScroll(hScroll)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
            // 左右边缘渐隐：仅在仍可向该方向滚动时出现，暗示长行可横滑
            if (hScroll.canScrollBackward) {
                Box(Modifier.matchParentSize().edgeFade(cardBg, atLeft = true))
            }
            if (hScroll.canScrollForward) {
                Box(Modifier.matchParentSize().edgeFade(cardBg, atLeft = false))
            }
        }
        if (collapsible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    text = if (expanded) "收起" else "查看完整内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = subColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun MdCodeAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 横滑边缘渐隐：代码隐入卡片底色，克制提示该方向仍有内容 */
private fun Modifier.edgeFade(edgeColor: Color, atLeft: Boolean): Modifier = drawBehind {
    val fadePx = 28.dp.toPx()
    val brush = if (atLeft) {
        Brush.horizontalGradient(listOf(edgeColor, Color.Transparent), startX = 0f, endX = fadePx)
    } else {
        Brush.horizontalGradient(
            listOf(Color.Transparent, edgeColor),
            startX = size.width - fadePx,
            endX = size.width,
        )
    }
    drawRect(brush)
}

/** 常见语言 → 文件扩展名（下载用），未识别回退 txt */
private val LANG_EXT = mapOf(
    "kotlin" to "kt", "java" to "java", "python" to "py", "javascript" to "js",
    "typescript" to "ts", "go" to "go", "rust" to "rs", "c" to "c", "cpp" to "cpp",
    "c++" to "cpp", "csharp" to "cs", "c#" to "cs", "shell" to "sh", "bash" to "sh",
    "json" to "json", "xml" to "xml", "yaml" to "yml", "yml" to "yml", "sql" to "sql",
    "html" to "html", "css" to "css", "markdown" to "md", "md" to "md", "php" to "php",
    "ruby" to "rb", "swift" to "swift", "dart" to "dart", "lua" to "lua",
)

/** 下载代码：API29+ 写入 LinkZone/Code 目录（免权限），低版本写应用私有目录 */
private fun saveCodeToFile(context: Context, code: String, language: String?): String {
    val ext = language?.lowercase()?.let { LANG_EXT[it] } ?: "txt"
    val name = "code_${System.currentTimeMillis()}.$ext"
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/LinkZone/Code")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert 失败")
            context.contentResolver.openOutputStream(uri)?.use { it.write(code.toByteArray()) }
                ?: error("打开输出流失败")
            "已保存到 LinkZone/Code：$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("存储不可用")
            File(dir, name).writeText(code)
            "已保存：${dir.absolutePath}/$name"
        }
    }.getOrElse { "保存失败：${it.message}" }
}
