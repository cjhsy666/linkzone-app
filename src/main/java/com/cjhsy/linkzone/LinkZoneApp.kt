package com.cjhsy.linkzone

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.cjhsy.linkzone.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class LinkZoneApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@LinkZoneApp)
            modules(appModule)
        }
    }

    /** Coil 全局 ImageLoader：注册 GIF/动图 WebP 解码（API 28+ 走 ImageDecoder，以下走 GifDecoder） */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
}
