package com.cjhsy.linkzone.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessageSegment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 纯视觉媒体段：任何一方都不套外层气泡（图片/闪照/动图/视频/语音） */
private val BARE_MEDIA_TYPES = setOf("image", "flash", "animation", "video", "voice")

/**
 * 文档流友好的段类型：AI 长回复以无气泡文档流直出；
 * 不在此集合的段（文件/音频/名片/位置/投票/按钮卡等 IM 卡片）自动套气泡。
 */
private val DOCUMENT_FRIENDLY_TYPES = setOf(
    "text", "markdown", "image", "flash", "animation", "video", "voice", "link",
    "reply", "mention", "mention_all", "sys.abort", "sys.listen_stop", "sys.emotion"
)

/**
 * 统一消息项（云端 / 本地智能体共用）：
 * - 用户：右侧 62% 品牌气泡（纯媒体裸渲染），本地会话点击文字可编辑重发（版本链），
 *   下方快捷操作行：时间戳/复制/编辑(本地)/重生成(本地)/‹ n/m › 切换器
 * - AI/框架：长文本/Markdown 走无气泡文档流 + 思考过程折叠面板 + 下方快捷操作行；
 *   IM 卡片类段（文件/名片/投票等）自动回落到气泡样式
 * - 长按弹出完整操作菜单（复制/自由复制/回复/编辑/重生成/换模型/撤回/删除），
 *   菜单显隐全部由能力参数决定，不再靠消息 id 前缀猜测引擎
 *
 * @param isLocal 是否本地智能体会话（决定头像/署名/默认样式；编辑重发仅本地开放）
 * @param streaming 是否处于本地流式生成中的最后一条 AI 消息（禁动画/隐操作行）
 * @param canReply 可引用回复（云端/本地均开放）
 * @param canRecall 可撤回（云端/本地均开放；云端乐观消息除外）
 * @param canRegenerate 可重新生成（仅本地智能体）
 * @param versionCount 版本链节点版本数（>1 时显示 ‹ n/m › 切换器，RikkaHub 式）
 * @param versionIndex 当前选中版本序号（0 起）
 * @param onSelectVersion 切换版本回调（参数为目标序号）
 * @param highlight 搜索定位高亮
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItemView(
    message: MessageItem,
    baseUrl: String,
    isLocal: Boolean,
    agentEmoji: String,
    streaming: Boolean,
    canReply: Boolean,
    canRecall: Boolean,
    canRegenerate: Boolean,
    onReply: () -> Unit,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateWithModel: () -> Unit,
    versionCount: Int = 1,
    versionIndex: Int = 0,
    onSelectVersion: ((Int) -> Unit)? = null,
    highlight: Boolean = false
) {
    // 长按弹出完整操作菜单；「自由复制」进入内联选择模式，点空白退出恢复原样
    var menuOpen by remember { mutableStateOf(false) }
    var freeCopy by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // 段提取统一走 MessageNodes 扩展（thinking→思考面板；sys.ver/sys.br→过滤；其余→正文）
    val thinkingText = remember(message.id, message.segments) { message.thinkingText() }
    val contentSegs = remember(message.id, message.segments) { message.visibleSegments() }
    val copyableText = remember(message.id, message.segments) {
        contentSegs
            .filter { it.type == "text" || it.type == "markdown" }
            .map { it.get("text", "content") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .let { it.ifBlank { if (thinkingText.isNotBlank()) "" else message.content } }
            .trim()
    }
    val hasText = copyableText.isNotBlank()

    // 搜索定位高亮：气泡走边框呼吸；文档流/裸媒体走柔和底色
    val highlightColor by animateColorAsState(
        targetValue = if (highlight) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        animationSpec = tween(600),
        label = "hlColor"
    )

    // 样式选择：用户消息恒为气泡（纯媒体除外）；
    // 对方消息内容全是文档友好段 → 文档流，否则回落气泡
    val selfPureMedia = message.isSelf && contentSegs.isNotEmpty() &&
        contentSegs.all { it.type in BARE_MEDIA_TYPES }
    val incomingDocument = !message.isSelf && if (contentSegs.isEmpty()) {
        isLocal // 无段的纯 content 兜底：本地按文档流，云端 IM 按气泡
    } else {
        contentSegs.all { it.type in DOCUMENT_FRIENDLY_TYPES }
    }

    // 生成中（内容未定稿）不弹菜单、不显示操作行
    val interactionsEnabled = !(streaming && !message.isSelf)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (freeCopy) Modifier.combinedClickable(
                onClick = { freeCopy = false },
                onLongClick = null
            ) else Modifier),
        horizontalArrangement = if (message.isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.isSelf) {
            // 云端沿用 36dp 头像规格，本地智能体 32dp
            val avatarSize = if (isLocal) 32.dp else 36.dp
            if (isLocal) AgentAvatar(agentEmoji, size = avatarSize)
            else SenderAvatar(message.senderName, size = avatarSize)
            Spacer(Modifier.width(8.dp))
        }

        // 对方消息占满剩余宽度（文档流需要）；气泡自身在内部限宽 62%
        val middleModifier = if (message.isSelf) {
            Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * MAX_BUBBLE_WIDTH_RATIO)
        } else {
            Modifier.weight(1f)
        }
        Column(
            modifier = middleModifier,
            horizontalAlignment = if (message.isSelf) Alignment.End else Alignment.Start
        ) {
            // 发送者署名：云端一直显示；本地仅换模型重答的「名字 · 模型」署名显示
            if (!message.isSelf) {
                val showName = message.senderName.isNotBlank() &&
                    (isLocal && message.senderName.contains("·") || !isLocal)
                if (showName) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    )
                }
            }

            if (message.recalled) {
                RecalledChip()
            } else if (message.isSelf) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SendStatusIndicator(message)
                    if (selfPureMedia) {
                        Box(
                            modifier = Modifier
                                .highlightBackground(highlightColor)
                                .combinedClickable(
                                    onClick = { if (freeCopy) freeCopy = false },
                                    onLongClick = { if (!freeCopy) menuOpen = true }
                                )
                        ) {
                            SegmentContent(
                                segments = contentSegs.ifEmpty { null },
                                fallback = message.content,
                                isSelf = true,
                                baseUrl = baseUrl,
                                freeCopy = freeCopy,
                                onMediaLongClick = { menuOpen = true },
                                onTextClick = { if (freeCopy) freeCopy = false }
                            )
                        }
                    } else {
                        ChatBubble(
                            isSelf = true,
                            highlightColor = highlightColor,
                            uploading = message.uploading,
                            onClick = {
                                if (freeCopy) freeCopy = false else if (hasText && isLocal) onEdit()
                            },
                            onLongClick = { if (!freeCopy) menuOpen = true }
                        ) {
                            SegmentContent(
                                segments = contentSegs.ifEmpty { null },
                                fallback = message.content,
                                isSelf = true,
                                baseUrl = baseUrl,
                                freeCopy = freeCopy,
                                onMediaLongClick = { menuOpen = true },
                                onTextClick = { if (!freeCopy && hasText && isLocal) onEdit() }
                            )
                        }
                    }
                }
            } else if (incomingDocument) {
                // ---------- AI 文档流：思考过程 + 无气泡正文 ----------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .highlightBackground(highlightColor)
                        // 流式生成时内容连续增长，禁用尺寸动画避免布局抖动卡顿
                        .then(if (streaming) Modifier else Modifier.animateContentSize())
                        .combinedClickable(
                            onClick = { if (freeCopy) freeCopy = false },
                            onLongClick = { if (interactionsEnabled && !freeCopy) menuOpen = true }
                        )
                ) {
                    // 正文是否已有实质内容（思考结束后面板应自动收起）
                    val hasAnswerContent = contentSegs.any {
                        it.type != "markdown" ||
                            (it.get("content").isNotBlank() && it.get("content") != "…")
                    }
                    if (thinkingText.isNotBlank()) {
                        ThinkingPanel(
                            thinking = thinkingText,
                            streaming = streaming && !hasAnswerContent
                        )
                    }
                    if (hasAnswerContent) {
                        SegmentContent(
                            segments = contentSegs,
                            fallback = "",
                            isSelf = false,
                            baseUrl = baseUrl,
                            freeCopy = freeCopy,
                            onMediaLongClick = { if (interactionsEnabled) menuOpen = true },
                            onTextClick = { if (freeCopy) freeCopy = false }
                        )
                    } else if (message.content.isNotBlank()) {
                        // 无 segments 的兜底纯文本
                        SegmentContent(
                            segments = null,
                            fallback = message.content,
                            isSelf = false,
                            baseUrl = baseUrl,
                            freeCopy = freeCopy,
                            onMediaLongClick = { if (interactionsEnabled) menuOpen = true },
                            onTextClick = { if (freeCopy) freeCopy = false }
                        )
                    } else if (thinkingText.isBlank()) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // ---------- 对方 IM 卡片消息：回落气泡 ----------
                ChatBubble(
                    isSelf = false,
                    highlightColor = highlightColor,
                    uploading = false,
                    onClick = { if (freeCopy) freeCopy = false },
                    onLongClick = { if (!freeCopy) menuOpen = true }
                ) {
                    SegmentContent(
                        segments = contentSegs.ifEmpty { null },
                        fallback = message.content,
                        isSelf = false,
                        baseUrl = baseUrl,
                        freeCopy = freeCopy,
                        onMediaLongClick = { menuOpen = true },
                        onTextClick = { if (freeCopy) freeCopy = false }
                    )
                }
            }

            // 用户消息下方快捷操作行（RikkaHub 式）：复制/编辑(仅本地)/重生成(仅本地)/版本切换器
            if (message.isSelf && !message.recalled) {
                SelfMessageActionBar(
                    isLocal = isLocal,
                    hasText = hasText,
                    canRegenerate = canRegenerate,
                    versionCount = versionCount,
                    versionIndex = versionIndex,
                    onSelectVersion = onSelectVersion,
                    timestamp = message.timestamp,
                    onCopy = { clipboard.setText(AnnotatedString(copyableText)) },
                    onEdit = onEdit,
                    onRegenerate = onRegenerate
                )
            }

            // AI 消息下方快捷操作行：复制常驻；重生成/换模型仅本地智能体
            if (!message.isSelf && !message.recalled && interactionsEnabled) {
                MessageActionBar(
                    hasText = hasText,
                    canRegenerate = canRegenerate,
                    versionCount = versionCount,
                    versionIndex = versionIndex,
                    onSelectVersion = onSelectVersion,
                    timestamp = message.timestamp,
                    onCopy = { clipboard.setText(AnnotatedString(copyableText)) },
                    onRegenerate = onRegenerate,
                    onRegenerateWithModel = onRegenerateWithModel
                )
            }

            // ---------- 长按操作菜单（能力显隐，与引擎解耦） ----------
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                if (hasText) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(copyableText))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("自由复制") },
                        onClick = {
                            menuOpen = false
                            freeCopy = true
                        }
                    )
                }
                if (canReply) {
                    DropdownMenuItem(
                        text = { Text("回复") },
                        onClick = {
                            menuOpen = false
                            onReply()
                        }
                    )
                }
                if (isLocal && message.isSelf && hasText) {
                    DropdownMenuItem(
                        text = { Text("编辑重发") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                }
                if (canRegenerate) {
                    DropdownMenuItem(
                        text = { Text("重新生成") },
                        onClick = {
                            menuOpen = false
                            onRegenerate()
                        }
                    )
                    if (!message.isSelf) {
                        DropdownMenuItem(
                            text = { Text("多模型重答") },
                            onClick = {
                                menuOpen = false
                                onRegenerateWithModel()
                            }
                        )
                    }
                }
                // 撤回：云端乐观消息（local- 前缀)服务端尚不存在，不能撤回
                if (canRecall && message.isSelf &&
                    (isLocal || !message.id.startsWith(LOCAL_PREFIX))
                ) {
                    DropdownMenuItem(
                        text = { Text("撤回") },
                        onClick = {
                            menuOpen = false
                            onRecall()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }

        if (message.isSelf) {
            Spacer(Modifier.width(8.dp))
            SelfAvatar(size = if (isLocal) 32.dp else 36.dp)
        }
    }
}

/** 高亮底色：文档流/裸媒体消息定位时的柔和提示（气泡用边框，这里用底色） */
private fun Modifier.highlightBackground(color: Color): Modifier =
    if (color == Color.Transparent) {
        this
    } else {
        this
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
    }

