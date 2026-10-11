package com.cjhsy.linkzone.core.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * 请求拦截器：自动为每个请求附加 Authorization: Bearer <token>。
 *
 * Access Token 仅存内存（[TokenHolder]），进程被杀即失效，
 * 依赖 [TokenAuthenticator] 通过 refresh_token 恢复。
 */
class AuthInterceptor(
    private val tokenHolder: TokenHolder
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenHolder.accessToken

        val request = if (!token.isNullOrEmpty() && original.header("Authorization") == null) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
