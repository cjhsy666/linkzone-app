package com.cjhsy.linkzone.ui.chat

import android.media.MediaPlayer
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * 媒体地址解析（图片/视频/语音等渲染入口共用）：
 * - http(s):// 远端地址原样；
 * - file:// 本地文件原样（发送前乐观消息直接读本地，秒显）；
 * - 其余视为服务端相对路径，补全 baseUrl。
 */
fun resolveMedia(rawUrl: String, baseUrl: String): String =
    if (rawUrl.startsWith("http") || rawUrl.startsWith("file:")) rawUrl
    else baseUrl.trimEnd('/') + "/" + rawUrl.trimStart('/')

// ============================================================================
// 视频：气泡内 ExoPlayer 直接播放（类 QQ），支持全屏。
// 点击封面才创建播放器，避免列表中多个视频同时解码耗性能。
// ============================================================================

@Composable
fun InlineVideoPlayer(
    rawUrl: String,
    rawCover: String,
    durationSec: Long,
    baseUrl: String
) {
    val videoUrl = remember(rawUrl) { resolveMedia(rawUrl, baseUrl) }
    val coverUrl = remember(rawCover) {
        if (rawCover.isBlank()) "" else resolveMedia(rawCover, baseUrl)
    }
    var activated by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    if (!activated) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable { activated = true }
        ) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "视频封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "播放视频", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (durationSec > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("${durationSec}秒", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    } else {
        val context = LocalContext.current
        val player = remember(videoUrl) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(videoUrl) {
            onDispose { player.release() }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            AndroidPlayerView(player = player, modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = { fullscreen = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(Icons.Filled.Fullscreen, "全屏播放", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        // 全屏复用同一 player：保留播放进度与缓冲，避免从头重播
        if (fullscreen) {
            VideoFullscreenDialog(player) { fullscreen = false }
        }
    }
}

@Composable
private fun AndroidPlayerView(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
        },
        modifier = modifier
    )
}

/**
 * 视频全屏：复用内联播放器实例（同一 player 可从内联 PlayerView 切换到
 * 全屏 PlayerView，Media3 自动处理 surface 切换），进度/缓冲不丢失。
 */
@Composable
private fun VideoFullscreenDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidPlayerView(player = player, modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White)
            }
        }
    }
}

// ============================================================================
// 语音消息：QQ 式胶囊
// - 胶囊宽度随时长增长；自己：主题色胶囊、喇叭朝右；对方：浅灰胶囊、喇叭朝左
// - 播放中三根音柱随进度点亮并轻微跳动；缓冲中显示转圈
// - 全局单播：点击新语音自动暂停其他语音
// ============================================================================

@Composable
fun VoicePlayerRow(rawUrl: String, durationSec: Long, isSelf: Boolean, baseUrl: String) {
    val audio = rememberAudioPlayback(rawUrl, baseUrl)

    val bg = if (isSelf) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    // 前景跟随 onPrimary：暗色主题 primary 是亮青需配深字，浅色紫配白字
    val fg = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    // 段携带时长优先；否则用播放器解析出的时长；都没有时先按 1 秒占位
    val resolvedSec: Long = durationSec.takeIf { it > 0 }
        ?: (audio.durationMs / 1000).toLong().takeIf { it > 0 }
        ?: 1L
    val totalSec = resolvedSec.coerceIn(1L, 60L)

    // 时长 1~60s 映射到 104~224dp（QQ 式），时长未知时解析后平滑变宽
    val capsuleWidth by animateDpAsState(
        targetValue = 104.dp + 120.dp * ((totalSec - 1).toFloat() / 59f),
        animationSpec = tween(220),
        label = "voiceWidth"
    )

    Row(
        modifier = Modifier
            .width(capsuleWidth)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { audio.toggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelf) {
            // 自己：时长在左、喇叭在右（镜像朝右）
            Text(
                "$totalSec″",
                style = MaterialTheme.typography.bodyMedium,
                color = fg
            )
            Spacer(Modifier.weight(1f))
            VoiceBars(
                active = audio.isPlaying,
                progress = audio.progress(),
                tint = fg
            )
            Spacer(Modifier.width(8.dp))
            VoiceSpeakerIcon(audio = audio, flip = true, tint = fg)
        } else {
            // 对方：喇叭在左（朝左）、时长在右
            VoiceSpeakerIcon(audio = audio, flip = false, tint = fg)
            Spacer(Modifier.width(8.dp))
            VoiceBars(
                active = audio.isPlaying,
                progress = audio.progress(),
                tint = fg
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$totalSec″",
                style = MaterialTheme.typography.bodyMedium,
                color = fg
            )
        }
    }
}

