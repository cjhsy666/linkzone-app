package com.cjhsy.linkzone.ui.llm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.cjhsy.linkzone.ai.provider.BuiltInTools
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ModelAbility
import com.cjhsy.linkzone.ai.provider.ModelType
import com.cjhsy.linkzone.ai.provider.Modality
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.registry.ModelRegistry

/** registry 对某个 modelId 的识别结果 */
data class RecognizedModel(
    val known: Boolean,
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
    val abilities: List<ModelAbility>,
)

/** 调用内置模型注册表识别模型的默认模态与能力（与 Rikka ModelSettingsForm.setModelId 同步） */
fun recognizeModelId(modelId: String): RecognizedModel {
    val id = modelId.trim()
    if (id.isBlank()) {
        return RecognizedModel(false, listOf(Modality.TEXT), listOf(Modality.TEXT), emptyList())
    }
    val input = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
    val output = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
    val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
    // registry 未收录时：input 兜底为 TEXT+IMAGE、abilities 为空（工具默认开启的语义见 supportsTools）
    val known = abilities.isNotEmpty() || input == listOf(Modality.TEXT)
    return RecognizedModel(known, input, output, abilities)
}

/** 依据识别结果推断模型类型（embedding 由拉取接口直接给出，此处只补图片生成模型） */
fun inferModelType(modelId: String, outputModalities: List<Modality>): ModelType {
    val lower = modelId.lowercase()
    val imageIdHints = listOf("image", "dall-e", "dall_e", "imagen", "flux", "sdxl", "stable-diffusion")
    return if (Modality.IMAGE in outputModalities && imageIdHints.any { it in lower }) {
        ModelType.IMAGE
    } else {
        ModelType.CHAT
    }
}

/**
 * 服务商模型管理页：搜索 / 拉取（与本地合并）/ 手动添加（实时识别）/ 排序 / 删除 / 编辑能力。
 */
