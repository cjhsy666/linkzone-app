package com.cjhsy.linkzone.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** 解析后的单条聊天消息（role 仅保留 user / assistant） */
data class ImportedChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long? = null,
)

/** 解析后的单个会话 */
data class ImportedChat(
    val sourceId: String?,
    val title: String,
    val createdAt: Long?,
    val messages: List<ImportedChatMessage>,
)

/**
 * DeepSeek 聊天记录导入解析器。
 *
 * 官方导出为 JSON 或包含 JSON 的 ZIP；社区导出工具常见数组 JSON / 单会话 JSON / JSONL。
 * 这里对结构做宽松识别：会话数组、单会话、ChatGPT 式 mapping、扁平消息数组都兼容。
 * 只关心标题、角色、正文与时间，未知字段全部忽略。
 */
object DeepSeekChatImporter {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val conversationArrayKeys = listOf(
        "conversations", "chats", "chat_sessions", "sessions",
        "conversation_list", "session_list"
    )
    private val messageArrayKeys = listOf(
        "messages", "chat_messages", "msgs", "message_list", "messageList"
    )

    /**
     * @param fileName 用于无标题时兜底命名，也用于判定 ZIP
     * @param bytes 文件原始内容（JSON / JSONL / ZIP）
     */
    fun parse(fileName: String, bytes: ByteArray): List<ImportedChat> {
        require(bytes.isNotEmpty()) { "文件为空" }
        return if (isZip(fileName, bytes)) parseZip(bytes) else parseText(decode(bytes), fileName)
    }

    // ============ 容器 ============

