package com.cjhsy.linkzone.core.storage

import android.content.Context

/** 工具调用权限档位（RikkaHub 式）：逐个工具可调，默认值按工具风险决定 */
enum class ToolPermission(val key: String, val label: String) {
    Allow("allow", "允许"),
    Ask("ask", "每次询问"),
    Deny("deny", "禁止");

    companion object {
        fun fromKey(raw: String?): ToolPermission? = entries.firstOrNull { it.key == raw }
    }
}

/**
 * 工具权限存储：按工具名持久化用户选择，未设置时回落到工具默认风险位。
 * 写操作类工具默认「每次询问」，只读类默认「允许」，用户点「总是允许」或到设置里调整后长期生效。
 */
class ToolPermissionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** askByDefault = 该工具是否为写操作（未配置时默认询问） */
    fun policyFor(toolName: String, askByDefault: Boolean): ToolPermission =
        ToolPermission.fromKey(prefs.getString(toolName, null))
            ?: if (askByDefault) ToolPermission.Ask else ToolPermission.Allow

    fun set(toolName: String, permission: ToolPermission) {
        prefs.edit().putString(toolName, permission.key).apply()
    }

    /** 恢复工具默认档位（写操作回到「每次询问」） */
    fun reset(toolName: String) {
        prefs.edit().remove(toolName).apply()
    }

    private companion object {
        const val PREFS_NAME = "tool_permissions"
    }
}
