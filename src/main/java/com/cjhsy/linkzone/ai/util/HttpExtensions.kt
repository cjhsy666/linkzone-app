package com.cjhsy.linkzone.ai.util

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) {
                    runCatching { response.close() }
                }
            }
        })
    }
}

val JsonElement.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

val JsonElement.jsonArrayOrNull: JsonArray?
    get() = this as? JsonArray

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

/**
 * 安全读取 ResponseBody 字符串（body 可能已被流式读取消费）
 */
fun okhttp3.ResponseBody.stringSafe(): String? = runCatching { string() }.getOrNull()

/**
 * 按 "a.b.0.c" 形式的 JSON 路径取值（支持 [n] 数组下标），返回原始内容的字符串表示。
 * 用于余额查询等 resultPath 配置。
 */
fun JsonObject.getByKey(key: String): String {
    var current: JsonElement = this
    val segments = key.split('.')
    for (seg in segments) {
        val m = Regex("^(.*?)\\[(\\d+)]$").find(seg.trim())
        val (name, idx) = if (m != null) {
            m.groupValues[1] to m.groupValues[2].toInt()
        } else {
            seg.trim() to -1
        }
        val element: JsonElement? = when (current) {
            is JsonObject -> if (name.isEmpty()) current else current[name]
            is JsonArray -> if (idx >= 0) current.getOrNull(idx) else null
            else -> null
        }
        if (element == null) return ""
        current = element
    }
    return when (current) {
        is JsonPrimitive -> current.content
        else -> current.toString()
    }
}
