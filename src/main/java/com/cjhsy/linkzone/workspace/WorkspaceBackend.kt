package com.cjhsy.linkzone.workspace

import com.cjhsy.linkzone.data.ai.SshSessionManager
import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.jcraft.jsch.ChannelSftp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 目录项（本地 / SSH 通用） */
data class WorkspaceEntryInfo(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** 命令执行结果（本地 / SSH 通用） */
data class WorkspaceExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * 工作区后端抽象：本地文件系统与 SSH 远端共用同一套能力，
 * 上层的智能体工具（workspace_*）与文件浏览/命令面板都只依赖这个接口。
 */
interface WorkspaceBackend {
    val id: String
    val name: String
    /** 展示用根路径：本地为绝对路径，SSH 为 host:port · workDir */
    val rootLabel: String
    /** shell 单次执行超时上限（毫秒） */
    val maxTimeoutMs: Long
    /** 写文件/改文件/执行命令是否需要用户批准 */
    val requiresApproval: Boolean
    /** 技能目录（仅本地后端有，用于 shell 工具提示） */
    val skillsPath: String?
    /** 是否支持本机交互式终端（仅本地） */
    val supportsTerminal: Boolean

    suspend fun readText(path: String): String
    suspend fun writeText(path: String, text: String, overwrite: Boolean): Long
    suspend fun editText(path: String, oldText: String, newText: String, replaceAll: Boolean): Pair<Int, Long>
    suspend fun listDir(path: String): List<WorkspaceEntryInfo>
    suspend fun exec(command: String, cwd: String, timeoutMs: Long): WorkspaceExecResult

    suspend fun mkdir(path: String)
    suspend fun touch(path: String)
    suspend fun rename(path: String, newName: String)
    suspend fun delete(path: String)
    suspend fun stats(): WorkspaceStats
}

internal const val TEXT_MAX_BYTES = 10 * 1024 * 1024 // 单次读写 10MB 上限

internal val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "jar", "apk",
    "dex", "so", "a", "o", "class", "mp3", "mp4", "wav", "ogg", "ttf", "otf",
    "woff", "woff2", "eot", "exe", "bin", "dat"
)

internal fun isBinaryPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS

internal fun looksBinary(bytes: ByteArray): Boolean =
    bytes.take(8192).any { it == 0.toByte() }

// ---------------------------------------------------------------------------
// 本地后端
// ---------------------------------------------------------------------------

