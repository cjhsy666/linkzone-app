package com.cjhsy.linkzone.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cjhsy.linkzone.data.model.MessageSegment
import com.cjhsy.linkzone.ui.chat.md.MarkdownContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * 消息段渲染入口：
 * - [freeCopy] 为 true（自由复制模式）时文本/Markdown 段包在 [SelectionContainer] 里，长按出选区手柄；
 * - 任何内容（文本/Markdown/媒体）长按都经 [onMediaLongClick] 上抛（弹消息操作菜单）；
 *   自由复制模式下长按让位给文本选择，不上抛。
 */
@Composable
fun SegmentContent(
    segments: List<MessageSegment>?,
    fallback: String,
    isSelf: Boolean,
    baseUrl: String,
    freeCopy: Boolean = false,
    onMediaLongClick: (() -> Unit)? = null,
    /** 点击文本/Markdown 非链接区域回调（编辑 / 退出自由复制） */
    onTextClick: (() -> Unit)? = null,
    /**
     * 正文是否占满父容器宽：AI 文档流为 true；用户消息传 false，
     * 纯文本按内容收束贴右，块级元素仍可撑满限宽容器。
     */
    fillWidth: Boolean = true,
) {
    // 自己气泡的前景色跟随主题 onPrimary（暗色主题 primary 是亮青，配深字；浅色紫配白字），
    // 不要硬编码白——暗色下亮青底白字完全看不清
    val onSurface = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    // 自由复制模式下长按 = 文本选择手势，不再上抛弹菜单
    val longClick = if (freeCopy) null else onMediaLongClick
    val linkColor = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    if (segments.isNullOrEmpty()) {
        val plain: @Composable () -> Unit = {
            MarkdownContent(
                markdown = fallback.ifBlank { " " },
                textColor = onSurface,
                linkColor = linkColor,
                selectable = freeCopy,
                onLongClick = longClick,
                onTextClick = onTextClick,
                baseUrl = baseUrl,
                fillWidth = fillWidth,
            )
        }
        if (freeCopy) SelectionContainer(content = plain) else plain()
        return
    }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.forEach {
                SegmentView(
                    seg = it,
                    isSelf = isSelf,
                    baseUrl = baseUrl,
                    onSurface = onSurface,
                    freeCopy = freeCopy,
                    onMediaLongClick = longClick,
                    onTextClick = onTextClick,
                    fillWidth = fillWidth,
                )
            }
        }
    }
    if (freeCopy) SelectionContainer(content = content) else content()
}

// ============================================================================
// 单段分发
// ============================================================================

