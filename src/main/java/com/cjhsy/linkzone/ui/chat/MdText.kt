package com.cjhsy.linkzone.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * 完整 Markdown 渲染（Markwon 驱动 TextView）：
 * 标题 / 段落 / 有序无序列表 / 引用 / 分割线 / 代码块 / 行内代码、加粗、斜体、删除线 /
 * 链接（点击打开浏览器）/ 表格 / 任务列表复选框 / 自动链接 / 基础内联 HTML /
 * LaTeX 数学公式（块级 $$...$$ 与行内 $...$）。
 *
 * 颜色随气泡适配：右侧品牌气泡传白色文字，左侧白气泡传深色文字。
 */
@Composable
fun MarkdownContent(
    markdown: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    /** 自由复制模式：开启后长按可直接选择文本（配合外层 SelectionContainer 使用） */
    selectable: Boolean = false,
    /** 长按 Markdown 文本时回调（弹出消息操作菜单）；自由复制模式下传 null 让位给文本选择 */
    onLongClick: (() -> Unit)? = null,
    /** 点击 Markdown 文本（非链接处）回调（编辑重发 / 退出自由复制） */
    onTextClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val latexTextSize = with(density) { 14.sp.toPx() }
    // 排版主题用到的像素值（一次性算好，remember 捕获）
    val hairlinePx = with(density) { 1.dp.toPx().toInt() }
    val quoteBarPx = with(density) { 3.dp.toPx().toInt() }
    val quotePadPx = with(density) { 10.dp.toPx().toInt() }

    val markwon = remember(linkColor, textColor, hairlinePx, quoteBarPx, quotePadPx) {
        // 代码块 / 行内代码配色（随气泡文字色派生，无新依赖）
        val inlineCodeBg = linkColor.copy(alpha = 0.12f).toArgb()
        val blockCodeBg = textColor.copy(alpha = 0.06f).toArgb()
        val dimLine = textColor.copy(alpha = 0.28f).toArgb()
        val quoteBar = textColor.copy(alpha = 0.3f).toArgb()
        val bullet = textColor.copy(alpha = 0.55f).toArgb()
        Markwon.builder(context)
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            // 行内解析交给 inline-parser（行内 LaTeX 需要），同时启用行内 LaTeX
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(latexTextSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().blockFitCanvas(true)
                }
            )
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(linkColor.toArgb())
                        .codeBackgroundColor(inlineCodeBg)
                        .codeBlockTextColor(textColor.toArgb())
                        .codeBlockBackgroundColor(blockCodeBg)
                        // 现代排版：标题层级、标题/段落分割线、引用竖条、列表圆点均随气泡色派生
                        .headingTextSize(floatArrayOf(20f, 17.5f, 16f, 15f, 14.5f, 14f))
                        .headingBreakColor(dimLine)
                        .headingBreakHeight(hairlinePx)
                        .blockquoteColor(quoteBar)
                        .blockquoteWidth(quoteBarPx)
                        .blockquoteLeftPadding(quotePadPx)
                        .blockquoteRightPadding(quotePadPx)
                        .thematicBreakColor(dimLine)
                        .thematicBreakHeight(hairlinePx)
                        .bulletColor(bullet)
                }
            })
            .build()
    }

    // 拆分为文本段（Markwon）/ 表格段 / 代码块段（均 Compose 自绘）：
    // Markwon 的表格 span 窄屏换行会溢出边框、代码块无卡片/复制/高亮，故两者自绘
    val segments = remember(markdown) { splitMarkdownBlocks(markdown) }

    // 长按/点击回调可能随重组变化，用稳定引用持有最新值（TextView 监听器只设置一次）
    val latestLongClick = rememberUpdatedState(onLongClick)
    val latestClick = rememberUpdatedState(onTextClick)

    Column(modifier = modifier) {
        segments.forEach { seg ->
            when (seg) {
                is MdSegment.Text -> MarkwonTextView(
                    markwon = markwon,
                    raw = seg.raw,
                    textColor = textColor,
                    linkColor = linkColor,
                    selectable = selectable,
                    latestLongClick = latestLongClick,
                    latestClick = latestClick
                )
                is MdSegment.Table -> MarkdownTableBlock(
                    rows = seg.rows,
                    textColor = textColor,
                    linkColor = linkColor
                )
                is MdSegment.Code -> MarkdownCodeBlock(
                    lang = seg.lang,
                    code = seg.code,
                    textColor = textColor
                )
            }
        }
    }
}

