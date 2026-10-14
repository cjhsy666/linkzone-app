package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 脚注（intellij-markdown 不原生支持 → 源码预处理实现）。
 *
 * - `[^id]: 定义`（支持缩进续行）在解析前提取：被正文引用的定义块从正文移除，
 *   未被引用的定义块原文保留（内容不丢失）；
 * - 正文 `[^id]` 渲染为小号上标药丸，编号按「引用首次出现顺序」分配（同 id 复用）；
 * - 文末以细顶线分隔的脚注区按编号列条目；点击上标/条目双向滚动定位
 *   （各挂一个 [BringIntoViewRequester]，由 Compose 滚动祖先负责滚入视口，误差远小于 16dp）；
 * - 无定义的引用、畸形 `[^…` 原样渲染为原文。
 */

/** 脚注模型 */
internal class MdFootnotes(
    /** label(小写) → 定义文本 */
    val defs: Map<String, String>,
    /** label(小写) → 显示编号（1 起）；仅包含「正文引用过且存在定义」的 label */
    val numbers: Map<String, Int>
) {
    /** 按编号升序的 (编号, label, 定义文本) */
    val ordered: List<Triple<Int, String, String>> =
        numbers.entries.sortedBy { it.value }
            .map { Triple(it.value, it.key, defs[it.key].orEmpty()) }
}

/** 预处理产物：清理后的正文源（node 偏移基准）+ 脚注模型（无脚注为 null） */
internal class MdPreprocessed(val cleaned: String, val footnotes: MdFootnotes?)

/** 脚注定义行：`[^id]: 文本`（至多 3 空格缩进） */
private val footnoteDefRegex = Regex("""^ {0,3}\[\^([^\]\s]+)\]:[ \t]*(.*)$""")

/** 行内引用：`[^id]` */
internal val footnoteInlineRegex = Regex("""\[\^([^\]\s]+)\]""")

private class DefBlock(val start: Int, val end: Int, val label: String)

/**
 * 脚注预处理：提取定义块、按正文引用顺序编号、清理正文。
 * 无 `[^` 的文档直接原样返回（零开销）。
 */
internal fun preprocessFootnotes(markdown: String): MdPreprocessed {
    if (!markdown.contains("[^")) return MdPreprocessed(markdown, null)
    // 统一换行，避免 CRLF 干扰逐行判定
    val source = if (markdown.contains("\r\n")) markdown.replace("\r\n", "\n") else markdown
    val lines = source.split('\n')
    val defBlocks = ArrayList<DefBlock>()
    val defTexts = LinkedHashMap<String, String>()
    var inFence = false
    var fenceChar = '`'
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trimStart()
        // 围栏代码块内不做脚注定义识别（避免代码内容被吞）
        if (inFence) {
            val run = trimmed.takeWhile { it == fenceChar }.length
            if (run >= 3 && trimmed.drop(run).isBlank()) inFence = false
            i++
            continue
        }
        if (trimmed.length >= 3 && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
            inFence = true
            fenceChar = trimmed[0]
            i++
            continue
        }
        val m = footnoteDefRegex.matchEntire(lines[i])
        if (m != null) {
            val label = m.groupValues[1].lowercase()
            val sb = StringBuilder(m.groupValues[2])
            // 续行：紧邻的缩进行（4 空格 / 制表符）并入定义
            var j = i + 1
            while (j < lines.size && lines[j].isNotBlank() &&
                (lines[j].startsWith("    ") || lines[j].startsWith("\t"))
            ) {
                sb.append('\n').append(lines[j].trim())
                j++
            }
            defBlocks.add(DefBlock(i, j, label))
            if (!defTexts.containsKey(label)) defTexts[label] = sb.toString().trim()
            i = j
            continue
        }
        i++
    }
    if (defBlocks.isEmpty()) return MdPreprocessed(source, null)

    // 正文（去掉全部定义块）仅用于统计引用顺序，避免把定义内容里的引用计入编号
    val skip = BooleanArray(lines.size)
    defBlocks.forEach { b -> for (k in b.start until b.end) skip[k] = true }
    val bodyText = lines.filterIndexed { idx, _ -> !skip[idx] }.joinToString("\n")

    val numbers = LinkedHashMap<String, Int>()
    var n = 0
    for (m in footnoteInlineRegex.findAll(bodyText)) {
        val label = m.groupValues[1].lowercase()
        if (!numbers.containsKey(label) && defTexts.containsKey(label)) {
            n++
            numbers[label] = n
        }
    }

    // 清理正文：被引用的定义块移除；未被引用的定义块原文保留（安全降级）
    val cleanedLines = ArrayList<String>(lines.size)
    for (idx in lines.indices) {
        val b = defBlocks.firstOrNull { idx >= it.start && idx < it.end }
        if (b == null || !numbers.containsKey(b.label)) cleanedLines.add(lines[idx])
    }
    return MdPreprocessed(cleanedLines.joinToString("\n"), MdFootnotes(defTexts, numbers))
}

// ============================================================================
// 引用识别
// ============================================================================

/** 兄弟序列中的脚注引用命中：label + 消耗的节点数 */
internal class MdFootnoteRef(val label: String, val consumed: Int)

