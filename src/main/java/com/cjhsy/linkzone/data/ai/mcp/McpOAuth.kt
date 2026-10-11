package com.cjhsy.linkzone.data.ai.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth 2.1 客户端 + loopback 回调服务器 + 浏览器拉起（RikkaHub oauth 模块手写适配版：
 * Ktor 嵌入式服务器 → ServerSocket；Custom Tabs → Intent ACTION_VIEW）。
 */

// ---------------------------------------------------------------------------
// OAuth HTTP 客户端（RFC 7591 注册 / PKCE / 授权码 / 刷新令牌）
// ---------------------------------------------------------------------------

/** OAuth 2.x 的通用 HTTP、PKCE、授权码和刷新令牌客户端 */
class OAuthHttpClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** RFC 7591 动态客户端注册请求 */
    @Serializable
    data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> =
            listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
        @SerialName("scope") val scope: String? = null,
    )

    @Serializable
    data class ClientRegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    data class Pkce(val verifier: String, val challenge: String)

    data class AuthorizationRequest(
        val authorizationEndpoint: String,
        val clientId: String,
        val redirectUri: String,
        val pkce: Pkce,
        val state: String,
        val scope: String? = null,
        val resources: List<String> = emptyList(),
    )

    data class AuthorizationCodeTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String? = null,
        val code: String,
        val codeVerifier: String,
        val redirectUri: String,
        val resources: List<String> = emptyList(),
    )

    data class RefreshTokenRequest(
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String? = null,
        val refreshToken: String,
        val scope: String? = null,
        val resources: List<String> = emptyList(),
    )

    suspend fun registerClient(
        registrationEndpoint: String,
        request: ClientRegistrationRequest,
    ): ClientRegistrationResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ClientRegistrationRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val text = execute(httpRequest)
        json.decodeFromString(ClientRegistrationResponse.serializer(), text)
    }

    fun generatePkce(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier = verifier, challenge = base64Url(digest))
    }

    fun generateState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    fun buildAuthorizationUrl(request: AuthorizationRequest): String {
        val base = request.authorizationEndpoint.toHttpUrlOrNull()
            ?: error("非法的授权端点: ${request.authorizationEndpoint}")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", request.clientId)
            .addQueryParameter("redirect_uri", request.redirectUri)
            .addQueryParameter("code_challenge", request.pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", request.state)
            .apply {
                if (!request.scope.isNullOrBlank()) addQueryParameter("scope", request.scope)
                request.resources.forEach { addQueryParameter("resource", it) }
            }
            .build()
            .toString()
    }

    suspend fun exchangeAuthorizationCode(
        request: AuthorizationCodeTokenRequest,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", request.code)
            .add("redirect_uri", request.redirectUri)
            .add("client_id", request.clientId)
            .add("code_verifier", request.codeVerifier)
            .apply {
                if (!request.clientSecret.isNullOrBlank()) add("client_secret", request.clientSecret)
                request.resources.forEach { add("resource", it) }
            }
            .build()
        postToken(request.tokenEndpoint, form)
    }

    suspend fun refreshToken(request: RefreshTokenRequest): TokenResponse =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", request.refreshToken)
                .add("client_id", request.clientId)
                .apply {
                    if (!request.clientSecret.isNullOrBlank()) add("client_secret", request.clientSecret)
                    if (!request.scope.isNullOrBlank()) add("scope", request.scope)
                    request.resources.forEach { add("resource", it) }
                }
                .build()
            postToken(request.tokenEndpoint, form)
        }

    private suspend fun postToken(tokenEndpoint: String, form: FormBody): TokenResponse {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val text = execute(request)
        return json.decodeFromString(TokenResponse.serializer(), text)
    }

    private suspend fun execute(request: Request): String {
        executeRaw(request).use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(300)}")
            }
            return body
        }
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ---------------------------------------------------------------------------
// Loopback 回调服务器（ServerSocket 手写版）
// ---------------------------------------------------------------------------

data class OAuthCallback(
    val code: String?,
    val state: String,
    val error: String?,
    val errorDescription: String?,
)

/**
 * 临时 OAuth loopback 回调服务器。
 *
 * 服务器只绑定 IPv4 回环地址；首个授权会话打开时启动，最后一个会话关闭时停止。
 * 同一实例可承载多个并发授权，通过 state 将回调路由到对应会话。
 *
 * redirect URI 使用 localhost 而非 127.0.0.1：部分授权服务器前置的 WAF 会拦截
 * 请求体中带 IPv4 主机的 URL，导致动态客户端注册直接 403。
 */
