package com.cjhsy.linkzone.core.error

/**
 * 全局错误文案常量。
 *
 * UI 层严禁散落硬编码错误字符串，统一从这里取，保证风格一致、可集中维护。
 */
object ErrorMessages {

    // ---------- 通用 ----------
    const val GENERIC = "操作失败，请稍后重试"
    const val NETWORK = "网络异常，请检查网络后重试"
    const val NETWORK_TIMEOUT = "网络连接超时，请稍后重试"
    const val NETWORK_UNREACHABLE = "无法连接服务器，请检查网络"
    const val SERVER_ERROR = "服务端异常，请稍后重试"

    // ---------- 认证 ----------
    const val AUTH_EXPIRED = "登录已过期，请重新登录"
    const val AUTH_FORBIDDEN = "没有操作权限"
    const val AUTH_NOT_FOUND = "资源不存在"
    const val RATE_LIMITED = "请求过于频繁，请稍后再试"

    // ---------- 聊天 / 生成 ----------
    const val CHAT_GENERATE = "生成失败，请稍后重试"
    const val CHAT_LOAD_HISTORY = "加载消息失败"
    const val CHAT_LOAD_MORE = "加载失败"
    const val CHAT_SEND_OFFLINE = "连接已断开，消息未发送"
    const val CHAT_CONV_UNAVAILABLE = "云端对话不可用，请检查网络后重试"
    const val CHAT_CLEAR_FAIL = "清空失败"
    const val CHAT_NOT_EXIST = "消息已不存在，操作失败"

    // ---------- 模型服务 ----------
    const val MODEL_UNCONFIGURED = "尚未配置可用模型，请到「设置 → AI 模型服务」添加"
    const val MODEL_DISABLED = "模型所属服务已停用"
    const val MODEL_NOT_FOUND = "模型不存在"
    const val MODEL_NO_BALANCE = "余额不足或额度已用完"
    const val MODEL_RATE_LIMIT = "模型服务限流，请稍后重试"
    const val MODEL_RESPONSE_FAIL = "模型服务响应失败"
    const val MODEL_STREAM_FAIL = "模型流式响应中断"
    const val MODEL_LIST_EMPTY = "服务返回空模型列表，请检查地址与 Key"

    // ---------- 工具 / MCP ----------
    const val MCP_EXEC_FAIL = "工具调用失败"
    const val MCP_CONNECT_FAIL = "MCP 服务器连接失败"
    const val TOOL_STEPS_EXCEEDED = "工具调用步数超过上限，已中止"

    // ---------- 文件 / 媒体 ----------
    const val FILE_TOO_LARGE = "文件过大，限制 20MB"
    const val FILE_READ_FAIL = "文件读取失败"
    const val IMAGE_ENCODE_FAIL = "图片读取失败，AI 看不到这张图"
    const val DOC_PARSE_FAIL = "文档解析失败"

    // ---------- 表单校验 ----------
    const val NAME_EMPTY = "名称不能为空"
    const val CONTENT_EMPTY = "内容不能为空"
    const val TITLE_CONTENT_EMPTY = "标题和内容不能为空"
    const val REQUIRED_EMPTY = "必填项不能为空"
    const val SERVER_URL_EMPTY = "服务器地址不能为空"
    const val INJECT_CONTENT_EMPTY = "注入内容不能为空"
}
