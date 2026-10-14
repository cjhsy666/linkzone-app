package com.cjhsy.linkzone.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * Markdown 富渲染后台管线。
 *
 * 目标（NFR-1 / AC-3 / AC-4）：
 * - AST 解析、Prism 语法高亮、LaTeX 位图生成全部移出主线程（Dispatchers.Default），
 *   composition 内不存在同步重计算；
 * - AST 走全局 LRU（源文本 → ASTNode，64 条），历史消息滚出再滚回不重复解析；
 * - 流式（streaming=true）：半截 AST 不写全局 LRU（不挤占历史缓存），
 *   富渲染按 ~120ms 节流合并，内容静默时不重复重算；停止后一次性最终富渲染并落缓存；
 * - 解析/高亮工作挂在 produceState（结构化 Job）上，消息离开组合或被 Stop 打断时
 *   协程立即 cancel，取消按正常路径处理，不写错误态。
 */

// ============================================================================
// AST 全局 LRU
// ============================================================================

private const val AST_CACHE_MAX = 64
internal const val STREAM_THROTTLE_MS = 120L

/** 解析产物：AST + 预处理后的正文源（node 偏移以该源为准）+ 脚注模型（无脚注为 null） */
internal class MdParsed(
    val ast: ASTNode,
    val cleaned: String,
    val footnotes: MdFootnotes?
)

// 手动 LRU（accessOrder=true）：避免依赖 LinkedHashMap.removeEldestEntry 覆写签名
private val astCache = java.util.LinkedHashMap<String, MdParsed>(16, 0.75f, true)

