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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.cjhsy.linkzone.ai.core.MessageRole
import com.cjhsy.linkzone.data.files.SkillFrontmatterParser
import com.cjhsy.linkzone.data.model.LlmInjectionPosition
import com.cjhsy.linkzone.data.model.LlmLorebook
import com.cjhsy.linkzone.data.model.LlmLorebookEntry
import com.cjhsy.linkzone.core.error.toUserMessage
import com.cjhsy.linkzone.data.model.LlmQuickMessage
import com.cjhsy.linkzone.data.model.LlmSkill
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** AI 扩展管理页状态：技能 / 提示词（模式注入+世界书）/ 快捷指令 / 云端指令 */
data class LlmExtensionUiState(
    val skills: List<LlmSkill> = emptyList(),
    val prompts: List<LlmPrompt> = emptyList(),
    val lorebooks: List<LlmLorebook> = emptyList(),
    val quickMessages: List<LlmQuickMessage> = emptyList(),
    val cloudCommands: List<com.cjhsy.linkzone.data.model.CloudQuickCommand> = emptyList(),
    /** 非空时显示编辑表单（当前 tab 的编辑对象） */
    val editingSkill: LlmSkill? = null,
    val editingPrompt: LlmPrompt? = null,
    val editingLorebook: LlmLorebook? = null,
    val editingQuickMessage: LlmQuickMessage? = null,
    val editingCloudCommand: com.cjhsy.linkzone.data.model.CloudQuickCommand? = null,
    val message: String? = null,
    val error: String? = null
)