@Composable
private fun SegmentView(
    seg: MessageSegment,
    isSelf: Boolean,
    baseUrl: String,
    onSurface: Color,
    freeCopy: Boolean = false,
    onMediaLongClick: (() -> Unit)? = null,
    onTextClick: (() -> Unit)? = null,
    fillWidth: Boolean = true,
) {
    val muted = onSurface.copy(alpha = 0.72f)
    val context = LocalContext.current

    fun open(rawUrl: String) {
        if (rawUrl.isBlank()) return
        val url = resolveMedia(rawUrl, baseUrl)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    // 文本段与 AI markdown 回复走同一渲染器：代码块/表格/SVG/图片/加粗等能力完全一致
    when (seg.type) {
        "text", "markdown" -> MarkdownContent(
            markdown = seg.get(if (seg.type == "markdown") "content" else "text"),
            textColor = onSurface,
            linkColor = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            selectable = freeCopy,
            onLongClick = onMediaLongClick,
            onTextClick = onTextClick,
            baseUrl = baseUrl,
            fillWidth = fillWidth,
        )

        "image" -> ImageBlock(seg, baseUrl, badge = null, onLongClick = onMediaLongClick)

        "flash" -> ImageBlock(seg, baseUrl, badge = "闪照", onLongClick = onMediaLongClick)

        "animation" -> ImageBlock(seg, baseUrl, badge = "GIF", onLongClick = onMediaLongClick)

        "voice" -> VoicePlayerRow(
            rawUrl = seg.mediaUrl,
            durationSec = seg.getInt("duration"),
            isSelf = isSelf,
            baseUrl = baseUrl
        )

        "audio" -> MediaPlayerRow(
            rawUrl = seg.mediaUrl,
            baseUrl = baseUrl,
            isSelf = isSelf,
            title = seg.get("title"),
            artist = seg.get("artist")
        )

        "video" -> InlineVideoPlayer(
            rawUrl = seg.mediaUrl,
            rawCover = seg.get("cover"),
            durationSec = seg.getInt("duration"),
            baseUrl = baseUrl
        )

        "file" -> FileBlock(seg, isSelf, onClick = { open(seg.mediaUrl) })

        "reply" -> ReplyQuote(seg, onSurface)

        "link" -> LinkBlock(seg, baseUrl, isSelf, onClick = { open(seg.get("url")) })

        "location" -> LocationBlock(seg, isSelf)

        "contact" -> ContactBlock(seg, isSelf)

        "mention", "mention_all" -> Text(
            seg.display,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
        )

        "face", "sticker" -> FaceChip(seg, isSelf)

        "keyboard" -> KeyboardBlock(seg, onSurface)

        "forward" -> ForwardBlock(seg, onSurface)

        "node" -> NodeBlock(seg, onSurface)

        "music" -> MediaPlayerRow(
            // 优先用可直接播放的音频直链；仅有跳转链接时退回跳转页打开
            rawUrl = seg.get("audio").ifBlank { seg.get("url") },
            baseUrl = baseUrl,
            isSelf = isSelf,
            title = seg.get("title"),
            artist = seg.get("artist"),
            rawCover = seg.get("cover")
        )

        "card" -> GenericCardText(seg, onSurface)

        "rich" -> Text(
            extractXmlItems(seg.get("data")).ifBlank { "[富文本]" },
            style = MaterialTheme.typography.bodyMedium,
            color = onSurface
        )

        "poll" -> PollBlock(seg, onSurface)

        "poke" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.TouchApp, null,
                tint = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("戳了戳你", style = MaterialTheme.typography.bodyMedium, color = onSurface)
        }

        "dice" -> DiceBlock(seg, emoji = "🎲", fallbackLabel = "掷骰子", onSurface = onSurface)

        "rps" -> RpsBlock(seg, onSurface)

        "tool" -> ToolBlock(seg, onSurface)

        "sys.abort" -> Text(
            "已终止${seg.get("reason").let { if (it.isNotBlank()) "：$it" else "" }}",
            style = MaterialTheme.typography.bodySmall, color = muted
        )

        "sys.listen_stop" -> Text(
            "语音监听已停止", style = MaterialTheme.typography.bodySmall, color = muted
        )

        "sys.emotion" -> Text(
            "${seg.get("emoji")} ${seg.get("emotion")}".trim(),
            style = MaterialTheme.typography.bodySmall, color = muted
        )

        else -> Text(
            seg.display, style = MaterialTheme.typography.bodyMedium, color = onSurface
        )
    }
}

// ============================================================================
// 工具调用段
// ============================================================================

/**
 * 工具调用折叠卡片（本地智能体消息）：
 * 一行摘要 = 图标 + 工具名 + 成功/失败，点击展开查看输入/输出（等宽字体）。
 * 刻意保持轻量：浅色底、无阴影、无边框，与思考过程面板的视觉层级一致。
 */