/** Markwon 驱动的单段 Markdown 文本视图（从 MarkdownContent 抽出，便于表格分段复用） */
@Composable
private fun MarkwonTextView(
    markwon: Markwon,
    raw: String,
    textColor: Color,
    linkColor: Color,
    selectable: Boolean,
    latestLongClick: androidx.compose.runtime.State<(() -> Unit)?>,
    latestClick: androidx.compose.runtime.State<(() -> Unit)?>
) {
    // 仅在内容变化时解析+渲染，避免列表重组反复解析整段 Markdown
    val rendered = remember(markwon, raw) { markwon.toMarkdown(preprocessLatex(raw)) }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                // 链接由 Markwon 的 LinkResolver 统一以 ACTION_VIEW 打开浏览器
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                // 自由复制模式由外层切换（菜单点"自由复制"进入，点气泡背景退出）
                setTextIsSelectable(false)
                setLineSpacing(4f, 1f)
                val pad = (6 * ctx.resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
                // 长按主动上报给外层弹消息菜单：
                // TextView 会吞掉触摸流，外层 Compose 的 combinedClickable 收不到长按，
                // 必须在这里桥接。返回 true 消费掉，避免触发系统的文本选择；
                // 自由复制模式下外层传 null，返回 false 让位给系统选择
                setOnLongClickListener {
                    val cb = latestLongClick.value
                    if (cb != null && !isTextSelectable) {
                        cb()
                        true
                    } else false
                }
                // 设置 OnLongClickListener 后 TextView 会消费掉所有触摸事件（含点击），
                // 外层气泡的点击（编辑重发/退出自由复制）收不到，这里桥接点击；
                // 命中链接时 LinkMovementMethod 优先处理，不会触发该回调
                setOnClickListener { latestClick.value?.invoke() }
            }
        },
        update = { tv ->
            tv.setTextColor(textColor.toArgb())
            tv.setLinkTextColor(linkColor.toArgb())
            tv.setTextIsSelectable(selectable)
            tv.textSize = 14f
            tv.text = rendered
        }
    )
}

// ---------- 表格：从 Markdown 拆出 + Compose 自绘（替代 Markwon TablePlugin） ----------

/** 拆分出的渲染段：纯文本段（走 Markwon）或表格段（Compose 自绘） */
internal sealed class MdSegment {
    data class Text(val raw: String) : MdSegment()
    /** rows[0] 为表头，其余为数据行 */
    data class Table(val rows: List<List<String>>) : MdSegment()
    /** 围栏代码块：lang 为语言标识（可为空），code 为代码内容 */
    data class Code(val lang: String, val code: String) : MdSegment()
}

/** GFM 表格分隔行：| :--- | ---: | 之类（仅含 | : - 空格 且至少一段 --） */
private fun isTableDivider(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty() || !t.contains('-')) return false
    if (!t.all { it == '|' || it == ':' || it == '-' || it == ' ' || it == '\t' }) return false
    return Regex("-{2,}").containsMatchIn(t)
}

/** 解析一行表格为单元格列表（剥首尾 |，处理 \| 转义） */
private fun parseTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
    return s.split('|').map { it.trim().replace("\\|", "|") }
}

/**
 * 把 markdown 拆成文本段/表格段序列。
 * 表格判定：当前行含 | 且下一行是分隔行（GFM 规则），随后的含 | 非空行都归入表格。
 */
internal fun splitMarkdownTables(markdown: String): List<MdSegment> {
    val out = mutableListOf<MdSegment>()
    val textBuf = StringBuilder()
    val lines = markdown.lines()

    fun flushText() {
        if (textBuf.isNotBlank()) out.add(MdSegment.Text(textBuf.toString().trimEnd('\n')))
        textBuf.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        if (line.contains('|') && next != null && isTableDivider(next)) {
            flushText()
            val rows = mutableListOf(parseTableRow(line)) // 表头
            i += 2 // 跳过表头与分隔行
            while (i < lines.size && lines[i].trim().isNotEmpty() &&
                (lines[i].contains('|') || lines[i].trimStart().startsWith("|"))) {
                rows.add(parseTableRow(lines[i]))
                i++
            }
            // 规整为相同列数，避免渲染越界
            val cols = rows.maxOf { it.size }.coerceAtLeast(2)
            out.add(MdSegment.Table(rows.map { r ->
                if (r.size >= cols) r else r + List(cols - r.size) { "" }
            }))
            continue
        }
        textBuf.append(line).append('\n')
        i++
    }
    flushText()
    return out
}

