package com.cjhsy.linkzone.ui.chat

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * intellij-markdown AST 解析与提取工具。
 * 解析器对半截输入（流式未闭合围栏/定界符）天然容错，无需特判。
 */

private val parser by lazy { MarkdownParser(GFMFlavourDescriptor()) }

internal fun parseMarkdownAst(markdown: String): ASTNode =
    parser.buildMarkdownTreeFromString(markdown)

internal fun ASTNode.nodeText(src: String): String = getTextInNode(src).toString()

// ---------- 围栏代码块 ----------

internal fun ASTNode.fenceLang(src: String): String =
    findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.nodeText(src)?.trim().orEmpty()

/** 拼接 CODE_FENCE_CONTENT + EOL；未闭合围栏（流式中）自然截断 */
internal fun ASTNode.fenceCode(src: String): String {
    val sb = StringBuilder()
    for (child in children) {
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> sb.append(child.nodeText(src))
            MarkdownTokenTypes.EOL -> sb.append('\n')
            else -> {}
        }
    }
    return sb.toString().trimEnd('\n')
}

// ---------- 表格 ----------

internal enum class MdColumnAlign { LEFT, CENTER, RIGHT }

/** 表格模型：header 与 rows 均为 CELL 节点列表（内容走行内渲染）；alignments 为 GFM 分隔行列对齐 */
internal class MdTable(
    val header: List<ASTNode>,
    val rows: List<List<ASTNode>>,
    val alignments: List<MdColumnAlign>
)

/** 从分隔行源码（形如 `|:---:|:---|---:|`）解析每列对齐；无法判定默认左对齐 */
private fun parseAlignments(separatorLine: String): List<MdColumnAlign> {
    // 去掉首尾管道后按 | 切分；无管道的简单写法按空白切分兜底
    val cells = if (separatorLine.contains('|')) {
        separatorLine.trim().trimStart('|').trimEnd('|').split('|')
    } else {
        separatorLine.trim().split(Regex("\\s+"))
    }
    return cells.map { raw ->
        val c = raw.trim()
        val left = c.startsWith(':')
        val right = c.endsWith(':')
        when {
            left && right -> MdColumnAlign.CENTER
            right -> MdColumnAlign.RIGHT
            else -> MdColumnAlign.LEFT
        }
    }
}

internal fun ASTNode.tableModel(src: String): MdTable? {
    if (type != GFMElementTypes.TABLE) return null
    var header: List<ASTNode>? = null
    val rows = mutableListOf<List<ASTNode>>()
    for (child in children) {
        when (child.type) {
            GFMElementTypes.HEADER ->
                header = child.children.filter { it.type == GFMTokenTypes.CELL }
            GFMElementTypes.ROW -> {
                val cells = child.children.filter { it.type == GFMTokenTypes.CELL }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            else -> {}
        }
    }
    val h = header ?: return null
    if (h.isEmpty()) return null

    // 对齐信息不暴露在 AST 节点上，从源码分隔行（表头之后的第二行）自行解析
    val alignments = run {
        val secondLine = nodeText(src).lineSequence().drop(1).firstOrNull().orEmpty()
        val parsed = parseAlignments(secondLine)
        if (parsed.size == h.size) parsed
        else List(h.size) { MdColumnAlign.LEFT }
    }

    return MdTable(h, rows, alignments)
}

// ---------- 链接定义（引用式链接 [text][label]） ----------

/** 预扫描整棵树的 LINK_DEFINITION，建立 label(小写) → (destination, title) 映射 */
internal fun collectLinkDefinitions(root: ASTNode, src: String): Map<String, Pair<String, String?>> {
    val out = mutableMapOf<String, Pair<String, String?>>()
    fun visit(n: ASTNode) {
        if (n.type == MarkdownElementTypes.LINK_DEFINITION) {
            val label = n.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                ?.nodeText(src)?.removeSurrounding("[", "]")?.trim()?.lowercase()
            val dest = n.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                ?.nodeText(src)?.trim()
            val title = n.findChildOfType(MarkdownElementTypes.LINK_TITLE)
                ?.nodeText(src)?.trim()
            if (!label.isNullOrEmpty() && !dest.isNullOrEmpty()) {
                out[label] = dest to title
            }
            return
        }
        n.children.forEach { visit(it) }
    }
    visit(root)
    return out
}

// ---------- 任务列表 ----------

/** LIST_ITEM 内的 CHECK_BOX 节点文本为 "[ ]" / "[x]"；null 表示非任务项 */
internal fun ASTNode.taskChecked(src: String): Boolean? {
    val cb = findChildOfType(GFMTokenTypes.CHECK_BOX) ?: return null
    val t = cb.nodeText(src)
    return t.length >= 3 && t[1] != ' '
}

// ---------- 标题锚点（TOC 预留，Task 3 / Task 10） ----------

/** 文档顺序的标题条目：index（0 起，消息内顺序）/ level（1–6）/ 纯文本 */
data class MdHeadingEntry(val index: Int, val level: Int, val text: String)

/**
 * 按文档顺序收集 h1–h6 标题（ATX / SETEXT），返回「(标题节点, 条目)」对。
 * 节点身份用于渲染期回填锚点（onGloballyPositioned 偏移），条目供 TOC 列表展示。
 * 流式中每次 AST 更新都会重新收集，标题随生成增长实时可见（Task 10）。
 */
internal fun collectHeadingEntries(root: ASTNode, src: String): List<Pair<ASTNode, MdHeadingEntry>> {
    val out = mutableListOf<Pair<ASTNode, MdHeadingEntry>>()
    fun visit(n: ASTNode) {
        val level = when (n.type) {
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
            MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            MarkdownElementTypes.ATX_6 -> 6
            else -> 0
        }
        if (level > 0) {
            val content = n.children.firstOrNull {
                it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
            }
            val text = content?.nodeText(src)?.trim().orEmpty()
            out.add(n to MdHeadingEntry(out.size, level, text))
            return // 标题内不再嵌套标题
        }
        n.children.forEach { visit(it) }
    }
    visit(root)
    return out
}

// ---------- LaTeX 启发式判定（避免货币 $5 误判为公式） ----------

internal fun looksLikeLatex(inner: String): Boolean =
    inner.contains('\\') || inner.contains('^') || inner.contains('_') ||
        inner.contains('{') || inner.contains('=') || inner.contains("frac")

/** INLINE_MATH / BLOCK_MATH 节点文本含 $ 定界符，剥离之 */
internal fun ASTNode.mathContent(src: String): String {
    val raw = nodeText(src).trim()
    return when {
        raw.startsWith("$$") && raw.endsWith("$$") && raw.length >= 4 ->
            raw.substring(2, raw.length - 2).trim()
        raw.startsWith("$") && raw.endsWith("$") && raw.length >= 2 ->
            raw.substring(1, raw.length - 1).trim()
        else -> raw
    }
}
