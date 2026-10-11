package com.cjhsy.linkzone.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.backup.WebDavSync
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 备份与恢复页。
 *
 * Tab 1「导入 / 导出」：SAF 选择导出位置或备份文件，本地生成/恢复 zip；
 * Tab 2「WebDAV」：配置服务器并测试连接 / 上传 / 下载恢复。
 * 备份内容为本地 LLM 配置（Provider / Agent / Skills / 偏好与本地数据库）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 导出：让用户选择保存位置（创建 zip 文档）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }

    // 导入：让用户选择备份文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = { Text("导入 / 导出") }
                )
                Tab(
                    selected = state.tab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = { Text("WebDAV") }
                )
            }
            if (state.tab == 0) {
                LocalBackupTab(
                    state = state,
                    onExport = { exportLauncher.launch("linkzone_backup.zip") },
                    onImport = { importLauncher.launch(arrayOf("application/zip")) }
                )
            } else {
                WebDavTab(
                    state = state,
                    viewModel = viewModel
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 1：本地导入 / 导出
// ---------------------------------------------------------------------------

@Composable
private fun LocalBackupTab(
    state: BackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 页面说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "备份涵盖 AI 全量配置：Provider 与 API Key、智能体、技能（含文件）、提示词、世界书、快捷指令、MCP、记忆、本地会话、用量统计与偏好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "导入备份会覆盖现有配置，恢复完成后需重启 APP 生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.needRestart) {
            Text(
                "已恢复备份，请重启 APP 使配置生效。",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 导出
        Button(
            onClick = onExport,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).width(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("处理中…")
            } else {
                Text("导出备份到本地")
            }
        }

        // 导入
        OutlinedButton(
            onClick = onImport,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("从本地文件导入备份")
        }

        state.lastBackupFile?.let { path ->
            Text(
                "最近一次本地备份：$path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Tab 2：WebDAV
// ---------------------------------------------------------------------------

@Composable
private fun WebDavTab(
    state: BackupUiState,
    viewModel: BackupViewModel
) {
    val config = state.webdavConfig

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 页面说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "通过 WebDAV 在多设备间同步本地 LLM 配置备份（远端文件名固定为 ${WebDavSync.REMOTE_BACKUP_FILE}）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "注意：密码以明文存储在本机偏好中，请勿使用高权限账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // 表单
        OutlinedTextField(
            value = config.url,
            onValueChange = { v -> viewModel.updateConfig { it.copy(url = v) } },
            label = { Text("服务器地址（如 https://dav.example.com/dav）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.username,
            onValueChange = { v -> viewModel.updateConfig { it.copy(username = v) } },
            label = { Text("账号（可留空）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.password,
            onValueChange = { v -> viewModel.updateConfig { it.copy(password = v) } },
            label = { Text("密码（明文存储）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.directory,
            onValueChange = { v -> viewModel.updateConfig { it.copy(directory = v) } },
            label = { Text("远端目录（不存在时会自动创建）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // 保存配置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                "保存配置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewModel.saveConfig() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // 上次同步时间
        val lastSyncText = if (state.webdavLastSync > 0L) {
            TIME_FORMAT.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(state.webdavLastSync))
        } else {
            "从未同步"
        }
        Text(
            "上次上传：$lastSyncText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.webdavBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 操作按钮
        Button(
            onClick = { viewModel.testWebDav() },
            enabled = !state.webdavBusy && config.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("测试连接")
        }
        OutlinedButton(
            onClick = { viewModel.uploadToWebDav() },
            enabled = !state.webdavBusy && config.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("上传备份")
        }
        OutlinedButton(
            onClick = { viewModel.downloadFromWebDav() },
            enabled = !state.webdavBusy && config.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("下载并恢复（覆盖本地）")
        }

        if (state.needRestart) {
            Text(
                "已从 WebDAV 恢复，请重启 APP 使配置生效。",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
