package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.ai.core.ReasoningLevel
import com.cjhsy.linkzone.ai.provider.ModelRef
import com.cjhsy.linkzone.data.ai.LocalToolOption
import com.cjhsy.linkzone.data.ai.mcp.McpServerConfig
import com.cjhsy.linkzone.data.ai.mcp.McpStatus
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.LlmSkill

// ============================================================
// 本地智能体专属 UI：顶栏模型选择面板 / AI 扩展 chips / 扩展面板
// 统一聊天页通过组件复用这些能力（云端会话不显示）
// ============================================================

/**
 * 关闭内容层的 overscroll（拉伸回弹）。
 *
 * ModalBottomSheet 内嵌 LazyColumn 时，快速滑动到边界继续 fling，列表的回弹
 * 嵌套滚动会与 BottomSheet 自身 anchoredDraggable 互相抢占剩余滑动量，
 * 表现为「抽屉一直跳动/抖动」。在 sheet 内容提供 null OverscrollFactory
 * （Compose 1.8+ 的 LocalOverscrollFactory）即可消除该反馈环，
 * 列表正常滚动与顶部下拉关闭手势不受影响。
 */
@Composable
internal fun BottomSheetNoOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null, content = content)
}

/**
 * 顶栏模型选择底部菜单：
 * - 顶部搜索框（按模型名 / id / 服务商过滤，渠道与模型多时快速定位）
 * - 无搜索词时：「最近使用」分组置顶（与下方分组重复展示，不占位），其余按服务商折叠分组，
 *   点服务商展开/收起；当前选中模型所在的服务商默认展开
 * - 底部附管理智能体入口（切换智能体在 AI 扩展面板中）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelSheet(
    models: List<ChatModelOption>,
    currentModelId: String,
    recentModelIds: List<String>,
    onPick: (String) -> Unit,
    onManageAgents: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val keyword = query.trim().lowercase()

    // 搜索结果：label / modelId / providerName 任一中即命中，保持 provider 分组顺序
    val filtered = remember(keyword, models) {
        if (keyword.isBlank()) models
        else models.filter {
            it.label.lowercase().contains(keyword) ||
                it.modelId.lowercase().contains(keyword) ||
                it.providerName.lowercase().contains(keyword)
        }
    }
    // 最近使用：按 prefs 顺序映射到现有模型，最多 6 个。
    // 条目是「providerId/modelId」复合引用；旧数据只有 modelId，按首个同 id 命中
    val recent = remember(recentModelIds, models, keyword) {
        if (keyword.isNotBlank()) emptyList()
        else recentModelIds.mapNotNull { ref ->
            models.firstOrNull { it.ref == ref }
                ?: ModelRef.decode(ref)
                    ?.let { (pid, id) -> models.firstOrNull { it.providerId == pid && it.modelId == id } }
                ?: models.firstOrNull { it.modelId == ref }
        }.distinctBy { it.ref }.take(6)
    }
    // 服务商折叠状态：null = 全部展开（搜索时强制展开）；非空 = 仅展开集合内的服务商
    val searching = keyword.isNotBlank()
    var expandedProviders by remember { mutableStateOf<Set<String>?>(null) }
    // 分组与折叠都以 providerId 为准（服务商可改名、也可能重名）
    val currentProvider = (models.firstOrNull { it.ref == currentModelId }
        ?: models.firstOrNull { it.modelId == currentModelId })?.providerId
    LaunchedEffect(currentProvider) {
        // 首次打开：默认只展开当前模型所在的服务商
        if (expandedProviders == null && currentProvider != null) {
            expandedProviders = setOf(currentProvider)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 关闭 sheet 拖拽手势：内层 LazyColumn 快速 fling 到边界后与 sheet 的
        // anchoredDraggable 嵌套滚动互相抢 delta，导致抽屉持续跳动；
        // 关闭后经点空白/返回键关闭，手势冲突从根上消除
        sheetGesturesEnabled = false
    ) {
        BottomSheetNoOverscroll {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索模型或服务商", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            if (filtered.isEmpty()) {
                Text(
                    text = if (models.isEmpty()) "暂无可用模型，请先在「AI 中心 → 模型服务」中添加"
                    else "没有匹配的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .padding(top = 6.dp)
                ) {
                    if (recent.isNotEmpty()) {
                        item(key = "recent_header") { GroupHeader("最近使用") }
                        items(recent, key = { "recent_${it.ref}" }) { model ->
                            ModelRow(
                                model = model,
                                selected = model.ref == currentModelId ||
                                    (!currentModelId.contains('/') && model.modelId == currentModelId),
                                onPick = onPick,
                                showProvider = true,
                            )
                        }
                    }
                    // 服务商分组（模型仍完整保留在所属服务商下，与最近使用重复展示）
                    filtered.groupBy { it.providerId }.toList().forEach { (providerId, list) ->
                        if (list.isEmpty()) return@forEach
                        val providerName = list.first().providerName
                        val expandedSet = expandedProviders
                        val expanded = searching || expandedSet == null || providerId in expandedSet
                        item(key = "prov_$providerId") {
                            CollapsibleGroupHeader(
                                title = providerName,
                                count = list.size,
                                expanded = expanded,
                                onToggle = {
                                    val current = expandedProviders ?: emptySet()
                                    expandedProviders = if (expanded) current - providerId
                                    else current + providerId
                                }
                            )
                        }
                        if (expanded) {
                            items(list, key = { "m_${it.ref}" }) { model ->
                                ModelRow(
                                    model = model,
                                    selected = model.ref == currentModelId ||
                                        (!currentModelId.contains('/') && model.modelId == currentModelId),
                                    onPick = onPick,
                                    showProvider = false,
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
            }

            // 底部入口：管理智能体（切换智能体在 AI 扩展面板中）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onManageAgents, modifier = Modifier.weight(1f)) {
                    Text("管理智能体")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        }
    }
}

/** 可折叠服务商分组头：名称 + 模型数 + 展开箭头 */
@Composable
private fun CollapsibleGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
            else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun ModelRow(
    model: ChatModelOption,
    selected: Boolean,
    onPick: (String) -> Unit,
    /** 「最近使用」分组下才在副标题里补服务商名，服务商分组内由组头表达归属 */
    showProvider: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(model.ref) }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (model.modelId != model.label || showProvider) {
                Text(
                    text = if (showProvider) "${model.modelId} · ${model.providerName}"
                    else model.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 能力标签：视觉 / 工具 / 推理
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.supportsVision) CapabilityTag("📷 图片")
                if (model.supportsTools) CapabilityTag("🔧 工具")
                if (model.supportsReasoning) CapabilityTag("🧠 思考")
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "当前模型",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 模型能力小标签——极简圆角 chip */
@Composable
private fun CapabilityTag(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

// ============================================================
// AI 扩展入口（Rikka 式：输入栏上方功能 chips + 扩展面板）
// ============================================================

/**
 * 输入栏上方功能行：联网搜索开关 chip + 推理等级 chip（点击直接选等级）+ AI 扩展入口 chip（带角标）。
 * 模型切换统一由顶栏标题区承担，此处不再放重复入口。
 */
@Composable
internal fun LocalExtensionChips(
    agent: LlmAgent?,
    onToggleSearch: () -> Unit,
    onReasoningChange: (ReasoningLevel) -> Unit,
    onOpenExtensions: () -> Unit,
    extensionsCount: Int = 0
) {
    if (agent == null) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "web_search") {
            LocalToggleChip(
                label = "联网搜索",
                icon = Icons.Filled.Public,
                checked = agent.enableWebSearch,
                onClick = onToggleSearch
            )
        }
        item(key = "reasoning") {
            ReasoningChip(
                level = agent.reasoningLevel,
                onSelect = onReasoningChange
            )
        }
        item(key = "extensions") {
            LocalToggleChip(
                label = "AI 扩展",
                icon = Icons.Filled.Extension,
                checked = false,
                badge = extensionsCount,
                onClick = onOpenExtensions
            )
        }
    }
}

