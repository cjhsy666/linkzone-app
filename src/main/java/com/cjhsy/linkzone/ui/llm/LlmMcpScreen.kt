package com.cjhsy.linkzone.ui.llm

import com.cjhsy.linkzone.core.error.toUserMessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.ai.mcp.McpOAuthState
import com.cjhsy.linkzone.data.ai.mcp.McpServerConfig
import com.cjhsy.linkzone.data.ai.mcp.McpStatus
import com.cjhsy.linkzone.data.ai.mcp.McpTool
import com.cjhsy.linkzone.data.ai.mcp.serverUrl
import com.cjhsy.linkzone.data.repository.LlmRepository
import com.cjhsy.linkzone.data.ai.mcp.McpManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class LlmMcpUiState(
    val servers: List<McpServerConfig> = emptyList(),
    /** 配置 id → 连接状态（McpManager 状态机） */
    val status: Map<String, McpStatus> = emptyMap(),
    /** 非空时显示编辑页 */
    val editing: McpServerConfig? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

/**
 * MCP 服务器管理：CRUD、传输类型（StreamableHTTP / SSE）、自定义请求头、
 * 工具开关（同步后回写）、OAuth 授权（RFC 9728 发现 + PKCE + loopback 回调）。
 */
class LlmMcpViewModel(
    private val llmRepository: LlmRepository,
    private val mcpManager: McpManager
) : ViewModel() {

    private val _state = MutableStateFlow(LlmMcpUiState())
    val state: StateFlow<LlmMcpUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            llmRepository.observeMcpServers().collect { list ->
                _state.value = _state.value.copy(servers = list)
            }
        }
        viewModelScope.launch {
            mcpManager.syncingStatus.collect { map ->
                _state.value = _state.value.copy(status = map)
            }
        }
    }

    fun startCreateStreamable() {
        _state.value = _state.value.copy(editing = McpServerConfig.StreamableHTTPServer(), error = null, message = null)
    }

    fun startCreateSse() {
        _state.value = _state.value.copy(editing = McpServerConfig.SseTransportServer(), error = null, message = null)
    }

    fun startEdit(config: McpServerConfig) {
        _state.value = _state.value.copy(editing = config, error = null, message = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    fun updateEditing(transform: (McpServerConfig) -> McpServerConfig) {
        _state.value = _state.value.editing?.let { cur ->
            _state.value.copy(editing = transform(cur))
        } ?: _state.value
    }

    fun updateTransport(streamable: Boolean) {
        updateEditing { c ->
            when (c) {
                is McpServerConfig.StreamableHTTPServer ->
                    if (streamable) c else McpServerConfig.SseTransportServer(c.id, c.commonOptions, c.url)
                is McpServerConfig.SseTransportServer ->
                    if (!streamable) c else McpServerConfig.StreamableHTTPServer(c.id, c.commonOptions, c.url)
            }
        }
    }

    fun saveEditing() {
        val current = _state.value.editing ?: return
        if (current.serverUrl.isBlank()) {
            _state.value = _state.value.copy(error = "服务器地址不能为空")
            return
        }
        val editing = if (current.commonOptions.name.isBlank()) {
            current.clone(commonOptions = current.commonOptions.copy(name = "MCP 服务器"))
        } else current
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            llmRepository.upsertMcpServer(editing)
            mcpManager.addClient(editing)
            _state.value = _state.value.copy(editing = null, saving = false, message = "已保存")
        }
    }

    /** 列表页直接切换启用开关（落库触发 reconcile 重连/断开） */
    fun toggleEnabled(config: McpServerConfig, enabled: Boolean) {
        viewModelScope.launch {
            llmRepository.updateMcpServer(config.id) { c ->
                c.clone(commonOptions = c.commonOptions.copy(enable = enabled))
            }
        }
    }

    fun delete(config: McpServerConfig) {
        viewModelScope.launch {
            runCatching { mcpManager.removeClient(config) }
            llmRepository.deleteMcpServer(config.id)
            _state.value = _state.value.copy(editing = null, message = "已删除")
        }
    }

    /** 手动全量同步（重连全部并回写工具列表） */
    fun syncAll() {
        viewModelScope.launch {
            runCatching { mcpManager.syncAll() }
                .onFailure { _state.value = _state.value.copy(error = it.toUserMessage("同步失败")) }
        }
    }

    /** 编辑页授权按钮：先落库再走 OAuth 发现 + 浏览器授权 */
    fun authorize(editing: McpServerConfig, context: android.content.Context) {
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            llmRepository.upsertMcpServer(editing)
            mcpManager.startAuthorization(editing, context)
            _state.value = _state.value.copy(saving = false, editing = null, message = "已发起授权，请在浏览器中完成")
        }
    }

    fun cancelAuthorization(configId: String) = mcpManager.cancelAuthorization(configId)

    fun clearAuthorization(config: McpServerConfig) {
        viewModelScope.launch {
            runCatching { mcpManager.clearAuthorization(config) }
                .onFailure { _state.value = _state.value.copy(error = it.toUserMessage("清除授权失败")) }
        }
    }

    // ---- 编辑页字段辅助 ----

    fun updateTool(index: Int, transform: (McpTool) -> McpTool) {
        updateEditing { c ->
            val tools = c.commonOptions.tools.toMutableList()
            if (index in tools.indices) tools[index] = transform(tools[index])
            c.clone(commonOptions = c.commonOptions.copy(tools = tools))
        }
    }

    fun addHeader(key: String, value: String) {
        if (key.isBlank()) return
        updateEditing { c ->
            c.clone(commonOptions = c.commonOptions.copy(headers = c.commonOptions.headers + (key.trim() to value.trim())))
        }
    }

    fun removeHeader(index: Int) {
        updateEditing { c ->
            val headers = c.commonOptions.headers.toMutableList()
            if (index in headers.indices) headers.removeAt(index)
            c.clone(commonOptions = c.commonOptions.copy(headers = headers))
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}

private fun statusLabel(status: McpStatus?): String = when (status) {
    null, McpStatus.Idle -> "未连接"
    McpStatus.Connecting -> "连接中…"
    McpStatus.Connected -> "已连接"
    is McpStatus.Reconnecting -> "重连中（${status.attempt}/${status.maxAttempts}）"
    is McpStatus.Error -> "错误：${status.message.take(60)}"
    McpStatus.NeedsAuthorization -> "需要 OAuth 授权"
    McpStatus.Authorizing -> "授权中…"
}

@Composable
private fun statusColor(status: McpStatus?) = when (status) {
    McpStatus.Connected -> MaterialTheme.colorScheme.primary
    is McpStatus.Error -> MaterialTheme.colorScheme.error
    McpStatus.NeedsAuthorization -> MaterialTheme.colorScheme.tertiary
    null, McpStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun LlmMcpScreen(
    onBack: () -> Unit,
    viewModel: LlmMcpViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val editing = state.editing
    if (editing != null) {
        McpEditor(config = editing, state = state, viewModel = viewModel)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MCP 服务器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = onBack) { Text("返回") }
                    TextButton(onClick = viewModel::syncAll) { Text("同步") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::startCreateStreamable) { Text("+ Streamable HTTP") }
                TextButton(onClick = viewModel::startCreateSse) { Text("+ SSE（旧版）") }
            }
        }

        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (state.servers.isEmpty()) {
            item {
                Text(
                    "MCP（Model Context Protocol）让智能体接入外部工具。添加服务器后，启用服务器的工具会注入对话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(state.servers, key = { it.id }) { config ->
            val status = state.status[config.id]
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.startEdit(config) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            config.commonOptions.name.ifBlank { "未命名" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (config.commonOptions.enable) "启用" else "停用",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (config.commonOptions.enable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = config.commonOptions.enable,
                                onCheckedChange = { v -> viewModel.toggleEnabled(config, v) }
                            )
                        }
                    }
                    Text(
                        "${when (config) {
                            is McpServerConfig.StreamableHTTPServer -> "Streamable HTTP"
                            is McpServerConfig.SseTransportServer -> "SSE"
                        }} · ${config.serverUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(shape = RoundedCornerShape(50), color = statusColor(status).copy(alpha = 0.12f)) {
                            Text(
                                statusLabel(status),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor(status),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        val enabledTools = config.commonOptions.tools.count { it.enable }
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                            Text(
                                "工具 ${enabledTools}/${config.commonOptions.tools.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (config.commonOptions.oauth?.isAuthorized == true) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)) {
                                Text(
                                    "已授权",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable { viewModel.delete(config) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun McpEditor(
    config: McpServerConfig,
    state: LlmMcpUiState,
    viewModel: LlmMcpViewModel
) {
    val context = LocalContext.current
    var headerKey by remember { mutableStateOf("") }
    var headerValue by remember { mutableStateOf("") }
    val oauth = config.commonOptions.oauth

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("编辑 MCP 服务器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = viewModel::cancelEdit) { Text("取消") }
                    TextButton(onClick = viewModel::saveEditing, enabled = !state.saving) { Text("保存") }
                }
            }
        }

        item {
            OutlinedTextField(
                value = config.commonOptions.name,
                onValueChange = { v -> viewModel.updateEditing { c -> c.clone(commonOptions = c.commonOptions.copy(name = v)) } },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = config.serverUrl,
                onValueChange = { v -> viewModel.updateEditing { c ->
                    when (c) {
                        is McpServerConfig.StreamableHTTPServer -> c.copy(url = v)
                        is McpServerConfig.SseTransportServer -> c.copy(url = v)
                    }
                } },
                label = { Text("服务器地址（URL）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Text("传输类型", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val streamable = config is McpServerConfig.StreamableHTTPServer
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (streamable) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable { viewModel.updateTransport(true) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "Streamable HTTP",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (streamable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (!streamable) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable { viewModel.updateTransport(false) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "SSE（旧版）",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!streamable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用（自动连接）", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = config.commonOptions.enable,
                    onCheckedChange = { v ->
                        viewModel.updateEditing { c -> c.clone(commonOptions = c.commonOptions.copy(enable = v)) }
                    }
                )
            }
        }

        // ===== 自定义请求头 =====
        item { Text("自定义请求头", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        items(config.commonOptions.headers.size) { index ->
            val (key, value) = config.commonOptions.headers[index]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$key: $value",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    "移除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { viewModel.removeHeader(index) }
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = headerKey,
                    onValueChange = { headerKey = it },
                    label = { Text("Header") },
                    modifier = Modifier.weight(0.4f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = headerValue,
                    onValueChange = { headerValue = it },
                    label = { Text("值") },
                    modifier = Modifier.weight(0.6f),
                    singleLine = true
                )
                TextButton(onClick = {
                    viewModel.addHeader(headerKey, headerValue)
                    headerKey = ""
                    headerValue = ""
                }) { Text("添加") }
            }
        }

        // ===== OAuth =====
        item { Text("OAuth 授权", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用 OAuth（401 时自动识别）", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = oauth?.enabled == true,
                    onCheckedChange = { v ->
                        viewModel.updateEditing { c ->
                            c.clone(
                                commonOptions = c.commonOptions.copy(
                                    oauth = (c.commonOptions.oauth ?: McpOAuthState(enabled = true)).copy(enabled = v)
                                )
                            )
                        }
                    }
                )
            }
        }
        if (oauth?.enabled == true) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (oauth.isAuthorized) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)) {
                            Text(
                                "已授权" + if (oauth.expiresAt > 0) {
                                    "（令牌 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(oauth.expiresAt)) + " 到期）"
                                } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        TextButton(onClick = { viewModel.clearAuthorization(config) }) { Text("清除授权") }
                    } else {
                        TextButton(onClick = { viewModel.authorize(config, context) }, enabled = !state.saving) {
                            Text("前往授权")
                        }
                        TextButton(onClick = { viewModel.cancelAuthorization(config.id) }) {
                            Text("取消授权流程")
                        }
                    }
                }
            }
        }

        // ===== 工具 =====
        item { Text("工具（${config.commonOptions.tools.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Text(
                "点击「同步」从服务器拉取工具列表；每个工具可单独停用或要求确认后执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(config.commonOptions.tools.size) { index ->
            val tool = config.commonOptions.tools[index]
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    tool.description?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = tool.enable,
                            onCheckedChange = { v -> viewModel.updateTool(index) { t -> t.copy(enable = v) } }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("执行前需确认", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = tool.needsApproval,
                            onCheckedChange = { v -> viewModel.updateTool(index) { t -> t.copy(needsApproval = v) } }
                        )
                    }
                }
            }
        }

        item {
            TextButton(onClick = { viewModel.delete(config) }) {
                Text("删除服务器", color = MaterialTheme.colorScheme.error)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
