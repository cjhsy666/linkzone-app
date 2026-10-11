package com.cjhsy.linkzone.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 最小编解码（LinkZone 未挂 MCP 官方 SDK，手写实现）。
 *
 * 只覆盖 MCP 客户端需要的部分：客户端请求/通知的编码，以及入站消息的
 * 分类（响应 / 服务器请求 / 通知）。
 */
internal object McpJsonRpc {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** MCP 2025-06-18 规范版本 */
    const val PROTOCOL_VERSION = "2025-06-18"

    const val METHOD_NOT_FOUND = -32601
    const val PARSE_ERROR = -32700

    /** 编码客户端请求（带 id，期待响应） */
    fun buildRequest(id: Long, method: String, params: JsonObject?): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** 编码通知（无 id，不期待响应） */
    fun buildNotification(method: String, params: JsonObject?): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    /** 编码对服务器请求的成功响应 */
    fun buildResult(id: JsonElement, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()

    /** 编码对服务器请求的错误响应 */
    fun buildError(id: JsonElement, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()

    /** 入站 JSON-RPC 消息分类 */
    sealed class Incoming {
        /** 对客户端请求的响应（result 与 error 二选一） */
        data class Response(
            val id: JsonElement,
            val result: JsonObject?,
            val errorCode: Int?,
            val errorMessage: String?,
        ) : Incoming()

        /** 服务器发起的请求（需要客户端回应） */
        data class ServerRequest(
            val id: JsonElement,
            val method: String,
            val params: JsonObject?,
        ) : Incoming()

        /** 服务器通知 */
        data class ServerNotification(
            val method: String,
            val params: JsonObject?,
        ) : Incoming()
    }

    /** 解析入站消息；无法识别时返回 null */
    fun parse(text: String): Incoming? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        val id = obj["id"]
        when {
            method != null && id != null -> Incoming.ServerRequest(
                id = id,
                method = method,
                params = obj["params"] as? JsonObject,
            )

            method != null -> Incoming.ServerNotification(
                method = method,
                params = obj["params"] as? JsonObject,
            )

            id != null -> {
                val error = obj["error"] as? JsonObject
                Incoming.Response(
                    id = id,
                    result = obj["result"] as? JsonObject,
                    errorCode = (error?.get("code") as? JsonPrimitive)?.longOrNull?.toInt(),
                    errorMessage = (error?.get("message") as? JsonPrimitive)?.contentOrNull,
                )
            }

            else -> null
        }
    }.getOrNull()

    /** 提取响应 id 的可比较键（数字或字符串） */
    fun idKey(id: JsonElement): String = (id as? JsonPrimitive)?.let { it.contentOrNull ?: it.longOrNull?.toString() } ?: ""

    /** 判断出站消息是否为客户端请求（带 id 且带 method，期待响应） */
    fun isRequest(message: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(message).jsonObject
        obj.containsKey("method") && obj.containsKey("id")
    }.getOrDefault(false)

    /** 提取出站请求的 id（用于匹配响应） */
    fun requestIdKey(message: String): String? = runCatching {
        val obj = json.parseToJsonElement(message).jsonObject
        obj["id"]?.let { idKey(it) }
    }.getOrNull()
}
