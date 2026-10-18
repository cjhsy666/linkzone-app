package com.cjhsy.linkzone.ui.backup

import com.cjhsy.linkzone.core.error.toUserMessage

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.core.storage.LlmPrefs
import com.cjhsy.linkzone.data.backup.BackupManager
import com.cjhsy.linkzone.data.backup.WebDavConfig
import com.cjhsy.linkzone.data.backup.WebDavSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class BackupUiState(
    /** 0 = 导入/导出，1 = WebDAV */
    val tab: Int = 0,
    /** 本地导出/导入进行中 */
    val busy: Boolean = false,
    /** 一次性提示（Snackbar 展示） */
    val message: String? = null,
    /** 恢复成功后置位：提示重启 APP 生效 */
    val needRestart: Boolean = false,
    /** 最近一次导出的本地 zip 路径（展示用） */
    val lastBackupFile: String? = null,
    /** WebDAV 配置（表单双向绑定） */
    val webdavConfig: WebDavConfig = WebDavConfig(),
    /** WebDAV 操作进行中 */
    val webdavBusy: Boolean = false,
    /** 上次 WebDAV 上传成功时间戳（毫秒，0 表示从未同步） */
    val webdavLastSync: Long = 0L
)

/**
 * 备份与恢复 ViewModel。
 *
 * 本地导出/导入走 SAF Uri（Uri 的读写需要 contentResolver，故注入 Application Context）；
 * WebDAV 配置持久化在 LlmPrefs（明文存储，UI 需提示）。
 */
class BackupViewModel(
    private val backupManager: BackupManager,
    private val webDavSync: WebDavSync,
    private val llmPrefs: LlmPrefs,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            webdavConfig = llmPrefs.getObject(LlmPrefs.KEY_WEBDAV_CONFIG, WebDavConfig()),
            webdavLastSync = llmPrefs.getLong(LlmPrefs.KEY_WEBDAV_LAST_SYNC, 0L)
        )
    }

    // ---------- 通用 ----------

    fun setTab(index: Int) {
        _state.value = _state.value.copy(tab = index)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---------- 本地导入 / 导出 ----------

    /** 导出：生成备份 zip 并写入 SAF Uri */
    fun exportTo(uri: Uri) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            runCatching {
                val file = backupManager.createBackup()
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法打开导出位置")
                file
            }.fold(
                onSuccess = { file ->
                    _state.value = _state.value.copy(
                        busy = false,
                        message = "备份已导出（${file.name}）",
                        lastBackupFile = file.absolutePath
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        busy = false,
                        message = "导出失败：${e.toUserMessage("未知错误")}"
                    )
                }
            )
        }
    }

    /** 导入：把 SAF Uri 内容落到临时文件后恢复 */
    fun importFrom(uri: Uri) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            runCatching {
                val tmp = File(appContext.cacheDir, "restore_import.zip")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")
                try {
                    if (tmp.length() == 0L) throw IllegalStateException("所选文件为空")
                    backupManager.restoreBackup(tmp)
                } finally {
                    tmp.delete()
                }
            }.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        busy = false,
                        needRestart = true,
                        message = "恢复完成，重启 APP 后生效"
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        busy = false,
                        message = "恢复失败：${e.toUserMessage("未知错误")}"
                    )
                }
            )
        }
    }

    // ---------- WebDAV ----------

    /** 更新表单字段 */
    fun updateConfig(transform: (WebDavConfig) -> WebDavConfig) {
        _state.value = _state.value.copy(webdavConfig = transform(_state.value.webdavConfig))
    }

    /** 保存配置到本地偏好（密码明文存储） */
    fun saveConfig() {
        val config = _state.value.webdavConfig
        llmPrefs.putObject(LlmPrefs.KEY_WEBDAV_CONFIG, config)
        _state.value = _state.value.copy(message = "配置已保存（密码明文存储于本机）")
    }

    /** 测试连接 */
    fun testWebDav() {
        val config = _state.value.webdavConfig
        if (!config.isReady || _state.value.webdavBusy) return
        persistConfigLocked(config)
        _state.value = _state.value.copy(webdavBusy = true, message = null)
        viewModelScope.launch {
            runCatching { webDavSync.testConnection(config) }.fold(
                onSuccess = { msg ->
                    _state.value = _state.value.copy(webdavBusy = false, message = msg)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        webdavBusy = false,
                        message = e.toUserMessage("连接失败")
                    )
                }
            )
        }
    }

    /** 上传备份：先本地生成 zip，再 PUT 到远端固定文件名 */
    fun uploadToWebDav() {
        val config = _state.value.webdavConfig
        if (!config.isReady || _state.value.webdavBusy) return
        persistConfigLocked(config)
        _state.value = _state.value.copy(webdavBusy = true, message = null)
        viewModelScope.launch {
            runCatching {
                val file = backupManager.createBackup()
                webDavSync.upload(file, config, remoteName = WebDavSync.REMOTE_BACKUP_FILE)
                // 本地产物用完即删（在 cache 下，避免堆积）
                file.delete()
            }.fold(
                onSuccess = {
                    val now = System.currentTimeMillis()
                    llmPrefs.putLong(LlmPrefs.KEY_WEBDAV_LAST_SYNC, now)
                    _state.value = _state.value.copy(
                        webdavBusy = false,
                        webdavLastSync = now,
                        message = "已上传备份到 WebDAV"
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        webdavBusy = false,
                        message = "上传失败：${e.toUserMessage("未知错误")}"
                    )
                }
            )
        }
    }

    /** 下载并恢复：GET 远端固定文件名 → 覆盖本地数据 */
    fun downloadFromWebDav() {
        val config = _state.value.webdavConfig
        if (!config.isReady || _state.value.webdavBusy) return
        persistConfigLocked(config)
        _state.value = _state.value.copy(webdavBusy = true, message = null)
        viewModelScope.launch {
            runCatching {
                val file = webDavSync.download(config)
                try {
                    backupManager.restoreBackup(file)
                } finally {
                    file.delete()
                }
            }.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        webdavBusy = false,
                        needRestart = true,
                        message = "已从 WebDAV 恢复，重启 APP 后生效"
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        webdavBusy = false,
                        message = "下载/恢复失败：${e.toUserMessage("未知错误")}"
                    )
                }
            )
        }
    }

    private fun persistConfigLocked(config: WebDavConfig) {
        llmPrefs.putObject(LlmPrefs.KEY_WEBDAV_CONFIG, config)
    }
}
