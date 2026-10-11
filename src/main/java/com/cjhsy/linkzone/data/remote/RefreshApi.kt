package com.cjhsy.linkzone.data.remote

import com.cjhsy.linkzone.core.network.ApiEnvelope
import com.cjhsy.linkzone.data.model.RefreshResponse
import retrofit2.http.Body
import retrofit2.http.POST
import kotlinx.serialization.Serializable

@Serializable
data class RefreshBody(val refresh_token: String)

/**
 * 刷新 Token 专用接口。
 *
 * CRITICAL: 该接口必须由「不含 AuthInterceptor、不含 TokenAuthenticator」的
 * 独立 Retrofit 实例提供，从根源上杜绝刷新请求再次触发 401 刷新（死循环）。
 */
interface RefreshApi {
    @POST("api/v1/user/refresh")
    suspend fun refresh(@Body body: RefreshBody): ApiEnvelope<RefreshResponse>
}
