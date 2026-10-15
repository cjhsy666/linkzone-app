package com.cjhsy.linkzone.ui.chat.md

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 小岛页面与原生侧共享的桥：WebView 实例 + 首载标记 + 已渲染内容（去抖判重） */
private class IslandHandle {
    var webView: WebView? = null
    var loaded: Boolean = false
    var lastRendered: String? = null
}

/**
 * WebView 小岛基座：离线 assets 页面（WebViewAssetLoader，https 伪源）渲染富内容。
 * JS 侧约定：window.[renderFn](content) 渲染；LinkZone.onHeight(物理px) 上报高度；
 * LinkZone.onError(msg) 上报失败 → 原生侧回退为代码卡片（至少可见源码）。
 * 高度动画平滑过渡；内容变化 600ms 防抖重渲染；首次渲染在 onPageFinished 后立即触发。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MdWebIsland(
    page: String,
    renderFn: String,
    content: String,
    fallbackLanguage: String,
    textColor: Color,
    // 图表用轻卡片包裹；块级公式不着卡片，直接居中呈现（数学式本体即视觉主体）
    framed: Boolean,
) {
    val density = LocalDensity.current.density
    var heightDp by remember { mutableStateOf(48.dp) }
    var error by remember { mutableStateOf<String?>(null) }
    val handle = remember { IslandHandle() }
    val latestContent = rememberUpdatedState(content)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // 兜底卡片复用现有设计 token：等宽样式取自当前 Markdown 排版，高亮板按气泡明暗选择
    val fallbackStyle = LocalMarkdownTypography.current.code
    val highlightsBuilder = rememberMdHighlightsBuilder(dark = textColor.luminance() > 0.5f)

    fun render(c: String) {
        handle.webView?.evaluateJavascript("window.$renderFn(${JSONObject.quote(c) ?: "\"\""})", null)
        handle.lastRendered = c
    }

    // 内容变化 → 600ms 防抖重渲染（首渲染由 onPageFinished 触发，此处判重跳过）
    LaunchedEffect(content) {
        if (!handle.loaded || handle.lastRendered == content) return@LaunchedEffect
        delay(600)
        render(content)
    }

    if (error != null) {
        MdCodeCard(content, fallbackLanguage, fallbackStyle, textColor, highlightsBuilder)
        return
    }

    val animatedHeight by animateDpAsState(targetValue = heightDp, label = "islandHeight")
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (framed) 4.dp else 8.dp)
            .then(
                if (framed) {
                    Modifier
                        .clip(cardShape)
                        .border(1.dp, textColor.copy(alpha = 0.10f), cardShape)
                        .background(textColor.copy(alpha = 0.03f))
                        .padding(10.dp)
                } else {
                    Modifier
                }
            )
    ) {
        // 白底内容区：图表/公式始终在白底上渲染，与气泡配色解耦
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        val assetLoader = WebViewAssetLoader.Builder()
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                            .build()
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                            override fun onPageFinished(view: WebView, url: String) {
                                handle.loaded = true
                                render(latestContent.value)
                            }
                        }
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onHeight(px: Int) {
                                mainHandler.post { heightDp = (px / density).dp }
                            }

                            @JavascriptInterface
                            fun onError(msg: String) {
                                mainHandler.post { error = msg }
                            }
                        }, "LinkZone")
                        handle.webView = this
                        loadUrl("https://appassets.androidplatform.net/assets/md/$page")
                    }
                },
                onRelease = { webView ->
                    handle.webView = null
                    webView.destroy()
                },
            )
        }
    }
}

/** Mermaid 图表小岛（```mermaid 代码围栏）；失败回退为源码卡片 */
@Composable
fun MdMermaidIsland(code: String, textColor: Color) {
    MdWebIsland(
        page = "mermaid.html",
        renderFn = "renderDiagram",
        content = code,
        fallbackLanguage = "mermaid",
        textColor = textColor,
        framed = true,
    )
}

/** LaTeX 块级公式小岛（$$...$$ 段落）；失败回退为源码卡片 */
@Composable
fun MdKatexIsland(formula: String, textColor: Color) {
    MdWebIsland(
        page = "katex.html",
        renderFn = "renderFormula",
        content = formula,
        fallbackLanguage = "latex",
        textColor = textColor,
        framed = false,
    )
}
