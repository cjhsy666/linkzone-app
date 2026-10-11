package com.cjhsy.linkzone.data.remote

import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.core.storage.SecureStore
import com.cjhsy.linkzone.data.model.MessageSegment
import com.cjhsy.linkzone.data.model.displayText
import com.cjhsy.linkzone.di.BASE_URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.SystemClock
import kotlin.math.min

/**
 * 聊天 WebSocket 管理器（LinkZone 适配器实时通道）。
 *
 * - 连接：ws(s)://<BASE_URL host>/api/v1/linkzone/ws?token=<access_token>
 * - 心跳：每 15s 发送 {"type":"ping"}
 * - 重连：断线后指数退避（1s → 2s → 4s … 上限 30s）
 * - Token 自恢复：WS 客户端不挂 OkHttp Authenticator。当内存 token 缺失
 *   （冷启动/进程重建）或握手被服务端以 401/403 拒绝（token 过期）时，
 *   重连协程先用 refresh_token 调 [ensureAccessToken] 换新 token 再连接，
 *   刷新失败也不清凭证、不断重连链（可能是瞬时网络问题）。
 */
class ChatSocketManager(
    private val okHttpClient: OkHttpClient,
    private val tokenHolder: TokenHolder,
    private val json: Json,
    private val secureStore: SecureStore,
    private val refreshApiProvider: () -> RefreshApi
) {

    sealed interface Event {
        /** 连接建立（服务端已确认身份） */
        data class Connected(val linkzoneId: String, val username: String) : Event

        /** 收到聊天消息（智能体/插件回复 / 群消息 / 自己消息的群内回显） */
        data class ChatMessage(
            val messageId: String,
            val senderId: String,
            val senderName: String,
            val receiverId: String,
            val segments: List<MessageSegment>,
            val text: String,
            val timestamp: Long,
            /** 回复归属会话（服务端持久化后回传，空串=旧服务端） */
            val convId: String = ""
        ) : Event

        /** 消息撤回广播（convId 为空表示旧版服务端未携带，按全局处理） */
        data class Recalled(val messageId: String, val convId: String = "") : Event

        /** 连接断开（即将自动重连） */
        data object Disconnected : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    /** 连接生命周期锁（connect/disconnect 可能同时来自主线程与 IO 重连协程） */
    private val connLock = Any()

    @Volatile
    private var manualClosed = false

    @Volatile
    private var reconnectAttempts = 0

    /**
     * 最近一次从服务端收到帧（onOpen/任意消息/pong 由 OkHttp 内部处理）的时间。
     * 用于回前台探活：后台期间系统挂起网络后，socket 可能假死
     * （对象还在但实际已断），仅靠读超时自愈要等 20+ 秒。
     */
    @Volatile
    private var lastActivityAt = 0L

    /** 上次握手被服务端以 401/403 拒绝（token 过期），重连前必须先刷新 */
    @Volatile
    private var authFailed = false

    /** 被服务端拒绝的那个 token，用于识别其他路径是否已并发刷新成功 */
    @Volatile
    private var rejectedToken: String? = null

    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null

    val isConnected: Boolean
        get() = webSocket != null

    /** 建立 / 复活连接（幂等）。显式调用视为恢复连接，清除手动断开标记 */
    fun connect() {
        synchronized(connLock) {
            manualClosed = false
            if (webSocket != null) return
        }
        if (tokenHolder.accessToken != null) {
            openConnection()
        } else {
            // 内存无 token（冷启动/进程重建）：立即刷新凭证再连接，重连链不能断
            scheduleReconnect(immediate = true)
        }
    }

    /** 用当前内存 token 真正发起 WS 连接（connLock 内完成状态切换） */
    private fun openConnection() {
        var missingToken = false
        synchronized(connLock) {
            if (manualClosed) return
            if (webSocket != null) return
            val token = tokenHolder.accessToken
            if (token == null) {
                missingToken = true
            } else {
                val request = Request.Builder().url(buildWsUrl(token)).build()
                webSocket = okHttpClient.newWebSocket(request, listener)
            }
        }
        // token 在锁外被清空等极端情况：立即走刷新重连
        if (missingToken) scheduleReconnect(immediate = true)
    }

    /** 主动断开（登出/页面销毁），不再自动重连 */
    fun disconnect() {
        synchronized(connLock) {
            manualClosed = true
            heartbeatJob?.cancel()
            heartbeatJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            webSocket?.close(1000, "client closed")
            webSocket = null
        }
    }

    /**
     * App 回前台时调用（幂等）：
     * - 无连接：立即发起连接（走 connect 原逻辑，含 token 自恢复）
     * - 有连接但长时间没收到任何帧（后台网络被挂起后的假死连接）：
     *   主动掐断，让 onClosed/onFailure 走标准重连链，避免傻等读超时
     * - 连接健康：不动
     */
    fun resume() {
        val ws = synchronized(connLock) { webSocket }
        if (ws == null) {
            connect()
            return
        }
        val idleMs = SystemClock.elapsedRealtime() - lastActivityAt
        if (lastActivityAt > 0L && idleMs > STALE_CONNECTION_MS) {
            synchronized(connLock) {
                if (webSocket === ws) {
                    webSocket = null
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                }
            }
            runCatching { ws.close(1000, "resume probe") }
            scheduleReconnect(immediate = true)
        }
    }

    /** 发送 JSON 文本（仅连接打开时成功） */
    fun send(text: String): Boolean {
        val ws = webSocket ?: return false
        return ws.send(text)
    }

    /**
     * 发送结构化消息段列表（支持富媒体 / 多种段类型）。
     * 与后端框架 [pkg/message.Segment] 对齐，包含 sender_id / conv_id / platform 等必要字段。
     */
    fun sendSegments(
        segments: List<MessageSegment>,
        convId: String,
        senderId: String,
        receiverId: String,
        botId: String,
        messageId: String
    ): Boolean {
        val ws = webSocket ?: return false
        val json = buildJsonObject {
            put("type", "message")
            put("platform", "linkzone")
            put("message_id", messageId)
            put("bot_id", botId)
            put("sender_id", senderId)
            put("conv_id", convId)
            put("receiver_type", "private")
            put("receiver_id", receiverId)
            put("timestamp", System.currentTimeMillis() * 1000)
            put("segments", JsonArray(segments.map { seg ->
                buildJsonObject {
                    put("type", seg.type)
                    put("data", JsonObject(
                        seg.data.mapValues { (_, v) ->
                            when (v) {
                                is JsonPrimitive -> v
                                is JsonArray -> v
                                is JsonObject -> v
                                else -> JsonPrimitive(v.toString())
                            }
                        }
                    ))
                }
            }))
        }
        return ws.send(json.toString())
    }

    // ============ 内部 ============

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            authFailed = false
            rejectedToken = null
            lastActivityAt = SystemClock.elapsedRealtime()
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastActivityAt = SystemClock.elapsedRealtime()
            handleRawMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // WS 升级被拒：token 过期/无效，重连前必须先刷新
            if (response != null && (response.code == 401 || response.code == 403)) {
                authFailed = true
                rejectedToken = tokenHolder.accessToken
            }
            cleanup(webSocket)
            if (!manualClosed) {
                scope.launch { _events.emit(Event.Disconnected) }
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            cleanup(webSocket)
            if (!manualClosed) {
                scope.launch { _events.emit(Event.Disconnected) }
                scheduleReconnect()
            }
        }
    }

    private fun cleanup(failed: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        // 只清理触发回调的旧连接，避免竞态下把已经建好的新连接置空
        synchronized(connLock) {
            if (webSocket === failed) webSocket = null
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                send("""{"type":"ping"}""")
            }
        }
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (!immediate) {
                reconnectAttempts++
                val backoff = min(1000L * (1L shl min(reconnectAttempts, 5)), 30_000L)
                delay(backoff)
            }
            if (manualClosed) return@launch

            // 内存 token 缺失，或上次握手被 401/403 拒绝：
            // 先用 refresh_token 换新 access token 再连接，
            // 否则会永远拿着旧 token 撞 401（服务端表现为"断开后再无新连接"）
            if (authFailed || tokenHolder.accessToken == null) {
                if (!ensureAccessToken()) {
                    // 刷新失败（多为瞬时网络问题）：保留凭证，退避后继续尝试，重连链不断
                    scheduleReconnect()
                    return@launch
                }
            }
            openConnection()
        }
    }

    /**
     * 用 refresh_token 换取新 access token（WS 专用恢复路径）。
     *
     * 与 [com.cjhsy.linkzone.core.network.TokenAuthenticator] 的区别：
     * 刷新失败时不清空凭证、不标记会话失效——可能只是瞬时网络问题，
     * 由重连循环退避后继续尝试；真正的 refresh 失效由 REST 路径处理跳登录。
     */
    private suspend fun ensureAccessToken(): Boolean {
        // 双重检查：TokenAuthenticator 可能已并发刷新成功，直接认账，
        // 避免用同一 refresh_token 重复刷新（双 token 轮换会导致其中一方失败）
        tokenHolder.accessToken?.takeIf { it != rejectedToken }?.let {
            authFailed = false
            return true
        }

        val refreshToken = secureStore.getRefreshToken() ?: return false
        val ok = try {
            val envelope = refreshApiProvider().refresh(RefreshBody(refreshToken))
            val data = envelope.data
            if (envelope.isSuccess && data != null && data.token.isNotEmpty()) {
                tokenHolder.update(data.token, data.expiresAt)
                data.refreshToken
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { secureStore.saveRefreshToken(it) }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }

        if (ok) {
            authFailed = false
            return true
        }

        // 自身刷新失败，但其他路径（TokenAuthenticator）已把 token 更新为新值：认账
        return tokenHolder.accessToken?.takeIf { it != rejectedToken }?.let {
            authFailed = false
            true
        } ?: false
    }

    private fun handleRawMessage(text: String) {
        // 整体兜底：单个字段类型异常（如服务端把字符串给成对象）不应炸掉
        // OkHttp 分发线程，否则可能触发非预期的 onFailure/断连
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        runCatching {
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "connected" -> {
                val linkzoneId = obj["linkzone_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val username = obj["user_info"]
                    ?.let { runCatching { it.jsonObject["username"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                    ?: ""
                scope.launch { _events.emit(Event.Connected(linkzoneId, username)) }
            }

            "message" -> {
                val messageId = obj["message_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val senderId = obj["sender_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val senderName = obj["sender_name"]?.jsonPrimitive?.contentOrNull ?: ""
                val receiverId = obj["receiver_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val convId = obj["conv_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val segments = parseSegments(obj["segments"])
                val textContent = segments.displayText()
                    .ifBlank { obj["message"]?.jsonPrimitive?.contentOrNull ?: "" }
                val ts = normalizeMicro(obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
                scope.launch {
                    _events.emit(
                        Event.ChatMessage(
                            messageId, senderId, senderName, receiverId,
                            segments, textContent, ts, convId
                        )
                    )
                }
            }

            "recall" -> {
                val messageId = obj["message_id"]?.jsonPrimitive?.contentOrNull ?: ""
                val convId = obj["conv_id"]?.jsonPrimitive?.contentOrNull ?: ""
                if (messageId.isNotEmpty()) {
                    scope.launch { _events.emit(Event.Recalled(messageId, convId)) }
                }
            }
            // pong 忽略
            }
        }
    }

    private fun parseSegments(element: kotlinx.serialization.json.JsonElement?): List<MessageSegment> {
        val arr = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { item ->
            runCatching {
                val segObj = item.jsonObject
                val type = segObj["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                // data 正常是对象；兼容旧服务端/异常数据：裸字符串归到 "text"，
                // null / 其他类型降级为空 map，避免整段丢失。
                // 注意各分支必须统一为 Map<String, JsonElement>，不能混入裸 String
                val data: Map<String, JsonElement> = when (val d = segObj["data"]) {
                    is JsonObject -> d.toMap()
                    is JsonPrimitive ->
                        if (d.isString && !d.contentOrNull.isNullOrEmpty())
                            mapOf("text" to JsonPrimitive(d.content))
                        else emptyMap()
                    else -> emptyMap()
                }
                MessageSegment(type = type, data = data)
            }.getOrNull()
        }
    }

    /** 秒/毫秒/微秒 → 微秒（与后端归一化规则一致） */
    private fun normalizeMicro(ts: Long): Long {
        if (ts <= 0L) return System.currentTimeMillis() * 1000
        val abs = kotlin.math.abs(ts)
        return when {
            abs < 10_000_000_000L -> ts * 1_000_000          // 秒级（<1e10）
            abs < 10_000_000_000_000L -> ts * 1000           // 毫秒级（<1e13）
            abs < 10_000_000_000_000_000L -> ts              // 微秒级（<1e16）
            else -> System.currentTimeMillis() * 1000
        }
    }

    private fun buildWsUrl(token: String): String {
        val base = BASE_URL.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$wsBase/api/v1/linkzone/ws?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        /** 超过该时长没收到服务端任何帧，回前台时判定为假死连接并立即重连 */
        private const val STALE_CONNECTION_MS = 25_000L
    }
}
