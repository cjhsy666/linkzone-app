package com.cjhsy.linkzone.ui.translate

import com.cjhsy.linkzone.core.error.toUserMessage

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.ai.provider.ModelType
import com.cjhsy.linkzone.core.storage.LlmPrefs
import com.cjhsy.linkzone.data.ai.TranslationHandler
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 翻译目标语言（display 为界面名，code 传给翻译提示词 / Qwen MT translation_options） */
data class TargetLanguage(val display: String, val code: String)

val TARGET_LANGUAGES = listOf(
    TargetLanguage("英语", "English"),
    TargetLanguage("日语", "Japanese"),
    TargetLanguage("韩语", "Korean"),
    TargetLanguage("中文（简体）", "Simplified Chinese"),
    TargetLanguage("中文（繁体）", "Traditional Chinese"),
    TargetLanguage("法语", "French"),
    TargetLanguage("德语", "German"),
    TargetLanguage("西班牙语", "Spanish"),
    TargetLanguage("俄语", "Russian"),
)

/** 可选翻译模型项（providerId + modelId 联合定位） */
data class TranslateModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val label: String,
)

data class TranslateUiState(
    val models: List<TranslateModelOption> = emptyList(),
    /** 记忆的 modelId（跨 provider 查找） */
    val selectedModelId: String = "",
    val selectedLanguage: String = "English",
    val source: String = "",
    val result: String = "",
    val translating: Boolean = false,
    /** 自动翻译开关（源文本/语言变化后防抖自动执行） */
    val autoTranslate: Boolean = true,
    val error: String? = null,
)

/**
 * 翻译页 ViewModel：目标语言 / 模型选择记忆到 LlmPrefs，翻译走 [TranslationHandler]。
 */
class TranslateViewModel(
    private val handler: TranslationHandler,
    private val llmRepository: LlmRepository,
    private val llmPrefs: LlmPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(
        TranslateUiState(
            selectedModelId = llmPrefs.getString(LlmPrefs.KEY_TRANSLATION_MODEL).orEmpty(),
            selectedLanguage = llmPrefs.getString(LlmPrefs.KEY_TRANSLATION_TARGET_LANG, "English")
                ?: "English",
        )
    )
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private var translateJob: Job? = null
    private var debounceJob: Job? = null

    init {
        loadModels()
    }

    /** 加载全部启用 Provider 的 CHAT 模型 */
    fun loadModels() {
        viewModelScope.launch {
            val options = llmRepository.getProviders()
                .filter { it.enabled }
                .flatMap { p ->
                    p.models.filter { it.type == ModelType.CHAT }.map { m ->
                        TranslateModelOption(
                            providerId = p.id,
                            providerName = p.name,
                            modelId = m.modelId,
                            label = m.displayName.ifBlank { m.modelId },
                        )
                    }
                }
                // 选择器 key 为「providerId + modelId」：同服务商下重复添加同一个模型会 key 冲突
                .distinctBy { it.providerId to it.modelId }
            _state.value = _state.value.copy(
                models = options,
                // 记忆的模型已被删除时回落到第一个
                selectedModelId = options.firstOrNull { it.modelId == _state.value.selectedModelId }
                    ?.modelId
                    ?: options.firstOrNull()?.modelId
                    ?: ""
            )
        }
    }

    fun setSource(text: String) {
        _state.value = _state.value.copy(source = text)
        scheduleAutoTranslate()
    }

    fun selectLanguage(code: String) {
        if (_state.value.selectedLanguage == code) return
        _state.value = _state.value.copy(selectedLanguage = code)
        llmPrefs.putString(LlmPrefs.KEY_TRANSLATION_TARGET_LANG, code)
        scheduleAutoTranslate()
    }

    fun selectModel(modelId: String) {
        _state.value = _state.value.copy(selectedModelId = modelId)
        llmPrefs.putString(LlmPrefs.KEY_TRANSLATION_MODEL, modelId)
    }

    fun toggleAuto(enabled: Boolean) {
        _state.value = _state.value.copy(autoTranslate = enabled)
        if (enabled) scheduleAutoTranslate()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearAll() {
        translateJob?.cancel()
        debounceJob?.cancel()
        _state.value = _state.value.copy(source = "", result = "", translating = false, error = null)
    }

    /** 自动翻译：600ms 防抖 */
    private fun scheduleAutoTranslate() {
        if (!_state.value.autoTranslate) return
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(600)
            translate()
        }
    }

    /** 手动/自动触发翻译；模型未配置时提示 */
    fun translate() {
        val s = _state.value
        if (s.translating) return
        if (s.source.isBlank()) return
        val option = s.models.firstOrNull { it.modelId == s.selectedModelId }
        if (option == null) {
            _state.value = s.copy(error = "尚未配置可用模型，请到「设置 → AI 模型服务」添加")
            return
        }
        translateJob?.cancel()
        _state.value = s.copy(translating = true, error = null)
        translateJob = viewModelScope.launch {
            try {
                val resolved = llmRepository.getProviders()
                    .filter { it.enabled }
                    .firstOrNull { it.id == option.providerId }
                if (resolved == null) {
                    _state.value = _state.value.copy(translating = false, error = "翻译模型所属服务已停用")
                    return@launch
                }
                val model = resolved.models.firstOrNull { it.modelId == option.modelId }
                    ?: run {
                        _state.value = _state.value.copy(translating = false, error = "翻译模型不存在")
                        return@launch
                    }
                handler.translateText(
                    providerSetting = resolved,
                    model = model,
                    sourceText = s.source,
                    targetLanguage = s.selectedLanguage,
                ).collect { translated ->
                    _state.value = _state.value.copy(result = translated)
                }
                _state.value = _state.value.copy(translating = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(translating = false)
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    translating = false,
                    error = e.toUserMessage("翻译失败")
                )
            }
        }
    }

    /** 停止当前翻译 */
    fun stopTranslate() {
        translateJob?.cancel()
        _state.value = _state.value.copy(translating = false)
    }
}