/** 推理等级 chip：点击弹出等级菜单直接切换，不再跳转大面板 */
@Composable
private fun ReasoningChip(
    level: ReasoningLevel,
    onSelect: (ReasoningLevel) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        LocalToggleChip(
            label = "推理·${reasoningLevelLabel(level)}",
            icon = Icons.Filled.Psychology,
            checked = level.isEnabled,
            onClick = { menuOpen = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            ReasoningLevel.entries.forEach { lvl ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            (if (lvl == level) "✓ " else "") + reasoningLevelLabel(lvl)
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(lvl)
                    }
                )
            }
        }
    }
}

/** Rikka ToggleSurface 风格小 chip：选中态用 primary 容器色 */
@Composable
private fun LocalToggleChip(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onClick: () -> Unit,
    badge: Int = 0
) {
    val bg = if (checked) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val fg = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        tonalElevation = if (checked) 2.dp else 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
            if (badge > 0) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                }
            }
        }
    }
}

private fun reasoningLevelLabel(level: ReasoningLevel): String = when (level) {
    ReasoningLevel.OFF -> "关"
    ReasoningLevel.AUTO -> "自动"
    ReasoningLevel.LOW -> "低"
    ReasoningLevel.MEDIUM -> "中"
    ReasoningLevel.HIGH -> "高"
    ReasoningLevel.XHIGH -> "超高"
    ReasoningLevel.MAX -> "最大"
}

