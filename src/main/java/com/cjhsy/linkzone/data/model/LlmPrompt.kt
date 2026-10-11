package com.cjhsy.linkzone.data.model

import com.cjhsy.linkzone.ai.core.MessageRole
import com.cjhsy.linkzone.ai.ui.UIMessage
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import kotlinx.serialization.Serializable

/**
 * 提示词注入位置 —— 移植自 RikkaHub InjectionPosition。
 */
@Serializable
enum class LlmInjectionPosition {
    /** 系统提示词之前 */
    BEFORE_SYSTEM_PROMPT,

    /** 系统提示词之后（最常用） */
    AFTER_SYSTEM_PROMPT,

    /** 对话最开头（第一条用户消息之前） */
    TOP_OF_CHAT,

    /** 最新消息之前（当前用户输入之前） */
    BOTTOM_OF_CHAT,

    /** 在指定深度位置插入（从最新消息往前数） */
    AT_DEPTH
}

/**
 * 世界书条目 —— 移植自 RikkaHub PromptInjection.RegexInjection。
 *
 * 触发规则：[constantActive] 常驻激活；否则任一 [keywords] 命中
 * 最近 [scanDepth] 条消息（[useRegex] 时关键词按正则解释）即触发。
 */
@Serializable
data class LlmLorebookEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val position: LlmInjectionPosition = LlmInjectionPosition.AFTER_SYSTEM_PROMPT,
    val content: String = "",
    val injectDepth: Int = 4,
    val role: MessageRole = MessageRole.USER,
    val keywords: List<String> = emptyList(),
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val scanDepth: Int = 4,
    val constantActive: Boolean = false
)

/**
 * 世界书 —— 组织多个 [LlmLorebookEntry]，整体启用/停用。
 */
@Serializable
data class LlmLorebook(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<LlmLorebookEntry> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 模式注入 —— 移植自 RikkaHub PromptInjection.ModeInjection。
 * 基于开关状态触发（Agent 关联后按开关注入，无关键词匹配）。
 */
@Serializable
data class LlmPrompt(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val position: LlmInjectionPosition = LlmInjectionPosition.AFTER_SYSTEM_PROMPT,
    val content: String = "",
    val injectDepth: Int = 4,
    val role: MessageRole = MessageRole.USER,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 判断世界书条目是否被上下文文本触发。
 */
fun LlmLorebookEntry.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false
    return keywords.any { keyword ->
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, options).containsMatchIn(context)
            } catch (_: Exception) {
                false
            }
        } else {
            if (caseSensitive) context.contains(keyword)
            else context.contains(keyword, ignoreCase = true)
        }
    }
}

/**
 * 提取最近 [scanDepth] 条消息的纯文本用于关键词匹配。
 */
fun extractContextForMatching(messages: List<UIMessage>, scanDepth: Int): String =
    messages.takeLast(scanDepth.coerceAtLeast(1)).joinToString("\n") { it.toText() }

/**
 * 统一注入项：模式注入与世界书条目归一后的注入载荷。
 */
internal data class LlmInjection(
    val priority: Int,
    val position: LlmInjectionPosition,
    val injectDepth: Int,
    val role: MessageRole,
    val content: String
)

/**
 * 提示词注入器 —— 移植自 RikkaHub PromptInjectionTransformer 的核心逻辑。
 *
 * 收集 Agent 关联且已启用的模式注入 + 世界书中被触发的条目，
 * 按位置/优先级注入消息列表。纯函数，无状态。
 */
object LlmPromptInjector {

    fun apply(
        messages: List<UIMessage>,
        prompts: List<LlmPrompt>,
        lorebooks: List<LlmLorebook>,
        enabledPromptIds: Set<String>,
        enabledLorebookIds: Set<String>
    ): List<UIMessage> {
        val injections = collectInjections(messages, prompts, lorebooks, enabledPromptIds, enabledLorebookIds)
        if (injections.isEmpty()) return messages
        val byPosition = injections
            .sortedByDescending { it.priority }
            .groupBy { it.position }
        return applyInjections(messages, byPosition)
    }

