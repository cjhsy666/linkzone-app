package com.cjhsy.linkzone.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.UserProfile
import com.cjhsy.linkzone.ui.components.LzConfirmDialog

/** 本地智能体会话 bot_id 前缀（与 ChatViewModel 保持一致） */
private const val LOCAL_BOT_PREFIX = "bot-local-"

/**
 * 左侧抽屉菜单（豆包式）：
 * - 顶部会话搜索框 + 唯一云端主会话（固定置顶，不可删/不可置顶）+ 本地会话历史列表
 * - 本地会话按智能体可折叠分组（组头：图标+名称+数量+箭头），长按会话可重命名/置顶/删除
 * - 底部：设置 + 渐变资料卡（点击进入「我的」）
 * - 新建对话入口在聊天页顶栏右上角（仅本地智能体；云端唯一主会话不可新建）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(
    profile: UserProfile?,
    /** 唯一云端主会话（固定置顶项；null 时显示占位，点击触发重建） */
    mainConv: ConvItem?,
    conversations: List<ConvItem>,
    /** 本地智能体清单（二级 tab 分组用，按 id → 名称映射） */
    agents: List<LlmAgent> = emptyList(),
    currentConvId: String,
    connected: Boolean,
    onOpenMainConversation: () -> Unit,
    onSwitchConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit = { _, _ -> },
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onClearMainConversation: () -> Unit = {},
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 待确认删除的会话（非空时弹确认框）
    var pendingDelete by remember { mutableStateOf<ConvItem?>(null) }
    // 长按菜单打开的会话（重命名/置顶/删除）
    var menuConv by remember { mutableStateOf<ConvItem?>(null) }
    // 重命名对话框目标（非空时弹输入框）
    var renamingConv by remember { mutableStateOf<ConvItem?>(null) }
    // 会话搜索关键词（按会话名过滤）
    var searchQuery by remember { mutableStateOf("") }
    // 收起的智能体分组（默认全部展开）
    val collapsedAgents = remember { mutableStateListOf<String>() }

    /** conv.target_id（bot-local-{agentId}）→ agentId */
    fun agentIdOf(conv: ConvItem): String =
        conv.target_id.removePrefix(LOCAL_BOT_PREFIX).substringBefore(':')

    // 仅在搜索词或会话列表变化时重新过滤，避免每次重组都重算
    val filtered = remember(searchQuery, conversations) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val pinned = remember(filtered) { filtered.filter { it.pinned } }
    val normal = remember(filtered) { filtered.filter { !it.pinned } }

    // 智能体分组（按智能体清单顺序；组内置顶优先；智能体已删除的会话归入「其他会话」组）
    val agentGroups = remember(filtered, agents) {
        val byId = filtered.groupBy { agentIdOf(it) }
        val known = agents.mapTo(mutableSetOf()) { it.id }
        val named = agents.mapNotNull { a ->
            byId[a.id]?.sortedByDescending { it.pinned }?.let {
                AgentGroup(a.id, a.name.ifBlank { "智能体" }, it)
            }
        }
        val orphans = byId.filterKeys { it !in known }
            .map { AgentGroup(it.key, "其他会话", it.value.sortedByDescending { c -> c.pinned }) }
        named + orphans
    }

    // 会话行渲染：搜索拍平与智能体分组两种模式共用；indent=true 时缩进对齐分组标题文本
    val convRow: @Composable (ConvItem, Boolean) -> Unit = { conv, indent ->
        Box(Modifier.padding(start = if (indent) 34.dp else 0.dp)) {
            ConversationDrawerItem(
                conv = conv,
                leadingDot = indent,
                selected = conv.id == currentConvId,
                onClick = { onSwitchConversation(conv.id) },
                onLongClick = { menuConv = conv },
                menuOpen = menuConv?.id == conv.id,
                onDismissMenu = { menuConv = null },
                onRename = {
                    menuConv = null
                    renamingConv = conv
                },
                onPin = {
                    menuConv = null
                    onPinConversation(conv.id, !conv.pinned)
                },
                onDelete = {
                    menuConv = null
                    pendingDelete = conv
                }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // ---------- 顶部：轻量搜索框 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = "搜索会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ---------- LinkZone 对话：点击进入云端主会话，长按仅「清空聊天记录」（不可删除） ----------
        var showMainMenu by remember { mutableStateOf(false) }
        var pendingClearMain by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 12.dp)) {
            LinkZoneMainCard(
                selected = mainConv != null && mainConv.id == currentConvId,
                onClick = {
                    if (mainConv != null) onSwitchConversation(mainConv.id) else onOpenMainConversation()
                },
                onLongClick = { showMainMenu = true }
            )
            DropdownMenu(
                expanded = showMainMenu,
                onDismissRequest = { showMainMenu = false },
                shape = RoundedCornerShape(16.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("清空聊天记录", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    onClick = {
                        showMainMenu = false
                        pendingClearMain = true
                    }
                )
            }
        }

        // 清空云端主会话确认框
        if (pendingClearMain) {
            LzConfirmDialog(
                title = "清空聊天记录",
                message = "确定清空「LinkZone 对话」的全部聊天记录吗？此操作不可恢复，但对话本身会保留。",
                confirmText = "清空",
                danger = true,
                onConfirm = {
                    pendingClearMain = false
                    onClearMainConversation()
                },
                onDismiss = { pendingClearMain = false }
            )
        }

        SectionLabel(if (searchQuery.isBlank()) "本地智能体" else "搜索结果")

        // ---------- 会话区（搜索时拍平；否则按智能体分组折叠） ----------
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "暂无本地会话" else "没有匹配的会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (searchQuery.isNotBlank()) {
                    // 搜索结果拍平展示（置顶优先，图钉标记区分）
                    if (pinned.isNotEmpty()) {
                        items(pinned, key = { "p-" + it.id }) { conv -> convRow(conv, false) }
                    }
                    if (normal.isNotEmpty()) {
                        if (pinned.isNotEmpty()) {
                            item(key = "divider") { Spacer(Modifier.height(6.dp)) }
                        }
                        items(normal, key = { it.id }) { conv -> convRow(conv, false) }
                    }
                } else {
                    // 智能体分组：组头可点击折叠/展开，组内会话缩进展示
                    agentGroups.forEach { group ->
                        val expanded = group.agentId !in collapsedAgents
                        item(key = "g-" + group.agentId) {
                            AgentGroupHeader(
                                name = group.name,
                                count = group.convs.size,
                                expanded = expanded,
                                onClick = {
                                    if (expanded) collapsedAgents.add(group.agentId)
                                    else collapsedAgents.remove(group.agentId)
                                }
                            )
                        }
                        if (expanded) {
                            items(group.convs, key = { it.id }) { conv -> convRow(conv, true) }
                        }
                    }
                }
            }
        }

        // 删除会话确认框
        pendingDelete?.let { conv ->
            LzConfirmDialog(
                title = "删除会话",
                message = "确定删除「${conv.name.ifBlank { "新对话" }}」吗？会话内的消息将一并删除。",
                confirmText = "删除",
                danger = true,
                onConfirm = {
                    pendingDelete = null
                    onDeleteConversation(conv.id)
                },
                onDismiss = { pendingDelete = null }
            )
        }

        // 重命名会话对话框（默认标题 = 首条用户消息，可随时改）
        renamingConv?.let { conv ->
            var nameInput by remember(conv.id) { mutableStateOf(conv.name) }
            Dialog(onDismissRequest = { renamingConv = null }) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                        Text(
                            text = "重命名会话",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it.take(RENAME_MAX_LEN) },
                            singleLine = true,
                            placeholder = { Text("会话标题") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { renamingConv = null }) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    renamingConv = null
                                    onRenameConversation(conv.id, nameInput)
                                },
                                enabled = nameInput.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                Text("保存", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // ---------- 底部资料卡（点头像/昵称进入「我的」，右上角设置图标进入设置） ----------
        DrawerHeader(
            profile = profile,
            connected = connected,
            onClick = { onNavigate("profile") },
            onSettings = { onNavigate("settings") }
        )
    }
}

