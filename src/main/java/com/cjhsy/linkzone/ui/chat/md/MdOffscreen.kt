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
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 通用离屏位图渲染器：每个页面全 App 共享一个不挂载视图树的 WebView，
 * 串行队列渲染富内容（mermaid 图表 / KaTeX 块级公式）并截取白底位图，
 * 供 Compose 直接以 Image 展示。
 *
 * 相比每实例挂载 AndroidView+WebView：LazyColumn 滑动进出组合零 WebView
 * 创建/销毁开销（这是此前滑动卡顿的最大来源），位图按内容 LruCache，
 * 滚动回看零重渲染 —— 滑动流畅度与内存占用双收益。
 *
 * 可靠性设计与 [MdInlineMathRenderer] 一致：软件层绘制、白底不透明位图、
 * 超时/JS 报错一律 Failed（调用方回退源码卡片），内容绝不可不可见。
 */
object MdOffscreen {

    /** 渲染结果：Ready=位图就绪；Failed=失败/超时（调用方回退源码卡片）；results 无记录=渲染中 */
    sealed interface Result {
        data class Ready(val bitmap: Bitmap) : Result
        data object Failed : Result
    }

    /** 缓存/状态表 key（页面 + 渲染函数 + 着色 + 内容）：着色不同（深浅气泡）需分别缓存 */
    fun keyOf(page: String, renderFn: String, content: String, tint: Color? = null): String =
        if (tint == null) "$page|$renderFn|$content"
        else "$page|$renderFn|${hexOf(tint)}|$content"