/**
 * 统一消息气泡：半透明底 + 柔和阴影 + 20/6dp 不对称圆角。
 * 媒体上传中叠加半透明遮罩 + 居中进度（仅用户消息）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    isSelf: Boolean,
    highlightColor: Color,
    uploading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val bubbleShape = RoundedCornerShape(
        topStart = if (isSelf) 20.dp else 6.dp,
        topEnd = if (isSelf) 6.dp else 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 20.dp
    )
    val bubbleBg = if (isSelf) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.90f else 0.94f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.82f else 0.90f)
    }
    Box {
        Box(
            modifier = Modifier
                .widthIn(max = LocalConfiguration.current.screenWidthDp.dp * MAX_BUBBLE_WIDTH_RATIO)
                .shadow(
                    elevation = 10.dp,
                    shape = bubbleShape,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.12f),
                    spotColor = if (isSelf) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    else Color.Black.copy(alpha = if (dark) 0.30f else 0.12f)
                )
                .clip(bubbleShape)
                .background(bubbleBg)
                .then(
                    if (highlightColor != Color.Transparent) Modifier.border(2.dp, highlightColor, bubbleShape)
                    else if (!isSelf) Modifier.border(
                        0.6.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        bubbleShape
                    ) else Modifier
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            content()
        }

        if (uploading) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(bubbleShape)
                    .background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White
                )
            }
        }
    }
}

/** 撤回提示（双方统一样式） */
@Composable
private fun RecalledChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "消息已撤回",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/** 用户头像：品牌色圆形，显示"我" */
@Composable
private fun SelfAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "我",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** 云端发送者头像：浅紫底圆形，显示首字符 */
@Composable
private fun SenderAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).ifBlank { "L" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 智能体头像：emoji 圆形（无 emoji 时回退机器人图标），顶栏/空状态/消息列表共用 */
@Composable
internal fun AgentAvatar(
    emoji: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp? = null
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (emoji.isBlank()) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(iconSize ?: size * 0.5f)
            )
        } else {
            Text(
                text = emoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(2.dp)
            )
        }
    }
}

