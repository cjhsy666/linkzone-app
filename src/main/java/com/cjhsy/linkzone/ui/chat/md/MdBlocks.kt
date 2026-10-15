package com.cjhsy.linkzone.ui.chat.md

/**
 * 顶层块拆分（仅沿围栏代码块边界）：为块级解析缓存提供粒度。
 *
 * 只把围栏代码块（```/~~~）独立成块——流式期间代码内容增长不连累正文重解析。
 * 其余内容保持完整文档单次解析，避免破坏跨块结构：
 * 引用式链接/图片、跨空行的列表与引用块、HTML 块、setext 标题、缩进代码块。
 * 未闭合围栏（流式中）视为延伸至文末。
 */
internal fun splitTopLevelBlocks(markdown: String): List<String> {
    val out = mutableListOf<String>()
    val buf = StringBuilder()
    val lines = markdown.lines()

    fun flushText() {
        val s = buf.toString().trim()
        if (s.isNotEmpty()) out.add(s)
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
            flushText()
            val closePrefix = fenceChar.toString().repeat(3)
            val block = StringBuilder()
            block.append(lines[i]).append('\n')
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(closePrefix)) {
                block.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) {
                block.append(lines[i]) // 闭合围栏并入本块（未闭合则已到文末）
                i++
            }
            out.add(block.toString().trimEnd('\n'))
            continue
        }
        buf.append(lines[i]).append('\n')
        i++
    }
    flushText()

    if (out.size <= 1) return out
    // 引用式链接/图片/脚注定义常在文末，跨块引用会断：
    // 把全文定义行附加到每个非围栏块末尾（解析器忽略未使用的定义，定义行本身不渲染）
    val refDefs = REF_DEF_RE.findAll(markdown).map { it.value }.toList()
    if (refDefs.isEmpty()) return out
    val defsSuffix = "\n\n" + refDefs.joinToString("\n")
    return out.map { block ->
        if (isFenceBlock(block)) block else block + defsSuffix
    }
}

/** 链接引用定义：`[label]: destination` / 脚注 `[^note]: text` */
private val REF_DEF_RE = Regex("""(?m)^[ ]{0,3}\[[^\]\n]+\]:\s*\S+.*$""")

private fun isFenceBlock(block: String): Boolean {
    val t = block.trimStart()
    return t.startsWith("```") || t.startsWith("~~~")
}

/** 解析兜底：转义全部 markdown 特殊字符，使内容按纯文本渲染 */
private val MD_ESCAPABLE = Regex("""([\\`*_{}\[\]()#+\-.!>|~])""")

internal fun escapeMarkdown(text: String): String =
    MD_ESCAPABLE.replace(text) { "\\" + it.value }
