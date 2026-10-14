package com.cjhsy.linkzone.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

/**
 * Mermaid 图表：唯一允许 WebView 的场景（无原生渲染器）。
 *
 * 可靠性：
 * - 静态壳 HTML 内联 mermaid.min.js（Kotlin 读 asset 注入，杜绝脚本加载竞态白屏），
 *   onPageFinished 后再注入图表代码；JS 侧 mermaid 对象就绪轮询，5s 超时进错误态。
 * - 状态机：Loading（骨架转圈）/ Done（JS 回报内容高度，px→dp，160–420dp）/ Error（错误卡片 + 重试）。
 * - 安全沙箱：javaScriptEnabled 开，但四个文件/内容访问开关全关，baseURL about:blank；
 *   Bridge 仅 `onResult(heightPx, error)` / `onConsole(msg)` 两个只读回报接口；
 *   销毁移除 Bridge + about:blank + clearCache + destroy。
 * - 主题：themeVariables 透明背景 + 明暗配色；palette 变化时仅重注 render()，无白底闪烁。
 * - 全屏 Dialog 双轴可滚动 + 双指缩放。
 */

private val MIN_CHART_HEIGHT = 160.dp
private val MAX_CHART_HEIGHT = 420.dp

private sealed interface MermaidState {
    data object Loading : MermaidState
    data class Done(val heightDp: Int) : MermaidState
    data class Error(val message: String) : MermaidState
}

