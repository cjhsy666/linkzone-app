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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.UserProfile

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

    // 智能体分组（按智能体清单顺序；智能体已删除的会话归入「其他会话」组）
    val agentGroups = remember(filtered, agents) {
        val byId = filtered.groupBy { agentIdOf(it) }
        val known = agents.mapTo(mutableSetOf()) { it.id }
        val named = agents.mapNotNull { a ->
            byId[a.id]?.let { AgentGroup(a.id, a.name.ifBlank { "智能体" }, it) }
        }
        val orphans = byId.filterKeys { it !in known }
            .map { AgentGroup(it.key, "其他会话", it.value) }
        named + orphans
    }

    // 会话行渲染：搜索拍平与智能体分组两种模式共用；indent=true 时左缩进体现层级
    val convRow: @Composable (ConvItem, Boolean) -> Unit = { conv, indent ->
        Box(Modifier.padding(start = if (indent) 8.dp else 0.dp)) {
            ConversationDrawerItem(
                conv = conv,
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
    ) {
        // ---------- 顶部：会话搜索（新建对话入口已移至聊天页顶栏） ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(15.dp)
            )
            androidx.compose.material3.TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
                placeholder = { Text("搜索会话", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }

        // ---------- 固定置顶：唯一云端主会话（搜索不影响它，始终可见） ----------
        val mainItem = mainConv ?: ConvItem(id = "__main_placeholder__", name = "LinkZone 对话")
        ConversationDrawerItem(
            conv = mainItem,
            selected = mainConv != null && mainConv.id == currentConvId,
            onClick = {
                if (mainConv != null) onSwitchConversation(mainConv.id) else onOpenMainConversation()
            },
            // 云端主会话是固定入口：无长按菜单（不可删/不可置顶）
            onLongClick = {}
        )

        SectionLabel(if (searchQuery.isBlank()) "本地会话" else "搜索结果")

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
                    // 搜索结果拍平展示（置顶优先）
                    if (pinned.isNotEmpty()) {
                        items(pinned, key = { "p-" + it.id }) { conv -> convRow(conv, false) }
                    }
                    if (normal.isNotEmpty()) {
                        if (pinned.isNotEmpty()) {
                            item(key = "divider") {
                                Text(
                                    text = "全部会话",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                                )
                            }
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
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除会话") },
                text = { Text("确定删除「${conv.name.ifBlank { "新对话" }}」吗？会话内的消息将一并删除。") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        onDeleteConversation(conv.id)
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text("取消")
                    }
                }
            )
        }

        // 重命名会话对话框（默认标题 = 首条用户消息，可随时改）
        renamingConv?.let { conv ->
            var nameInput by remember(conv.id) { mutableStateOf(conv.name) }
            AlertDialog(
                onDismissRequest = { renamingConv = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(RENAME_MAX_LEN) },
                        singleLine = true,
                        placeholder = { Text("会话标题") }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = nameInput.isNotBlank(),
                        onClick = {
                            renamingConv = null
                            onRenameConversation(conv.id, nameInput)
                        }
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renamingConv = null }) { Text("取消") }
                }
            )
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

/** 分区小标题 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
    )
}

/** 智能体分组头：主色机器人图标 + 名称 + 会话数 + 展开/收起箭头（无底色，轻量紧凑） */
@Composable
private fun AgentGroupHeader(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(5.dp))
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
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
    onDelete: () -> Unit = {}
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
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conv.name.ifBlank { "新对话" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
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

/** 底部渐变资料卡（整卡可点击进入「我的」；右上角设置图标进入设置） */
@Composable
private fun DrawerHeader(
    profile: UserProfile?,
    connected: Boolean,
    onClick: () -> Unit,
    onSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 渐变背景置于 navigationBarsPadding 外层，让紫色铺满到屏幕底（避免导航区露出白底）
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.03f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    )
                )
            )
            .clickable(onClick = onClick)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "我的",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile?.username?.ifBlank { profile.linkzoneId } ?: "灵臻用户",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 连接状态指示
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (connected) Color(0xFF34C759)
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (connected) "已连接" else "连接中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (profile != null) {
                        val id = profile.linkzoneId
                        Text(
                            text = " · ID ${id.take(10)}${if (id.length > 10) "…" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // 右上角设置图标（独立可点，不冒泡到整卡点击）
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
