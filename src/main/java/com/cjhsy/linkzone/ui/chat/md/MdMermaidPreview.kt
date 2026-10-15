package com.cjhsy.linkzone.ui.chat.md

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/** 预览弹窗固定浅色主题（图表/代码卡片均渲染在白底上，与气泡配色解耦） */
private val PREVIEW_TEXT = Color(0xFF1F2328)
private val PREVIEW_SUB = Color(0xFF6B7280)

/**
 * Mermaid 图全屏预览：顶部「预览 / 代码」双 tab + 右上角关闭，系统返回键关闭。
 * 预览 tab 独立新建 WebView 实例（与消息内小岛互不共享状态），同一 assets 页面与
 * 渲染管线，开启捏合缩放；渲染失败回退源码卡片。代码 tab 复用 MdCodeCard。
 */
@Composable
fun MdMermaidPreviewDialog(
    code: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler { onDismiss() }
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                var tab by remember { mutableStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PreviewTab(title = "预览", selected = tab == 0) { tab = 0 }
                    PreviewTab(title = "代码", selected = tab == 1) { tab = 1 }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = PREVIEW_SUB,
                        )
                    }
                }
                when (tab) {
                    0 -> MdMermaidPreviewIsland(
                        code = code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MdCodeCard(
                            code = code,
                            language = "mermaid",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            textColor = PREVIEW_TEXT,
                            highlightsBuilder = rememberMdHighlightsBuilder(dark = false),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTab(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = if (selected) PREVIEW_TEXT else PREVIEW_SUB,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

/**
 * 全屏预览用 Mermaid 小岛：独立 WebView 实例，fillMaxSize 高度 + WebView 内部滚动，
 * 捏合缩放（无缩放按钮）；失败回退 MdCodeCard。高度上报忽略（全屏自适应）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MdMermaidPreviewIsland(code: String, modifier: Modifier = Modifier) {
    var error by remember { mutableStateOf<String?>(null) }
    if (error != null) {
        MdCodeCard(
            code = code,
            language = "mermaid",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            textColor = PREVIEW_TEXT,
            highlightsBuilder = rememberMdHighlightsBuilder(dark = false),
        )
        return
    }
    AndroidView(
        modifier = modifier.background(Color.White),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // 捏合缩放：内置缩放控制 + 隐藏缩放按钮
                settings.builtInZoomControls = true
                settings.setSupportZoom(true)
                settings.displayZoomControls = false
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .build()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(
                            "window.renderDiagram(${JSONObject.quote(code) ?: "\"\""})",
                            null,
                        )
                    }
                }
                val mainHandler = Handler(Looper.getMainLooper())
                addJavascriptInterface(object {
                    // 全屏模式高度自适应，忽略页面高度上报（桥方法必须存在，页面会调用）
                    @JavascriptInterface
                    fun onHeight(px: Int) = Unit

                    @JavascriptInterface
                    fun onError(msg: String) {
                        mainHandler.post { error = msg }
                    }
                }, "LinkZone")
                loadUrl("https://appassets.androidplatform.net/assets/md/mermaid.html")
            }
        },
        onRelease = { it.destroy() },
    )
}
