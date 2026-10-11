package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.data.model.AgentItem
import com.cjhsy.linkzone.data.model.AgentPreference
import com.cjhsy.linkzone.data.model.CreateMyAgentRequest
import com.cjhsy.linkzone.data.model.CreateMyAgentResponse
import com.cjhsy.linkzone.data.model.MyAgentsPage
import com.cjhsy.linkzone.data.model.UpdateAgentPreferenceRequest
import com.cjhsy.linkzone.data.model.UpdateMyAgentRequest
import com.cjhsy.linkzone.data.remote.UserApi

/**
 * 智能体仓库：创建/编辑/删除智能体、可用列表、我创建的（含配额）、默认智能体偏好。
 */
class AgentRepository(
    private val userApi: UserApi,
    private val tokenHolder: TokenHolder
) {

    /** 创建智能体 */
    suspend fun createAgent(
        name: String,
        persona: String,
        speechStyle: String = ""
    ): Result<CreateMyAgentResponse> =
        runEnvelope(tokenHolder) {
            userApi.createAgent(CreateMyAgentRequest(name, persona, speechStyle))
        }

    /** 编辑智能体（任一字段为 null 表示不修改）。
     *  服务端成功时 data 为 null（SuccessWithMessage），须用 runUnitEnvelope，
     *  否则 runEnvelope 会把 null data 误判为失败。 */
    suspend fun updateAgent(
        agentId: String,
        name: String? = null,
        persona: String? = null,
        speechStyle: String? = null,
        enabled: Boolean? = null
    ): Result<Unit> =
        runUnitEnvelope(tokenHolder) {
            userApi.updateMyAgent(
                agentId,
                UpdateMyAgentRequest(name, persona, speechStyle, enabled)
            )
        }

    /** 删除智能体（同上，成功时 data 为 null） */
    suspend fun deleteAgent(agentId: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { userApi.deleteMyAgent(agentId) }

    /** 我可用的智能体（公共 + 本人创建） */
    suspend fun loadAgents(): Result<List<AgentItem>> =
        runEnvelope(tokenHolder) { userApi.getAgents() }.map { it.agents }

    /** 我创建的智能体 + 等级配额 */
    suspend fun loadMyAgents(): Result<MyAgentsPage> =
        runEnvelope(tokenHolder) { userApi.getMyAgents() }.map {
            MyAgentsPage(
                agents = it.agents,
                created = it.created,
                quota = it.quota,
                unlimited = it.unlimited
            )
        }

    /** 获取默认智能体偏好 */
    suspend fun loadPreference(): Result<AgentPreference> =
        runEnvelope(tokenHolder) { userApi.getAgentPreference() }

    /** 设置默认智能体 */
    suspend fun setDefaultAgent(agentId: String): Result<AgentPreference> =
        runEnvelope(tokenHolder) {
            userApi.updateAgentPreference(UpdateAgentPreferenceRequest(agentId))
        }
}
