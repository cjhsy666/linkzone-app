package com.cjhsy.linkzone.data.stats

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// 数据结构
// ---------------------------------------------------------------------------

/** 单模型的用量聚合条目 */
@Serializable
data class UsageEntry(
    /** 累计输入（prompt）tokens */
    val promptTokens: Long = 0L,
    /** 累计输出（completion）tokens */
    val completionTokens: Long = 0L,
    /** 累计请求次数 */
    val requestCount: Int = 0,
    /** 累计生成耗时（毫秒），用于估算输出速率 */
    val durationMs: Long = 0L,
    /** 最近一次使用时间戳（毫秒） */
    val lastUsedAt: Long = 0L
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** 单日总量 */
@Serializable
data class DayTotals(
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val requestCount: Int = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** 单日统计：按模型明细 + 当日总量 */
@Serializable
data class DayStats(
    /** 模型 ID -> 用量明细 */
    val perModel: Map<String, UsageEntry> = emptyMap(),
    /** 当日总量 */
    val totals: DayTotals = DayTotals()
)

/** 落盘根结构：按天组织 */
@Serializable
data class UsageStatsData(
    /** 结构版本，便于后续迁移 */
    val version: Int = 1,
    /** 日期字符串（yyyy-MM-dd）-> 当日统计 */
    val days: Map<String, DayStats> = emptyMap()
)

// ---------------------------------------------------------------------------
// 查询结果（供统计页展示）
// ---------------------------------------------------------------------------

/** 按模型排行条目（tokens + 占比） */
data class ModelUsageRank(
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    /** 占全部总 tokens 的比例（0f..1f），用于画横条 */
    val share: Float
)

/** 按天用量条目 */
data class DayUsage(
    /** 日期字符串 yyyy-MM-dd */
    val date: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val requestCount: Int,
    /** 相对区间内最大日的比例（0f..1f），用于画迷你横条 */
    val barWeight: Float
)

/** 统计快照：一次查询返回统计页需要的全部数据 */
data class UsageSnapshot(
    /** 全量累计（所有日期） */
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalRequestCount: Int,
    /** 今日用量（无数据为 0） */
    val todayPromptTokens: Long,
    val todayCompletionTokens: Long,
    val todayRequestCount: Int,
    /** 按模型排行（按总 tokens 降序） */
    val modelRanks: List<ModelUsageRank>,
    /** 近 [days] 天按天用量（按日期倒序，最新在前） */
    val daily: List<DayUsage>
) {
    val totalTokens: Long get() = totalPromptTokens + totalCompletionTokens
    val todayTokens: Long get() = todayPromptTokens + todayCompletionTokens
}

// ---------------------------------------------------------------------------
// 存储
// ---------------------------------------------------------------------------

/**
 * Token 用量统计存储。
 *
 * RikkaHub 的统计口径是从消息库中聚合每条消息的 usage（prompt/completion/cached tokens）
 * 与时间字段，得出总量、按模型、按天三个维度；LinkZone 对话消息落服务端、本地无消息库，
 * 因此参考其口径在本地以独立 JSON 文件聚合：调用方（接线层）在每次请求完成后调用
 * [record] 传入模型 ID 与本次 tokens/耗时，这里做内存聚合并每次写盘
 * （context.filesDir/usage_stats.json），统计页通过 [snapshot] 查询。
 *
 * 写盘使用 Dispatchers.IO；所有操作经 [mutex] 串行化，避免并发读写损坏数据。
 */
class UsageStatsStore(context: Context, private val json: Json) {

    private val appContext = context.applicationContext
    private val file: File = File(appContext.filesDir, STATS_FILE_NAME)
    private val mutex = Mutex()

    /** 内存聚合数据；首次访问时从磁盘加载 */
    private var data: UsageStatsData = UsageStatsData()
    private var loaded = false

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ---------- 记录 ----------

    /**
     * 记录一次模型调用的用量。
     * 内存聚合 + 每次写盘（Dispatchers.IO）。
     */
    suspend fun record(
        modelId: String,
        promptTokens: Long,
        completionTokens: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val day = dayFormat.format(LocalDate.now())
            val dayStats = data.days[day] ?: DayStats()
            val entry = dayStats.perModel[modelId] ?: UsageEntry()
            val now = System.currentTimeMillis()
            val updatedEntry = entry.copy(
                promptTokens = entry.promptTokens + promptTokens.coerceAtLeast(0L),
                completionTokens = entry.completionTokens + completionTokens.coerceAtLeast(0L),
                requestCount = entry.requestCount + 1,
                durationMs = entry.durationMs + durationMs.coerceAtLeast(0L),
                lastUsedAt = now
            )
            val updatedDay = dayStats.copy(
                perModel = dayStats.perModel + (modelId to updatedEntry),
                totals = DayTotals(
                    promptTokens = dayStats.totals.promptTokens + promptTokens.coerceAtLeast(0L),
                    completionTokens = dayStats.totals.completionTokens + completionTokens.coerceAtLeast(0L),
                    requestCount = dayStats.totals.requestCount + 1
                )
            )
            data = data.copy(days = data.days + (day to updatedDay))
            runCatching { persistLocked() }
            Unit
        }
    }

    // ---------- 查询 ----------

    /**
     * 查询统计快照。
     * @param days 近 N 天按天明细（按日总量倒序），默认 14 天
     */
    suspend fun snapshot(days: Int = DEFAULT_SNAPSHOT_DAYS): UsageSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val today = dayFormat.format(LocalDate.now())

            var totalPrompt = 0L
            var totalCompletion = 0L
            var totalRequests = 0
            // 模型维度聚合（全量）
            val perModelTotal = LinkedHashMap<String, UsageEntry>()

            for ((_, dayStats) in data.days) {
                totalPrompt += dayStats.totals.promptTokens
                totalCompletion += dayStats.totals.completionTokens
                totalRequests += dayStats.totals.requestCount
                for ((modelId, entry) in dayStats.perModel) {
                    val acc = perModelTotal[modelId] ?: UsageEntry()
                    perModelTotal[modelId] = acc.copy(
                        promptTokens = acc.promptTokens + entry.promptTokens,
                        completionTokens = acc.completionTokens + entry.completionTokens,
                        requestCount = acc.requestCount + entry.requestCount,
                        durationMs = acc.durationMs + entry.durationMs,
                        lastUsedAt = maxOf(acc.lastUsedAt, entry.lastUsedAt)
                    )
                }
            }

            val grandTotal = (totalPrompt + totalCompletion).coerceAtLeast(1L)

            val ranks = perModelTotal.entries
                .map { (modelId, entry) ->
                    ModelUsageRank(
                        modelId = modelId,
                        promptTokens = entry.promptTokens,
                        completionTokens = entry.completionTokens,
                        totalTokens = entry.totalTokens,
                        share = (entry.totalTokens.toFloat() / grandTotal).coerceIn(0f, 1f)
                    )
                }
                .sortedByDescending { it.totalTokens }

            // 近 N 天按天明细（含无数据日填 0，保证图表连续）
            val todayDate = LocalDate.now()
            val dailyBuilder = ArrayList<DayUsage>(days)
            var maxDayTokens = 1L
            for (i in 0 until days) {
                val date = todayDate.minusDays(i.toLong())
                val key = dayFormat.format(date)
                val dayStats = data.days[key]
                val total = dayStats?.totals?.totalTokens ?: 0L
                if (total > maxDayTokens) maxDayTokens = total
                dailyBuilder.add(
                    DayUsage(
                        date = key,
                        promptTokens = dayStats?.totals?.promptTokens ?: 0L,
                        completionTokens = dayStats?.totals?.completionTokens ?: 0L,
                        totalTokens = total,
                        requestCount = dayStats?.totals?.requestCount ?: 0,
                        barWeight = 0f
                    )
                )
            }
            val daily = dailyBuilder.map { it.copy(barWeight = (it.totalTokens.toFloat() / maxDayTokens).coerceIn(0f, 1f)) }

            val todayStats = data.days[today]
            UsageSnapshot(
                totalPromptTokens = totalPrompt,
                totalCompletionTokens = totalCompletion,
                totalRequestCount = totalRequests,
                todayPromptTokens = todayStats?.totals?.promptTokens ?: 0L,
                todayCompletionTokens = todayStats?.totals?.completionTokens ?: 0L,
                todayRequestCount = todayStats?.totals?.requestCount ?: 0,
                modelRanks = ranks,
                daily = daily
            )
        }
    }

    /** 清空全部统计数据（设置页可用） */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            data = UsageStatsData()
            runCatching { persistLocked() }
            Unit
        }
    }

    // ---------- 内部 ----------

    /** 须在持有 mutex 时调用：首次从磁盘加载 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        data = runCatching {
            if (file.exists()) json.decodeFromString<UsageStatsData>(file.readText())
            else UsageStatsData()
        }.getOrDefault(UsageStatsData())
    }

    /** 须在持有 mutex 时调用：写盘 */
    private fun persistLocked() {
        val tmp = File(file.parentFile, STATS_FILE_NAME + ".tmp")
        tmp.writeText(json.encodeToString(data))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    companion object {
        private const val STATS_FILE_NAME = "usage_stats.json"
        const val DEFAULT_SNAPSHOT_DAYS = 14
    }
}
