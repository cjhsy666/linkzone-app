package com.cjhsy.linkzone.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.data.model.UserProfile
import com.cjhsy.linkzone.data.remote.ChatSocketManager
import com.cjhsy.linkzone.data.repository.AuthRepository
import com.cjhsy.linkzone.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：为左侧抽屉提供用户资料摘要，并处理登出。
 *
 * 全局监听 [TokenHolder.sessionExpired]：用户停留在聊天等任意 Tab 时，
 * 若 refresh token 也失效，同样能清会话并跳登录页（原先仅"我的"页监听）。
 */
class MainViewModel(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val tokenHolder: TokenHolder,
    private val socketManager: ChatSocketManager
) : ViewModel() {

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    init {
        // 登录进入主界面即建立实时连接并启动前台保活，
        // 不依赖用户是否打开过聊天页；connect 幂等。
        socketManager.connect()
        refresh()
        observeSessionExpired()
    }

    private fun observeSessionExpired() {
        viewModelScope.launch {
            tokenHolder.sessionExpired.collect { expired ->
                if (expired) {
                    authRepository.clearSession()
                    userRepository.clear()
                    _profile.value = null
                    _loggedOut.value = true
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _profile.value = userRepository.loadProfile().getOrNull()
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            userRepository.clear()
            _loggedOut.value = true
        }
    }
}
