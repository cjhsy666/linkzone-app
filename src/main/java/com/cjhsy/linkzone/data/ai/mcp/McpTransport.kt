package com.cjhsy.linkzone.data.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MCP 传输层（手写实现，替代 MCP 官方 SDK 的 Ktor 传输）。
 *
 * - [McpStreamableHttpTransport]：Streamable HTTP（POST 请求，响应可为 JSON 或 SSE 流，
 *   会话经 Mcp-Session-Id 响应头维持）
 * - [McpSseTransport]：旧版 SSE（GET 事件流 + `endpoint` 事件给出 POST 地址，
 *   服务器响应经 `message` 事件回传）
 */
internal interface McpTransport {
    /**
     * 建立连接。onMessage 收到服务器下发的 JSON-RPC 文本（响应/请求/通知）；
     * onError 在连接层出错时回调；onClose 在流正常结束时回调。
     */
    suspend fun start(
        onMessage: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onClose: () -> Unit,
    )

    /** 发送一条 JSON-RPC 文本（请求/响应/通知） */
    suspend fun send(message: String)

    /** 关闭连接（幂等） */
    fun close()
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** OkHttp 异步执行；协程取消时取消底层 Call（call timeout 覆盖到响应体读取） */
internal suspend fun executeHttp(
    client: OkHttpClient,
    request: Request,
    timeoutMs: Long = 0L,
): Response = suspendCancellableCoroutine { cont ->
    val call = client.newCall(request)
    if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** SSE 单事件（event 名 + data 载荷） */
private class SseEvent(
    val event: String,
    val data: String,
)

/** 逐行解析 SSE 流；每个完整事件回调一次，读到流尾返回 */
private fun Response.forEachSseEvent(onEvent: (SseEvent) -> Unit) {
    val source = body?.source() ?: return
    var eventName = ""
    val data = StringBuilder()
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    onEvent(SseEvent(eventName.ifEmpty { "message" }, data.toString()))
                }
                eventName = ""
                data.setLength(0)
            }

            line.startsWith(":") -> Unit // 注释/心跳
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
    }
    if (data.isNotEmpty()) {
        onEvent(SseEvent(eventName.ifEmpty { "message" }, data.toString()))
    }
}

// ---------------------------------------------------------------------------
// Streamable HTTP
// ---------------------------------------------------------------------------