class LocalWorkspaceBackend(
    private val workspace: Workspace,
    private val runner: WorkspaceShellRunner,
) : WorkspaceBackend {

    override val id: String get() = workspace.id
    override val name: String get() = workspace.name
    override val rootLabel: String get() = workspace.root.absolutePath
    override val maxTimeoutMs: Long = 120_000L
    override val requiresApproval: Boolean = false
    override val skillsPath: String get() = runner.skillsPath
    override val supportsTerminal: Boolean = true

    private fun resolve(rawPath: String): File {
        val safe = rawPath.trim().removePrefix("/")
        val rootCanonical = workspace.root.canonicalPath
        val target = File(workspace.root, safe).canonicalFile
        if (!target.path.startsWith(rootCanonical + File.separator) && target.path != rootCanonical) {
            error("Path '$rawPath' is outside the workspace")
        }
        return target
    }

    override suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) error("File not found: $path")
        if (file.isDirectory) error("'$path' is a directory")
        if (file.length() > TEXT_MAX_BYTES) error("File too large (>10MB)")
        if (isBinaryPath(file.name)) error("'$path' appears to be a binary file")
        val bytes = file.readBytes()
        if (looksBinary(bytes)) error("'$path' appears to be a binary file")
        String(bytes, Charsets.UTF_8)
    }

    override suspend fun writeText(path: String, text: String, overwrite: Boolean): Long =
        withContext(Dispatchers.IO) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= TEXT_MAX_BYTES) { "Content too large (>10MB)" }
            val file = resolve(path)
            if (file.exists() && !overwrite) error("File already exists: $path (overwrite=false)")
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            file.length()
        }

    override suspend fun editText(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) error("File not found: $path")
        if (file.isDirectory) error("'$path' is a directory")
        if (file.length() > TEXT_MAX_BYTES) error("File too large (>10MB)")
        val original = file.readText(Charsets.UTF_8)
        val edit = applyTextEdit(original, oldText, newText, replaceAll)
        val bytes = edit.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TEXT_MAX_BYTES) { "Result too large (>10MB)" }
        file.writeText(edit.text, Charsets.UTF_8)
        edit.count to file.length()
    }

    override suspend fun listDir(path: String): List<WorkspaceEntryInfo> = withContext(Dispatchers.IO) {
        val dir = resolve(path.ifBlank { "." })
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles().orEmpty()
            .map {
                WorkspaceEntryInfo(
                    name = it.name,
                    isDir = it.isDirectory,
                    size = if (it.isDirectory) 0L else it.length(),
                    lastModified = it.lastModified(),
                )
            }
            .sortedWith(compareByDescending<WorkspaceEntryInfo> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun exec(command: String, cwd: String, timeoutMs: Long): WorkspaceExecResult =
        withContext(Dispatchers.IO) {
            val result = runner.execute(
                WorkspaceShellContext(
                    root = workspace.root,
                    command = command,
                    cwd = cwd,
                    timeoutMillis = timeoutMs,
                )
            )
            WorkspaceExecResult(result.exitCode, result.stdout, result.stderr, result.timedOut)
        }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (!dir.isDirectory && !dir.mkdirs()) error("Failed to create directory: $path")
    }

    override suspend fun touch(path: String) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        if (!file.exists() && !file.createNewFile()) error("Failed to create file: $path")
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val from = resolve(path)
        if (!from.exists()) error("File not found: $path")
        val to = File(from.parentFile, newName)
        if (!from.renameTo(to)) error("Failed to rename '$path' to '$newName'")
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val target = resolve(path)
        if (target.exists() && !target.deleteRecursively()) error("Failed to delete: $path")
    }

    override suspend fun stats(): WorkspaceStats = withContext(Dispatchers.IO) {
        var files = 0
        var bytes = 0L
        workspace.root.walkTopDown().forEach { f ->
            if (f.isFile && f.name != WorkspaceManager.META_FILE) {
                files++
                bytes += f.length()
            }
        }
        WorkspaceStats(files, bytes)
    }
}

// ---------------------------------------------------------------------------
// SSH 后端
// ---------------------------------------------------------------------------