/** 喇叭图标；缓冲中替换为转圈；flip=true 水平镜像（自己的消息朝右） */
@Composable
private fun VoiceSpeakerIcon(audio: AudioPlaybackHolder, flip: Boolean, tint: Color) {
    val flipMod = if (flip) Modifier.graphicsLayer { scaleX = -1f } else Modifier
    if (audio.isPreparing) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = tint
        )
    } else {
        Icon(
            Icons.Filled.VolumeUp,
            contentDescription = if (audio.isPlaying) "暂停语音" else "播放语音",
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .then(flipMod)
        )
    }
}

// ============================================================================
// 音频 / 音乐卡片：封面 + 标题 + 进度条拖动 + 播放/暂停
// ============================================================================

@Composable
fun MediaPlayerRow(
    rawUrl: String,
    baseUrl: String,
    isSelf: Boolean,
    title: String = "",
    artist: String = "",
    rawCover: String = ""
) {
    val audio = rememberAudioPlayback(rawUrl, baseUrl)
    val fg = nestedFg(isSelf)
    val coverUrl = if (rawCover.isBlank()) "" else resolveMedia(rawCover, baseUrl)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(nestedBg(isSelf))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                IconBox(Icons.Filled.MusicNote, isSelf)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "音频消息" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotBlank()) {
                    Text(
                        artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(fg.copy(alpha = 0.15f))
                    .clickable { audio.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (audio.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (audio.isPlaying) "暂停" else "播放",
                    tint = fg,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (audio.durationMs > 0) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTrackTime(audio.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.65f)
                )
                Slider(
                    value = audio.positionMs.toFloat(),
                    onValueChange = { audio.seekTo(it.toInt()) },
                    valueRange = 0f..audio.durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = fg,
                        activeTrackColor = fg,
                        inactiveTrackColor = fg.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatTrackTime(audio.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.65f)
                )
            }
        }
    }
}

// ============================================================================
// 音频播放状态持有（MediaPlayer；prepareAsync 支持流式 URL）
// ============================================================================

/** 语音全局单播协调：同时只允许一条语音在播，点击新的自动暂停旧的 */
private object VoicePlaybackCenter {
    var current: AudioPlaybackHolder? = null
        private set

    @Synchronized
    fun claim(holder: AudioPlaybackHolder) {
        val prev = current
        if (prev !== holder) {
            prev?.pauseExternal()
            current = holder
        }
    }

    @Synchronized
    fun clear(holder: AudioPlaybackHolder) {
        if (current === holder) current = null
    }
}

private class AudioPlaybackHolder(private val url: String) {

    private var player: MediaPlayer? = null