/** 翻译页：源文本 → 流式译文，目标语言与模型选择记忆 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    onBack: () -> Unit,
    viewModel: TranslateViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // 模型选择器开关
    var showModelPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 目标语言 chips ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TARGET_LANGUAGES.forEach { lang ->
                    val selected = lang.code == state.selectedLanguage
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { viewModel.selectLanguage(lang.code) }
                    ) {
                        Text(
                            text = lang.display,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ---------- 模型选择 ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "翻译模型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.loadModels()
                        showModelPicker = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = state.models.firstOrNull { it.modelId == state.selectedModelId }
                            ?.label ?: "选择模型",
                        maxLines = 1
                    )
                }
            }

            // ---------- 源文本 ----------
            OutlinedTextField(
                value = state.source,
                onValueChange = viewModel::setSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                placeholder = { Text("输入要翻译的文本…") },
                textStyle = MaterialTheme.typography.bodyMedium
            )

            // ---------- 操作行：翻译/停止 + 自动翻译 + 复制 + 清空 ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.translating) {
                    TextButton(onClick = viewModel::stopTranslate) { Text("停止") }
                } else {
                    TextButton(
                        onClick = viewModel::translate,
                        enabled = state.source.isNotBlank()
                    ) { Text("翻译") }
                }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(state.result)) },
                    enabled = state.result.isNotBlank()
                ) { Text("复制译文") }
                TextButton(onClick = viewModel::clearAll) { Text("清空") }
                Spacer(Modifier.weight(1f))
                Text("自动翻译", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = state.autoTranslate,
                    onCheckedChange = viewModel::toggleAuto
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ---------- 译文 ----------
            Text(
                text = "译文",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    if (state.translating && state.result.isBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "翻译中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = state.result.ifBlank { "译文会在这里实时显示" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.result.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ---------- 模型选择对话框 ----------
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("选择翻译模型") },
            text = {
                if (state.models.isEmpty()) {
                    Text("还没有可用模型，请先到设置添加 AI 模型服务")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.models, key = { it.providerId + it.modelId }) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (option.modelId == state.selectedModelId)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable {
                                        viewModel.selectModel(option.modelId)
                                        showModelPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = option.providerName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModelPicker = false }) { Text("取消") }
            }
        )
    }
}