/** MCP 状态文案（与 LlmMcpScreen 保持一致） */
private fun mcpStatusLabel(status: McpStatus?): String = when (status) {
    null, McpStatus.Idle -> "未连接"
    McpStatus.Connecting -> "连接中…"
    McpStatus.Connected -> "已连接"
    is McpStatus.Reconnecting -> "重连中（${status.attempt}/${status.maxAttempts}）"
    is McpStatus.Error -> "错误：${status.message.take(40)}"
    McpStatus.NeedsAuthorization -> "需要 OAuth 授权"
    McpStatus.Authorizing -> "授权中…"
}

/**
 * AI 扩展面板（会话级配置，Rikka 式 ModalBottomSheet）：
 * 模型与生成参数 / 系统提示词 / 上下文策略 / 代码工作区 / 搜索·记忆·OCR·图片生成 /
 * MCP / 技能 / 提示词·世界书·快捷指令关联 / 本地工具。
 *
 * 所有改动经 onUpdate 写入**会话覆盖**，只对当前会话生效，不影响原智能体；
 * 顶部提供「恢复默认」一键清除覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalExtensionSheet(
    agent: LlmAgent?,
    skills: List<LlmSkill>,
    prompts: List<com.cjhsy.linkzone.data.model.LlmPrompt>,
    lorebooks: List<com.cjhsy.linkzone.data.model.LlmLorebook>,
    quickMessages: List<com.cjhsy.linkzone.data.model.LlmQuickMessage>,
    mcpServers: List<McpServerConfig>,
    mcpStatus: Map<String, McpStatus>,
    agentOverridden: Boolean,
    onResetOverride: () -> Unit,
    onPickModel: () -> Unit,
    onSwitchAgent: () -> Unit,
    onBindWorkspace: (String?) -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onOpenWorkspaceManager: () -> Unit,
    onCompressNow: () -> Unit,
    onUpdate: ((LlmAgent) -> LlmAgent) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val workspaceManager = org.koin.compose.koinInject<com.cjhsy.linkzone.workspace.WorkspaceManager>()
    var wsTick by remember { mutableStateOf(0) }
    // 工作区列表是磁盘 IO（列目录 + 逐个解析 meta JSON），放到 IO 线程加载，
    // 避免打开 AI 扩展面板时在主线程读盘造成卡顿
    var workspaces by remember {
        mutableStateOf(listOf<com.cjhsy.linkzone.workspace.WorkspaceMeta>())
    }
    LaunchedEffect(wsTick, agent?.workspaceId) {
        workspaces = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            workspaceManager.list()
        }
    }
    var newWsName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 关闭 sheet 拖拽手势：内层 LazyColumn fling 过界后与 sheet 拖拽互相抢占，
        // 滑到底再上滑时抽屉持续跳动；关闭后经点空白/返回键关闭，彻底消除
        sheetGesturesEnabled = false
    ) {
        BottomSheetNoOverscroll {
        if (agent == null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前会话未关联智能体",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
                Spacer(Modifier.height(20.dp))
            }
        } else Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            // 标题行 + 临时覆盖提示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI 扩展 · 仅本会话",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "改动只作用于当前对话，不影响原智能体",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onSwitchAgent) { Text("切换智能体") }
                if (agentOverridden) {
                    TextButton(onClick = onResetOverride) { Text("恢复默认") }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // ============ 模型与生成 ============
                item { LocalSheetSectionTitle("模型与生成") }
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onPickModel)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    agent.chatModelId
                                        ?.let { ModelRef.decode(it)?.second ?: it }
                                        ?.ifBlank { null } ?: "默认模型",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "点击切换模型（可搜索）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("切换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                // 推理等级
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(ReasoningLevel.entries.size) { idx ->
                            val level = ReasoningLevel.entries[idx]
                            val selected = agent.reasoningLevel == level
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onUpdate { it.copy(reasoningLevel = level) } }
                            ) {
                                Text(
                                    text = reasoningLevelLabel(level),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                // 温度 / TopP / 最大 Token
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SessionFloatField(
                            label = "温度",
                            value = agent.temperature,
                            placeholder = "默认",
                            modifier = Modifier.weight(1f),
                            onChange = { v -> onUpdate { it.copy(temperature = v) } }
                        )
                        SessionFloatField(
                            label = "Top P",
                            value = agent.topP,
                            placeholder = "默认",
                            modifier = Modifier.weight(1f),
                            onChange = { v -> onUpdate { it.copy(topP = v) } }
                        )
                        SessionIntField(
                            label = "最大 Token",
                            value = agent.maxTokens,
                            placeholder = "默认",
                            modifier = Modifier.weight(1f),
                            onChange = { v -> onUpdate { it.copy(maxTokens = v) } }
                        )
                    }
                }
                item {
                    LocalSheetSwitchRow(
                        title = "流式输出",
                        subtitle = "逐字显示回复",
                        checked = agent.streamOutput,
                        onToggle = { onUpdate { it.copy(streamOutput = !it.streamOutput) } }
                    )
                }

                // ============ 系统提示词 ============
                item { SystemPromptEditor(agent.systemPrompt) { text -> onUpdate { it.copy(systemPrompt = text) } } }

                // ============ 上下文 ============
                item { LocalSheetSectionTitle("上下文策略") }
                item {
                    LocalSheetSwitchRow(
                        title = "自动压缩上下文",
                        subtitle = "超出上限时自动把旧消息总结为检查点",
                        checked = agent.enableContextCompression,
                        onToggle = { onUpdate { it.copy(enableContextCompression = !it.enableContextCompression) } }
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "上下文上限（条）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        SessionIntField(
                            label = "",
                            value = agent.contextMessageLimit.takeIf { it > 0 },
                            placeholder = "不限",
                            modifier = Modifier.width(110.dp),
                            onChange = { v -> onUpdate { it.copy(contextMessageLimit = v ?: 0) } }
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("立即压缩上下文", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "手动总结较早消息为检查点，长对话立即瘦身",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onCompressNow) { Text("执行") }
                    }
                }
                item {
                    LocalSheetSwitchRow(
                        title = "时间感知",
                        subtitle = "长间隔消息前注入当前时间提醒",
                        checked = agent.enableTimeReminder,
                        onToggle = { onUpdate { it.copy(enableTimeReminder = !it.enableTimeReminder) } }
                    )
                }
                if (agent.enableTimeReminder) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("触发间隔（分钟）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            SessionIntField(
                                label = "",
                                value = agent.timeReminderIntervalMinutes.takeIf { it > 0 },
                                placeholder = "60",
                                modifier = Modifier.width(110.dp),
                                onChange = { v -> onUpdate { it.copy(timeReminderIntervalMinutes = v ?: 60) } }
                            )
                        }
                    }
                }

                // ============ 代码工作区 ============
                item { LocalSheetSectionTitle("代码工作区") }
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        // 未绑定
                        WorkspaceRadioRow(
                            title = "未绑定",
                            subtitle = "不启用文件与命令能力",
                            selected = agent.workspaceId == null,
                            onSelect = { onBindWorkspace(null) }
                        )
                        workspaces.forEach { ws ->
                            val root = workspaceManager.get(ws.id)?.root?.absolutePath
                            WorkspaceRadioRow(
                                title = ws.name,
                                subtitle = root ?: "",
                                selected = agent.workspaceId == ws.id,
                                onSelect = { onBindWorkspace(ws.id) }
                            )
                        }
                        // 新建并立即选中
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newWsName,
                                onValueChange = { newWsName = it },
                                placeholder = { Text("新建工作区名称", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                enabled = newWsName.isNotBlank(),
                                onClick = {
                                    onCreateWorkspace(newWsName.trim())
                                    newWsName = ""
                                    wsTick++
                                }
                            ) { Text("新建并绑定") }
                        }
                        Text(
                            "工作区位于应用私有目录，可在完整管理页浏览文件、执行命令；" +
                                "文件可随时导出到 Download 等任意目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onOpenWorkspaceManager) {
                            Text("打开完整管理页（文件浏览 / 终端）↗")
                        }
                    }
                }

                // ============ 能力开关 ============
                item { LocalSheetSectionTitle("能力开关") }
                item {
                    LocalSheetSwitchRow(
                        title = "联网搜索",
                        subtitle = "注入 search_web 工具",
                        checked = agent.enableWebSearch,
                        onToggle = { onUpdate { it.copy(enableWebSearch = !it.enableWebSearch) } }
                    )
                }
                item {
                    LocalSheetSwitchRow(
                        title = "启用记忆",
                        subtitle = "注入已有记忆并允许模型增删改",
                        checked = agent.enableMemory,
                        onToggle = { onUpdate { it.copy(enableMemory = !it.enableMemory) } }
                    )
                }
                if (agent.enableMemory) {
                    item {
                        LocalSheetSwitchRow(
                            title = "使用全局共享记忆",
                            subtitle = "关闭时使用本智能体隔离记忆",
                            checked = agent.useGlobalMemory,
                            onToggle = { onUpdate { it.copy(useGlobalMemory = !it.useGlobalMemory) } }
                        )
                    }
                }
                item {
                    LocalSheetSwitchRow(
                        title = "图片 OCR 兜底",
                        subtitle = "非视觉模型处理图片时先识别为文字",
                        checked = agent.enableOcr,
                        onToggle = { onUpdate { it.copy(enableOcr = !it.enableOcr) } }
                    )
                }
                item {
                    LocalSheetSwitchRow(
                        title = "图片生成",
                        subtitle = "允许模型调用生图模型",
                        checked = agent.enableImageGeneration,
                        onToggle = { onUpdate { it.copy(enableImageGeneration = !it.enableImageGeneration) } }
                    )
                }

                // ============ MCP ============
                item { LocalSheetSectionTitle("MCP 服务器") }
                if (mcpServers.isEmpty()) {
                    item { LocalSheetEmptyHint("暂无 MCP 服务器，请在「AI 中心 → MCP」中添加") }
                } else {
                    items(mcpServers, key = { "mcp_${it.id}" }) { server ->
                        val enabled = server.id in agent.mcpServers
                        LocalSheetSwitchRow(
                            title = server.commonOptions.name.ifBlank { "未命名服务器" },
                            subtitle = mcpStatusLabel(mcpStatus[server.id]) +
                                if (server.commonOptions.enable) "" else " · 已禁用",
                            checked = enabled,
                            onToggle = {
                                onUpdate { a ->
                                    a.copy(mcpServers = if (enabled) a.mcpServers - server.id else a.mcpServers + server.id)
                                }
                            }
                        )
                    }
                }

                // ============ 技能 ============
                item { LocalSheetSectionTitle("技能") }
                if (skills.isEmpty()) {
                    item { LocalSheetEmptyHint("暂无技能，请在「AI 中心 → AI 扩展」中添加") }
                } else {
                    items(skills, key = { "skill_${it.id}" }) { skill ->
                        val enabled = skill.id in agent.enabledSkills
                        LocalSheetSwitchRow(
                            title = skill.name.ifBlank { "未命名技能" },
                            subtitle = skill.description.take(40),
                            checked = enabled,
                            onToggle = {
                                onUpdate { a ->
                                    a.copy(enabledSkills = if (enabled) a.enabledSkills - skill.id else a.enabledSkills + skill.id)
                                }
                            }
                        )
                    }
                }

                // ============ 提示词 / 世界书 / 快捷指令 ============
                item { LocalSheetSectionTitle("提示词模式") }
                if (prompts.isEmpty()) {
                    item { LocalSheetEmptyHint("暂无提示词模式") }
                } else {
                    items(prompts, key = { "prompt_${it.id}" }) { p ->
                        val enabled = p.id in agent.promptIds
                        LocalSheetSwitchRow(
                            title = p.name.ifBlank { "未命名提示词" },
                            subtitle = p.content.take(40),
                            checked = enabled,
                            onToggle = {
                                onUpdate { a ->
                                    a.copy(promptIds = if (enabled) a.promptIds - p.id else a.promptIds + p.id)
                                }
                            }
                        )
                    }
                }
                item { LocalSheetSectionTitle("世界书") }
                if (lorebooks.isEmpty()) {
                    item { LocalSheetEmptyHint("暂无世界书") }
                } else {
                    items(lorebooks, key = { "lb_${it.id}" }) { lb ->
                        val enabled = lb.id in agent.lorebookIds
                        LocalSheetSwitchRow(
                            title = lb.name.ifBlank { "未命名世界书" },
                            subtitle = lb.description.take(40),
                            checked = enabled,
                            onToggle = {
                                onUpdate { a ->
                                    a.copy(lorebookIds = if (enabled) a.lorebookIds - lb.id else a.lorebookIds + lb.id)
                                }
                            }
                        )
                    }
                }
                item { LocalSheetSectionTitle("快捷指令") }
                if (quickMessages.isEmpty()) {
                    item { LocalSheetEmptyHint("暂无快捷指令") }
                } else {
                    items(quickMessages, key = { "qm_${it.id}" }) { qm ->
                        val enabled = qm.id in agent.quickMessageIds
                        LocalSheetSwitchRow(
                            title = qm.title.ifBlank { qm.content.take(20).ifBlank { "未命名快捷指令" } },
                            subtitle = qm.content.take(40),
                            checked = enabled,
                            onToggle = {
                                onUpdate { a ->
                                    a.copy(quickMessageIds = if (enabled) a.quickMessageIds - qm.id else a.quickMessageIds + qm.id)
                                }
                            }
                        )
                    }
                }

                // ============ 本地工具 ============
                item { LocalSheetSectionTitle("本地工具（操作本机）") }
                item {
                    LocalToolSwitchList(
                        selected = agent.localTools,
                        onToggle = { option, enable ->
                            onUpdate { a ->
                                a.copy(localTools = if (enable) a.localTools + option else a.localTools.filter { it != option })
                            }
                        }
                    )
                }
            }
        }
        }
    }
}

/** 工作区单选行：名称 + 绝对路径 */
@Composable
private fun WorkspaceRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 可折叠系统提示词编辑区（会话覆盖） */
@Composable
private fun SystemPromptEditor(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "系统提示词",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "收起 ▴" else if (value.isBlank()) "未设置 · 展开 ▾" else "已设置 · 展开 ▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = { Text("为本会话单独覆盖系统提示词", style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(12.dp),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 可空整数输入（空 = null/默认），本地文本态承接中间输入 */
@Composable
internal fun SessionIntField(
    label: String,
    value: Int?,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (Int?) -> Unit
) {
    // remember(value)：外部值变化（含自己 onChange 触发的回写）时同步；
    // 但不会因面板其他项重组而丢失用户输入
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(7)
            onChange(text.toIntOrNull())
        },
        label = if (label.isNotBlank()) ({ Text(label, style = MaterialTheme.typography.labelMedium) }) else null,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    )
}

