package com.cjhsy.linkzone.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.di.BASE_URL
import java.util.concurrent.TimeUnit

/** 响应/数据截断上限 */
private const val MAX_DATA_CHARS = 8192

/**
 * 服务端联动配置：LinkZone 框架地址 + admin token（全局 SharedPreferences）。
 * admin token 对应服务端 system.server.admin_token，在智能体编辑页「服务端联动」开关下填写。
 */
object LinkZoneAdminConfig {
    private const val PREFS = "linkzone_admin_prefs"
    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "admin_token"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER, null)?.trim().takeUnless { it.isNullOrBlank() }
            ?: BASE_URL.trimEnd('/')

    fun adminToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.trim().orEmpty()

    fun save(context: Context, serverUrl: String, adminToken: String) {
        prefs(context).edit()
            .putString(KEY_SERVER, serverUrl.trim())
            .putString(KEY_TOKEN, adminToken.trim())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 服务端联动工具：linkzone_push（推送消息）+ linkzone_schedule（定时任务管理）。
 * 鉴权：Bearer admin token（服务端 system.server.admin_token）。
 */
fun buildLinkZoneTools(context: Context): List<Tool> = listOf(
    buildLinkZonePushTool(context),
    buildLinkZoneScheduleTool(context)
)

private fun buildLinkZonePushTool(context: Context): Tool = Tool(
    name = "linkzone_push",
    description = """
        Push a message through the LinkZone framework to a messaging platform (e.g. QQ) user or group.
        Requires the LinkZone admin token configured in the agent edit page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("platform", buildJsonObject {
                    put("type", "string")
                    put("description", "Target platform adapter, e.g. 'qq'")
                })
                put("target_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("user"); add("group") })
                    put("description", "Push to a user (private) or a group")
                })
                put("target_id", buildJsonObject {
                    put("type", "string")
                    put("description", "User id or group id on the platform")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Plain text message to send")
                })
                put("bot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional bot id when multiple bots exist on the platform")
                })
            },
            required = listOf("platform", "target_type", "target_id", "message")
        )
    },
    execute = {
        val params = it.jsonObject
        val body = buildJsonObject {
            put("platform", params.str("platform"))
            put("target_type", params.str("target_type"))
            put("target_id", params.str("target_id"))
            put("content", params.str("message"))
            params["bot_id"]?.jsonPrimitive?.contentOrNull?.let { b -> put("bot_id", b) }
        }
        listOf(UIMessagePart.Text(linkZoneRequest(context, "POST", "/api/v1/admin/push", body).toString()))
    }
)

private fun buildLinkZoneScheduleTool(context: Context): Tool = Tool(
    name = "linkzone_schedule",
    description = """
        Manage scheduled jobs on the LinkZone framework. Actions:
        - list: list all scheduled jobs
        - create: create a cron push job (requires id, cron e.g. '0 30 8 * * *', platform, receiver_type, receiver_id, message)
        - delete / trigger / enable / disable: operate on a job by id
        Requires the LinkZone admin token configured in the agent edit page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("list"); add("create"); add("delete"); add("trigger"); add("enable"); add("disable")
                    })
                    put("description", "Operation to perform")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Job id (required for create/delete/trigger/enable/disable)")
                })
                put("cron", buildJsonObject {
                    put("type", "string")
                    put("description", "Cron expression with seconds, e.g. '0 30 8 * * *' = every day 08:30 (create only)")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Job description (create only)")
                })
                put("platform", buildJsonObject {
                    put("type", "string")
                    put("description", "Target platform adapter, e.g. 'qq' (create only)")
                })
                put("receiver_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("user"); add("group") })
                    put("description", "Receiver type (create only)")
                })
                put("receiver_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Receiver user/group id (create only)")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Plain text message to push on schedule (create only)")
                })
                put("bot_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional bot id (create only)")
                })
            },
            required = listOf("action")
        )
    },
    execute = {
        val params = it.jsonObject
        val action = params.str("action")
        val result = when (action) {
            "list" -> linkZoneRequest(context, "GET", "/api/v1/admin/scheduler/jobs", null)
            "create" -> {
                val body = buildJsonObject {
                    put("id", params.str("id"))
                    put("type", "push")
                    put("cron", params.str("cron"))
                    params["description"]?.jsonPrimitive?.contentOrNull?.let { d -> put("description", d) }
                    put("push_data", buildJsonObject {
                        put("platform", params.str("platform"))
                        put("receiver_type", params.str("receiver_type"))
                        put("receiver_id", params.str("receiver_id"))
                        put("content", params.str("message"))
                        params["bot_id"]?.jsonPrimitive?.contentOrNull?.let { b -> put("bot_id", b) }
                    })
                }
                linkZoneRequest(context, "POST", "/api/v1/admin/scheduler/jobs", body)
            }
            "delete", "trigger", "enable", "disable" -> {
                val id = params.str("id")
                if (action == "delete") {
                    linkZoneRequest(context, "DELETE", "/api/v1/admin/scheduler/jobs/$id", null)
                } else {
                    linkZoneRequest(context, "POST", "/api/v1/admin/scheduler/jobs/$id/$action", null)
                }
            }
            else -> error("unknown action: $action")
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("$key is required")

/** 统一请求 LinkZone admin API；信封 {code,message,data}，code==0 成功 */
private suspend fun linkZoneRequest(
    context: Context,
    method: String,
    path: String,
    body: JsonObject?
): JsonObject {
    val token = LinkZoneAdminConfig.adminToken(context)
    if (token.isBlank()) {
        return buildJsonObject {
            put("success", false)
            put("error", "未配置 LinkZone admin token：请在智能体编辑页开启「服务端联动」后填写框架 admin token")
        }
    }
    val url = LinkZoneAdminConfig.serverUrl(context).trimEnd('/') + path
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    return withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .apply {
                    if (body == null) {
                        method(method, null)
                    } else {
                        method(method, body.toString().toRequestBody("application/json".toMediaType()))
                    }
                }
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                if (json != null && (json["code"]?.jsonPrimitive?.intOrNull ?: -1) == 0) {
                    buildJsonObject {
                        put("success", true)
                        put("data", json["data"]?.toString()?.take(MAX_DATA_CHARS) ?: "")
                    }
                } else {
                    buildJsonObject {
                        put("success", false)
                        put("status", resp.code)
                        put("error", json?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: raw.take(500).ifBlank { "HTTP ${resp.code}" })
                    }
                }
            }
        }.getOrElse { e ->
            buildJsonObject {
                put("success", false)
                put("error", e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
