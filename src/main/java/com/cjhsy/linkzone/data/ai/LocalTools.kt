package com.cjhsy.linkzone.data.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 按智能体开关组装本地工具（对齐 RikkaHub LocalTools.getTools 的裁剪版）。
 *
 * - [LocalToolOption.TimeInfo] → get_time_info
 * - [LocalToolOption.Clipboard] → clipboard_tool
 * - [LocalToolOption.AskUser] → ask_user（经 [askUser] 桥接挂起等用户作答）
 * - [LocalToolOption.Calendar] → calendar_query + calendar_create（需系统日历权限）
 *
 * ChartDisplay 未接线：气泡内图表渲染尚未实现，接入后模型会调用但用户看不到结果。
 */
fun createLocalTools(
    context: Context,
    options: List<LocalToolOption>,
    askUser: AskUserBridge,
): List<Tool> = buildList {
    if (options.contains(LocalToolOption.TimeInfo)) add(buildTimeInfoTool())
    if (options.contains(LocalToolOption.Clipboard)) add(buildClipboardTool(context))
    if (options.contains(LocalToolOption.AskUser)) add(buildAskUserTool(askUser))
    if (options.contains(LocalToolOption.Tts)) add(buildTextToSpeechTool(context))
    if (options.contains(LocalToolOption.Javascript)) add(buildJavascriptTool())
    if (options.contains(LocalToolOption.Calendar)) {
        add(buildCalendarQueryTool(context))
        add(buildCalendarCreateTool(context))
    }
    if (options.contains(LocalToolOption.SystemControl)) add(buildPhoneControlTool(context))
    if (options.contains(LocalToolOption.Contacts)) add(buildContactsQueryTool(context))
    if (options.contains(LocalToolOption.SmsCallLog)) {
        add(buildSmsQueryTool(context))
        add(buildCallLogQueryTool(context))
    }
}

/** ask_user 的挂起桥：execute 内等待用户作答，返回 JSON（问题 id → 用户答案） */
fun interface AskUserBridge {
    suspend fun ask(questions: JsonElement): JsonElement
}

private fun buildTimeInfoTool(): Tool = Tool(
    name = "get_time_info",
    description = "Get the current local date and time info from the device. Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val time = now.toLocalTime().withNano(0)
        val weekday = now.dayOfWeek
        val payload = buildJsonObject {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
            put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("weekday_index", weekday.value)
            put("date", date.toString())
            put("time", time.toString())
            put("datetime", now.withNano(0).toString())
            put("timezone", now.zone.id)
            put("utc_offset", now.offset.id)
            put("timestamp_ms", now.toInstant().toEpochMilli())
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun buildClipboardTool(context: Context): Tool = Tool(
    name = "clipboard_tool",
    description = "Read or write plain text from the device clipboard. Use action: read or write. For write, provide text. Do NOT write to the clipboard unless the user has explicitly requested it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("read")
                        add("write")
                    })
                    put("description", "Operation to perform: read or write")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to write to the clipboard (required for write)")
                })
            },
            required = listOf("action")
        )
    },
    execute = {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        when (action) {
            "read" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("text", text)
                }.toString()))
            }

            "write" -> {
                val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", text))
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("text", text)
                }.toString()))
            }

            else -> error("unknown action: $action, must be one of [read, write]")
        }
    }
)

private fun buildAskUserTool(askUser: AskUserBridge): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions when you need clarification, additional information, or confirmation.
        Each question can optionally provide a list of suggested options for the user to choose from.
        The user may provide a free-text answer for every question, including single and multi selection questions.
        For multi selection questions, custom text can be combined with selected options.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put("description", "Optional list of suggested options for the user to choose from")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("text")
                                    add("single")
                                    add("multi")
                                })
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select one option or enter custom text), multi (select options and/or enter custom text)"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    execute = { args ->
        // 挂起等待用户作答；用户停止生成时随协程取消
        listOf(UIMessagePart.Text(askUser.ask(args).toString()))
    }
)