private fun astCachePut(key: String, value: MdParsed) {
    synchronized(astCache) {
        astCache[key] = value
        while (astCache.size > AST_CACHE_MAX) {
            val it = astCache.keys.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }
}

private fun peekAst(markdown: String): MdParsed? = synchronized(astCache) { astCache[markdown] }

/**
 * 解析文档（后台可调用）：先做脚注预处理（提取定义、清理正文），再解析 AST。
 * 缓存 key 用原始 markdown（同内容不重复预处理/解析）；返回 null 表示解析失败（绝不抛出）。
 */
private fun parseDoc(markdown: String, store: Boolean): MdParsed? {
    if (store) {
        synchronized(astCache) { astCache[markdown]?.let { return it } }
    }
    val prepared = runCatching { preprocessFootnotes(markdown) }.getOrNull() ?: return null
    val ast = runCatching { parseMarkdownAst(prepared.cleaned) }.getOrNull() ?: return null
    val parsed = MdParsed(ast, prepared.cleaned, prepared.footnotes)
    if (store) astCachePut(markdown, parsed)
    return parsed
}

/**
 * 按内容解析 AST（后台）：命中 LRU 立即返回，未就绪返回 null，
 * 入口以同版式纯文本兜底（TR-2.2）。解析异常返回 null，绝不抛出。
 *
 * @param streaming 流式中的消息：节流刷新且半截 AST 不写 LRU
 */
@Composable
internal fun rememberMarkdownAst(markdown: String, streaming: Boolean): MdParsed? {
    if (!streaming) {
        val ast by produceState<MdParsed?>(
            initialValue = peekAst(markdown),
            key1 = markdown
        ) {
            value = withContext(Dispatchers.Default) { parseDoc(markdown, store = true) }
        }
        return ast
    }
    // 流式：内容每次变化都读最新值，但按 STREAM_THROTTLE_MS 节流重算；
    // 内容静默时不重复解析；streaming 结束（分支切换）时本协程被取消。
    val latest by rememberUpdatedState(markdown)
    val ast by produceState<MdParsed?>(
        initialValue = null,
        key1 = true
    ) {
        var lastSrc: String? = null
        while (true) {
            val src = latest
            if (src != lastSrc) {
                lastSrc = src
                val parsed = withContext(Dispatchers.Default) { parseDoc(src, store = false) }
                if (parsed != null) value = parsed
            }
            delay(STREAM_THROTTLE_MS)
        }
    }
    return ast
}

// ============================================================================
// Prism 语法高亮缓存（key 含 palette/主题维度，主题切换不串色）
// ============================================================================

private data class PrismKey(val code: String, val lang: String, val palette: MdCodePalette)

private const val CODE_CACHE_MAX = 48
private val codeCache = java.util.LinkedHashMap<PrismKey, AnnotatedString>(16, 0.75f, true)

private fun highlightCached(code: String, lang: String, palette: MdCodePalette): AnnotatedString? {
    val key = PrismKey(code, lang, palette)
    synchronized(codeCache) { codeCache[key]?.let { return it } }
    val result = highlightWithPrism(code, lang, palette) ?: return null
    synchronized(codeCache) {
        codeCache[key] = result
        while (codeCache.size > CODE_CACHE_MAX) {
            val it = codeCache.keys.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }
    return result
}

/**
 * 代码高亮后台化 + 缓存。streaming 期间加 STREAM_THROTTLE_MS 防抖：
 * 半截代码按 STREAM_THROTTLE_MS 节流重高亮（未就绪时调用方以纯等宽兜底），停止后得到最终高亮。
 * 返回 null 表示未就绪 / 未知语言 / 超长，调用方按正文色纯文本渲染。
 */
@Composable
internal fun rememberHighlightedCode(
    code: String,
    lang: String,
    palette: MdCodePalette,
    streaming: Boolean
): AnnotatedString? {
    val key = remember(code, lang, palette) { PrismKey(code, lang, palette) }
    val result by produceState<AnnotatedString?>(
        initialValue = synchronized(codeCache) { codeCache[key] },
        key1 = key,
        key2 = streaming
    ) {
        if (streaming) delay(STREAM_THROTTLE_MS)
        value = withContext(Dispatchers.Default) { highlightCached(code, lang, palette) }
    }
    return result
}

// ============================================================================
// 行内药丸 / 键帽宽度预算（主线程 TextMeasurer 实测，后台构建零竞态）
// ============================================================================

/** 行内药丸/键帽宽度缓存 key：字号区分代码药丸(13) 与键帽(12) */
internal fun pillWidthKey(fontSizeSp: Int, text: String): String = "$fontSizeSp|$text"

/**
 * 遍历 AST，为主线程预算所有行内代码药丸 / `<kbd>` 键帽 / 脚注上标药丸的实测宽度。
 * TextMeasurer 非线程安全，故测量只在主线程做一次，后台构建期直接查表（TR-2.1/TR-4.1）。
 */
internal fun collectPillWidths(
    root: ASTNode,
    src: String,
    density: Density,
    measurer: TextMeasurer,
    footnotes: MdFootnotes? = null
): Map<String, Float> {
    val out = HashMap<String, Float>()
    // 仅当文档真的含 <kbd> 时才预算键帽宽度（绝大多数消息无 kbd，零额外成本）
    val hasKbd = src.contains("<kbd>", ignoreCase = true)
    val budget = PillBudget(out, src, density, measurer, footnotes, hasKbd)
    pillBudgetList(budget, listOf(root))
    return out
}

/** 药丸宽度预算上下文（顶层互递归遍历用；局部函数无法前向引用/互递归） */
private class PillBudget(
    val out: HashMap<String, Float>,
    val src: String,
    val density: Density,
    val measurer: TextMeasurer,
    val footnotes: MdFootnotes?,
    val hasKbd: Boolean
)

private fun pillBudgetFootnote(c: PillBudget, number: Int) {
    val text = number.toString()
    c.out[pillWidthKey(10, text)] = measurePillSp(c.measurer, c.density, text, 10, 5f)
}

/** 兄弟序列扫描：LBRACKET + TEXT("^id") + RBRACKET（脚注引用） */
private fun pillBudgetList(c: PillBudget, list: List<ASTNode>) {
    var i = 0
    while (i < list.size) {
        if (c.footnotes != null) {
            val ref = footnoteRefAt(list, i, c.src)
            val num = ref?.let { c.footnotes.numbers[it.label] }
            if (ref != null && num != null) {
                pillBudgetFootnote(c, num)
                i += ref.consumed
                continue
            }
            // 兜底：整段引用落在单个 TEXT token 内
            val n = list[i]
            if (n.type == MarkdownTokenTypes.TEXT) {
                val t = n.nodeText(c.src)
                if (t.contains("[^")) {
                    for (m in footnoteInlineRegex.findAll(t)) {
                        c.footnotes.numbers[m.groupValues[1].lowercase()]?.let { pillBudgetFootnote(c, it) }
                    }
                }
            }
        }
        pillBudgetNode(c, list[i])
        i++
    }
}

private fun pillBudgetNode(c: PillBudget, n: ASTNode) {
    when (n.type) {
        MarkdownElementTypes.CODE_SPAN -> {
            val code = codeSpanText(n, c.src)
            if (code.isNotEmpty() && code.length <= 24) {
                c.out[pillWidthKey(13, code)] =
                    measurePillSp(c.measurer, c.density, code, 13, MdSpace.inlineCodePadH.value)
            }
            return // 代码内不进兄弟扫描
        }
        // 行内容器/叶子：键帽候选（纯文本 token 预测量，成本极低）
        MarkdownTokenTypes.TEXT -> {
            if (c.hasKbd) {
                val text = n.nodeText(c.src)
                if (text.isNotBlank()) {
                    c.out[pillWidthKey(12, text)] = measurePillSp(c.measurer, c.density, text, 12, 7f)
                }
            }
        }
        GFMElementTypes.INLINE_MATH, MarkdownElementTypes.IMAGE -> return
        else -> {}
    }
    pillBudgetList(c, n.children)
}

private fun measurePillSp(
    measurer: TextMeasurer,
    density: Density,
    text: String,
    fontSizeSp: Int,
    padDp: Float
): Float {
    val padPx = with(density) { padDp.dp.toPx() }
    val widthPx = runCatching {
        measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp),
            constraints = Constraints()
        ).size.width
    }.getOrNull() ?: (text.length * fontSizeSp * 0.6f).toInt()
    return with(density) { pxToPlaceholderSp(widthPx + padPx * 2f) }
}

