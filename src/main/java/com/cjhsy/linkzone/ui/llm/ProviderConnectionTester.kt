package com.cjhsy.linkzone.ui.llm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.ai.provider.Model

/** 单项检测状态 */
sealed interface TestItemState {
    data object Idle : TestItemState
    data object Loading : TestItemState
    data class Ok(val detail: String = "") : TestItemState
    data class Error(val message: String) : TestItemState
}

/** 连接测试器整体状态 */
data class ConnectionTestState(
    val modelId: String?,
    val running: Boolean = false,
    val nonStream: TestItemState = TestItemState.Idle,
    val stream: TestItemState = TestItemState.Idle,
    val streamText: String = "",
    val tools: TestItemState = TestItemState.Idle,
)

/**
 * 连接测试对话框：对选定模型依次验证非流式对话、流式对话、工具调用三项能力。
 * 测试对象是编辑中的（可能尚未保存的）配置。
 */
@Composable
fun ProviderConnectionTesterDialog(
    models: List<Model>,
    state: ConnectionTestState,
    onSelectModel: (String) -> Unit,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedModel = models.firstOrNull { it.id == state.modelId } ?: models.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接测试") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelSelector(
                    models = models,
                    selected = selectedModel,
                    enabled = !state.running,
                    onSelect = onSelectModel,
                )
                TestResultRow(
                    label = "非流式对话",
                    state = state.nonStream,
                    detail = (state.nonStream as? TestItemState.Ok)?.detail ?: "",
                )
                TestResultRow(
                    label = "流式对话",
                    state = state.stream,
                    detail = state.streamText,
                )
                TestResultRow(
                    label = "工具调用",
                    state = state.tools,
                    detail = (state.tools as? TestItemState.Ok)?.detail ?: "",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRun,
                enabled = !state.running && selectedModel != null,
            ) {
                Text(if (state.running) "测试中…" else "开始测试")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.running) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ModelSelector(
    models: List<Model>,
    selected: Model?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled && models.isNotEmpty()) it.clickable { expanded = true } else it },
    ) {
        OutlinedTextField(
            value = selected?.let { it.displayName.ifBlank { it.modelId } } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("测试模型") },
            placeholder = { Text(if (models.isEmpty()) "请先拉取或添加模型" else "选择模型") },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "选择模型",
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                model.displayName.ifBlank { model.modelId },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (model.displayName.isNotBlank() && model.displayName != model.modelId) {
                                Text(
                                    model.modelId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TestResultRow(
    label: String,
    state: TestItemState,
    detail: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(76.dp),
        )
        Box(Modifier.weight(1f)) {
            when (state) {
                is TestItemState.Idle -> Text(
                    "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is TestItemState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Text("请求中…", style = MaterialTheme.typography.bodySmall)
                }

                is TestItemState.Ok -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "✓ 成功",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }
                }

                is TestItemState.Error -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}
