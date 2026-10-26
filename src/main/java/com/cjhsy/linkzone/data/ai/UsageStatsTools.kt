package com.cjhsy.linkzone.data.ai

import android.app.AppOpsManager
import android.app.usage.UsageEvents
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 应用使用统计工具（usage_stats）。
 *
 * 数据来源分两条路：
 *  1) 主路径 queryEvents()：重建「亮屏 + 唯一最上层 App」区间，等价于 Digital Wellbeing 的算法，
 *     能给出真实的 screen-on 时长，也能正确处理分屏/前台服务造成的重复计时。
 *  2) 兜底 queryUsageStats(INTERVAL_DAILY)：事件流不可用时使用。
 *     ——绝不要用 INTERVAL_BEST，它在部分 ROM 上会返回跨天甚至跨周的粗桶，
 *        totalTimeInForeground 是整个桶的累计值，会严重高估。
 *
 * 输出中两个总时长必须分开看：
 *  - screen_on_time：真实亮屏使用时长（权威值）
 *  - foreground_sum：Σ 各 App 前台时长，因多窗口/前台服务可能大于 screen_on_time
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

/** 聚合结果 */
private class UsageAggregate(
    val perApp: MutableMap<String, Long> = HashMap(),
    val lastUsed: MutableMap<String, Long> = HashMap(),
    var screenOnMs: Long = 0L,
    var estimated: Boolean = false,
    val warnings: MutableList<String> = mutableListOf()
)

/**
 * 从事件流重建使用区间。
 * 返回 null 表示事件流不可用（无权限/被 ROM 裁剪/抛异常），调用方应退回聚合表。
 */
private fun aggregateFromEvents(
    usm: UsageStatsManager,
    begin: Long,
    end: Long,
    warnings: MutableList<String>
): UsageAggregate? {
    val result = runCatching { usm.queryEvents(begin, end) }
    val events = result.getOrNull()
    if (events == null) {
        result.exceptionOrNull()?.let { warnings += "queryEvents 失败：${it.message}" }
        return null
    }

    val agg = UsageAggregate(warnings = warnings)
    val e = UsageEvents.Event()
    var seen = false

    var screenOn = false
    var screenSince = begin
    var top: String? = null
    var topSince = begin

    // 结算 [topSince, at) 这一段前台时间（仅在亮屏时计入）
    fun flushTop(at: Long) {
        val pkg = top ?: return
        val d = at - topSince
        if (screenOn && d > 0) agg.perApp[pkg] = (agg.perApp[pkg] ?: 0L) + d
    }

    fun flushScreen(at: Long) {
        val d = at - screenSince
        if (screenOn && d > 0) agg.screenOnMs += d
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(e)
        seen = true
        val t = e.timeStamp.coerceIn(begin, end)

        when (e.eventType) {
            // 亮屏 / 解锁后亮屏
            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                if (!screenOn) {
                    screenOn = true
                    screenSince = t
                    // 关键：亮屏前累积的 topSince 不可信，重置为亮屏时刻
                    if (top != null) topSince = t
                }
            }

            // 灭屏 / 锁屏 / 关机：结算并清空
            UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.DEVICE_SHUTDOWN -> {
                flushTop(t)
                flushScreen(t)
                screenOn = false
                top = null
            }

            // API 29+ 为 ACTIVITY_RESUMED，旧版本 MOVE_TO_FOREGROUND 数值同为 1
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                val pkg = e.packageName
                if (!pkg.isNullOrEmpty()) {
                    flushTop(t)
                    top = pkg
                    topSince = t
                    if (t > (agg.lastUsed[pkg] ?: 0L)) agg.lastUsed[pkg] = t
                }
            }

            // 仅当暂停/停止的正是当前最上层时才结算，避免多窗口误伤
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED -> {
                if (e.packageName != null && e.packageName == top) {
                    flushTop(t)
                    top = null
                }
            }
        }
    }

    if (!seen) return null

    flushTop(end)
    flushScreen(end)

    if (agg.screenOnMs <= 0L && agg.perApp.isNotEmpty()) {
        // 极少数 ROM 不派发 SCREEN_INTERACTIVE，退化为前台之和
        agg.screenOnMs = agg.perApp.values.sum()
        agg.estimated = true
        warnings += "未收到亮屏事件，screen_on_time 由前台时长推算。"
    }
    return agg
}

/**
 * 兜底：用 INTERVAL_DAILY 聚合表估算。
 * 只接受与区间真正重叠的桶，并对跨区间边界的桶按重叠比例裁剪。
 */
