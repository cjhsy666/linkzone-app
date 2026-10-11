package com.cjhsy.linkzone.core.network

/**
 * 业务异常：当 code != 0 时抛出。
 * code = 401 时触发 TokenAuthenticator 刷新流程。
 */
class ApiException(
    val code: Int,
    override val message: String,
    val traceId: String? = null
) : Exception(message) {

    /** 是否属于认证失效，需要刷新 Token 或跳转登录 */
    val isAuthError: Boolean get() = code == 401

    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_INVALID_REQUEST = 400
        const val CODE_UNAUTHORIZED = 401
        const val CODE_FORBIDDEN = 403
        const val CODE_NOT_FOUND = 404
        const val CODE_SERVER_ERROR = 500
        const val CODE_USER_EXISTS = 40001
        const val CODE_USER_NOT_FOUND = 40002
        const val CODE_BAD_CREDENTIALS = 40003
        const val CODE_CAPTCHA_ERROR = 40004
        const val CODE_EMAIL_CODE_ERROR = 40005
        const val CODE_SESSION_INVALID = 40006
    }
}
