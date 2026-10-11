package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /user/login 返回的 data。
 *
 * 后端同时返回 token / refresh_token / expires_at 以及 user 信息。
 */
@Serializable
data class LoginResponse(
    val token: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0L,
    val user: UserProfile? = null
)

/**
 * POST /user/login 请求体。
 * identifier 支持 LinkZoneID / 用户名 / 邮箱 三种。
 */
@Serializable
data class LoginRequest(
    val identifier: String,
    val password: String
)

/**
 * 通用空响应（logout 等接口 data 为 null）。
 */
@Serializable
data class EmptyData(val ok: Boolean = true)
