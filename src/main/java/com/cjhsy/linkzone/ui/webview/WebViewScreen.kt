package com.cjhsy.linkzone.ui.webview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 通用 WebView 页面。
 *
 * - AndroidView 包 android.webkit.WebView：JS / DOM 存储 / 缩放均开启；
 * - 顶部细进度条，标题栏显示当前页 title；
 * - 系统返回优先 WebView.goBack()（没有历史时交给外部 onBack）；
 * - 动作按钮：系统浏览器打开、刷新；
 * - WebViewClient 不劫持 http/https 同源跳转（由 WebView 自己加载），
 *   非网页协议（mailto/tel/intent 等）尝试交给系统处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 支持缩放（手势缩放，不显示右下角缩放按钮）
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 混合内容兼容（页面内 http 资源在 https 页下允许加载）
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    // http/https 交由 WebView 自行加载，不劫持（含同源跳转）
                    if (scheme == "http" || scheme == "https") return false
                    // 其他协议交给系统处理
                    return try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (e: ActivityNotFoundException) {
                        true
                    }
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    canGoBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, visitUrl: String, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title ?: ""
                }
            }
            loadUrl(url)
        }
    }

    // 系统返回：WebView 有历史时优先 goBack
    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    // 生命周期：前后台暂停/恢复渲染；离开页面销毁 WebView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.stopLoading()
            webView.destroy()
        }
    }

    // url 参数变化时重新加载（路由复用同一页面的情况）
    LaunchedEffect(url) {
        if (webView.url != url) webView.loadUrl(url)
    }

    Scaffold(
        topBar = {
            // TopAppBar 自带状态栏 insets，无需再叠 safeDrawingPadding
            Column {
                TopAppBar(
                    title = {
                        Text(
                            pageTitle.ifBlank { url },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: ActivityNotFoundException) {
                                    // 设备上没有可处理的应用，忽略
                                }
                            }
                        ) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = "用系统浏览器打开")
                        }
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                )
                // 顶部细进度条：加载中（1..99）才显示
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