/** 可空浮点输入（0..1 常用生成参数），空 = null/默认 */
@Composable
internal fun SessionFloatField(
    label: String,
    value: Float?,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (Float?) -> Unit
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() || it == '.' }.take(6)
            val v = text.toFloatOrNull()
            onChange(if (v != null && v in 0f..2f) v else null)
        },
        label = if (label.isNotBlank()) ({ Text(label, style = MaterialTheme.typography.labelMedium) }) else null,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    )
}

/**
 * 本地工具开关列表（智能体编辑页与聊天扩展面板共用）。
 * 开启「日历读写」时顺带申请系统日历权限。
 */
@Composable
internal fun LocalToolSwitchList(
    selected: List<com.cjhsy.linkzone.data.ai.LocalToolOption>,
    onToggle: (com.cjhsy.linkzone.data.ai.LocalToolOption, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val tools = listOf(
        Triple("时间查询", "查询设备当前日期、时间与时区", LocalToolOption.TimeInfo),
        Triple("剪贴板", "读取或写入剪贴板文本", LocalToolOption.Clipboard),
        Triple("主动追问", "信息不足时暂停生成向你提问（可跳过）", LocalToolOption.AskUser),
        Triple("日历读写", "查询/创建系统日历日程", LocalToolOption.Calendar),
        Triple("JS 执行", "eval_javascript：用 QuickJS 运行 JS 代码/计算", LocalToolOption.Javascript),
        Triple(
            "手机控制",
            "打开 App / 网页 / 拨号盘 / 系统设置、系统分享、查设备信息与定位",
            LocalToolOption.SystemControl
        ),
        Triple("通讯录", "按姓名或号码查询手机联系人", LocalToolOption.Contacts),
        Triple("短信与通话记录", "查询近期短信和通话记录（仅读取）", LocalToolOption.SmsCallLog)
    )
    Column(modifier = modifier) {
        tools.forEach { (title, subtitle, option) ->
            val enabled = option in selected
            LocalSheetSwitchRow(
                title = title,
                subtitle = subtitle,
                checked = enabled,
                onToggle = {
                    onToggle(option, !enabled)
                    // 开启时顺带申请该工具所需的运行时权限（拒绝不影响开关状态，调用时会再次提示）
                    if (!enabled) {
                        val perms = when (option) {
                            is LocalToolOption.Calendar -> arrayOf(
                                android.Manifest.permission.READ_CALENDAR,
                                android.Manifest.permission.WRITE_CALENDAR
                            )
                            is LocalToolOption.SystemControl -> arrayOf(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                android.Manifest.permission.ACCESS_FINE_LOCATION
                            )
                            is LocalToolOption.Contacts -> arrayOf(android.Manifest.permission.READ_CONTACTS)
                            is LocalToolOption.SmsCallLog -> arrayOf(
                                android.Manifest.permission.READ_SMS,
                                android.Manifest.permission.READ_CALL_LOG
                            )
                            else -> emptyArray()
                        }
                        if (perms.isNotEmpty()) permLauncher.launch(perms)
                    }
                }
            )
        }
    }
}

/** 扩展面板小节标题 */
@Composable
internal fun LocalSheetSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

/** 扩展面板空提示 */
@Composable
internal fun LocalSheetEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

/** 扩展面板通用 Switch 行 */
@Composable
internal fun LocalSheetSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
