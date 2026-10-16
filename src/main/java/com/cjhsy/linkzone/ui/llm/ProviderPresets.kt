package com.cjhsy.linkzone.ui.llm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.cjhsy.linkzone.ai.provider.BalanceOption
import com.cjhsy.linkzone.ai.provider.ProviderSetting

/**
 * 厂商预设：一键创建一份只缺 API Key 的配置。
 * 仅收录官方服务与主流 OpenAI 兼容服务，不带任何推广/充值链接。
 */
data class ProviderPreset(
    val name: String,
    val detail: String,
    val typeTag: String,
    val factory: () -> ProviderSetting,
)

object ProviderPresets {

    /** 官方服务（含 Vertex） */
    val official: List<ProviderPreset> = listOf(
        ProviderPreset(
            name = "OpenAI",
            detail = "GPT 系列官方 API · api.openai.com",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(name = "OpenAI")
        },
        ProviderPreset(
            name = "Google Gemini",
            detail = "Gemini 系列官方 API · AI Studio Key",
            typeTag = "Google",
        ) {
            ProviderSetting.Google(name = "Google Gemini")
        },
        ProviderPreset(
            name = "Anthropic Claude",
            detail = "Claude 系列官方 API · api.anthropic.com",
            typeTag = "Claude",
        ) {
            ProviderSetting.Claude(name = "Anthropic Claude")
        },
        ProviderPreset(
            name = "Google Vertex AI",
            detail = "GCP 企业服务，需服务账号 JSON",
            typeTag = "Google",
        ) {
            ProviderSetting.Google(
                name = "Vertex AI",
                vertexAI = true,
                useServiceAccount = true,
            )
        },
    )

    /** OpenAI 兼容的第三方/国内服务 */
    val compatible: List<ProviderPreset> = listOf(
        ProviderPreset(
            name = "DeepSeek",
            detail = "api.deepseek.com · 支持余额查询",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                balanceOption = BalanceOption(
                    enabled = true,
                    apiPath = "/user/balance",
                    resultPath = "balance_infos[0].total_balance",
                ),
            )
        },
        ProviderPreset(
            name = "月之暗面 Kimi",
            detail = "api.moonshot.cn · 支持余额查询",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(
                name = "月之暗面 Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                balanceOption = BalanceOption(
                    enabled = true,
                    apiPath = "/users/me/balance",
                    resultPath = "data.available_balance",
                ),
            )
        },
        ProviderPreset(
            name = "OpenRouter",
            detail = "openrouter.ai · 一个 Key 用百家模型",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                balanceOption = BalanceOption(
                    enabled = true,
                    apiPath = "/credits",
                    resultPath = "data.total_credits",
                ),
            )
        },
        ProviderPreset(
            name = "硅基流动",
            detail = "api.siliconflow.cn",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(
                name = "硅基流动",
                baseUrl = "https://api.siliconflow.cn/v1",
            )
        },
        ProviderPreset(
            name = "阿里云百炼",
            detail = "通义系列 · DashScope 兼容模式",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(
                name = "阿里云百炼",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            )
        },
        ProviderPreset(
            name = "智谱 GLM",
            detail = "open.bigmodel.cn · GLM 系列",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(
                name = "智谱 GLM",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            )
        },
    )

    /** 手动配置（空白） */
    val custom: List<ProviderPreset> = listOf(
        ProviderPreset(
            name = "OpenAI 兼容",
            detail = "任意兼容 /chat/completions 的服务",
            typeTag = "OpenAI",
        ) {
            ProviderSetting.OpenAI(name = "新的 OpenAI 兼容服务")
        },
        ProviderPreset(
            name = "Google 兼容",
            detail = "Gemini 协议或自建网关",
            typeTag = "Google",
        ) {
            ProviderSetting.Google(name = "新的 Google 服务")
        },
        ProviderPreset(
            name = "Claude 兼容",
            detail = "Anthropic 协议或中转服务",
            typeTag = "Claude",
        ) {
            ProviderSetting.Claude(name = "新的 Claude 服务")
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderSheet(
    onDismiss: () -> Unit,
    onSelect: (ProviderPreset) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    "添加服务商",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                )
            }
            presetSection("官方服务", ProviderPresets.official, onSelect)
            presetSection("OpenAI 兼容", ProviderPresets.compatible, onSelect)
            presetSection("手动配置", ProviderPresets.custom, onSelect)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.presetSection(
    title: String,
    presets: List<ProviderPreset>,
    onSelect: (ProviderPreset) -> Unit,
) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        )
    }
    items(presets.size) { index ->
        val preset = presets[index]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(preset) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    preset.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            ) {
                Text(
                    text = preset.typeTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}
