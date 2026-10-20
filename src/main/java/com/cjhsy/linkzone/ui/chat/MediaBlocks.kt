package com.cjhsy.linkzone.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InlineVideoPlayer(
    rawUrl: String,
    rawCover: String,
    durationSec: Long,
    title: String = "",
    baseUrl: String,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val videoUrl = remember(rawUrl) { resolveMedia(rawUrl, baseUrl) }
    val coverUrl = remember(rawCover) {
        if (rawCover.isBlank()) "" else resolveMedia(rawCover, baseUrl)
    }
    var activated by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    val fg = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val cardBg = if (dark) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.03f)
    val cardBorder = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    if (!activated) {
        // 未激活：卡片包裹封面 + 播放按钮 + 时长角标
        Column(
            modifier = Modifier
                .widthIn(max = 288.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = AudioCardShape,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f)
                )
                .clip(AudioCardShape)
                .background(cardBg)
                .border(1.dp, cardBorder, AudioCardShape)
                .combinedClickable(
                    onClick = { showSheet = true },
                    onLongClick = onLongClick
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Color.Black)
            ) {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = "视频封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                // 中央播放按钮（点击直接播放，不弹窗）
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { activated = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, "播放视频", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                if (durationSec > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(formatDuration(durationSec), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
            // 底部信息行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow, null,
                    tint = fg.copy(alpha = 0.52f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    title.ifBlank { "视频" },
                    fontSize = 12.sp,
                    color = fg.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("›", fontSize = 12.sp, color = fg.copy(alpha = 0.50f))
            }
        }
    } else {
        // 已激活：播放器包在卡片内，保持视觉一致性
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
        Column(
            modifier = Modifier
                .widthIn(max = 288.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = AudioCardShape,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f)
                )
                .clip(AudioCardShape)
                .background(cardBg)
                .border(1.dp, cardBorder, AudioCardShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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
            // 底部信息行（点击弹出操作弹窗）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSheet = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow, null,
                    tint = fg.copy(alpha = 0.52f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    title.ifBlank { "视频" },
                    fontSize = 12.sp,
                    color = fg.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("›", fontSize = 12.sp, color = fg.copy(alpha = 0.50f))
            }
        }

        // 全屏复用同一 player：保留播放进度与缓冲，避免从头重播
        if (fullscreen) {
            VideoFullscreenDialog(player) { fullscreen = false }
        }
    }

    // 操作弹窗（不含播放项）
    if (showSheet) {
        val downloadName = if (title.isNotBlank()) "$title.mp4" else "video_${System.currentTimeMillis()}.mp4"
        VideoActionSheet(
            title = title.ifBlank { "视频" },
            coverUrl = coverUrl,
            videoUrl = videoUrl,
            downloadFileName = downloadName,
            onDismiss = { showSheet = false }
        )
    }
}

/**
 * 视频操作弹窗：下载 / 复制链接。
 * 与音卡 AudioActionSheet 同设计语言。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoActionSheet(
    title: String,
    coverUrl: String,
    videoUrl: String,
    downloadFileName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var downloadState by remember { mutableStateOf(DownloadState.Idle) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 34.dp)
        ) {
            // 头部：封面 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 下载视频
                when (downloadState) {
                    DownloadState.Downloading -> SheetActionItem(
                        icon = Icons.Filled.Download,
                        label = "下载中…",
                        desc = "正在保存",
                        enabled = false
                    )
                    DownloadState.Downloaded -> SheetActionItem(
                        icon = Icons.Filled.CheckCircle,
                        label = "已下载",
                        desc = "已保存到 LinkZone/Videos",
                        enabled = false
                    )
                    DownloadState.Failed -> SheetActionItem(
                        icon = Icons.Filled.Refresh,
                        label = "重试下载",
                        desc = "上次下载失败",
                        onClick = {
                            downloadState = DownloadState.Downloading
                            downloadToPublic(context, videoUrl, downloadFileName, "Videos") { ok ->
                                downloadState = if (ok) DownloadState.Downloaded else DownloadState.Failed
                            }
                        }
                    )
                    else -> SheetActionItem(
                        icon = Icons.Filled.Download,
                        label = "下载视频",
                        desc = "保存到 LinkZone/Videos",
                        onClick = {
                            downloadState = DownloadState.Downloading
                            downloadToPublic(context, videoUrl, downloadFileName, "Videos") { ok ->
                                downloadState = if (ok) DownloadState.Downloaded else DownloadState.Failed
                            }
                        }
                    )
                }

                // 复制链接
                if (videoUrl.isNotBlank()) {
                    SheetActionItem(
                        icon = Icons.Filled.ContentCopy,
                        label = "复制链接",
                        desc = "复制视频地址到剪贴板",
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("链接", videoUrl))
                            Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

/** 秒数 → mm:ss / hh:mm:ss */
private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
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
// 音频 / 音乐卡片（音卡）：
// 独立卡片视觉、不套气泡 —— 封面内嵌播放按钮（触屏常显）、种子波形进度条、
// 底部来源行。可播放卡 = 头部 + 波形行 + 来源行；静态卡（无直链，只有跳转）
// = 头部 + 「去 X 播放」来源行。播放只在封面按钮上，点卡片主体打开跳转链接。
// ============================================================================

private val AudioCardShape = RoundedCornerShape(16.dp)
private val AudioCoverShape = RoundedCornerShape(12.dp)

/** 封面渐变调色板（无封面图时的替代视觉），按标题哈希取色 */
private val CoverPalettes = listOf(
    0xFF8B5CF6 to 0xFFEC4899,
    0xFF06B6D4 to 0xFF3B82F6,
    0xFFF59E0B to 0xFFEF4444,
    0xFF10B981 to 0xFF0EA5E9,
    0xFF6366F1 to 0xFFA855F7,
    0xFFF43F5E to 0xFFFB923C
)

private val CoverGlyphs = listOf("♪", "♫", "♬")

/** 种子波形高度（0.14..1，正弦包络），与 Web 端设计稿同一 LCG 算法 */
private fun waveHeights(seed: Int, count: Int = 48): List<Float> {
    var s = abs(seed.takeIf { it != 0 } ?: 7).toLong().coerceIn(1L, 2147483646L)
    val out = ArrayList<Float>(count)
    for (i in 0 until count) {
        s = s * 16807 % 2147483647L
        val r = s / 2147483647.0
        val env = 0.32 + 0.68 * sin(i.toDouble() / (count - 1) * Math.PI)
        out += (env * (0.38 + r * 0.82)).toFloat().coerceIn(0.14f, 1f)
    }
    return out
}

/** 跳转链接推断来源名（deeplink/未知域名走兜底） */
private fun musicSourceName(url: String): String {
    val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
    return when {
        "music.163.com" in host -> "网易云音乐"
        host.endsWith("qq.com") || "qqmusic" in host -> "QQ 音乐"
        "kugou.com" in host -> "酷狗音乐"
        "kuwo.cn" in host -> "酷我音乐"
        "bilibili.com" in host -> "哔哩哔哩"
        "ximalaya.com" in host -> "喜马拉雅"
        "douyin.com" in host -> "汽水音乐"
        host.isNotBlank() -> host.removePrefix("www.")
        else -> "外部应用"
    }
}

/** 下载状态 */
private enum class DownloadState { Idle, Downloading, Downloaded, Failed }

/**
 * 音卡操作弹窗（不含播放）：打开来源 / 下载音频 / 复制链接。
 * 对齐 Web 设计稿：头部封面+标题歌手，操作列表无播放项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioActionSheet(
    title: String,
    artist: String,
    coverUrl: String,
    coverGradient: Pair<Color, Color>,
    coverGlyph: String,
    jumpUrl: String,
    downloadUrl: String,
    downloadFileName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var downloadState by remember { mutableStateOf(DownloadState.Idle) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
        ) {
            // ---- 头部：封面 + 标题/歌手 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(coverGradient.first, coverGradient.second)))
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(coverGlyph, fontSize = 20.sp, color = Color.White.copy(alpha = 0.92f))
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            artist,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // ---- 操作列表（无播放项）----
            Column(Modifier.padding(top = 6.dp)) {
                // 打开来源
                if (jumpUrl.isNotBlank()) {
                    SheetActionItem(
                        icon = Icons.Filled.OpenInNew,
                        label = "打开来源",
                        desc = "跳转到来源播放",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(jumpUrl))
                                )
                            }
                            onDismiss()
                        }
                    )
                }

                // 下载音频（状态流转：下载中→已下载/失败重试）
                if (downloadUrl.isNotBlank()) {
                    when (downloadState) {
                        DownloadState.Downloading -> SheetActionItem(
                            icon = Icons.Filled.Download,
                            label = "下载中…",
                            desc = "正在保存",
                            enabled = false
                        )
                        DownloadState.Downloaded -> SheetActionItem(
                            icon = Icons.Filled.CheckCircle,
                            label = "已下载",
                            desc = "已保存到下载目录",
                            enabled = false
                        )
                        DownloadState.Failed -> SheetActionItem(
                            icon = Icons.Filled.Refresh,
                            label = "重试下载",
                            desc = "上次下载失败",
                            onClick = {
                                downloadState = DownloadState.Downloading
                                downloadToPublic(context, downloadUrl, downloadFileName, "Music") { ok ->
                                    downloadState = if (ok) DownloadState.Downloaded else DownloadState.Failed
                                }
                            }
                        )
                        else -> SheetActionItem(
                            icon = Icons.Filled.Download,
                            label = "下载音频",
                            desc = "保存到 LinkZone/Music",
                            onClick = {
                                downloadState = DownloadState.Downloading
                                downloadToPublic(context, downloadUrl, downloadFileName, "Music") { ok ->
                                    downloadState = if (ok) DownloadState.Downloaded else DownloadState.Failed
                                }
                            }
                        )
                    }
                }

                // 复制链接：优先复制音频直链（下载地址），无直链时复制跳转链接
                val copyUrl = downloadUrl.ifBlank { jumpUrl }
                if (copyUrl.isNotBlank()) {
                    SheetActionItem(
                        icon = Icons.Filled.ContentCopy,
                        label = "复制链接",
                        desc = "复制音频地址到剪贴板",
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("链接", copyUrl))
                            Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }

                // 空状态
                if (jumpUrl.isBlank() && downloadUrl.isBlank()) {
                    SheetActionItem(
                        icon = Icons.Filled.OpenInNew,
                        label = "暂无可用操作",
                        desc = "这张卡没有链接或音频",
                        enabled = false
                    )
                }
            }
        }
    }
}

/** Sheet 操作行：图标 + 主文案 + 副文案 */
@Composable
private fun SheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/**
 * 下载到 LinkZone 分类目录（MediaStore.Downloads 下的 LinkZone/{category}/）。
 * targetSdk 29+ 无需 WRITE_EXTERNAL_STORAGE。完成后回调主线程。
 */
private fun downloadToPublic(
    context: Context,
    url: String,
    fileName: String,
    category: String,
    onComplete: (Boolean) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        val ok = runCatching {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("empty body")

                val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val relativePath = "Download/LinkZone/$category"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")

                resolver.openOutputStream(uri)?.use { out ->
                    body.byteStream().copyTo(out)
                } ?: throw IllegalStateException("openOutputStream failed")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            }
        }.getOrDefault(false)

        withContext(Dispatchers.Main) {
            onComplete(ok)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioCard(
    rawUrl: String,
    jumpUrl: String,
    title: String,
    artist: String,
    rawCover: String,
    baseUrl: String,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val playable = rawUrl.isNotBlank()
    val audio = rememberAudioPlayback(rawUrl, baseUrl)
    val playing = playable && audio.isPlaying
    val loading = playable && audio.isPreparing
    val failed = playable && audio.hasError
    var showSheet by remember { mutableStateOf(false) }

    val fg = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val errorC = MaterialTheme.colorScheme.error
    val cardBg = if (dark) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.03f)
    val cardBorderIdle = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    // 呼吸动画：仅播放中读取 breath.value，静止时不触发逐帧重组
    val breath = rememberInfiniteTransition(label = "audioBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "breath"
    )

    // 封面视觉：有封面图用图；否则按标题哈希取渐变色 + 音符字符
    val coverUrl = if (rawCover.isBlank()) "" else resolveMedia(rawCover, baseUrl)
    val seed = remember(title) { abs(title.ifBlank { "音频" }.hashCode()) }
    val (c1, c2) = CoverPalettes[seed % CoverPalettes.size]
    val glyph = CoverGlyphs[seed % CoverGlyphs.size]

    // 波形进度：轮询值经线性补间插值成连续动画（300ms 轮询 + 300ms tween = 匀速）
    val waveProgress by animateFloatAsState(
        targetValue = audio.progress(),
        animationSpec = tween(300, easing = LinearEasing),
        label = "audioWaveProgress"
    )

    // 点击卡片主体 → 打开操作弹窗（设计稿：弹窗只放卡片上没有的操作）
    Column(
        modifier = Modifier
            .widthIn(max = 288.dp)
            .shadow(
                elevation = 10.dp,
                shape = AudioCardShape,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f),
                spotColor = if (playing) primary.copy(alpha = 0.25f)
                else Color.Black.copy(alpha = if (dark) 0.30f else 0.10f)
            )
            .clip(AudioCardShape)
            .background(cardBg)
            .border(
                width = 1.dp,
                color = when {
                    failed -> errorC.copy(alpha = 0.30f)
                    playing -> primary.copy(alpha = 0.26f + 0.12f * breath.value)
                    else -> cardBorderIdle
                },
                shape = AudioCardShape
            )
            .combinedClickable(onClick = { showSheet = true }, onLongClick = onLongClick)
            .padding(14.dp)
    ) {
        // ---- 头部：封面（内嵌播放按钮）+ 标题/歌手 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(AudioCoverShape)
                    .background(Brush.linearGradient(listOf(Color(c1), Color(c2))))
            ) {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            glyph,
                            fontSize = 22.sp,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                }
                // 光影叠层：上部高光 + 下部压暗，替代设计稿封面的径向渐变
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(0.16f), Color.Transparent, Color.Black.copy(0.20f))
                            )
                        )
                )
                if (playing) {
                    // 播放中封面呼吸描边 + 光晕感
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                1.5.dp,
                                primary.copy(alpha = 0.30f + 0.18f * breath.value),
                                AudioCoverShape
                            )
                    )
                }
                // 封面内播放按钮（触屏设备常显；加载中转圈；失败可点重试）
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable(enabled = playable && !loading) {
                            if (audio.hasError) audio.retry() else audio.toggle()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.6.dp,
                            color = Color.White
                        )
                        playing -> Icon(
                            Icons.Filled.Pause, "暂停",
                            tint = Color.White, modifier = Modifier.size(18.dp)
                        )
                        else -> Icon(
                            Icons.Filled.PlayArrow,
                            if (failed) "重试播放" else "播放",
                            tint = Color.White, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "音频消息" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotBlank()) {
                    Text(
                        artist,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = fg.copy(alpha = 0.64f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        // ---- 波形行（可播放卡专属）：波形 + 时长 ----
        if (playable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    AudioWave(
                        heights = remember(seed) { waveHeights(seed) },
                        progress = waveProgress,
                        playing = playing,
                        failed = failed,
                        loading = loading,
                        playedTop = primary,
                        playedBottom = tertiary,
                        idleColor = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.16f),
                        failedColor = errorC.copy(alpha = 0.30f),
                        enabled = !loading && !failed && audio.durationMs > 0,
                        onSeek = { f ->
                            val d = audio.durationMs
                            if (d > 0) audio.seekTo((f * d).toInt().coerceIn(0, d))
                        }
                    )
                    // 加载中遮罩：波形压暗 + 居中转圈
                    if (loading) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.5.dp,
                                color = primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "加载中…",
                                fontSize = 10.5.sp,
                                color = fg.copy(alpha = 0.72f)
                            )
                        }
                    }
                    // 失败遮罩：提示 + 重试
                    if (failed) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠ 播放失败", fontSize = 10.5.sp, color = errorC)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, errorC.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                    .background(errorC.copy(alpha = 0.10f))
                                    .clickable { audio.retry() }
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "重试",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = errorC
                                )
                            }
                        }
                    }
                }
                // 播放中显示进度时间，其余显示总时长
                Text(
                    when {
                        audio.durationMs <= 0 -> "--:--"
                        playing -> formatTrackTime(audio.positionMs)
                        else -> formatTrackTime(audio.durationMs)
                    },
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.Medium,
                        color = fg.copy(alpha = 0.55f)
                    )
                )
            }
        }

        // ---- 来源行：细分隔线 + 音符 + 来源名 + 箭头，点击打开操作弹窗 ----
        if (jumpUrl.isNotBlank()) {
            val sourceName = remember(jumpUrl) { musicSourceName(resolveMedia(jumpUrl, baseUrl)) }
            Column(Modifier.padding(top = 10.dp)) {
                HorizontalDivider(thickness = 1.dp, color = fg.copy(alpha = 0.08f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.MusicNote, null,
                        tint = fg.copy(alpha = 0.52f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        if (playable) "来自 $sourceName" else "去 $sourceName 播放",
                        fontSize = 10.5.sp,
                        color = fg.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (playable) "›" else "↗",
                        fontSize = 11.sp,
                        color = fg.copy(alpha = 0.50f)
                    )
                }
            }
        }
    }

    // ---- 操作弹窗（不含播放项）----
    if (showSheet) {
        val resolvedJump = if (jumpUrl.isBlank()) "" else resolveMedia(jumpUrl, baseUrl)
        val resolvedDownload = if (rawUrl.isBlank()) "" else resolveMedia(rawUrl, baseUrl)
        val downloadName = if (title.isNotBlank()) "$title.mp3" else "audio_${System.currentTimeMillis()}.mp3"
        AudioActionSheet(
            title = title.ifBlank { "音频消息" },
            artist = artist,
            coverUrl = coverUrl,
            coverGradient = Pair(Color(c1), Color(c2)),
            coverGlyph = glyph,
            jumpUrl = resolvedJump,
            downloadUrl = resolvedDownload,
            downloadFileName = downloadName,
            onDismiss = { showSheet = false }
        )
    }
}

