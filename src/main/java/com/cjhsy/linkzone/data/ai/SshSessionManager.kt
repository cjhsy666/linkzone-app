package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.cjhsy.linkzone.workspace.MAX_SHELL_OUTPUT_CHARS
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 会话管理器：按工作区 id 缓存并复用 JSch Session。
 *
 * - 首次用时建立连接（密码或私钥），断线后下次调用自动重连
 * - 每个 Session 上的 channel 操作经 per-config Mutex 串行化（JSch Session 非线程安全）
 * - 主机密钥策略：accept-new（首次信任，之后校验）——手机端没有 known_hosts 交互场景，
 *   与 Termius/JuiceSSH 的默认行为一致；known_hosts 存 filesDir/ssh_known_hosts
 */
object SshSessionManager {

    private val jsch = JSch()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var knownHostsPath: String? = null

    /** 由 UI/工具层在 Application context 下调用一次，设置 known_hosts 路径 */
    fun init(filesDir: java.io.File) {
        if (knownHostsPath == null) {
            val f = java.io.File(filesDir, "ssh_known_hosts")
            knownHostsPath = f.absolutePath
            jsch.setKnownHosts(f.absolutePath)
        }
    }

    data class SshExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    /** 在指定工作区上执行 shell 命令（自动 cd 到 workDir），超时强制断 channel */
    suspend fun exec(
        config: SshWorkspaceConfig,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = 30_000L,
    ): SshExecResult = withChannel(config) { session ->
        val fullCommand = buildString {
            append("cd ").append(shellQuote(resolveCwd(config.workDir, cwd))).append(" && ")
            append(command)
        }
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        try {
            channel.setCommand(fullCommand)
            channel.setOutputStream(stdout)
            channel.setExtOutputStream(stderr)
            channel.connect(10_000)
            val deadline = System.currentTimeMillis() + timeoutMillis
            var timedOut = false
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    channel.disconnect()
                    break
                }
                Thread.sleep(50)
            }
            SshExecResult(
                exitCode = if (timedOut) -1 else channel.exitStatus,
                stdout = truncate(stdout.toString(Charsets.UTF_8.name())),
                stderr = truncate(stderr.toString(Charsets.UTF_8.name())),
                timedOut = timedOut,
            )
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /** 打开 SFTP 通道并执行操作；调用方负责不在 lambda 内做长耗时逻辑 */
    suspend fun <T> withSftp(config: SshWorkspaceConfig, block: (ChannelSftp) -> T): T =
        withChannel(config) { session ->
            val channel = session.openChannel("sftp") as ChannelSftp
            try {
                channel.connect(10_000)
                block(channel)
            } finally {
                runCatching { channel.disconnect() }
            }
        }

    /** 测试连接：建立会话并跑一条 echo，返回耗时毫秒；失败抛异常 */
    suspend fun testConnection(config: SshWorkspaceConfig): Long {
        val start = System.currentTimeMillis()
        val r = exec(config.copy(workDir = "~"), "echo linkzone-ssh-ok", timeoutMillis = 15_000)
        if (r.timedOut || !r.stdout.contains("linkzone-ssh-ok")) {
            error("连接成功但命令回显异常：${r.stderr.ifBlank { r.stdout }}")
        }
        return System.currentTimeMillis() - start
    }

    /** 配置变更/删除后调用，丢弃缓存会话 */
    fun invalidate(id: String) {
        sessions.remove(id)?.let { runCatching { it.disconnect() } }
    }

    /** 将相对 cwd 解析到 workDir 下（允许 ../ 但拼接后交给远端 shell 解释） */
    private fun resolveCwd(workDir: String, cwd: String): String {
        val c = cwd.trim().trimStart('/')
        return if (c.isEmpty()) workDir else "$workDir/$c"
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun truncate(s: String): String =
        if (s.length > MAX_SHELL_OUTPUT_CHARS) {
            s.take(MAX_SHELL_OUTPUT_CHARS) + "\n... [output truncated]"
        } else s

    private suspend fun <T> withChannel(
        config: SshWorkspaceConfig,
        block: (Session) -> T,
    ): T = withContext(Dispatchers.IO) {
        val lock = locks.getOrPut(config.id) { Mutex() }
        lock.withLock {
            val session = getOrConnect(config)
            try {
                block(session)
            } catch (e: Exception) {
                // 通道级失败可能是会话已死：丢弃缓存让下次重连，再抛出本次错误
                invalidate(config.id)
                throw e
            }
        }
    }

    private fun getOrConnect(config: SshWorkspaceConfig): Session {
        sessions[config.id]?.let { if (it.isConnected) return it }
        invalidate(config.id)

        val session = jsch.getSession(config.username, config.host, config.port)
        if (config.authType == "key") {
            jsch.addIdentity(
                config.id,
                config.privateKey.toByteArray(Charsets.UTF_8),
                null,
                config.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        } else {
            session.setPassword(config.password)
        }
        val props = Properties()
        // accept-new：已知主机严格校验、新主机自动记录（防 silent MITM 又免交互）
        props["StrictHostKeyChecking"] = "accept-new"
        session.setConfig(props)
        session.setServerAliveInterval(15_000)
        session.setTimeout(10_000)
        session.connect(10_000)
        sessions[config.id] = session
        return session
    }
}
