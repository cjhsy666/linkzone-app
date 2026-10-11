package com.cjhsy.linkzone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.cjhsy.linkzone.MainActivity
import com.cjhsy.linkzone.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 AI 生成前台服务 —— 移植自 RikkaHub ChatGenerationForegroundService 的裁剪版。
 *
 * 生成开始时 [acquire] 提升为前台服务并展示常驻通知，使 App 退到后台/锁屏后
 * 网络请求与协程不被系统杀死；所有生成结束（[release] 计数归零）后自动停止。
 *
 * targetSdk 34：使用 dataSync 前台服务类型（清单声明 + 运行时类型参数）。
 */
class ChatGenerationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                val convId = intent.getStringExtra(EXTRA_CONV_ID).orEmpty()
                startForegroundCompat(buildNotification(convId))
            }
            ACTION_RELEASE -> {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(convId: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (convId.isNotBlank()) putExtra(EXTRA_CONV_ID, convId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AI 正在生成回复")
            .setContentText("点击返回 LinkZone")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    priority = Notification.PRIORITY_LOW
                }
            }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI 生成",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "本地 AI 回复生成进行中"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "chat_generation"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_ACQUIRE = "com.cjhsy.linkzone.action.GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "com.cjhsy.linkzone.action.GENERATION_RELEASE"
        const val EXTRA_CONV_ID = "conv_id"

        private val activeCount = AtomicInteger(0)

        fun acquire(context: Context, convId: String = "") {
            // 已在前台（连续多轮排队生成）时只计数，不重启通知
            if (activeCount.incrementAndGet() > 1) return
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_CONV_ID, convId)
            }
            startService(context, intent)
        }

        fun release(context: Context) {
            if (activeCount.decrementAndGet() > 0) return
            activeCount.set(0)
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
            }
            startService(context, intent)
        }

        private fun startService(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // 后台启动限制等场景：无前台保活也不影响生成本身（协程仍在进程内运行）
            }
        }
    }
}
