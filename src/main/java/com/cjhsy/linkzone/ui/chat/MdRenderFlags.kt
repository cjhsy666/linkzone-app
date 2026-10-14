package com.cjhsy.linkzone.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Markdown 渲染器本地 Feature Flag。
 *
 * [useV2Renderer]（键名 localUseV2Renderer，默认 true）：
 * 关闭后 Markdown 消息回退到旧版纯文本/基础渲染（AutoLinkText 级），
 * V2 富渲染树完全不进入组合——上线后若出现大规模渲染异常可一键兜底，
 * 核心聊天链路始终可用。状态持久化在 "md_render_prefs"，切换即时驱动重组。
 */
object MdRenderFlags {

    private const val PREFS_NAME = "md_render_prefs"
    private const val KEY_USE_V2 = "localUseV2Renderer"

    private var prefs: SharedPreferences? = null

    var useV2Renderer by mutableStateOf(true)
        private set

    /** 幂等初始化；在渲染入口与设置页调用，无 Application 启动顺序依赖 */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            useV2Renderer = p.getBoolean(KEY_USE_V2, true)
        }
    }

    // @JvmName：属性委托生成的 setter 与本函数 JVM 签名相同（setUseV2Renderer(Z)V），需错开
    @JvmName("setUseV2RendererFlag")
    fun setUseV2Renderer(enabled: Boolean) {
        useV2Renderer = enabled
        prefs?.edit()?.putBoolean(KEY_USE_V2, enabled)?.apply()
    }
}
