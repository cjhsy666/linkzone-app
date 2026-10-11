package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.ai.core.ReasoningLevel
import com.cjhsy.linkzone.data.ai.LocalToolOption
import com.cjhsy.linkzone.data.ai.mcp.McpServerConfig
import com.cjhsy.linkzone.data.ai.mcp.McpStatus
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.LlmSkill

// ============================================================
// 本地智能体专属 UI：顶栏模型选择面板 / AI 扩展 chips / 扩展面板
// 统一聊天页通过组件复用这些能力（云端会话不显示）
// ============================================================

/** 顶栏模型选择底部菜单：按服务商分组；底部附切换智能体 / 管理智能体入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelSheet(
    models: List<ChatModelOption>,
    currentModelId: String,
    onPick: (String) -> Unit,
    onSwitchAgent: () -> Unit,
    onManageAgents: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (models.isEmpty()) {
                Text(
                    text = "暂无可用模型，请先在「设置 → 模型服务」中启用服务商并添加模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                // 按服务商分组保持顺序（LinkedHashMap 保序）
                val grouped = remember(models) {
                    models.groupBy { it.providerName }.toList()
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    grouped.forEach { (provider, list) ->
                        item(key = "prov_$provider") {
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 20.dp, end = 20.dp,
                                    top = 10.dp, bottom = 4.dp
                                )
                            )
                        }
                        items(list, key = { "m_${it.modelId}" }) { model ->
                            val selected = model.modelId == currentModelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(model.modelId) }
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
                                    if (model.modelId != model.label) {
                                        Text(
                                            text = model.modelId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
            }

            // 底部入口：切换智能体 / 管理智能体
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onSwitchAgent, modifier = Modifier.weight(1f)) {
                    Text("切换智能体")
                }
                TextButton(onClick = onManageAgents, modifier = Modifier.weight(1f)) {
                    Text("管理智能体")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ============================================================
// AI 扩展入口（Rikka 式：输入栏上方功能 chips + 扩展面板）
// ============================================================

/** 输入栏上方功能行：模型 chip + 联网搜索开关 chip + 推理等级 chip + AI 扩展入口 chip（带角标） */
@Composable
internal fun LocalExtensionChips(
    agent: LlmAgent?,
    currentModelLabel: String?,
    onOpenModels: () -> Unit,
    onToggleSearch: () -> Unit,
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
        item(key = "model") {
            LocalToggleChip(
                label = currentModelLabel?.let { "模型 · $it" } ?: "选择模型",
                icon = Icons.Filled.SmartToy,
                checked = false,
                onClick = onOpenModels
            )
        }
        item(key = "web_search") {
            LocalToggleChip(
                label = "联网搜索",
                icon = Icons.Filled.Public,
                checked = agent.enableWebSearch,
                onClick = onToggleSearch
            )
        }
        item(key = "reasoning") {
            LocalToggleChip(
                label = "推理·${reasoningLevelLabel(agent.reasoningLevel)}",
                icon = Icons.Filled.Psychology,
                checked = agent.reasoningLevel.isEnabled,
                onClick = onOpenExtensions
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
 * AI 扩展面板（Rikka 式 ModalBottomSheet）：
 * 推理等级 / MCP 服务器 / 技能 / 记忆 / 本地工具，改动即时持久化到当前智能体
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalExtensionSheet(
    agent: LlmAgent?,
    skills: List<LlmSkill>,
    mcpServers: List<McpServerConfig>,
    mcpStatus: Map<String, McpStatus>,
    workspaceName: String?,
    onOpenWorkspace: () -> Unit,
    onCompressNow: () -> Unit,
    onUpdate: ((LlmAgent) -> LlmAgent) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        if (agent == null) {
            Text(
                text = "当前会话未关联智能体",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
            Spacer(Modifier.height(20.dp))
            return@ModalBottomSheet
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "AI 扩展",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // ---------- 代码工作区 ----------
            LocalSheetSectionTitle("代码工作区")
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenWorkspace)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = workspaceName ?: "未绑定工作区",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (workspaceName != null) "已绑定 · 点击打开管理页（文件浏览 / 终端）"
                            else "绑定后智能体可读写文件、执行命令",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "↗",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ---------- 联网搜索 ----------
            LocalSheetSwitchRow(
                title = "联网搜索",
                subtitle = "为当前会话注入 search_web 工具",
                checked = agent.enableWebSearch,
                onToggle = { onUpdate { it.copy(enableWebSearch = !it.enableWebSearch) } }
            )

            // ---------- 推理等级 ----------
            LocalSheetSectionTitle("推理等级")
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
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

            // ---------- MCP 服务器 ----------
            LocalSheetSectionTitle("MCP 服务器")
            if (mcpServers.isEmpty()) {
                LocalSheetEmptyHint("暂无 MCP 服务器，请在「设置 → MCP」中添加")
            } else {
                mcpServers.forEach { server ->
                    val enabled = server.id in agent.mcpServers
                    val statusText = mcpStatusLabel(mcpStatus[server.id]) +
                        if (server.commonOptions.enable) "" else " · 已禁用"
                    LocalSheetSwitchRow(
                        title = server.commonOptions.name.ifBlank { "未命名服务器" },
                        subtitle = statusText,
                        checked = enabled,
                        onToggle = {
                            onUpdate { a ->
                                val next = if (enabled) a.mcpServers - server.id
                                else a.mcpServers + server.id
                                a.copy(mcpServers = next)
                            }
                        }
                    )
                }
            }

            // ---------- 技能 ----------
            LocalSheetSectionTitle("技能")
            if (skills.isEmpty()) {
                LocalSheetEmptyHint("暂无技能，请在「设置 → 技能」中添加")
            } else {
                skills.forEach { skill ->
                    val enabled = skill.id in agent.enabledSkills
                    LocalSheetSwitchRow(
                        title = skill.name.ifBlank { "未命名技能" },
                        subtitle = skill.description.take(40),
                        checked = enabled,
                        onToggle = {
                            onUpdate { a ->
                                val next = if (enabled) a.enabledSkills - skill.id
                                else a.enabledSkills + skill.id
                                a.copy(enabledSkills = next)
                            }
                        }
                    )
                }
            }

            // ---------- 记忆 ----------
            LocalSheetSectionTitle("记忆")
            LocalSheetSwitchRow(
                title = "启用记忆",
                subtitle = "注入已有记忆并允许模型增删改",
                checked = agent.enableMemory,
                onToggle = { onUpdate { it.copy(enableMemory = !it.enableMemory) } }
            )
            if (agent.enableMemory) {
                LocalSheetSwitchRow(
                    title = "使用全局共享记忆",
                    subtitle = "关闭时使用本智能体隔离记忆",
                    checked = agent.useGlobalMemory,
                    onToggle = { onUpdate { it.copy(useGlobalMemory = !it.useGlobalMemory) } }
                )
            }

            // ---------- 上下文 ----------
            LocalSheetSectionTitle("上下文")
            LocalSheetSwitchRow(
                title = "自动压缩上下文",
                subtitle = "超出上限时自动把旧消息总结为检查点",
                checked = agent.enableContextCompression,
                onToggle = { onUpdate { it.copy(enableContextCompression = !it.enableContextCompression) } }
            )
            LocalSheetSwitchRow(
                title = "时间感知",
                subtitle = "在长间隔消息前注入当前时间提醒，跨天对话不混淆时间",
                checked = agent.enableTimeReminder,
                onToggle = { onUpdate { it.copy(enableTimeReminder = !it.enableTimeReminder) } }
            )
            // 手动压缩入口：立即把较早消息总结为检查点
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "立即压缩上下文",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "手动总结较早消息为检查点，长对话立即瘦身",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onCompressNow) { Text("执行") }
            }

            // ---------- 本地工具 ----------
            LocalSheetSectionTitle("本地工具")
            LocalToolSwitchList(
                selected = agent.localTools,
                onToggle = { option, enable ->
                    onUpdate { a ->
                        a.copy(
                            localTools = if (enable) a.localTools + option
                            else a.localTools.filter { it != option }
                        )
                    }
                }
            )
        }
    }
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
    val calendarPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val tools = listOf(
        Triple("图表渲染", "允许模型通过 chart_display 渲染图表", LocalToolOption.ChartDisplay),
        Triple("时间查询", "查询设备当前日期、时间与时区", LocalToolOption.TimeInfo),
        Triple("剪贴板", "读取或写入剪贴板文本", LocalToolOption.Clipboard),
        Triple("主动追问", "信息不足时暂停生成向你提问（可跳过）", LocalToolOption.AskUser),
        Triple("日历读写", "查询/创建系统日历日程", LocalToolOption.Calendar),
        Triple("语音播报", "朗读文本给用户听（系统 TTS）", LocalToolOption.Tts),
        Triple("JS 执行", "eval_javascript：用 QuickJS 运行 JS 代码/计算", LocalToolOption.Javascript)
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
                    if (option is LocalToolOption.Calendar && !enabled) {
                        calendarPermLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.READ_CALENDAR,
                                android.Manifest.permission.WRITE_CALENDAR
                            )
                        )
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