private fun aggregateFromBuckets(
    usm: UsageStatsManager,
    begin: Long,
    end: Long,
    warnings: MutableList<String>
): UsageAggregate {
    val agg = UsageAggregate(estimated = true, warnings = warnings)
    val stats = runCatching {
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
    }.getOrNull().orEmpty()

    if (stats.isEmpty()) {
        warnings += "queryUsageStats 返回空，可能未授权或系统数据被重置。"
        return agg
    }

    for (s in stats) {
        val pkg = s.packageName ?: continue
        val fg = s.totalTimeInForeground
        if (fg <= 0L) continue

        val bStart = s.firstTimeStamp
        val bEnd = maxOf(s.lastTimeStamp, bStart + 1L)
        if (bStart >= end) continue

        val overlap = (minOf(bEnd, end) - maxOf(bStart, begin)).coerceAtLeast(0L)
        if (overlap <= 0L) continue

        val value = if (bStart >= begin) {
            // 桶完全在区间内（含当天那个"进行中"的桶，其数据只到今天为止）
            fg
        } else {
            // 桶跨越了 begin（周查询边界），按时间比例裁剪
            (fg.toDouble() * overlap / (bEnd - bStart)).toLong()
        }

        agg.perApp[pkg] = (agg.perApp[pkg] ?: 0L) + value
        if (s.lastTimeUsed > (agg.lastUsed[pkg] ?: 0L)) agg.lastUsed[pkg] = s.lastTimeUsed
    }

    agg.screenOnMs = agg.perApp.values.sum()
    warnings += "使用聚合表估算（事件流不可用），数值仅供参考。"
    return agg
}

fun buildUsageStatsTool(context: Context): Tool = Tool(
    name = "usage_stats",
    description = """
        Read the user's screen time and per-app usage breakdown (via Android UsageStatsManager).
        - range: "today" (default, from local midnight) or "week" (last 7 calendar days incl. today).
        - limit: max apps in the breakdown, default 10, max 30. The rest is summarized as "其他".
        Returns:
          * screen_on_time / screen_on_time_ms — authoritative screen-on duration; use this for "screen time".
          * foreground_sum / foreground_sum_ms — sum of per-app foreground time; may exceed screen_on_time
            because of split screen, floating windows and foreground services. Use only for the breakdown.
          * estimated=true means the numbers come from the aggregated table, not the event stream.
          * suspect=true means foreground_sum exceeds the elapsed wall time — treat with caution.
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
                    put("description", "today = since local midnight (default); week = last 7 calendar days including today.")
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

        val zone = ZoneId.systemDefault()
        val endMs = System.currentTimeMillis()
        // 关键：按日历对齐，不用 now - 7*24h
        val beginMs = when (range) {
            "week" -> LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            else -> LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val elapsedMs = endMs - beginMs

        val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val warnings = mutableListOf<String>()

        val agg = aggregateFromEvents(usm, beginMs, endMs, warnings)
            ?: aggregateFromBuckets(usm, beginMs, endMs, warnings)

        val foregroundSum = agg.perApp.values.sum()
        if (foregroundSum <= 0L) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("count", 0)
                put("range", range)
                put("begin", Instant.ofEpochMilli(beginMs).atZone(zone).toString())
                put("end", Instant.ofEpochMilli(endMs).atZone(zone).toString())
                put("message", "No foreground app usage recorded in this range.")
                if (warnings.isNotEmpty()) {
                    put("warnings", buildJsonArray { warnings.forEach { add(it) } })
                }
            }.toString()))
        }

        // 自检
        val suspect = foregroundSum > elapsedMs
        if (suspect) {
            warnings += "各应用前台时长之和（${formatDurationZh(foregroundSum)}）超过区间实际经过时间" +
                "（${formatDurationZh(elapsedMs)}），说明存在多窗口或前台服务重复计时，请以 screen_on_time 为准。"
        }
        val screenOnMs = agg.screenOnMs.coerceAtMost(elapsedMs)

        val pm = appContext.packageManager
        val sorted = agg.perApp.entries.sortedByDescending { it.value }
        val shown = sorted.take(limit)
        val restMs = sorted.drop(limit).sumOf { it.value }

        fun share(ms: Long) = ((ms * 1000.0 / foregroundSum).toLong() / 10.0)

        val appsJson = buildJsonArray {
            shown.forEach { (pkg, ms) ->
                add(buildJsonObject {
                    put("app", appLabel(pm, pkg))
                    put("package", pkg)
                    put("duration", formatDurationZh(ms))
                    put("duration_ms", ms)
                    put("share_percent", share(ms))
                    agg.lastUsed[pkg]?.let {
                        put("last_used", Instant.ofEpochMilli(it).atZone(zone).toString())
                    }
                })
            }
            if (restMs > 0L) {
                add(buildJsonObject {
                    put("app", "其他")
                    put("duration", formatDurationZh(restMs))
                    put("duration_ms", restMs)
                    put("share_percent", share(restMs))
                })
            }
        }

        listOf(UIMessagePart.Text(buildJsonObject {
            put("range", range)
            put("begin", Instant.ofEpochMilli(beginMs).atZone(zone).toString())
            put("end", Instant.ofEpochMilli(endMs).atZone(zone).toString())
            put("elapsed", formatDurationZh(elapsedMs))

            put("screen_on_time", formatDurationZh(screenOnMs))
            put("screen_on_time_ms", screenOnMs)

            put("foreground_sum", formatDurationZh(foregroundSum))
            put("foreground_sum_ms", foregroundSum)

            put("estimated", agg.estimated)
            put("suspect", suspect)
            put("source", if (agg.estimated) "usage_stats_buckets" else "usage_events")
            put("app_count", agg.perApp.size)
            put("apps", appsJson)
            if (warnings.isNotEmpty()) {
                put("warnings", buildJsonArray { warnings.forEach { add(it) } })
            }
            put(
                "message",
                "Per-app usage sorted by duration desc; 'duration' is localized for the user. " +
                    "Report screen_on_time as the user's screen time, not foreground_sum."
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