    private fun collectInjections(
        messages: List<UIMessage>,
        prompts: List<LlmPrompt>,
        lorebooks: List<LlmLorebook>,
        enabledPromptIds: Set<String>,
        enabledLorebookIds: Set<String>
    ): List<LlmInjection> {
        val result = mutableListOf<LlmInjection>()
        prompts.filter { it.enabled && it.id in enabledPromptIds }.forEach {
            result += LlmInjection(it.priority, it.position, it.injectDepth, it.role, it.content)
        }
        lorebooks.filter { it.enabled && it.id in enabledLorebookIds }.forEach { book ->
            // 只扫非 SYSTEM 消息（对齐 RikkaHub），扫描深度按条目各自的 scanDepth
            val nonSystem = messages.filter { it.role != MessageRole.SYSTEM }
            book.entries.filter { entry ->
                entry.isTriggered(extractContextForMatching(nonSystem, entry.scanDepth))
            }.forEach {
                result += LlmInjection(it.priority, it.position, it.injectDepth, it.role, it.content)
            }
        }
        return result
    }

    private fun applyInjections(
        messages: List<UIMessage>,
        byPosition: Map<LlmInjectionPosition, List<LlmInjection>>
    ): List<UIMessage> {
        val result = messages.toMutableList()

        // BEFORE/AFTER_SYSTEM_PROMPT：拼进系统消息
        val before = byPosition[LlmInjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty().joinToString("\n") { it.content }
        val after = byPosition[LlmInjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty().joinToString("\n") { it.content }
        if (before.isNotEmpty() || after.isNotEmpty()) {
            val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }
            if (systemIndex >= 0) {
                val systemMessage = result[systemIndex]
                val original = systemMessage.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                val newText = buildString {
                    if (before.isNotEmpty()) { append(before); appendLine() }
                    append(original)
                    if (after.isNotEmpty()) { appendLine(); append(after) }
                }
                result[systemIndex] = systemMessage.copy(
                    parts = listOf(UIMessagePart.Text(newText)),
                    isSynthetic = true
                )
            } else {
                val combined = buildString {
                    if (before.isNotEmpty()) append(before)
                    if (after.isNotEmpty()) {
                        if (isNotEmpty()) appendLine()
                        append(after)
                    }
                }
                if (combined.isNotEmpty()) {
                    result.add(0, UIMessage.system(combined).copy(isSynthetic = true))
                }
            }
        }

        // TOP_OF_CHAT：第一条用户消息之前
        byPosition[LlmInjectionPosition.TOP_OF_CHAT]?.takeIf { it.isNotEmpty() }?.let { list ->
            var index = result.indexOfFirst { it.role == MessageRole.USER }.takeIf { it >= 0 } ?: result.size
            index = findSafeInsertIndex(result, index)
            createMerged(list).forEach { result.add(index, it); index++ }
        }

        // BOTTOM_OF_CHAT：最后一条消息之前
        byPosition[LlmInjectionPosition.BOTTOM_OF_CHAT]?.takeIf { it.isNotEmpty() }?.let { list ->
            var index = (result.size - 1).coerceAtLeast(0)
            index = findSafeInsertIndex(result, index)
            createMerged(list).forEach { result.add(index, it); index++ }
        }

        // AT_DEPTH：从最新消息往前数，按深度从大到小处理避免索引漂移
        byPosition[LlmInjectionPosition.AT_DEPTH]?.takeIf { it.isNotEmpty() }?.let { list ->
            list.groupBy { it.injectDepth }.keys.sortedDescending().forEach { depth ->
                val group = list.filter { it.injectDepth == depth }
                var index = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
                index = findSafeInsertIndex(result, index)
                createMerged(group).forEach { result.add(index, it); index++ }
            }
        }

        return result
    }

    /** 同 role 的注入合并为一条合成消息 */
    private fun createMerged(injections: List<LlmInjection>): List<UIMessage> =
        injections.groupBy { it.role }.map { (role, group) ->
            val content = group.joinToString("\n") { it.content }
            val message = when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(content)
                else -> UIMessage.user(content)
            }
            message.copy(isSynthetic = true)
        }

    /**
     * 避免插到 USER → ASSISTANT(含 Tool) 之间——部分提供商要求
     * USER 之后紧跟带工具调用的 ASSISTANT，中间插消息会报错。
     */
    private fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
        var index = targetIndex.coerceIn(0, messages.size)
        while (index > 0) {
            val prev = messages.getOrNull(index - 1)
            val current = messages.getOrNull(index)
            val isPrevUser = prev?.role == MessageRole.USER
            val isCurrentAssistantWithTools = current?.role == MessageRole.ASSISTANT &&
                current.getTools().isNotEmpty()
            if (isPrevUser && isCurrentAssistantWithTools) index-- else break
        }
        return index
    }
}
