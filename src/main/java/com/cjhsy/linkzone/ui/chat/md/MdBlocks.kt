package com.cjhsy.linkzone.ui.chat.md

/**
 * 顶层块拆分：为块级解析缓存提供粒度。
 * 围栏代码块（```/~~~，未闭合视为延伸至文末）单独成块——代码内可能有空行，
 * 绝不能按空行切开；其余内容按空行分段（段落/标题/列表/引用/表格天然各自成块）。
 */
internal fun splitTopLevelBlocks(markdown: String): List<String> {
    val out = mutableListOf<String>()
    val buf = StringBuilder()
    val lines = markdown.lines()

    fun flush() {
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
            flush()
            val closePrefix = fenceChar.toString().repeat(3)
            buf.append(lines[i]).append('\n')
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(closePrefix)) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) {
                buf.append(lines[i]).append('\n') // 闭合围栏并入本块（未闭合则已到文末）
                i++
            }
            flush()
            continue
        }
        if (lines[i].isBlank()) {
            flush()
            i++
            continue
        }
        buf.append(lines[i]).append('\n')
        i++
    }
    flush()
    return out
}

/** 解析兜底：转义全部 markdown 特殊字符，使内容按纯文本渲染 */
private val MD_ESCAPABLE = Regex("""([\\`*_{}\[\]()#+\-.!>|~])""")

internal fun escapeMarkdown(text: String): String =
    MD_ESCAPABLE.replace(text) { "\\" + it.value }
