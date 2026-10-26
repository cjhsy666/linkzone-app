package com.cjhsy.linkzone.ui.llm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.data.ai.LinkZoneAdminConfig
import com.cjhsy.linkzone.data.ai.LocalToolOption

/**
 * 本地工具独立配置页：
 * - 工具清单 + 用途说明 + 所需权限（只读浏览，实际开关在智能体编辑页勾选）
 * - LinkZone 服务端联动的全局配置（框架地址 + admin token）
 */
@Composable
fun LlmLocalToolsScreen(onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("本地工具", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        }
        item {
            Text(
                "让智能体操作本机的能力：时间/剪贴板/日历/通讯录/短信/提醒/HTTP 请求/服务端联动等。开关在智能体编辑页里按智能体单独勾选，每个智能体的工具集相互独立。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // LinkZone 全局配置卡（唯一需要填全局参数的本地工具）
        item {
            val serverUrl by remember { mutableStateOf(LinkZoneAdminConfig.serverUrl(appContext)) }
            val adminToken by remember { mutableStateOf(LinkZoneAdminConfig.adminToken(appContext)) }
            GlobalConfigCard(
                title = "服务端联动全局配置",
                subtitle = "linkzone_push / linkzone_schedule：控制 LinkZone 框架的推送与定时任务，需要 admin token"
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { LinkZoneAdminConfig.save(appContext, it, adminToken); (it) },
                    label = { Text("框架地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = adminToken,
                    onValueChange = { LinkZoneAdminConfig.save(appContext, serverUrl, it); (it) },
                    label = { Text("admin token（system.server.admin_token）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 工具清单：按分组展示
        item { Text("系统工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(LOCAL_TOOLS_SYSTEM.size) { i -> ToolRow(LOCAL_TOOLS_SYSTEM[i]) }

        item { Text("扩展工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(LOCAL_TOOLS_EXTENDED.size) { i -> ToolRow(LOCAL_TOOLS_EXTENDED[i]) }
    }
}

/** 工具一行：名称 + 说明 + 权限标签 */
@Composable
private fun ToolRow(info: ToolInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    info.permissions.forEach { perm ->
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                " 需要 $perm 权限 ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    info.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 全局配置卡 */
@Composable
private fun GlobalConfigCard(
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

/** 工具展示元数据 */
private data class ToolInfo(
    val option: LocalToolOption,
    val name: String,
    val desc: String,
    val permissions: List<String> = emptyList()
)

private val LOCAL_TOOLS_SYSTEM = listOf(
    ToolInfo(LocalToolOption.TimeInfo, "时间查询", "查询设备当前日期、时间与时区"),
    ToolInfo(LocalToolOption.Clipboard, "剪贴板读写", "读取或写入剪贴板文本"),
    ToolInfo(LocalToolOption.AskUser, "主动追问", "信息不足时暂停生成向你提问（可跳过）"),
    ToolInfo(LocalToolOption.Calendar, "日历读写", "calendar_query / calendar_create：查询/创建系统日历日程", listOf("CALENDAR")),
    ToolInfo(LocalToolOption.Javascript, "JS 执行", "eval_javascript：用 QuickJS 运行 JS 代码/计算"),
    ToolInfo(LocalToolOption.SystemControl, "手机控制", "打开 App / 网页 / 拨号盘 / 系统设置、系统分享、查设备信息与定位", listOf("定位")),
    ToolInfo(LocalToolOption.Contacts, "通讯录", "contacts_query：按姓名或号码查询手机联系人", listOf("通讯录")),
    ToolInfo(LocalToolOption.SmsCallLog, "短信与通话记录", "sms_query / call_log_query：查询近期短信和通话记录", listOf("SMS", "通话记录"))
)

private val LOCAL_TOOLS_EXTENDED = listOf(
    ToolInfo(LocalToolOption.Reminder, "定时提醒", "reminder_create / list / cancel：到点系统通知提醒", listOf("通知")),
    ToolInfo(LocalToolOption.HttpRequest, "HTTP 请求", "http_request：让模型调用任意 HTTP 接口"),
    ToolInfo(LocalToolOption.LinkZone, "服务端联动", "linkzone_push / linkzone_schedule：操作 LinkZone 框架推送与定时任务")
)