    var isPlaying by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    /** 0f..1f 播放进度，供语音音柱逐段点亮 */
    fun progress(): Float {
        val d = durationMs
        return if (d > 0) (positionMs.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun toggle() {
        val existing = player
        if (existing == null) {
            startNew()
            return
        }
        if (isPlaying) {
            existing.pause()
            isPlaying = false
        } else {
            existing.start()
            isPlaying = true
            positionMs = existing.currentPosition
            VoicePlaybackCenter.claim(this)
        }
    }

    /** 被其他语音抢占时外部暂停（不改播放中心指向以外的状态机） */
    fun pauseExternal() {
        runCatching { player?.pause() }
        isPlaying = false
    }

    private fun startNew() {
        runCatching {
            MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { prepared ->
                    durationMs = runCatching { prepared.duration }.getOrDefault(0).coerceAtLeast(0)
                    isPreparing = false
                    prepared.start()
                }
                setOnCompletionListener {
                    // 必须限定外层接收者：apply 内裸写 isPlaying 会解析到
                    // MediaPlayer.isPlaying（只读合成属性）导致无法赋值
                    this@AudioPlaybackHolder.isPlaying = false
                    this@AudioPlaybackHolder.positionMs = 0
                    VoicePlaybackCenter.clear(this@AudioPlaybackHolder)
                }
                setOnErrorListener { _, _, _ ->
                    this@AudioPlaybackHolder.isPlaying = false
                    this@AudioPlaybackHolder.isPreparing = false
                    VoicePlaybackCenter.clear(this@AudioPlaybackHolder)
                    true
                }
                isPreparing = true
                prepareAsync()
            }.also { player = it }
            isPlaying = true
            VoicePlaybackCenter.claim(this)
        }.onFailure {
            isPlaying = false
            isPreparing = false
        }
    }

    fun seekTo(ms: Int) {
        positionMs = ms
        runCatching { player?.seekTo(ms) }
    }

    /** 播放中轮询进度（由 Composable 每 300ms 调用） */
    fun tickPosition() {
        if (isPlaying) {
            positionMs = runCatching { player?.currentPosition ?: positionMs }.getOrDefault(positionMs)
        }
    }

    fun release() {
        VoicePlaybackCenter.clear(this)
        runCatching { player?.release() }
        player = null
        isPlaying = false
        isPreparing = false
    }
}

@Composable
private fun rememberAudioPlayback(rawUrl: String, baseUrl: String): AudioPlaybackHolder {
    val url = remember(rawUrl) { resolveMedia(rawUrl, baseUrl) }
    val holder = remember(url) { AudioPlaybackHolder(url) }

    DisposableEffect(url) {
        onDispose { holder.release() }
    }

    LaunchedEffect(holder.isPlaying) {
        while (holder.isPlaying) {
            holder.tickPosition()
            delay(300)
        }
    }

    return holder
}

// ============================================================================
// 大图预览：全屏 + 双击放大/还原 + 双指缩放拖拽，单击关闭
// ============================================================================

@Composable
fun ZoomableImageDialog(rawUrl: String, baseUrl: String, onDismiss: () -> Unit) {
    val url = remember(rawUrl) { resolveMedia(rawUrl, baseUrl) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
        ) {
            AsyncImage(
                model = url,
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale <= 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                    }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White)
            }
        }
    }
}

// ============================================================================
// 工具
// ============================================================================

/**
 * 语音音柱：三根，按播放进度逐根点亮；播放中有轻微上下波动。
 * @param progress 0f..1f 播放进度
 */
@Composable
private fun VoiceBars(active: Boolean, progress: Float, tint: Color) {
    // 播放中 600ms 周期的波动值（每根错相）
    val transition = rememberInfiniteTransition(label = "voiceBars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "voicePhase"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val lit = active && progress >= i / 3f
            // 波动：正弦起伏 9~15dp；未播放时静态 10/14/11
            val baseHeights = listOf(10f, 15f, 11f)
            val wave = if (active) {
                val p = (phase + i * 0.33f) % 1f
                kotlin.math.sin(p * 2f * Math.PI).toFloat() * 2.5f
            } else 0f
            val h = (baseHeights[i] + wave).coerceIn(7f, 18f).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint.copy(alpha = if (lit) 1f else 0.35f))
            )
        }
    }
}

private fun formatTrackTime(ms: Int): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
