package com.cjhsy.linkzone.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 本地工具开关选项（对齐 RikkaHub LocalToolOption 的裁剪版）
 *
 * 增删子类的约束：开关以 JSON 存进 Room（llm_agent.localTools 及整行 agent JSON），
 * 删除子类时必须在 LlmRepository 解码前从存储 JSON 剔除其 serial 名，
 * 否则旧数据遇到未知子类会让整个列表（乃至整个智能体）反序列化失败。
 */
@Serializable
sealed class LocalToolOption {
    /** 时间感知 get_time_info（查询设备当前日期时间） */
    @Serializable
    @SerialName("time_info")
    object TimeInfo : LocalToolOption()

    /** 剪贴板读写 clipboard_tool */
    @Serializable
    @SerialName("clipboard")
    object Clipboard : LocalToolOption()

    /** 主动追问 ask_user（HITL：生成暂停等用户作答） */
    @Serializable
    @SerialName("ask_user")
    object AskUser : LocalToolOption()

    /** 日历读写 calendar_query / calendar_create（需系统日历权限） */
    @Serializable
    @SerialName("calendar")
    object Calendar : LocalToolOption()

    /** 语音播报 text_to_speech（朗读文本） */
    @Serializable
    @SerialName("tts")
    object Tts : LocalToolOption()

    /** JavaScript 代码执行 eval_javascript（QuickJS 引擎） */
    @Serializable
    @SerialName("javascript")
    object Javascript : LocalToolOption()

    /** 手机控制 phone_control（打开 App / 链接 / 拨号盘 / 设置 / 系统分享 / 设备信息 / 定位） */
    @Serializable
    @SerialName("system_control")
    object SystemControl : LocalToolOption()

    /** 通讯录读取 contacts_query（需 READ_CONTACTS 运行时权限） */
    @Serializable
    @SerialName("contacts")
    object Contacts : LocalToolOption()

    /** 短信与通话记录读取 sms_query / call_log_query（需 READ_SMS / READ_CALL_LOG） */
    @Serializable
    @SerialName("sms_call_log")
    object SmsCallLog : LocalToolOption()
}
