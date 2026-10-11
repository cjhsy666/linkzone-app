package com.cjhsy.linkzone.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class ForgotPasswordUiState(
    val identifier: String = "",
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val codeSent: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val done: Boolean = false
)

/**
 * 找回密码 ViewModel，两步流程：
 * 1) 发送验证码（服务端恒返回成功，防账号枚举）
 * 2) 提交 identifier + code + new_password 重置
 */
class ForgotPasswordViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    fun onIdentifierChange(v: String) { _state.value = _state.value.copy(identifier = v, error = null) }
    fun onCodeChange(v: String) { _state.value = _state.value.copy(code = v, error = null) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(newPassword = v, error = null) }
    fun onConfirmChange(v: String) { _state.value = _state.value.copy(confirmPassword = v, error = null) }

    fun sendCode() {
        val s = _state.value
        if (s.loading) return
        if (s.identifier.isBlank()) {
            _state.value = s.copy(error = "请输入用户名 / 邮箱 / 手机号")
            return
        }
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = authRepository.forgotPassword(s.identifier)
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, codeSent = true) },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "发送失败") }
            )
        }
    }

    fun reset() {
        val s = _state.value
        if (s.loading) return
        if (s.code.isBlank()) {
            _state.value = s.copy(error = "请输入验证码")
            return
        }
        if (s.newPassword.length < 6 || s.newPassword.length > 64) {
            _state.value = s.copy(error = "新密码需为 6-64 位")
            return
        }
        if (s.newPassword != s.confirmPassword) {
            _state.value = s.copy(error = "两次输入的密码不一致")
            return
        }
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = authRepository.resetPassword(s.identifier, s.code, s.newPassword)
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, done = true) },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "重置失败") }
            )
        }
    }
}

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ForgotPasswordViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "找回密码",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.identifier,
            onValueChange = viewModel::onIdentifierChange,
            label = { Text("用户名 / 邮箱 / 手机号") },
            singleLine = true,
            enabled = !state.codeSent,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        if (!state.codeSent) {
            Button(
                onClick = viewModel::sendCode,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("发送验证码")
                }
            }
        } else {
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::onCodeChange,
                label = { Text("验证码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.newPassword,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.confirmPassword,
                onValueChange = viewModel::onConfirmChange,
                label = { Text("确认新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = viewModel::reset,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("重置密码")
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = viewModel::sendCode,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新发送验证码")
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, enabled = !state.loading) {
            Text("返回登录")
        }
    }
}
