package com.cjhsy.linkzone.core.error

import com.cjhsy.linkzone.core.network.ApiException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 全局异常翻译器：把任意 Throwable 映射为面向用户的中文消息。
 *
 * 覆盖三类来源：
 * 1. 业务异常 [ApiException] —— 服务端 message 优先（服务端应返回中文业务文案）
 * 2. 网络异常（超时 / 断连 / 域名解析失败 / SSL）—— 按类型翻译
 * 3. LLM Provider / MCP / 解析等内部英文异常 —— 按消息关键字翻译
 *
 * 无法识别时透传原始 message（保留诊断信息），仅当 message 为空时返回 [fallback]。
 */
fun Throwable.toUserMessage(fallback: String = ErrorMessages.GENERIC): String {
    // 协程取消：不应展示错误
    if (this is kotlinx.coroutines.CancellationException) return fallback

    // 1. 业务异常：服务端 message 优先；特定 code 覆盖
    if (this is ApiException) {
        val serverMsg = message?.takeIf { it.isNotBlank() }
        return when (code) {
            ApiException.CODE_UNAUTHORIZED -> ErrorMessages.AUTH_EXPIRED
            ApiException.CODE_FORBIDDEN -> ErrorMessages.AUTH_FORBIDDEN
            ApiException.CODE_NOT_FOUND -> ErrorMessages.AUTH_NOT_FOUND
            ApiException.CODE_SERVER_ERROR -> serverMsg ?: ErrorMessages.SERVER_ERROR
            else -> serverMsg ?: fallback
        }
    }

    val raw = (message ?: "").trim()

    // 2. 网络异常：按类型优先
    when (this) {
        is SocketTimeoutException -> return ErrorMessages.NETWORK_TIMEOUT
        is UnknownHostException -> return ErrorMessages.NETWORK_UNREACHABLE
        is ConnectException -> return ErrorMessages.NETWORK_UNREACHABLE
        is SSLException -> return "网络安全连接失败，请检查时间设置后重试"
        is java.io.IOException -> return ErrorMessages.NETWORK
    }

    // 空消息直接兜底
    if (raw.isBlank()) return fallback

    // 3. 按关键字翻译（只保留精准匹配，宽泛匹配宁可透传原文）
    return when {
        // HTTP 状态码透出
        raw.contains("401") -> ErrorMessages.AUTH_EXPIRED
        raw.contains("403") -> ErrorMessages.AUTH_FORBIDDEN
        raw.contains("429") || raw.contains("rate limit", ignoreCase = true)
            || raw.contains("too many requests", ignoreCase = true) -> ErrorMessages.RATE_LIMITED
        raw.contains("insufficient", ignoreCase = true)
            || raw.contains("quota", ignoreCase = true)
            || raw.contains("balance", ignoreCase = true) -> ErrorMessages.MODEL_NO_BALANCE

        // 模型服务
        raw.contains("Failed to get response", ignoreCase = true) -> ErrorMessages.MODEL_RESPONSE_FAIL
        raw.contains("Stream failed", ignoreCase = true) -> ErrorMessages.MODEL_STREAM_FAIL

        // 工具
        raw.contains("MCP", ignoreCase = true) -> ErrorMessages.MCP_EXEC_FAIL
        raw.contains("timed out", ignoreCase = true) -> ErrorMessages.NETWORK_TIMEOUT
        raw.contains("timeout", ignoreCase = true) -> ErrorMessages.NETWORK_TIMEOUT
        raw.contains("connection reset", ignoreCase = true)
            || raw.contains("broken pipe", ignoreCase = true) -> ErrorMessages.NETWORK
        raw.contains("unable to resolve host", ignoreCase = true)
            || raw.contains("no address associated", ignoreCase = true) -> ErrorMessages.NETWORK_UNREACHABLE

        // 解析
        raw.contains("Failed to decode", ignoreCase = true) -> "数据解析失败"
        raw.contains("Unable to find", ignoreCase = true) -> ErrorMessages.DOC_PARSE_FAIL

        // 已是中文或包含中文 → 直接用原文
        raw.any { it.code in 0x4E00..0x9FFF } -> raw

        // 识别不了：透传原文，便于用户/开发者定位真实原因
        else -> raw
    }
}
