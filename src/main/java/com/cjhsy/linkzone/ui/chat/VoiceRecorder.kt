package com.cjhsy.linkzone.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * 按住说话录音器：AAC 单声道、44.1kHz、128kbps，封装为 m4a。
 *
 * 生命周期由调用方保证：
 * - [start] 成功后必须配对 [finish]（发送）或 [cancel]（取消/失败清理）
 * - API 31+ 使用带 Context 的 MediaRecorder 构造（旧构造已废弃但仍可工作）
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startElapsed: Long = 0L

    /** 是否正在录音 */
    val isRecording: Boolean get() = recorder != null

    /** 开始录音；失败返回 false（通常是被占用/初始化错误），失败后内部已清理 */
    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(
            context.cacheDir,
            "voice_${System.currentTimeMillis()}.m4a"
        )
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = file
            startElapsed = SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            runCatching { mr.release() }
            file.delete()
            false
        }
    }

    /**
     * 结束并返回录音文件与时长（秒）。
     * 异常或时长不足约 0.4s 时返回 null 并清理临时文件。
     */
    fun finish(): Result? {
        val mr = recorder ?: return null
        val file = outputFile
        val durationMs = SystemClock.elapsedRealtime() - startElapsed
        val stoppedOk = runCatching {
            // 部分 ROM 立即 stop 会抛 IllegalStateException，先给一个极小缓冲
            mr.stop()
        }.isSuccess
        runCatching { mr.release() }
        recorder = null
        outputFile = null
        if (file == null || !file.exists()) return null
        if (!stoppedOk || durationMs < 400 || file.length() <= 0) {
            file.delete()
            return null
        }
        // 四舍五入到秒
        return Result(file, (durationMs + 500) / 1000)
    }

    /** 放弃录音并删除临时文件 */
    fun cancel() {
        val mr = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        file?.delete()
    }

    data class Result(val file: File, val durationSec: Long)
}
