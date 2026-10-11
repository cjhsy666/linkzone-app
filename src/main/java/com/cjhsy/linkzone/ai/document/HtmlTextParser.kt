package com.cjhsy.linkzone.ai.document

import java.io.File

/**
 * HTML 极简解析：剥离脚本/样式/标签，保留标题、列表、段落的换行结构，
 * 解码常见实体。不引入 Jsoup，满足「喂给模型」场景即可。
 */
object HtmlTextParser {

    private val skipBlocks = Regex("(?is)<(script|style|head|noscript)\\b.*?</\\1>")
    private val breakTags = Regex(
        "(?i)</?(p|div|br|h[1-6]|li|tr|section|article|header|footer|blockquote|hr|table|ul|ol)[^>]*>"
    )
    private val allTags = Regex("(?s)<[^>]+>")
    private val spaces = Regex("[ \\t]+")
    private val blankLines = Regex("\\n{3,}")

    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&hellip;" to "…", "&mdash;" to "—", "&ndash;" to "–",
        "&copy;" to "©", "&reg;" to "®", "&trade;" to "™",
        "&times;" to "×", "&divide;" to "÷", "&nbsp" to " "
    )

    fun parse(file: File): String {
        val raw = file.readText(Charsets.UTF_8)
        return parse(raw)
    }

    fun parse(html: String): String {
        val text = html
            .replace(skipBlocks, "")
            .replace(breakTags, "\n")
            .replace(allTags, "")
        return entities.entries.fold(text) { acc, (k, v) -> acc.replace(k, v) }
            .lines()
            .joinToString("\n") { it.replace(spaces, " ").trim() }
            .replace(blankLines, "\n\n")
            .trim()
    }
}
