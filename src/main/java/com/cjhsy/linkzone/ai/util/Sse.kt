package com.cjhsy.linkzone.ai.util

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * okhttp-sse 的零依赖替代实现（API 与 okhttp3.sse 对齐）：
 * `EventSources.createFactory(client).newEventSource(request, listener)`。
 *
 * 语义：2xx 且流读取正常 → onOpen → onEvent* → onClosed；
 * HTTP 错误 / 连接失败 / 读取中断 → onFailure（带 t 或 response）；
 * cancel/close 后不再回调。
 */
interface EventSource {
    fun cancel()
    fun close()

    fun interface Factory {
        fun newEventSource(request: Request, listener: EventSourceListener): EventSource
    }
}

abstract class EventSourceListener {
    open fun onOpen(eventSource: EventSource, response: Response) {}
    open fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {}
    open fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {}
    open fun onClosed(eventSource: EventSource) {}
}

object EventSources {
    fun createFactory(client: OkHttpClient): EventSource.Factory =
        EventSource.Factory { request, listener -> RealEventSource(client, request, listener) }
}

private class RealEventSource(
    client: OkHttpClient,
    private val request: Request,
    private val listener: EventSourceListener,
) : EventSource {
    private val call: Call = client.newCall(request)

    @Volatile
    private var canceled = false

    init {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (canceled) return
                listener.onFailure(this@RealEventSource, e, null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (canceled) {
                    runCatching { response.close() }
                    return
                }
                if (!response.isSuccessful) {
                    listener.onFailure(
                        this@RealEventSource,
                        IOException("HTTP ${response.code} for ${request.url}"),
                        response,
                    )
                    return
                }
                listener.onOpen(this@RealEventSource, response)
                try {
                    parseSseStream(response.body ?: throw IOException("empty response body")) { id, type, data ->
                        listener.onEvent(this@RealEventSource, id, type, data)
                    }
                    listener.onClosed(this@RealEventSource)
                } catch (e: Throwable) {
                    runCatching { response.close() }
                    if (!canceled) listener.onFailure(this@RealEventSource, e, null)
                }
            }
        })
    }

    override fun cancel() {
        canceled = true
        runCatching { call.cancel() }
    }

    override fun close() = cancel()
}

/** 逐行解析 SSE 流：空行派发事件，event/id/data 行累积，注释与 retry 行忽略 */
private inline fun parseSseStream(
    body: okhttp3.ResponseBody,
    onEvent: (id: String?, type: String?, data: String) -> Unit,
) {
    body.source().use { source ->
        var id: String? = null
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) onEvent(id, event, data.toString())
                    id = null
                    event = null
                    data.setLength(0)
                }
                line.startsWith(":") -> Unit // 注释 / 心跳
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("retry:") -> Unit
            }
        }
        // 流末尾未以空行结束时也派发一次，避免丢最后一个事件
        if (data.isNotEmpty()) onEvent(id, event, data.toString())
    }
}
