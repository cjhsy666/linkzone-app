package com.cjhsy.linkzone.ui.llm

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ModelAbility
import com.cjhsy.linkzone.ai.provider.Modality
import com.cjhsy.linkzone.ai.provider.ProviderManager
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class LlmProviderUiState(
    val providers: List<ProviderSetting> = emptyList(),
    /** 非空时显示编辑页 */
    val editing: ProviderSetting? = null,
    /** 正在拉取模型列表的 provider id */
    val fetchingModelsFor: String? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

/**
 * LLM Provider 管理：列表 / 新建（openai/google/claude）/ 编辑（key、地址、自定义头）/
 * 拉取模型列表 / 删除。配置纯本地存储（Room）。
 */
class LlmProviderViewModel(
    private val llmRepository: LlmRepository,
    private val providerManager: ProviderManager
) : ViewModel() {

    private val _state = MutableStateFlow(LlmProviderUiState())
    val state: StateFlow<LlmProviderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            llmRepository.observeProviders().collect { list ->
                _state.value = _state.value.copy(providers = list)
            }
        }
    }

    // ============ 编辑 ============

    fun startCreate(type: Class<out ProviderSetting>) {
        val setting: ProviderSetting = when (type) {
            ProviderSetting.OpenAI::class.java -> ProviderSetting.OpenAI()
            ProviderSetting.Google::class.java -> ProviderSetting.Google()
            else -> ProviderSetting.Claude()
        }
        _state.value = _state.value.copy(editing = setting, error = null, message = null)
    }

    fun startEdit(provider: ProviderSetting) {
        _state.value = _state.value.copy(editing = provider, error = null, message = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    fun updateEditing(transform: (ProviderSetting) -> ProviderSetting) {
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
            llmRepository.upsertProvider(editing)
            _state.value = _state.value.copy(editing = null, saving = false, message = "已保存")
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            llmRepository.deleteProvider(id)
            _state.value = _state.value.copy(editing = null, message = "已删除")
        }
    }

    fun toggleEnabled(provider: ProviderSetting, enabled: Boolean) {
        viewModelScope.launch {
            llmRepository.upsertProvider(provider.copyProvider(enabled = enabled))
        }
    }

    // ============ 拉取模型 ============

    /** 调 Provider 的 listModels 并把结果写回 provider 配置 */
    fun fetchModels(provider: ProviderSetting) {
        if (_state.value.fetchingModelsFor != null) return
        _state.value = _state.value.copy(fetchingModelsFor = provider.id, error = null)
        viewModelScope.launch {
            try {
                val models = providerManager.getProviderByType(provider).listModels(provider)
                if (models.isEmpty()) {
                    _state.value = _state.value.copy(
                        fetchingModelsFor = null,
                        error = "服务返回空模型列表，请检查地址与 Key"
                    )
                    return@launch
                }
                llmRepository.upsertProvider(provider.copyProvider(models = models))
                _state.value = _state.value.copy(
                    editing = _state.value.editing?.let { e ->
                        if (e.id == provider.id) e.copyProvider(models = models) else e
                    },
                    fetchingModelsFor = null,
                    message = "已拉取 ${models.size} 个模型"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    fetchingModelsFor = null,
                    error = "拉取失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    fun removeModel(provider: ProviderSetting, model: Model) {
        viewModelScope.launch {
            llmRepository.upsertProvider(provider.delModel(model))
        }
    }

    /** 更新模型能力（UI 上勾选图片/工具/推理开关时调用） */
    fun updateModel(provider: ProviderSetting, updated: Model) {
        viewModelScope.launch {
            llmRepository.upsertProvider(provider.editModel(updated))
            _state.value = _state.value.copy(
                editing = _state.value.editing?.let { e ->
                    if (e.id == provider.id) e.editModel(updated) else e
                }
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}

@Composable
fun LlmProviderScreen(
    onBack: () -> Unit,
    viewModel: LlmProviderViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val editing = state.editing
    if (editing != null) {
        ProviderEditor(
            provider = editing,
            state = state,
            viewModel = viewModel,
            onBack = viewModel::cancelEdit
        )
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
                Text("AI 模型服务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBack) { Text("返回") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.startCreate(ProviderSetting.OpenAI::class.java) }) { Text("+ OpenAI 兼容") }
                TextButton(onClick = { viewModel.startCreate(ProviderSetting.Google::class.java) }) { Text("+ Google") }
                TextButton(onClick = { viewModel.startCreate(ProviderSetting.Claude::class.java) }) { Text("+ Claude") }
            }
        }

        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (state.providers.isEmpty()) {
            item {
                Text(
                    "还没有服务商。\n添加 OpenAI 兼容 / Google / Claude 后拉取模型，\n即可在智能体中绑定使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(state.providers, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                fetching = state.fetchingModelsFor == provider.id,
                onClick = { viewModel.startEdit(provider) },
                onToggle = { viewModel.toggleEnabled(provider, it) },
                onFetchModels = { viewModel.fetchModels(provider) }
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderSetting,
    fetching: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onFetchModels: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = provider.typeName(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Switch(checked = provider.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${provider.models.size} 个模型 · ${maskedKey(provider)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (provider.models.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = provider.models.take(3).joinToString("、") { it.displayName.ifBlank { it.modelId } }
                        .let { if (provider.models.size > 3) "$it …" else it },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在拉取模型…", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        "拉取模型",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onFetchModels)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderEditor(
    provider: ProviderSetting,
    state: LlmProviderUiState,
    viewModel: LlmProviderViewModel,
    onBack: () -> Unit
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
                Text("编辑服务商", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = onBack) { Text("取消") }
                    TextButton(onClick = viewModel::saveEditing, enabled = !state.saving) { Text("保存") }
                }
            }
        }

        // ===== 基本信息 =====
        item {
            OutlinedTextField(
                value = provider.name,
                onValueChange = { v -> viewModel.updateEditing { p -> p.copyProvider(name = v) } },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        when (provider) {
            is ProviderSetting.OpenAI -> OpenAIFields(provider, viewModel)
            is ProviderSetting.Google -> GoogleFields(provider, viewModel)
            is ProviderSetting.Claude -> ClaudeFields(provider, viewModel)
        }

        // ===== 自定义请求头 =====
        item {
            Text("自定义请求头", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        items(provider.customHeaders.size) { index ->
            val header = provider.customHeaders[index]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { v ->
                        viewModel.updateEditing { p ->
                            p.copyProvider(customHeaders = p.customHeaders.mapIndexed { i, h -> if (i == index) h.copy(name = v) else h })
                        }
                    },
                    label = { Text("Header") },
                    modifier = Modifier.weight(0.4f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { v ->
                        viewModel.updateEditing { p ->
                            p.copyProvider(customHeaders = p.customHeaders.mapIndexed { i, h -> if (i == index) h.copy(value = v) else h })
                        }
                    },
                    label = { Text("值") },
                    modifier = Modifier.weight(0.6f),
                    singleLine = true
                )
                Text(
                    "✕",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            viewModel.updateEditing { p ->
                                p.copyProvider(customHeaders = p.customHeaders.filterIndexed { i, _ -> i != index })
                            }
                        }
                        .padding(6.dp)
                )
            }
        }
        item {
            TextButton(onClick = {
                viewModel.updateEditing { p ->
                    p.copyProvider(customHeaders = p.customHeaders + com.cjhsy.linkzone.ai.provider.CustomHeader("", ""))
                }
            }) { Text("+ 添加请求头") }
        }

        // ===== 模型列表 =====
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("模型（${provider.models.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { viewModel.fetchModels(provider) }, enabled = state.fetchingModelsFor == null) {
                    Text("拉取模型")
                }
            }
        }
        items(provider.models, key = { it.id }) { model ->
            ModelCapabilityRow(
                provider = provider,
                model = model,
                onToggleVision = { vision ->
                    val newModalities = if (vision) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT)
                    viewModel.updateModel(provider, model.copy(inputModalities = newModalities))
                },
                onToggleTools = { tools ->
                    val newAbilities = (model.abilities.toSet() - ModelAbility.TOOL).toMutableSet().apply {
                        if (tools) add(ModelAbility.TOOL)
                    }.toList()
                    viewModel.updateModel(provider, model.copy(abilities = newAbilities))
                },
                onToggleReasoning = { reasoning ->
                    val newAbilities = (model.abilities.toSet() - ModelAbility.REASONING).toMutableSet().apply {
                        if (reasoning) add(ModelAbility.REASONING)
                    }.toList()
                    viewModel.updateModel(provider, model.copy(abilities = newAbilities))
                },
                onRemove = { viewModel.removeModel(provider, model) }
            )
        }

        item {
            TextButton(onClick = { viewModel.delete(provider.id) }) {
                Text("删除服务商", color = MaterialTheme.colorScheme.error)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** OpenAI 兼容服务商字段（LazyListScope 扩展，供编辑页 LazyColumn 内调用） */
private fun LazyListScope.OpenAIFields(provider: ProviderSetting.OpenAI, viewModel: LlmProviderViewModel) {
    item {
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(apiKey = v) ?: p } },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(baseUrl = v) ?: p } },
            label = { Text("API 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        OutlinedTextField(
            value = provider.chatCompletionsPath,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(chatCompletionsPath = v) ?: p } },
            label = { Text("Chat Completions 路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        LabeledSwitch(
            label = "使用 Responses API",
            checked = provider.useResponseApi,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(useResponseApi = v) ?: p } }
        )
    }
}

/** Google 服务商字段 */
private fun LazyListScope.GoogleFields(provider: ProviderSetting.Google, viewModel: LlmProviderViewModel) {
    item {
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(apiKey = v) ?: p } },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(baseUrl = v) ?: p } },
            label = { Text("API 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        LabeledSwitch(
            label = "Vertex AI（服务账号）",
            checked = provider.vertexAI,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(vertexAI = v) ?: p } }
        )
    }
    if (provider.vertexAI) {
        item {
            OutlinedTextField(
                value = provider.projectId,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(projectId = v) ?: p } },
                label = { Text("项目 ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = provider.location,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(location = v) ?: p } },
                label = { Text("区域（如 us-central1）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = provider.serviceAccountEmail,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(serviceAccountEmail = v) ?: p } },
                label = { Text("服务账号邮箱") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = provider.privateKey,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(privateKey = v) ?: p } },
                label = { Text("私钥（PEM 内容）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
        }
    }
}

/** Claude 服务商字段 */
private fun LazyListScope.ClaudeFields(provider: ProviderSetting.Claude, viewModel: LlmProviderViewModel) {
    item {
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Claude)?.copy(apiKey = v) ?: p } },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Claude)?.copy(baseUrl = v) ?: p } },
            label = { Text("API 地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        LabeledSwitch(
            label = "提示词缓存",
            checked = provider.promptCaching,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Claude)?.copy(promptCaching = v) ?: p } }
        )
    }
}

