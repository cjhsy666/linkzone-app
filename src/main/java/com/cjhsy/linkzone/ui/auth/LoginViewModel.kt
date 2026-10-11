package com.cjhsy.linkzone.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.core.network.ApiException
import com.cjhsy.linkzone.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 登录页 UI 状态（UDF：单一 State 驱动界面）。
 */
data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false
) {
    val canSubmit: Boolean
        get() = identifier.isNotBlank() && password.isNotBlank() && !isLoading
}

/**
 * 登录 ViewModel。
 *
 * - 由 Koin 的 viewModel { } 注册（不使用 Hilt 注解）
 * - identifier 支持 LinkZoneID / 用户名 / 邮箱，不做纯数字限制
 */
class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onIdentifierChange(value: String) {
        _uiState.value = _uiState.value.copy(identifier = value, error = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun login() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.value = state.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = authRepository.login(state.identifier, state.password)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        loginSuccess = true
                    )
                },
                onFailure = { throwable ->
                    val message = (throwable as? ApiException)?.message
                        ?: throwable.message
                        ?: "登录失败，请稍后重试"
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = message,
                        loginSuccess = false
                    )
                }
            )
        }
    }
}
