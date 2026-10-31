package com.cjhsy.linkzone.data.ai

import android.content.Context
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.jcraft.jsch.ChannelSftp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * SSH 工作区工具集 —— 智能体绑定 SSH 工作区后注入五个工具：
 * - ssh_read_file：读远端 UTF-8 文本文件（二进制扩展名拒绝，SFTP 下载）
 * - ssh_write_file：写入/覆盖远端文件（SFTP 上传，自动建父目录）
 * - ssh_edit_file：局部替换（精确匹配，失败时退化到忽略空白的行级匹配）
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
        buildSshEditFileTool(config),
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

/** 局部替换结果：替换后全文 + 实际替换次数 */
private data class TextEditResult(val text: String, val count: Int)

/**
 * 文本局部替换（对齐 RikkaHub workspace_edit_file 的三级容错）：
 * 1. 精确匹配；出现多次且未开 replace_all 时报错，要求给更多上下文
 * 2. 精确匹配失败时，按行 trim 后匹配（容忍缩进/行尾空白差异）
 * 3. 仍失败则报错，提示补充上下文
 * CRLF 文件自动把 old/new 的换行对齐成 \r\n，避免"看得见却匹配不上"。
 */
private fun applyTextEdit(
    content: String,
    oldText: String,
    newText: String,
    replaceAll: Boolean
): TextEditResult {
    require(oldText.isNotEmpty()) { "old_text must not be empty" }
    val crlf = content.contains("\r\n")

    fun align(s: String): String =
        if (!crlf || s.contains("\r\n")) s else s.replace("\n", "\r\n")

    val oldAligned = align(oldText)
    val newAligned = align(newText)

    // ---- 第一级：精确匹配 ----
    val times = countOccurrences(content, oldAligned)
    if (times > 0) {
        if (times > 1 && !replaceAll) {
            error("old_text appears $times times. Provide more surrounding context or set replace_all=true.")
        }
        val result = if (replaceAll) content.replace(oldAligned, newAligned)
        else content.replaceFirst(oldAligned, newAligned)
        return TextEditResult(result, if (replaceAll) times else 1)
    }

    // ---- 第二级：忽略行首尾空白的行级匹配（保持原文件行内容） ----
    val contentLines = content.split("\n")
    val oldLines = oldAligned.split("\n")
    val matches = mutableListOf<Int>()
    var i = 0
    while (i + oldLines.size <= contentLines.size) {
        var ok = true
        for (j in oldLines.indices) {
            if (contentLines[i + j].trim() != oldLines[j].trim()) {
                ok = false
                break
            }
        }
        if (ok) {
            matches += i
            if (!replaceAll) break
            i += oldLines.size
        } else {
            i++
        }
    }
    if (matches.isEmpty()) {
        error("old_text not found in file. Read the file and provide more surrounding context.")
    }
    if (matches.size > 1 && !replaceAll) {
        error("old_text matches ${matches.size} locations (ignoring whitespace). Provide more context or set replace_all=true.")
    }

    val targets = if (replaceAll) matches else listOf(matches.first())
    val merged = contentLines.toMutableList()
    val replacementLines = newAligned.split("\n")
    // 从后往前替换，避免前面的修改导致后面下标偏移
    for (start in targets.asReversed()) {
        repeat(oldLines.size) { if (start < merged.size) merged.removeAt(start) }
        merged.addAll(start.coerceAtMost(merged.size), replacementLines)
    }
    return TextEditResult(merged.joinToString("\n"), targets.size)
}

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var idx = text.indexOf(needle)
    while (idx >= 0) {
        count++
        idx = text.indexOf(needle, idx + needle.length)
    }
    return count
}

private fun buildSshEditFileTool(config: SshWorkspaceConfig): Tool = Tool(
    name = "ssh_edit_file",
    description = """
        Replace an exact fragment of a UTF-8 text file in the remote SSH workspace '${config.name}' (${config.summary}).
        Paths are relative to the workspace directory '${config.workDir}'.
        Prefer this over ssh_write_file when changing part of an existing file: provide old_text (must be unique
        unless replace_all=true) and new_text. Whitespace-tolerant matching is used as a fallback.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path, relative to the workspace dir or absolute inside it.")
                })
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace. Include enough context to be unique.")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text. Use an empty string to delete the fragment.")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace every occurrence. Default false (error if not unique).")
                })
            },
            required = listOf("path", "old_text", "new_text")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val path = obj["path"]?.jsonPrimitive?.content ?: ""
        val oldText = obj["old_text"]?.jsonPrimitive?.content ?: ""
        val newText = obj["new_text"]?.jsonPrimitive?.content ?: ""
        val replaceAll = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
        runCatching {
            SshSessionManager.withSftp(config) { sftp ->
                val remote = resolveRemote(sftp, config, path)
                if (isBinaryPath(remote)) error("Binary file type is not supported: $remote")
                val stat = sftp.stat(remote)
                if (stat.isDir) error("Path is a directory: $remote")
                if (stat.size > TEXT_MAX_BYTES) error("File too large: ${stat.size} bytes (max $TEXT_MAX_BYTES)")
                val out = ByteArrayOutputStream()
                sftp.get(remote, out)
                val original = out.toString(Charsets.UTF_8.name())
                val edit = applyTextEdit(original, oldText, newText, replaceAll)
                val bytes = edit.text.toByteArray(Charsets.UTF_8)
                require(bytes.size <= TEXT_MAX_BYTES) { "Result too large: ${bytes.size} bytes (max $TEXT_MAX_BYTES)" }
                sftp.put(ByteArrayInputStream(bytes), remote)
                buildJsonObject {
                    put("path", remote)
                    put("replacements", edit.count)
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
        Default timeout 30s; long-running commands can raise timeout_sec up to 600.
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
                    put("description", "Timeout in seconds, 1-600. Default 30.")
                })
            },
            required = listOf("command")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val command = obj["command"]?.jsonPrimitive?.content ?: ""
        val cwd = obj["cwd"]?.jsonPrimitive?.content ?: ""
        val timeoutSec = (obj["timeout_sec"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 600)
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