/**
 * Provider 模型列表里的一行：名称 + 当前能力摘要 + 可展开的能力开关面板。
 * 用户没手动配置时能力可能为空（= 默认行为：registry 查表 / 工具全开 / 图片直传 API），
 * 用户手动勾选后会写入模型自身的 inputModalities / abilities 字段，覆盖默认行为。
 */
@Composable
private fun ModelCapabilityRow(
    provider: ProviderSetting,
    model: Model,
    onToggleVision: (Boolean) -> Unit,
    onToggleTools: (Boolean) -> Unit,
    onToggleReasoning: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 当前能力状态：模型自身显式声明优先，否则走 registry fallback 显示
    val vision = Modality.IMAGE in model.inputModalities
    val tools = ModelAbility.TOOL in model.abilities || model.abilities.isEmpty()
    val reasoning = ModelAbility.REASONING in model.abilities
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.displayName.ifBlank { model.modelId },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = model.modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (vision) CapChip("📷")
                    if (tools) CapChip("🔧")
                    if (reasoning) CapChip("🧠")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                LabeledSwitch("支持图片输入（视觉）", vision, onToggleVision)
                LabeledSwitch("支持工具调用", tools) { onToggleTools(it) }
                LabeledSwitch("支持深度思考（推理）", reasoning, onToggleReasoning)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRemove) {
                        Text("移除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 极简能力小徽章 */
@Composable
private fun CapChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun ProviderSetting.typeName(): String = when (this) {
    is ProviderSetting.OpenAI -> "OpenAI 兼容"
    is ProviderSetting.Google -> "Google"
    is ProviderSetting.Claude -> "Claude"
}

private fun maskedKey(provider: ProviderSetting): String {
    val key = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey
        is ProviderSetting.Google -> provider.apiKey
        is ProviderSetting.Claude -> provider.apiKey
    }
    if (provider is ProviderSetting.Google && provider.vertexAI) return "Vertex AI"
    return when {
        key.isBlank() -> "未配置 Key"
        key.length <= 8 -> "Key ****"
        else -> "Key ${key.take(4)}****${key.takeLast(4)}"
    }
}

// 说明：copyProvider 使用 ProviderSetting 的成员方法（带默认参数，成员优先于扩展），
// 可自动保留 balanceOption/builtIn 等未指定字段。
