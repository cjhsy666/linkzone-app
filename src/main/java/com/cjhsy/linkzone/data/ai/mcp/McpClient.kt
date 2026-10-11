package com.cjhsy.linkzone.data.ai.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** initialize 结果（协议版本协商） */
internal data class McpInitializeResult(
    val protocolVersion: String,
    val serverName: String?,
    val serverVersion: String?,
)

/** tools/call 结果 */
internal data class McpToolCallResult(
    val isError: Boolean,
    val content: List<UIMessagePart>,
)

/**
 * MCP 客户端（单连接）：在传输层之上实现 initialize 握手、tools/list、tools/call，
 * 并把服务器发起的 ping 请求应答为空结果、其余请求回 METHOD_NOT_FOUND。
 */
internal class McpClient(
    private val transport: McpTransport,
    private val clientName: String,
    private val scope: CoroutineScope,
    /** 连接层断开（SSE 流中断等）时通知注册表触发重连 */
    private val onDisconnected: (McpClient, Throwable?) -> Unit = { _, _ -> },
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<McpJsonRpc.Incoming.Response>>()

    /** 建立连接并完成 initialize 握手 */
    suspend fun connect(requestTimeoutMs: Long = 30_000L): McpInitializeResult {
        transport.start(
            onMessage = { dispatch(it) },
            onError = { error ->
                failAllPending(error)
                onDisconnected(this, error)
            },
            onClose = {
                failAllPending(IOException("MCP 连接已关闭"))
                onDisconnected(this, null)
            },
        )
        val result = request(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", McpJsonRpc.PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", clientName)
                    put("version", CLIENT_VERSION)
                })
            },
            timeoutMs = requestTimeoutMs,
        )
        val serverInfo = result["serverInfo"] as? JsonObject
        transport.send(McpJsonRpc.buildNotification("notifications/initialized", null))
        return McpInitializeResult(
            protocolVersion = (result["protocolVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: McpJsonRpc.PROTOCOL_VERSION,
            serverName = serverInfo?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            serverVersion = serverInfo?.get("version")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
        )
    }

    /** 拉取服务器工具清单 */
    suspend fun listTools(timeoutMs: Long = 30_000L): List<McpToolDef> {
        val result = request("tools/list", JsonObject(emptyMap()), timeoutMs)
        val tools = result["tools"] as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return tools.mapNotNull { el ->
            (el as? JsonObject)?.let { parseMcpToolDef(it) }
        }
    }

    /** 调用服务器工具；isError=true 时结果内容以文本说明错误 */
    suspend fun callTool(name: String, args: JsonObject, timeoutMs: Long = 120_000L): McpToolCallResult {
        val result = request(
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
            timeoutMs = timeoutMs,
        )
        val isError = parseToolIsError(result)
        val content = result["content"] as? kotlinx.serialization.json.JsonArray
        val parts = content?.mapNotNull { el -> convertContent(el as? JsonObject) }.orEmpty()
        return McpToolCallResult(
            isError = isError,
            content = parts.ifEmpty { listOf(UIMessagePart.Text(if (isError) "工具执行出错且无返回内容" else "工具无返回内容")) },
        )
    }

    fun close() {
        failAllPending(IOException("MCP 连接已关闭"))
        transport.close()
    }

    // ------------------------------------------------------------------

    /** 发起请求并等待对应响应（含 RPC error 转异常） */
    private suspend fun request(method: String, params: JsonObject?, timeoutMs: Long): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<McpJsonRpc.Incoming.Response>()
        val key = id.toString()
        pending[key] = deferred
        try {
            transport.send(McpJsonRpc.buildRequest(id, method, params))
            val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw IOException("MCP 请求超时: $method")
            response.errorMessage?.let { throw IOException("MCP $method 失败 (${response.errorCode}): $it") }
            return response.result ?: JsonObject(emptyMap())
        } finally {
            pending.remove(key)
        }
    }

    private fun dispatch(text: String) {
        val incoming = McpJsonRpc.parse(text) ?: return
        when (incoming) {
            is McpJsonRpc.Incoming.Response ->
                pending[McpJsonRpc.idKey(incoming.id)]?.complete(incoming)

            is McpJsonRpc.Incoming.ServerRequest ->
                // 在独立协程应答，避免阻塞传输层读循环
                scope.launch { respondToServerRequest(incoming) }

            is McpJsonRpc.Incoming.ServerNotification -> Unit // 忽略服务器通知
        }
    }

    private suspend fun respondToServerRequest(request: McpJsonRpc.Incoming.ServerRequest) {
        val response = if (request.method == "ping") {
            McpJsonRpc.buildResult(request.id, JsonObject(emptyMap()))
        } else {
            McpJsonRpc.buildError(request.id, McpJsonRpc.METHOD_NOT_FOUND, "method not found: ${request.method}")
        }
        runCatching { transport.send(response) }
    }

    private fun failAllPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    /** tools/call content 单项 → UIMessagePart */
    private fun convertContent(obj: JsonObject?): UIMessagePart? {
        if (obj == null) return null
        return when (obj["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }) {
            "text" -> UIMessagePart.Text(
                obj["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
            )

            "image" -> {
                val data = obj["data"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: return UIMessagePart.Text("[MCP 图片内容缺失]")
                val mime = obj["mimeType"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: "image/png"
                // data URI：OpenAI 兼容/Claude/Google 出站编码均接受
                UIMessagePart.Image("data:$mime;base64,$data")
            }

            else -> UIMessagePart.Text(obj.toString()) // audio/resource 等类型以 JSON 文本回传
        }
    }

    private companion object {
        const val CLIENT_VERSION = "1.0"
    }
}
