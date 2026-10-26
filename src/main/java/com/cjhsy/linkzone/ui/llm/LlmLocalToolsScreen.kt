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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.data.ai.LocalToolOption

/**
 * 本地工具独立配置页：
 * - 工具清单 + 用途说明 + 所需权限（只读浏览，实际开关在智能体编辑页勾选）
 */
@Composable
fun LlmLocalToolsScreen(onBack: () -> Unit) {
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
                "让智能体操作本机的能力：时间/剪贴板/日历/通讯录/短信/闹钟/HTTP 请求等。开关在智能体编辑页里按智能体单独勾选，每个智能体的工具集相互独立。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 工具清单：按分组展示
        item { Text("系统工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(LOCAL_TOOLS_SYSTEM.size) { i -> ToolRow(LOCAL_TOOLS_SYSTEM[i]) }

        item { Text("定时与扩展", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
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
    ToolInfo(LocalToolOption.AskUserPhoto, "请求选图", "ask_user_for_photo：模型要图时弹系统相册让你选（无需权限）"),
    ToolInfo(LocalToolOption.PhotoSearch, "相册搜索", "photo_query：静默按时间/相册/关键词搜图并回传", listOf("图片")),
    ToolInfo(LocalToolOption.Calendar, "日历读写", "calendar_query / calendar_create / calendar_delete：查询/创建/删除系统日历日程", listOf("CALENDAR")),
    ToolInfo(LocalToolOption.Javascript, "JS 执行", "eval_javascript：用 QuickJS 运行 JS 代码/计算"),
    ToolInfo(LocalToolOption.SystemControl, "手机控制", "打开 App / 网页 / 拨号盘 / 系统设置、系统分享、手电筒"),
    ToolInfo(LocalToolOption.DeviceInfo, "设备信息", "device_info：查询设备型号、系统、电量、网络、屏幕、存储"),
    ToolInfo(LocalToolOption.UsageStats, "应用使用统计", "usage_stats：今天/本周屏幕时长与各 App 使用占比（需在系统设置授权）", listOf("使用情况访问")),
    ToolInfo(LocalToolOption.Location, "定位", "get_location：获取当前 GPS/网络定位", listOf("定位")),
    ToolInfo(LocalToolOption.Contacts, "通讯录", "contacts_query：按姓名或号码查询手机联系人", listOf("通讯录")),
    ToolInfo(LocalToolOption.SmsCallLog, "短信与通话记录", "sms_query / call_log_query：查询近期短信和通话记录", listOf("SMS", "通话记录"))
)

private val LOCAL_TOOLS_EXTENDED = listOf(
    ToolInfo(LocalToolOption.Reminder, "闹钟与定时器", "alarm_create / timer_create / alarm_show：定闹钟、倒计时；系统闹钟的删除需打开时钟 App 操作"),
    ToolInfo(LocalToolOption.HttpRequest, "HTTP 请求", "http_request：让模型调用任意 HTTP 接口")
)
