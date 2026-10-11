package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.core.network.ApiEnvelope
import com.cjhsy.linkzone.core.network.ApiException
import com.cjhsy.linkzone.core.network.TokenHolder

/**
 * 仓库层共享工具：统一包络解包 + 异常包装。
 * 此前每个仓库各自复制了同样的 runEnvelope/runUnit 实现（约 120 行冗余）。
 */

/** 解包有数据的响应；data 为 null 视为失败。401 时标记会话失效 */
internal suspend fun <T> runEnvelope(
    tokenHolder: TokenHolder,
    block: suspend () -> ApiEnvelope<T>
): Result<T> = try {
    val envelope = block()
    if (envelope.isSuccess && envelope.data != null) {
        Result.success(envelope.data)
    } else {
        fail(tokenHolder, envelope)
    }
} catch (t: Throwable) {
    // 协程取消不能吞：否则 ViewModel 销毁时无法终止在途请求
    if (t is kotlinx.coroutines.CancellationException) throw t
    Result.failure(t.toApiException())
}

/** 解包只关心成功与否的响应 */
internal suspend fun runUnitEnvelope(
    tokenHolder: TokenHolder,
    block: suspend () -> ApiEnvelope<*>
): Result<Unit> = try {
    val envelope = block()
    if (envelope.isSuccess) Result.success(Unit)
    else Result.failure(envelope.toException().also {
        if (it.isAuthError) tokenHolder.markExpired()
    })
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    Result.failure(t.toApiException())
}

private fun <T> fail(
    tokenHolder: TokenHolder,
    envelope: ApiEnvelope<T>
): Result<T> {
    val ex = envelope.toException()
    if (ex.isAuthError) tokenHolder.markExpired()
    return Result.failure(ex)
}

/** 将任意异常统一包装为可展示的 ApiException */
internal fun Throwable.toApiException(): ApiException = when (this) {
    is ApiException -> this
    else -> ApiException(ApiException.CODE_SERVER_ERROR, message ?: "网络异常，请稍后重试")
}