@Composable
internal fun MdMermaidBlock(code: String, ctx: MdRenderContext) {
    val p = ctx.palette
    var fullscreen by rememberSaveable { mutableStateOf(false) }  // 旋转后保留全屏态（AC-17）

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(MdSpace.codeRadius))
            .border(1.dp, p.codeBorder, RoundedCornerShape(MdSpace.codeRadius))
            .background(p.codeSurface)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.codeHeaderBg)
                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BarChart,
                contentDescription = null,
                tint = p.dim,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Mermaid 图表",
                style = MaterialTheme.typography.labelSmall,
                color = p.dim,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { fullscreen = true },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(
                    Icons.Filled.OpenInFull,
                    contentDescription = "全屏查看图表",
                    tint = p.dim,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_CHART_HEIGHT),
            contentAlignment = Alignment.Center
        ) {
            if (ctx.streaming) {
                // 流式期间图表代码不断增长：不实例化 WebView，结束后一次性渲染
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "图表生成中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = p.dim
                    )
                }
            } else {
                MermaidWebView(code = code, dark = p.dark)
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Mermaid 图表",
                            style = MaterialTheme.typography.titleMedium,
                            color = p.text
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { fullscreen = false }
                                .padding(6.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = p.dim)
                        }
                    }
                    MermaidWebView(
                        code = code,
                        dark = p.dark,
                        fullscreen = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidWebView(
    code: String,
    dark: Boolean,
    fullscreen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    var state by remember { mutableStateOf<MermaidState>(MermaidState.Loading) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // mermaid.min.js 只读取/内联一次（进程级缓存）
    val shellHtml = remember {
        val js = context.assets.open("mermaid/mermaid.min.js").bufferedReader().use { it.readText() }
        // 防库源码中出现 </script> 字面量截断 script 标签
        val safeJs = js.replace("</script>", "<\\/script>")
        buildMermaidShell(safeJs)
    }

    DisposableEffect(Unit) {
        onDispose {
            // 安全销毁：移除 Bridge、断开页面、清缓存、销毁（TR-8.5）
            webViewRef?.let { wv ->
                runCatching { wv.removeJavascriptInterface("LinkZoneBridge") }
                runCatching { wv.stopLoading() }
                runCatching { wv.loadUrl("about:blank") }
                runCatching { wv.clearHistory() }
                runCatching { wv.clearCache(true) }
                runCatching { wv.removeAllViews() }
                runCatching { wv.destroy() }
            }
            webViewRef = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fullscreen) Modifier
                else Modifier.heightIn(
                    min = MIN_CHART_HEIGHT,
                    max = MAX_CHART_HEIGHT
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    // 安全沙箱：文件/内容访问一律关闭（TR-8.5）
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    runCatching { settings.allowFileAccessFromFileURLs = false }
                    runCatching { settings.allowUniversalAccessFromFileURLs = false }
                    settings.domStorageEnabled = false
                    settings.builtInZoomControls = fullscreen
                    settings.displayZoomControls = false
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = fullscreen
                    isHorizontalScrollBarEnabled = fullscreen

                    // Bridge 仅两个最小接口（TR-8.5）：onResult(heightPx, error) 回报渲染结果、
                    // onConsole 转发控制台；错误经 error 参数回传，不再单独暴露 onError
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onResult(heightPx: Int, error: String?) {
                            post {
                                if (!error.isNullOrBlank()) {
                                    state = MermaidState.Error(error.take(300))
                                } else {
                                    val hDp = (heightPx / density).toInt().coerceAtLeast(1)
                                    state = MermaidState.Done(hDp)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onConsole(message: String) {
                            // console 转发：仅用于原生侧观测，不改变 UI 状态
                            android.util.Log.d("MermaidWeb", message.take(500))
                        }
                    }, "LinkZoneBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            injectRender(view, code, dark)
                        }
                    }
                    webViewRef = this
                    // baseURL about:blank：库已内联，无任何本地文件访问
                    loadDataWithBaseURL(
                        "about:blank",
                        shellHtml,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            },
            update = { wv ->
                webViewRef = wv
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else when (val s = state) {
                        is MermaidState.Done -> Modifier.height(s.heightDp.dp)
                        else -> Modifier.heightIn(min = MIN_CHART_HEIGHT, max = MAX_CHART_HEIGHT)
                    }
                )
        )

        // 状态浮层
        when (val s = state) {
            is MermaidState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.dp
            )
            is MermaidState.Error -> MermaidErrorContent(s.message) {
                state = MermaidState.Loading
                webViewRef?.let { wv ->
                    // 整页重载，保证渲染环境干净；onPageFinished 会重新注入
                    wv.loadDataWithBaseURL("about:blank", shellHtml, "text/html", "utf-8", null)
                }
            }
            is MermaidState.Done -> {}
        }
    }

    // 主题切换：不重建 WebView，重注 render() 按新 palette 重绘（NFR-7，无白底串色）。
    // 首次注入由 onPageFinished 负责，此处跳过首帧，避免重复 render。
    var themeInit by remember { mutableStateOf(false) }
    LaunchedEffect(dark) {
        if (!themeInit) {
            themeInit = true
            return@LaunchedEffect
        }
        state = MermaidState.Loading
        webViewRef?.let { wv -> runCatching { injectRender(wv, code, dark) } }
    }
}

@Composable
private fun MermaidErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Text(
            "图表渲染失败",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("重试", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 注入图表代码并渲染（代码经 JSON 转义注入，不做模板字符串拼接） */
private fun injectRender(wv: WebView, code: String, dark: Boolean) {
    val codeLit = JSONObject.quote(code)
    val vars = mermaidThemeVariables(dark)
    val js = "__render($codeLit, ${if (dark) "'dark'" else "'base'"}, $vars);"
    wv.evaluateJavascript(js, null)
}

/** Mermaid themeVariables：透明背景 + 与 App 令牌协调的明暗配色 */
private fun mermaidThemeVariables(dark: Boolean): String {
    val m = JSONObject()
    fun putAny(key: String, value: String) = m.put(key, value)
    // 透明背景，透出代码卡面色
    putAny("background", "transparent")
    if (dark) {
        putAny("primaryColor", "#1E293B")
        putAny("primaryTextColor", "#E6EDF3")
        putAny("primaryBorderColor", "#3B4A63")
        putAny("lineColor", "#8FA3C0")
        putAny("secondaryColor", "#243044")
        putAny("tertiaryColor", "#2A3648")
        putAny("textColor", "#E6EDF3")
        putAny("fontFamily", "sans-serif")
    } else {
        putAny("primaryColor", "#EEF2FF")
        putAny("primaryTextColor", "#1E293B")
        putAny("primaryBorderColor", "#C7D0E0")
        putAny("lineColor", "#64748B")
        putAny("secondaryColor", "#F1F5F9")
        putAny("tertiaryColor", "#E8EEF7")
        putAny("textColor", "#1E293B")
        putAny("fontFamily", "sans-serif")
    }
    return m.toString()
}

/** 静态壳：内联库 + 就绪轮询（5s 超时）+ render() 入口 + Bridge 回报 */
private fun buildMermaidShell(mermaidJs: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=2">
<style>
  html,body{margin:0;padding:0;background:transparent !important;}
  #out{display:inline-block;}
  #out svg{max-width:none !important;height:auto !important;}
</style>
<script>$mermaidJs</script>
</head>
<body>
<div id="out"></div>
<script>
(function(){
  var READY_DEADLINE = 5000;
  // console 转发 + 全局错误捕获（原生侧可观测，FR-16）
  ['log','warn','error'].forEach(function(k){
    var orig = console[k];
    console[k] = function(){
      try { LinkZoneBridge.onConsole(k + ': ' + Array.prototype.join.call(arguments, ' ')); } catch(e){}
      if (orig) { try { orig.apply(console, arguments); } catch(e){} }
    };
  });
  window.onerror = function(m){ try { LinkZoneBridge.onResult(0, String(m)); } catch(e){} };
  function __whenReady(cb, t0){
    t0 = t0 || Date.now();
    if (window.mermaid && typeof mermaid.render === 'function') { cb(); }
    else if (Date.now() - t0 > READY_DEADLINE) {
      try { LinkZoneBridge.onResult(0, 'mermaid-ready-timeout'); } catch(e){}
    } else { setTimeout(function(){ __whenReady(cb, t0); }, 50); }
  }
  window.__render = function(code, theme, vars){
    __whenReady(function(){
      try {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'strict',
          theme: theme,
          themeVariables: vars,
          htmlLabels: false,
          flowchart: { useMaxWidth: false, htmlLabels: false },
          sequence: { useMaxWidth: false }
        });
        var id = 'g' + Date.now() + Math.floor(Math.random()*1000);
        mermaid.render(id, code).then(function(result){
          var el = document.getElementById('out');
          el.innerHTML = result.svg;
          // 等浏览器布局完成再回报高度
          setTimeout(function(){
            var h = Math.max(el.scrollHeight, el.offsetHeight, 1);
            try { LinkZoneBridge.onResult(h, null); } catch(e){}
          }, 30);
        }).catch(function(e){
          try { LinkZoneBridge.onResult(0, String(e && e.message ? e.message : e)); } catch(err){}
        });
      } catch(e) {
        try { LinkZoneBridge.onResult(0, String(e)); } catch(err){}
      }
    });
  };
})();
</script>
</body>
</html>
""".trimIndent()