/** AI 扩展管理：SKILL.md 技能、模式注入、世界书（Lorebook）、快捷指令的 CRUD */
class LlmExtensionViewModel(
    private val app: android.content.Context,
    private val llmRepository: LlmRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LlmExtensionUiState())
    val state: StateFlow<LlmExtensionUiState> = _state.asStateFlow()

    init {
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
            llmRepository.observeCloudQuickCommands().collect { list ->
                _state.value = _state.value.copy(cloudCommands = list)
            }
        }
    }

    // ---- 技能 ----

    fun startCreateSkill() {
        _state.value = _state.value.copy(
            editingSkill = LlmSkill(
                content = "---\nname: \ndescription: \n---\n\n在这里写技能正文……"
            ),
            error = null
        )
    }

    fun startEditSkill(skill: LlmSkill) {
        _state.value = _state.value.copy(editingSkill = skill, error = null)
    }

    fun updateEditingSkill(transform: (LlmSkill) -> LlmSkill) {
        _state.value = _state.value.editingSkill?.let { cur ->
            _state.value.copy(editingSkill = transform(cur))
        } ?: _state.value
    }

    fun saveSkill() {
        val editing = _state.value.editingSkill ?: return
        val parsed = LlmSkill.fromContent(editing.content)
        if (parsed == null) {
            _state.value = _state.value.copy(
                error = "frontmatter 缺失或无效：需要 name 与 description 字段"
            )
            return
        }
        viewModelScope.launch {
            llmRepository.upsertSkill(editing.copy(name = parsed.name, description = parsed.description))
            _state.value = _state.value.copy(editingSkill = null, error = null)
        }
    }

    fun deleteSkill(skill: LlmSkill) {
        viewModelScope.launch { llmRepository.deleteSkill(skill.id) }
    }

    /** 从标准 skill 包（.zip/.skill，内含 SKILL.md + 子文件）导入为新技能 */
    fun importSkillPackage(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("无法读取所选文件")
                val pkg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.cjhsy.linkzone.data.files.SkillPackageImporter.parse(bytes)
                } ?: throw IllegalArgumentException("包内未找到 SKILL.md")
                val parsed = LlmSkill.fromContent(pkg.skillMd)
                    ?: throw IllegalArgumentException("SKILL.md frontmatter 缺少 name 或 description")
                llmRepository.upsertSkill(
                    parsed.copy(files = pkg.files),
                    binaryFiles = pkg.binaryFiles
                )
                _state.value = _state.value.copy(error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "导入失败：${e.toUserMessage("未知错误")}")
            }
        }
    }

    // ---- 模式注入 ----

    fun startCreatePrompt() {
        _state.value = _state.value.copy(editingPrompt = LlmPrompt(), error = null)
    }

    fun startEditPrompt(prompt: LlmPrompt) {
        _state.value = _state.value.copy(editingPrompt = prompt, error = null)
    }

    fun updateEditingPrompt(transform: (LlmPrompt) -> LlmPrompt) {
        _state.value = _state.value.editingPrompt?.let { cur ->
            _state.value.copy(editingPrompt = transform(cur))
        } ?: _state.value
    }

    fun savePrompt() {
        val editing = _state.value.editingPrompt ?: return
        if (editing.content.isBlank()) {
            _state.value = _state.value.copy(error = "注入内容不能为空")
            return
        }
        viewModelScope.launch {
            llmRepository.upsertPrompt(editing)
            _state.value = _state.value.copy(editingPrompt = null, error = null)
        }
    }

    fun deletePrompt(prompt: LlmPrompt) {
        viewModelScope.launch { llmRepository.deletePrompt(prompt.id) }
    }

    // ---- 世界书 ----

    fun startCreateLorebook() {
        _state.value = _state.value.copy(editingLorebook = LlmLorebook(), error = null)
    }

    fun startEditLorebook(book: LlmLorebook) {
        _state.value = _state.value.copy(editingLorebook = book, error = null)
    }

    fun updateEditingLorebook(transform: (LlmLorebook) -> LlmLorebook) {
        _state.value = _state.value.editingLorebook?.let { cur ->
            _state.value.copy(editingLorebook = transform(cur))
        } ?: _state.value
    }

    fun saveLorebook() {
        val editing = _state.value.editingLorebook ?: return
        viewModelScope.launch {
            llmRepository.upsertLorebook(editing)
            _state.value = _state.value.copy(editingLorebook = null, error = null)
        }
    }

    fun deleteLorebook(book: LlmLorebook) {
        viewModelScope.launch { llmRepository.deleteLorebook(book.id) }
    }

    // ---- 快捷指令 ----

    fun startCreateQuickMessage() {
        _state.value = _state.value.copy(editingQuickMessage = LlmQuickMessage(), error = null)
    }

    fun startEditQuickMessage(item: LlmQuickMessage) {
        _state.value = _state.value.copy(editingQuickMessage = item, error = null)
    }

    fun updateEditingQuickMessage(transform: (LlmQuickMessage) -> LlmQuickMessage) {
        _state.value = _state.value.editingQuickMessage?.let { cur ->
            _state.value.copy(editingQuickMessage = transform(cur))
        } ?: _state.value
    }

    fun saveQuickMessage() {
        val editing = _state.value.editingQuickMessage ?: return
        if (editing.title.isBlank() || editing.content.isBlank()) {
            _state.value = _state.value.copy(error = "标题和内容不能为空")
            return
        }
        viewModelScope.launch {
            llmRepository.upsertQuickMessage(editing)
            _state.value = _state.value.copy(editingQuickMessage = null, error = null)
        }
    }

    fun deleteQuickMessage(item: LlmQuickMessage) {
        viewModelScope.launch { llmRepository.deleteQuickMessage(item.id) }
    }

    // ---- 云端指令（云端助手主会话快捷命令，支持 JSON 导入导出） ----

    fun startCreateCloudCommand() {
        _state.value = _state.value.copy(
            editingCloudCommand = com.cjhsy.linkzone.data.model.CloudQuickCommand(),
            error = null
        )
    }

    fun startEditCloudCommand(item: com.cjhsy.linkzone.data.model.CloudQuickCommand) {
        _state.value = _state.value.copy(editingCloudCommand = item, error = null)
    }

    fun updateEditingCloudCommand(
        transform: (com.cjhsy.linkzone.data.model.CloudQuickCommand) -> com.cjhsy.linkzone.data.model.CloudQuickCommand
    ) {
        _state.value = _state.value.editingCloudCommand?.let { cur ->
            _state.value.copy(editingCloudCommand = transform(cur))
        } ?: _state.value
    }

    fun saveCloudCommand() {
        val editing = _state.value.editingCloudCommand ?: return
        if (editing.title.isBlank() || editing.content.isBlank()) {
            _state.value = _state.value.copy(error = "标题和内容不能为空")
            return
        }
        viewModelScope.launch {
            llmRepository.upsertCloudQuickCommand(editing)
            _state.value = _state.value.copy(editingCloudCommand = null, error = null)
        }
    }

    fun deleteCloudCommand(item: com.cjhsy.linkzone.data.model.CloudQuickCommand) {
        viewModelScope.launch { llmRepository.deleteCloudQuickCommand(item.id) }
    }

    /** 云端指令导出文件格式（type 字段用于导入校验；commands 内只保留 title/content/id） */
    fun exportCloudCommandsJson(): String {
        val items = _state.value.cloudCommands
        val inner = items.joinToString(",") { c ->
            "{\"id\":${jsonStr(c.id)},\"title\":${jsonStr(c.title)},\"content\":${jsonStr(c.content)}}"
        }
        return "{\"app\":\"linkzone\",\"type\":\"cloud_quick_commands\",\"count\":${items.size},\"commands\":[$inner]}"
    }

    /** 从导出 JSON 文本导入云端指令：保留原 id（同 id 覆盖），新增排序按文件顺序 */
    fun importCloudCommandsJson(text: String) {
        viewModelScope.launch {
            try {
                val parsed = cloudCommandsJson.decodeFromString<CloudCommandsExport>(text)
                if (parsed.type != "cloud_quick_commands") {
                    throw IllegalArgumentException("不是云端指令导出文件")
                }
                if (parsed.commands.isEmpty()) throw IllegalArgumentException("文件内没有指令")
                var sort = System.currentTimeMillis()
                parsed.commands.forEach { c ->
                    if (c.title.isBlank() || c.content.isBlank()) return@forEach
                    llmRepository.upsertCloudQuickCommand(
                        com.cjhsy.linkzone.data.model.CloudQuickCommand(
                            id = c.id.ifBlank { java.util.UUID.randomUUID().toString() },
                            title = c.title,
                            content = c.content,
                            createdAt = sort++
                        )
                    )
                }
                _state.value = _state.value.copy(
                    message = "已导入 ${parsed.commands.size} 条云端指令",
                    error = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "导入失败：${e.toUserMessage("文件格式不正确")}")
            }
        }
    }

    fun importCloudCommands(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取所选文件")
                importCloudCommandsJson(text)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "导入失败：${e.toUserMessage("未知错误")}")
            }
        }
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 关闭所有编辑表单 */
    fun clearEdit() {
        _state.value = _state.value.copy(
            editingSkill = null,
            editingPrompt = null,
            editingLorebook = null,
            editingQuickMessage = null,
            editingCloudCommand = null,
            error = null
        )
    }
}

