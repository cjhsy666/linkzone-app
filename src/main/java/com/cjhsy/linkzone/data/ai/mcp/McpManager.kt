package com.cjhsy.linkzone.data.ai.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.repository.LlmRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth 与连接注册表；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 * 配置存储为 Room llm_mcp_server 表（经 [LlmRepository]），配置流驱动自动重连。
 */
class McpManager(
    private val llmRepository: LlmRepository,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 工具调用可长时间运行（读超时 10 分钟）；禁止挂日志拦截器（头里可能带 token）
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val statusStore = McpStatusStore()
    private val oauthCallbackServer = OAuthLoopbackCallbackServer(
        port = MCP_OAUTH_CALLBACK_PORT,
        callbackPath = MCP_OAUTH_CALLBACK_PATH,
    )
    private val oauthCoordinator = McpOAuthCoordinator(
        llmRepository = llmRepository,
        appScope = appScope,
        oauthClient = OAuthHttpClient(okHttpClient),
        discoveryClient = McpOAuthDiscoveryClient(okHttpClient),
        callbackServer = oauthCallbackServer,
        updateStatus = statusStore::update,
    )
    private val sessionRegistry = McpSessionRegistry(
        llmRepository = llmRepository,
        appScope = appScope,
        okHttpClient = okHttpClient,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
    )

    init {
        appScope.launch {
            llmRepository.observeMcpServers()
                .distinctUntilChanged()
                .collect { sessionRegistry.reconcile(it) }
        }
    }

    /** 全部服务器状态（配置 id → 状态），供设置页展示 */
    val syncingStatus: StateFlow<Map<String, McpStatus>>
        get() = statusStore.status

    fun getStatus(configId: String): Flow<McpStatus> = sessionRegistry.getStatus(configId)

    /**
     * Agent 可用的全部 MCP 工具：
     * (服务器 id, 服务器名, 工具定义)，仅含启用服务器中启用的工具。
     */
    suspend fun getAllAvailableTools(agentMcpServers: Set<String>): List<Triple<String, String, McpTool>> {
        if (agentMcpServers.isEmpty()) return emptyList()
        return llmRepository.getMcpServers()
            .filter { it.commonOptions.enable && it.id in agentMcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: String, toolName: String, args: JsonObject): List<UIMessagePart> {
        return try {
            val result = sessionRegistry.callTool(serverId, toolName, args)
            result.content
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            listOf(UIMessagePart.Text("Failed to execute MCP tool: ${e.message ?: e.javaClass.name}"))
        } catch (e: Exception) {
            Log.w(TAG, "MCP tool call failed: $toolName on $serverId", e)
            listOf(UIMessagePart.Text("Failed to execute MCP tool: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(configId: String) {
        oauthCoordinator.cancelAuthorization(configId)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    private companion object {
        const val TAG = "McpManager"
    }
}
