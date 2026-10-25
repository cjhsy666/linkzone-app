package com.cjhsy.linkzone.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 本地工具开关选项（对齐 RikkaHub LocalToolOption 的裁剪版）
 *
 * 增删子类必须带 @SerialName 且旧值不变：开关以 JSON 存进 Room（llm_agent.localTools），
 * 反序列化遇到未知 serial 名会失败，故只增不改。
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

    /** [已废弃] 语音播报工具已移除（改为消息操作栏「朗读」按钮，见 ui/chat/TtsPlayer）；
     *  子类保留仅为了旧数据（可能存过 "tts"）能正常反序列化，不再注入工具也不在 UI 展示 */
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

    /** 定时提醒 reminder_create / reminder_list / reminder_cancel（到点系统通知） */
    @Serializable
    @SerialName("reminder")
    object Reminder : LocalToolOption()

    /** 通用 HTTP 请求 http_request */
    @Serializable
    @SerialName("http_request")
    object HttpRequest : LocalToolOption()

    /** 服务端联动 linkzone_push / linkzone_schedule（需配置框架 admin token） */
    @Serializable
    @SerialName("linkzone")
    object LinkZone : LocalToolOption()
}
