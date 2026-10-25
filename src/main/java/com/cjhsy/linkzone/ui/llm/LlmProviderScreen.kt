package com.cjhsy.linkzone.ui.llm

import com.cjhsy.linkzone.core.error.ErrorMessages
import com.cjhsy.linkzone.core.error.toUserMessage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ModelType
import com.cjhsy.linkzone.ai.provider.ProviderManager
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.provider.TextGenerationParams
import com.cjhsy.linkzone.ai.ui.StreamChunk
import com.cjhsy.linkzone.ai.ui.UIMessage
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.androidx.compose.koinViewModel

data class BalanceUi(
    val loading: Boolean = false,
    val value: String? = null,
    val error: String? = null,
)

data class LlmProviderUiState(
    val providers: List<ProviderSetting> = emptyList(),
    /** 非空时显示编辑页 */
    val editing: ProviderSetting? = null,
    /** 正在拉取模型列表的 provider id */
    val fetchingModelsFor: String? = null,
    /** 拉取完成待勾选的模型列表（属于 fetchedModelsProviderId），确认前不落库 */
    val fetchedModels: List<com.cjhsy.linkzone.ai.provider.Model>? = null,
    val fetchedModelsProviderId: String? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val query: String = "",
    val showAddSheet: Boolean = false,
    val balances: Map<String, BalanceUi> = emptyMap(),
    val connectionTest: ConnectionTestState? = null,
    /** editing 非空时：true = 模型管理页，false = 服务商配置页 */
    val modelsPage: Boolean = false,
)

