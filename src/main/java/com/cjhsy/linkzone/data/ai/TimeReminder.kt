package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.ai.core.MessageRole
import com.cjhsy.linkzone.ai.ui.UIMessage
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 时间提醒注入（移植自 RikkaHub TimeReminderTransformer 的 UIMessage 版）。
 *
 * 在首条用户消息前、以及与上一条消息间隔超过阈值的用户消息前，
 * 注入一条仅存在于本次请求上下文的合成用户消息 `<time_reminder>`，
 * 帮助模型感知对话时间跨度（跨天/隔夜对话不再混淆时间）。
 */
fun applyTimeReminder(
    messages: List<UIMessage>,
    intervalMinutes: Int = 60,
): List<UIMessage> {
    val thresholdSeconds = intervalMinutes.coerceAtLeast(1).toLong() * 60
    val result = mutableListOf<UIMessage>()
    var firstUserFound = false
    for (i in messages.indices) {
        val current = messages[i]
        if (current.role == MessageRole.USER && current.parts.any { it is UIMessagePart.Text }) {
            if (!firstUserFound) {
                firstUserFound = true
                result += buildTimeReminderMessage(null, current.createdAt)
            } else {
                val previous = messages[i - 1]
                val gapSeconds = java.time.Duration.between(previous.createdAt, current.createdAt).seconds
                if (gapSeconds > thresholdSeconds) {
                    result += buildTimeReminderMessage(gapSeconds, current.createdAt)
                }
            }
        }
        result += current
    }
    return result
}

private fun buildTimeReminderMessage(gapSeconds: Long?, at: LocalDateTime): UIMessage {
    val dayOfWeek = at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val content = if (gapSeconds != null) {
        "<time_reminder>Current time: $dayOfWeek, $timeStr (${formatGap(gapSeconds)} since last message)</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $timeStr</time_reminder>"
    }
    return UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(content)),
        createdAt = at,
        isSynthetic = true
    )
}

private fun formatGap(seconds: Long): String = when {
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86400 -> "${seconds / 3600} h"
    else -> "${seconds / 86400} d"
}
