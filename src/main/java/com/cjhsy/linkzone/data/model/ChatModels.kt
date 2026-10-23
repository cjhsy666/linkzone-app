package com.cjhsy.linkzone.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 会话 / 消息数据模型 —— 客户端与 **LinkZone 适配器（框架）** 交互所用。
 *
 * 与智能体无关：消息经适配器进入框架，框架内部可能由智能体或插件响应，也可能不响应。
 * 会话由 bot_id 区分（conv-{ownerId}-{botId}），智能体页面配置见 [AgentModels]。
 */

// ========== 会话 GET/POST /api/v1/linkzone/chat/convs ==========
// 后端 GET /convs 的 data 直接返回会话数组（非分页对象）

@Serializable
data class ConvItem(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val unread: Int = 0,
    val pinned: Boolean = false,
    val target_id: String = "",
    val last_msg: String = "",
    @SerialName("last_time") val lastTime: Long = 0L,
    @SerialName("last_msg_id") val lastMsgId: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0L
)

@Serializable
data class CreateConvRequest(
    /** 客户端生成，规则 conv-{ownerId}-{targetId}（与服务端自动建会话一致，保证幂等） */
    val id: String,
    @SerialName("target_id") val targetId: String,
    val name: String? = null,
    val type: String = "private"
)

@Serializable
data class UpdateConvRequest(
    val name: String? = null,
    val pinned: Boolean? = null
)

// ========== 消息 GET /api/v1/linkzone/chat/msgs/:convId ==========
@Serializable
data class MessagesResponse(
    val items: List<MessageItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
)