/**
 * LLM Provider 管理：列表（搜索/预设/余额）/ 编辑（Key、地址、协议开关、Vertex）/
 * 模型（拉取/手动添加/排序/能力）/ 连接测试。配置纯本地存储（Room）。
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

    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message, error = null)
    }

    // ============ 列表 ============

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun openAddSheet() {
        _state.value = _state.value.copy(showAddSheet = true)
    }

    fun closeAddSheet() {
        _state.value = _state.value.copy(showAddSheet = false)
    }

    /** 选中预设后直接进入编辑页（配置尚未保存，用户补上 Key 后保存） */
    fun addPreset(preset: ProviderPreset) {
        _state.value = _state.value.copy(
            editing = preset.factory(),
            showAddSheet = false,
            error = null,
            message = null,
            modelsPage = false,
        )
    }

    // ============ 编辑 ============

    fun startCreate(type: Class<out ProviderSetting>) {
        val setting: ProviderSetting = when (type) {
            ProviderSetting.OpenAI::class.java -> ProviderSetting.OpenAI()
            ProviderSetting.Google::class.java -> ProviderSetting.Google()
            else -> ProviderSetting.Claude()
        }
        _state.value = _state.value.copy(
            editing = setting, error = null, message = null, modelsPage = false,
        )
    }

    fun startEdit(provider: ProviderSetting) {
        _state.value = _state.value.copy(
            editing = provider, error = null, message = null, modelsPage = false,
        )
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null, connectionTest = null, modelsPage = false)
    }

    fun openModelsPage() {
        _state.value = _state.value.copy(modelsPage = true)
    }

    fun closeModelsPage() {
        _state.value = _state.value.copy(modelsPage = false)
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
        // Key 常从后台粘贴，顺手去掉首尾空白/换行
        val toSave = when (editing) {
            is ProviderSetting.OpenAI -> editing.copy(
                name = editing.name.trim(),
                apiKey = editing.apiKey.trim(),
                baseUrl = editing.baseUrl.trim(),
            )
            is ProviderSetting.Google -> editing.copy(
                name = editing.name.trim(),
                apiKey = editing.apiKey.trim(),
                baseUrl = editing.baseUrl.trim(),
                privateKey = editing.privateKey.trim(),
            )
            is ProviderSetting.Claude -> editing.copy(
                name = editing.name.trim(),
                apiKey = editing.apiKey.trim(),
                baseUrl = editing.baseUrl.trim(),
            )
        }
        _state.value = _state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            llmRepository.upsertProvider(toSave)
            _state.value = _state.value.copy(
                editing = null, saving = false, connectionTest = null, modelsPage = false,
                message = "已保存",
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            llmRepository.deleteProvider(id)
            _state.value = _state.value.copy(
                editing = null, connectionTest = null, modelsPage = false, message = "已删除",
            )
        }
    }

    fun toggleEnabled(provider: ProviderSetting, enabled: Boolean) {
        viewModelScope.launch {
            llmRepository.upsertProvider(provider.copyProvider(enabled = enabled))
        }
    }

    // ============ Vertex 服务账号 JSON 导入 ============

    fun importVertexServiceAccount(projectId: String?, email: String?, privateKey: String?) {
        updateEditing { p ->
            (p as? ProviderSetting.Google)?.let { g ->
                g.copy(
                    projectId = projectId?.takeIf { it.isNotBlank() } ?: g.projectId,
                    serviceAccountEmail = email?.takeIf { it.isNotBlank() } ?: g.serviceAccountEmail,
                    privateKey = privateKey?.takeIf { it.isNotBlank() } ?: g.privateKey,
                )
            } ?: p
        }
        showMessage("已导入服务账号信息")
    }

    // ============ 拉取模型 ============

    /**
     * 从服务商拉取模型列表。
     *
     * 对齐 RikkaHub：拉取结果**不直接落库**，先进待选列表弹勾选 Sheet，由用户勾选
     * 需要的模型后确认才写入。聚合型网关（OpenRouter 等）/models 会返回几百个模型，
     * 全量合并会把大量用不到的模型灌进配置，污染模型选择器。
     */
    fun fetchModels(provider: ProviderSetting) {
        if (_state.value.fetchingModelsFor != null) return
        _state.value = _state.value.copy(
            fetchingModelsFor = provider.id, error = null, message = null,
            fetchedModels = null, fetchedModelsProviderId = null,
        )
        viewModelScope.launch {
            try {
                val remote = providerManager.getProviderByType(provider).listModels(provider)
                if (remote.isEmpty()) {
                    _state.value = _state.value.copy(
                        fetchingModelsFor = null,
                        error = ErrorMessages.MODEL_LIST_EMPTY
                    )
                    return@launch
                }
                // 新模型用内置注册表识别模态/能力；已有的保留用户自定义（确认时才真正合并）
                val existingIds = provider.models.mapTo(HashSet()) { it.modelId }
                val recognized = remote.map { fetched ->
                    if (fetched.modelId in existingIds) {
                        provider.models.first { it.modelId == fetched.modelId }
                    } else {
                        recognizeFetched(fetched)
                    }
                }
                _state.value = _state.value.copy(
                    fetchingModelsFor = null,
                    fetchedModels = recognized,
                    fetchedModelsProviderId = provider.id,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    fetchingModelsFor = null,
                    error = "拉取失败：${e.toUserMessage()}"
                )
            }
        }
    }

    /** 勾选确认：selected 为远端列表中被勾中的子集；远端已下线的本地模型保留不动 */
    fun confirmFetchSelection(provider: ProviderSetting, selected: List<Model>) {
        val pending = _state.value.fetchedModels ?: return
        val remoteIds = pending.mapTo(HashSet()) { it.modelId }
        val localOnly = provider.models.filter { it.modelId !in remoteIds }
        val existingById = provider.models.associateBy { it.modelId }
        val merged = selected.map { fetched ->
            val old = existingById[fetched.modelId]
            if (old != null) {
                // 远端能更准确识别 embedding 等类型
                if (old.type == ModelType.CHAT && fetched.type != ModelType.CHAT) {
                    old.copy(type = fetched.type)
                } else old
            } else fetched
        } + localOnly
        val added = merged.count { it.modelId !in existingById }
        viewModelScope.launch {
            persistProviderModelChange(provider, provider.copyProvider(models = merged))
            _state.value = _state.value.copy(
                fetchedModels = null, fetchedModelsProviderId = null,
                message = "已更新模型列表：共 ${merged.size} 个" +
                    if (added > 0) "，新增 $added 个" else "",
            )
        }
    }

    /** 放弃本次拉取结果 */
    fun dismissFetchSelection() {
        _state.value = _state.value.copy(fetchedModels = null, fetchedModelsProviderId = null)
    }

    /** 新拉取到的模型：用内置注册表填充默认模态与能力 */
    private fun recognizeFetched(model: Model): Model {
        val rec = recognizeModelId(model.modelId)
        return model.copy(
            inputModalities = rec.inputModalities,
            outputModalities = rec.outputModalities,
            abilities = rec.abilities,
            type = if (model.type == ModelType.CHAT) {
                inferModelType(model.modelId, rec.outputModalities)
            } else model.type,
        )
    }

    fun removeModel(provider: ProviderSetting, model: Model) {
        viewModelScope.launch {
            persistProviderModelChange(provider, provider.delModel(model))
        }
    }

    /** 新建或编辑模型后保存（同 id 或同 modelId 存在则覆盖，否则追加，避免重复） */
    fun saveModel(provider: ProviderSetting, draft: Model) {
        viewModelScope.launch {
            val updated = when {
                provider.models.any { it.id == draft.id } -> provider.editModel(draft)
                else -> {
                    val sameModelId = provider.models.firstOrNull { it.modelId == draft.modelId }
                    if (sameModelId != null) {
                        provider.editModel(draft.copy(id = sameModelId.id))
                    } else {
                        provider.addModel(draft)
                    }
                }
            }
            persistProviderModelChange(provider, updated)
        }
    }

    /** 模型上移/下移 */
    fun moveModel(provider: ProviderSetting, from: Int, to: Int) {
        val models = provider.models
        if (from !in models.indices || to !in models.indices) return
        viewModelScope.launch {
            persistProviderModelChange(provider, provider.moveMove(from, to))
        }
    }

    /** 模型变更统一落库并同步编辑草稿（模型操作即时生效，无需再点保存） */
    private suspend fun persistProviderModelChange(
        provider: ProviderSetting,
        updated: ProviderSetting,
    ) {
        llmRepository.upsertProvider(updated)
        _state.value = _state.value.copy(
            editing = _state.value.editing?.let { e -> if (e.id == provider.id) updated else e }
        )
    }

    // ============ 余额 ============

    fun refreshBalance(provider: ProviderSetting) {
        if (!provider.balanceOption.enabled) return
        _state.value = _state.value.copy(
            balances = _state.value.balances + (provider.id to BalanceUi(loading = true))
        )
        viewModelScope.launch {
            val result = runCatching {
                providerManager.getProviderByType(provider).getBalance(provider)
            }
            val ui = result.fold(
                onSuccess = { BalanceUi(loading = false, value = it) },
                onFailure = { BalanceUi(loading = false, error = it.toUserMessage("获取失败")) }
            )
            _state.value = _state.value.copy(
                balances = _state.value.balances + (provider.id to ui)
            )
        }
    }

    // ============ 连接测试 ============

    fun openConnectionTest() {
        val p = _state.value.editing ?: return
        val first = p.models.firstOrNull { it.type == ModelType.CHAT } ?: p.models.firstOrNull()
        _state.value = _state.value.copy(connectionTest = ConnectionTestState(modelId = first?.id))
    }

    fun closeConnectionTest() {
        if (_state.value.connectionTest?.running == true) return
        _state.value = _state.value.copy(connectionTest = null)
    }

    fun selectTestModel(modelId: String) {
        _state.value = _state.value.copy(
            connectionTest = _state.value.connectionTest?.copy(modelId = modelId)
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun runConnectionTest() {
        val setting = _state.value.editing ?: return
        val test = _state.value.connectionTest ?: return
        val model = setting.models.firstOrNull { it.id == test.modelId }
            ?: setting.models.firstOrNull { it.type == ModelType.CHAT }
            ?: return
        _state.value = _state.value.copy(
            connectionTest = ConnectionTestState(modelId = model.id, running = true)
        )
        viewModelScope.launch {
            val provider = providerManager.getProviderByType(setting)
            // 非流式
            setTestItem(nonStream = TestItemState.Loading)
            val nonStream = async {
                runCatching {
                    val result = provider.generateText(
                        providerSetting = setting,
                        messages = listOf(
                            UIMessage.system("You are a helpful assistant"),
                            UIMessage.user("hello"),
                        ),
                        params = TextGenerationParams(model = model),
                    )
                    result.message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                }
            }
            // 流式
            setTestItem(stream = TestItemState.Loading)
            val stream = async {
                runCatching {
                    val sb = StringBuilder()
                    provider.streamText(
                        providerSetting = setting,
                        messages = listOf(
                            UIMessage.system("You are a helpful assistant"),
                            UIMessage.user("hello"),
                        ),
                        params = TextGenerationParams(model = model),
                    ).collect { chunk ->
                        if (chunk is StreamChunk.TextDelta) sb.append(chunk.text)
                    }
                    sb.toString()
                }
            }
            // 工具调用
            setTestItem(tools = TestItemState.Loading)
            val tools = async {
                runCatching {
                    val testTool = Tool(
                        name = "get_current_time",
                        description = "Get the current date and time.",
                        execute = { emptyList() }
                    )
                    val result = provider.generateText(
                        providerSetting = setting,
                        messages = listOf(
                            UIMessage.system("You are a helpful assistant"),
                            UIMessage.user("Use the get_current_time tool to tell me the current time."),
                        ),
                        params = TextGenerationParams(model = model, tools = listOf(testTool)),
                    )
                    val called = result.message.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                    if (called != null) {
                        "模型请求了工具：${called.toolName}(${called.input})"
                    } else {
                        val text = result.message.parts.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("") { it.text }
                        "模型未调用工具。回复：$text"
                    }
                }
            }

            nonStream.await().fold(
                onSuccess = { setTestItem(nonStream = TestItemState.Ok(it)) },
                onFailure = { setTestItem(nonStream = TestItemState.Error(it.toUserMessage("请求失败"))) }
            )
            stream.await().fold(
                onSuccess = { text ->
                    _state.value = _state.value.copy(
                        connectionTest = _state.value.connectionTest?.copy(
                            stream = TestItemState.Ok(),
                            streamText = text,
                        )
                    )
                },
                onFailure = { setTestItem(stream = TestItemState.Error(it.toUserMessage("请求失败"))) }
            )
            tools.await().fold(
                onSuccess = { setTestItem(tools = TestItemState.Ok(it)) },
                onFailure = { setTestItem(tools = TestItemState.Error(it.toUserMessage("请求失败"))) }
            )
            _state.value = _state.value.copy(
                connectionTest = _state.value.connectionTest?.copy(running = false)
            )
        }
    }

    private fun setTestItem(
        nonStream: TestItemState? = null,
        stream: TestItemState? = null,
        tools: TestItemState? = null,
    ) {
        _state.value = _state.value.copy(
            connectionTest = _state.value.connectionTest?.let {
                it.copy(
                    nonStream = nonStream ?: it.nonStream,
                    stream = stream ?: it.stream,
                    tools = tools ?: it.tools,
                )
            }
        )
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

    // 拉取模型结果待勾选：列表卡片 / 编辑页 / 模型页三个入口共用同一 Sheet
    state.fetchedModels?.let { fetched ->
        val pid = state.fetchedModelsProviderId
        val target = state.editing?.takeIf { it.id == pid }
            ?: state.providers.firstOrNull { it.id == pid }
        if (target != null) {
            FetchModelsSelectionSheet(
                fetched = fetched,
                existingIds = target.models.mapTo(HashSet()) { it.modelId },
                onDismiss = viewModel::dismissFetchSelection,
                onConfirm = { viewModel.confirmFetchSelection(target, it) },
            )
        }
    }

    if (state.showAddSheet) {
        AddProviderSheet(
            onDismiss = viewModel::closeAddSheet,
            onSelect = viewModel::addPreset,
        )
    }

    val editing = state.editing
    if (editing != null) {
        if (state.modelsPage) {
            ProviderModelsPage(
                provider = editing,
                state = state,
                viewModel = viewModel,
                onBack = viewModel::closeModelsPage,
            )
        } else {
            ProviderEditor(
                provider = editing,
                state = state,
                viewModel = viewModel,
                onBack = viewModel::cancelEdit
            )
        }
        return
    }

    val query = state.query.trim()
    val shown = if (query.isBlank()) {
        state.providers
    } else {
        state.providers.filter { p ->
            p.name.contains(query, ignoreCase = true) ||
                p.models.any { it.modelId.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true) }
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
                Text("AI 模型服务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBack) { Text("返回") }
            }
        }

        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索服务商或模型") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }

        item {
            OutlinedButton(
                onClick = viewModel::openAddSheet,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加服务商")
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
                    "还没有服务商。\n点击「添加服务商」选择官方或常用预设，\n补上 API Key 后拉取模型即可使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    "没有匹配「$query」的服务商或模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(shown, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                balance = state.balances[provider.id],
                fetching = state.fetchingModelsFor == provider.id,
                onClick = { viewModel.startEdit(provider) },
                onToggle = { viewModel.toggleEnabled(provider, it) },
                onFetchModels = { viewModel.fetchModels(provider) },
                onRefreshBalance = { viewModel.refreshBalance(provider) },
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderSetting,
    balance: BalanceUi?,
    fetching: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onFetchModels: () -> Unit,
    onRefreshBalance: () -> Unit,
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
            if (provider.balanceOption.enabled) {
                Spacer(Modifier.height(6.dp))
                BalanceRow(balance, onRefreshBalance)
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
private fun BalanceRow(balance: BalanceUi?, onRefresh: () -> Unit) {
    // 首次展示自动查询一次
    LaunchedEffect(Unit) {
        if (balance == null) onRefresh()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "余额：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            balance == null || balance.loading -> {
                CircularProgressIndicator(modifier = Modifier.height(12.dp).width(12.dp), strokeWidth = 2.dp)
            }
            balance.error != null -> {
                Text(
                    "获取失败，点击重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRefresh)
                        .padding(vertical = 2.dp),
                )
            }
            else -> {
                Text(
                    balance.value ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onRefresh, modifier = Modifier.height(28.dp).width(28.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新余额",
                        modifier = Modifier.height(15.dp).width(15.dp),
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
    val context = LocalContext.current

    // Vertex 服务账号 JSON 选择
    val serviceAccountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(content).jsonObject
            viewModel.importVertexServiceAccount(
                projectId = obj["project_id"]?.jsonPrimitive?.contentOrNull,
                email = obj["client_email"]?.jsonPrimitive?.contentOrNull,
                privateKey = obj["private_key"]?.jsonPrimitive?.contentOrNull,
            )
        }.onFailure {
            viewModel.showMessage("服务账号解析失败：${it.toUserMessage("文件格式不正确")}")
        }
    }

    state.connectionTest?.let { test ->
        ProviderConnectionTesterDialog(
            models = provider.models.filter { it.type == ModelType.CHAT },
            state = test,
            onSelectModel = viewModel::selectTestModel,
            onRun = viewModel::runConnectionTest,
            onDismiss = viewModel::closeConnectionTest,
        )
    }

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
                    TextButton(
                        onClick = viewModel::openConnectionTest,
                        enabled = provider.models.isNotEmpty(),
                    ) { Text("连接测试") }
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
            is ProviderSetting.Google -> GoogleFields(provider, viewModel) {
                serviceAccountLauncher.launch(arrayOf("application/json"))
            }
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

        // ===== 模型入口（独立页面） =====
        item {
            ModelEntryCard(
                provider = provider,
                fetching = state.fetchingModelsFor == provider.id,
                onClick = viewModel::openModelsPage,
                onFetch = { viewModel.fetchModels(provider) },
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

/** 模型入口卡片：展示数量与前几个模型，点击进入模型管理页 */
@Composable
private fun ModelEntryCard(
    provider: ProviderSetting,
    fetching: Boolean,
    onClick: () -> Unit,
    onFetch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "模型 · ${provider.models.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (provider.models.isEmpty()) {
                        "点此拉取或手动添加模型"
                    } else {
                        provider.models.take(3)
                            .joinToString("、") { it.displayName.ifBlank { it.modelId } }
                            .let { if (provider.models.size > 3) "$it …" else it }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onFetch, enabled = !fetching) {
                if (fetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "拉取模型")
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = "进入模型管理",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** API Key / 私钥类敏感输入：遮罩 + 眼睛切换 */
@Composable
private fun SecretKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = !visible,
        maxLines = if (visible) 3 else 1,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隐藏" else "显示",
                )
            }
        }
    )
}

/** baseUrl 输入：格式校验 + 一键重置官方地址 */
@Composable
private fun BaseUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    val valid = value.isBlank() || value.toHttpUrlOrNull() != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("API 地址") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = !valid,
        supportingText = if (!valid) {
            { Text("地址格式不正确，需以 http:// 或 https:// 开头") }
        } else null,
        trailingIcon = {
            IconButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = "重置为官方地址")
            }
        }
    )
}

/** OpenAI 兼容服务商字段（LazyListScope 扩展，供编辑页 LazyColumn 内调用） */
private fun LazyListScope.OpenAIFields(provider: ProviderSetting.OpenAI, viewModel: LlmProviderViewModel) {
    item {
        SecretKeyField(
            value = provider.apiKey,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(apiKey = v) ?: p } },
            label = "API Key",
        )
    }
    item {
        BaseUrlField(
            value = provider.baseUrl,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(baseUrl = v) ?: p } },
            onReset = {
                viewModel.updateEditing { p ->
                    (p as? ProviderSetting.OpenAI)?.copy(baseUrl = "https://api.openai.com/v1") ?: p
                }
            },
        )
    }
    item {
        LabeledSwitch(
            label = "使用 Responses API",
            supporting = "关闭则走 Chat Completions",
            checked = provider.useResponseApi,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(useResponseApi = v) ?: p } }
        )
    }
    item {
        OutlinedTextField(
            value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
            onValueChange = { v ->
                viewModel.updateEditing { p ->
                    (p as? ProviderSetting.OpenAI)?.let {
                        if (it.useResponseApi) it.copy(responsesPath = v) else it.copy(chatCompletionsPath = v)
                    } ?: p
                }
            },
            label = { Text(if (provider.useResponseApi) "Responses 路径" else "Chat Completions 路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
    item {
        LabeledSwitch(
            label = "历史消息携带推理内容",
            supporting = "关闭可节省 Token，部分推理模型要求开启",
            checked = provider.includeHistoryReasoning,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.OpenAI)?.copy(includeHistoryReasoning = v) ?: p } }
        )
    }
    // 余额查询（仅 OpenAI 协议实现）
    item {
        LabeledSwitch(
            label = "启用余额查询",
            supporting = "在列表卡片显示账户余额",
            checked = provider.balanceOption.enabled,
            onChange = { enabled ->
                viewModel.updateEditing { p ->
                    (p as? ProviderSetting.OpenAI)?.let {
                        it.copy(balanceOption = it.balanceOption.copy(enabled = enabled))
                    } ?: p
                }
            }
        )
    }
    if (provider.balanceOption.enabled) {
        item {
            OutlinedTextField(
                value = provider.balanceOption.apiPath,
                onValueChange = { v ->
                    viewModel.updateEditing { p ->
                        (p as? ProviderSetting.OpenAI)?.let {
                            it.copy(balanceOption = it.balanceOption.copy(apiPath = v))
                        } ?: p
                    }
                },
                label = { Text("余额接口路径（如 /user/balance）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = provider.balanceOption.resultPath,
                onValueChange = { v ->
                    viewModel.updateEditing { p ->
                        (p as? ProviderSetting.OpenAI)?.let {
                            it.copy(balanceOption = it.balanceOption.copy(resultPath = v))
                        } ?: p
                    }
                },
                label = { Text("余额字段路径（如 data.total_usage）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

/** Google 服务商字段 */
private fun LazyListScope.GoogleFields(
    provider: ProviderSetting.Google,
    viewModel: LlmProviderViewModel,
    onImportServiceAccount: () -> Unit,
) {
    item {
        LabeledSwitch(
            label = "Vertex AI",
            supporting = "GCP 企业服务，需项目与服务账号",
            checked = provider.vertexAI,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(vertexAI = v) ?: p } }
        )
    }
    if (provider.vertexAI) {
        item {
            LabeledSwitch(
                label = "使用服务账号认证",
                supporting = "导入 GCP JSON 密钥，以 JWT 换取 Access Token",
                checked = provider.useServiceAccount,
                onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(useServiceAccount = v) ?: p } }
            )
        }
    }
    // Vertex + 服务账号时使用 JWT 认证与固定 endpoint，无需 Key/自定义 baseUrl
    if (!(provider.vertexAI && provider.useServiceAccount)) {
        item {
            SecretKeyField(
                value = provider.apiKey,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(apiKey = v) ?: p } },
                label = "API Key",
            )
        }
        item {
            BaseUrlField(
                value = provider.baseUrl,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(baseUrl = v) ?: p } },
                onReset = {
                    viewModel.updateEditing { p ->
                        (p as? ProviderSetting.Google)?.copy(baseUrl = "https://generativelanguage.googleapis.com/v1beta") ?: p
                    }
                },
            )
        }
    }
    if (provider.vertexAI && provider.useServiceAccount) {
        item {
            OutlinedButton(
                onClick = onImportServiceAccount,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("导入服务账号 JSON")
            }
        }
        item {
            OutlinedTextField(
                value = provider.projectId,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(projectId = v) ?: p } },
                label = { Text("项目 ID（project_id）") },
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
                label = { Text("服务账号邮箱（client_email）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            SecretKeyField(
                value = provider.privateKey,
                onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(privateKey = v) ?: p } },
                label = "私钥（private_key，PEM 内容）",
            )
        }
    }
    if (!provider.vertexAI) {
        item {
            LabeledSwitch(
                label = "使用 Interactions API",
                supporting = "Gemini 新版交互接口（不支持时保持关闭）",
                checked = provider.useInteractionsApi,
                onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Google)?.copy(useInteractionsApi = v) ?: p } }
            )
        }
    }
}

