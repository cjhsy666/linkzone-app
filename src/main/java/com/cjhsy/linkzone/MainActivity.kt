package com.cjhsy.linkzone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
            }
        }
    }
}
