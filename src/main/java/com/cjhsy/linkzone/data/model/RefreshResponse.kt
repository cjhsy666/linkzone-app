package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /user/refresh 返回的 data。
 * 每次刷新执行双 Token 轮换，旧凭证立即作废。
 */
@Serializable
data class RefreshResponse(
    val token: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_at") val expiresAt: Long = 0L
)
