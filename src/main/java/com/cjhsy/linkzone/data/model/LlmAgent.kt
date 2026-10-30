package com.cjhsy.linkzone.data.model

import com.cjhsy.linkzone.ai.core.ReasoningLevel
import com.cjhsy.linkzone.ai.provider.CustomBody
import com.cjhsy.linkzone.ai.provider.CustomHeader
import com.cjhsy.linkzone.data.ai.LocalToolOption
import kotlinx.serialization.Serializable

/**
 * 本地 LLM Agent（智能体）—— 移植自 RikkaHub `Assistant` 的裁剪版。
 *
 * 仅保留一期会用到的字段；配置纯本地存储（Room llm_agent 表 JSON 列），
 * 不与服务端交互，字段可随批次演进而不需要迁移表结构。
 */
@Serializable
data class LlmAgent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    /** 头像：emoji 字符（LinkZone 无图片上传，简化 RikkaHub 的 Avatar 多态） */
    val avatarEmoji: String = "",
    /** 系统提示词，支持 prompt 变量 {{model}} / {{time}} / {{date}} */
    val systemPrompt: String = "",
    /** 对话模型 id（[com.cjhsy.linkzone.ai.provider.Model.modelId]，跨拉取稳定）；null 时使用全局默认模型 */
    val chatModelId: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    /** 上下文消息条数上限，超出后阶梯式截断；0 表示不限制 */
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    /** 启用的 skill 名称列表（use_skill 工具注入） */
    val enabledSkills: Set<String> = emptySet(),
    /** 关联的模式注入 id 列表（llm_prompt 表） */
    val promptIds: Set<String> = emptySet(),
    /** 关联的世界书 id 列表（llm_lorebook 表） */
    val lorebookIds: Set<String> = emptySet(),
    /** 关联的快捷指令 id 列表（llm_quick_message 表） */
    val quickMessageIds: Set<String> = emptySet(),
    /** 启用记忆：注入已有记忆 + memory_tool 增删改 */
    val enableMemory: Boolean = false,
    /** 使用全局共享记忆（"__global__"）而非智能体隔离记忆 */
    val useGlobalMemory: Boolean = false,
    /** 启用联网搜索（使用设置中选中的搜索服务注册 search_web 工具） */
    val enableWebSearch: Boolean = false,
    /**
     * 会话查询工具（recent_chats / conversation_search）：让模型检索历史会话。
     * 默认开启——此前一直是无条件注入，改成开关后要保持老用户行为不变。
     */
    val enableConversationTools: Boolean = true,
    /** 关联的 MCP 服务器 id 列表（llm_mcp_server 表，启用且已连接的服务器工具会被注入） */
    val mcpServers: Set<String> = emptySet(),
    /** 本地工具开关（时间/剪贴板/日历/手机控制等本地执行的工具） */
    val localTools: List<LocalToolOption> = emptyList(),
    /** 时间感知：在首条/间隔较大的用户消息前注入当前时间提醒（RikkaHub TimeReminderTransformer） */
    val enableTimeReminder: Boolean = true,
    /** 时间提醒触发间隔（分钟），距上条消息超过该间隔才注入 */
    val timeReminderIntervalMinutes: Int = 60,
    /** 上下文压缩：超 contextMessageLimit 时把旧消息总结为检查点而非直接丢弃（RikkaHub CompressPrompt） */
    val enableContextCompression: Boolean = true,
    /** 启用图片生成工具 image_generate（需配置 IMAGE 类型模型） */
    val enableImageGeneration: Boolean = false,
    /** 指定图片生成模型 modelId；null 时自动选用第一个 IMAGE 类型模型 */
    val imageModelId: String? = null,
    /** OCR 兜底：对话模型不支持图片输入时，用视觉模型把用户图片转成文本描述（RikkaHub OcrTransformer） */
    val enableOcr: Boolean = false,
    /** OCR 使用的视觉模型 modelId；null 时自动选用第一个支持 IMAGE 输入的 CHAT 模型 */
    val ocrModelId: String? = null,
    /** 绑定的代码工作区 id（非空时注入 workspace_read_file/write_file/edit_file/shell 工具） */
    val workspaceId: String? = null,
    /** 绑定的 SSH 工作区 id（非空时注入 ssh_read_file/write_file/list_dir/shell 工具） */
    val sshWorkspaceId: String? = null,
    /** 请求级自定义请求头（合并进 Provider 自身配置） */
    val customHeaders: List<CustomHeader> = emptyList(),
    /** 请求级自定义 body 字段 */
    val customBodies: List<CustomBody> = emptyList(),
    /** 预设对话（RikkaHub presetMessages）：新建会话时注入为用户/助手消息，作为开场示例 */
    val presetMessages: List<PresetMessage> = emptyList(),
    /** 输出正则处理（RikkaHub AssistantRegex）：对用户/助手文本做查找替换 */
    val regexes: List<AgentRegex> = emptyList(),
    /** 创建时间（排序用） */
    val createdAt: Long = System.currentTimeMillis(),
)

/** 预设对话条目：role "user"/"assistant"，content 为消息文本 */
@Serializable
data class PresetMessage(
    val role: String = "user",
    val content: String = ""
)

/** 输出正则处理条目（对齐 RikkaHub AssistantRegex） */
@Serializable
data class AgentRegex(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    /** 作用范围："user" / "assistant"，多选 */
    val affectingScope: Set<String> = setOf("assistant"),
    /** 仅视觉上替换（不进上下文），false 则修改真实消息 */
    val visualOnly: Boolean = true
)
