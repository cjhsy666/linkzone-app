package com.cjhsy.linkzone.data.ai

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart

/**
 * 定时闹钟工具：alarm_create。
 *
 * 不自己维护调度，而是调用系统闹钟 App（AlarmClock.ACTION_SET_ALARM）创建一个真正的系统闹钟——
 * 由系统负责到点响铃/通知，本应用不持有任何定时状态，也无法列举或取消系统闹钟。
 */
fun buildReminderTools(context: Context): List<Tool> = listOf(
    buildAlarmCreateTool(context)
)

private fun buildAlarmCreateTool(context: Context): Tool = Tool(
    name = "alarm_create",
    description = """
        Create a system alarm on the user's device via the stock clock app. The system alarm will ring/vibrate at the given time.
        - `hour` and `minute` are required (24-hour local time, e.g. hour=8 minute=30 means 08:30).
        - `message` is the alarm label shown by the clock app.
        - `days` is an optional array of weekdays to repeat: 1=Monday ... 7=Sunday (ISO weekday). Omit for a one-time alarm.
        - `skip_ui` defaults to true (create silently); set false to open the clock app's create screen for the user to confirm.
        Returns success plus the resolved time. This tool cannot list or cancel alarms afterwards — they belong to the system clock app.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hour of day, 0-23 (24-hour local time)")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minute, 0-59")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Alarm label shown by the clock app")
                })
                put("days", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional weekdays to repeat: 1=Mon ... 7=Sun. Omit for one-time.")
                    put("items", buildJsonObject { put("type", "integer") })
                })
                put("skip_ui", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True = create silently (default); false = open clock app create screen")
                })
            },
            required = listOf("hour", "minute")
        )
    },
    execute = {
        val params = it.jsonObject
        val hour = params["hour"]?.jsonPrimitive?.intOrNull ?: error("hour is required (0-23)")
        val minute = params["minute"]?.jsonPrimitive?.intOrNull ?: error("minute is required (0-59)")
        require(hour in 0..23) { "hour must be 0-23" }
        require(minute in 0..59) { "minute must be 0-59" }
        val message = params["message"]?.jsonPrimitive?.contentOrNull ?: "提醒"
        val days = parseDays(params["days"])
        val skipUi = params["skip_ui"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (days != null) {
                val calendarDays = days.map { isoToCalendarDay(it) }.toIntArray()
                putExtra(AlarmClock.EXTRA_DAYS, ArrayList<Int>(calendarDays.toList()))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) == null) {
            error("设备没有可用的闹钟 App")
        }
        context.startActivity(intent)

        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("hour", hour)
            put("minute", minute)
            put("message", message)
            put("days", buildJsonArray { days?.forEach { add(it) } })
            put("skip_ui", skipUi)
        }.toString()))
    }
)

/** ISO 星期（1=周一 ... 7=周日）→ Calendar.xxx_DAY */
private fun isoToCalendarDay(iso: Int): Int =
    when (iso) {
        1 -> java.util.Calendar.MONDAY
        2 -> java.util.Calendar.TUESDAY
        3 -> java.util.Calendar.WEDNESDAY
        4 -> java.util.Calendar.THURSDAY
        5 -> java.util.Calendar.FRIDAY
        6 -> java.util.Calendar.SATURDAY
        7 -> java.util.Calendar.SUNDAY
        else -> error("invalid weekday: $iso, expect 1-7")
    }

private fun parseDays(element: JsonElement?): List<Int>? {
    if (element == null) return null
    return runCatching {
        element.jsonArray.map { it.jsonPrimitive.intOrNull ?: error("days item must be integer") }
    }.getOrNull()
}