/** 消息段（框架 pkg/message 标准结构）。@Immutable：段创建后不再原地修改，供 Compose 跳过未变气泡重组 */
@Immutable
@Serializable
data class MessageSegment(
    val type: String = "",
    val data: Map<String, JsonElement> = emptyMap()
) {
    /** 按优先级取首个非空字符串字段 */
    fun get(vararg keys: String): String {
        for (k in keys) {
            val v = data[k]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    /** 读取整型字段 */
    fun getInt(vararg keys: String): Long {
        for (k in keys) {
            val v = data[k]?.jsonPrimitive?.contentOrNull
            v?.toLongOrNull()?.let { return it }
        }
        return 0L
    }

    /** 读取浮点字段（location 经纬度等） */
    fun getFloat(vararg keys: String): Double {
        for (k in keys) {
            val v = runCatching { data[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            v?.toDoubleOrNull()?.let { return it }
        }
        return 0.0
    }

    /** 读取布尔字段（poll multi/anonymous） */
    fun getBool(vararg keys: String): Boolean {
        for (k in keys) {
            val v = runCatching { data[k]?.jsonPrimitive?.booleanOrNull }.getOrNull()
            if (v != null) return v
        }
        return false
    }

    /** 读取字符串数组（poll options） */
    fun getStringList(key: String): List<String> =
        runCatching {
            data[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull() ?: emptyList()

    /** 读取嵌套消息段数组（node/forward 的 nodes） */
    fun getSubSegments(key: String = "segments"): List<MessageSegment> {
        val arr = runCatching { data[key]?.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { item ->
            runCatching {
                val o = item.jsonObject
                val t = o["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val d = (o["data"] as? JsonObject)?.toMap() ?: emptyMap()
                MessageSegment(type = t, data = d)
            }.getOrNull()
        }
    }

    /** 读取 keyboard 按钮行 */
    fun getButtonRows(): List<List<KeyboardButtonData>> {
        val arr = data["buttons"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { rowEl ->
            runCatching {
                rowEl.jsonArray.map { bEl ->
                    val b = bEl.jsonObject
                    KeyboardButtonData(
                        label = b["label"]?.jsonPrimitive?.contentOrNull ?: "",
                        data = b["data"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = b["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            }.getOrNull()
        }.filter { it.isNotEmpty() }
    }

    /** 是否有可用的 URL（兼容 url / file 字段） */
    val mediaUrl: String get() = get("url", "file")

    /** 是否为媒体类段（图片/视频/语音/文件等） */
    val isMedia: Boolean get() = type in setOf(
        "image", "flash", "video", "animation", "voice", "audio", "file"
    )

    /** 展示文本（对齐后端 pkg/message 的 Display/ToText 语义） */
    val display: String
        get() = when (type) {
            "text" -> get("text")
            "markdown" -> get("content").ifBlank { "[Markdown]" }
            "mention" -> {
                val name = get("name")
                val id = get("user_id")
                val lzid = get("linkzone_id")
                val platform = get("platform")
                when {
                    name.isNotBlank() && platform.isNotBlank() -> "@$name($platform)"
                    name.isNotBlank() -> "@$name"
                    id.isNotBlank() -> "@($id)"
                    lzid.isNotBlank() -> "@($lzid)"
                    else -> "@"
                }
            }
            "mention_all" -> "@全体成员"
            "face" -> {
                val name = get("name")
                val id = get("id")
                if (name.isNotBlank()) "[$name]" else if (id.isNotBlank()) "[表情:$id]" else "[表情]"
            }
            "image" -> "[图片]"
            "flash" -> "[闪照]"
            "voice" -> "[语音]"
            "audio" -> {
                val title = get("title")
                if (title.isNotBlank()) "[音频:$title]" else "[音频]"
            }
            "video", "animation" -> {
                val dur = getInt("duration")
                if (dur > 0) "[视频:${dur}秒]" else "[视频]"
            }
            "file" -> {
                val name = get("name", "title")
                if (name.isNotBlank()) "[文件:$name]" else "[文件]"
            }
            "reply" -> {
                val sender = get("sender_name", "nickname", "displayname")
                val content = get("text", "content").ifBlank { "..." }.let {
                    if (it.length > 50) it.take(50) + "..." else it
                }
                if (sender.isNotBlank()) "[回复 $sender: $content]" else "[回复: $content]"
            }
            "link" -> {
                val title = get("title")
                val url = get("url")
                when {
                    title.isNotBlank() && url.isNotBlank() -> "$title $url"
                    title.isNotBlank() -> title
                    url.isNotBlank() -> url
                    else -> "[链接]"
                }
            }
            "location" -> {
                val title = get("title").ifBlank { "位置" }
                val addr = get("address")
                if (addr.isNotBlank()) "[$title:$addr]" else "[$title]"
            }
            "contact" -> {
                val name = get("name")
                val uid = get("user_id")
                when {
                    name.isNotBlank() -> "[推荐联系人:$name]"
                    uid.isNotBlank() -> "[推荐联系人:$uid]"
                    else -> "[推荐联系人]"
                }
            }
            "keyboard" -> "[按钮]"
            "forward" -> {
                val id = get("id")
                if (id.isNotBlank()) "[合并转发:$id]" else "[合并转发]"
            }
            "node" -> "[聊天记录]"
            "music" -> {
                val title = get("title")
                val artist = get("artist")
                when {
                    title.isNotBlank() && artist.isNotBlank() -> "[音乐:$title - $artist]"
                    title.isNotBlank() -> "[音乐:$title]"
                    else -> "[音乐]"
                }
            }
            "card" -> "[卡片消息]"
            "rich" -> "[富文本]"
            "sticker" -> {
                val summary = get("summary", "name")
                if (summary.isNotBlank()) "[表情:$summary]" else "[商城表情]"
            }
            "poke" -> "[戳一戳]"
            "dice" -> get("emoji").ifBlank { "[掷骰子]" }
            "rps" -> get("emoji").ifBlank { "[猜拳]" }
            "poll" -> {
                val title = get("title")
                if (title.isNotBlank()) "[投票:$title]" else "[投票]"
            }
            // 思考过程段：仅本地智能体消息内嵌展示，不参与展示文本摘要
            "thinking" -> ""
            // 工具调用段：会话列表等纯文本场景只留一行摘要
            "tool" -> {
                val name = get("name")
                if (name.isNotBlank()) "[工具:$name]" else "[工具调用]"
            }
            // 版本链元数据段（sys.ver）与分支约束段（sys.br）：不参与展示文本摘要
            "sys.ver" -> ""
            "sys.br" -> ""
            // 用户中断生成标记：仅驱动「继续生成」按钮，不参与展示文本摘要
            "sys.stop" -> ""
            "sys.abort" -> "[已终止]"
            "sys.listen_stop" -> "[已停止]"
            "sys.emotion" -> {
                val emoji = get("emoji")
                if (emoji.isNotBlank()) emoji else "[情绪]"
            }
            else -> {
                // 兜底：按优先级取第一个非空字段，否则显示类型占位
                listOf("text", "content", "title", "description", "summary", "name", "label", "url")
                    .firstNotNullOfOrNull { k -> get(k).takeIf { it.isNotBlank() } }
                    ?: "[$type]"
            }
        }
}

/** 消息段列表转纯文本（用于会话列表 last_msg、通知等纯文本场景） */
fun List<MessageSegment>?.displayText(): String {
    if (this == null) return ""
    return joinToString("") { it.display }
}

@Immutable
@Serializable
data class MessageItem(
    val id: String = "",
    @SerialName("conv_id") val convId: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("sender_name") val senderName: String = "",
    val segments: List<MessageSegment>? = null,
    val content: String = "",
    @SerialName("is_self") val isSelf: Boolean = false,
    val recalled: Boolean = false,
    val timestamp: Long = 0L,
    /** 媒体正在后台上传（本地临时 URL 展示中），UI 显示上传遮罩 */
    val uploading: Boolean = false,
    /** 媒体上传/发送失败，UI 显示失败标记，可删除后重发 */
    val uploadFailed: Boolean = false
) {
    /** 展示文本：优先 segments 提取，回退 content */
    val displayText: String get() = segments.displayText().ifBlank { content }

    /** 是否有富媒体/结构化内容（需要专用渲染而非纯文本气泡） */
    val hasRichContent: Boolean
        get() = segments?.any {
            it.type !in setOf("text", "markdown", "mention", "mention_all", "reply", "sys.abort", "sys.listen_stop", "thinking")
        } == true
}

// ========== 上传响应 ==========
@Serializable
data class UploadResponse(
    val url: String = "",
    val name: String = "",
    val size: Long = 0L,
    @SerialName("seg_type") val segType: String = ""
)

// ========== keyboard 按钮 ==========
@Serializable
data class KeyboardButtonData(
    val label: String = "",
    val data: String = "",
    val url: String = ""
)

/**
 * 消息段构造工厂 —— 与后端 pkg/message 构造器一一对应（字段名/语义一致）。
 * 供发送面板构造用户消息使用。
 */
object Segments {

    fun text(value: String): MessageSegment = seg("text", "text" to value)

    fun markdown(content: String): MessageSegment = seg("markdown", "content" to content)

    /** 思考过程段（仅本地智能体会话持久化，云端协议不产生该类型） */
    fun thinking(content: String): MessageSegment = seg("thinking", "text" to content)

    /**
     * 工具调用段（仅本地智能体会话持久化）：记录一次已执行的工具调用。
     * ok=false 时字段被 seg() 省略（读取侧 getBool 默认 false，语义一致）。
     */
    fun tool(callId: String, name: String, input: String, output: String, ok: Boolean = true) =
        seg("tool", "call_id" to callId, "name" to name, "input" to input, "output" to output, "ok" to ok)

    fun mention(userId: String, name: String = "", platform: String = ""): MessageSegment =
        seg("mention", "user_id" to userId, "name" to name, "platform" to platform)

    fun mentionByLinkzoneId(linkzoneId: String, name: String = ""): MessageSegment =
        seg("mention", "linkzone_id" to linkzoneId, "name" to name)

    fun mentionAll(): MessageSegment = MessageSegment("mention_all")

    fun face(id: String, name: String = ""): MessageSegment =
        seg("face", "id" to id, "name" to name)

    fun image(url: String, name: String = "", width: Int = 0, height: Int = 0, size: Long = 0L) =
        seg("image", "url" to url, "name" to name, "width" to width, "height" to height, "size" to size)

    fun flash(url: String, name: String = "", width: Int = 0, height: Int = 0) =
        seg("flash", "url" to url, "name" to name, "width" to width, "height" to height)

    fun voice(url: String, duration: Long = 0L) =
        seg("voice", "url" to url, "duration" to duration)

    fun audio(url: String, title: String = "", artist: String = "", duration: Long = 0L) =
        seg("audio", "url" to url, "title" to title, "artist" to artist, "duration" to duration)

    fun video(url: String, duration: Long = 0L, cover: String = "", width: Int = 0, height: Int = 0) =
        seg("video", "url" to url, "duration" to duration, "cover" to cover, "width" to width, "height" to height)

    fun animation(url: String, duration: Long = 0L, width: Int = 0, height: Int = 0) =
        seg("animation", "url" to url, "duration" to duration, "width" to width, "height" to height)

    fun file(url: String, name: String, size: Long = 0L, localPath: String = "") =
        seg("file", "url" to url, "name" to name, "size" to size, "local_path" to localPath)

    fun reply(messageId: String, senderId: String = "", senderName: String = "", preview: String = "") =
        seg("reply", "message_id" to messageId, "user_id" to senderId,
            "sender_name" to senderName, "text" to preview)

    fun link(url: String, title: String = "", description: String = "", cover: String = "") =
        seg("link", "url" to url, "title" to title, "description" to description, "cover" to cover)

    fun location(lat: Double, lon: Double, title: String = "", address: String = "") =
        seg("location", "latitude" to lat, "longitude" to lon, "title" to title, "address" to address)

    fun contact(userId: String, name: String = "") =
        seg("contact", "user_id" to userId, "name" to name)

    fun music(type: String, jumpUrl: String, audioUrl: String,
              title: String = "", artist: String = "", description: String = "", cover: String = "") =
        seg("music", "type" to type, "url" to jumpUrl, "audio" to audioUrl,
            "title" to title, "artist" to artist, "description" to description, "cover" to cover)

    fun sticker(id: String, summary: String = "", packageId: String = "") =
        seg("sticker", "id" to id, "summary" to summary, "package_id" to packageId)

    fun poke(): MessageSegment = MessageSegment("poke")

    fun dice(emoji: String = "", value: Int = 0) =
        seg("dice", "emoji" to emoji, "value" to value)

    fun rps(emoji: String = "", value: Int = 0) =
        seg("rps", "emoji" to emoji, "value" to value)

    fun poll(title: String, options: List<String>, multi: Boolean = false,
             anonymous: Boolean = false, expiry: Int = 0) =
        seg("poll", "title" to title, "options" to JsonArray(options.map { JsonPrimitive(it) }),
            "multi" to multi, "anonymous" to anonymous, "expiry" to expiry)

    fun forwardById(id: String) = seg("forward", "id" to id)

    fun forwardNodes(nodes: List<MessageSegment>): MessageSegment =
        seg("forward", "segments" to JsonArray(nodes.map { it.toJson() }))

    fun node(userId: String, content: List<MessageSegment>, name: String = ""): MessageSegment =
        seg("node", "user_id" to userId, "name" to name,
            "segments" to JsonArray(content.map { it.toJson() }))

    private fun MessageSegment.toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("data", JsonObject(data))
    }

    /** 通用构造：跳过 null/空串/0 值/false（对齐后端 Meta 只写非零字段的习惯） */
    private fun seg(type: String, vararg pairs: Pair<String, Any?>): MessageSegment {
        val map = LinkedHashMap<String, JsonElement>()
        pairs.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is String -> if (v.isNotEmpty()) map[k] = JsonPrimitive(v)
                is Int -> if (v != 0) map[k] = JsonPrimitive(v)
                is Long -> if (v != 0L) map[k] = JsonPrimitive(v)
                is Double -> if (v != 0.0) map[k] = JsonPrimitive(v)
                is Boolean -> if (v) map[k] = JsonPrimitive(true)
                is JsonElement -> map[k] = v
                else -> map[k] = JsonPrimitive(v.toString())
            }
        }
        return MessageSegment(type, map)
    }
}
