package com.cjhsy.linkzone.data.remote

import com.cjhsy.linkzone.core.network.ApiEnvelope
import com.cjhsy.linkzone.data.model.*
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 用户端全部接口（对齐 USER_API.md）。
 * 路径前缀统一为 api/v1/。
 */
interface UserApi {

    // ---------- 认证 ----------
    @GET("api/v1/user/captcha")
    suspend fun getCaptcha(): ApiEnvelope<CaptchaResponse>

    @POST("api/v1/user/login")
    suspend fun login(@Body body: LoginRequest): ApiEnvelope<LoginResponse>

    @POST("api/v1/user/logout")
    suspend fun logout(): ApiEnvelope<EmptyData?>

    @POST("api/v1/user/logout_all")
    suspend fun logoutAll(): ApiEnvelope<EmptyData?>

    @POST("api/v1/user/password/forgot")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ApiEnvelope<EmptyData?>

    @POST("api/v1/user/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): ApiEnvelope<EmptyData?>

    @PUT("api/v1/user/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): ApiEnvelope<EmptyData?>

    // ---------- 资料 ----------
    @GET("api/v1/user/profile")
    suspend fun getProfile(): ApiEnvelope<UserProfile>

    @PUT("api/v1/user/profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): ApiEnvelope<UserProfile>

    @POST("api/v1/user/deactivate")
    suspend fun deactivate(): ApiEnvelope<EmptyData?>

    // ---------- 资产 ----------
    @GET("api/v1/user/balance")
    suspend fun getBalance(): ApiEnvelope<BalanceOverview>

    @GET("api/v1/user/balance/history")
    suspend fun getBalanceHistory(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<PageData<BalanceLog>>

    @GET("api/v1/user/points/history")
    suspend fun getPointsHistory(): ApiEnvelope<PointsHistoryResponse>

    @POST("api/v1/user/withdrawals")
    suspend fun applyWithdraw(@Body body: WithdrawRequest): ApiEnvelope<Withdrawal>

    @GET("api/v1/user/withdrawals")
    suspend fun getWithdrawals(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<PageData<Withdrawal>>

    @GET("api/v1/user/withdrawals/config")
    suspend fun getWithdrawConfig(): ApiEnvelope<WithdrawConfig>

    // ---------- 签到 ----------
    @POST("api/v1/user/sign-in")
    suspend fun signIn(): ApiEnvelope<SignInResult>

    @GET("api/v1/user/sign-in/status")
    suspend fun getSignInStatus(): ApiEnvelope<SignInStatus>

    @GET("api/v1/user/sign-in/calendar")
    suspend fun getSignInCalendar(@Query("month") month: String? = null): ApiEnvelope<SignInCalendar>

    // ---------- 邀请 ----------
    @GET("api/v1/user/invite/code")
    suspend fun getInviteCode(): ApiEnvelope<InviteCodeResponse>

    @GET("api/v1/user/invitees")
    suspend fun getInvitees(): ApiEnvelope<InviteesResponse>

    @GET("api/v1/user/inviter")
    suspend fun getInviter(): ApiEnvelope<InviterResponse>

    // ---------- 设备会话 ----------
    @GET("api/v1/user/sessions")
    suspend fun getSessions(): ApiEnvelope<SessionsResponse>

    @DELETE("api/v1/user/sessions/{id}")
    suspend fun kickSession(@Path("id") sessionId: String): ApiEnvelope<EmptyData?>

    // ---------- 智能体 ----------
    @GET("api/v1/user/agents")
    suspend fun getAgents(): ApiEnvelope<AgentsResponse>

    @POST("api/v1/user/agents")
    suspend fun createAgent(@Body body: CreateMyAgentRequest): ApiEnvelope<CreateMyAgentResponse>

    @GET("api/v1/user/agents/mine")
    suspend fun getMyAgents(): ApiEnvelope<AgentsResponse>

    @PUT("api/v1/user/agents/{id}")
    suspend fun updateMyAgent(@Path("id") id: String, @Body body: UpdateMyAgentRequest): ApiEnvelope<EmptyData?>

    @DELETE("api/v1/user/agents/{id}")
    suspend fun deleteMyAgent(@Path("id") id: String): ApiEnvelope<EmptyData?>

    @GET("api/v1/user/agent/preference")
    suspend fun getAgentPreference(): ApiEnvelope<AgentPreference>

    @PUT("api/v1/user/agent/preference")
    suspend fun updateAgentPreference(@Body body: UpdateAgentPreferenceRequest): ApiEnvelope<AgentPreference>

    // ---------- 通知 ----------
    @GET("api/v1/user/notifications")
    suspend fun getNotifications(@Query("limit") limit: Int = 50): ApiEnvelope<NotificationsResponse>

    @GET("api/v1/user/notifications/unread_count")
    suspend fun getUnreadCount(): ApiEnvelope<UnreadCountResponse>

    @POST("api/v1/user/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): ApiEnvelope<EmptyData?>

    @POST("api/v1/user/notifications/read_all")
    suspend fun markAllNotificationsRead(): ApiEnvelope<EmptyData?>
}
