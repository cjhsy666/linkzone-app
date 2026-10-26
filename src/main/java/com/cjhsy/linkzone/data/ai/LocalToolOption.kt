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

    /** 请求用户选图 ask_user_for_photo（HITL：弹出系统相册 Photo Picker，选完回传给模型） */
    @Serializable
    @SerialName("ask_user_photo")
    object AskUserPhoto : LocalToolOption()

    /** 相册静默搜索 photo_query（MediaStore 按时间/相册/关键词搜图，需图片权限） */
    @Serializable
    @SerialName("photo_search")
    object PhotoSearch : LocalToolOption()

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

    /** 手机控制 phone_control（打开 App / 链接 / 拨号盘 / 设置 / 系统分享 / 手电筒） */
    @Serializable
    @SerialName("system_control")
    object SystemControl : LocalToolOption()

    /** 设备信息 device_info（型号 / 系统 / 电量 / 网络 / 屏幕 / 存储） */
    @Serializable
    @SerialName("device_info")
    object DeviceInfo : LocalToolOption()

    /** 定位 get_location（需定位运行时权限） */
    @Serializable
    @SerialName("location")
    object Location : LocalToolOption()

    /** 应用使用统计 usage_stats（需「使用情况访问」特殊权限，系统设置页手动授权） */
    @Serializable
    @SerialName("usage_stats")
    object UsageStats : LocalToolOption()

    /** 通讯录读取 contacts_query（需 READ_CONTACTS 运行时权限） */
    @Serializable
    @SerialName("contacts")
    object Contacts : LocalToolOption()

    /** 短信与通话记录读取 sms_query / call_log_query（需 READ_SMS / READ_CALL_LOG） */
    @Serializable
    @SerialName("sms_call_log")
    object SmsCallLog : LocalToolOption()

    /** 闹钟与定时器 alarm_create / timer_create：调用系统时钟 App 定闹钟、定倒计时 */
    @Serializable
    @SerialName("reminder")
    object Reminder : LocalToolOption()

    /** 通用 HTTP 请求 http_request */
    @Serializable
    @SerialName("http_request")
    object HttpRequest : LocalToolOption()
}
