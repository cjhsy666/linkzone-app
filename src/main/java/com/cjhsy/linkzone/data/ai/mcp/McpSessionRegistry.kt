package com.cjhsy.linkzone.data.ai.mcp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.cjhsy.linkzone.data.repository.LlmRepository
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "McpSessionRegistry"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

/** 单个 MCP 服务器的全部运行时状态 */
private class McpSession(initialConfig: McpServerConfig) {
    @Volatile
    var config: McpServerConfig = initialConfig

    @Volatile
    var client: McpClient? = null

    @Volatile
    var connectedConfig: McpServerConfig? = null

    val lifecycleMutex = Mutex()
    var reconnectJob: Job? = null
    var reconnectAttempt: Int = 0
}

private sealed interface ConnectResult {
    data object Success : ConnectResult
    data object Stale : ConnectResult
    data object NeedsAuthorization : ConnectResult
    data object Failed : ConnectResult
}

internal class McpClientUnavailableException(message: String) : IllegalStateException(message)

internal class McpStatusStore {
    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpStatus>> = _status.asStateFlow()

    fun get(configId: String): Flow<McpStatus> =
        status.map { it[configId] ?: McpStatus.Idle }.distinctUntilChanged()

    fun update(configId: String, status: McpStatus) {
        _status.update { current -> current + (configId to status) }
    }

    fun remove(configId: String) {
        _status.update { current -> current - configId }
    }
}

/**
 * MCP 连接运行时注册表（RikkaHub McpSessionRegistry 对齐，存储改为 Room/LlmRepository）。
 *
 * 每个 serverId 对应一个 [McpSession]，该 Session 的连接、同步、关闭和重连通过
 * 同一把 Mutex 串行执行。Client 只有在 connect 与首次工具同步都成功后才对外可见。
 */