/** 云端指令导入导出文件结构 */
@kotlinx.serialization.Serializable
private data class CloudCommandsExport(
    val app: String = "linkzone",
    val type: String = "",
    val count: Int = 0,
    val commands: List<CloudCommandItem> = emptyList()
)

@kotlinx.serialization.Serializable
private data class CloudCommandItem(
    val id: String = "",
    val title: String = "",
    val content: String = ""
)

private val cloudCommandsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private val POSITION_LABELS = mapOf(
    LlmInjectionPosition.BEFORE_SYSTEM_PROMPT to "系统提示前",
    LlmInjectionPosition.AFTER_SYSTEM_PROMPT to "系统提示后",
    LlmInjectionPosition.TOP_OF_CHAT to "对话开头",
    LlmInjectionPosition.BOTTOM_OF_CHAT to "最新消息前",
    LlmInjectionPosition.AT_DEPTH to "指定深度"
)

@Composable
fun LlmExtensionScreen(
    onBack: () -> Unit,
    viewModel: LlmExtensionViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI 扩展", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("返回") }
        }

        TabRow(selectedTabIndex = tab) {
            listOf("技能", "提示词注入", "快捷指令", "云端指令").forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label) }
                )
            }
        }

        when (tab) {
            0 -> SkillsTab(state, viewModel)
            1 -> PromptsTab(state, viewModel)
            2 -> QuickMessagesTab(state, viewModel)
            else -> CloudCommandsTab(state, viewModel)
        }
    }

    // 编辑表单
    state.editingSkill?.let { editing ->
        SkillEditor(editing, viewModel)
    }
    state.editingPrompt?.let { editing ->
        PromptEditor(editing, viewModel)
    }
    state.editingLorebook?.let { editing ->
        LorebookEditor(editing, viewModel)
    }
    state.editingQuickMessage?.let { editing ->
        QuickMessageEditor(editing, viewModel)
    }
    state.editingCloudCommand?.let { editing ->
        CloudCommandEditor(editing, viewModel)
    }
}

