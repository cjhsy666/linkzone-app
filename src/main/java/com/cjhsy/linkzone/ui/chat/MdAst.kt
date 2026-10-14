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

/** 表格模型：header 与 rows 均为 CELL 节点列表（内容走行内渲染） */
internal class MdTable(
    val header: List<ASTNode>,
    val rows: List<List<ASTNode>>
)

internal fun ASTNode.tableModel(): MdTable? {
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
    // 规整列数，避免渲染越界
    val cols = (listOf(h.size) + rows.map { it.size }).max()
    fun pad(r: List<ASTNode>) = if (r.size >= cols) r else r // 缺列渲染为空串由渲染层兜底
    return MdTable(pad(h), rows.map { pad(it) })
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