/**
 * 顶层拆分：先提取围栏代码块（代码块内可能包含表格样本文本，必须优先于表格提取），
 * 普通文本再交给 [splitMarkdownTables] 拆表格。
 */
internal fun splitMarkdownBlocks(markdown: String): List<MdSegment> {
    val out = mutableListOf<MdSegment>()
    val buf = StringBuilder()
    val lines = markdown.lines()

    fun flush() {
        if (buf.isNotBlank()) out.addAll(splitMarkdownTables(buf.toString().trimEnd('\n')))
        buf.clear()
    }

    var i = 0
    while (i < lines.size) {
        val t = lines[i].trimStart()
        val fenceChar = when {
            t.startsWith("```") -> '`'
            t.startsWith("~~~") -> '~'
            else -> null
        }
        if (fenceChar != null) {
            flush()
            val lang = t.drop(3).trim()
            val closePrefix = fenceChar.toString().repeat(3)
            i++
            val code = StringBuilder()
            while (i < lines.size && !lines[i].trimStart().startsWith(closePrefix)) {
                code.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // 跳过闭合围栏（未闭合则已到文件尾）
            out.add(MdSegment.Code(lang, code.toString().trimEnd('\n')))
            continue
        }
        buf.append(lines[i]).append('\n')
        i++
    }
    flush()
    return out
}

/** 去掉单元格内的常见行内 markdown 标记（粗体/斜体/行内代码/链接只留文本） */
private fun cleanInlineMd(s: String): String = s
    .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
    .replace(Regex("""\*([^*]+)\*"""), "$1")
    .replace(Regex("""`([^`]+)`"""), "$1")
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")

/** Compose 自绘表格：自适应行高（内容换行不会溢出）、横向滚动（宽表格）、行分隔线 */
@Composable
private fun MarkdownTableBlock(
    rows: List<List<String>>,
    textColor: Color,
    linkColor: Color
) {
    if (rows.isEmpty()) return
    val header = rows.first()
    val body = rows.drop(1)
    val divider = textColor.copy(alpha = 0.2f)
    val cellPad = 10.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, divider, RoundedCornerShape(10.dp))
    ) {
        // 表头
        Row {
            header.forEach { cell ->
                Text(
                    text = cleanInlineMd(cell),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .widthIn(min = 104.dp)
                        .padding(horizontal = cellPad, vertical = 8.dp)
                )
            }
        }
        HorizontalDivider(color = divider, thickness = 1.dp)
        // 数据行
        body.forEachIndexed { ri, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.forEach { cell ->
                    AutoLinkText(
                        text = cleanInlineMd(cell),
                        textColor = textColor,
                        linkColor = linkColor,
                        modifier = Modifier
                            .widthIn(min = 104.dp)
                            .padding(horizontal = cellPad, vertical = 7.dp)
                    )
                }
            }
            if (ri < body.lastIndex) HorizontalDivider(color = divider.copy(alpha = 0.6f), thickness = 0.7.dp)
        }
    }
}

// ---------- 代码块：Compose 卡片（语言标签 / 一键复制 / prism4j 语法高亮） ----------

/** 一套代码高亮配色（随气泡文字明暗切换） */
private data class CodePalette(
    val keyword: Color, val string: Color, val number: Color,
    val comment: Color, val function: Color, val type: Color, val operator: Color
)

private val LIGHT_CODE = CodePalette(
    keyword = Color(0xFF7C4DFF), string = Color(0xFF2E7D32), number = Color(0xFFB25000),
    comment = Color(0xFF9E9E9E), function = Color(0xFF0B7285), type = Color(0xFF00695C),
    operator = Color(0xFF5D4037)
)

private val DARK_CODE = CodePalette(
    keyword = Color(0xFFC792EA), string = Color(0xFFC3E88D), number = Color(0xFFF78C6C),
    comment = Color(0xFF8B949E), function = Color(0xFF82AAFF), type = Color(0xFF4EC9B0),
    operator = Color(0xFF89DDFF)
)