// ==================== 技能 ====================

@Composable
private fun SkillsTab(state: LlmExtensionUiState, viewModel: LlmExtensionViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::startCreateSkill) { Text("+ 新建技能（SKILL.md）") }
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::importSkillPackage) }
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) { Text("导入 .zip 技能包") }
            }
        }
        item {
            Text(
                "技能是一段可被模型按需加载的专项指令（Agent Skills 规范）。标准技能包为多文件结构：SKILL.md + scripts/references 等子文件，模型按需加载。创建后在智能体编辑页启用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        items(state.skills, key = { it.id }) { skill ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.startEditSkill(skill) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    if (skill.files.isNotEmpty()) {
                        Text(
                            "${skill.files.size} 个子文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "长按删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable { viewModel.deleteSkill(skill) }
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SkillEditor(skill: LlmSkill, viewModel: LlmExtensionViewModel) {
    val fm = SkillFrontmatterParser.parse(skill.content)
    // 当前选中的子文件路径；null = 编辑 SKILL.md 正文
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var newFileMode by remember { mutableStateOf(false) }
    var newPath by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = viewModel::clearEdit,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "技能：${fm.name ?: "（name 缺失）"}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    TextButton(onClick = viewModel::clearEdit) { Text("取消") }
                    TextButton(onClick = {
                        localError = null
                        viewModel.saveSkill()
                    }) { Text("保存") }
                }
                // 文件标签栏：SKILL.md / 各子文件 / 新增
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileChip("SKILL.md", selectedFile == null && !newFileMode) {
                        selectedFile = null; newFileMode = false
                    }
                    skill.files.keys.sorted().forEach { path ->
                        FileChip(path, selectedFile == path && !newFileMode) {
                            selectedFile = path; newFileMode = false
                        }
                    }
                    FileChip("＋ 新增子文件", newFileMode) {
                        newFileMode = true; selectedFile = null; localError = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
                when {
                    newFileMode -> {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            OutlinedTextField(
                                value = newPath,
                                onValueChange = { newPath = it },
                                label = { Text("子文件相对路径，如 scripts/parse.py") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "输入路径后点确认，即可在列表中打开并编辑内容。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val norm = LlmSkill.normalizePath(newPath)
                                    when {
                                        norm == null -> localError = "路径非法（不允许越界访问）"
                                        norm.equals("SKILL.md", ignoreCase = true) ->
                                            localError = "SKILL.md 为主文件，不能作为子文件路径"
                                        skill.files.containsKey(norm) -> localError = "该文件已存在"
                                        else -> {
                                            viewModel.updateEditingSkill {
                                                it.copy(files = it.files + (norm to ""))
                                            }
                                            selectedFile = norm
                                            newFileMode = false
                                            newPath = ""
                                            localError = null
                                        }
                                    }
                                }) { Text("确认创建") }
                                TextButton(onClick = { newFileMode = false; newPath = "" }) { Text("取消") }
                            }
                        }
                    }
                    selectedFile == null -> {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Text(
                                "description: ${(fm.description ?: "（缺失）").take(60)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = skill.content,
                                onValueChange = { v -> viewModel.updateEditingSkill { it.copy(content = v) } },
                                label = { Text("SKILL.md 全文（frontmatter: name / description）") },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                    else -> {
                        val path = selectedFile!!
                        val fileContent = skill.files[path].orEmpty()
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(path, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    viewModel.updateEditingSkill { it.copy(files = it.files - path) }
                                    selectedFile = null
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = fileContent,
                                onValueChange = { v ->
                                    viewModel.updateEditingSkill {
                                        it.copy(files = it.files + (path to v))
                                    }
                                },
                                label = { Text("文件内容") },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// ==================== 提示词注入 ====================

@Composable
private fun PromptsTab(state: LlmExtensionUiState, viewModel: LlmExtensionViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::startCreatePrompt) { Text("+ 模式注入") }
                TextButton(onClick = viewModel::startCreateLorebook) { Text("+ 世界书") }
            }
        }
        item {
            Text(
                "模式注入按开关注入；世界书按关键词触发。均可按位置（系统提示前/后、对话开头/结尾、指定深度）注入，并在智能体编辑页关联。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- 模式注入 ----
        if (state.prompts.isNotEmpty()) {
            item { Text("模式注入", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(state.prompts, key = { "p-${it.id}" }) { prompt ->
                InjectionCard(
                    title = prompt.name.ifBlank { "未命名" },
                    subtitle = prompt.content,
                    tags = buildList {
                        add(POSITION_LABELS[prompt.position] ?: prompt.position.name)
                        add("优先级 ${prompt.priority}")
                        if (!prompt.enabled) add("已停用")
                    },
                    onEdit = { viewModel.startEditPrompt(prompt) },
                    onDelete = { viewModel.deletePrompt(prompt) }
                )
            }
        }

        // ---- 世界书 ----
        if (state.lorebooks.isNotEmpty()) {
            item { Text("世界书", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(state.lorebooks, key = { "l-${it.id}" }) { book ->
                InjectionCard(
                    title = book.name.ifBlank { "未命名世界书" },
                    subtitle = book.description.ifBlank { "${book.entries.size} 个条目" },
                    tags = buildList {
                        add("${book.entries.size} 条目")
                        if (!book.enabled) add("已停用")
                    },
                    onEdit = { viewModel.startEditLorebook(book) },
                    onDelete = { viewModel.deleteLorebook(book) }
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun InjectionCard(
    title: String,
    subtitle: String,
    tags: List<String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Text(
                "删除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onDelete)
            )
        }
    }
}

@Composable
private fun PromptEditor(prompt: LlmPrompt, viewModel: LlmExtensionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::clearEdit,
        title = { Text("编辑模式注入") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = prompt.name,
                    onValueChange = { v -> viewModel.updateEditingPrompt { it.copy(name = v) } },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用")
                    Switch(
                        checked = prompt.enabled,
                        onCheckedChange = { v -> viewModel.updateEditingPrompt { it.copy(enabled = v) } }
                    )
                }
                OutlinedTextField(
                    value = prompt.priority.toString(),
                    onValueChange = { v -> viewModel.updateEditingPrompt { it.copy(priority = v.toIntOrNull() ?: 0) } },
                    label = { Text("优先级（大者优先）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("注入位置", style = MaterialTheme.typography.titleSmall)
                ChipSelector(
                    options = LlmInjectionPosition.entries,
                    labelOf = { POSITION_LABELS[it] ?: it.name },
                    selected = prompt.position,
                    onSelect = { p -> viewModel.updateEditingPrompt { it.copy(position = p) } }
                )
                if (prompt.position == LlmInjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = prompt.injectDepth.toString(),
                        onValueChange = { v -> viewModel.updateEditingPrompt { it.copy(injectDepth = v.toIntOrNull() ?: 4) } },
                        label = { Text("深度（1 = 最新消息之前）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Text("注入角色", style = MaterialTheme.typography.titleSmall)
                ChipSelector(
                    options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                    labelOf = { if (it == MessageRole.ASSISTANT) "助手" else "用户" },
                    selected = prompt.role,
                    onSelect = { r -> viewModel.updateEditingPrompt { it.copy(role = r) } }
                )
                OutlinedTextField(
                    value = prompt.content,
                    onValueChange = { v -> viewModel.updateEditingPrompt { it.copy(content = v) } },
                    label = { Text("注入内容") },
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::savePrompt) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::clearEdit) { Text("取消") } }
    )
}

@Composable
private fun LorebookEditor(book: LlmLorebook, viewModel: LlmExtensionViewModel) {
    var editingEntry by remember(book.id, book.entries.size) { mutableStateOf<LlmLorebookEntry?>(null) }

    AlertDialog(
        onDismissRequest = viewModel::clearEdit,
        title = { Text("编辑世界书") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = book.name,
                    onValueChange = { v -> viewModel.updateEditingLorebook { it.copy(name = v) } },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = book.description,
                    onValueChange = { v -> viewModel.updateEditingLorebook { it.copy(description = v) } },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用")
                    Switch(
                        checked = book.enabled,
                        onCheckedChange = { v -> viewModel.updateEditingLorebook { it.copy(enabled = v) } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("条目（${book.entries.size}）", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        viewModel.updateEditingLorebook { it.copy(entries = it.entries + LlmLorebookEntry()) }
                    }) { Text("+ 添加") }
                }
                book.entries.forEachIndexed { index, entry ->
                    val triggered = if (entry.constantActive) "常驻" else entry.keywords.joinToString("/").take(30)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editingEntry = entry },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.name.ifBlank { "条目 ${index + 1}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    triggered.ifBlank { "无关键词" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Text(
                                "删除",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable {
                                    viewModel.updateEditingLorebook { b ->
                                        b.copy(entries = b.entries.filterNot { it.id == entry.id })
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveLorebook) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::clearEdit) { Text("取消") } }
    )

    editingEntry?.let { entry ->
        LorebookEntryEditor(
            entry = entry,
            onUpdate = { updated ->
                viewModel.updateEditingLorebook { b ->
                    b.copy(entries = b.entries.map { if (it.id == updated.id) updated else it })
                }
                editingEntry = null
            },
            onDismiss = { editingEntry = null }
        )
    }
}

@Composable
private fun LorebookEntryEditor(
    entry: LlmLorebookEntry,
    onUpdate: (LlmLorebookEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var newKeyword by remember { mutableStateOf("") }
    var local by remember(entry.id) { mutableStateOf(entry) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑条目") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = local.name,
                    onValueChange = { v -> local = local.copy(name = v) },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("常驻激活（免关键词）")
                    Switch(
                        checked = local.constantActive,
                        onCheckedChange = { v -> local = local.copy(constantActive = v) }
                    )
                }
                if (!local.constantActive) {
                    Text("触发关键词", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newKeyword,
                            onValueChange = { newKeyword = it },
                            label = { Text("新关键词") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        TextButton(onClick = {
                            if (newKeyword.isNotBlank()) {
                                local = local.copy(keywords = local.keywords + newKeyword.trim())
                                newKeyword = ""
                            }
                        }) { Text("添加") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        local.keywords.take(6).forEach { keyword ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                modifier = Modifier.clickable {
                                    local = local.copy(keywords = local.keywords - keyword)
                                }
                            ) {
                                Text(
                                    "$keyword ×",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正则", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = local.useRegex,
                                onCheckedChange = { v -> local = local.copy(useRegex = v) },
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("区分大小写", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = local.caseSensitive,
                                onCheckedChange = { v -> local = local.copy(caseSensitive = v) },
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = local.scanDepth.toString(),
                        onValueChange = { v -> local = local.copy(scanDepth = v.toIntOrNull() ?: 4) },
                        label = { Text("扫描最近 N 条消息") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                Text("注入配置", style = MaterialTheme.typography.titleSmall)
                ChipSelector(
                    options = LlmInjectionPosition.entries,
                    labelOf = { POSITION_LABELS[it] ?: it.name },
                    selected = local.position,
                    onSelect = { p -> local = local.copy(position = p) }
                )
                OutlinedTextField(
                    value = local.priority.toString(),
                    onValueChange = { v -> local = local.copy(priority = v.toIntOrNull() ?: 0) },
                    label = { Text("优先级") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (local.position == LlmInjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = local.injectDepth.toString(),
                        onValueChange = { v -> local = local.copy(injectDepth = v.toIntOrNull() ?: 4) },
                        label = { Text("深度") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                ChipSelector(
                    options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                    labelOf = { if (it == MessageRole.ASSISTANT) "助手" else "用户" },
                    selected = local.role,
                    onSelect = { r -> local = local.copy(role = r) }
                )
                OutlinedTextField(
                    value = local.content,
                    onValueChange = { v -> local = local.copy(content = v) },
                    label = { Text("注入内容") },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUpdate(local) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 快捷指令 ====================

@Composable
private fun QuickMessagesTab(state: LlmExtensionUiState, viewModel: LlmExtensionViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            TextButton(onClick = viewModel::startCreateQuickMessage) { Text("+ 新建快捷指令") }
        }
        if (state.quickMessages.isEmpty()) {
            item {
                Text(
                    "快捷指令是一段可一键填入聊天输入框的常用文本，在智能体编辑页按智能体关联（仅本地会话）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(state.quickMessages, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.startEditQuickMessage(item) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            item.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { viewModel.deleteQuickMessage(item) }
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun QuickMessageEditor(item: LlmQuickMessage, viewModel: LlmExtensionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::clearEdit,
        title = { Text("编辑快捷指令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = item.title,
                    onValueChange = { v -> viewModel.updateEditingQuickMessage { it.copy(title = v) } },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = item.content,
                    onValueChange = { v -> viewModel.updateEditingQuickMessage { it.copy(content = v) } },
                    label = { Text("填入输入框的内容") },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveQuickMessage) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::clearEdit) { Text("取消") } }
    )
}

// ==================== 云端指令 ====================

@Composable
private fun CloudCommandsTab(state: LlmExtensionUiState, viewModel: LlmExtensionViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importCloudCommands) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::startCreateCloudCommand) { Text("+ 新建") }
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) { Text("导入") }
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                val file = java.io.File(dir, "cloud_commands_${System.currentTimeMillis()}.json")
                                file.writeText(viewModel.exportCloudCommandsJson())
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, "导出云端指令")
                                )
                            }.onFailure { e ->
                                android.widget.Toast.makeText(
                                    context, "导出失败：${e.toUserMessage("未知错误")}", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = state.cloudCommands.isNotEmpty()
                ) { Text("导出") }
            }
        }
        if (state.cloudCommands.isEmpty()) {
            item {
                Text(
                    "云端指令显示在云端助手对话输入框上方，点击即填入，适合存放常用插件命令。支持 JSON 导入导出，可与本地智能体的快捷指令互不影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.message?.let {
            item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        state.error?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        items(state.cloudCommands, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.startEditCloudCommand(item) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            item.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { viewModel.deleteCloudCommand(item) }
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CloudCommandEditor(
    item: com.cjhsy.linkzone.data.model.CloudQuickCommand,
    viewModel: LlmExtensionViewModel
) {
    AlertDialog(
        onDismissRequest = viewModel::clearEdit,
        title = { Text("编辑云端指令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = item.title,
                    onValueChange = { v -> viewModel.updateEditingCloudCommand { it.copy(title = v) } },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = item.content,
                    onValueChange = { v -> viewModel.updateEditingCloudCommand { it.copy(content = v) } },
                    label = { Text("填入输入框的内容（如插件命令）") },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveCloudCommand) { Text("保存") } },
        dismissButton = { TextButton(onClick = viewModel::clearEdit) { Text("取消") } }
    )
}

// ==================== 通用 ====================

@Composable
private fun <T> ChipSelector(
    options: List<T>,
    labelOf: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    labelOf(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
