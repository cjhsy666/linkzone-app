package com.cjhsy.linkzone.data.ai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.cjhsy.linkzone.ai.core.InputSchema
import java.util.UUID

/**
 * MCP 服务器配置模型（RikkaHub McpConfig 对齐，id 改为 String UUID）。
 *
 * 配置持久化在 Room llm_mcp_server 表 JSON 列；运行时状态（连接/重连）见
 * [McpSessionRegistry]，OAuth 令牌持久化回写进 [McpCommonOptions.oauth]。
 */
@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val oauth: McpOAuthState? = null,
)

/**
 * OAuth 2.1 授权状态，遵循 MCP 授权规范。
 *
 * 持久化动态客户端注册结果、授权服务器端点与令牌，用于对需要 OAuth 的
 * MCP 服务器注入 `Authorization: Bearer` 请求头并支持刷新。
 */
@Serializable
data class McpOAuthState(
    val enabled: Boolean = false,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val registrationEndpoint: String? = null,
    val redirectUri: String? = null,
    val scope: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L, // epoch millis, 0 表示未知/不过期
) {
    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank()

    // 脱敏 toString，避免 client_secret / token 随 config 打印到日志
    override fun toString(): String =
        "McpOAuthState(enabled=$enabled, clientId=$clientId, clientSecret=${clientSecret.masked()}, " +
            "authorizationEndpoint=$authorizationEndpoint, tokenEndpoint=$tokenEndpoint, " +
            "registrationEndpoint=$registrationEndpoint, redirectUri=$redirectUri, scope=$scope, " +
            "accessToken=${accessToken.masked()}, refreshToken=${refreshToken.masked()}, expiresAt=$expiresAt)"

    private fun String?.masked(): String = when {
        this == null -> "null"
        isBlank() -> "***"
        else -> "***(${length})"
    }
}

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
    val needsApproval: Boolean = false,
)

@Serializable
sealed class McpServerConfig {
    abstract val id: String
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: String = this.id,
        commonOptions: McpCommonOptions = this.commonOptions,
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: String = UUID.randomUUID().toString(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: String, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: String = UUID.randomUUID().toString(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: String, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}

/** MCP 服务器的连接地址（作为 OAuth 的 canonical resource 标识） */
val McpServerConfig.serverUrl: String
    get() = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }
