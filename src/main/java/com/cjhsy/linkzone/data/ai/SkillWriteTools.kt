package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.model.LlmSkill
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 让模型在对话中创建 / 优化本地 skill（对齐 TRAE skill-creator 的对话驱动体验）。
 *
 * - [createSkillTool]：新建技能，content 为完整 SKILL.md（含 frontmatter），files 为子文件映射
 * - [updateSkillTool]：按名称定位已有技能并整体更新 content / description / files
 *
 * 写入统一走 LlmRepository.upsertSkill（磁盘 + Room 同步），保证 use_skill 下次构建工具时能识别。
 * 新建技能默认不在任何智能体的启用列表中，用户需在智能体设置里勾选后才会出现在 use_skill 索引。
 */
fun createSkillWriteTools(
    getSkills: suspend () -> List<LlmSkill>,
    upsertSkill: suspend (LlmSkill) -> Unit,
): List<Tool> = listOf(
    createSkillTool(getSkills, upsertSkill),
    updateSkillTool(getSkills, upsertSkill),
)

/** 注入给模型的 skill 创作工作流指令（仅在工具可用时出现） */
private val SKILL_AUTHORING_GUIDE = """
**Skill authoring**
You can create and improve local skills for the user directly from this conversation.

When to act:
- Use create_skill when the user asks to build a NEW skill, tool, capability, workflow, or reusable instruction set.
- Use update_skill when the user asks to modify, fix, optimize, or rewrite an EXISTING skill (first locate it by name).

SKILL.md format (pass as the `content` argument, complete text including frontmatter):
```
---
name: <kebab-case-short-name>
description: <one sentence: when to use this skill>
---

<When to invoke>
<What it does>
<Concrete steps / rules to follow>
```
Rules:
- `name`: short kebab-case, 2-4 words, no spaces. It is the identifier the model uses with use_skill.
- `description`: one concise sentence describing WHEN to use, max ~200 chars.
- Body: actionable and specific, no placeholders, no "TBD". Keep it focused; split large logic into child files.
- Child files: put scripts or references under `scripts/` or `references/`; pass them via the `files` map (relative path -> text).
- Before creating, quickly confirm or infer the skill's purpose and trigger; do not ask unnecessary questions if the request is clear.
- After create/update, report the skill name and remind the user to enable it in the agent's settings (skills are disabled by default) so it can be used with use_skill in the next conversation.
""".trimIndent()

private fun createSkillTool(
    getSkills: suspend () -> List<LlmSkill>,
    upsertSkill: suspend (LlmSkill) -> Unit,
): Tool = Tool(
    name = "create_skill",
    description = """
        Create a new local skill. Pass the complete SKILL.md text (with YAML frontmatter containing
        name and description) as `content`, and optional child files as `files` (relative path -> text).
        Use this when the user wants a new reusable skill, tool, or workflow.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ -> SKILL_AUTHORING_GUIDE },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Complete SKILL.md text including YAML frontmatter (name + description required)")
                })
                put("files", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional child files: relative path (e.g. scripts/parse.py) -> file text")
                })
            },
            required = listOf("content")
        )
    },
    execute = { args ->
        val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull
            ?: error("content (完整 SKILL.md) 是必填项")
        val parsed = LlmSkill.fromContent(content)
            ?: error("SKILL.md frontmatter 缺少 name 或 description，无法创建")
        val files = args.jsonObject.parseFiles()
        val existing = getSkills()
        if (existing.any { it.name == parsed.name }) {
            error("已存在同名技能「${parsed.name}」，请改用 update_skill 修改或换个名字")
        }
        val skill = parsed.copy(files = files)
        upsertSkill(skill)
        listOf(
            UIMessagePart.Text(
                buildString {
                    append("已创建技能「").append(skill.name).append("」。\n")
                    append("位置：filesDir/skills/").append(skill.name).append("/SKILL.md\n")
                    if (files.isNotEmpty()) {
                        append("子文件：").append(files.keys.joinToString(", ")).append("\n")
                    }
                    append("提示：该技能默认未启用，请在智能体设置里勾选后，下次对话即可通过 use_skill 调用。")
                }
            )
        )
    }
)

private fun updateSkillTool(
    getSkills: suspend () -> List<LlmSkill>,
    upsertSkill: suspend (LlmSkill) -> Unit,
): Tool = Tool(
    name = "update_skill",
    description = """
        Update an existing local skill by name. Provide the new full SKILL.md as `content` (or omit to keep
        the current content), optionally override `description` and `files` (files replaces the whole map).
        Use this when the user wants to modify, fix, or improve an existing skill.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the existing skill to update")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "New complete SKILL.md text. Omit to keep current content")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "New description. Omit to keep current (or derive from new content frontmatter)")
                })
                put("files", buildJsonObject {
                    put("type", "object")
                    put("description", "New child files map (replaces all). Omit to keep current files")
                })
            },
            required = listOf("name")
        )
    },
    execute = { args ->
        val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: error("name 是必填项")
        val all = getSkills()
        val target = all.firstOrNull { it.name == name }
            ?: error("未找到技能「$name」。现有技能：${all.joinToString { it.name }}")

        val newContent = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val parsed = newContent?.let { LlmSkill.fromContent(it) }
        if (newContent != null && parsed == null) {
            error("新 content 的 frontmatter 缺少 name 或 description，无法更新")
        }
        val newFiles = args.jsonObject["files"]?.jsonObject?.let { it.parseFilesMap() }
        val newDescription = args.jsonObject["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: parsed?.description ?: target.description

        val updated = target.copy(
            name = parsed?.name ?: target.name,
            description = newDescription,
            content = newContent ?: target.content,
            files = newFiles ?: target.files,
        )
        // 改名后重名检查
        if (all.any { it.name == updated.name && it.id != target.id }) {
            error("已存在同名技能「${updated.name}」，无法更新")
        }
        upsertSkill(updated)
        listOf(
            UIMessagePart.Text(
                "已更新技能「${updated.name}」。改动将在下次进入会话（重新构建工具列表）时生效。"
            )
        )
    }
)

/** 解析工具参数里的 files 对象（path -> text），路径做规范化与越界校验 */
private fun JsonObject.parseFilesMap(): Map<String, String> =
    mapNotNull { (rawPath, value) ->
        val text = value.jsonPrimitive.contentOrNull ?: return@mapNotNull null
        val safe = LlmSkill.normalizePath(rawPath) ?: return@mapNotNull null
        safe to text
    }.toMap()

private fun JsonObject.parseFiles(): Map<String, String> =
    this["files"]?.jsonObject?.parseFilesMap() ?: emptyMap()
