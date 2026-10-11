package com.cjhsy.linkzone.data.model

import com.cjhsy.linkzone.data.files.SkillFrontmatterParser
import kotlinx.serialization.Serializable

/**
 * LLM Skill —— 移植自 RikkaHub（Agent Skills 规范）。
 *
 * 标准 skill 包是多文件的：根目录 SKILL.md（YAML frontmatter 含 name/description）+
 * scripts/references/assets 等子文件，模型读 SKILL.md 后按需用 use_skill 的 path 参数
 * 加载单个子文件。文件实体存储在 filesDir/skills/<name>/（SkillFileStore，磁盘为唯一
 * 事实源，可被代码工作区挂载执行脚本）；Room 仅保留 id/name 等元数据索引。
 * [files] 为读取时从磁盘水合的「相对路径 → 文本内容」映射（二进制资产不进入此映射）。
 *
 * [content] 保存完整 SKILL.md 文本（含 frontmatter）；name/description
 * 从 frontmatter 解析并与手填字段解耦：编辑器保存时以 frontmatter 为准。
 */
@Serializable
data class LlmSkill(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 技能名（frontmatter.name），use_skill 调用时模型引用的标识 */
    val name: String = "",
    val description: String = "",
    /** 完整 SKILL.md 文本 */
    val content: String = "",
    /**
     * 子文件映射：相对路径（如 "scripts/parse.py"、"references/api.md"）→ 文本内容。
     * 路径规范化：统一 '/' 分隔、禁止 '..' 越界、不允许以 '/' 开头。
     */
    val files: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /** frontmatter 之后的正文（use_skill 执行时注入给模型） */
    val body: String get() = SkillFrontmatterParser.extractBody(content)

    /** 读取子文件（路径做规范化与越界校验）；不存在返回 null */
    fun readFile(path: String): String? = files[normalizePath(path)]

    companion object {
        /** 由编辑器内容解析构建；frontmatter 缺失时返回 null（name/description 必填） */
        fun fromContent(content: String): LlmSkill? {
            val fm = SkillFrontmatterParser.parse(content)
            val name = fm.name?.takeIf { it.isNotBlank() } ?: return null
            val description = fm.description?.takeIf { it.isNotBlank() } ?: return null
            return LlmSkill(name = name, description = description, content = content)
        }

        /**
         * 规范化 skill 内相对路径：统一 '/'、去前导 '/'、解析 '.'/'..'，越界返回 null。
         */
        fun normalizePath(raw: String): String? {
            val parts = raw.replace('\\', '/').trim().split('/').filter { it.isNotEmpty() && it != "." }
            val resolved = mutableListOf<String>()
            for (p in parts) {
                if (p == "..") {
                    if (resolved.isEmpty()) return null
                    resolved.removeAt(resolved.size - 1)
                } else {
                    resolved += p
                }
            }
            return resolved.joinToString("/").ifBlank { null }
        }
    }
}

/**
 * 快捷指令 —— 移植自 RikkaHub QuickMessage：点击后把 [content] 填入聊天输入框。
 */
@Serializable
data class LlmQuickMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
