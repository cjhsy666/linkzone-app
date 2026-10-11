package com.cjhsy.linkzone.tts.controller

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import androidx.annotation.RequiresApi
import com.cjhsy.linkzone.tts.model.AudioFormat
import com.cjhsy.linkzone.tts.model.PlaybackState
import com.cjhsy.linkzone.tts.model.PlaybackStatus
import com.cjhsy.linkzone.tts.model.TTSResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 android.media.MediaPlayer 的音频播放器（替代 RikkaHub 的 ExoPlayer 实现）。
 * 通过内存 MediaDataSource 直接播放字节流，PCM 数据先包装成 WAV 容器。
 */
class AudioPlayer(@Suppress("unused") private val context: Context) {
    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null

    // 暂停/未开始时记录目标速度，待真正播放时再应用（MediaPlayer 变速会自动恢复播放）
    private var pendingSpeed: Float = 1.0f

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    fun resume() {
        val p = player ?: return
        p.start()
        applySpeed(p, pendingSpeed)
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
        startPositionUpdates()
    }

    fun stop() {
        val p = player ?: return
        runCatching { p.stop() }
        stopPositionUpdates()
        _playbackState.update { it.copy(status = PlaybackStatus.Idle, positionMs = 0L) }
    }

    fun clear() {
        // MediaPlayer 无媒体队列概念，reset 即等价于清空
        runCatching { player?.reset() }
    }

    fun release() {
        stopPositionUpdates()
        player?.release()
        player = null
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        runCatching {
            p.seekTo((p.currentPosition + ms).toInt().coerceAtLeast(0))
        }
    }

    fun setSpeed(speed: Float) {
        pendingSpeed = speed
        val p = player ?: return
        if (p.isPlaying) {
            applySpeed(p, speed)
        }
        _playbackState.update { it.copy(speed = speed) }
    }

    // MediaPlayer 变速只能在播放态调用（paused 态调用会自动恢复播放），暂停时仅记录待应用
    private fun applySpeed(p: MediaPlayer, speed: Float) {
        if (speed == 1.0f) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            p.playbackParams = p.playbackParams.setSpeed(speed)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun play(response: TTSResponse) = suspendCancellableCoroutine<Unit> { cont ->
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000)
        } else response.audioData

        stopPositionUpdates()
        val p = (player ?: MediaPlayer().also { player = it })
        runCatching { p.reset() }

        var completed = false
        try {
            p.setDataSource(ByteArrayMediaDataSource(bytes))
            p.setOnPreparedListener { mp ->
                mp.start()
                applySpeed(mp, pendingSpeed)
                _playbackState.update {
                    it.copy(
                        status = PlaybackStatus.Playing,
                        positionMs = 0L,
                        durationMs = (response.duration?.times(1000))?.toLong() ?: it.durationMs
                    )
                }
                startPositionUpdates()
            }
            p.setOnCompletionListener { _ ->
                completed = true
                stopPositionUpdates()
                _playbackState.update {
                    it.copy(
                        status = PlaybackStatus.Ended,
                        positionMs = if (it.durationMs > 0) it.durationMs else it.positionMs
                    )
                }
                if (cont.isActive) cont.resume(Unit)
            }
            p.setOnErrorListener { _, what, extra ->
                stopPositionUpdates()
                _playbackState.update {
                    it.copy(status = PlaybackStatus.Error, errorMessage = "MediaPlayer error what=$what extra=$extra")
                }
                if (!completed && cont.isActive) {
                    cont.resumeWithException(IOException("MediaPlayer error what=$what extra=$extra"))
                }
                true
            }
            p.prepare()
        } catch (e: Exception) {
            _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = e.message) }
            if (cont.isActive) cont.resumeWithException(e)
        }

        cont.invokeOnCancellation {
            runCatching {
                player?.takeIf { it.isPlaying }?.stop()
            }
            stopPositionUpdates()
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                val p = player ?: break
                val duration = runCatching { p.duration }.getOrDefault(0)
                _playbackState.update {
                    it.copy(
                        positionMs = runCatching { p.currentPosition }.getOrDefault(0).toLong(),
                        durationMs = if (duration > 0) duration.toLong() else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    /** 直接持有字节的 MediaDataSource，避免落盘临时文件（API 23+）。 */
    private class ByteArrayMediaDataSource(
        private val data: ByteArray
    ) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val pos = position.toInt()
            val len = minOf(size, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, len)
            return len
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {}
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
