package com.cjhsy.linkzone.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.model.AssistantMemory
import java.time.LocalDate

/**
 * memory_tool —— 移植自 RikkaHub MemoryTools。
 *
 * 模型经 create/edit/delete 维护长期记忆；已有记忆在生成前由
 * [buildMemoryPrompt] 注入系统提示。
 */
fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Memories will automatically appear in the <memories> tag in later conversations.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now()}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User's preferred name updated to A-Xing, prefers Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

/**
 * 记忆注入提示块（RikkaHub buildMemoryPrompt 对齐）：以 JSON 数组形式
 * 列出 id 与 content，追加进系统提示。
 */
fun buildMemoryPrompt(memories: List<AssistantMemory>): String = buildString {
    appendLine()
    append("**Memories**")
    appendLine()
    append("These are memories stored via the memory_tool that you can reference in future conversations.")
    appendLine()
    val json = buildJsonArray {
        memories.forEach { memory ->
            add(buildJsonObject {
                put("id", memory.id)
                put("content", memory.content)
            })
        }
    }
    append(json.toString())
    appendLine()
}
