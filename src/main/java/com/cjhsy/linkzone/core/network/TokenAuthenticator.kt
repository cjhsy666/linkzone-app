package com.cjhsy.linkzone.core.network

import com.cjhsy.linkzone.core.storage.SecureStore
import com.cjhsy.linkzone.data.remote.RefreshApi
import com.cjhsy.linkzone.data.remote.RefreshBody
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 自动刷新认证器（防死循环）。
 *
 * 关键设计：
 * 1. 使用 [refreshApiProvider] 提供的独立 Retrofit（无 AuthInterceptor / 无本 Authenticator），
 *    refresh 请求本身绝不会再次触发刷新，从根源上避免死循环。
 * 2. 使用 synchronized 锁（双重检查）保证并发 401 只发起一次刷新，其余线程复用新 token。
 * 3. 刷新失败 → 清空本地凭证 + 标记 sessionExpired，由 UI 层跳转登录页。
 */
class TokenAuthenticator(
    private val secureStore: SecureStore,
    private val tokenHolder: TokenHolder,
    private val refreshApiProvider: () -> RefreshApi
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 防死循环第 1 道闸：refresh 接口自身返回 401 时直接放弃
        if (response.request.url.encodedPath.endsWith("/user/refresh")) return null

        // 防死循环第 2 道闸：同一请求最多重试一次
        if (responseCount(response) >= 2) return null

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(lock) {
            val currentToken = tokenHolder.accessToken

            // 其它线程已经刷新成功：直接用新 token 重放
            if (!currentToken.isNullOrEmpty() && currentToken != failedToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val refreshToken = secureStore.getRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                onRefreshFailed()
                return null
            }

            // 当前线程负责刷新（同步阻塞，OkHttp Authenticator 回调线程）
            val newToken = runBlocking {
                try {
                    val envelope = refreshApiProvider().refresh(RefreshBody(refreshToken))
                    if (envelope.isSuccess && envelope.data != null) {
                        val data = envelope.data
                        tokenHolder.update(data.token, data.expiresAt)
                        data.refreshToken?.let { secureStore.saveRefreshToken(it) }
                        data.token
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
            }

            if (newToken.isNullOrEmpty()) {
                // 并发刷新可能已由其他路径（如 WS 自恢复）完成：
                // token 已更新则直接重放，不要误清凭证把用户登出
                val concurrent = tokenHolder.accessToken
                if (!concurrent.isNullOrEmpty() && concurrent != failedToken) {
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $concurrent")
                        .build()
                }
                onRefreshFailed()
                return null
            }

            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }
    }

    private fun onRefreshFailed() {
        secureStore.clear()
        tokenHolder.markExpired()
    }

    /** 统计该请求链上已重试次数，超过 1 次即放弃 */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
