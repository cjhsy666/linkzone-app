package com.cjhsy.linkzone.ui.llm

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.FileProvider
import com.cjhsy.linkzone.ai.core.ReasoningLevel
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.data.ai.mcp.McpServerConfig
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.LlmLorebook
import com.cjhsy.linkzone.data.model.LlmPrompt
import com.cjhsy.linkzone.data.model.LlmQuickMessage
import com.cjhsy.linkzone.data.model.LlmSkill
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel

data class LlmAgentUiState(
    val agents: List<LlmAgent> = emptyList(),
    /** Provider → 模型清单（供模型选择） */
    val providers: List<ProviderSetting> = emptyList(),
    /** AI 扩展配置（编辑页关联多选用） */
    val skills: List<LlmSkill> = emptyList(),
    val prompts: List<LlmPrompt> = emptyList(),
    val lorebooks: List<LlmLorebook> = emptyList(),
    val quickMessages: List<LlmQuickMessage> = emptyList(),
    /** MCP 服务器清单（编辑页多选） */
    val mcpServers: List<McpServerConfig> = emptyList(),
    /** 非空时显示编辑页 */
    val editing: LlmAgent? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

/** 智能体导出文件格式（type 字段用于导入时校验） */
@Serializable
internal data class AgentExportPayload(
    val type: String = "linkzone-agent",
    val version: Int = 1,
    val agent: LlmAgent
)

/**
 * 本地智能体管理：CRUD、系统提示词（支持 {{model}}/{{time}}/{{date}}）、
 * 模型绑定、生成参数（温度/topP/最大 token/推理等级）、上下文上限、流式开关、
 * AI 扩展关联（Skills / 模式注入 / 世界书 / 快捷指令）、导出/导入（JSON 文件）。
 */
class LlmAgentViewModel(
    private val llmRepository: LlmRepository,
    private val json: Json
) : ViewModel() {

    private val _state = MutableStateFlow(LlmAgentUiState())
    val state: StateFlow<LlmAgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            llmRepository.observeAgents().collect { list ->
                _state.value = _state.value.copy(agents = list)
            }
        }
        viewModelScope.launch {
            llmRepository.observeProviders().collect { list ->
                _state.value = _state.value.copy(providers = list)
            }
        }
        viewModelScope.launch {
            llmRepository.observeSkills().collect { list ->
                _state.value = _state.value.copy(skills = list)
            }
        }
        viewModelScope.launch {
            llmRepository.observePrompts().collect { list ->
                _state.value = _state.value.copy(prompts = list)
            }
        }
        viewModelScope.launch {
            llmRepository.observeLorebooks().collect { list ->
                _state.value = _state.value.copy(lorebooks = list)
            }
        }
        viewModelScope.launch {
            llmRepository.observeQuickMessages().collect { list ->
                _state.value = _state.value.copy(quickMessages = list)
            }
        }
        viewModelScope.launch {
            llmRepository.observeMcpServers().collect { list ->
                _state.value = _state.value.copy(mcpServers = list)
            }
        }
    }

    fun startCreate() {
        _state.value = _state.value.copy(
            editing = LlmAgent(name = "", avatarEmoji = "🤖"),
            error = null, message = null
        )
    }

    fun startEdit(agent: LlmAgent) {
        _state.value = _state.value.copy(editing = agent, error = null, message = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    fun updateEditing(transform: (LlmAgent) -> LlmAgent) {
        _state.value = _state.value.editing?.let { cur ->
            _state.value.copy(editing = transform(cur))
        } ?: _state.value
    }

    fun saveEditing() {
        val editing = _state.value.editing ?: return
        if (editing.name.isBlank()) {
            _state.value = _state.value.copy(error = "名称不能为空")
            return
        }
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            llmRepository.upsertAgent(editing)
            _state.value = _state.value.copy(editing = null, saving = false, message = "已保存")
        }
    }

    fun delete(agent: LlmAgent) {
        viewModelScope.launch {
            if (llmRepository.isAgentBuiltIn(agent.id)) {
                _state.value = _state.value.copy(error = "内置默认智能体不可删除")
                return@launch
            }
            llmRepository.deleteAgent(agent.id)
            _state.value = _state.value.copy(editing = null, message = "已删除")
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    /** 序列化智能体为导出 JSON（文件内容由 UI 层写入并分享） */
    fun exportAgentJson(agent: LlmAgent): String =
        json.encodeToString(AgentExportPayload.serializer(), AgentExportPayload(agent = agent))

    /**
     * 从导出 JSON 导入智能体：校验格式、重置 id 避免覆盖已有配置，
     * 与现有智能体重名时自动追加「·导入」后缀。
     */
    fun importAgentJson(text: String) {
        viewModelScope.launch {
            try {
                val payload = json.decodeFromString(AgentExportPayload.serializer(), text)
                val imported = payload.agent
                    .copy(id = java.util.UUID.randomUUID().toString())
                    .let { a ->
                        val nameTaken = _state.value.agents.any { it.name == a.name }
                        if (nameTaken) a.copy(name = a.name.ifBlank { "导入的智能体" } + " ·导入")
                        else a.copy(name = a.name.ifBlank { "导入的智能体" })
                    }
                llmRepository.upsertAgent(imported)
                _state.value = _state.value.copy(
                    message = "已导入「${imported.name}」", error = null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "导入失败：${e.message ?: "文件格式不正确"}"
                )
            }
        }
    }

    /** 可选模型展示名 */
    fun modelLabel(modelId: String?): String {
        if (modelId == null) return "跟随全局默认"
        state.value.providers.forEach { p ->
            p.models.firstOrNull { it.modelId == modelId }?.let {
                return "${p.name} / ${it.displayName.ifBlank { it.modelId }}"
            }
        }
        return "模型已被删除"
    }
}

@Composable
fun LlmAgentScreen(
    onBack: () -> Unit,
    viewModel: LlmAgentViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val editing = state.editing
    if (editing != null) {
        AgentEditor(agent = editing, state = state, viewModel = viewModel)
        return
    }

    // 导入：从文件选择器挑 JSON（兼容部分文件管理器报 text/plain）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取所选文件")
                    viewModel.importAgentJson(text)
                }.onFailure { e ->
                    Toast.makeText(context, "导入失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                Text("智能体", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = onBack) { Text("返回") }
                    TextButton(onClick = viewModel::startCreate) { Text("新建") }
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("导入") }
                }
            }
        }

        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (state.agents.isEmpty()) {
            item {
                Text(
                    "还没有智能体。新建一个并绑定模型，即可在抽屉「新建本地对话」中使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(state.agents, key = { it.id }) { agent ->
            var menuOpen by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.startEdit(agent) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .width(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(agent.avatarEmoji.ifBlank { "🤖" }, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(agent.name.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            viewModel.modelLabel(agent.chatModelId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (agent.systemPrompt.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                "提示词",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("导出") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        runCatching {
                                            val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                            val safeName = agent.name.ifBlank { "agent" }
                                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                                .take(40)
                                            val file = java.io.File(dir, "${safeName}_${System.currentTimeMillis()}.json")
                                            file.writeText(viewModel.exportAgentJson(agent))
                                            val uri = FileProvider.getUriForFile(
                                                context, "${context.packageName}.fileprovider", file
                                            )
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                android.content.Intent.createChooser(intent, "导出智能体")
                                            )
                                        }.onFailure { e ->
                                            Toast.makeText(context, "导出失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AgentEditor(
    agent: LlmAgent,
    state: LlmAgentUiState,
    viewModel: LlmAgentViewModel
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
                Text("编辑智能体", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = viewModel::cancelEdit) { Text("取消") }
                    TextButton(onClick = viewModel::saveEditing, enabled = !state.saving) { Text("保存") }
                }
            }
        }

        // ===== 基础 =====
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = agent.avatarEmoji,
                    onValueChange = { v -> viewModel.updateEditing { it.copy(avatarEmoji = v.take(2)) } },
                    label = { Text("头像") },
                    modifier = Modifier.width(88.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = agent.name,
                    onValueChange = { v -> viewModel.updateEditing { it.copy(name = v) } },
                    label = { Text("名称") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }

        // ===== 模型绑定 =====
        item { Text("对话模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            ModelPicker(
                providers = state.providers,
                selectedModelId = agent.chatModelId,
                label = viewModel.modelLabel(agent.chatModelId),
                onSelect = { modelId -> viewModel.updateEditing { it.copy(chatModelId = modelId) } }
            )
        }

        // ===== 提示词 =====
        item { Text("系统提示词", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            OutlinedTextField(
                value = agent.systemPrompt,
                onValueChange = { v -> viewModel.updateEditing { it.copy(systemPrompt = v) } },
                label = { Text("支持 {{model}} / {{time}} / {{date}} 变量") },
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
        }

        // ===== 参数 =====
        item { Text("生成参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = agent.temperature?.toString() ?: "",
                    onValueChange = { v -> viewModel.updateEditing { it.copy(temperature = v.toFloatOrNull()) } },
                    label = { Text("温度") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = agent.topP?.toString() ?: "",
                    onValueChange = { v -> viewModel.updateEditing { it.copy(topP = v.toFloatOrNull()) } },
                    label = { Text("Top P") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = agent.maxTokens?.toString() ?: "",
                    onValueChange = { v -> viewModel.updateEditing { it.copy(maxTokens = v.toIntOrNull()) } },
                    label = { Text("最大 Token") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }
        item {
            OutlinedTextField(
                value = if (agent.contextMessageLimit == 0) "" else agent.contextMessageLimit.toString(),
                onValueChange = { v -> viewModel.updateEditing { it.copy(contextMessageLimit = v.toIntOrNull() ?: 0) } },
                label = { Text("上下文消息条数上限（0 = 不限制）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Text(
                "推理等级",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningLevel.entries.forEach { level ->
                    val selected = agent.reasoningLevel == level
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                            .clickable { viewModel.updateEditing { it.copy(reasoningLevel = level) } }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            level.name.lowercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("流式输出", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agent.streamOutput,
                    onCheckedChange = { v -> viewModel.updateEditing { it.copy(streamOutput = v) } }
                )
            }
        }

        // ===== AI 扩展关联 =====
        item { Text("AI 扩展", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Text(
                "技能（use_skill 按需加载）、模式注入与世界书（提示词注入）、快捷指令（输入框上方一键填入）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.skills.isNotEmpty()) {
            item { Text("技能", style = MaterialTheme.typography.bodyMedium) }
            item {
                MultiSelectChips(
                    items = state.skills.map { it.id to it.name },
                    selectedIds = agent.enabledSkills,
                    onToggle = { id ->
                        viewModel.updateEditing { a ->
                            a.copy(enabledSkills = toggleIn(a.enabledSkills, id))
                        }
                    }
                )
            }
        }
        if (state.prompts.isNotEmpty()) {
            item { Text("模式注入", style = MaterialTheme.typography.bodyMedium) }
            item {
                MultiSelectChips(
                    items = state.prompts.map { it.id to (it.name.ifBlank { "未命名" }) },
                    selectedIds = agent.promptIds,
                    onToggle = { id ->
                        viewModel.updateEditing { a ->
                            a.copy(promptIds = toggleIn(a.promptIds, id))
                        }
                    }
                )
            }
        }
        if (state.lorebooks.isNotEmpty()) {
            item { Text("世界书", style = MaterialTheme.typography.bodyMedium) }
            item {
                MultiSelectChips(
                    items = state.lorebooks.map { it.id to (it.name.ifBlank { "未命名" }) },
                    selectedIds = agent.lorebookIds,
                    onToggle = { id ->
                        viewModel.updateEditing { a ->
                            a.copy(lorebookIds = toggleIn(a.lorebookIds, id))
                        }
                    }
                )
            }
        }
        if (state.quickMessages.isNotEmpty()) {
            item { Text("快捷指令", style = MaterialTheme.typography.bodyMedium) }
            item {
                MultiSelectChips(
                    items = state.quickMessages.map { it.id to it.title },
                    selectedIds = agent.quickMessageIds,
                    onToggle = { id ->
                        viewModel.updateEditing { a ->
                            a.copy(quickMessageIds = toggleIn(a.quickMessageIds, id))
                        }
                    }
                )
            }
        }

        // ===== 工具与记忆 =====
        item { Text("工具与记忆", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        if (state.mcpServers.isNotEmpty()) {
            item {
                Text(
                    "MCP 服务器（启用且已连接的服务器工具会注入对话，命名 mcp__服务器__工具）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                MultiSelectChips(
                    items = state.mcpServers.map { it.id to it.commonOptions.name.ifBlank { "未命名" } },
                    selectedIds = agent.mcpServers,
                    onToggle = { id ->
                        viewModel.updateEditing { a ->
                            a.copy(mcpServers = toggleIn(a.mcpServers, id))
                        }
                    }
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用记忆（memory_tool + 记忆注入）", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agent.enableMemory,
                    onCheckedChange = { v -> viewModel.updateEditing { it.copy(enableMemory = v) } }
                )
            }
        }
        if (agent.enableMemory) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("使用全局共享记忆", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = agent.useGlobalMemory,
                        onCheckedChange = { v -> viewModel.updateEditing { it.copy(useGlobalMemory = v) } }
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
                Text("联网搜索（search_web / scrape_web）", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agent.enableWebSearch,
                    onCheckedChange = { v -> viewModel.updateEditing { it.copy(enableWebSearch = v) } }
                )
            }
        }

        // ===== 图片生成 =====
        item { Text("图片生成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用 image_generate 工具（模型按需出图）", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agent.enableImageGeneration,
                    onCheckedChange = { v -> viewModel.updateEditing { it.copy(enableImageGeneration = v) } }
                )
            }
        }
        if (agent.enableImageGeneration) {
            item {
                ModelPicker(
                    providers = state.providers,
                    selectedModelId = agent.imageModelId,
                    label = agent.imageModelId?.let(viewModel::modelLabel) ?: "自动选择第一个图片模型",
                    onSelect = { modelId -> viewModel.updateEditing { it.copy(imageModelId = modelId) } },
                    defaultLabel = "自动选择第一个图片模型",
                    modelFilter = { it.type == com.cjhsy.linkzone.ai.provider.ModelType.IMAGE }
                )
            }
        }

        // ===== OCR 兜底 =====
        item { Text("图片识别（OCR）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("对话模型不支持图片时，用视觉模型把图片转文字", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = agent.enableOcr,
                    onCheckedChange = { v -> viewModel.updateEditing { it.copy(enableOcr = v) } }
                )
            }
        }
        if (agent.enableOcr) {
            item {
                ModelPicker(
                    providers = state.providers,
                    selectedModelId = agent.ocrModelId,
                    label = agent.ocrModelId?.let(viewModel::modelLabel) ?: "自动选择第一个视觉模型",
                    onSelect = { modelId -> viewModel.updateEditing { it.copy(ocrModelId = modelId) } },
                    defaultLabel = "自动选择第一个视觉模型",
                    modelFilter = {
                        it.type == com.cjhsy.linkzone.ai.provider.ModelType.CHAT &&
                            com.cjhsy.linkzone.ai.provider.Modality.IMAGE in it.inputModalities
                    }
                )
            }
        }

        // ===== 代码工作区 =====
        item { Text("代码工作区", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        item { WorkspaceSelector(agent, viewModel) }

        item {
            TextButton(onClick = { viewModel.delete(agent) }) {
                Text("删除智能体", color = MaterialTheme.colorScheme.error)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun toggleIn(set: Set<String>, id: String): Set<String> =
    if (id in set) set - id else set + id

/** 工作区绑定选择器：列出/新建/删除工作区，并把所选 id 写回智能体 */
@Composable
private fun WorkspaceSelector(
    agent: LlmAgent,
    viewModel: LlmAgentViewModel
) {
    val workspaceManager = org.koin.compose.koinInject<com.cjhsy.linkzone.workspace.WorkspaceManager>()
    var showDialog by remember { mutableStateOf(false) }
    val boundName = remember(agent.workspaceId, showDialog) {
        agent.workspaceId?.let { workspaceManager.get(it)?.name }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { showDialog = true }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                boundName ?: "未绑定（点击管理工作区）",
                style = MaterialTheme.typography.bodyMedium,
                color = if (boundName != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("管理 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showDialog) {
        WorkspaceManageDialog(
            workspaceManager = workspaceManager,
            selectedId = agent.workspaceId,
            onSelect = { id ->
                viewModel.updateEditing { it.copy(workspaceId = id) }
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun WorkspaceManageDialog(
    workspaceManager: com.cjhsy.linkzone.workspace.WorkspaceManager,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var refreshTick by remember { mutableStateOf(0) }
    val workspaces = remember(refreshTick) { workspaceManager.list() }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun refresh() { refreshTick++ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("代码工作区") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
                if (workspaceManager.shellMode == "PRoot Linux") {
                    Text(
                        "Shell 模式：PRoot Linux（完整发行版用户态）。AI 可在绑定工作区内读写文件、执行命令（workspace_read_file/write_file/edit_file/shell）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Shell 模式：Android Shell（/system/bin/sh，toybox 命令集）。\n" +
                            "如需完整 Linux（apt/python/node），将可执行 proot 二进制放到 " +
                            "filesDir/proot/bin/proot、解压 rootfs 到 filesDir/linux/rootfs 后自动切换为 PRoot。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (workspaces.isEmpty()) {
                    Text("还没有工作区，在下方新建一个。", style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(workspaces, key = { it.id }) { ws ->
                        val stats = remember(ws.id, refreshTick) { workspaceManager.get(ws.id)?.let { workspaceManager.stats(it) } }
                        val selected = ws.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable { onSelect(ws.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (selected) "✓ " else "") + ws.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${stats?.fileCount ?: 0} 个文件 · ${(stats?.totalBytes ?: 0) / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("删除", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        workspaceManager.delete(ws.id)
                                        refresh()
                                    }
                                })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("新工作区名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val ws = workspaceManager.create(newName.trim())
                                newName = ""
                                refresh()
                                onSelect(ws.id)
                            }
                        }
                    }) { Text("新建") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(null) }) { Text("取消绑定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 通用多选 chips（横向换行简化为纵向排列的横向滚动） */
@Composable
private fun MultiSelectChips(
    items: List<Pair<String, String>>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (id, label) ->
            val selected = id in selectedIds
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onToggle(id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    (if (selected) "✓ " else "") + label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 模型下拉选择：按 Provider 分组展示，附「跟随全局默认」选项 */
@Composable
private fun ModelPicker(
    providers: List<ProviderSetting>,
    selectedModelId: String?,
    label: String,
    onSelect: (String?) -> Unit,
    defaultLabel: String = "跟随全局默认",
    modelFilter: (com.cjhsy.linkzone.ai.provider.Model) -> Boolean = { true }
) {
    var expanded = remember { mutableStateOf(false) }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { expanded.value = true }
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text("选择 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = { onSelect(null); expanded.value = false }
            )
            providers.filter { it.enabled }.forEach { provider ->
                val visibleModels = provider.models.filter(modelFilter)
                if (visibleModels.isEmpty()) return@forEach
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {}
                )
                visibleModels.forEach { model ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(model.displayName.ifBlank { model.modelId }) },
                        onClick = { onSelect(model.modelId); expanded.value = false }
                    )
                }
            }
        }
    }
}
