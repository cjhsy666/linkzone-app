package com.cjhsy.linkzone.core.network

import com.cjhsy.linkzone.di.BASE_URL
import org.koin.core.context.GlobalContext

/**
 * 服务器媒体请求鉴权辅助。
 *
 * 服务端已将 /public/uploads/ 与 /public/chat-media/ 改为登录后可下载（防附件 URL 外泄被匿名拖取），
 * APP 侧所有直连本服务器的媒体加载（图片/视频/音频/文件下载/AI 取图）统一经此处附带 Bearer token；
 * 指向外部站点的 URL 不带 token，避免凭证泄漏给第三方。
 */
object MediaAuth {

    private val serverHost: String? by lazy {
        runCatching { java.net.URI(BASE_URL).host }.getOrNull()
    }

    /** 当前请求头（空 map = 无需鉴权/未登录） */
    fun headersFor(url: String): Map<String, String> {
        val host = serverHost ?: return emptyMap()
        val urlHost = runCatching { java.net.URI(url).host }.getOrNull() ?: return emptyMap()
        if (!host.equals(urlHost, ignoreCase = true)) return emptyMap()
        val token = runCatching { GlobalContext.get().get<TokenHolder>().accessToken }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    /**
     * 供外部浏览器/系统组件打开的服务器 URL：以 ?token= 形式附带凭证
     * （外部应用无法设置 Authorization 请求头，服务端 /public 处理器同样接受 query token）
     */
    fun urlWithToken(url: String): String {
        val token = headersFor(url)["Authorization"]?.removePrefix("Bearer ") ?: return url
        val sep = if (url.contains('?')) "&" else "?"
        return url + sep + "token=" + java.net.URLEncoder.encode(token, "UTF-8")
    }
}
