package com.cjhsy.linkzone.data.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cjhsy.linkzone.MainActivity
import com.cjhsy.linkzone.R

/** 提醒触发广播接收器：到点后发系统通知（点击回到 App） */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (id < 0) return
        // 触发即视为完成，从存储移除
        ReminderStore(context).remove(id)
        notify(context, id, title)
    }

    private fun notify(context: Context, id: Int, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "本地 AI 工具创建的定时提醒"
                }
            )
        }
        val openIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("提醒")
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        nm.notify(NOTIFICATION_BASE + (id % 10000), notification)
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        private const val CHANNEL_ID = "local_reminders"
        private const val NOTIFICATION_BASE = 7200
    }
}
