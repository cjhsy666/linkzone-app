package com.cjhsy.linkzone.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import java.util.concurrent.TimeUnit

/** 响应体截断上限，避免超长响应撑爆上下文 */
private const val MAX_BODY_CHARS = 8192

/**
 * http_request —— 通用 HTTP 请求工具。
 *
 * 让模型可以调用任意 HTTP 接口（查 API、GET/POST JSON 等）。
 * 响应体按 [MAX_BODY_CHARS] 截断。自建独立 OkHttpClient，不带 App 鉴权链。
 */
fun buildHttpRequestTool(): Tool = Tool(
    name = "http_request",
    description = """
        Make an HTTP request to any URL. Supports custom method, headers and body.
        For JSON POST/PUT, set header Content-Type: application/json and pass the JSON string in body.
        Response body is truncated to $MAX_BODY_CHARS characters.
        Do NOT use this to leak user private data to untrusted servers.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full URL, e.g. https://api.example.com/data?key=value")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("GET"); add("POST"); add("PUT"); add("PATCH"); add("DELETE"); add("HEAD")
                    })
                    put("description", "HTTP method, default GET")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional request headers as a JSON object of string key-values")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional request body (for POST/PUT/PATCH/DELETE)")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Total timeout in seconds, default 30, max 120")
                })
            },
            required = listOf("url")
        )
    },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
        val method = params["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val body = params["body"]?.jsonPrimitive?.contentOrNull
        val timeoutSec = (params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 120)
        val headers = params["headers"]?.jsonObject

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val request = Request.Builder()
            .url(url)
            .apply {
                headers?.forEach { (k, v) ->
                    val value = v.jsonPrimitive.contentOrNull
                    if (value != null) addHeader(k, value)
                }
                when (method) {
                    "GET", "HEAD" -> method(method, null)
                    else -> {
                        val contentType = headers?.get("Content-Type")?.jsonPrimitive?.contentOrNull
                            ?: headers?.get("content-type")?.jsonPrimitive?.contentOrNull
                            ?: "text/plain; charset=utf-8"
                        method(method, (body ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
                    }
                }
            }
            .build()

        val payload = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    buildJsonObject {
                        put("ok", resp.isSuccessful)
                        put("status", resp.code)
                        put("status_text", resp.message)
                        put("headers", buildJsonObject {
                            resp.headers.forEach { (k, v) -> put(k, v) }
                        })
                        put("truncated", raw.length > MAX_BODY_CHARS)
                        put("body", raw.take(MAX_BODY_CHARS))
                    }
                }
            }.getOrElse { e ->
                buildJsonObject {
                    put("ok", false)
                    put("error", e.message ?: e.javaClass.simpleName)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
