package com.cjhsy.linkzone.data.ai

import android.content.Context
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.jcraft.jsch.ChannelSftp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * SSH 工作区工具集 —— 智能体绑定 SSH 工作区后注入四个工具：
 * - ssh_read_file：读远端 UTF-8 文本文件（二进制扩展名拒绝，SFTP 下载）
 * - ssh_write_file：写入/覆盖远端文件（SFTP 上传，自动建父目录）
 * - ssh_list_dir：列远端目录（SFTP ls）
 * - ssh_shell：在远端工作目录执行 shell（exec 通道，需用户批准）
 *
 * 路径解析：相对路径相对 workDir；所有路径经远端 realpath 校验，
 * 禁止越出工作区根目录（与本地 WorkspaceTools 同策略）。
 */
fun createSshTools(
    context: Context,
    config: SshWorkspaceConfig,
): List<Tool> {
    SshSessionManager.init(context.filesDir)
    return listOf(
        buildSshReadFileTool(config),
        buildSshWriteFileTool(config),
        buildSshListDirTool(config),
        buildSshShellTool(config),
    )
}

private const val TEXT_MAX_BYTES = 10 * 1024 * 1024 // 单次读写 10MB 上限
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "jar", "apk",
    "dex", "so", "a", "o", "class", "mp3", "mp4", "wav", "ogg", "ttf", "otf",
    "woff", "woff2", "eot", "exe", "bin", "dat"
)

private fun isBinaryPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS

/** 相对/绝对路径 → 远端绝对路径；越出 workDir 抛异常。在 SFTP 通道内调用。 */
private fun resolveRemote(sftp: ChannelSftp, config: SshWorkspaceConfig, rawPath: String): String {
    val raw = rawPath.trim()
    require(raw.isNotEmpty()) { "path 不能为空" }
    val home = sftp.home
    fun expand(p: String): String = when {
        p == "~" -> home
        p.startsWith("~/") -> home + p.substring(1)
        else -> p
    }
    val workDirAbs = sftp.realpath(expand(config.workDir))
    val joined = if (raw.startsWith("/") || raw == "~" || raw.startsWith("~/")) {
        expand(raw)
    } else {
        "$workDirAbs/$raw"
    }
    // realpath 要求目标存在；写新文件时退化为规范化拼接再校验前缀
    val canonical = runCatching { sftp.realpath(joined) }.getOrElse { normalize(joined) }
    if (canonical != workDirAbs && !canonical.startsWith("$workDirAbs/")) {
        error("Path '$rawPath' is outside the workspace ($workDirAbs)")
    }
    return canonical
}

/** 无 realpath 可用时的纯字符串规范化（处理 . / .. / 重复斜杠） */
private fun normalize(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
    val stack = ArrayDeque<String>()
    for (p in parts) {
        if (p == "..") {
            if (stack.isEmpty()) error("Path escapes root") else stack.removeLast()
        } else stack.addLast(p)
    }
    return "/" + stack.joinToString("/")
}

private fun errJson(e: Throwable): List<UIMessagePart.Text> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("error", e.javaClass.simpleName)
            put("message", e.message ?: "unknown error")
        }.toString()
    )
)

