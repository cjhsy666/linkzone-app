package com.cjhsy.linkzone.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 统一响应包络：所有 HTTP 状态码均为 200，业务状态在 code 字段。
 * code = 0 表示成功，非 0 表示失败。
 */
@Serializable
data class ApiEnvelope<T>(
    val code: Int = 0,
    val message: String? = null,
    val data: T? = null,
    val timestamp: Long? = null,
    @SerialName("trace_id") val traceId: String? = null
) {
    val isSuccess: Boolean get() = code == 0

    fun requireData(): T = data ?: throw ApiException(code, message ?: "响应数据为空", traceId)

    fun toException(): ApiException = ApiException(code, message ?: "请求失败", traceId)
}
