package com.cjhsy.linkzone.ui.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.repository.LlmRepository
import com.cjhsy.linkzone.data.search.SearchServiceOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 单个搜索服务条目（options + 开关 + 是否当前使用） */
data class SearchProviderEntry(
    val options: SearchServiceOptions,
    val enabled: Boolean,
    val selected: Boolean
)

data class LlmSearchUiState(
    val providers: List<SearchProviderEntry> = emptyList(),
    /** 非空时弹出编辑对话框 */
    val editing: SearchServiceOptions? = null,
    /** 新建时的初始开关状态 */
    val editingIsNew: Boolean = false,
    val error: String? = null
)

/** 联网搜索服务管理：Tavily / Exa / Brave / SearXNG 配置、启用与「当前使用」单选 */
class LlmSearchViewModel(
    private val llmRepository: LlmRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LlmSearchUiState())
    val state: StateFlow<LlmSearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            llmRepository.observeSearchProviders().collect { list ->
                _state.value = _state.value.copy(
                    providers = list.map { (options, entity) ->
                        SearchProviderEntry(options, entity.enabled, entity.selected)
                    }
                )
            }
        }
    }

    fun startCreate(type: Class<out SearchServiceOptions>) {
        val options: SearchServiceOptions = when (type) {
            SearchServiceOptions.TavilyOptions::class.java -> SearchServiceOptions.TavilyOptions()
            SearchServiceOptions.ExaOptions::class.java -> SearchServiceOptions.ExaOptions()
            SearchServiceOptions.BraveOptions::class.java -> SearchServiceOptions.BraveOptions()
            SearchServiceOptions.SearXNGOptions::class.java -> SearchServiceOptions.SearXNGOptions()
            else -> return
        }
        _state.value = _state.value.copy(editing = options, editingIsNew = true, error = null)
    }

    fun startEdit(entry: SearchProviderEntry) {
        _state.value = _state.value.copy(editing = entry.options, editingIsNew = false, error = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null, error = null)
    }

    fun updateEditing(transform: (SearchServiceOptions) -> SearchServiceOptions) {
        _state.value = _state.value.editing?.let { cur ->
            _state.value.copy(editing = transform(cur))
        } ?: _state.value
    }

    /** 保存（selected 单选语义由仓库层 clearSelected 保证） */
    fun save(options: SearchServiceOptions, enabled: Boolean, selected: Boolean) {
        val valid = when (options) {
        is SearchServiceOptions.SearXNGOptions -> options.url.isNotBlank()
        else -> true
    }
        if (!valid) {
            _state.value = _state.value.copy(error = "必填项不能为空")
            return
        }
        viewModelScope.launch {
            llmRepository.upsertSearchProvider(options, enabled, selected)
            _state.value = _state.value.copy(editing = null, error = null)
        }
    }

    fun delete(entry: SearchProviderEntry) {
        viewModelScope.launch { llmRepository.deleteSearchProvider(entry.options.id) }
    }

    /** 仅供列表页快捷切换「当前使用」 */
    fun selectEntry(entry: SearchProviderEntry) {
        viewModelScope.launch {
            llmRepository.upsertSearchProvider(entry.options, enabled = true, selected = true)
        }
    }

    fun toggleEnabled(entry: SearchProviderEntry, enabled: Boolean) {
        viewModelScope.launch {
            llmRepository.upsertSearchProvider(entry.options, enabled = enabled, selected = entry.selected)
        }
    }
}