internal class McpStreamableHttpTransport(
    private val url: String,
    private val client: OkHttpClient,
    private val headers: List<Pair<String, String>>,
    private val requestTimeoutMs: Long = 120_000L,
) : McpTransport {

    @Volatile
    private var sessionId: String? = null

    private var startMessages: Triple<
        (String) -> Unit,
        (Throwable) -> Unit,
        () -> Unit,
        >? = null

    @Volatile
    private var closed = false

    override suspend fun start(
        onMessage: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onClose: () -> Unit,
    ) {
        // Streamable HTTP 无需持久入站流；POST 响应内联返回。
        // 最小实现不打开 GET 推送流：服务器主动通知/请求被忽略（工具调用均为客户端发起）。
        startMessages = Triple(onMessage, onError, onClose)
    }

    override suspend fun send(message: String) {
        if (closed) throw IOException("MCP 传输已关闭")
        val isRequest = McpJsonRpc.isRequest(message)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .headers(headers.toOkHttpHeaders())
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(message.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = executeHttp(client, request, if (isRequest) requestTimeoutMs else 30_000L)
        response.use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            when {
                // 通知/响应无需回复内容
                resp.code == 202 -> return
                !resp.isSuccessful -> {
                    val bodyText = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                    throw IOException("MCP HTTP ${resp.code}: ${bodyText.take(300)}")
                }
            }

            val contentType = resp.header("Content-Type") ?: ""
            if (!isRequest) return

            val idKey = McpJsonRpc.requestIdKey(message) ?: return
            val (onMessage, _, _) = startMessages ?: return
            val matcher = ResponseMatcher(idKey)
            if (contentType.contains("text/event-stream")) {
                resp.forEachSseEvent { event ->
                    dispatchStreamMessage(event.data, onMessage, matcher)
                }
            } else {
                val text = resp.body?.string() ?: ""
                dispatchStreamMessage(text, onMessage, matcher)
            }
            matcher.ensureMatched()
        }
    }

    /** 转发流内消息；遇到匹配 id 的响应时完成等待 */
    private fun dispatchStreamMessage(
        text: String,
        onMessage: (String) -> Unit,
        matcher: ResponseMatcher,
    ) {
        val incoming = McpJsonRpc.parse(text) ?: return
        if (incoming is McpJsonRpc.Incoming.Response && McpJsonRpc.idKey(incoming.id) == matcher.idKey) {
            matcher.complete(text)
        } else {
            onMessage(text)
        }
    }

    override fun close() {
        closed = true
        // 规范建议 DELETE 终止会话；尽力而为
        val id = sessionId ?: return
        val request = Request.Builder()
            .url(url)
            .header("Mcp-Session-Id", id)
            .headers(headers.toOkHttpHeaders())
            .delete()
            .build()
        runCatching { client.newCall(request).execute().use { } }
    }

    /** 匹配响应（POST 返回前一定已经过流/JSON 体的同步分发） */
    private class ResponseMatcher(val idKey: String) {
        @Volatile
        private var matched: String? = null

        fun complete(text: String) {
            matched = text
        }

        fun ensureMatched() {
            if (matched == null) {
                throw IOException("MCP 响应流中未收到请求的响应消息")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 旧版 SSE 传输
// ---------------------------------------------------------------------------

internal class McpSseTransport(
    private val url: String,
    private val client: OkHttpClient,
    private val headers: List<Pair<String, String>>,
    private val scope: CoroutineScope,
) : McpTransport {

    private val endpointDeferred = CompletableDeferred<String>()
    private var readerJob: Job? = null

    @Volatile
    private var streamCall: Call? = null

    @Volatile
    private var closed = false

    override suspend fun start(
        onMessage: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onClose: () -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .headers(headers.toOkHttpHeaders())
            .get()
            .build()

        readerJob = scope.launch(Dispatchers.IO) {
            try {
                val call = client.newCall(request)
                streamCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("MCP SSE HTTP ${resp.code}")
                    }
                    resp.forEachSseEvent { event ->
                        when (event.event) {
                            "endpoint" -> {
                                val endpoint = resolveEndpoint(event.data)
                                endpointDeferred.complete(endpoint)
                            }

                            else -> onMessage(event.data) // message 及其他命名事件均按消息分发
                        }
                    }
                }
                if (!closed) onClose()
            } catch (e: CancellationException) {
                // close() 取消读取，不算错误
            } catch (e: Throwable) {
                if (!closed) onError(e)
            }
        }
    }

    override suspend fun send(message: String) {
        if (closed) throw IOException("MCP 传输已关闭")
        val endpoint = withTimeoutOrNull(30_000L) { endpointDeferred.await() }
            ?: throw IOException("MCP SSE endpoint 未就绪")
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .headers(headers.toOkHttpHeaders())
            .post(message.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeHttp(client, request, 30_000L).use { resp ->
            if (!resp.isSuccessful && resp.code != 202) {
                val bodyText = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                throw IOException("MCP HTTP ${resp.code}: ${bodyText.take(300)}")
            }
        }
    }

    override fun close() {
        closed = true
        readerJob?.cancel()
        streamCall?.cancel()
    }

    /** endpoint 事件数据可为绝对 URL 或相对路径 */
    private fun resolveEndpoint(data: String): String {
        val trimmed = data.trim()
        trimmed.toHttpUrlOrNull()?.let { return it.toString() }
        val base = url.toHttpUrlOrNull() ?: throw IOException("非法的 MCP SSE 地址: $url")
        return base.resolve(trimmed)?.toString()
            ?: throw IOException("无法解析 MCP SSE endpoint: $trimmed")
    }
}

/** Headers.Builder 全版本 okhttp 通用（headersOf/Headers.of 在不同版本间更名或弃用） */
private fun List<Pair<String, String>>.toOkHttpHeaders(): okhttp3.Headers =
    okhttp3.Headers.Builder().apply {
        forEach { (name, value) -> add(name, value) }
    }.build()
