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
        // PdfBox-Android 资源初始化（本地 AI 的 PDF 附件文本提取）
        PdfTextParser.init(this)
        startKoin {
            androidLogger()
            androidContext(this@LinkZoneApp)
            modules(appModule)
        }
    }

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
