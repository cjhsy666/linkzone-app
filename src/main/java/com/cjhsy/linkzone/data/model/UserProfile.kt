package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户资料（严格对齐 GET /user/profile 与登录返回的 user 结构）。
 * 未知字段由 Json { ignoreUnknownKeys = true } 忽略。
 */
@Serializable
data class UserProfile(
    @SerialName("linkzone_id") val linkzoneId: String = "",
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    val bio: String = "",
    val level: Int = 0,
    @SerialName("level_name") val levelName: String = "",
    val status: Int = 0,
    @SerialName("status_text") val statusText: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
    val email: String = "",
    @SerialName("email_verified") val emailVerified: Boolean = false,
    val phone: String = "",
    @SerialName("phone_verified") val phoneVerified: Boolean = false,
    val balance: Double = 0.0,
    @SerialName("frozen_balance") val frozenBalance: Double = 0.0,
    val points: Int = 0,
    @SerialName("total_earned") val totalEarned: Double = 0.0,
    @SerialName("total_withdraw") val totalWithdraw: Double = 0.0,
    val timezone: String = "",
    val language: String = "",
    val theme: String = "auto",
    val tags: List<String> = emptyList(),
    @SerialName("primary_platform") val primaryPlatform: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("last_active_at") val lastActiveAt: Long = 0L,
    @SerialName("sign_in") val signIn: SignInSummary? = null,
    val stats: UserStats? = null
)

/** profile.sign_in 摘要 */
@Serializable
data class SignInSummary(
    @SerialName("continuous_days") val continuousDays: Int = 0,
    @SerialName("total_days") val totalDays: Int = 0,
    @SerialName("last_sign_in_date") val lastSignInDate: Long = 0L
)

/** profile.stats 摘要 */
@Serializable
data class UserStats(
    @SerialName("total_messages") val totalMessages: Int = 0,
    @SerialName("total_sessions") val totalSessions: Int = 0,
    @SerialName("agent_interactions") val agentInteractions: Int = 0,
    @SerialName("active_days") val activeDays: Int = 0
)

/**
 * PUT /user/profile 请求体（全部可选，只传要改的字段）。
 * 使用显式 null 表示“不修改”。
 */
@Serializable
data class UpdateProfileRequest(
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    val timezone: String? = null,
    val language: String? = null,
    val theme: String? = null
)