class SshWorkspaceBackend(
    private val config: SshWorkspaceConfig,
) : WorkspaceBackend {

    override val id: String get() = config.id
    override val name: String get() = config.name.ifBlank { config.summary }
    override val rootLabel: String get() = "${config.summary} · ${config.workDir}"
    override val maxTimeoutMs: Long = 600_000L
    override val requiresApproval: Boolean = true
    override val skillsPath: String? = null
    override val supportsTerminal: Boolean = false

    override suspend fun readText(path: String): String =
        SshSessionManager.withSftp(config) { sftp ->
            val remote = resolveRemote(sftp, config, path)
            if (isBinaryPath(remote)) error("Binary file type is not supported: $remote")
            val stat = sftp.stat(remote)
            if (stat.isDir) error("Path is a directory: $remote")
            if (stat.size > TEXT_MAX_BYTES) error("File too large: ${stat.size} bytes")
            val out = ByteArrayOutputStream()
            sftp.get(remote, out)
            val bytes = out.toByteArray()
            if (looksBinary(bytes)) error("Binary file type is not supported: $remote")
            String(bytes, Charsets.UTF_8)
        }

    override suspend fun writeText(path: String, text: String, overwrite: Boolean): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TEXT_MAX_BYTES) { "Content too large (>10MB)" }
        return SshSessionManager.withSftp(config) { sftp ->
            val remote = resolveRemote(sftp, config, path)
            if (!overwrite && runCatching { sftp.stat(remote) }.isSuccess) {
                error("File already exists: $path (overwrite=false)")
            }
            ensureDirs(sftp, remote.substringBeforeLast('/', ""))
            sftp.put(ByteArrayInputStream(bytes), remote)
            bytes.size.toLong()
        }
    }

    override suspend fun editText(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
    ): Pair<Int, Long> = SshSessionManager.withSftp(config) { sftp ->
        val remote = resolveRemote(sftp, config, path)
        if (isBinaryPath(remote)) error("Binary file type is not supported: $remote")
        val stat = sftp.stat(remote)
        if (stat.isDir) error("Path is a directory: $remote")
        if (stat.size > TEXT_MAX_BYTES) error("File too large: ${stat.size} bytes")
        val out = ByteArrayOutputStream()
        sftp.get(remote, out)
        val original = out.toString(Charsets.UTF_8.name())
        val edit = applyTextEdit(original, oldText, newText, replaceAll)
        val bytes = edit.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TEXT_MAX_BYTES) { "Result too large (>10MB)" }
        sftp.put(ByteArrayInputStream(bytes), remote)
        edit.count to bytes.size.toLong()
    }

    override suspend fun listDir(path: String): List<WorkspaceEntryInfo> =
        SshSessionManager.withSftp(config) { sftp ->
            val remote = resolveRemote(sftp, config, path)
            val entries = mutableListOf<WorkspaceEntryInfo>()
            for (item in sftp.ls(remote)) {
                val e = item as ChannelSftp.LsEntry
                if (e.filename == "." || e.filename == "..") continue
                entries += WorkspaceEntryInfo(
                    name = e.filename,
                    isDir = e.attrs.isDir,
                    size = if (e.attrs.isDir) 0L else e.attrs.size,
                    lastModified = e.attrs.mTime.toLong() * 1000L,
                )
            }
            entries.sortedWith(compareByDescending<WorkspaceEntryInfo> { it.isDir }.thenBy { it.name.lowercase() })
        }

    override suspend fun exec(command: String, cwd: String, timeoutMs: Long): WorkspaceExecResult {
        val r = SshSessionManager.exec(config, command, cwd, timeoutMs)
        return WorkspaceExecResult(r.exitCode, r.stdout, r.stderr, r.timedOut)
    }

    override suspend fun mkdir(path: String) = SshSessionManager.withSftp(config) { sftp ->
        val remote = resolveRemote(sftp, config, path)
        ensureDirs(sftp, remote)
    }

    override suspend fun touch(path: String) = SshSessionManager.withSftp(config) { sftp ->
        val remote = resolveRemote(sftp, config, path)
        ensureDirs(sftp, remote.substringBeforeLast('/', ""))
        sftp.put(ByteArrayInputStream(ByteArray(0)), remote)
        Unit
    }

    override suspend fun rename(path: String, newName: String) = SshSessionManager.withSftp(config) { sftp ->
        val from = resolveRemote(sftp, config, path)
        val target = normalize(from.substringBeforeLast('/', "") + "/" + newName)
        val workDirAbs = sftp.realpath(expandPath(sftp, config.workDir))
        if (target != workDirAbs && !target.startsWith("$workDirAbs/")) {
            error("Path '$newName' is outside the workspace")
        }
        sftp.rename(from, target)
        Unit
    }

    override suspend fun delete(path: String) = SshSessionManager.withSftp(config) { sftp ->
        val target = resolveRemote(sftp, config, path)
        deleteRecursive(sftp, target)
        Unit
    }

    override suspend fun stats(): WorkspaceStats = SshSessionManager.withSftp(config) { sftp ->
        var files = 0
        var bytes = 0L
        val base = resolveRemote(sftp, config, "")
        fun walk(dir: String, depth: Int) {
            if (depth > 30 || files >= 2000) return
            for (item in runCatching { sftp.ls(dir) }.getOrNull().orEmpty()) {
                val e = item as? ChannelSftp.LsEntry ?: continue
                if (e.filename == "." || e.filename == "..") continue
                if (e.attrs.isDir) walk("$dir/${e.filename}", depth + 1) else {
                    files++
                    bytes += e.attrs.size
                }
            }
        }
        walk(base, 0)
        WorkspaceStats(files, bytes)
    }

    private fun ensureDirs(sftp: ChannelSftp, absPath: String) {
        val parts = absPath.split('/').filter { it.isNotEmpty() }
        var cur = ""
        for (p in parts) {
            cur += "/$p"
            if (runCatching { sftp.stat(cur) }.isFailure) runCatching { sftp.mkdir(cur) }
        }
    }

    private fun deleteRecursive(sftp: ChannelSftp, path: String) {
        val attrs = runCatching { sftp.stat(path) }.getOrNull() ?: return
        if (attrs.isDir) {
            for (item in runCatching { sftp.ls(path) }.getOrNull().orEmpty()) {
                val e = item as? ChannelSftp.LsEntry ?: continue
                if (e.filename == "." || e.filename == "..") continue
                deleteRecursive(sftp, "$path/${e.filename}")
            }
            runCatching { sftp.rmdir(path) }
        } else {
            runCatching { sftp.rm(path) }
        }
    }
}

