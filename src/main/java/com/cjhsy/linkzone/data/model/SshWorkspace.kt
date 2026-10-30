package com.cjhsy.linkzone.data.model

import kotlinx.serialization.Serializable

/**
 * SSH 工作区配置 —— 远程 shell + SFTP 文件操作（对齐 RikkaHub 工作区工具面，
 * 底层从本机文件系统换成 SSH 通道）。
 *
 * 认证二选一：密码 或 私钥（PEM 文本，可选 passphrase）。
 */
@Serializable
data class SshWorkspaceConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /** "password" | "key" */
    val authType: String = "password",
    val password: String = "",
    /** PEM 格式私钥文本（authType = "key" 时使用） */
    val privateKey: String = "",
    val keyPassphrase: String = "",
    /** 远程工作目录（工具内相对路径都相对它解析），如 /home/user/proj */
    val workDir: String = "~",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val summary: String get() = "$username@$host:$port"
}
