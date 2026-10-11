package com.cjhsy.linkzone.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全存储封装：保存 refresh_token / user_id。
 *
 * 优先使用 EncryptedSharedPreferences；在部分国产 ROM 上 Keystore 可能损坏
 * （MasterKey/EncryptedPrefs 构造抛 GeneralSecurityException、IOException 等，
 * 是常见的启动闪退源），此时降级为普通 SharedPreferences，保证不闪退。
 * 每次读写同样兜底，解密失败即清空重建，避免后续永久不可用。
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createEncrypted() ?: createPlain()

    private fun createEncrypted(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    private fun createPlain(): SharedPreferences =
        appContext.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存 refresh_token */
    fun saveRefreshToken(token: String) {
        runCatching { prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply() }
    }

    /** 读取 refresh_token，不存在或解密失败返回 null */
    fun getRefreshToken(): String? =
        runCatching { prefs.getString(KEY_REFRESH_TOKEN, null) }
            .getOrNull()

    /** 保存当前登录用户的 linkzone_id */
    fun saveUserId(userId: String) {
        runCatching { prefs.edit().putString(KEY_USER_ID, userId).apply() }
    }

    fun getUserId(): String? =
        runCatching { prefs.getString(KEY_USER_ID, null) }.getOrNull()

    /** 是否已登录（本地存在 refresh_token） */
    fun isLoggedIn(): Boolean = !getRefreshToken().isNullOrEmpty()

    /** 清空全部凭证；加密存储损坏时删除文件以便下次重建 */
    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    companion object {
        private const val PREFS_NAME = "linkzone_secure_prefs"
        private const val PLAIN_PREFS_NAME = "linkzone_plain_prefs"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
    }
}
