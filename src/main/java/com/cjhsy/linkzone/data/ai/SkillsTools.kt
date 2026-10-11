package com.cjhsy.linkzone.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.model.LlmSkill

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

/**
 * use_skill 工具 —— 移植自 RikkaHub SkillsTools，支持标准多文件 skill 包。
 *
 * 注入系统提示列出可用技能索引（name + description + 子文件列表，XML 转义防注入），
 * 模型先加载 SKILL.md 正文，再按其中出现的链接用 path 参数按需读取单个子文件。
 */
fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<LlmSkill>,
): List<Tool> {
    // enabledSkills 存的是技能 id（AgentEditor 多选 chips 传 id）
    val available = allSkills.filter { it.id in enabledSkills }
    if (available.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
                The default response is the skill's SKILL.md instructions. If the instructions
                reference additional files, call again with the 'path' parameter to read them.
            """.trimIndent().replace("\n", " "),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    available.forEach { skill ->
                        appendLine("  <skill>")
                        // 技能可能来自第三方导入，转义并限长，防止闭合标签注入任意系统提示
                        appendLine("    <name>${skill.name.escapeXml()}</name>")
                        appendLine("    <description>${skill.description.take(MAX_SKILL_DESCRIPTION_LENGTH).escapeXml()}</description>")
                        if (skill.files.isNotEmpty()) {
                            appendLine("    <files>")
                            skill.files.keys.sorted().forEach { path ->
                                appendLine("      <file>${path.escapeXml()}</file>")
                            }
                            appendLine("    </files>")
                        }
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill. Omit to read the default SKILL.md instructions. Only use paths listed in the skill's <files> index. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                // 模型可能照抄系统提示中转义后的名称，两种形式都接受
                val skill = available.firstOrNull { s -> s.name == name || s.name.escapeXml() == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content?.takeIf { p -> p.isNotBlank() }
                val content = if (path == null) {
                    skill.body.ifBlank { error("Skill '$name' has empty instructions") }
                } else {
                    skill.readFile(path)
                        ?: error(
                            "File '$path' not found in skill '$name'. Available files: " +
                                skill.files.keys.joinToString(", ")
                        )
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
