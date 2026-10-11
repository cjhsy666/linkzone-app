package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.model.MessageItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话查询工具（移植自 RikkaHub ConversationTools）：
 * 让模型按需检索用户的历史会话，而非把所有历史塞进系统提示破坏 prompt cache。
 *
 * - [recent_chats]：返回最近 N 个本地会话摘要
 * - [conversation_search]：跨所有本地会话按关键词搜索消息
 */
fun createConversationTools(
    loadConversations: suspend () -> List<ConvSummary>,
    loadAllMessages: suspend (String) -> List<MessageItem>,
): List<Tool> = listOf(
    buildRecentChatsTool(loadConversations),
    buildConversationSearchTool(loadConversations, loadAllMessages)
)

/** 工具用的会话摘要（解耦具体 ConvItem 类型） */
data class ConvSummary(
    val id: String,
    val name: String,
    val lastMsg: String,
    val lastTime: Long
)

private fun buildRecentChatsTool(
    loadConversations: suspend () -> List<ConvSummary>
): Tool = Tool(
    name = "recent_chats",
    description = "List the user's most recent conversations. Returns conversation id, title, last message preview, and last updated time. Use to find a conversation to search with conversation_search.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of conversations to return. Default 10.")
                })
            }
        )
    },
    execute = { args ->
        val limit = args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val convs = loadConversations().take(limit)
        val arr = buildJsonArray {
            convs.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("title", c.name)
                    put("last_message", c.lastMsg.take(200))
                    put("updated_at", fmtTime(c.lastTime))
                })
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("count", convs.size)
            put("conversations", arr)
        }.toString()))
    }
)

private fun buildConversationSearchTool(
    loadConversations: suspend () -> List<ConvSummary>,
    loadAllMessages: suspend (String) -> List<MessageItem>
): Tool = Tool(
    name = "conversation_search",
    description = "Search the user's past conversations for messages matching a keyword. Returns matching messages with conversation title, content, and timestamp. Use recent_chats first to find a specific conversation id, or pass no conversation_id to search all conversations.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keyword to search for in message content (case-insensitive substring match).")
                })
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: search only this conversation. If omitted, search all conversations.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of matching messages to return. Default 20.")
                })
            },
            required = listOf("query")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("query is required")
        val targetId = params["conversation_id"]?.jsonPrimitive?.contentOrNull
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val convs = loadConversations().filter { targetId == null || it.id == targetId }
        val results = mutableListOf<JsonElement>()
        for (c in convs) {
            if (results.size >= limit) break
            val msgs = loadAllMessages(c.id)
            for (m in msgs) {
                if (results.size >= limit) break
                val text = m.content.ifBlank { m.displayText }
                if (text.contains(query, ignoreCase = true)) {
                    results += buildJsonObject {
                        put("conversation_id", c.id)
                        put("conversation_title", c.name)
                        put("sender", m.senderName)
                        put("content", text.take(500))
                        put("timestamp", fmtTime(m.timestamp))
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("query", query)
            put("count", results.size)
            put("messages", buildJsonArray { results.forEach { add(it) } })
        }.toString()))
    }
)

private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun fmtTime(micros: Long): String {
    val ms = if (micros > 1e14) micros / 1000 else micros
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().format(TIME_FMT)
}