    /** Compose 颜色 → JS 可用 #RRGGBB */
    private fun hexOf(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

    private const val TIMEOUT_MS = 8000L
    // 几何上报后等一短拍再截屏：等字体加载/布局稳定，期间如有更新几何以最新为准
    private const val DRAW_DELAY_MS = 120L
    // 截到空白位图时，延迟重渲重试的间隔
    private const val BLANK_RETRY_DELAY_MS = 250L
    private const val PAGE_URL = "https://appassets.androidplatform.net/assets/md/"

    /** Compose 可观察的结果表（key=[keyOf]）；无记录表示尚未就绪 */
    val results = mutableStateMapOf<String, Result>()

    private val handler = Handler(Looper.getMainLooper())

    // 位图缓存 16MB：mermaid 全宽位图较大（1440xH），katex 公式较小
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) results.remove(key)
        }
    }

    private class Job(
        val key: String,
        /** 已拼好的 JS 调用（含引号安全的内容与可选着色参数） */
        val jsCall: String,
        val maxWidthPx: Int,
        /** true=透明底（公式浮在气泡背景上）；false=白底（图表等自带配色的内容） */
        val transparent: Boolean,
    )

    /** 每页面一个 runner：懒建共享 WebView + 串行队列 */
    private class Runner(private val page: String) {
        var webView: WebView? = null
        var loaded = false
        var busy = false
        var current: Job? = null
        var currentW = 0
        var currentH = 0
        var blankRetried = false
        val queue = ArrayDeque<Job>()
        val enqueued = mutableSetOf<String>()

        private val timeoutRunnable = Runnable { current?.let(::fail) }
        private val drawRunnable = Runnable { drawCurrent() }
        private val blankRetryRunnable = Runnable {
            // 空白位图多为截图早于绘制：重新触发一次渲染并重新走几何上报→截图流程
            val job = current ?: return@Runnable
            val wv = webView ?: return@Runnable
            wv.evaluateJavascript(job.jsCall, null)
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun ensure(context: Context) {
            if (webView != null) return
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            // 透明底色：页面自身不画底的区域（公式）保持透明，浮在气泡背景上
            wv.setBackgroundColor(AndroidColor.TRANSPARENT)
            // 软件层：保证 draw(Canvas) 到位图可靠且白底可信
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    loaded = true
                    pump()
                }
            }
            wv.addJavascriptInterface(object {
                /** mermaid 页回调：仅报内容高（物理px），宽取测量宽（容器 width:100% = 测量宽） */
                @JavascriptInterface
                fun onHeight(px: Int) {
                    handler.post {
                        val w = webView?.measuredWidth?.takeIf { it > 0 }
                            ?: current?.maxWidthPx ?: 0
                        onGeometry(w, px)
                    }
                }

                /** katex 页回调：报内容宽高（物理px） */
                @JavascriptInterface
                fun onSize(json: String) {
                    handler.post {
                        val o = runCatching { JSONObject(json) }.getOrNull() ?: return@post
                        onGeometry(o.optInt("w"), o.optInt("h"))
                    }
                }

                @JavascriptInterface
                fun onError(msg: String) {
                    handler.post { current?.let(::fail) }
                }
            }, "LinkZone")
            webView = wv
            wv.loadUrl("$PAGE_URL$page")
        }

        fun request(context: Context, job: Job) {
            cache.get(job.key)?.let {
                results[job.key] = Result.Ready(it)
                return
            }
            if (results.containsKey(job.key) || !enqueued.add(job.key)) return
            ensure(context)
            queue.addLast(job)
            pump()
        }

        /** 串行泵：同一时刻只渲染一条，完成后弹下一条 */
        private fun pump() {
            if (busy || !loaded) return
            val wv = webView ?: return
            val job = queue.removeFirstOrNull() ?: return
            enqueued.remove(job.key)
            busy = true
            current = job
            currentW = 0
            currentH = 0
            blankRetried = false
            // 宽上限 AT_MOST：页面 #c width:100% 撑满测量宽，内容据此排版
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(job.maxWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            wv.layout(0, 0, wv.measuredWidth, wv.measuredHeight)
            wv.evaluateJavascript(job.jsCall, null)
            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        }

        private fun onGeometry(w: Int, h: Int) {
            if (!busy) return
            currentW = w
            currentH = h
            if (currentW <= 0 || currentH <= 0) return
            handler.removeCallbacks(drawRunnable)
            handler.postDelayed(drawRunnable, DRAW_DELAY_MS)
        }

        private fun drawCurrent() {
            val job = current ?: return
            val wv = webView ?: run { fail(job); return }
            val w = currentW.coerceIn(1, job.maxWidthPx)
            val h = currentH.coerceAtLeast(1)
            val bitmap = runCatching {
                // 按上报几何精确排版后再截屏（渲染前 layout 的高度是空内容的）
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                )
                wv.layout(0, 0, w, h)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    // 透明任务不铺底：公式字形直接画在透明画布上
                    canvas.drawColor(if (job.transparent) AndroidColor.TRANSPARENT else AndroidColor.WHITE)
                    wv.draw(canvas)
                }
            }.getOrNull()
            if (bitmap == null) {
                fail(job)
            } else if (!job.transparent && isMostlyBlank(bitmap)) {
                // 白底位图但截不到内容：几何已上报而图形未上屏。延迟重渲一次，仍空白则判失败，
                // 调用方回退源码卡片，避免给用户展示一个巨大的空白盒
                if (!blankRetried) {
                    blankRetried = true
                    handler.postDelayed(blankRetryRunnable, BLANK_RETRY_DELAY_MS)
                } else {
                    fail(job)
                }
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
            handler.removeCallbacks(blankRetryRunnable)
            busy = false
            current = null
            pump()
        }
    }

    private val runners = HashMap<String, Runner>()

    /** 请求渲染（任意线程可调；已缓存/在途自动去重）。
     *  @param tint 非空时作为第二个 JS 参数传给渲染函数（内容着色），并计入缓存 key
     *  @param transparent true=透明底位图（公式），false=白底位图（图表） */
    fun request(
        context: Context,
        page: String,
        renderFn: String,
        content: String,
        maxWidthPx: Int,
        tint: Color? = null,
        transparent: Boolean = false,
    ) {
        if (content.isBlank()) return
        val quoted = JSONObject.quote(content) ?: "\"\""
        val jsCall = if (tint == null) {
            "window.$renderFn($quoted)"
        } else {
            "window.$renderFn($quoted, \"${hexOf(tint)}\")"
        }
        val job = Job(keyOf(page, renderFn, content, tint), jsCall, maxWidthPx, transparent)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            handler.post { runnerFor(page).request(app, job) }
            return
        }
        runnerFor(page).request(context.applicationContext, job)
    }

    private fun runnerFor(page: String): Runner =
        runners.getOrPut(page) { Runner(page) }

    /** 采样检测白底位图是否基本无内容（任通道低于阈值即视为墨迹）。
     *  mermaid 节点描边/文字均为深色，正常图表采样点远超阈值；空白位图为 0。 */
    private fun isMostlyBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val strideX = (bitmap.width / 180).coerceAtLeast(3)
        val strideY = (bitmap.height / 180).coerceAtLeast(3)
        var ink = 0
        var y = 1
        while (y < bitmap.height) {
            var x = 1
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (((p shr 16) and 0xFF) < 245 ||
                    ((p shr 8) and 0xFF) < 245 ||
                    (p and 0xFF) < 245
                ) {
                    if (++ink >= 12) return false
                }
                x += strideX
            }
            y += strideY
        }
        return true
    }
}

/**
 * 订阅离屏渲染结果：null=渲染中（调用方显示占位，避免布局跳动）；
 * Ready=白底位图；Failed=失败/超时（调用方回退源码卡片）。
 * 请求带 300ms 防抖：流式期间内容仍在增长，停顿后才开始渲染，避免逐字重渲染。
 */
@Composable
fun rememberOffscreenRender(
    page: String,
    renderFn: String,
    content: String,
    maxWidthPx: Int,
    tint: Color? = null,
    transparent: Boolean = false,
): MdOffscreen.Result? {
    val context = LocalContext.current
    val key = MdOffscreen.keyOf(page, renderFn, content, tint)
    val result = MdOffscreen.results[key]
    // 仅在结果为空时请求：覆盖首次渲染，也覆盖位图被 LruCache 淘汰后结果消失的情况
    // （此时重新渲染，界面不会永久停在占位）；Ready/Failed 均不重复触发
    LaunchedEffect(key, result) {
        if (result == null) {
            delay(300)
            MdOffscreen.request(context.applicationContext, page, renderFn, content, maxWidthPx, tint, transparent)
        }
    }
    return result
}
