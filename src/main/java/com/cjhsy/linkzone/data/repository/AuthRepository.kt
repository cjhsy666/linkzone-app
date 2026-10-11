package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.core.storage.SecureStore
import com.cjhsy.linkzone.data.model.ForgotPasswordRequest
import com.cjhsy.linkzone.data.model.LoginRequest
import com.cjhsy.linkzone.data.model.LoginResponse
import com.cjhsy.linkzone.data.model.ResetPasswordRequest
import com.cjhsy.linkzone.data.remote.ChatSocketManager
import com.cjhsy.linkzone.data.remote.UserApi

/**
 * 认证仓库：登录 / 登出 / 找回密码 / 重置密码。
 *
 * - 登录成功后：access token 存内存 TokenHolder，refresh_token 存 SecureStore
 * - 登出：先请求服务端撤销会话，无论成功与否都清空本地凭证并断开 WebSocket
 */
class AuthRepository(
    private val userApi: UserApi,
    private val secureStore: SecureStore,
    private val tokenHolder: TokenHolder,
    private val socketManager: ChatSocketManager
) {

    fun isLoggedIn(): Boolean = secureStore.isLoggedIn()

    fun getUserId(): String? = secureStore.getUserId()

    suspend fun login(identifier: String, password: String): Result<LoginResponse> =
        runCatching {
            val envelope = userApi.login(LoginRequest(identifier.trim(), password))
            if (!envelope.isSuccess) throw envelope.toException()
            val data = envelope.requireData()
            // 踢下线/过期后重登时可能残留旧身份的 WebSocket，先断开；
            // 稍后 ChatViewModel.connect() 会以新 token 重连
            socketManager.disconnect()
            tokenHolder.update(data.token, data.expiresAt)
            secureStore.saveRefreshToken(data.refreshToken)
            data.user?.linkzoneId?.takeIf { it.isNotEmpty() }?.let { secureStore.saveUserId(it) }
            data
        }.recoverCatching { throw it.toApiException() }

    /** 忘记密码：发送验证码（恒定返回，防账号枚举） */
    suspend fun forgotPassword(identifier: String): Result<Unit> =
        runCatching {
            val envelope = userApi.forgotPassword(ForgotPasswordRequest(identifier.trim()))
            if (!envelope.isSuccess) throw envelope.toException()
        }.recoverCatching { throw it.toApiException() }

    /** 重置密码：成功后全部会话被强制登出 */
    suspend fun resetPassword(identifier: String, code: String, newPassword: String): Result<Unit> =
        runCatching {
            val envelope = userApi.resetPassword(
                ResetPasswordRequest(identifier.trim(), code.trim(), newPassword)
            )
            if (!envelope.isSuccess) throw envelope.toException()
            clearSession()
        }.recoverCatching { throw it.toApiException() }

    /** 退出登录：清空本地凭证，服务端撤销失败不阻塞流程 */
    suspend fun logout(): Result<Unit> {
        runCatching { userApi.logout() }
        clearSession()
        return Result.success(Unit)
    }

    /** 退出所有设备 */
    suspend fun logoutAll(): Result<Unit> {
        runCatching { userApi.logoutAll() }
        clearSession()
        return Result.success(Unit)
    }

    /** 清空本地全部凭证并断开实时连接（所有登出路径统一入口） */
    fun clearSession() {
        socketManager.disconnect()
        secureStore.clear()
        tokenHolder.clear()
    }
}