/** prism4j 零依赖替代：轻量正则高亮（注释/字符串/数字/关键字/注解/函数调用） */
private val CODE_KEYWORDS = setOf(
    "abstract", "as", "assert", "async", "await", "begin", "bool", "boolean", "break",
    "byte", "case", "catch", "char", "class", "const", "constructor", "continue",
    "debugger", "def", "default", "del", "delete", "do", "double", "echo", "elif", "else",
    "end", "enum", "except", "extends", "false", "final", "finally", "float", "fn", "for",
    "foreach", "from", "func", "function", "global", "go", "if", "impl", "implements",
    "import", "in", "instanceof", "int", "interface", "internal", "is", "lambda", "let",
    "local", "long", "match", "module", "mut", "namespace", "new", "nil", "none", "nonlocal",
    "not", "null", "open", "operator", "or", "override", "package", "pass", "private",
    "protected", "pub", "public", "raise", "readonly", "record", "require", "return",
    "sealed", "self", "short", "signed", "static", "string", "struct", "super", "switch",
    "synchronized", "template", "then", "this", "throw", "throws", "trait", "transient",
    "true", "try", "type", "typedef", "typeof", "union", "unsigned", "use", "using",
    "val", "var", "virtual", "void", "when", "where", "while", "with", "yield"
)

private val HASH_COMMENT_LANGS = setOf(
    "python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "ruby", "rb",
    "makefile", "dockerfile", "toml", "r", "perl", "conf", "ini"
)

