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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.ui.navigation.Routes

/**
 * AI 中心聚合页：本地 AI 能力的统一入口。
 *
 * 「LinkZone 平台服务」与「本机 AI 能力」在信息架构上分开：
 * 平台功能（钱包/签到/通知等）走个人中心，本地 AI 相关全部收纳于此，
 * 避免设置页膨胀，也让用户心智清晰。
 */
@Composable
fun AiHubScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "AI 中心",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(48.dp))
        }

        Spacer(Modifier.height(8.dp))

        // 说明
        Text(
            text = "本地 AI 能力：模型服务、智能体、扩展、MCP、搜索、记忆，全部数据仅存于本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(Modifier.height(20.dp))

        // 功能网格（2 列，现代化卡片）
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(aiHubEntries) { entry ->
                AiHubCard(
                    icon = entry.icon,
                    title = entry.title,
                    desc = entry.desc,
                    onClick = { onNavigate(entry.route) }
                )
            }
        }
    }
}

/** AI 中心功能卡片 */
@Composable
private fun AiHubCard(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 图标圆角背景
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/** AI 中心入口数据 */
private data class AiHubEntry(
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val route: String
)

private val aiHubEntries = listOf(
    AiHubEntry(
        icon = Icons.Filled.Api,
        title = "模型服务",
        desc = "接入 OpenAI / Google / Claude 等服务商",
        route = Routes.LLM_PROVIDERS
    ),
    AiHubEntry(
        icon = Icons.Filled.SmartToy,
        title = "本地智能体",
        desc = "创建并管理你的 AI 智能体",
        route = Routes.LLM_AGENTS
    ),
    AiHubEntry(
        icon = Icons.Filled.Extension,
        title = "AI 扩展",
        desc = "技能 / 提示词 / 快捷指令 / 云端指令",
        route = Routes.LLM_EXTENSIONS
    ),
    AiHubEntry(
        icon = Icons.Filled.Hub,
        title = "MCP 服务器",
        desc = "配置外部工具与数据源",
        route = Routes.LLM_MCP
    ),
    AiHubEntry(
        icon = Icons.Filled.Search,
        title = "联网搜索",
        desc = "让智能体能检索实时网络信息",
        route = Routes.LLM_SEARCH
    ),
    AiHubEntry(
        icon = Icons.Filled.Memory,
        title = "长期记忆",
        desc = "跨会话持久化的记忆与知识",
        route = Routes.LLM_MEMORY
    ),
    AiHubEntry(
        icon = Icons.Filled.Terminal,
        title = "代码工作区",
        desc = "文件浏览与 shell 命令台",
        route = Routes.LLM_WORKSPACE
    ),
    AiHubEntry(
        icon = Icons.Filled.BarChart,
        title = "用量统计",
        desc = "Token 用量、请求数与每日趋势",
        route = Routes.STATS
    )
)