/**
 * 识别 `[^label]`：LBRACKET + TEXT("^label") + RBRACKET 三个连续 token。
 * 返回 null 表示当前位置不是脚注引用（调用方按原文渲染）。
 */
internal fun footnoteRefAt(nodes: List<ASTNode>, index: Int, src: String): MdFootnoteRef? {
    if (index + 2 >= nodes.size) return null
    if (nodes[index].type != MarkdownTokenTypes.LBRACKET) return null
    val mid = nodes[index + 1]
    if (mid.type != MarkdownTokenTypes.TEXT) return null
    if (nodes[index + 2].type != MarkdownTokenTypes.RBRACKET) return null
    val t = mid.nodeText(src)
    if (t.length < 2 || t[0] != '^') return null
    val label = t.substring(1).lowercase()
    if (label.isEmpty() || label.any { it == ']' || it == ' ' || it == '\t' || it == '\n' }) return null
    return MdFootnoteRef(label, 3)
}

// ============================================================================
// 双向定位锚点
// ============================================================================

/**
 * 脚注导航锚点：label → 引用处 / 脚注条目处的 [BringIntoViewRequester]。
 * 点击引用跳条目、点击条目回跳引用，由最近的滚动祖先（LazyColumn）完成滚入。
 */
internal class MdFootnoteNav {
    val refRequesters = HashMap<String, BringIntoViewRequester>()
    val entryRequesters = HashMap<String, BringIntoViewRequester>()
}

// ============================================================================
// 上标药丸 / 文末脚注区
// ============================================================================

/**
 * 脚注引用上标药丸（InlineTextContent 圆角 + 上标对齐）：点击滚动定位到文末脚注条目。
 * 同时在 [MdFootnoteNav] 登记自身 requester，供「条目 → 回跳引用」使用。
 */
internal fun appendFootnoteRef(
    label: String,
    number: Int,
    ctx: MdRenderContext,
    out: AnnotatedString.Builder,
    inlineContent: MutableList<Pair<String, InlineTextContent>>
) {
    val id = "fn_${inlineContent.size}"
    val num = number.toString()
    val padHPx = with(ctx.density) { 5.dp.toPx() }
    val wSp = ctx.measureInlineWidthSp(num, fontSize = 10, padHPx = padHPx)
    val nav = ctx.footnoteNav
    val scope = ctx.footnoteScope
    inlineContent.add(id to InlineTextContent(
        Placeholder(
            width = wSp.sp,
            height = 15.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline
        )
    ) {
        val requester = remember { BringIntoViewRequester() }
        SideEffect { nav?.refRequesters?.putIfAbsent(label, requester) }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ctx.palette.quoteBg)
                .bringIntoViewRequester(requester)
                .then(
                    if (nav != null && scope != null) Modifier.clickable {
                        val target = nav.entryRequesters[label]
                        if (target != null) scope.launch { target.bringIntoView() }
                    } else Modifier
                )
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = num,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = ctx.palette.link,
                maxLines = 1
            )
        }
    })
    out.appendInlineContent(id, "[^$label]")
}

/** 文末脚注区：细顶线分隔 + 按编号列条目；点击条目回跳正文引用处 */
@Composable
internal fun MdFootnoteSection(footnotes: MdFootnotes, ctx: MdRenderContext) {
    val ordered = footnotes.ordered
    if (ordered.isEmpty()) return
    val nav = ctx.footnoteNav
    val scope = ctx.footnoteScope
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MdSpace.blockGap)
    ) {
        HorizontalDivider(color = ctx.palette.divider, thickness = 0.8.dp)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ordered.forEach { (number, label, text) ->
                val requester = remember(label) { BringIntoViewRequester() }
                SideEffect { nav?.entryRequesters?.putIfAbsent(label, requester) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(requester)
                        .then(
                            if (nav != null && scope != null) Modifier.clickable {
                                val target = nav.refRequesters[label]
                                if (target != null) scope.launch { target.bringIntoView() }
                            } else Modifier
                        ),
                    verticalAlignment = Alignment.Top
                ) {
                    FootnoteNumberChip(number, ctx)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        MdFootnoteDefText(text, ctx)
                    }
                }
            }
        }
    }
}

@Composable
private fun FootnoteNumberChip(number: Int, ctx: MdRenderContext) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ctx.palette.quoteBg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            ),
            color = ctx.palette.link,
            maxLines = 1
        )
    }
}

/** 定义文本：走行内 AST 渲染（支持粗体/链接/行内代码），颜色取次要令牌 */
@Composable
private fun MdFootnoteDefText(text: String, ctx: MdRenderContext) {
    if (text.isBlank()) return
    val doc = rememberMarkdownAst(text, streaming = false)
    val para = doc?.ast?.children?.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH }
    if (para != null) {
        val defCtx = remember(ctx, text) { ctx.forSource(text, ctx.palette.dim) }
        MdInlineText(para, defCtx, style = mdFootnoteStyle)
    } else {
        Text(text, style = mdFootnoteStyle, color = ctx.palette.dim)
    }
}

private val mdFootnoteStyle: TextStyle
    @Composable get() = mdBodyStyle.copy(fontSize = 13.sp, lineHeight = 19.sp)
