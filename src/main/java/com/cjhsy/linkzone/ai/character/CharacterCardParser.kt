package com.cjhsy.linkzone.ai.character

import com.cjhsy.linkzone.data.model.LlmInjectionPosition
import com.cjhsy.linkzone.data.model.LlmLorebook
import com.cjhsy.linkzone.data.model.LlmLorebookEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * SillyTavern 角色卡解析结果 → LinkZone 本地智能体所需的最小载荷。
 */
data class CharacterCard(
    val name: String,
    val systemPrompt: String,
    /** 卡片自带世界书（character_book），无则 null */
    val lorebook: LlmLorebook?
)

/**
 * SillyTavern 角色卡导入解析器（对齐 chara_card_v2/v3 规范，兼容 V1 平铺结构）。
 *
 * 支持两种载体：
 * - PNG：角色数据藏在 PNG 辅助数据块里（tEXt / zTXt / iTXt，keyword 为
 *   "chara"（V1/V2）或 "ccv3"（V3）），内容是 base64 编码的卡片 JSON，
 *   手写 chunk 遍历，不解码像素、零额外依赖；
 * - JSON：直接是卡片内容（{spec, data:{...}} 或 V1 平铺）。
 *
 * 字段映射（→ LlmAgent / LlmLorebook）：
 * - name → 智能体名；avatar 图片忽略（LinkZone 头像为 emoji）
 * - system_prompt / description / personality / scenario / first_mes → systemPrompt 分段拼接
 * - character_book → LlmLorebook（keys→keywords、constant→constantActive、
 *   insertion_order→priority、position 数字/字符串→注入位置）
 */
object CharacterCardParser {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** PNG 文件签名（8 字节） */
    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * 解析角色卡字节（PNG 或 JSON）。
     * @throws IllegalArgumentException 格式不正确 / 不是角色卡
     */
    fun parse(bytes: ByteArray): CharacterCard {
        val text = if (isPng(bytes)) extractCardJsonFromPng(bytes) else bytes.toString(Charsets.UTF_8)
        val root = JSON.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        return toCard(root)
    }