/** 发送状态枚举：无指示 / 发送中 / 失败 */
private enum class SendStatus { None, Sending, Failed }

/**
 * 消息发送状态指示（QQ/微信式，所有消息类型统一）：
 * - Sending：旋转进度环（local- 乐观消息等待服务端确认期间；
 *   媒体上传阶段由气泡上的遮罩进度承载）
 * - Failed：红色感叹号，弹性缩放入场 + 水平轻抖一次
 * - None：占位消失
 */
@Composable
private fun SendStatusIndicator(message: MessageItem) {
    val status = when {
        message.uploadFailed -> SendStatus.Failed
        message.uploading -> SendStatus.None
        message.id.startsWith(LOCAL_PREFIX) -> SendStatus.Sending
        else -> SendStatus.None
    }
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(160)))
                .togetherWith(fadeOut(tween(160)) + scaleOut(tween(160)))
        },
        label = "sendStatus"
    ) { st ->
        when (st) {
            SendStatus.Sending -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(14.dp),
                strokeWidth = 1.6.dp,
                color = MaterialTheme.colorScheme.outline
            )
            SendStatus.Failed -> FailedStatusIcon()
            SendStatus.None -> Spacer(Modifier.size(0.dp))
        }
    }
}

/** 发送失败图标：弹性缩放入场，随后水平轻抖一次 */
@Composable
private fun FailedStatusIcon() {
    val scale = remember { Animatable(0f) }
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        shakeX.animateTo(
            0f,
            keyframes {
                durationMillis = 320
                0f at 0
                -3f at 60
                3f at 130
                -2f at 200
                2f at 260
                0f at 320
            }
        )
    }
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .offset(x = shakeX.value.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = "发送失败",
            tint = Color.White,
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * 用户消息下方快捷操作行（RikkaHub 式，右对齐）：
 * 时间戳 / 复制 / 编辑重发（仅本地）/ 重新生成（仅本地）/ ‹ n/m › 版本切换。
 */
@Composable
private fun SelfMessageActionBar(
    isLocal: Boolean,
    hasText: Boolean,
    canRegenerate: Boolean,
    versionCount: Int,
    versionIndex: Int,
    onSelectVersion: ((Int) -> Unit)?,
    timestamp: Long,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit
) {
    Row(
        modifier = Modifier.padding(top = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val time = formatMessageTime(timestamp)
        if (time.isNotBlank()) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        if (hasText) ActionBarIcon(Icons.Filled.ContentCopy, "复制", onCopy)
        if (isLocal && hasText) ActionBarIcon(Icons.Filled.Edit, "编辑重发", onEdit)
        if (canRegenerate) ActionBarIcon(Icons.Filled.Refresh, "重新生成", onRegenerate)
        if (versionCount > 1 && onSelectVersion != null) {
            VersionSwitcher(
                versionCount = versionCount,
                versionIndex = versionIndex,
                onSelectVersion = onSelectVersion
            )
        }
    }
}

/**
 * AI 消息下方快捷操作行（RikkaHub 式）：
 * 复制 / 重新生成 / 多模型重答 / ‹ n/m › 版本切换 / 行尾时间戳。
 * 弱化小图标，不抢正文视觉。
 */
@Composable
private fun MessageActionBar(
    hasText: Boolean,
    canRegenerate: Boolean,
    versionCount: Int,
    versionIndex: Int,
    onSelectVersion: ((Int) -> Unit)?,
    timestamp: Long,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateWithModel: () -> Unit
) {
    if (!hasText && !canRegenerate && versionCount <= 1) return
    Row(
        modifier = Modifier.padding(top = 2.dp, start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasText) ActionBarIcon(Icons.Filled.ContentCopy, "复制", onCopy)
        if (canRegenerate) {
            ActionBarIcon(Icons.Filled.Refresh, "重新生成", onRegenerate)
            ActionBarIcon(Icons.Filled.SwapHoriz, "多模型重答", onRegenerateWithModel)
        }
        if (versionCount > 1 && onSelectVersion != null) {
            VersionSwitcher(
                versionCount = versionCount,
                versionIndex = versionIndex,
                onSelectVersion = onSelectVersion
            )
        }
        Spacer(Modifier.weight(1f))
        // 行尾时间戳（RikkaHub 式弱化展示）
        val time = formatMessageTime(timestamp)
        if (time.isNotBlank()) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}

/**
 * 版本切换器（RikkaHub ChatMessageBranch 式）：‹ 当前/总数 ›。
 * 端点（已是首/末版本）箭头半透明不可用。
 */
@Composable
private fun VersionSwitcher(
    versionCount: Int,
    versionIndex: Int,
    onSelectVersion: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 现代化分段胶囊：弱化底座 + ‹ n/m ›，端点箭头降透明表示不可用
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val canPrev = versionIndex > 0
        val canNext = versionIndex < versionCount - 1
        Icon(
            imageVector = Icons.Filled.KeyboardArrowLeft,
            contentDescription = "上一版本",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canPrev) 0.9f else 0.35f),
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(enabled = canPrev) { onSelectVersion(versionIndex - 1) }
                .padding(3.dp)
        )
        Text(
            text = "${versionIndex + 1}/$versionCount",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 1.dp)
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = "下一版本",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canNext) 0.9f else 0.35f),
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(enabled = canNext) { onSelectVersion(versionIndex + 1) }
                .padding(3.dp)
        )
    }
}

