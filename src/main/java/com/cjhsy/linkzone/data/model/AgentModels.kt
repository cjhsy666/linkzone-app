package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 智能体页面的数据模型 —— 仅用于「智能体」配置页（列表 / 默认偏好 / 创建）。
 *
 * 注意：智能体**不是聊天对象**。用户消息始终经 LinkZone 适配器发往框架，
 * 框架内部根据默认智能体偏好路由处理。会话/消息模型见 [ChatModels]。
 */

// ========== 智能体 GET /user/agents ==========
@Serializable
data class AgentsResponse(
    val agents: List<AgentItem> = emptyList(),
    val total: Int = 0,
    // 以下字段仅 GET /agents/mine 返回：创建配额
    val created: Int = 0,
    val quota: Int = 0,
    val unlimited: Boolean = false
)

@Serializable
data class AgentItem(
    val id: String = "",
    val name: String = "",
    val persona: String = "",
    @SerialName("speech_style") val speechStyle: String = "",
    val enabled: Boolean = true,
    val model: String = "",
    @SerialName("enabled_groups") val enabledGroups: Int = 0,
    @SerialName("talk_value") val talkValue: Double = 0.0,
    val level: Int = 0,
    val scope: Int = 0,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("created_at") val createdAt: Long = 0L
)

// ========== 智能体偏好 GET/PUT /user/agent/preference ==========
@Serializable
data class AgentPreference(
    @SerialName("linkzone_id") val linkzoneId: String = "",
    @SerialName("default_agent_id") val defaultAgentId: String = "",
    @SerialName("platform_overrides") val platformOverrides: Map<String, String> = emptyMap(),
    @SerialName("context_overrides") val contextOverrides: Map<String, String> = emptyMap(),
    @SerialName("updated_at") val updatedAt: Long = 0L
)

@Serializable
data class UpdateAgentPreferenceRequest(
    @SerialName("default_agent_id") val defaultAgentId: String
)

// ========== 创建智能体 POST /user/agents ==========
@Serializable
data class CreateMyAgentRequest(
    val name: String,
    val persona: String = "",
    @SerialName("speech_style") val speechStyle: String = ""
)

@Serializable
data class CreateMyAgentResponse(
    val id: String = "",
    val name: String = ""
)

// ========== 编辑智能体 PUT /user/agents/:id（字段为 null 表示不修改） ==========
@Serializable
data class UpdateMyAgentRequest(
    val name: String? = null,
    val persona: String? = null,
    @SerialName("speech_style") val speechStyle: String? = null,
    val enabled: Boolean? = null
)

/** GET /agents/mine：我的智能体 + 等级配额 */
data class MyAgentsPage(
    val agents: List<AgentItem>,
    val created: Int,
    val quota: Int,
    val unlimited: Boolean
)
