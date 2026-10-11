package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ========== 图形验证码 GET /user/captcha ==========
@Serializable
data class CaptchaResponse(
    @SerialName("captcha_id") val captchaId: String = "",
    val svg: String = "",
    @SerialName("expires_in") val expiresIn: Int = 300
)

// ========== 忘记密码 POST /user/password/forgot ==========
@Serializable
data class ForgotPasswordRequest(
    val identifier: String
)

// ========== 重置密码 POST /user/password/reset ==========
@Serializable
data class ResetPasswordRequest(
    val identifier: String,
    val code: String,
    @SerialName("new_password") val newPassword: String
)

// ========== 修改密码 PUT /user/password ==========
@Serializable
data class ChangePasswordRequest(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)

// ========== 上传头像 POST /user/avatar ==========
@Serializable
data class AvatarUploadResponse(
    @SerialName("avatar_url") val avatarUrl: String = "",
    val url: String = ""
)