@Composable
private fun ActionBarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 思考过程折叠面板（流式时默认展开，结束后自动收起；手动切换后以手动为准） */
@Composable
internal fun ThinkingPanel(thinking: String, streaming: Boolean) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: streaming
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "thinkChevron"
    )
    // 流式思考中：图标呼吸脉冲，暗示正在生成
    val pulse by if (streaming) {
        rememberInfiniteTransition(label = "thinkPulse").animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "thinkPulseAlpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            0.6.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { userExpanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = pulse),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "思考中…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (streaming) FontWeight.Medium else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevron)
                )
            }
            // 收起态显示最后一行思考预览（RikkaHub 式，流式思考中不显示避免闪烁）
            if (!expanded && !streaming) {
                val lastLine = thinking.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                if (lastLine.isNotBlank()) {
                    Text(
                        text = lastLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(tween(220)) +
                    androidx.compose.animation.fadeIn(tween(220)),
                exit = androidx.compose.animation.shrinkVertically(tween(180)) +
                    androidx.compose.animation.fadeOut(tween(180))
            ) {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ============ 时间 / 文件工具 ============

/** 消息时间：间隔超过 5 分钟才显示（由调用方判断），格式 HH:mm */
// SimpleDateFormat 非线程安全，用 ThreadLocal 复用而不是每次调用新建
private val messageTimeFormat = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val millis = when {
        timestamp < 10_000_000_000L -> timestamp * 1000L          // 秒
        timestamp < 10_000_000_000_000L -> timestamp              // 毫秒
        timestamp < 10_000_000_000_000_000L -> timestamp / 1000L  // 微秒
        else -> timestamp
    }
    return messageTimeFormat.get()!!.format(Date(millis))
}

/** 两条消息是否应显示时间分隔（间隔 > 5 分钟） */
fun shouldShowTime(prevTs: Long, curTs: Long): Boolean {
    if (prevTs <= 0L) return true
    fun toMicro(ts: Long): Long = when {
        ts < 10_000_000_000L -> ts * 1_000_000
        ts < 10_000_000_000_000L -> ts * 1000
        else -> ts
    }
    return toMicro(curTs) - toMicro(prevTs) > 5 * 60 * 1_000_000L
}

/** 把 content:// Uri 复制到缓存目录，返回临时 File（保留原始文件名，供上传使用） */
fun uriToCacheFile(context: Context, uri: Uri): File? {
    return runCatching {
        var displayName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) displayName = cursor.getString(idx)
        }
        if (displayName.isNullOrBlank()) {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val ext = when {
                mimeType.startsWith("image/") -> ".jpg"
                mimeType.startsWith("video/") -> ".mp4"
                mimeType.startsWith("audio/") -> ".m4a"
                else -> ""
            }
            displayName = "upload_${System.currentTimeMillis()}$ext"
        }
        // 净化文件名：去掉路径分隔符并限长，防止逃逸缓存目录或超长文件名
        val safeName = displayName!!.replace(File.separatorChar, '_')
            .replace("/", "_").take(120)
        val temp = File(context.cacheDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        temp
    }.getOrNull()
}

private const val MAX_BUBBLE_WIDTH_RATIO = 0.62f
