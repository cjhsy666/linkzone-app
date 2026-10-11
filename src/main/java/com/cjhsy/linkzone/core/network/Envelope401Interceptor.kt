package com.cjhsy.linkzone.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 统一响应包 401 改写拦截器（必须注册为 network interceptor）。
 *
 * 背景：后端所有业务错误都以 HTTP 200 + body `{"code":xxx}` 返回，
 * 而 OkHttp 的 [TokenAuthenticator] 只在真实 HTTP 401 时才触发刷新。
 * 该拦截器在响应阶段嗅探 JSON 包络首部，发现 `code == 401` 时把响应
 * 改写为真实 HTTP 401，使刷新流程对包络式 401 同样生效。
 *
 * 为何必须是 network interceptor：Authenticator 由 RetryAndFollowUpInterceptor
 * 调用，它位于应用拦截器之下、网络拦截器之上；应用拦截器改写的 401
 * 它永远看不到，网络拦截器改写的 401 才会触发刷新并重放请求。
 *
 * 附带效果：冷启动 token 丢失时，首个请求会先内部 401 → 自动 refresh →
 * 重放成功，用户无感知，无需额外的启动预恢复逻辑。
 */
class Envelope401Interceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // 只处理 HTTP 200 的 JSON 包络；真实 401 直接放行（Authenticator 自己会处理）
        if (response.code != 200) return response
        val contentType = response.header("Content-Type") ?: return response
        if (!contentType.contains("json", ignoreCase = true)) return response

        // 只窥探首部一小段：统一包络的 code 字段固定在最前
        val head = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrNull()
            ?: return response
        if (!ENVELOPE_CODE_401.containsMatchIn(head)) return response

        // 保留原始 body 改写状态码：刷新成功则请求被重放，上层只看到最终结果；
        // 刷新失败则 401 原样上传（body 仍是包络 JSON，可正常解析错误信息）
        return response.newBuilder()
            .code(401)
            .message("Unauthorized")
            .build()
    }

    companion object {
        private const val PEEK_BYTES = 512L

        /** 匹配包络首部 `"code":401`（兼容空白字符，避免误伤 4010 等业务码） */
        private val ENVELOPE_CODE_401 = Regex("^\\s*\\{\\s*\"code\"\\s*:\\s*401\\b")
    }
}
