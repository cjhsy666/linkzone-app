package com.cjhsy.linkzone

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.cjhsy.linkzone.ai.document.PdfTextParser
import com.cjhsy.linkzone.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * 应用入口：初始化 Koin 依赖注入容器。
 *
 * 实现 [ImageLoaderFactory]：为 Coil 全局 ImageLoader 注册 GIF / 动图 WebP 解码器，
 * 聊天中的 .gif 图片与 animation 消息段无需额外配置即可在所有 AsyncImage 中播放。
 */
class LinkZoneApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // #region debug-point chat-gen-crash
        installDebugCrashHandler()
        // #endregion
        // PdfBox-Android 资源初始化（本地 AI 的 PDF 附件文本提取）
        PdfTextParser.init(this)
        startKoin {
            androidLogger()
            androidContext(this@LinkZoneApp)
            modules(appModule)
        }
    }

    // #region debug-point chat-gen-crash
    /**
     * 调试插桩（chat-gen-crash）：未捕获异常落盘到 filesDir/debug_last_crash.txt，
     * 记录崩溃线程、堆内存快照（区分 OOM 与其他异常）与完整堆栈；
     * 下次启动由 MainActivity 弹窗展示供复制回传。随后链回原默认处理。
     */
    private fun installDebugCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching {
                val rt = Runtime.getRuntime()
                val report = buildString {
                    appendLine("time=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    appendLine("thread=${thread.name}")
                    appendLine("heapUsedMB=${(rt.totalMemory() - rt.freeMemory()) / 1048576}")
                    appendLine("heapMaxMB=${rt.maxMemory() / 1048576}")
                    appendLine("---- stack ----")
                    appendLine(android.util.Log.getStackTraceString(err))
                }
                java.io.File(filesDir, "debug_last_crash.txt").writeText(report)
            }
            prev?.uncaughtException(thread, err) ?: kotlin.system.exitProcess(2)
        }
    }
    // #endregion

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
}
