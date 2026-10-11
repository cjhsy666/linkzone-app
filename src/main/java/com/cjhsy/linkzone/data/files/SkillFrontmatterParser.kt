package com.cjhsy.linkzone.data.files

/**
 * SKILL.md frontmatter 解析器 —— 纯 Kotlin 轻量实现。
 *
 * 只需提取 `name` / `description` 两个标量字段（Agent Skills 规范），
 * 无需完整 YAML 能力：逐行匹配顶层 `key: value`，支持单/双引号与
 * 行尾注释；不支持多行块标量（技能名与描述均为单行文本）。
 */
object SkillFrontmatterParser {

    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    data class Frontmatter(val name: String?, val description: String?)

    fun parse(content: String): Frontmatter {
        if (!content.startsWith("---")) return Frontmatter(null, null)
        val end = frontmatterEndRegex.find(content, startIndex = 3) ?: return Frontmatter(null, null)
        val yaml = content.substring(3, end.range.first).trim()
        if (yaml.isEmpty()) return Frontmatter(null, null)
        return runCatching {
            var name: String? = null
            var description: String? = null
            yaml.lineSequence().forEach { rawLine ->
                val line = rawLine.takeWhile { it == ' ' || it == '\t' }.let { rawLine.removePrefix(it) }
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon).trim()
                val value = unquote(line.substring(colon + 1).trim())
                when (key) {
                    "name" -> if (name == null) name = value
                    "description" -> if (description == null) description = value
                }
            }
            Frontmatter(name, description)
        }.getOrDefault(Frontmatter(null, null))
    }

    /** 剥离 frontmatter，返回正文（无 frontmatter 时原样返回） */
    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val end = frontmatterEndRegex.find(content, startIndex = 3) ?: return content
        return content.substring(end.range.last + 1).trimStart('\r', '\n')
    }

    private fun unquote(value: String): String {
        val v = value.substringBefore(" #").trim()
        if (v.length >= 2) {
            val first = v.first()
            val last = v.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length - 1)
            }
        }
        return v
    }
}