private fun expandPath(sftp: ChannelSftp, path: String): String {
    val home = sftp.home
    return when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.substring(1)
        else -> path
    }
}

/** 相对/绝对路径 → 远端绝对路径；越出 workDir 抛异常。在 SFTP 通道内调用。 */
private fun resolveRemote(sftp: ChannelSftp, config: SshWorkspaceConfig, rawPath: String): String {
    val raw = rawPath.trim()
    val home = sftp.home
    fun expand(p: String): String = when {
        p == "~" -> home
        p.startsWith("~/") -> home + p.substring(1)
        else -> p
    }
    val workDirAbs = sftp.realpath(expand(config.workDir))
    val joined = when {
        raw.isEmpty() || raw == "." -> workDirAbs
        raw.startsWith("/") || raw == "~" || raw.startsWith("~/") -> expand(raw)
        else -> "$workDirAbs/$raw"
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

// ---------------------------------------------------------------------------
// 文本局部替换（对齐 RikkaHub 的三级容错）
// ---------------------------------------------------------------------------

/** 局部替换结果：替换后全文 + 实际替换次数 */
internal data class TextEditResult(val text: String, val count: Int)

/**
 * 1. 精确匹配；出现多次且未开 replace_all 时报错，要求给更多上下文
 * 2. 精确匹配失败时，按行 trim 后匹配（容忍缩进/行尾空白差异）
 * 3. 仍失败则报错，提示补充上下文
 * CRLF 文件自动把 old/new 的换行对齐成 \r\n，避免「看得见却匹配不上」。
 */
internal fun applyTextEdit(
    content: String,
    oldText: String,
    newText: String,
    replaceAll: Boolean,
): TextEditResult {
    require(oldText.isNotEmpty()) { "old_text must not be empty" }
    val crlf = content.contains("\r\n")

    fun align(s: String): String =
        if (!crlf || s.contains("\r\n")) s else s.replace("\n", "\r\n")

    val oldAligned = align(oldText)
    val newAligned = align(newText)

    val times = countOccurrences(content, oldAligned)
    if (times > 0) {
        if (times > 1 && !replaceAll) {
            error("old_text appears $times times. Provide more surrounding context or set replace_all=true.")
        }
        val result = if (replaceAll) content.replace(oldAligned, newAligned)
        else content.replaceFirst(oldAligned, newAligned)
        return TextEditResult(result, if (replaceAll) times else 1)
    }

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
