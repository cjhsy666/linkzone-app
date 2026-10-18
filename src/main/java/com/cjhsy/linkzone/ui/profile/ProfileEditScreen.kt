package com.cjhsy.linkzone.ui.profile

import com.cjhsy.linkzone.core.error.toUserMessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.data.model.UpdateProfileRequest
import com.cjhsy.linkzone.data.repository.UserRepository
import com.cjhsy.linkzone.di.BASE_URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class ProfileEditUiState(
    val username: String = "",
    val bio: String = "",
    val theme: String = "auto",
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

/**
 * 资料编辑 ViewModel：修改用户名 / 简介 / 主题，以及单独修改密码。
 * 头像修改走 avatarUrl 字段（当前为 URL 输入占位，后续接图片上传接口）。
 */
class ProfileEditViewModel(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val result = userRepository.loadProfile()
            result.onSuccess { p ->
                _state.value = _state.value.copy(
                    username = p.username,
                    bio = p.bio,
                    theme = p.theme
                )
            }
        }
    }

    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v, error = null) }
    fun onBioChange(v: String) { _state.value = _state.value.copy(bio = v, error = null) }
    fun onThemeChange(v: String) { _state.value = _state.value.copy(theme = v, error = null) }
    fun onOldPasswordChange(v: String) { _state.value = _state.value.copy(oldPassword = v, error = null) }
    fun onNewPasswordChange(v: String) { _state.value = _state.value.copy(newPassword = v, error = null) }
    fun onConfirmPasswordChange(v: String) { _state.value = _state.value.copy(confirmPassword = v, error = null) }

    fun saveProfile() {
        val s = _state.value
        if (s.loading) return
        val username = s.username.trim()
        if (username.length < 3 || username.length > 32) {
            _state.value = s.copy(error = "用户名需为 3-32 位")
            return
        }
        if (s.bio.length > 200) {
            _state.value = s.copy(error = "简介不能超过 200 字")
            return
        }
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = userRepository.updateProfile(
                UpdateProfileRequest(username = username, bio = s.bio, theme = s.theme)
            )
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, saved = true) },
                onFailure = { _state.value.copy(loading = false, error = it.toUserMessage("保存失败")) }
            )
        }
    }

    fun changePassword() {
        val s = _state.value
        if (s.loading) return
        if (s.oldPassword.isBlank()) {
            _state.value = s.copy(error = "请输入原密码")
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
            val result = userRepository.changePassword(s.oldPassword, s.newPassword)
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, error = null, oldPassword = "", newPassword = "", confirmPassword = "", saved = true) },
                onFailure = { _state.value.copy(loading = false, error = it.toUserMessage("修改失败")) }
            )
        }
    }
}

@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ProfileEditViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(text = "编辑资料", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))

        // 基本资料卡
        SectionCard(title = "基本资料") {
            // 头像
            AvatarEditor(
                username = state.username,
                onAvatarClick = { /* TODO: 接图片上传接口，当前为 URL 输入占位 */ }
            )
            Spacer(Modifier.height(16.dp))

            EditTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = "用户名",
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            EditTextField(
                value = state.bio,
                onValueChange = viewModel::onBioChange,
                label = "个人简介",
                minLines = 3
            )
            Spacer(Modifier.height(12.dp))

            // 主题分段选择
            Text(
                text = "主题",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ThemeSegmented(
                selected = state.theme,
                onSelect = viewModel::onThemeChange
            )
            Spacer(Modifier.height(16.dp))

            GradientButton(
                text = "保存资料",
                loading = state.loading,
                onClick = viewModel::saveProfile
            )
        }

        Spacer(Modifier.height(16.dp))

        // 修改密码卡
        SectionCard(title = "修改密码") {
            EditTextField(
                value = state.oldPassword,
                onValueChange = viewModel::onOldPasswordChange,
                label = "原密码",
                singleLine = true,
                isPassword = true
            )
            Spacer(Modifier.height(12.dp))

            EditTextField(
                value = state.newPassword,
                onValueChange = viewModel::onNewPasswordChange,
                label = "新密码",
                singleLine = true,
                isPassword = true
            )
            Spacer(Modifier.height(12.dp))

            EditTextField(
                value = state.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "确认新密码",
                singleLine = true,
                isPassword = true
            )
            Spacer(Modifier.height(16.dp))

            GradientButton(
                text = "修改密码",
                loading = state.loading,
                onClick = viewModel::changePassword
            )
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(12.dp)
            ) {
                Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ============ 头像编辑 ============

@Composable
private fun AvatarEditor(username: String, onAvatarClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = username.take(1).ifBlank { "灵" },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "头像",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "点击修改头像",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable(onClick = onAvatarClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "更换头像",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ============ 主题分段选择 ============

private val themeOptions = listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色")

@Composable
private fun ThemeSegmented(
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        themeOptions.forEach { (value, label) ->
            val isSelected = selected == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else null,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============ 通用组件 ============

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun GradientButton(
    text: String,
    loading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** 头像 URL 归一化（预留，头像上传功能接入后使用） */
@Suppress("unused")
private fun resolveAvatarUrl(url: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) url
    else BASE_URL.trimEnd('/') + "/" + url.trimStart('/')
