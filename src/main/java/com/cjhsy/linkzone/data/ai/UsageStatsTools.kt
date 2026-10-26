package com.cjhsy.linkzone.data.ai

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * 应用使用统计工具（usage_stats）：
 * 通过 UsageStatsManager 读取屏幕使用时长与各 App 前台使用占比（今天/本周）。
 *
 * 权限为「使用情况访问」（PACKAGE_USAGE_STATS），属于特殊权限——
 * 无法在运行时弹窗申请，只能引导用户到系统设置页（ACTION_USAGE_ACCESS_SETTINGS）手动开启；
 * 未授权时 queryUsageStats 返回空列表，必须先 AppOpsManager 预检。
 *
 * 输出面向模型做了本地化：包名转应用中文名（PackageManager.loadLabel），
 * 时长格式化为「X小时Y分钟」，附占比百分比。
 */

/** 「使用情况访问」特殊权限检查 */
internal fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun buildUsageStatsTool(context: Context): Tool = Tool(
    name = "usage_stats",
    description = """
        Read the user's screen time and per-app usage breakdown (via Android UsageStatsManager).
        - range: "today" (default, from local midnight) or "week" (last 7 days).
        - limit: max apps in the breakdown, default 10, max 30. The rest is summarized as "其他".
        Returns total screen time plus per-app entries with the app's localized (Chinese) name,
        duration formatted like "3小时25分钟", and its share of total screen time.
        Requires the special "Usage Access" permission granted in system settings. If it is not
        granted, an error is returned — tell the user to enable it (Settings → 使用情况访问权限),
        or call phone_control open_settings with section "usage" to open that page directly.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("range", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("today"); add("week") })
                    put("description", "today = since local midnight (default); week = last 7 days.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max apps listed. Default 10, max 30.")
                })
            }
        )
    },
    execute = { args ->
        val appContext = context.applicationContext
        if (!hasUsageStatsPermission(appContext)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "未授予「使用情况访问」权限。请引导用户到系统设置开启（设置 → 应用 → 特殊应用权限 → 使用情况访问），" +
                        "或使用 phone_control open_settings(section=usage) 直接打开该页面。"
                )
            }.toString()))
        }

        val params = args.jsonObject
        val range = params["range"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "today"
        val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 30) ?: 10

        val zone = java.time.ZoneId.systemDefault()
        val endMs = System.currentTimeMillis()
        val beginMs = when (range) {
            "week" -> endMs - 7L * 24 * 60 * 60 * 1000
            else -> LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        }

        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, beginMs, endMs)
        }.getOrNull()

        if (stats.isNullOrEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("count", 0)
                put(
                    "message",
                    "No usage data for the selected range. The device may have just been reset, " +
                        "or usage access was revoked."
                )
            }.toString()))
        }

        // 按包名聚合前台时长（INTERVAL_BEST 可能按天拆条），取最后使用时间
        data class AppUsage(var totalMs: Long, var lastUsedMs: Long)
        val aggregated = HashMap<String, AppUsage>()
        stats.forEach { s ->
            val pkg = s.packageName ?: return@forEach
            val fg = s.totalTimeInForeground
            if (fg <= 0) return@forEach
            val entry = aggregated.getOrPut(pkg) { AppUsage(0, 0) }
            entry.totalMs += fg
            if (s.lastTimeUsed > entry.lastUsedMs) entry.lastUsedMs = s.lastTimeUsed
        }

        val totalMs = aggregated.values.sumOf { it.totalMs }
        if (totalMs <= 0) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("count", 0)
                put("message", "No foreground app usage recorded in this range.")
            }.toString()))
        }

        val pm = appContext.packageManager
        val sorted = aggregated.entries.sortedByDescending { it.value.totalMs }
        val shown = sorted.take(limit)
        val restMs = sorted.drop(limit).sumOf { it.value.totalMs }

        val appsJson = buildJsonArray {
            shown.forEach { (pkg, usage) ->
                add(buildJsonObject {
                    put("app", appLabel(pm, pkg))
                    put("package", pkg)
                    put("duration", formatDurationZh(usage.totalMs))
                    put("duration_ms", usage.totalMs)
                    put("share_percent", (usage.totalMs * 100.0 / totalMs).let {
                        ((it * 10).toLong() / 10.0)
                    })
                    put(
                        "last_used",
                        java.time.Instant.ofEpochMilli(usage.lastUsedMs).atZone(zone).toString()
                    )
                })
            }
            if (restMs > 0) {
                add(buildJsonObject {
                    put("app", "其他")
                    put("duration", formatDurationZh(restMs))
                    put("duration_ms", restMs)
                    put("share_percent", (restMs * 100.0 / totalMs).let {
                        ((it * 10).toLong() / 10.0)
                    })
                })
            }
        }

        listOf(UIMessagePart.Text(buildJsonObject {
            put("range", range)
            put("begin", java.time.Instant.ofEpochMilli(beginMs).atZone(zone).toString())
            put("end", java.time.Instant.ofEpochMilli(endMs).atZone(zone).toString())
            put("total_screen_time", formatDurationZh(totalMs))
            put("total_screen_time_ms", totalMs)
            put("app_count", aggregated.size)
            put("apps", appsJson)
            put(
                "message",
                "Per-app usage sorted by duration desc; 'duration' is already localized for the user."
            )
        }.toString()))
    }
)

/** 包名 → 应用中文名（拿不到回退包名） */
private fun appLabel(pm: PackageManager, pkg: String): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(pkg, 0)
    }
    pm.getApplicationLabel(info).toString().ifBlank { pkg }
}.getOrDefault(pkg)

/** 时长格式化为中文：不足1分钟 / Y分钟 / X小时Y分钟 / X天X小时 */
private fun formatDurationZh(ms: Long): String {
    val totalMinutes = ms / 60_000
    if (totalMinutes < 1) return "不足1分钟"
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0) append("${hours}小时")
        if (minutes > 0) append("${minutes}分钟")
    }
}