/** Claude 服务商字段 */
private fun LazyListScope.ClaudeFields(provider: ProviderSetting.Claude, viewModel: LlmProviderViewModel) {
    item {
        SecretKeyField(
            value = provider.apiKey,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Claude)?.copy(apiKey = v) ?: p } },
            label = "API Key",
        )
    }
    item {
        BaseUrlField(
            value = provider.baseUrl,
            onValueChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Claude)?.copy(baseUrl = v) ?: p } },
            onReset = {
                viewModel.updateEditing { p ->
                    (p as? ProviderSetting.Claude)?.copy(baseUrl = "https://api.anthropic.com/v1") ?: p
                }
            },
        )
    }
    item {
        LabeledSwitch(
            label = "提示词缓存",
            supporting = "在请求中标记 cache_control，降低重复内容费用",
            checked = provider.promptCaching,
            onChange = { v -> viewModel.updateEditing { p -> (p as? ProviderSetting.Claude)?.copy(promptCaching = v) ?: p } }
        )
    }
    if (provider.promptCaching) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("缓存有效期", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider.promptCacheTtl == com.cjhsy.linkzone.ai.provider.ClaudePromptCacheTtl.FIVE_MINUTES,
                        onClick = {
                            viewModel.updateEditing { p ->
                                (p as? ProviderSetting.Claude)?.copy(
                                    promptCacheTtl = com.cjhsy.linkzone.ai.provider.ClaudePromptCacheTtl.FIVE_MINUTES
                                ) ?: p
                            }
                        },
                        label = { Text("5 分钟（默认）") }
                    )
                    FilterChip(
                        selected = provider.promptCacheTtl == com.cjhsy.linkzone.ai.provider.ClaudePromptCacheTtl.ONE_HOUR,
                        onClick = {
                            viewModel.updateEditing { p ->
                                (p as? ProviderSetting.Claude)?.copy(
                                    promptCacheTtl = com.cjhsy.linkzone.ai.provider.ClaudePromptCacheTtl.ONE_HOUR
                                ) ?: p
                            }
                        },
                        label = { Text("1 小时（扩展缓存）") }
                    )
                }
            }
        }
    }
}

/**
 * 带说明文字的开关行（协议字段用）。
 */
@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    supporting: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    if (provider is ProviderSetting.Google && provider.vertexAI && provider.useServiceAccount) return "服务账号认证"
    if (provider is ProviderSetting.Google && provider.vertexAI) return "Vertex AI"
    return when {
        key.isBlank() -> "未配置 Key"
        key.length <= 8 -> "Key ****"
        else -> "Key ${key.take(4)}****${key.takeLast(4)}"
    }
}
