package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.data.model.InviteCodeResponse
import com.cjhsy.linkzone.data.model.InviteesResponse
import com.cjhsy.linkzone.data.model.InviterResponse
import com.cjhsy.linkzone.data.model.NotificationsResponse
import com.cjhsy.linkzone.data.model.SignInCalendar
import com.cjhsy.linkzone.data.model.SignInResult
import com.cjhsy.linkzone.data.model.SignInStatus
import com.cjhsy.linkzone.data.remote.UserApi

/**
 * 互动仓库：签到、邀请、通知。
 */
class EngageRepository(
    private val userApi: UserApi,
    private val tokenHolder: TokenHolder
) {

    // ---------- 签到 ----------
    suspend fun signIn(): Result<SignInResult> =
        runEnvelope(tokenHolder) { userApi.signIn() }

    suspend fun loadSignInStatus(): Result<SignInStatus> =
        runEnvelope(tokenHolder) { userApi.getSignInStatus() }

    suspend fun loadSignInCalendar(month: String? = null): Result<SignInCalendar> =
        runEnvelope(tokenHolder) { userApi.getSignInCalendar(month) }

    // ---------- 邀请 ----------
    suspend fun loadInviteCode(): Result<InviteCodeResponse> =
        runEnvelope(tokenHolder) { userApi.getInviteCode() }

    suspend fun loadInvitees(): Result<InviteesResponse> =
        runEnvelope(tokenHolder) { userApi.getInvitees() }

    suspend fun loadInviter(): Result<InviterResponse> =
        runEnvelope(tokenHolder) { userApi.getInviter() }

    // ---------- 通知 ----------
    suspend fun loadNotifications(limit: Int = 50): Result<NotificationsResponse> =
        runEnvelope(tokenHolder) { userApi.getNotifications(limit) }

    suspend fun loadUnreadCount(): Result<Int> =
        runEnvelope(tokenHolder) { userApi.getUnreadCount() }.map { it.unreadCount }

    suspend fun markRead(id: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { userApi.markNotificationRead(id) }

    suspend fun markAllRead(): Result<Unit> =
        runUnitEnvelope(tokenHolder) { userApi.markAllNotificationsRead() }
}
