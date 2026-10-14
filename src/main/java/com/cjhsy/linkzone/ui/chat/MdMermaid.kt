package com.cjhsy.linkzone.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/**
 * Mermaid 图表：唯一允许 WebView 渲染的场景（无原生渲染器）。
 * 懒加载——仅点击「渲染图表」后才实例化 WebView 加载 assets 中的 mermaid.min.js。
 */

@Composable
internal fun MdMermaidBlock(code: String, textColor: Color) {
    var rendering by remember { mutableStateOf(false) }
    val dark = textColor.luminance() > 0.5f
    val bg = textColor.copy(alpha = 0.06f)
    val border = textColor.copy(alpha = 0.14f)
    val dim = textColor.copy(alpha = 0.65f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .background(bg)
    ) {
        // 顶栏：Mermaid 标识 + 渲染按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BarChart,
                contentDescription = null,
                tint = dim,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Mermaid 图表",
                style = MaterialTheme.typography.labelSmall,
                color = dim,
                modifier = Modifier.weight(1f)
            )
            if (!rendering) {
                TextButton(
                    onClick = { rendering = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = "渲染图表",
                        style = MaterialTheme.typography.labelSmall,
                        color = dim
                    )
                }
            }
        }

        if (rendering) {
            HorizontalDivider(color = border, thickness = 0.7.dp)
            MermaidWebView(code = code, dark = dark)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidWebView(code: String, dark: Boolean) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 退出组合时销毁 WebView，防泄漏
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                webView = this
                // 模板内 __CODE__ / __THEME__ 为占位符
                val template = assets.open("mermaid/view.html").bufferedReader().use { it.readText() }
                val html = template
                    .replace("/*__CODE__*/", code.toJsString())
                    .replace("__THEME__", if (dark) "dark" else "default")
                loadDataWithBaseURL(
                    "file:///android_asset/mermaid/",
                    html, "text/html", "utf-8", null
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    )
}

/** 转义为 JS 模板字符串字面量（反引号字符串）：防代码中的 ` 与 ${ 破坏模板 */
private fun String.toJsString(): String = "`" + this
    .replace("\\", "\\\\")
    .replace("`", "\\`")
    .replace("\${", "\\${") + "`"