// ============================================================================
// 行内富文本构建（后台）：AnnotatedString + InlineTextContent
// ============================================================================

/**
 * 后台构建段落行内产物（含行内 LaTeX 位图 —— 位图生成在 Default 线程，NFR-1）。
 * 未就绪返回 null，调用方以同版式纯文本兜底。
 *
 * 采用「常驻 holder + LaunchedEffect」：key 变化时保留上一产物直到新产物就绪，
 * 避免流式每 ~120ms 重算时的整段闪空（TR-2.4 跟手度）。
 * 消息离开组合或 key 变化时协程立即 cancel（TR-2.5）。
 *
 * @param cacheKey 稳定身份（ASTNode 身份），避免 subList/filter 生成的新列表导致反复重算
 */
@Composable
internal fun rememberInlineBuild(
    nodes: List<ASTNode>,
    ctx: MdRenderContext,
    cacheKey: Any?
): Pair<AnnotatedString, Map<String, InlineTextContent>>? {
    val holder = remember {
        androidx.compose.runtime.mutableStateOf<Pair<AnnotatedString, Map<String, InlineTextContent>>?>(null)
    }
    val latestNodes by rememberUpdatedState(nodes)
    LaunchedEffect(cacheKey, ctx.palette, ctx.src) {
        val built = withContext(Dispatchers.Default) {
            val inlineContent = mutableListOf<Pair<String, InlineTextContent>>()
            val annotated = androidx.compose.ui.text.buildAnnotatedString {
                buildInlineNodes(latestNodes, ctx, this, inlineContent)
            }
            annotated to inlineContent.toMap()
        }
        holder.value = built
    }
    return holder.value
}

/** 未就绪兜底：拼接原始文本，与富渲染同版式（15sp/1.7），替换时无跳变 */
internal fun plainTextOf(nodes: List<ASTNode>, src: String): String =
    nodes.joinToString(separator = "") { it.nodeText(src) }.ifBlank { " " }
