package com.cjhsy.linkzone.core.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LLM 功能域偏好存储。
 *
 * RikkaHub 用 DataStore 存储扩展功能（TTS / 图片生成 / 翻译 / WebDAV 等）的配置，
 * LinkZone 这里用 SharedPreferences + kotlinx.serialization 自封装实现同等能力（不新增依赖）：
 * - 基础类型（String/Int/Boolean/Long）直接读写 SharedPreferences；
 * - 对象类型通过 json 编解码为字符串后存取（getObject/putObject）。
 *
 * 全部为同步读写，适用于配置项这类轻量数据。
 * 文件名为 "llm_prefs"；clear() 只清理带 [KEY_PREFIX] 前缀的键，避免误伤其他功能域。
 */
class LlmPrefs(context: Context, @PublishedApi internal val json: Json) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    // ---------- 基础类型 ----------

    fun getString(key: String, defValue: String? = null): String? =
        runCatching { prefs.getString(key, defValue) }.getOrNull()

    fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    fun getInt(key: String, defValue: Int = 0): Int =
        runCatching { prefs.getInt(key, defValue) }.getOrDefault(defValue)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getBoolean(key: String, defValue: Boolean = false): Boolean =
        runCatching { prefs.getBoolean(key, defValue) }.getOrDefault(defValue)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getLong(key: String, defValue: Long = 0L): Long =
        runCatching { prefs.getLong(key, defValue) }.getOrDefault(defValue)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    /** 删除单个键 */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * 清空本功能域全部数据。
     * 只移除带 "llm_" 前缀的键，不影响同文件内其他功能域的偏好。
     */
    fun clear() {
        val editor = prefs.edit()
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 是否包含某个键 */
    fun contains(key: String): Boolean =
        runCatching { prefs.contains(key) }.getOrDefault(false)

    // ---------- 对象类型（json 编解码为字符串存取） ----------

    /**
     * 读取对象：json 反序列化；键不存在或历史数据损坏时返回 [default]。
     */
    inline fun <reified T> getObject(key: String, default: T): T {
        val raw = getString(key) ?: return default
        return runCatching { json.decodeFromString<T>(raw) }.getOrNull() ?: default
    }

    /**
     * 写入对象：json 序列化为字符串后存储。
     */
    inline fun <reified T> putObject(key: String, value: T) {
        putString(key, json.encodeToString(value))
    }

    companion object {
        private const val PREFS_FILE_NAME = "llm_prefs"

        /** 本功能域键的统一前缀（clear 依据） */
        const val KEY_PREFIX = "llm_"

        // ---------- 键名常量 ----------

        /** TTS 服务商配置列表（json 数组，由接线层序列化） */
        const val KEY_TTS_PROVIDERS = "llm_tts_providers"

        /** 图片生成（mediagen）服务商配置列表（json 数组） */
        const val KEY_MEDIAGEN_PROVIDERS = "llm_mediagen_providers"

        /** 翻译目标语言 */
        const val KEY_TRANSLATION_TARGET_LANG = "llm_translation_target_lang"

        /** 翻译使用的模型 ID */
        const val KEY_TRANSLATION_MODEL = "llm_translation_model"

        /** WebDAV 同步配置（WebDavConfig 序列化） */
        const val KEY_WEBDAV_CONFIG = "llm_webdav_config"

        /** WebDAV 上次同步时间戳（毫秒） */
        const val KEY_WEBDAV_LAST_SYNC = "llm_webdav_last_sync"

        /** 历史保留工具调用：开启时历史消息中的工具调用/结果回传给 AI（关闭省 token，但 AI 看不到历史工具结果） */
        const val KEY_HISTORY_KEEP_TOOLS = "llm_history_keep_tools"
    }
}
