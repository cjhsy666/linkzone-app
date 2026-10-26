package com.cjhsy.linkzone.data.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定时提醒工具：reminder_create / reminder_list / reminder_cancel。
 *
 * - 持久化：SharedPreferences 存 JSON（避免 Room 迁移），提醒量小足够
 * - 调度：AlarmManager.setAndAllowWhileIdle（非精确闹钟，免 SCHEDULE_EXACT_ALARM 权限）
 * - 触发：[ReminderReceiver] 收到广播后发系统通知
 */
fun buildReminderTools(context: Context): List<Tool> {
    val store = ReminderStore(context)
    return listOf(
        buildReminderCreateTool(context, store),
        buildReminderListTool(store),
        buildReminderCancelTool(context, store)
    )
}

private fun buildReminderCreateTool(context: Context, store: ReminderStore): Tool = Tool(
    name = "reminder_create",
    description = """
        Create a local reminder on the user's device. When the time arrives, a system notification is shown.
        Provide EITHER `delay_minutes` (e.g. 30 = half an hour later) OR `at` (absolute time, "yyyy-MM-dd HH:mm" local time).
        Use get_time_info first if you need the current time. Returns the created reminder with its id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "What to remind about, shown in the notification")
                })
                put("delay_minutes", buildJsonObject {
                    put("type", "number")
                    put("description", "Minutes from now until the reminder fires (optional)")
                })
                put("at", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute local time 'yyyy-MM-dd HH:mm' (optional)")
                })
            },
            required = listOf("title")
        )
    },
    execute = {
        val params = it.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull ?: error("title is required")
        val delayMinutes = params["delay_minutes"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val at = params["at"]?.jsonPrimitive?.contentOrNull
        val zone = ZoneId.systemDefault()
        val triggerAt = when {
            delayMinutes != null -> System.currentTimeMillis() + (delayMinutes * 60_000).toLong()
            !at.isNullOrBlank() -> runCatching {
                LocalDateTime.parse(at.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(zone).toInstant().toEpochMilli()
            }.getOrElse { error("invalid 'at' format, expect 'yyyy-MM-dd HH:mm'") }
            else -> error("either delay_minutes or at is required")
        }
        if (triggerAt <= System.currentTimeMillis()) error("reminder time must be in the future")
        val reminder = store.add(title, triggerAt)
        ReminderScheduler.schedule(context, reminder)
        listOf(UIMessagePart.Text(reminderToJson(reminder, zone).toString()))
    }
)

private fun buildReminderListTool(store: ReminderStore): Tool = Tool(
    name = "reminder_list",
    description = "List all pending local reminders (id, title, trigger time).",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val zone = ZoneId.systemDefault()
        val items = store.list()
        val payload = buildJsonObject {
            put("count", items.size)
            put("reminders", buildJsonArray {
                items.forEach { add(reminderToJson(it, zone)) }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun buildReminderCancelTool(context: Context, store: ReminderStore): Tool = Tool(
    name = "reminder_cancel",
    description = "Cancel a pending local reminder by its id (see reminder_list).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Reminder id to cancel")
                })
            },
            required = listOf("id")
        )
    },
    execute = {
        val id = it.jsonObject["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
        val removed = store.remove(id)
        if (removed) ReminderScheduler.cancel(context, id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", removed)
            put("id", id)
        }.toString()))
    }
)

@Serializable
data class Reminder(
    val id: Int,
    val title: String,
    val triggerAt: Long
)

private fun reminderToJson(reminder: Reminder, zone: ZoneId) = buildJsonObject {
    put("id", reminder.id)
    put("title", reminder.title)
    put("trigger_at", reminder.triggerAt)
    put(
        "trigger_time",
        LocalDateTime.ofInstant(Instant.ofEpochMilli(reminder.triggerAt), zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    )
}

/** SharedPreferences JSON 存储 */
internal class ReminderStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("reminder_store", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Reminder.serializer())

    @Synchronized
    fun list(): List<Reminder> =
        runCatching {
            json.decodeFromString(listSerializer, prefs.getString(KEY, "[]") ?: "[]")
        }.getOrDefault(emptyList())
            .filter { it.triggerAt > System.currentTimeMillis() - 60_000 }
            .sortedBy { it.triggerAt }

    @Synchronized
    fun add(title: String, triggerAt: Long): Reminder {
        val items = list().toMutableList()
        var id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        while (items.any { it.id == id }) id = (id + 1) % Int.MAX_VALUE
        val reminder = Reminder(id, title, triggerAt)
        items.add(reminder)
        save(items)
        return reminder
    }

    @Synchronized
    fun remove(id: Int): Boolean {
        val items = list().toMutableList()
        val removed = items.removeAll { it.id == id }
        if (removed) save(items)
        return removed
    }

    private fun save(items: List<Reminder>) {
        prefs.edit().putString(KEY, json.encodeToString(listSerializer, items)).apply()
    }

    private companion object {
        const val KEY = "items"
    }
}

/** AlarmManager 调度（精确闹钟；USE_EXACT_ALARM 权限使系统不延迟到点触发） */
internal object ReminderScheduler {

    fun schedule(context: Context, reminder: Reminder) {
        val am = alarmManager(context)
        val pi = pendingIntent(context, reminder)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
            } else {
                // 没精确闹钟权限时降级到非精确，但仍允许 Doze 期间唤醒
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
        }
    }

    fun cancel(context: Context, id: Int) {
        alarmManager(context).cancel(pendingIntent(context, Reminder(id, "", 0)))
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, reminder: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminder.id,
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
                putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