@Composable
fun ProviderModelsPage(
    provider: ProviderSetting,
    state: LlmProviderUiState,
    viewModel: LlmProviderViewModel,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 非空时打开模型编辑 Sheet；新建与编辑共用（id 是否已存在于 provider 决定是哪一种）
    var editingDraft by remember { mutableStateOf<Model?>(null) }
    // 非空时弹出删除确认
    var pendingDelete by remember { mutableStateOf<Model?>(null) }

    editingDraft?.let { draft ->
        ModelEditorSheet(
            initial = draft,
            isNew = provider.models.none { it.id == draft.id },
            onDismiss = { editingDraft = null },
            onConfirm = { saved ->
                viewModel.saveModel(provider, saved)
                editingDraft = null
            },
        )
    }

    val keyword = query.trim()
    val shown = if (keyword.isBlank()) {
        provider.models
    } else {
        provider.models.filter {
            it.modelId.contains(keyword, ignoreCase = true) ||
                it.displayName.contains(keyword, ignoreCase = true)
        }
    }
    val fetching = state.fetchingModelsFor == provider.id

    LazyColumn(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text("模型", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { viewModel.fetchModels(provider) },
                    enabled = !fetching,
                ) {
                    if (fetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).width(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "拉取模型")
                    }
                }
                IconButton(onClick = { editingDraft = Model() }) {
                    Icon(Icons.Filled.Add, contentDescription = "手动添加模型")
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索模型 ID 或名称") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }

        state.message?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (provider.models.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "暂无模型",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "点右上角 ⟳ 从服务拉取，或 + 手动添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    "没有匹配「$keyword」的模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }

        items(shown, key = { it.id }) { model ->
            val index = provider.models.indexOf(model)
            ModelManageRow(
                model = model,
                index = index,
                total = provider.models.size,
                onClick = { editingDraft = model.copy() },
                onMoveUp = { viewModel.moveModel(provider, index, index - 1) },
                onMoveDown = { viewModel.moveModel(provider, index, index + 1) },
                onDelete = { pendingDelete = model },
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    pendingDelete?.let { model ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型") },
            text = {
                Text("确定删除「${model.displayName.ifBlank { model.modelId }}」吗？删除后对话中若仍选择该模型将无法调用。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeModel(provider, model)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 拉取结果勾选 Sheet（对齐 RikkaHub）：已在用模型默认勾中，新模型默认不勾，
 * 确认后才写回服务商配置；本地手动添加/远端已下线的模型不在此列、不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchModelsSelectionSheet(
    fetched: List<Model>,
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Model>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var checkedIds by remember {
        mutableStateOf(fetched.mapNotNull { m -> m.modelId.takeIf { it in existingIds } }.toSet())
    }
    val keyword = query.trim()
    val shown = if (keyword.isBlank()) fetched else fetched.filter {
        it.modelId.contains(keyword, ignoreCase = true) || it.displayName.contains(keyword, ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                "选择要保留的模型（${checkedIds.size}/${fetched.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "未勾选的模型不会加入服务商配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索模型 ID 或名称") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    checkedIds = checkedIds + shown.map { it.modelId }
                }) { Text("全选") }
                TextButton(onClick = {
                    checkedIds = checkedIds - shown.map { it.modelId }.toSet()
                }) { Text("全不选") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            ) {
                items(shown, key = { it.modelId }) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                checkedIds = if (model.modelId in checkedIds) {
                                    checkedIds - model.modelId
                                } else {
                                    checkedIds + model.modelId
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = model.modelId in checkedIds,
                            onCheckedChange = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.displayName.ifBlank { model.modelId },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (model.displayName.isNotBlank() && model.displayName != model.modelId) {
                                Text(
                                    model.modelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(fetched.filter { it.modelId in checkedIds }) },
                ) { Text("确定（${checkedIds.size}）") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelManageRow(
    model: Model,
    index: Int,
    total: Int,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val vision = Modality.IMAGE in model.inputModalities
    val tools = ModelAbility.TOOL in model.abilities || model.abilities.isEmpty()
    val reasoning = ModelAbility.REASONING in model.abilities
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = model.displayName.ifBlank { model.modelId },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    ModelMiniChip(typeLabel(model.type))
                    if (model.type == ModelType.CHAT) {
                        if (vision) ModelMiniChip("📷 视觉")
                        if (tools) ModelMiniChip("🔧 工具")
                        if (reasoning) ModelMiniChip("🧠 推理")
                    }
                    if (model.tools.isNotEmpty()) ModelMiniChip("🌐 ${model.tools.size} 个内置工具")
                }
            }
            IconButton(
                onClick = onMoveUp,
                enabled = index > 0,
                modifier = Modifier.height(32.dp).width(32.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(
                onClick = onMoveDown,
                enabled = index < total - 1,
                modifier = Modifier.height(32.dp).width(32.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.height(32.dp).width(32.dp),
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModelMiniChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun typeLabel(type: ModelType): String = when (type) {
    ModelType.CHAT -> "对话"
    ModelType.IMAGE -> "图片生成"
    ModelType.EMBEDDING -> "向量"
}

/**
 * 模型编辑/添加 Sheet。对齐 Rikka ModelSettingsForm：
 * modelId 输入时实时经 registry 识别模态与能力，可手动覆盖；CHAT 模型另可配置内置工具。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelEditorSheet(
    initial: Model,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Model) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var modelId by remember { mutableStateOf(initial.modelId) }
    var displayName by remember { mutableStateOf(initial.displayName) }
    var displayTouched by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initial.type) }
    var inputModalities by remember { mutableStateOf(initial.inputModalities.toSet()) }
    var outputModalities by remember { mutableStateOf(initial.outputModalities.toSet()) }
    var abilities by remember { mutableStateOf(initial.abilities.toSet()) }
    var builtInTools by remember { mutableStateOf(initial.tools) }

    // 新建时输入 modelId 实时识别；displayName 未手动编辑时跟随 id
    val recognized = remember(modelId) { recognizeModelId(modelId) }
    fun applyRecognition() {
        val rec = recognizeModelId(modelId)
        inputModalities = rec.inputModalities.toSet()
        outputModalities = rec.outputModalities.toSet()
        abilities = rec.abilities.toSet()
        type = inferModelType(modelId, rec.outputModalities)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNew) "添加模型" else "编辑模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            // 模型 ID
            OutlinedTextField(
                value = modelId,
                onValueChange = { v ->
                    modelId = v.trimStart()
                    if (!displayTouched) displayName = modelId
                },
                label = { Text("模型 ID") },
                placeholder = { Text("如 gpt-4o-mini、gemini-2.5-flash") },
                singleLine = true,
                enabled = isNew,
                supportingText = if (!isNew) ({ Text("已添加模型的 ID 不可修改，能力调整不影响调用") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )

            // 识别状态
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (modelId.isBlank()) {
                            Text(
                                "输入模型 ID 后自动识别默认能力",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (recognized.known) {
                            Text("✓ 已识别", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                text = buildList {
                                    if (Modality.IMAGE in recognized.inputModalities) add("视觉输入")
                                    if (ModelAbility.TOOL in recognized.abilities) add("工具调用")
                                    if (ModelAbility.REASONING in recognized.abilities) add("深度思考")
                                    if (isEmpty()) add("纯文本对话")
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("未收录模型", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "使用通用默认，可在下方手动调整",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!isNew) {
                        TextButton(onClick = { applyRecognition() }) { Text("重新识别") }
                    }
                }
            }

            // 显示名称
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    displayTouched = true
                },
                label = { Text("显示名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 模型类型
            SegmentSection(label = "模型类型") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val types = listOf(
                        ModelType.CHAT to "对话",
                        ModelType.IMAGE to "图片生成",
                        ModelType.EMBEDDING to "向量",
                    )
                    types.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = type == value,
                            onClick = { type = value },
                            shape = SegmentedButtonDefaults.itemShape(index, types.size),
                        ) { Text(label) }
                    }
                }
            }

            SegmentSection(label = "输入内容") {
                MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modalityChoices().forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            checked = value in inputModalities,
                            onCheckedChange = { checked ->
                                inputModalities = inputModalities.toggle(value, checked)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }

            SegmentSection(label = "输出内容") {
                MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modalityChoices().forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            checked = value in outputModalities,
                            onCheckedChange = { checked ->
                                outputModalities = outputModalities.toggle(value, checked)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }

            if (type == ModelType.CHAT) {
                SegmentSection(label = "模型能力") {
                    MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val abilityOptions = listOf(
                            ModelAbility.TOOL to "工具调用",
                            ModelAbility.REASONING to "深度思考",
                        )
                        abilityOptions.forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                checked = value in abilities,
                                onCheckedChange = { checked ->
                                    abilities = abilities.toggle(value, checked)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, abilityOptions.size),
                            ) { Text(label) }
                        }
                    }
                }

                // 内置工具（协议层 provider 已支持，按服务商实际能力生效）
                SegmentSection(label = "内置工具") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BuiltInToolToggle(
                            title = "联网搜索",
                            supporting = "Gemini/OpenAI/Claude 服务端搜索，需模型支持",
                            checked = BuiltInTools.Search in builtInTools,
                        ) { checked ->
                            builtInTools = builtInTools.toggle(BuiltInTools.Search, checked)
                        }
                        BuiltInToolToggle(
                            title = "URL 上下文",
                            supporting = "让模型读取指定网页内容（Gemini）",
                            checked = BuiltInTools.UrlContext in builtInTools,
                        ) { checked ->
                            builtInTools = builtInTools.toggle(BuiltInTools.UrlContext, checked)
                        }
                        BuiltInToolToggle(
                            title = "图片生成",
                            supporting = "对话中按需生成图片（Gemini/OpenAI）",
                            checked = BuiltInTools.ImageGeneration in builtInTools,
                        ) { checked ->
                            builtInTools = builtInTools.toggle(BuiltInTools.ImageGeneration, checked)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val finalName = displayName.ifBlank { modelId }
                        onConfirm(
                            initial.copy(
                                modelId = modelId.trim(),
                                displayName = finalName.trim(),
                                type = type,
                                inputModalities = inputModalities.toList().ifEmpty { listOf(Modality.TEXT) },
                                outputModalities = outputModalities.toList().ifEmpty { listOf(Modality.TEXT) },
                                abilities = abilities.toList(),
                                tools = builtInTools,
                            )
                        )
                    },
                    enabled = modelId.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun SegmentSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun BuiltInToolToggle(
    title: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun modalityChoices(): List<Pair<Modality, String>> = listOf(
    Modality.TEXT to "文本",
    Modality.IMAGE to "图片",
)

private fun <T> Set<T>.toggle(item: T, add: Boolean): Set<T> =
    if (add) this + item else this - item