private fun buildSshReadFileTool(config: SshWorkspaceConfig): Tool = Tool(
    name = "ssh_read_file",
    description = """
        Read a UTF-8 text file from the remote SSH workspace '${config.name}' (${config.summary}).
        Paths are relative to the workspace directory '${config.workDir}' (use ~ or absolute paths only inside it).
        Binary files (images, archives, etc.) are rejected. Max 10MB per read.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path, relative to the workspace dir or absolute inside it.")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.content ?: ""
        runCatching {
            SshSessionManager.withSftp(config) { sftp ->
                val remote = resolveRemote(sftp, config, path)
                if (isBinaryPath(remote)) error("Binary file type is not supported: $remote")
                val stat = sftp.stat(remote)
                if (stat.isDir) error("Path is a directory: $remote")
                if (stat.size > TEXT_MAX_BYTES) error("File too large: ${stat.size} bytes (max $TEXT_MAX_BYTES)")
                val out = ByteArrayOutputStream()
                sftp.get(remote, out)
                buildJsonObject {
                    put("path", remote)
                    put("size", stat.size)
                    put("content", out.toString(Charsets.UTF_8.name()))
                }.toString()
            }
        }.fold(
            onSuccess = { listOf(UIMessagePart.Text(it)) },
            onFailure = { errJson(it) }
        )
    }
)

private fun buildSshWriteFileTool(config: SshWorkspaceConfig): Tool = Tool(
    name = "ssh_write_file",
    description = """
        Write (create or overwrite) a UTF-8 text file in the remote SSH workspace '${config.name}' (${config.summary}).
        Paths are relative to the workspace directory '${config.workDir}'. Parent directories are created automatically.
        Max 10MB per write.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path, relative to the workspace dir or absolute inside it.")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full text content to write.")
                })
            },
            required = listOf("path", "content")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.content ?: ""
        val content = args.jsonObject["content"]?.jsonPrimitive?.content ?: ""
        runCatching {
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(bytes.size <= TEXT_MAX_BYTES) { "Content too large: ${bytes.size} bytes (max $TEXT_MAX_BYTES)" }
            SshSessionManager.withSftp(config) { sftp ->
                val remote = resolveRemote(sftp, config, path)
                // 逐级创建父目录（SFTP 无 mkdir -p）
                val parent = remote.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) {
                    val parts = parent.split('/').filter { it.isNotEmpty() }
                    var cur = ""
                    for (p in parts) {
                        cur += "/$p"
                        runCatching { sftp.mkdir(cur) }
                    }
                }
                sftp.put(ByteArrayInputStream(bytes), remote)
                buildJsonObject {
                    put("path", remote)
                    put("bytes_written", bytes.size)
                }.toString()
            }
        }.fold(
            onSuccess = { listOf(UIMessagePart.Text(it)) },
            onFailure = { errJson(it) }
        )
    }
)

private fun buildSshListDirTool(config: SshWorkspaceConfig): Tool = Tool(
    name = "ssh_list_dir",
    description = """
        List files and directories in the remote SSH workspace '${config.name}' (${config.summary}).
        Paths are relative to the workspace directory '${config.workDir}'. Defaults to the workspace root.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Directory path; empty or '.' means the workspace root.")
                })
            },
            required = emptyList()
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.content?.ifBlank { "." } ?: "."
        runCatching {
            SshSessionManager.withSftp(config) { sftp ->
                val remote = resolveRemote(sftp, config, path)
                val entries = mutableListOf<String>()
                val vector = sftp.ls(remote)
                for (item in vector) {
                    val e = item as ChannelSftp.LsEntry
                    if (e.filename == "." || e.filename == "..") continue
                    val type = if (e.attrs.isDir) "dir" else "file"
                    entries += "$type\t${e.attrs.size}\t${e.filename}"
                }
                buildJsonObject {
                    put("path", remote)
                    put("count", entries.size)
                    put("entries", entries.joinToString("\n"))
                }.toString()
            }
        }.fold(
            onSuccess = { listOf(UIMessagePart.Text(it)) },
            onFailure = { errJson(it) }
        )
    }
)

private fun buildSshShellTool(config: SshWorkspaceConfig): Tool = Tool(
    name = "ssh_shell",
    description = """
        Execute a shell command on the remote SSH workspace '${config.name}' (${config.summary}).
        Commands run in the workspace directory '${config.workDir}' (use cwd for a subdirectory).
        Use for builds, git, package installs, inspections, etc. Interactive commands are not supported.
        Default timeout 30s; long-running commands can raise timeout_sec up to 300.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "The shell command to execute.")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Subdirectory under the workspace dir to run in; empty means workspace root.")
                })
                put("timeout_sec", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in seconds, 1-300. Default 30.")
                })
            },
            required = listOf("command")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val command = obj["command"]?.jsonPrimitive?.content ?: ""
        val cwd = obj["cwd"]?.jsonPrimitive?.content ?: ""
        val timeoutSec = (obj["timeout_sec"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        runCatching {
            val r = SshSessionManager.exec(config, command, cwd, timeoutSec * 1000L)
            buildJsonObject {
                put("exit_code", r.exitCode)
                put("timed_out", r.timedOut)
                put("stdout", r.stdout)
                put("stderr", r.stderr)
            }.toString()
        }.fold(
            onSuccess = { listOf(UIMessagePart.Text(it)) },
            onFailure = { errJson(it) }
        )
    }
)
