package com.cjhsy.linkzone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cjhsy.linkzone.ui.navigation.AppNavHost
import com.cjhsy.linkzone.ui.theme.LinkZoneTheme
import java.io.File

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
        val crashText = runCatching {
            File(filesDir, "last_crash.txt").takeIf { it.isFile }?.readText()
        }.getOrNull()
        setContent {
            LinkZoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                    CrashReportDialog(crashText)
                }
            }
        }
    }
}

/** 上次崩溃报告：启动时展示一次，可复制发回，关闭或复制后删除文件 */
@androidx.compose.runtime.Composable
private fun CrashReportDialog(crashText: String?) {
    if (crashText.isNullOrBlank()) return
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dismiss: () -> Unit = {
        visible = false
        runCatching { File(context.filesDir, "last_crash.txt").delete() }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("上次运行发生了崩溃") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    crashText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(crashText))
                dismiss()
            }) { Text("复制并关闭") }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text("关闭") }
        }
    )
}
