package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.data.model.ChangePasswordRequest
import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.core.storage.SecureStore
import com.cjhsy.linkzone.data.model.UpdateProfileRequest
import com.cjhsy.linkzone.data.model.UserProfile
import com.cjhsy.linkzone.data.remote.UserApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户资料 / 密码 / 设备会话仓库。
 *
 * 401 由 OkHttp 的 TokenAuthenticator 自动刷新并重放，
 * 这里只处理最终结果，并在鉴权失败时标记会话失效。
 */
class UserRepository(
    private val userApi: UserApi,
    private val secureStore: SecureStore,
    private val tokenHolder: TokenHolder
) {

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    /** 拉取最新资料并写入缓存 */
    suspend fun loadProfile(): Result<UserProfile> =
        runEnvelope(tokenHolder) { userApi.getProfile() }
            .onSuccess {
                _profile.value = it
                if (it.linkzoneId.isNotEmpty()) secureStore.saveUserId(it.linkzoneId)
            }

    /** 修改个人信息，返回更新后的完整 profile */
    suspend fun updateProfile(body: UpdateProfileRequest): Result<UserProfile> =
        runEnvelope(tokenHolder) { userApi.updateProfile(body) }
            .onSuccess { _profile.value = it }

    /** 修改密码：成功后除当前设备外全部会话失效 */
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) {
            userApi.changePassword(ChangePasswordRequest(oldPassword, newPassword))
        }

    /** 登录设备列表 */
    suspend fun loadSessions(): Result<List<com.cjhsy.linkzone.data.model.SessionItem>> =
        runEnvelope(tokenHolder) { userApi.getSessions() }.map { it.sessions }

    /** 踢掉指定设备 */
    suspend fun kickSession(sessionId: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { userApi.kickSession(sessionId) }

    /** 注销账号（30 天冷静期，全部会话撤销） */
    suspend fun deactivate(): Result<Unit> =
        runUnitEnvelope(tokenHolder) { userApi.deactivate() }

    /** 退出登录或刷新失败时清空缓存 */
    fun clear() {
        _profile.value = null
    }
}
