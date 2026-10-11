package com.cjhsy.linkzone.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Access Token 内存持有器。
 *
 * - 仅存于内存，进程被杀即失效
 * - 通过 refresh_token 在 [TokenAuthenticator] 中恢复
 * - 同时暴露登录态 StateFlow，供 UI 层响应（如被踢下线时跳登录页）
 */
class TokenHolder {

    @Volatile
    var accessToken: String? = null
        private set

    private val _sessionExpired = MutableStateFlow(false)
    /** true 表示 refresh 失败，需要 UI 跳转登录页 */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun update(token: String?, expiresAt: Long = 0L) {
        accessToken = token
        if (!token.isNullOrEmpty()) {
            _sessionExpired.value = false
        }
    }

    /** 主动标记会话失效（refresh 也失败时调用） */
    fun markExpired() {
        accessToken = null
        _sessionExpired.value = true
    }

    fun clear() {
        accessToken = null
        _sessionExpired.value = false
    }
}