@Composable
fun LlmSearchScreen(
    onBack: () -> Unit,
    viewModel: LlmSearchViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                Text("联网搜索", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        item {
            Text(
                "配置一个搜索服务并在智能体中开启「联网搜索」后，对话将注入 search_web（及 scrape_web）工具。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { viewModel.startCreate(SearchServiceOptions.TavilyOptions::class.java) }) { Text("+ Tavily") }
                TextButton(onClick = { viewModel.startCreate(SearchServiceOptions.ExaOptions::class.java) }) { Text("+ Exa") }
                TextButton(onClick = { viewModel.startCreate(SearchServiceOptions.BraveOptions::class.java) }) { Text("+ Brave") }
                TextButton(onClick = { viewModel.startCreate(SearchServiceOptions.SearXNGOptions::class.java) }) { Text("+ SearXNG") }
            }
        }

        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (state.providers.isEmpty()) {
            item {
                Text(
                    "还没有搜索服务。SearXNG 适合自托管（无需 API Key）；Tavily / Exa / Brave 需要 API Key。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(state.providers, key = { it.options.id }) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.startEdit(entry) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.options.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            describeOptions(entry.options),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (entry.selected) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                            Text(
                                "当前使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { v -> viewModel.toggleEnabled(entry, v) }
                    )
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { viewModel.delete(entry) }
                    )
                }
            }
            if (!entry.selected) {
                Text(
                    "设为当前使用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .clickable { viewModel.selectEntry(entry) }
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    state.editing?.let { editing ->
        SearchProviderEditor(
            options = editing,
            isNew = state.editingIsNew,
            viewModel = viewModel
        )
    }
}

@Composable
private fun SearchProviderEditor(
    options: SearchServiceOptions,
    isNew: Boolean,
    viewModel: LlmSearchViewModel
) {
    var enabled by remember { mutableStateOf(true) }
    var selected by remember(isNew) { mutableStateOf(isNew) }

    AlertDialog(
        onDismissRequest = viewModel::cancelEdit,
        title = { Text(if (isNew) "添加 ${options.displayName}" else "编辑 ${options.displayName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (options) {
                    is SearchServiceOptions.TavilyOptions -> {
                        OutlinedTextField(
                            value = options.apiKey,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.TavilyOptions).copy(apiKey = v) } },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = options.depth,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.TavilyOptions).copy(depth = v) } },
                            label = { Text("搜索深度（basic / advanced）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    is SearchServiceOptions.ExaOptions -> {
                        OutlinedTextField(
                            value = options.apiKey,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.ExaOptions).copy(apiKey = v) } },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    is SearchServiceOptions.BraveOptions -> {
                        OutlinedTextField(
                            value = options.apiKey,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.BraveOptions).copy(apiKey = v) } },
                            label = { Text("API Key（X-Subscription-Token）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    is SearchServiceOptions.SearXNGOptions -> {
                        OutlinedTextField(
                            value = options.url,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.SearXNGOptions).copy(url = v) } },
                            label = { Text("实例地址（如 https://searx.example.com）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = options.engines,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.SearXNGOptions).copy(engines = v) } },
                            label = { Text("引擎（逗号分隔，可留空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = options.language,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.SearXNGOptions).copy(language = v) } },
                            label = { Text("语言（如 zh-CN，可留空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = options.username,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.SearXNGOptions).copy(username = v) } },
                            label = { Text("用户名（Basic Auth，可留空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = options.password,
                            onValueChange = { v -> viewModel.updateEditing { o -> (o as SearchServiceOptions.SearXNGOptions).copy(password = v) } },
                            label = { Text("密码（可留空）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设为当前使用")
                    Switch(checked = selected, onCheckedChange = { selected = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.save(options, enabled, selected) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelEdit) { Text("取消") }
        }
    )
}

private fun describeOptions(options: SearchServiceOptions): String = when (options) {
    is SearchServiceOptions.TavilyOptions ->
        if (options.apiKey.isBlank()) "未配置 API Key" else "深度 ${options.depth}"
    is SearchServiceOptions.ExaOptions ->
        if (options.apiKey.isBlank()) "未配置 API Key" else "API Key 已配置"
    is SearchServiceOptions.BraveOptions ->
        if (options.apiKey.isBlank()) "未配置 API Key" else "API Key 已配置"
    is SearchServiceOptions.SearXNGOptions ->
        options.url.ifBlank { "未配置实例地址" }
}
