package com.cjhsy.linkzone.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ========== 签到 POST /user/sign-in ==========
@Serializable
data class SignInResult(
    val points: Int = 0,
    @SerialName("continuous_bonus") val continuousBonus: Int = 0,
    @SerialName("total_points") val totalPoints: Int = 0
)

// ========== 签到状态 GET /user/sign-in/status ==========
@Serializable
data class SignInStatus(
    @SerialName("signed_today") val signedToday: Boolean = false,
    @SerialName("continuous_days") val continuousDays: Int = 0,
    @SerialName("total_days") val totalDays: Int = 0,
    @SerialName("last_sign_in_date") val lastSignInDate: Long = 0L
)

// ========== 签到日历 GET /user/sign-in/calendar ==========
@Serializable
data class SignInCalendar(
    val month: String = "",
    @SerialName("signed_days") val signedDays: List<String> = emptyList(),
    @SerialName("signed_count") val signedCount: Int = 0,
    @SerialName("days_in_month") val daysInMonth: Int = 0
)

// ========== 邀请 GET /user/invite/code ==========
@Serializable
data class InviteCodeResponse(
    @SerialName("invite_code") val inviteCode: String = "",
    @SerialName("invitee_count") val inviteeCount: Int = 0
)

// ========== 我的被邀请人 GET /user/invitees ==========
@Serializable
data class InviteesResponse(
    val invitees: List<InviteeItem> = emptyList(),
    val total: Int = 0
)

@Serializable
data class InviteeItem(
    @SerialName("linkzone_id") val linkzoneId: String = "",
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("last_active_at") val lastActiveAt: Long = 0L
)

// ========== 我的邀请人 GET /user/inviter ==========
@Serializable
data class InviterResponse(
    val inviter: InviterItem? = null
)

@Serializable
data class InviterItem(
    @SerialName("linkzone_id") val linkzoneId: String = "",
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String = ""
)

// ========== 设备会话 GET /user/sessions ==========
@Serializable
data class SessionsResponse(
    val sessions: List<SessionItem> = emptyList(),
    val total: Int = 0
)

@Serializable
data class SessionItem(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("expires_at") val expiresAt: Long = 0L,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("is_current") val isCurrent: Boolean = false
)

// ========== 通知 GET /user/notifications ==========
@Serializable
data class NotificationsResponse(
    val items: List<NotificationItem> = emptyList(),
    val total: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0
)

@Serializable
data class NotificationItem(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val content: String = "",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0L,
    val category: String = "personal"
)

@Serializable
data class UnreadCountResponse(
    @SerialName("unread_count") val unreadCount: Int = 0
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
    val unread: Int = 0
)