/** 分词正则：注释 | 字符串 | 数字 | 注解 | 关键字 | 函数调用（命名组判定类别） */
private fun buildCodeTokenRegex(hashComment: Boolean): Regex {
    val commentBranch = buildString {
        append("(?<!:)//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        if (hashComment) append("|#[^\\n]*")
    }
    return Regex(
        "(?<comment>$commentBranch)" +
            "|(?<string>\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\\\n])*\"|" +
            "'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`)" +
            "|(?<number>\\b(?:0[xXbBoO][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.[\\d_]+)?(?:[eE][+-]?\\d+)?)[fFlLdDuU]*\\b)" +
            "|(?<annotation>@[A-Za-z_]\\w*)" +
            "|(?<keyword>\\b(?:${CODE_KEYWORDS.sorted().joinToString("|")})\\b)" +
            "|(?<func>\\b[A-Za-z_]\\w*(?=\\s*\\())"
    )
}

private val CODE_TOKENS_PLAIN = buildCodeTokenRegex(false)
private val CODE_TOKENS_HASH = buildCodeTokenRegex(true)

/** 正则高亮为 AnnotatedString；无命中保持正文色 */
private fun highlightCode(code: String, lang: String, dark: Boolean): AnnotatedString {
    val palette = if (dark) DARK_CODE else LIGHT_CODE
    val regex = if (lang.lowercase() in HASH_COMMENT_LANGS) CODE_TOKENS_HASH else CODE_TOKENS_PLAIN
    return buildAnnotatedString {
        append(code)
        for (m in regex.findAll(code)) {
            val color = when {
                m.groups["comment"] != null -> palette.comment
                m.groups["string"] != null -> palette.string
                m.groups["number"] != null -> palette.number
                m.groups["annotation"] != null -> palette.type
                m.groups["keyword"] != null -> palette.keyword
                m.groups["func"] != null -> palette.function
                else -> null
            }
            if (color != null) addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
        }
    }
}

/** 现代代码卡片：顶部语言标签 + 复制按钮、等宽代码区、横向滚动、随气泡明暗配色 */
@Composable
private fun MarkdownCodeBlock(
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
    val dark = textColor.luminance() > 0.5f
    val bg = textColor.copy(alpha = 0.06f)
    val border = textColor.copy(alpha = 0.14f)
    val dim = textColor.copy(alpha = 0.65f)
    val highlighted = remember(code, lang, dark) { highlightCode(code, lang, dark) }

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
        // 代码区：等宽字体 + 横向滚动（长行不换行折断，滑出查看）
        Text(
            text = highlighted,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            ),
            color = textColor,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

// ---------- 行内 LaTeX 预处理：把单美元 $...$ 转为 Markwon 的 $$...$$ ----------
// Markwon 行内公式语法是 $$...$$，而模型通常输出 $...$；
// 仅当内容具备 LaTeX 特征（反斜杠命令/上下标/花括号/等号/frac）才转换，避免误伤货币金额。
private val SINGLE_DOLLAR_LATEX = Regex("""(?<!\$)\$(?!\$)(?!\s)([^\$\n]{1,200}?)\$(?!\$)""")

private fun preprocessLatex(markdown: String): String =
    SINGLE_DOLLAR_LATEX.replace(markdown) { m ->
        val inner = m.groupValues[1]
        val looksLikeLatex = inner.contains('\\') || inner.contains('^') || inner.contains('_') ||
            inner.contains('{') || inner.contains('=') || inner.contains("frac")
        if (looksLikeLatex) "\$\$$inner\$\$" else m.value
    }

// ---------- 纯文本消息：网址 / 邮箱 / 电话 自动识别为可点链接（纯 Compose 实现） ----------

private val LINKIFY_PATTERNS = listOf(
    // 网址（http/https/ftp 开头，或 www. 开头）
    Regex("""(?:https?|ftp)://[^\s<>"')\]]+|www\.[^\s<>"')\]]+""") to "url",
    // 邮箱
    Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "email",
    // 手机号（中国大陆 11 位）
    Regex("""1[3-9]\d{9}""") to "phone"
)

/**
 * 纯文本消息渲染：不做 Markdown 解析，仅把网址 / 邮箱 / 电话
 * 自动识别为可点链接（QQ 同款效果：网址主题色下划线、点击打开浏览器，
 * 邮箱拉起邮件客户端，电话拉起拨号盘）。
 *
 * 手势策略：单击命中链接注解打开链接；长按主动上报 [onLongClick] 弹消息菜单。
 * 自由复制模式下外层不传 onLongClick，长按交给外层 SelectionContainer 出选区手柄。
 */
@Composable
fun AutoLinkText(
    text: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    /** 长按文本（非链接处）回调（弹消息菜单）；自由复制模式下传 null 让位给外层选择手势 */
    onLongClick: (() -> Unit)? = null,
    /** 点击非链接区域回调（编辑重发 / 退出自由复制）；点链接仍优先打开链接 */
    onTextClick: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 构建带链接注解的 AnnotatedString，只在文本变化时重新扫描
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            val matches = mutableListOf<Triple<IntRange, String, String>>()
            for ((regex, type) in LINKIFY_PATTERNS) {
                for (m in regex.findAll(text)) {
                    matches.add(Triple(m.range, type, m.value))
                }
            }
            matches.sortBy { it.first.first }
            // 去重：跳过与已选区间重叠的匹配
            val selected = mutableListOf<Triple<IntRange, String, String>>()
            for (m in matches) {
                if (selected.none { it.first.overlaps(m.first) }) selected.add(m)
            }
            for ((range, type, raw) in selected) {
                val target = when (type) {
                    "url" -> if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
                    "email" -> "mailto:$raw"
                    "phone" -> "tel:$raw"
                    else -> raw
                }
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    range.first, range.last + 1
                )
                addStringAnnotation(tag = "link", annotation = target, start = range.first, end = range.last + 1)
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotated, onLongClick != null) {
            detectTapGestures(
                // detectTapGestures 会消费按压事件，外层气泡的 combinedClickable 收不到长按，
                // 因此长按必须在这里检测并主动上报
                onLongPress = { onLongClick?.invoke() },
                onTap = { pos ->
                    val offset = layoutResult?.getOffsetForPosition(pos)
                    val ann = offset?.let {
                        annotated.getStringAnnotations(tag = "link", start = it, end = it).firstOrNull()
                    }
                    if (ann == null) {
                        // 点击非链接区域：上抛给外层气泡（编辑重发 / 退出自由复制），
                        // 否则 detectTapGestures 吞掉点击后气泡永远收不到
                        onTextClick?.invoke()
                        return@detectTapGestures
                    }
                    when {
                        ann.item.startsWith("mailto:") -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                                data = android.net.Uri.parse(ann.item)
                            })
                        }
                        ann.item.startsWith("tel:") -> runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                                data = android.net.Uri.parse(ann.item)
                            })
                        }
                        else -> runCatching { uriHandler.openUri(ann.item) }
                    }
                }
            )
        }
    )
}

/** 判断两个 IntRange 是否重叠 */
private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last