class OAuthLoopbackCallbackServer(
    private val port: Int = MCP_OAUTH_CALLBACK_PORT,
    private val callbackPath: String = MCP_OAUTH_CALLBACK_PATH,
) {
    private class CallbackRegistration {
        val result = CompletableDeferred<OAuthCallback>()
        val claimed = AtomicBoolean(false)
    }

    private val lifecycleMutex = Mutex()
    private val callbacks = ConcurrentHashMap<String, CallbackRegistration>()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var redirectUri: String? = null

    init {
        require(port in 0..65535) { "非法的 OAuth 回调端口: $port" }
        require(callbackPath.startsWith('/') && !callbackPath.startsWith("//")) {
            "OAuth callbackPath 必须是以单个 / 开头的绝对路径"
        }
    }

    suspend fun openSession(expectedState: String): OAuthLoopbackCallbackSession {
        require(expectedState.isNotBlank()) { "OAuth state 不能为空" }
        val registration = CallbackRegistration()
        return lifecycleMutex.withLock {
            check(callbacks.putIfAbsent(expectedState, registration) == null) {
                "OAuth state 已存在待处理的授权会话"
            }
            try {
                val uri = ensureStarted()
                OAuthLoopbackCallbackSession(
                    redirectUri = uri,
                    expectedState = expectedState,
                    callback = registration.result,
                    owner = this,
                )
            } catch (e: Exception) {
                callbacks.remove(expectedState, registration)
                stopServerIfIdle()
                throw e
            }
        }
    }

    private fun ensureStarted(): String {
        redirectUri?.let { return it }
        val socket = ServerSocket(port, 50, java.net.InetAddress.getByName(LOOPBACK_HOST))
        socket.soTimeout = 0
        serverSocket = socket
        val thread = Thread({
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                // 每个回调连接一个短生命周期线程；回调极轻量（写一段 HTML）
                Thread({ handleConnectionSafely(conn) }).start()
            }
        }, "mcp-oauth-callback")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
        return "http://$REDIRECT_HOST:${socket.localPort}$callbackPath".also {
            redirectUri = it
        }
    }

    private fun handleConnectionSafely(conn: Socket) {
        try {
            conn.use { handleConnection(it) }
        } catch (_: Throwable) {
            // 单个连接失败不影响其他回调
        }
    }

    private fun handleConnection(conn: Socket) {
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        // 读完请求头直到空行（不读取请求体——OAuth 回调为 GET）
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(" ")
        val pathWithQuery = parts.getOrNull(1) ?: return respondHtml(conn, 400, invalidCallbackHtml())
        val uri = ("http://localhost$pathWithQuery").toHttpUrlOrNull()
            ?: return respondHtml(conn, 400, invalidCallbackHtml())
        if (uri.encodedPath != callbackPath) {
            return respondHtml(conn, 404, invalidCallbackHtml())
        }

        val state = uri.queryParameter("state")
        val registration = state?.let(callbacks::get)
        if (state.isNullOrBlank() || registration == null) {
            return respondHtml(conn, 400, invalidCallbackHtml())
        }

        val code = uri.queryParameter("code")
        val error = uri.queryParameter("error")
        if (code.isNullOrBlank() && error.isNullOrBlank()) {
            return respondHtml(conn, 400, invalidCallbackHtml())
        }

        val result = OAuthCallback(
            code = code,
            state = state,
            error = error,
            errorDescription = uri.queryParameter("error_description"),
        )
        val html = if (error == null) successHtml() else errorHtml()
        if (!registration.claimed.compareAndSet(false, true)) {
            return respondHtml(conn, 409, callbackAlreadyHandledHtml())
        }
        respondHtml(conn, 200, html)
        registration.result.complete(result)
    }

    private fun respondHtml(conn: Socket, status: Int, html: String) {
        runCatching {
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                409 -> "Conflict"
                else -> "Error"
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            conn.getOutputStream().apply {
                write(head.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
        }
    }

    internal suspend fun closeSession(expectedState: String, callback: CompletableDeferred<OAuthCallback>) {
        lifecycleMutex.withLock {
            callbacks[expectedState]
                ?.takeIf { it.result === callback }
                ?.let { callbacks.remove(expectedState, it) }
            callback.cancel()
            stopServerIfIdle()
        }
    }

    private fun stopServerIfIdle() {
        if (callbacks.isEmpty()) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptThread = null
            redirectUri = null
        }
    }

    private fun successHtml() = callbackPage(
        tone = "success",
        symbol = "&#10003;",
        title = "授权完成",
        message = "账号已成功连接。",
        hint = "可以关闭此页面并返回 LinkZone。",
    )

    private fun errorHtml() = callbackPage(
        tone = "error",
        symbol = "&#10005;",
        title = "授权失败",
        message = "授权服务器未批准本次请求。",
        hint = "请关闭此页面并回到 LinkZone 重新发起授权。",
    )

    private fun invalidCallbackHtml() = callbackPage(
        tone = "warning",
        symbol = "!",
        title = "无效的授权回调",
        message = "回调信息缺失或授权会话已过期。",
        hint = "请回到 LinkZone 重新发起授权。",
    )

    private fun callbackAlreadyHandledHtml() = callbackPage(
        tone = "info",
        symbol = "i",
        title = "回调已处理",
        message = "本次授权回调已被处理过。",
        hint = "可以安全关闭此页面并返回 LinkZone。",
    )

    private fun callbackPage(tone: String, symbol: String, title: String, message: String, hint: String): String {
        fun String.escape(): String = this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="light dark">
              <title>${title.escape()} · LinkZone</title>
              <style>
                :root { color-scheme: light dark; font-family: -apple-system, "Segoe UI", Roboto, sans-serif;
                  --page:#ffffff; --text:#202124; --muted:#5f6368; }
                @media (prefers-color-scheme: dark) { :root { --page:#202124; --text:#f1f3f4; --muted:#bdc1c6; } }
                body { --accent:#1a73e8; min-height:100vh; margin:0; display:grid; place-items:center; padding:24px;
                  color:var(--text); background:var(--page); }
                body.success { --accent:#188038; }
                body.error { --accent:#d93025; }
                body.warning { --accent:#b06000; }
                main { width:min(100%, 480px); }
                .brand { font-size:20px; font-weight:600; margin:0 0 40px; }
                .status { display:flex; align-items:flex-start; gap:16px; }
                .icon { flex:0 0 44px; width:44px; height:44px; display:grid; place-items:center; color:var(--accent);
                  border:2px solid currentColor; border-radius:50%; font-size:22px; font-weight:700; }
                h1 { margin:6px 0 0; font-size:26px; }
                .message { margin-top:24px; color:var(--muted); font-size:17px; line-height:1.6; }
                .hint { margin-top:28px; padding-top:20px; color:var(--muted); font-size:15px;
                  border-top:1px solid var(--muted); }
              </style>
            </head>
            <body class="$tone">
              <main role="status">
                <p class="brand">LinkZone</p>
                <div class="status">
                  <div class="icon">$symbol</div>
                  <h1>${title.escape()}</h1>
                </div>
                <p class="message">${message.escape()}</p>
                <p class="hint">${hint.escape()}</p>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val REDIRECT_HOST = "localhost"
    }
}

class OAuthLoopbackCallbackSession internal constructor(
    val redirectUri: String,
    private val expectedState: String,
    private val callback: CompletableDeferred<OAuthCallback>,
    private val owner: OAuthLoopbackCallbackServer,
) {
    private val closed = AtomicBoolean(false)

    /** 等待浏览器回调；超时返回 null */
    suspend fun awaitCallback(timeoutMs: Long): OAuthCallback? =
        withTimeoutOrNull(timeoutMs) { callback.await() }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.closeSession(expectedState, callback)
        }
    }
}

/** 用系统浏览器（ACTION_VIEW）打开 OAuth 授权页面（无 Custom Tabs 依赖） */
object IntentOAuthAuthorizationLauncher {
    fun launch(context: Context, authorizationUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

/** OAuth 授权 URL 编码辅助（浏览器地址栏展示用） */
internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

const val MCP_OAUTH_CALLBACK_PORT = 52_134
const val MCP_OAUTH_CALLBACK_PATH = "/oauth/callback"
const val MCP_OAUTH_REDIRECT_URI = "http://localhost:$MCP_OAUTH_CALLBACK_PORT$MCP_OAUTH_CALLBACK_PATH"