/** 分区小标题：极小、淡色 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp)
    )
}

/**
 * 「LinkZone 对话」主会话卡片：40dp 主色图标盒 + 主标题 + 「云端助手」副标题。
 * 点击进入云端主会话；长按弹「清空聊天记录」菜单（不可删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkZoneMainCard(selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "LinkZone 对话",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "云端助手",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }
    }
}

/**
 * 智能体分组头：36dp 圆角图标盒 + 名称/会话数两行 + 右侧收展箭头。
 * 无底色轻量行式，可点击折叠/展开组内会话。
 */
@Composable
private fun AgentGroupHeader(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "$count 个会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 抽屉本地会话的智能体分组 */
private data class AgentGroup(
    val agentId: String,
    val name: String,
    val convs: List<ConvItem>
)

/** 手动重命名标题上限（与 ChatViewModel.RENAME_MAX_LEN 一致） */
private const val RENAME_MAX_LEN = 50

/** 抽屉中的历史会话项（点击切换，长按重命名/置顶/删除菜单） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawerItem(
    conv: ConvItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuOpen: Boolean = false,
    onDismissMenu: () -> Unit = {},
    onRename: () -> Unit = {},
    onPin: () -> Unit = {},
    onDelete: () -> Unit = {},
    /** 组内会话：标题前显示小圆点，表达文件树层级 */
    leadingDot: Boolean = false
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(container)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingDot) {
                Box(
                    modifier = Modifier
                        .padding(end = 9.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                )
            }
            Text(
                text = conv.name.ifBlank { "新对话" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            // 置顶标记
            if (conv.pinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "已置顶",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(13.dp)
                )
            }
            if (conv.unread > 0) {
                Spacer(Modifier.width(6.dp))
                val badge = if (conv.unread > 99) "99+" else conv.unread.toString()
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .widthIn(min = 18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 长按菜单：重命名 + 置顶/取消置顶 + 删除（锚定到会话项）
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            shape = RoundedCornerShape(16.dp)
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                },
                onClick = onRename
            )
            DropdownMenuItem(
                text = { Text(if (conv.pinned) "取消置顶" else "置顶") },
                leadingIcon = {
                    Icon(
                        imageVector = if (conv.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                },
                onClick = onPin
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(17.dp)
                    )
                },
                onClick = onDelete
            )
        }
    }
}

/** 底部资料卡：白底 + 顶部分割线，整卡可点进「我的」，右侧设置入口 */
@Composable
private fun DrawerHeader(
    profile: UserProfile?,
    connected: Boolean,
    onClick: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "我的",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile?.username?.ifBlank { profile.linkzoneId } ?: "灵臻用户",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (connected) Color(0xFF34C759) else MaterialTheme.colorScheme.outline)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (connected) "已连接" else "连接中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    if (profile != null) {
                        val id = profile.linkzoneId
                        Text(
                            text = " · ID ${id.take(10)}${if (id.length > 10) "…" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}