internal class McpSessionRegistry(
    private val llmRepository: LlmRepository,
    private val appScope: CoroutineScope,
    private val okHttpClient: OkHttpClient,
    private val oauthCoordinator: McpOAuthCoordinator,
    private val statusStore: McpStatusStore,
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    fun getClient(configId: String): McpClient? = sessions[configId]?.client

    fun getStatus(configId: String): Flow<McpStatus> = statusStore.get(configId)

    /** 配置流驱动：新增连接、参数变化重连、移除断开 */
    fun reconcile(configs: List<McpServerConfig>) {
        val activeConfigs = configs
            .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
            .associateBy { it.id }

        (sessions.keys - activeConfigs.keys).forEach { configId ->
            val detached = sessions.remove(configId) ?: return@forEach
            oauthCoordinator.forget(configId)
            statusStore.remove(configId)
            appScope.launch { closeSession(detached) }
        }

        activeConfigs.values.forEach { newConfig ->
            val existing = sessions[newConfig.id]
            if (existing == null) {
                val session = McpSession(newConfig)
                if (sessions.putIfAbsent(newConfig.id, session) == null) {
                    appScope.launch { addClient(newConfig) }
                }
                return@forEach
            }

            val mustReconnect = !hasSameConnectionParameters(existing.config, newConfig)
            existing.config = newConfig
            if (mustReconnect) {
                appScope.launch { addClient(newConfig) }
            }
        }
    }

    suspend fun callTool(serverId: String, toolName: String, args: JsonObject): McpToolCallResult {
        val session = sessions[serverId]
            ?: throw McpClientUnavailableException("No MCP session for server $serverId")
        val freshConfig = oauthCoordinator.ensureFreshToken(session.config)
        if (!hasSameConnectionParameters(session.connectedConfig, freshConfig)) {
            session.config = freshConfig
            addClient(freshConfig)
        }

        val client = session.client
            ?: throw McpClientUnavailableException("MCP client $serverId is not connected")
        val config = session.connectedConfig ?: session.config
        Log.i(TAG, "Calling tool $toolName on $serverId (${config.commonOptions.name})")
        return try {
            client.callTool(toolName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (oauthCoordinator.needsAuthorization(config, e)) {
                statusStore.update(config.id, McpStatus.NeedsAuthorization)
            } else {
                // 连接可能已失效：置空客户端，下次调用自动重连
                invalidateClient(session)
            }
            throw e
        }
    }

    suspend fun addClient(configInput: McpServerConfig) {
        // 配置真源是 Room。旧任务排队后可能晚于新配置执行，不能写回旧快照。
        val desiredConfig = llmRepository.getMcpServer(configInput.id) ?: run {
            removeClient(configInput)
            return
        }
        if (!desiredConfig.commonOptions.enable || desiredConfig.commonOptions.name.isBlank()) {
            removeClient(desiredConfig)
            return
        }

        val session = sessions.computeIfAbsent(desiredConfig.id) { McpSession(desiredConfig) }
        session.config = desiredConfig
        connectSession(
            session = session,
            requestedConfig = desiredConfig,
            cancelPendingReconnect = true,
            forceReconnect = false,
        )
    }

    suspend fun removeClient(config: McpServerConfig) {
        val session = sessions.remove(config.id)
        oauthCoordinator.forget(config.id)
        if (session != null) closeSession(session)
        statusStore.remove(config.id)
    }

    suspend fun syncAll() {
        sessions.values.toList().forEach { session -> syncSession(session) }
    }

    private fun invalidateClient(session: McpSession) {
        val client = session.client ?: return
        session.client = null
        session.connectedConfig = null
        closeClient(client, session.config.commonOptions.name)
    }

    private suspend fun connectSession(
        session: McpSession,
        requestedConfig: McpServerConfig,
        cancelPendingReconnect: Boolean,
        forceReconnect: Boolean,
    ): ConnectResult = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            if (sessions[requestedConfig.id] !== session) return@withLock ConnectResult.Stale
            if (!hasSameConnectionParameters(session.config, requestedConfig)) {
                return@withLock ConnectResult.Stale
            }

            if (cancelPendingReconnect) {
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.reconnectAttempt = 0
            }

            val config = oauthCoordinator.ensureFreshToken(session.config)
            session.config = config
            if (!forceReconnect &&
                session.client != null &&
                hasSameConnectionParameters(session.connectedConfig, config)
            ) {
                return@withLock ConnectResult.Success
            }

            statusStore.update(config.id, McpStatus.Connecting)
            val oldClient = session.client
            session.client = null
            session.connectedConfig = null
            oldClient?.let { closeClient(it, config.commonOptions.name) }

            val client = createClient(config)
            try {
                client.connect()
                val syncedConfig = syncTools(session, client, config)
                if (sessions[config.id] !== session ||
                    !hasSameConnectionParameters(config, syncedConfig)
                ) {
                    closeClient(client, config.commonOptions.name)
                    return@withLock ConnectResult.Stale
                }

                session.config = syncedConfig
                session.connectedConfig = syncedConfig
                session.client = client
                session.reconnectAttempt = 0
                statusStore.update(config.id, McpStatus.Connected)
                Log.i(TAG, "Connected MCP server ${config.id} (${config.commonOptions.name})")
                ConnectResult.Success
            } catch (e: CancellationException) {
                closeClient(client, config.commonOptions.name)
                throw e
            } catch (e: Exception) {
                closeClient(client, config.commonOptions.name)
                Log.e(TAG, "Failed to connect MCP server ${config.id}", e)
                if (oauthCoordinator.needsAuthorization(config, e)) {
                    statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    ConnectResult.NeedsAuthorization
                } else {
                    statusStore.update(config.id, McpStatus.Error.from(e))
                    ConnectResult.Failed
                }
            }
        }
    }

    private suspend fun syncSession(session: McpSession) {
        val config = session.config
        if (session.client == null) {
            addClient(config)
            return
        }

        var reconnectConfig: McpServerConfig? = null
        withContext(Dispatchers.IO) {
            session.lifecycleMutex.withLock {
                val client = session.client ?: return@withLock
                val connectedConfig = session.connectedConfig ?: return@withLock
                statusStore.update(config.id, McpStatus.Connecting)
                try {
                    val syncedConfig = syncTools(session, client, session.config)
                    session.config = syncedConfig
                    if (hasSameConnectionParameters(connectedConfig, syncedConfig)) {
                        session.connectedConfig = syncedConfig
                        statusStore.update(config.id, McpStatus.Connected)
                    } else {
                        reconnectConfig = syncedConfig
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (oauthCoordinator.needsAuthorization(config, e)) {
                        statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    } else {
                        statusStore.update(config.id, McpStatus.Error.from(e))
                    }
                }
            }
        }
        reconnectConfig?.let { addClient(it) }
    }

    /** 拉取服务器工具清单并合并用户开关后回写 Room */
    private suspend fun syncTools(
        session: McpSession,
        client: McpClient,
        connectionConfig: McpServerConfig,
    ): McpServerConfig {
        val serverTools = client.listTools()
        Log.i(TAG, "Synced ${serverTools.size} tools from ${connectionConfig.id}")
        var updatedConfig = connectionConfig
        llmRepository.updateMcpServer(connectionConfig.id) { storedConfig ->
            val tools = mergeTools(storedConfig.commonOptions.tools, serverTools)
            storedConfig.clone(commonOptions = storedConfig.commonOptions.copy(tools = tools))
                .also { updatedConfig = it }
        }
        session.config = updatedConfig
        return updatedConfig
    }

    /** 合并重复的断连通知，并保证每个 Session 最多只有一个重连任务 */
    private fun requestReconnect(
        configId: String,
        sourceClient: McpClient?,
        retryAfterFailure: Boolean = false,
    ) {
        appScope.launch {
            val session = sessions[configId] ?: return@launch
            session.lifecycleMutex.withLock {
                if (sessions[configId] !== session) return@withLock
                if (sourceClient != null && session.client !== sourceClient) return@withLock
                if (!retryAfterFailure && statusStore.status.value[configId] != McpStatus.Connected) {
                    return@withLock
                }
                if (session.reconnectJob?.isActive == true) return@withLock

                val attempt = session.reconnectAttempt + 1
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    val failedClient = session.client
                    session.client = null
                    session.connectedConfig = null
                    failedClient?.let { closeClient(it, session.config.commonOptions.name) }
                    statusStore.update(configId, McpStatus.Error("连接断开，已达最大重连次数"))
                    return@withLock
                }

                session.reconnectAttempt = attempt
                statusStore.update(configId, McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
                session.reconnectJob = appScope.launch {
                    reconnectAfterDelay(session, calculateBackoffDelay(attempt))
                }
            }
        }
    }

    private suspend fun reconnectAfterDelay(session: McpSession, delayMs: Long) {
        val runningJob = currentCoroutineContext().job
        var retry = false
        try {
            delay(delayMs)
            val latestConfig = llmRepository.getMcpServers().find {
                it.id == session.config.id &&
                    it.commonOptions.enable &&
                    it.commonOptions.name.isNotBlank()
            } ?: return
            session.config = latestConfig
            retry = connectSession(
                session = session,
                requestedConfig = latestConfig,
                cancelPendingReconnect = false,
                forceReconnect = true,
            ) == ConnectResult.Failed
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable) {
                session.lifecycleMutex.withLock {
                    if (session.reconnectJob === runningJob) session.reconnectJob = null
                }
            }
        }

        if (retry) requestReconnect(session.config.id, sourceClient = null, retryAfterFailure = true)
    }

    private suspend fun closeSession(session: McpSession) = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            session.reconnectJob?.cancel()
            session.reconnectJob = null
            session.reconnectAttempt = 0
            val client = session.client
            session.client = null
            session.connectedConfig = null
            client?.let { closeClient(it, session.config.commonOptions.name) }
        }
    }

    private fun closeClient(client: McpClient, serverName: String) {
        runCatching { client.close() }
            .onFailure { Log.w(TAG, "Failed to close MCP client $serverName", it) }
    }

    private fun createClient(config: McpServerConfig): McpClient {
        val transport = when (config) {
            is McpServerConfig.SseTransportServer -> McpSseTransport(
                url = config.url,
                client = okHttpClient,
                headers = config.resolvedHeaders(),
                scope = appScope,
            )

            is McpServerConfig.StreamableHTTPServer -> McpStreamableHttpTransport(
                url = config.url,
                client = okHttpClient,
                headers = config.resolvedHeaders(),
            )
        }
        return McpClient(
            transport = transport,
            clientName = config.commonOptions.name.ifBlank { "linkzone" },
            scope = appScope,
            onDisconnected = { client, _ ->
                // 传输层断开（旧版 SSE 流中断）→ 走统一重连路径
                requestReconnect(config.id, client)
            },
        )
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
}

