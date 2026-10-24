package com.cjhsy.linkzone

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.cjhsy.linkzone.core.network.MediaAuth
import com.cjhsy.linkzone.di.appModule
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class LinkZoneApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        startKoin {
            androidContext(this@LinkZoneApp)
            modules(appModule)
        }
    }

    /** 崩溃落盘：下次启动由 MainActivity 弹窗展示，便于无 adb 环境定位闪退 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    append("thread: ").append(thread.name).append('\n')
                    append("time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
                    append("device: ").append(android.os.Build.MODEL).append(" / Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                    append("abi: ").append(android.os.Build.SUPPORTED_ABIS.joinToString()).append('\n')
                    append('\n')
                    append(android.util.Log.getStackTraceString(throwable))
                }
                java.io.File(filesDir, "last_crash.txt").writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Coil 全局 ImageLoader：注册 GIF/动图 WebP 解码（API 28+ 走 ImageDecoder，以下走 GifDecoder）。
     * 经 OkHttp 拦截器为本机服务器的媒体请求（/public/uploads/ 等已需登录鉴权）附带 Bearer token；
     * 外部 URL 由 MediaAuth 自动跳过，不向第三方泄漏凭证。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val headers = MediaAuth.headersFor(req.url.toString())
                        if (headers.isEmpty()) {
                            chain.proceed(req)
                        } else {
                            chain.proceed(req.newBuilder().apply {
                                headers.forEach { (k, v) -> header(k, v) }
                            }.build())
                        }
                    }
                    .build()
            )
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
}
