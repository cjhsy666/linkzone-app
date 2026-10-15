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
 * 串行队列逐条渲染 KaTeX 行内公式（throwOnError，失败即降级），
 * 按 JS 上报的几何（宽/高/基线下沉深度）截取白底位图，
 * 供 Compose InlineTextContent 以「位图底部对齐文字基线」插入段落。
 *
 * 可靠性设计：
 * - 软件层绘制（硬件层离屏 draw(Canvas) 在部分机型全黑）；
 * - 白底位图（不透明），杜绝透明快照变黑的机型差异；
 * - 位图按 tex 缓存（LruCache 8MB），流式期间反复重组零重渲染；
 * - 3s 超时 / JS 报错 / 截屏异常一律 Failed，调用方降级为纯文本，消息绝不可不见。
 */
object MdInlineMathRenderer {

    /** 渲染结果：Ready=位图就绪；Failed=失败/超时（降级纯文本）；results 中无记录=渲染中 */
    sealed interface Result {
        data class Ready(val bitmap: Bitmap) : Result
        data object Failed : Result
    }

    private const val MAX_WIDTH_PX = 2000
    private const val MAX_TEX_LENGTH = 200
    private const val TIMEOUT_MS = 3000L
    // 几何上报后等一短拍再截屏：等 KaTeX 字体加载/布局稳定，期间如有更新几何以最新为准
    private const val DRAW_DELAY_MS = 120L
    private const val PAGE_URL = "https://appassets.androidplatform.net/assets/md/katex.html"

    /** Compose 可观察的结果表（key=tex）；无记录表示尚未就绪 */
    val results = mutableStateMapOf<String, Result>()

    // 位图缓存：tex -> bitmap，按字节计费 8MB；淘汰时同步清 results，触发按需重渲染
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) results.remove(key)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var density: Float = 3f
    private var pageLoaded = false
    private var busy = false

    private data class Job(val key: String, val tex: String, val tint: Color?)

    private val queue = ArrayDeque<Job>()
    private val enqueued = mutableSetOf<String>()
    private var current: Job? = null
    private var currentW = 0
    private var currentH = 0
    private var currentDepth = 0

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
        // 透明底：页面自身不画底，公式字形直接画在透明画布上
        wv.setBackgroundColor(AndroidColor.TRANSPARENT)
        // 软件层：保证 draw(Canvas) 到位图可靠
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
                handler.post { currentTex?.let(::fail) }
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
        // 宽上限 AT_MOST：内容自然排版不折行（#i white-space:nowrap），超高内容由 JS 精确回报
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
        currentW = ceil(o.optDouble("w") * density).toInt()
        currentH = ceil(o.optDouble("h") * density).toInt()
        currentDepth = ceil(o.optDouble("depth") * density).toInt()
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
        // 位图底部留 depth 空白：Compose 把位图底部对齐文字基线时公式基线正确
        // 透明底：不铺白底，公式字形直接浮在段落文字上（豆包风格）
        val bitmap = runCatching {
            // 按上报几何精确排版后再截屏（渲染前 layout 的高度是空内容的）
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w + padX * 2, h + currentDepth.coerceAtLeast(0) + padY * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(AndroidColor.TRANSPARENT)
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

/** 订阅 tex 的离屏渲染结果；null=渲染中，Ready=透明底位图，Failed=失败。
 *  @param tint 字形颜色（匹配气泡文字色），计入缓存 key 并传给 JS。 */
@Composable
fun rememberInlineMath(tex: String, tint: Color? = null): MdInlineMathRenderer.Result? {
    val context = LocalContext.current
    LaunchedEffect(tex, tint) { MdInlineMathRenderer.request(context.applicationContext, tex, tint) }
    return MdInlineMathRenderer.results[inlineMathKey(tex, tint)]
}