/** 只包含会影响实际连接的字段；工具开关和 Schema 变化不会触发重连 */
internal data class McpConnectionKey(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
)

internal fun McpServerConfig.connectionKey(): McpConnectionKey = McpConnectionKey(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedHeaders(),
)

private fun hasSameConnectionParameters(
    left: McpServerConfig?,
    right: McpServerConfig?,
): Boolean = left != null && right != null && left.connectionKey() == right.connectionKey()

private fun McpServerConfig.resolvedHeaders(): List<Pair<String, String>> {
    // 设置页“添加请求头”后未填写会留下空名称，OkHttp 会直接抛出 "name is empty"
    val base = commonOptions.headers.filter { it.first.isNotBlank() }
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}

/** 保留用户工具开关，合并服务器最新工具清单 */
private fun mergeTools(storedTools: List<McpTool>, serverTools: List<McpToolDef>): List<McpTool> {
    val toolsByName = storedTools.associateBy { it.name }
    return serverTools.map { serverTool ->
        toolsByName[serverTool.name]?.copy(
            description = serverTool.description,
            inputSchema = serverTool.inputSchema,
        ) ?: McpTool(
            name = serverTool.name,
            description = serverTool.description,
            enable = true,
            inputSchema = serverTool.inputSchema,
        )
    }
}
