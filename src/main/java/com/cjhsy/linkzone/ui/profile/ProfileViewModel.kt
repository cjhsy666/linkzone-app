package com.cjhsy.linkzone.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.core.network.ApiException
import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.data.model.UserProfile
import com.cjhsy.linkzone.data.repository.AuthRepository
import com.cjhsy.linkzone.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 个人中心 UI 状态（UDF）。
 */
data class ProfileUiState(
    val profile: UserProfile? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loggedOut: Boolean = false
)

/**
 * 个人中心 ViewModel。
 *
 * - 进入页面拉取 profile（401 由 TokenAuthenticator 自动刷新并重放）
 * - 监听 [TokenHolder.sessionExpired]：refresh 也失败时强制登出，UI 跳登录页
 * - 由 Koin 的 viewModel { } 注册，不使用 Hilt 注解
 */
class ProfileViewModel(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val tokenHolder: TokenHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeSession()
        refresh()
    }

    /** 会话失效（refresh 失败）→ 清本地凭证并通知 UI 跳登录页 */
    private fun observeSession() {
        viewModelScope.launch {
            tokenHolder.sessionExpired.collect { expired ->
                if (expired) {
                    authRepository.clearSession()
                    userRepository.clear()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        profile = null,
                        loggedOut = true,
                        error = "登录已过期，请重新登录"
                    )
                }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = userRepository.loadProfile()
            result.fold(
                onSuccess = { profile ->
                    _uiState.value = _uiState.value.copy(
                        profile = profile,
                        isLoading = false,
                        error = null
                    )
                },
                onFailure = { throwable ->
                    val isAuth = (throwable as? ApiException)?.isAuthError == true
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = if (isAuth) "登录已过期，请重新登录"
                        else (throwable.message ?: "加载失败，请稍后重试"),
                        loggedOut = isAuth
                    )
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            userRepository.clear()
            _uiState.value = ProfileUiState(loggedOut = true)
        }
    }
}