    // ---------- PNG 数据块提取 ----------

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i].toInt() and 0xFF != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    /**
     * 遍历 PNG chunk，取出 "chara"（优先）或 "ccv3" 文本块中的 base64 并解码为卡片 JSON。
     */
    private fun extractCardJsonFromPng(bytes: ByteArray): String {
        var chara: String? = null
        var ccv3: String? = null
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            // 长度异常或数据越界 → 非标准文件，停止扫描
            if (length < 0 || dataStart.toLong() + length > bytes.size) break
            when (type) {
                "tEXt", "zTXt", "iTXt" -> {
                    val chunk = readTextChunk(bytes, dataStart, length, type)
                    if (chunk != null) {
                        val (keyword, text) = chunk
                        when (keyword.lowercase()) {
                            "chara" -> if (chara == null) chara = text
                            "ccv3" -> if (ccv3 == null) ccv3 = text
                        }
                    }
                }
                "IEND" -> break
            }
            offset = dataStart + length + 4 // 跳过数据与 CRC
        }
        val base64 = chara ?: ccv3
            ?: throw IllegalArgumentException("不是有效的角色卡 PNG（未找到 chara 数据块）")
        // MIME 解码器容忍换行等非字母表字符（部分导出工具会折行）
        val decoded = try {
            Base64.getMimeDecoder().decode(base64)
        } catch (_: Exception) {
            throw IllegalArgumentException("角色卡数据解码失败")
        }
        return String(decoded, Charsets.UTF_8)
    }

    /** 解析 PNG 文本块为 (keyword, text)，支持 tEXt / zTXt / iTXt 三种编码 */
    private fun readTextChunk(bytes: ByteArray, start: Int, length: Int, type: String): Pair<String, String>? {
        val data = bytes.copyOfRange(start, start + length)
        // keyword 以 NUL 结尾
        var nul = data.indexOf(0.toByte())
        if (nul <= 0) return null
        val keyword = String(data, 0, nul, Charsets.ISO_8859_1)
        var rest = data.copyOfRange(nul + 1, data.size)
        return when (type) {
            "tEXt" -> keyword to String(rest, Charsets.ISO_8859_1)
            "zTXt" -> {
                // 1 字节压缩方法，0 = zlib
                if (rest.isEmpty() || rest[0].toInt() != 0) return null
                keyword to String(inflate(rest.copyOfRange(1, rest.size)), Charsets.UTF_8)
            }
            else -> {
                // iTXt：compFlag(1) compMethod(1) languageTag<NUL> translatedKeyword<NUL> text
                if (rest.size < 2) return null
                val compressed = rest[0].toInt() == 1
                rest = rest.copyOfRange(2, rest.size)
                nul = rest.indexOf(0.toByte()); if (nul < 0) return null
                rest = rest.copyOfRange(nul + 1, rest.size)
                nul = rest.indexOf(0.toByte()); if (nul < 0) return null
                rest = rest.copyOfRange(nul + 1, rest.size)
                val text = if (compressed) String(inflate(rest), Charsets.UTF_8) else String(rest, Charsets.UTF_8)
                keyword to text
            }
        }
    }

    private fun inflate(data: ByteArray): ByteArray = try {
        InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    } catch (_: Exception) {
        throw IllegalArgumentException("角色卡压缩数据块解压失败")
    }

    // ---------- 卡片 JSON → 模型映射 ----------

    private fun toCard(root: JsonObject): CharacterCard {
        // V2/V3 规范包一层 data；V1 平铺在根对象
        val card = root["data"] as? JsonObject ?: root

        val name = card.str("name").orEmpty().trim()
        val description = card.str("description").orEmpty().trim()
        val personality = card.str("personality").orEmpty().trim()
        val scenario = card.str("scenario").orEmpty().trim()
        val firstMes = card.str("first_mes").orEmpty().trim()
        val explicitSystem = card.str("system_prompt").orEmpty().trim()
        // 作者备注：角色设计意图 / 扮演要点，对角色扮演质量影响很大
        val creatorNotes = card.str("creator_notes").orEmpty().trim()
        // 历史后指令：SillyTavern 中注入在对话历史之后，LinkZone 为单系统提示词，
        // 追加到末尾以尽量还原其语义
        val postHistory = card.str("post_history_instructions").orEmpty().trim()

        val prompt = buildString {
            if (explicitSystem.isNotEmpty()) {
                append(explicitSystem)
            } else if (description.isNotEmpty()) {
                append(description)
            }
            if (personality.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## 性格\n").append(personality)
            }
            if (scenario.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## 场景\n").append(scenario)
            }
            if (creatorNotes.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## 作者备注\n").append(creatorNotes)
            }
            if (firstMes.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## 开场白\n（首次回复时请先使用以下开场白：）\n").append(firstMes)
            }
            if (postHistory.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("## 历史后指令\n（请在每轮回复前遵循以下要求：）\n").append(postHistory)
            }
        }.trim()

        val book = parseCharacterBook(card["character_book"], name)
        return CharacterCard(
            name = name.ifBlank { "导入的角色" },
            systemPrompt = prompt,
            lorebook = book
        )
    }

    /** character_book → LlmLorebook；无有效条目时返回 null */
    private fun parseCharacterBook(element: JsonElement?, cardName: String): LlmLorebook? {
        val book = element as? JsonObject ?: return null
        val rawEntries = book["entries"] as? JsonArray ?: return null
        val entries = rawEntries.mapIndexedNotNull { index, item ->
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null
            parseBookEntry(obj, index)
        }
        if (entries.isEmpty()) return null
        return LlmLorebook(
            name = book.str("name").orEmpty().trim().ifBlank { "$cardName · 世界书" },
            description = book.str("description").orEmpty().trim(),
            entries = entries
        )
    }

    private fun parseBookEntry(obj: JsonObject, index: Int): LlmLorebookEntry? {
        // V2 规范用 keys 数组；部分 V1/第三方导出用 key
        val keywords = (obj["keys"] as? JsonArray ?: obj["key"] as? JsonArray)
            ?.mapNotNull { it.asStringOrNull() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val content = obj.str("content").orEmpty()
        if (content.isBlank() && keywords.isEmpty()) return null

        val ext = obj["extensions"] as? JsonObject
        val position = resolvePosition(obj, ext)

        return LlmLorebookEntry(
            name = (obj.str("name") ?: obj.str("comment")).orEmpty().trim()
                .ifBlank { "条目 ${index + 1}" },
            enabled = obj.bool("enabled") ?: true,
            priority = (obj.int("insertion_order") ?: obj.int("order") ?: obj.int("priority") ?: 0),
            position = position.first,
            injectDepth = position.second,
            content = content,
            keywords = keywords,
            caseSensitive = obj.bool("case_sensitive") ?: ext.bool("case_sensitive") ?: false,
            constantActive = obj.bool("constant") ?: ext.bool("constant") ?: false
        )
    }

    /**
     * 归一注入位置：兼容字符串形式（before_char / after_char / at_depth）
     * 与 V2 规范数字（0=before_char, 1=after_char, 4=at_depth，其余近似为 after_char）。
     * @return (注入位置, 深度)
     */
    private fun resolvePosition(obj: JsonObject, ext: JsonObject?): Pair<LlmInjectionPosition, Int> {
        val depth = (ext.int("depth") ?: obj.int("depth") ?: 4).coerceIn(1, 64)
        val raw = obj.str("position") ?: ext.str("position")
        val position = when {
            raw == "before_char" || raw == "0" -> LlmInjectionPosition.BEFORE_SYSTEM_PROMPT
            raw == "at_depth" || raw == "4" -> LlmInjectionPosition.AT_DEPTH
            raw == "after_char" || raw == "1" -> LlmInjectionPosition.AFTER_SYSTEM_PROMPT
            else -> when (obj.int("position") ?: ext.int("position")) {
                0 -> LlmInjectionPosition.BEFORE_SYSTEM_PROMPT
                4 -> LlmInjectionPosition.AT_DEPTH
                else -> LlmInjectionPosition.AFTER_SYSTEM_PROMPT
            }
        }
        return position to depth
    }

    // ---------- 宽松取值工具（角色卡实现五花八门，任一字段都可能缺失/变型） ----------

    private fun JsonElement?.asStringOrNull(): String? {
        if (this !is JsonPrimitive || this is JsonNull) return null
        return content
    }

    private fun JsonElement?.asBoolOrNull(): Boolean? {
        if (this !is JsonPrimitive || this is JsonNull) return null
        return content.toBooleanStrictOrNull()
    }

    private fun JsonElement?.asIntOrNull(): Int? {
        if (this !is JsonPrimitive || this is JsonNull) return null
        return content.toIntOrNull()
    }

    private fun JsonObject?.str(vararg keys: String): String? {
        if (this == null) return null
        keys.forEach { key -> this[key].asStringOrNull()?.let { return it } }
        return null
    }

    private fun JsonObject?.bool(key: String): Boolean? = this?.get(key).asBoolOrNull()

    private fun JsonObject?.int(key: String): Int? = this?.get(key).asIntOrNull()
}
