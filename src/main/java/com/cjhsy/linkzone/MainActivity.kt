package com.cjhsy.linkzone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cjhsy.linkzone.ui.navigation.AppNavHost
import com.cjhsy.linkzone.ui.theme.LinkZoneTheme

/**
 * 唯一 Activity：承载 Compose 导航宿主。
 */
// #region debug-point chat-gen-crash
/** 调试插桩：ApplicationExitInfo.reason → 可读名称 */
private fun debugExitReasonName(reason: Int): String = when (reason) {
    0 -> "UNKNOWN"
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY(系统低内存查杀)"
    4 -> "CRASH(Java崩溃)"
    5 -> "CRASH_NATIVE(原生崩溃)"
    6 -> "ANR(无响应)"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "code=$reason"
}
// #endregion

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式全屏：内容延伸到状态栏/导航栏之后，系统栏透明，
        // 各页面通过 safeDrawingPadding / statusBarsPadding 自行避让。
        // 键盘避让依赖 Manifest adjustResize + 各输入区 imePadding。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LinkZoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
                // #region debug-point chat-gen-crash
                // 调试插桩：上次 Java 崩溃落盘 + 系统历史退出原因（识别低内存查杀/
                // native 崩溃/ANR——这些不走 Java 异常通道，崩溃文件会缺失）
                var debugCrash by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    debugCrash = runCatching {
                        buildString {
                            val f = java.io.File(filesDir, "debug_last_crash.txt")
                            if (f.exists()) {
                                append(f.readText())
                                f.delete()
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                val am = getSystemService(android.app.ActivityManager::class.java)
                                val exits = am?.getHistoricalProcessExitReasons(packageName, 0, 5).orEmpty()
                                exits.forEachIndexed { i, info ->
                                    appendLine("---- exit#$i ----")
                                    appendLine("reason=${debugExitReasonName(info.reason)} time=${
                                        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                                            .format(java.util.Date(info.timestamp))
                                    }")
                                    appendLine("pssMB=${info.pss / 1024} rssMB=${info.rss / 1024} importance=${info.importance} status=${info.status}")
                                    info.description?.let { appendLine("desc=$it") }
                                    if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                                        info.reason == android.app.ApplicationExitInfo.REASON_ANR
                                    ) {
                                        val trace = runCatching {
                                            info.traceInputStream?.readBytes()?.let { String(it) }
                                        }.getOrNull()
                                        if (!trace.isNullOrBlank()) appendLine(trace.take(6000))
                                    }
                                }
                            }
                        }.ifBlank { null }
                    }.getOrNull()
                }
                val debugClipboard = LocalClipboardManager.current
                debugCrash?.let { report ->
                    AlertDialog(
                        onDismissRequest = { debugCrash = null },
                        title = { Text("上次崩溃/退出报告（请复制发给开发者）") },
                        text = {
                            Text(
                                text = report,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                debugClipboard.setText(AnnotatedString(report))
                                debugCrash = null
                            }) { Text("复制并关闭") }
                        },
                        dismissButton = {
                            TextButton(onClick = { debugCrash = null }) { Text("关闭") }
                        }
                    )
                }
                // #endregion
            }
        }
    }
}