@Composable
private fun ToolBlock(seg: MessageSegment, onSurface: Color) {
    val name = seg.get("name").ifBlank { "工具调用" }
    val ok = seg.getBool("ok")
    var expanded by remember { mutableStateOf(false) }
    val muted = onSurface.copy(alpha = 0.6f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(onSurface.copy(alpha = 0.06f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Build, contentDescription = null,
                tint = muted, modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (ok) "成功" else "失败",
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = muted, modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            val input = seg.get("input")
            val output = seg.get("output")
            if (input.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("输入", style = MaterialTheme.typography.labelSmall, color = muted)
                Spacer(Modifier.height(2.dp))
                Text(
                    input,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = onSurface.copy(alpha = 0.85f)
                )
            }
            if (output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("输出", style = MaterialTheme.typography.labelSmall, color = muted)
                Spacer(Modifier.height(2.dp))
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = onSurface.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// ============================================================================
// 媒体段
// ============================================================================

/** 图片 / 闪照 / 动图：自适应展示，角标；点击全屏查看（双指缩放/双击放大）；长按冒泡弹菜单 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageBlock(
    seg: MessageSegment,
    baseUrl: String,
    badge: String?,
    onLongClick: (() -> Unit)? = null
) {
    val url = resolveMedia(seg.mediaUrl, baseUrl)
    var preview by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { preview = true },
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            // crossfade：远端图片加载完成后淡入，避免突兀闪现
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = seg.get("name").ifBlank { "图片" },
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
    if (preview) {
        ZoomableImageDialog(rawUrl = url, baseUrl = "") { preview = false }
    }
}

/** 文件：图标 + 文件名 + 大小 */
@Composable
private fun FileBlock(seg: MessageSegment, isSelf: Boolean, onClick: () -> Unit) {
    NestedCard(isSelf, onClick = onClick) {
        IconBox(Icons.Filled.Description, isSelf)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                seg.get("name", "title").ifBlank { "文件" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = nestedFg(isSelf),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val size = seg.getInt("size", "file_size")
            if (size > 0) Text(
                formatFileSize(size), style = MaterialTheme.typography.labelSmall,
                color = nestedFg(isSelf).copy(alpha = 0.7f)
            )
        }
    }
}

// ============================================================================
// 结构段
// ============================================================================

/** 回复引用块：竖线 + 发送者 + 内容摘要 */
@Composable
private fun ReplyQuote(seg: MessageSegment, onSurface: Color) {
    val sender = seg.get("sender_name", "nickname")
    val preview = seg.get("text", "content").ifBlank { "..." }
    Row {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(
                    if (onSurface == Color.White) Color.White.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.primary
                )
        )
        Spacer(Modifier.width(8.dp))
        Column {
            if (sender.isNotBlank()) Text(
                sender, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (onSurface == Color.White) Color.White
                else MaterialTheme.colorScheme.primary
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.72f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 链接分享：封面 + 标题 + 描述 + 来源 */
@Composable
private fun LinkBlock(seg: MessageSegment, baseUrl: String, isSelf: Boolean, onClick: () -> Unit) {
    val url = seg.get("url")
    val cover = seg.get("cover").let {
        if (it.isBlank()) it else resolveMedia(it, baseUrl)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(nestedBg(isSelf))
            .clickable(onClick = onClick)
    ) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(cover)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                contentScale = ContentScale.Crop
            )
        }
        Column(Modifier.padding(12.dp)) {
            Text(
                seg.get("title").ifBlank { "链接分享" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = nestedFg(isSelf),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (seg.get("description").isNotBlank()) Text(
                seg.get("description"),
                style = MaterialTheme.typography.bodySmall,
                color = nestedFg(isSelf).copy(alpha = 0.72f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                hostOf(url),
                style = MaterialTheme.typography.labelSmall,
                color = nestedFg(isSelf).copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 位置：卡片，点击打开地图 */
@Composable
private fun LocationBlock(seg: MessageSegment, isSelf: Boolean) {
    val context = LocalContext.current
    val lat = seg.getFloat("latitude")
    val lon = seg.getFloat("longitude")
    val title = seg.get("title", "name")
    val addr = seg.get("address")

    NestedCard(isSelf, onClick = {
        val geo = "geo:$lat,$lon?q=$lat,$lon"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(geo)))
        }
    }) {
        IconBox(Icons.Filled.LocationOn, isSelf)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "位置消息" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = nestedFg(isSelf),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                addr.ifBlank { "%.5f, %.5f".format(lat, lon) },
                style = MaterialTheme.typography.labelSmall,
                color = nestedFg(isSelf).copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 名片 */
@Composable
private fun ContactBlock(seg: MessageSegment, isSelf: Boolean) {
    NestedCard(isSelf) {
        IconBox(Icons.Filled.Person, isSelf)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                seg.get("name").ifBlank { "联系人" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = nestedFg(isSelf),
                maxLines = 1
            )
            Text(
                "ID: ${seg.get("user_id")}",
                style = MaterialTheme.typography.labelSmall,
                color = nestedFg(isSelf).copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

/** QQ 表情 / 商城表情：小徽章 */
@Composable
private fun FaceChip(seg: MessageSegment, isSelf: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(nestedBg(isSelf))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            when {
                seg.get("name").isNotBlank() -> seg.get("name")
                seg.get("summary").isNotBlank() -> seg.get("summary")
                seg.get("id").isNotBlank() -> "表情 #${seg.get("id")}"
                else -> "表情"
            },
            style = MaterialTheme.typography.bodySmall,
            color = nestedFg(isSelf)
        )
    }
}

/** 按钮卡片：标题 + 按钮行（url 可点，data 仅展示） */
@Composable
private fun KeyboardBlock(seg: MessageSegment, onSurface: Color) {
    val light = onSurface != Color.White
    val accent = if (light) MaterialTheme.colorScheme.primary else Color.White
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (light) MaterialTheme.colorScheme.surfaceVariant else Color.White.copy(alpha = 0.18f))
            .padding(12.dp)
    ) {
        if (seg.get("title").isNotBlank()) Text(
            seg.get("title"), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = onSurface
        )
        if (seg.get("description").isNotBlank()) Text(
            seg.get("description"), style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp)
        )
        seg.getButtonRows().forEach { row ->
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { btn ->
                    val clickable = btn.url.isNotBlank()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = if (clickable) 0.16f else 0.08f))
                            .then(if (clickable) Modifier.clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(btn.url))
                                    )
                                }
                            } else Modifier)
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            btn.label.ifBlank { "按钮" },
                            style = MaterialTheme.typography.labelLarge,
                            color = accent.copy(alpha = if (clickable) 1f else 0.6f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** 合并转发：标题 + 前几条节点摘要 */
@Composable
private fun ForwardBlock(seg: MessageSegment, onSurface: Color) {
    val nodes = seg.getSubSegments()
    val light = onSurface != Color.White
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (light) MaterialTheme.colorScheme.surfaceVariant else Color.White.copy(alpha = 0.18f))
            .padding(12.dp)
    ) {
        Text(
            "合并转发", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = onSurface
        )
        Spacer(Modifier.height(6.dp))
        if (nodes.isNotEmpty()) {
            nodes.take(3).forEach { node ->
                val name = node.get("name").ifBlank { "用户" }
                val text = node.getSubSegments().firstOrNull()?.display ?: node.display
                Text(
                    "$name：${text.take(20)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.78f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            if (nodes.size > 3) Text(
                "共 ${nodes.size} 条记录",
                style = MaterialTheme.typography.labelSmall,
                color = onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                "转发记录 #${seg.get("id")}",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

/** 节点（独立出现时） */
@Composable
private fun NodeBlock(seg: MessageSegment, onSurface: Color) {
    val name = seg.get("name").ifBlank { "用户" }
    val text = seg.getSubSegments().firstOrNull()?.display ?: ""
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$name：", style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium, color = onSurface
        )
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.75f),
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 投票 */
@Composable
private fun PollBlock(seg: MessageSegment, onSurface: Color) {
    val light = onSurface != Color.White
    val options = seg.getStringList("options")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (light) MaterialTheme.colorScheme.surfaceVariant else Color.White.copy(alpha = 0.18f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Poll, null, tint = onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                seg.get("title").ifBlank { "投票" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = onSurface
            )
        }
        Spacer(Modifier.height(8.dp))
        options.forEachIndexed { i, opt ->
            // 超过 26 个选项后字母会越出 A-Z（Char + Int 直接溢出到 '[' 等），
            // 退化为数字序号
            val label = if (i < 26) ('A' + i).toString() else (i + 1).toString()
            Text(
                "$label. $opt",
                style = MaterialTheme.typography.bodyMedium,
                color = onSurface.copy(alpha = 0.85f),
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
        val hints = buildList {
            if (seg.getBool("multi")) add("多选")
            if (seg.getBool("anonymous")) add("匿名")
            if (seg.getInt("expiry") > 0) add("限时${seg.getInt("expiry") / 60}分钟")
        }.joinToString(" · ")
        if (hints.isNotBlank()) Text(
            hints, style = MaterialTheme.typography.labelSmall,
            color = onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 骰子 */
@Composable
private fun DiceBlock(seg: MessageSegment, emoji: String, fallbackLabel: String, onSurface: Color) {
    val value = seg.getInt("value")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (value in 1..6) diceFace(value) else emoji,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (value in 1..6) "点数：$value" else fallbackLabel,
            style = MaterialTheme.typography.bodyMedium, color = onSurface
        )
    }
}

/** 猜拳 */
@Composable
private fun RpsBlock(seg: MessageSegment, onSurface: Color) {
    when (val v = seg.getInt("value")) {
        1L, 2L, 3L -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rpsEmoji(v), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(8.dp))
            Text(rpsLabel(v), style = MaterialTheme.typography.bodyMedium, color = onSurface)
        }
        else -> Text(
            seg.get("emoji").ifBlank { "猜拳" },
            style = MaterialTheme.typography.bodyMedium, color = onSurface
        )
    }
}

/** QQ JSON 卡片兜底：尽力提取标题/描述 */
@Composable
private fun GenericCardText(seg: MessageSegment, onSurface: Color) {
    val direct = seg.get("description", "title")
    val nested = seg.data["data"]?.let { el ->
        runCatching {
            if (el is JsonObject) {
                listOf("desc", "title", "prompt").firstNotNullOfOrNull { k ->
                    el[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }
            } else {
                val prim = el.jsonPrimitive
                if (prim.isString) prim.contentOrNull else null
            }
        }.getOrNull()
    }
    Text(
        nested ?: direct.ifBlank { "[卡片消息]" },
        style = MaterialTheme.typography.bodyMedium, color = onSurface
    )
}

// Markdown 段渲染见 [MarkdownContent]（ui.chat.md 包：multiplatform-markdown-renderer 纯 Compose 渲染）

private val xmlItemRegex = Regex("""item\s+name="([^"]+)"""")

// ============================================================================
// 通用容器
// ============================================================================

@Composable
private fun NestedCard(
    isSelf: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(nestedBg(isSelf))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
internal fun IconBox(icon: ImageVector, isSelf: Boolean) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(nestedFg(isSelf).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = nestedFg(isSelf), modifier = Modifier.size(21.dp))
    }
}

@Composable
internal fun nestedBg(isSelf: Boolean): Color =
    if (isSelf) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant

@Composable
internal fun nestedFg(isSelf: Boolean): Color =
    if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

// ============================================================================
// 工具
// ============================================================================

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

private fun hostOf(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.") ?: url
}.getOrDefault(url)

/** 从 QQ XML 富文本中提取 item name（与后端 displayQQXML 对齐） */
private fun extractXmlItems(data: String): String =
    xmlItemRegex.findAll(data).mapNotNull { it.groupValues.getOrNull(1)?.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("，")

private fun diceFace(value: Long): String = when (value) {
    1L -> "⚀"; 2L -> "⚁"; 3L -> "⚂"; 4L -> "⚃"; 5L -> "⚄"; else -> "⚅"
}

private fun rpsEmoji(value: Long): String = when (value) {
    1L -> "✊"; 2L -> "✌️"; else -> "✋"
}

private fun rpsLabel(value: Long): String = when (value) {
    1L -> "石头"; 2L -> "剪刀"; else -> "布"
}