    private fun isZip(fileName: String, bytes: ByteArray): Boolean =
        fileName.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())

    private fun parseZip(bytes: ByteArray): List<ImportedChat> {
        val result = mutableListOf<ImportedChat>()
        var jsonEntries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val entryName = entry.name ?: continue
                val lower = entryName.lowercase()
                if (!lower.endsWith(".json") && !lower.endsWith(".jsonl")) continue
                val data = runCatching { zip.readBytes() }.getOrElse { continue }
                if (data.isEmpty()) continue
                jsonEntries++
                result += runCatching {
                    parseText(decode(data), entryName.substringAfterLast('/'))
                }.getOrDefault(emptyList())
            }
        }
        if (jsonEntries == 0) error("压缩包内没有找到 JSON 聊天记录")
        return result
    }

    private fun parseText(text: String, fileName: String): List<ImportedChat> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            if (root != null) {
                val chats = fromRoot(root, fileName)
                if (chats.isNotEmpty()) return chats
            }
        }
        // JSONL：每行一个 JSON（消息或会话）
        val elements = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") || it.startsWith("[") }
            .mapNotNull { line -> runCatching { json.parseToJsonElement(line) }.getOrNull() }
            .toList()
        return fromElements(elements, fileName)
    }

    private fun fromRoot(root: JsonElement, fileName: String): List<ImportedChat> {
        return when (root) {
            is JsonArray -> fromElements(root, fileName)
            is JsonObject -> {
                firstArray(root, conversationArrayKeys)?.let { return fromElements(it, fileName) }
                (root["data"] as? JsonObject)?.let { data ->
                    firstArray(data, conversationArrayKeys)?.let { return fromElements(it, fileName) }
                    if (looksLikeConversation(data)) {
                        return listOfNotNull(parseConversation(data, fileName))
                    }
                }
                if (looksLikeConversation(root)) {
                    return listOfNotNull(parseConversation(root, fileName))
                }
                // id -> 会话对象的 map
                val nested = root.values.filterIsInstance<JsonObject>().filter { looksLikeConversation(it) }
                if (nested.isNotEmpty()) {
                    return nested.mapNotNull { parseConversation(it, fileName) }
                }
                if (isMessageLike(root)) {
                    val message = parseMessage(root) ?: return emptyList()
                    return listOf(
                        ImportedChat(null, fileNameToTitle(fileName), message.timestamp, listOf(message))
                    )
                }
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun fromElements(elements: List<JsonElement>, fileName: String): List<ImportedChat> {
        if (elements.isEmpty()) return emptyList()
        val objects = elements.filterIsInstance<JsonObject>()
        if (objects.size == elements.size && objects.all { isMessageLike(it) }) {
            return fromFlatMessages(objects, fileName)
        }
        return elements.mapNotNull { parseConversation(it, fileName) }
    }

    /** 扁平消息数组：按 conversation_id 分组（没有该字段则视为单个会话） */
    private fun fromFlatMessages(messages: List<JsonObject>, fileName: String): List<ImportedChat> {
        val idKeys = listOf("conversation_id", "conv_id", "session_id", "chat_id")
        val hasConversationId = messages.any { obj -> idKeys.any { obj[it] != null } }
        if (!hasConversationId) {
            val parsed = messages.mapNotNull { parseMessage(it) }
            if (parsed.isEmpty()) return emptyList()
            return listOf(ImportedChat(null, fileNameToTitle(fileName), parsed.first().timestamp, parsed))
        }
        val groups = LinkedHashMap<String, MutableList<JsonObject>>()
        messages.forEach { obj ->
            val id = str(obj, *idKeys.toTypedArray()) ?: "default"
            groups.getOrPut(id) { mutableListOf() }.add(obj)
        }
        return groups.mapNotNull { (id, objs) ->
            val messages = objs.mapNotNull { parseMessage(it) }
            if (messages.isEmpty()) return@mapNotNull null
            val title = objs.firstNotNullOfOrNull {
                str(it, "conversation_title", "session_title", "title", "name")
            }?.takeIf { !isPlaceholderTitle(it) }
                ?: messages.firstOrNull { it.role == "user" }?.content?.toTitle()
                ?: "DeepSeek 对话"
            val createdAt = objs.firstNotNullOfOrNull {
                timeOf(it, "created_at", "create_time", "timestamp", "time", "inserted_at")
            }
            ImportedChat(id, title, createdAt, messages)
        }
    }

    // ============ 会话 / 消息 ============

    private fun parseConversation(el: JsonElement, fileName: String): ImportedChat? {
        val obj = el as? JsonObject ?: return null
        val messages = extractMessages(obj)
        if (messages.isEmpty()) return null
        // 部分导出把会话元信息放在 chat_session / session 等嵌套对象里
        val meta = listOf("chat_session", "session", "conversation", "meta", "info")
            .firstNotNullOfOrNull { obj[it] as? JsonObject }
        val sourceId = str(obj, "id", "conversation_id", "conv_id", "session_id", "chat_id", "uuid")
            ?: meta?.let { str(it, "id", "conversation_id", "conv_id", "session_id", "chat_id", "uuid") }
        val rawTitle = str(obj, "title", "name", "conversation_title", "session_title", "subject", "topic")
            ?: meta?.let { str(it, "title", "name", "conversation_title", "session_title", "subject", "topic") }
        val title = rawTitle?.takeIf { !isPlaceholderTitle(it) }
            ?: messages.firstOrNull { it.role == "user" }?.content?.toTitle()
            ?: fileNameToTitle(fileName)
        val createdAt = timeOf(
            obj, "created_at", "create_time", "createdAt", "inserted_at",
            "timestamp", "start_time", "updated_at"
        ) ?: meta?.let {
            timeOf(it, "created_at", "create_time", "createdAt", "inserted_at", "timestamp", "start_time")
        }
        return ImportedChat(sourceId, title, createdAt, messages)
    }

    private fun extractMessages(obj: JsonObject): List<ImportedChatMessage> {
        firstArray(obj, messageArrayKeys)?.let { arr ->
            val messages = arr.mapNotNull { parseMessage(it) }
            if (messages.isNotEmpty()) return messages
        }
        (obj["mapping"] as? JsonObject)?.let { mapping ->
            val messages = messagesFromMapping(mapping)
            if (messages.isNotEmpty()) return messages
        }
        (obj["fragments"] as? JsonArray)?.let { arr ->
            val messages = fragmentsToMessages(arr)
            if (messages.isNotEmpty()) return messages
        }
        listOf("data", "conversation", "chat", "session", "chat_session").forEach { key ->
            (obj[key] as? JsonObject)?.let { nested ->
                val messages = extractMessages(nested)
                if (messages.isNotEmpty()) return messages
            }
        }
        return emptyList()
    }

    /** ChatGPT 式 mapping：节点里的 message 按 create_time 排序 */
    private fun messagesFromMapping(mapping: JsonObject): List<ImportedChatMessage> {
        val nodes = mapping.values.filterIsInstance<JsonObject>()
        val parsed = nodes.mapNotNull { node ->
            val message = (node["message"] as? JsonObject) ?: node
            parseMessage(message)?.let { msg ->
                val timestamp = msg.timestamp ?: timeOf(node, "created_at", "create_time", "timestamp", "time")
                msg.copy(timestamp = timestamp)
            }
        }
        // 全部/部分有时间时才排序，避免无时间的节点被 Long.MAX_VALUE 打乱原有顺序
        return if (parsed.any { it.timestamp != null }) {
            parsed.sortedBy { it.timestamp ?: Long.MAX_VALUE }
        } else {
            parsed
        }
    }

    private fun fragmentsToMessages(arr: JsonArray): List<ImportedChatMessage> =
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val type = str(obj, "type", "role")?.uppercase() ?: return@mapNotNull null
            val role = when (type) {
                "REQUEST", "USER", "HUMAN", "QUESTION" -> "user"
                "RESPONSE", "ASSISTANT", "AI", "ANSWER" -> "assistant"
                else -> return@mapNotNull null
            }
            val content = messageText(obj)
            if (content.isBlank()) return@mapNotNull null
            ImportedChatMessage(role, content.trim(), timeOf(obj, "created_at", "create_time", "timestamp", "time"))
        }

    private fun parseMessage(el: JsonElement): ImportedChatMessage? {
        val obj = el as? JsonObject ?: return null
        val author = obj["author"] as? JsonObject
        val rawRole = str(obj, "role", "sender", "from") ?: author?.let { str(it, "role") }
        val type = str(obj, "type")?.uppercase()
        val role = when {
            rawRole != null -> when (rawRole.lowercase()) {
                "user", "human", "me", "request", "question" -> "user"
                "assistant", "ai", "bot", "model", "response", "answer" -> "assistant"
                else -> null
            }
            type == "REQUEST" -> "user"
            type == "RESPONSE" -> "assistant"
            else -> null
        } ?: return null
        val content = messageText(obj)
        if (content.isBlank()) return null
        val timestamp = timeOf(
            obj, "created_at", "create_time", "createdAt",
            "timestamp", "time", "inserted_at", "updated_at"
        )
        return ImportedChatMessage(role, content.trim(), timestamp)
    }

    private fun messageText(obj: JsonObject): String {
        for (key in listOf("content", "text", "parts")) {
            val text = textOf(obj[key])
            if (text.isNotBlank()) return text
        }
        (obj["fragments"] as? JsonArray)?.let { arr ->
            val text = arr.mapNotNull { textOf(it).takeIf(String::isNotBlank) }.joinToString("\n")
            if (text.isNotBlank()) return text
        }
        val nested = obj["message"] as? JsonObject
        if (nested != null && nested !== obj) return messageText(nested)
        return ""
    }

    private fun textOf(el: JsonElement?): String {
        when (el) {
            null -> return ""
            is JsonPrimitive -> return if (el.isString) el.content else ""
            is JsonArray -> return el.mapNotNull { child ->
                textOf(child).takeIf { it.isNotBlank() }
            }.joinToString("\n")
            is JsonObject -> {
                for (key in listOf("text", "parts", "content", "value")) {
                    val value = el[key] ?: continue
                    val text = textOf(value)
                    if (text.isNotBlank()) return text
                }
                return ""
            }
            else -> return ""
        }
    }

    // ============ 判定 / 取值 ============

    private fun isMessageLike(obj: JsonObject): Boolean {
        if (messageText(obj).isBlank()) return false
        val role = str(obj, "role", "sender", "from")
            ?: (obj["author"] as? JsonObject)?.let { str(it, "role") }
        if (!role.isNullOrBlank()) return true
        return str(obj, "type")?.uppercase() in setOf("REQUEST", "RESPONSE")
    }

    private fun looksLikeConversation(obj: JsonObject): Boolean {
        if (isMessageLike(obj)) return false
        return firstArray(obj, messageArrayKeys) != null ||
            obj["mapping"] is JsonObject ||
            obj["fragments"] is JsonArray ||
            str(obj, "conversation_id", "conv_id", "session_id", "title", "conversation_title") != null
    }

    private fun firstArray(obj: JsonObject, keys: List<String>): JsonArray? =
        keys.firstNotNullOfOrNull { obj[it] as? JsonArray }

    private fun str(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            val value = primitive.contentOrNull?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun timeOf(obj: JsonObject, vararg keys: String): Long? {
        for (key in keys) {
            val value = obj[key] ?: continue
            val time = timeOf(value)
            if (time != null && time > 0) return time
        }
        val message = obj["message"] as? JsonObject
        if (message != null && message !== obj) return timeOf(message, *keys)
        return null
    }

    private fun timeOf(el: JsonElement?): Long? {
        when (el) {
            null -> return null
            is JsonPrimitive -> {
                val raw = el.contentOrNull?.trim().orEmpty()
                if (raw.isEmpty()) return null
                raw.toLongOrNull()?.let { return normalizeEpoch(it) }
                raw.toDoubleOrNull()?.let { return normalizeEpoch(it.toLong()) }
                return parseIsoTime(raw)
            }
            is JsonObject -> {
                for (key in listOf("seconds", "value", "timestamp", "date")) {
                    val value = el[key] ?: continue
                    val time = timeOf(value)
                    if (time != null && time > 0) return time
                }
                return null
            }
            else -> return null
        }
    }

    /** epoch 秒 / 毫秒 / 微秒 / 纳秒统一成毫秒 */
    private fun normalizeEpoch(value: Long): Long? {
        if (value <= 0L) return null
        return when {
            value < 10_000_000_000L -> value * 1000L
            value < 10_000_000_000_000L -> value
            value < 10_000_000_000_000_000L -> value / 1000L
            else -> value / 1_000_000L
        }
    }

    private fun parseIsoTime(raw: String): Long? {
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
        val normalized = raw.replace(' ', 'T')
        runCatching { OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }

    private fun String.toTitle(): String =
        replace(Regex("\\s+"), " ").trim().take(40).ifBlank { "DeepSeek 对话" }

    private fun fileNameToTitle(fileName: String): String {
        val name = fileName.substringBeforeLast('.').trim()
        return name.takeIf {
            it.isNotBlank() &&
                !it.equals("conversations", ignoreCase = true) &&
                !it.equals("chat_history", ignoreCase = true)
        } ?: "DeepSeek 对话"
    }

    private fun isPlaceholderTitle(title: String): Boolean =
        title.isBlank() || title.trim().lowercase() in setOf(
            "新对话", "未命名对话", "无标题", "new chat", "untitled"
        )

    private fun decode(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
}
