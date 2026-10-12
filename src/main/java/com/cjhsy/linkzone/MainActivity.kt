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
                // 调试插桩：上次崩溃若已落盘则弹窗展示，一键复制回传后删除文件
                var debugCrash by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    val f = java.io.File(filesDir, "debug_last_crash.txt")
                    if (f.exists()) {
                        debugCrash = runCatching { f.readText() }.getOrNull()
                        f.delete()
                    }
                }
                val debugClipboard = LocalClipboardManager.current
                debugCrash?.let { report ->
                    AlertDialog(
                        onDismissRequest = { debugCrash = null },
                        title = { Text("上次崩溃报告（请复制发给开发者）") },
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
