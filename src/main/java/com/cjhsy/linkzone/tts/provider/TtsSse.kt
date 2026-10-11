package com.cjhsy.linkzone.tts.provider

import com.cjhsy.linkzone.ai.util.EventSource
import com.cjhsy.linkzone.ai.util.EventSourceListener
import com.cjhsy.linkzone.ai.util.EventSources
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * TTS 包内专用的 SSE 事件 sealed 类型（内联自 RikkaHub common.http，避免依赖 okhttp3-sse）。
 */
sealed class TtsSseEvent {
    /** 连接成功打开 */
    object Open : TtsSseEvent()

    /**
     * 收到一个具体事件
     * @param id 事件ID
     * @param type 事件类型
     * @param data 事件数据
     */
    data class Event(val id: String?, val type: String?, val data: String) : TtsSseEvent()

    /** 连接被关闭 */
    object Closed : TtsSseEvent()

    /**
     * 发生错误
     * @param throwable 异常信息
     * @param response 错误时的响应（可能为 null）
     */
    data class Failure(val throwable: Throwable?, val response: Response?) : TtsSseEvent()
}

/**
 * 为 OkHttpClient 创建 SSE (Server-Sent Events) 连接的扩展函数。
 * 基于 LinkZone 零依赖的 EventSources 实现，将回调封装成 Kotlin Flow。
 */
fun OkHttpClient.ttsSseFlow(request: Request): Flow<TtsSseEvent> {
    return callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(TtsSseEvent.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(TtsSseEvent.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(TtsSseEvent.Closed)
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(TtsSseEvent.Failure(t, response))
                channel.close(t)
            }
        }

        val factory = EventSources.createFactory(this@ttsSseFlow)
        val eventSource: EventSource = factory.newEventSource(request, listener)

        // Flow 被取消时关闭 SSE 连接，释放资源
        awaitClose {
            eventSource.cancel()
        }
    }
}