private fun buildCalendarQueryTool(context: Context): Tool = Tool(
    name = "calendar_query",
    description = """
        Query calendar events on the user's device within a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month).
        Returns a list of events with title, description, location, start/end times, and calendar info.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "End time (exclusive), same formats as 'begin'. Defaults to now.")
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("today")
                        add("week")
                        add("month")
                    })
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today."
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter events by title (case-insensitive substring match).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 20.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasCalendarReadPermission(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar read permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val query = params["query"]?.jsonPrimitive?.contentOrNull

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseCalendarTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }.toString()))
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        val selection = if (query != null) {
            "${CalendarContract.Instances.TITLE} LIKE ?"
        } else null
        val selectionArgs = if (query != null) {
            arrayOf("%$query%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = buildJsonArray {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "")
                        put("description", cursor.getString(2) ?: "")
                        put("location", cursor.getString(3) ?: "")
                        val dtStart = cursor.getLong(4)
                        val dtEnd = cursor.getLong(5)
                        val allDay = cursor.getInt(6) == 1
                        if (allDay) {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                } else {
                                    ""
                                }
                            )
                        } else {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString()
                                } else {
                                    ""
                                }
                            )
                        }
                        put("all_day", allDay)
                        put("calendar", cursor.getString(7) ?: "")
                    })
                    count++
                }
            }
        }

        listOf(UIMessagePart.Text(buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("count", events.size)
            put("events", events)
        }.toString()))
    }
)

private fun buildCalendarCreateTool(context: Context): Tool = Tool(
    name = "calendar_create",
    description = """
        Create a new calendar event on the user's device.
        Requires title and start time at minimum. End time defaults to 1 hour after start.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Calendar' permission; if it is not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Event title.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Event description or notes.")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "Event location.")
                })
                put("start", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "End time, same formats as 'start'. Defaults to 1 hour after start.")
                })
                put("all_day", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether this is an all-day event. Default false.")
                })
            },
            required = listOf("title", "start")
        )
    },
    execute = { args ->
        if (!hasCalendarWritePermission(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar write permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }.toString()))
        }

        val params = args.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val startRaw = params["start"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val allDay = params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        if (title.isNullOrBlank() || startRaw.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "MISSING_REQUIRED")
                put("message", "Both 'title' and 'start' are required.")
            }.toString()))
        }

        val zone = ZoneId.systemDefault()
        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = parseCalendarTime(startRaw, zone)
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else if (allDay) {
                startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            } else {
                startTime.plusHours(1)
            }
        } catch (e: Exception) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format.")
            }.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "end must be later than start.")
            }.toString()))
        }

        val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val location = params["location"]?.jsonPrimitive?.contentOrNull ?: ""

        val eventStartMillis: Long
        val eventEndMillis: Long
        val eventTimeZone: String
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "INVALID_RANGE")
                    put("message", "all-day event end date must be later than start date.")
                }.toString()))
            }
            eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eventTimeZone = "UTC"
        } else {
            eventStartMillis = startTime.toInstant().toEpochMilli()
            eventEndMillis = endTime.toInstant().toEpochMilli()
            eventTimeZone = zone.id
        }

        val calendarId = getDefaultCalendarId(context)
        if (calendarId == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_CALENDAR")
                put("message", "No calendar account found on this device. Please add a calendar account first.")
            }.toString()))
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, eventStartMillis)
            put(CalendarContract.Events.DTEND, eventEndMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        if (uri == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "INSERT_FAILED")
                put("message", "Failed to insert calendar event.")
            }.toString()))
        }

        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("event_id", ContentUris.parseId(uri))
            put("title", title)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("all_day", allDay)
            put("location", location)
        }.toString()))
    }
)

private fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun getDefaultCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val writableSelection =
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
    val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
        writableArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        writableSelection,
        writableArgs,
        "${CalendarContract.Calendars.VISIBLE} DESC"
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
}

/** 兼容 ISO 日期 / 本地时间 / 带偏移时间 / epoch 毫秒的宽松解析 */
private fun parseCalendarTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}

// ===================== text_to_speech =====================

private fun buildTextToSpeechTool(context: Context): Tool = Tool(
    name = "text_to_speech",
    description = """
        Speak text aloud to the user using the device's text-to-speech engine.
        Provide the text to speak. The tool returns after the speech finishes.
        Only call this when the user explicitly asks to hear or listen to something.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to speak aloud.")
                })
            },
            required = listOf("text")
        )
    },
    execute = { args ->
        val text = args.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("text is required")
        speakText(context.applicationContext, text)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("spoken_chars", text.length)
        }.toString()))
    }
)

/** 用系统 TextToSpeech 朗读文本（阻塞直到读完或出错） */
private suspend fun speakText(context: Context, text: String) {
    val result = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val instance = tts
            if (instance == null) {
                cont.resumeWith(Result.success(false))
                return@TextToSpeech
            }
            if (status != TextToSpeech.SUCCESS) {
                runCatching { instance.shutdown() }
                cont.resumeWith(Result.success(false))
                return@TextToSpeech
            }
            instance.setLanguage(java.util.Locale.getDefault())
            instance.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onDone(utteranceId: String?) {
                    runCatching { instance.shutdown() }
                    cont.resumeWith(Result.success(true))
                }
                override fun onError(utteranceId: String?) {
                    runCatching { instance.shutdown() }
                    cont.resumeWith(Result.success(false))
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    runCatching { instance.shutdown() }
                    cont.resumeWith(Result.success(false))
                }
                override fun onStart(utteranceId: String?) {}
            })
            runCatching {
                instance.speak(text, TextToSpeech.QUEUE_FLUSH, null, "linkzone-tts-${System.nanoTime()}")
            }.onFailure {
                runCatching { instance.shutdown() }
                cont.resumeWith(Result.success(false))
            }
        }
        cont.invokeOnCancellation { runCatching { tts?.stop(); tts?.shutdown() } }
    }
    if (!result) error("Text-to-speech failed or is not available on this device.")
}