/**
 * 音卡波形：48 根圆角短柱，播放进度内渐变高亮，播放中轻微正弦波动。
 * 点按 / 横向拖动均触发 [onSeek]（0f..1f）。
 */
@Composable
private fun AudioWave(
    heights: List<Float>,
    progress: Float,
    playing: Boolean,
    failed: Boolean,
    loading: Boolean,
    playedTop: Color,
    playedBottom: Color,
    idleColor: Color,
    failedColor: Color,
    enabled: Boolean,
    onSeek: (Float) -> Unit
) {
    // 波动相位：仅播放中在 draw 内读取，静止时波形不重绘
    val wobble = rememberInfiniteTransition(label = "audioWave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
        label = "wobble"
    )
    val alpha = when {
        loading -> 0.25f
        failed -> 0.32f
        else -> 1f
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .then(
                if (enabled) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { off -> onSeek(off.x / size.width) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                else Modifier
            )
    ) {
        val n = heights.size
        if (n == 0) return@Canvas
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val playedX = progress * size.width
        val playedBrush = Brush.verticalGradient(listOf(playedTop, playedBottom))
        val phase = if (playing) wobble.value * 2f * Math.PI.toFloat() else 0f
        val minBar = 2.dp.toPx()
        val maxRadius = 1.5.dp.toPx()
        for (i in 0 until n) {
            val x = i * (bw + gap)
            var hh = heights[i]
            if (playing) hh *= 0.82f + 0.18f * sin(phase + i * 0.38f)
            val bh = maxOf(minBar, hh * size.height)
            val y = (size.height - bh) / 2f
            val radius = CornerRadius(min(bw / 2f, maxRadius))
            val rect = Size(bw, bh)
            when {
                // 失败：整条波形置红
                failed -> drawRoundRect(failedColor, Offset(x, y), rect, radius)
                // 已播放段：主题渐变高亮
                x + bw / 2f <= playedX -> drawRoundRect(playedBrush, Offset(x, y), rect, radius)
                else -> drawRoundRect(idleColor, Offset(x, y), rect, radius)
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
    var hasError by mutableStateOf(false)
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

    /** 播放失败后重试：释放旧实例重建播放器 */
    fun retry() {
        if (isPreparing) return
        hasError = false
        startNew()
    }

    private fun startNew() {
        // 重建前释放旧实例（重试场景旧 player 可能仍存在）
        runCatching { player?.release() }
        player = null
        positionMs = 0
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
                    this@AudioPlaybackHolder.hasError = true
                    VoicePlaybackCenter.clear(this@AudioPlaybackHolder)
                    true
                }
                isPreparing = true
                hasError = false
                prepareAsync()
            }.also { player = it }
            isPlaying = true
            VoicePlaybackCenter.claim(this)
        }.onFailure {
            isPlaying = false
            isPreparing = false
            hasError = true
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
