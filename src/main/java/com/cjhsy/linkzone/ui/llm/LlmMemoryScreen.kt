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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.cjhsy.linkzone.data.model.AssistantMemory
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class LlmMemoryUiState(
    val agents: List<LlmAgent> = emptyList(),
    /** null = 全局共享记忆 */
    val selectedAgentId: String? = null,
    val memories: List<AssistantMemory> = emptyList(),
    /** 非空时弹出新增/编辑对话框 */
    val editing: AssistantMemory? = null,
    val error: String? = null
)

/**
 * 长期记忆管理：全局记忆（__global__）与各智能体记忆的增删改。
 * 记忆注入与 memory_tool 的运行时见 ChatViewModel + buildMemoryTools。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmMemoryViewModel(
    private val llmRepository: LlmRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LlmMemoryUiState())
    val state: StateFlow<LlmMemoryUiState> = _state.asStateFlow()

    private val selectedAgentId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            llmRepository.observeAgents().collect { list ->
                _state.value = _state.value.copy(agents = list)
            }
        }
        viewModelScope.launch {
            selectedAgentId.flatMapLatest { agentId ->
                llmRepository.observeMemories(agentId ?: LlmRepository.MEMORY_GLOBAL_ID)
            }.collectLatest { list ->
                _state.value = _state.value.copy(memories = list)
            }
        }
    }

    fun selectScope(agentId: String?) {
        selectedAgentId.value = agentId
        _state.value = _state.value.copy(selectedAgentId = agentId)
    }

    fun startCreate() {
        _state.value = _state.value.copy(editing = AssistantMemory(id = 0, content = ""), error = null)
    }

    fun startEdit(memory: AssistantMemory) {
        _state.value = _state.value.copy(editing = memory, error = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null, error = null)
    }

    fun save(content: String) {
        if (content.isBlank()) {
            _state.value = _state.value.copy(error = "内容不能为空")
            return
        }
        val editing = _state.value.editing ?: return
        viewModelScope.launch {
            if (editing.id == 0) {
                val agentId = _state.value.selectedAgentId ?: LlmRepository.MEMORY_GLOBAL_ID
                llmRepository.addMemory(agentId, content.trim())
            } else {
                llmRepository.updateMemoryContent(editing.id, content.trim())
            }
            _state.value = _state.value.copy(editing = null, error = null)
        }
    }

    fun delete(memory: AssistantMemory) {
        viewModelScope.launch { llmRepository.deleteMemory(memory.id) }
    }
}

@Composable
fun LlmMemoryScreen(
    onBack: () -> Unit,
    viewModel: LlmMemoryViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("记忆", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("返回") }
        }

        // 作用域选择：全局 / 各智能体
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScopeChip(
                label = "全局记忆",
                selected = state.selectedAgentId == null,
                onClick = { viewModel.selectScope(null) }
            )
            state.agents.forEach { agent ->
                ScopeChip(
                    label = agent.name.ifBlank { "未命名" },
                    selected = state.selectedAgentId == agent.id,
                    onClick = { viewModel.selectScope(agent.id) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item {
                TextButton(onClick = viewModel::startCreate) { Text("+ 添加记忆") }
            }
            state.error?.let {
                item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Text(
                    if (state.selectedAgentId == null)
                        "全局记忆对所有开启「使用全局记忆」的智能体可见；智能体记忆仅该智能体可见。开启记忆后，模型可通过 memory_tool 自主增删改。"
                    else
                        "该智能体专属记忆（需在智能体编辑页开启「启用记忆」）。开启记忆后，模型可通过 memory_tool 自主增删改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.memories.isEmpty()) {
                item {
                    Text(
                        "暂无记忆条目。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.memories, key = { it.id }) { memory ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.startEdit(memory) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "#${memory.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            memory.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable { viewModel.delete(memory) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    state.editing?.let { editing ->
        MemoryEditor(
            initial = editing.content,
            isNew = editing.id == 0,
            onConfirm = viewModel::save,
            onDismiss = viewModel::cancelEdit
        )
    }
}

@Composable
private fun MemoryEditor(
    initial: String,
    isNew: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加记忆" else "编辑记忆") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("记忆内容（一条事实/偏好）") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(content) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ScopeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            (if (selected) "✓ " else "") + label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
