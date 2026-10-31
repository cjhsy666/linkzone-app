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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.ai.SshSessionManager
import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class LlmSshUiState(
    val workspaces: List<SshWorkspaceConfig> = emptyList(),
    /** 非空时显示编辑页 */
    val editing: SshWorkspaceConfig? = null,
    val saving: Boolean = false,
    /** 正在测试连接的标记（编辑页按钮 loading） */
    val testing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

/**
 * SSH 工作区管理：CRUD + 连接测试。
 *
 * 配置存 Room（llm_ssh_workspace 表，常用列 + JSON 列），智能体绑定后注入
 * ssh_read_file / ssh_write_file / ssh_list_dir / ssh_shell 四个工具。
 * 密码/私钥存本机数据库（与其他 LLM 配置同一安全级别，不出设备）。
 */
class LlmSshViewModel(
    private val appContext: android.content.Context,
    private val llmRepository: LlmRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LlmSshUiState())
    val state: StateFlow<LlmSshUiState> = _state.asStateFlow()

    init {
        SshSessionManager.init(appContext.filesDir)
        viewModelScope.launch {
            llmRepository.observeSshWorkspaces().collect { list ->
                _state.value = _state.value.copy(workspaces = list)
            }
        }
    }

    fun startCreate() {
        _state.value = _state.value.copy(editing = SshWorkspaceConfig(), error = null, message = null)
    }

    fun startEdit(config: SshWorkspaceConfig) {
        _state.value = _state.value.copy(editing = config, error = null, message = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    fun updateEditing(transform: (SshWorkspaceConfig) -> SshWorkspaceConfig) {
        _state.value = _state.value.editing?.let { cur ->
            _state.value.copy(editing = transform(cur))
        } ?: _state.value
    }

    fun saveEditing() {
        val current = _state.value.editing ?: return
        if (current.host.isBlank() || current.username.isBlank()) {
            _state.value = _state.value.copy(error = "主机地址和用户名不能为空")
            return
        }
        if (current.authType == "password" && current.password.isBlank()) {
            _state.value = _state.value.copy(error = "密码不能为空（或改用私钥认证）")
            return
        }
        if (current.authType == "key" && current.privateKey.isBlank()) {
            _state.value = _state.value.copy(error = "私钥内容不能为空（或改用密码认证）")
            return
        }
        val editing = if (current.name.isBlank()) current.copy(name = current.summary) else current
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            llmRepository.upsertSshWorkspace(editing)
            // 连接参数可能已变：丢弃缓存会话，下次调用按新配置重连
            SshSessionManager.invalidate(editing.id)
            _state.value = _state.value.copy(editing = null, saving = false, message = "已保存")
        }
    }

    fun toggleEnabled(config: SshWorkspaceConfig, enabled: Boolean) {
        viewModelScope.launch {
            llmRepository.upsertSshWorkspace(config.copy(enabled = enabled))
            if (!enabled) SshSessionManager.invalidate(config.id)
        }
    }

    fun delete(config: SshWorkspaceConfig) {
        viewModelScope.launch {
            SshSessionManager.invalidate(config.id)
            llmRepository.deleteSshWorkspace(config.id)
            _state.value = _state.value.copy(editing = null, message = "已删除")
        }
    }

    /** 测试连接：用编辑页当前配置跑一次 echo 回显 */
    fun testConnection() {
        val current = _state.value.editing ?: return
        _state.value = _state.value.copy(testing = true, error = null, message = null)
        viewModelScope.launch {
            // 先丢弃可能存在的旧会话（用户可能刚改过密码/主机），用当前编辑配置全新连接
            SshSessionManager.invalidate(current.id)
            runCatching { SshSessionManager.testConnection(current) }
                .onSuccess { ms ->
                    _state.value = _state.value.copy(testing = false, message = "连接成功（${ms}ms）")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        testing = false,
                        error = e.toUserMessage("连接失败")
                    )
                }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}

@Composable
fun LlmSshScreen(
    onBack: () -> Unit,
    viewModel: LlmSshViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val editing = state.editing
    if (editing != null) {
        SshEditor(config = editing, state = state, viewModel = viewModel)
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
                Text("SSH 工作区", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = onBack) { Text("返回") }
                    TextButton(onClick = viewModel::startCreate) { Text("+ 添加") }
                }
            }
        }

        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (state.workspaces.isEmpty()) {
            item {
                Text(
                    "SSH 工作区让智能体连接远程服务器：执行 shell 命令、读写文件（SFTP）。" +
                        "添加后到智能体编辑页绑定，即注入 ssh_read_file / ssh_write_file / ssh_edit_file / ssh_list_dir / ssh_shell 工具。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(state.workspaces, key = { it.id }) { config ->
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
                            config.name.ifBlank { "未命名" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (config.enabled) "启用" else "停用",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (config.enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { v -> viewModel.toggleEnabled(config, v) }
                            )
                        }
                    }
                    Text(
                        "${config.summary} · 工作目录 ${config.workDir}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        ) {
                            Text(
                                if (config.authType == "key") "私钥认证" else "密码认证",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
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
private fun SshEditor(
    config: SshWorkspaceConfig,
    state: LlmSshUiState,
    viewModel: LlmSshViewModel
) {
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
                Text("编辑 SSH 工作区", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = viewModel::cancelEdit) { Text("取消") }
                    TextButton(onClick = viewModel::saveEditing, enabled = !state.saving) { Text("保存") }
                }
            }
        }

        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            OutlinedTextField(
                value = config.name,
                onValueChange = { v -> viewModel.updateEditing { it.copy(name = v) } },
                label = { Text("名称（留空用 用户名@主机）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.host,
                    onValueChange = { v -> viewModel.updateEditing { it.copy(host = v.trim()) } },
                    label = { Text("主机地址") },
                    modifier = Modifier.weight(0.7f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = config.port.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { p -> viewModel.updateEditing { it.copy(port = p) } }
                    },
                    label = { Text("端口") },
                    modifier = Modifier.weight(0.3f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
        item {
            OutlinedTextField(
                value = config.username,
                onValueChange = { v -> viewModel.updateEditing { it.copy(username = v.trim()) } },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // ===== 认证方式 =====
        item { Text("认证方式", style = MaterialTheme.typography.bodyMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isPassword = config.authType == "password"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isPassword) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable { viewModel.updateEditing { it.copy(authType = "password") } }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "密码",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (!isPassword) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable { viewModel.updateEditing { it.copy(authType = "key") } }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        "私钥",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!isPassword) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (config.authType == "password") {
            item {
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { v -> viewModel.updateEditing { it.copy(password = v) } },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = config.privateKey,
                    onValueChange = { v -> viewModel.updateEditing { it.copy(privateKey = v) } },
                    label = { Text("私钥（PEM 全文，含 BEGIN/END 行）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8
                )
            }
            item {
                OutlinedTextField(
                    value = config.keyPassphrase,
                    onValueChange = { v -> viewModel.updateEditing { it.copy(keyPassphrase = v) } },
                    label = { Text("私钥口令（无则留空）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        }

        item {
            OutlinedTextField(
                value = config.workDir,
                onValueChange = { v -> viewModel.updateEditing { it.copy(workDir = v.ifBlank { "~" }) } },
                label = { Text("远程工作目录（工具的相对路径基于它，如 ~/proj）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { v -> viewModel.updateEditing { it.copy(enabled = v) } }
                )
            }
        }

        item {
            TextButton(onClick = viewModel::testConnection, enabled = !state.testing) {
                Text(if (state.testing) "测试中…" else "测试连接")
            }
        }

        item {
            TextButton(onClick = { viewModel.delete(config) }) {
                Text("删除工作区", color = MaterialTheme.colorScheme.error)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
