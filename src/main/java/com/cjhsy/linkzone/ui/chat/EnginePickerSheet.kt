package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cjhsy.linkzone.data.model.LlmAgent

/**
 * 对话引擎选择器（顶栏引擎 chip / 抽屉「新建对话」共用）：
 * - ☁️ LinkZone 云端对话：唯一云端入口（服务端代理，走 LinkZone 适配器）
 * - 🤖 本地智能体：用户自建（RikkaHub 式），本机直连 AI 服务商，不依赖服务器
 * - ＋新建/管理智能体：跳转智能体管理页
 *
 * 当前引擎高亮；选中行为由外部决定（新建 or 切换引擎分叉）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginePickerSheet(
    localAgents: List<LlmAgent>,
    /** 当前会话是否本地引擎（高亮用） */
    currentIsLocal: Boolean,
    /** 当前本地会话绑定的 agent id（高亮用） */
    currentAgentId: String? = null,
    onPickCloud: () -> Unit,
    onPickLocal: (LlmAgent) -> Unit,
    onManageAgents: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
        ) {
            Text(
                text = "对话引擎",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )

            // ---------- 云端入口 ----------
            EngineRow(
                title = "LinkZone 云端对话",
                subtitle = "服务端代理 · 走 LinkZone 适配器",
                selected = !currentIsLocal,
                onClick = onPickCloud,
                leading = {
                    EngineIcon {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )

            // ---------- 本地智能体分组 ----------
            Text(
                text = "本地智能体 · 直连 AI 服务商",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
            )
            if (localAgents.isEmpty()) {
                Text(
                    text = "还没有智能体，点下方按钮创建一个吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(localAgents, key = { it.id }) { agent ->
                        EngineRow(
                            title = agent.name.ifBlank { "未命名智能体" },
                            subtitle = agent.chatModelId
                                ?.substringAfterLast('/')
                                ?.ifBlank { null }
                                ?: "默认模型",
                            selected = currentIsLocal && agent.id == currentAgentId,
                            onClick = { onPickLocal(agent) },
                            leading = {
                                EngineIcon {
                                    Text(
                                        text = agent.avatarEmoji.ifBlank { "🤖" },
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // ---------- 管理入口 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onManageAgents)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EngineIcon {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "新建 / 管理智能体",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "提示词 · 模型 · 记忆 · 联网 · MCP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 引擎行：圆形图标 + 标题/副标题 + 当前项对勾 */
@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "当前引擎",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 圆形底色图标容器（与抽屉/会话项视觉统一） */
@Composable
private fun EngineIcon(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
