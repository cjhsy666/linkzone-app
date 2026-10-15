package com.cjhsy.linkzone.ui.chat.md

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import kotlin.math.ceil

/** Compose 颜色 → JS 可用 #RRGGBB */
private fun hexOf(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

/** 缓存 key：tex + 着色，深浅气泡文字色不同需分别缓存；同包组件共享 */
internal fun inlineMathKey(tex: String, tint: Color?): String =
    if (tint == null) tex else "$tex|${hexOf(tint)}"

/**
 * 行内 $...$ 公式离屏渲染器：全 App 共享一个不挂载视图树的 WebView，
 * 串行队列渲染并截取白底位图，供 Compose 以 InlineTextContent 嵌入段落。
 *
 * 相比每实例挂载 WebView：LazyColumn 滑动进出组合零 WebView 创建/销毁开销，
 * 位图按 tex+着色 LruCache，滚动回看零重渲染。
 */
object MdInlineMathRenderer {

    sealed interface Result {
        data class Ready(val bitmap: Bitmap) : Result
        data object Failed : Result
    }

    private const val MAX_TEX_LENGTH = 200
    private const val MAX_WIDTH_PX = 720
    private const val TIMEOUT_MS = 8000L
    private const val DRAW_DELAY_MS = 120L
    private const val PAGE_URL = "https://appassets.androidplatform.net/assets/md/katex.html"

    /** Compose 可观察的结果表（key=[inlineMathKey]）；无记录表示尚未就绪 */
    val results = mutableStateMapOf<String, Result>()

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageLoaded = false
    private var busy = false
    private var current: Job? = null
    private var currentW = 0
    private var currentH = 0
    private var currentDepth = 0
    private var density = 3f

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) results.remove(key)
        }
    }

    private data class Job(val key: String, val tex: String, val tint: Color?)

    private val queue = ArrayDeque<Job>()
    private val enqueued = mutableSetOf<String>()

    private val timeoutRunnable = Runnable { current?.let { fail(it) } }
    private val drawRunnable = Runnable { drawCurrent() }

    /** 请求渲染（任意线程可调；已缓存/在途自动去重）。
     *  @param tint 字形颜色（非空时传给 JS 并计入缓存 key），匹配气泡文字色 */
    fun request(context: Context, tex: String, tint: Color? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            handler.post { request(app, tex, tint) }
            return
        }
        if (tex.isBlank() || tex.length > MAX_TEX_LENGTH) return
        val key = inlineMathKey(tex, tint)
        cache.get(key)?.let {
            results[key] = Result.Ready(it)
            return
        }
        if (results.containsKey(key) || !enqueued.add(key)) return
        queue.addLast(Job(key, tex, tint))
        ensureWebView(context.applicationContext)
        pump()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context) {
        if (webView != null) return
        density = context.resources.displayMetrics.density
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.setBackgroundColor(AndroidColor.WHITE)
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                pump()
            }
        }
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onInline(json: String) {
                handler.post { onGeometry(json) }
            }

            @JavascriptInterface
            fun onInlineError(msg: String) {
                handler.post { current?.let { fail(it) } }
            }
        }, "LinkZone")
        webView = wv
        wv.loadUrl(PAGE_URL)
    }

    /** 串行泵：同一时刻只渲染一条公式，完成后弹下一条 */
    private fun pump() {
        if (busy || !pageLoaded) return
        val wv = webView ?: return
        val job = queue.removeFirstOrNull() ?: return
        enqueued.remove(job.key)
        busy = true
        current = job
        currentW = 0
        currentH = 0
        currentDepth = 0
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(MAX_WIDTH_PX, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        wv.layout(0, 0, wv.measuredWidth, wv.measuredHeight)
        val quotedTex = JSONObject.quote(job.tex) ?: "\"\""
        val jsCall = if (job.tint == null) {
            "window.renderInline($quotedTex)"
        } else {
            "window.renderInline($quotedTex, \"${hexOf(job.tint)}\")"
        }
        wv.evaluateJavascript(jsCall, null)
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun onGeometry(json: String) {
        if (!busy) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        currentW = o.optInt("w")
        currentH = o.optInt("h")
        currentDepth = o.optInt("depth")
        if (currentW <= 0 || currentH <= 0) return
        handler.removeCallbacks(drawRunnable)
        handler.postDelayed(drawRunnable, DRAW_DELAY_MS)
    }

    private fun drawCurrent() {
        val job = current ?: return
        val wv = webView ?: run { fail(job); return }
        val w = currentW.coerceIn(1, MAX_WIDTH_PX)
        val h = currentH.coerceAtLeast(1)
        val padX = ceil(2 * density).toInt()
        val padY = ceil(1 * density).toInt()
        val bitmap = runCatching {
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w + padX * 2, h + currentDepth.coerceAtLeast(0) + padY * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(AndroidColor.WHITE)
            canvas.translate(padX.toFloat(), padY.toFloat())
            wv.draw(canvas)
            bmp
        }.getOrNull()
        if (bitmap == null) {
            fail(job)
        } else {
            cache.put(job.key, bitmap)
            results[job.key] = Result.Ready(bitmap)
            next()
        }
    }

    private fun fail(job: Job) {
        results[job.key] = Result.Failed
        next()
    }

    private fun next() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(drawRunnable)
        busy = false
        current = null
        pump()
    }
}

/** 订阅 tex 的离屏渲染结果；null=渲染中，Ready=白底位图，Failed=失败。
 *  @param tint 字形颜色（匹配气泡文字色），计入缓存 key 并传给 JS。 */
@Composable
fun rememberInlineMath(tex: String, tint: Color? = null): MdInlineMathRenderer.Result? {
    val context = LocalContext.current
    LaunchedEffect(tex, tint) { MdInlineMathRenderer.request(context.applicationContext, tex, tint) }
    return MdInlineMathRenderer.results[inlineMathKey(tex, tint)]
}
