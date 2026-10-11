package com.cjhsy.linkzone.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Shell 执行抽象：Host（设备自带 /system/bin/sh）或 PRoot（发行版 rootfs） */
interface WorkspaceShellRunner {
    /** 技能目录在 shell 视角下的路径（PRoot 为 /skills，宿主为 filesDir/skills 绝对路径） */
    val skillsPath: String

    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult

    companion object {
        /** 选择当前可用 runner：检测到 proot 二进制与 rootfs 时用 PRoot，否则回退宿主 shell */
        fun select(filesDir: File): WorkspaceShellRunner {
            val proot = File(filesDir, "proot/bin/proot")
            val rootfs = File(filesDir, "linux/rootfs")
            val skillsDir = File(filesDir, "skills").apply { mkdirs() }
            return if (proot.canExecute() && rootfs.isDirectory) {
                ProotShellRunner(proot, rootfs, skillsDir)
            } else {
                HostShellRunner(skillsDir)
            }
        }

        private fun File.canExecute(): Boolean = exists() && canRead()
    }
}

data class WorkspaceShellContext(
    /** 工作区根目录 */
    val root: File,
    val command: String,
    /** 相对工作区根的工作目录（空串=根） */
    val cwd: String = "",
    val timeoutMillis: Long = 30_000L,
    val stdin: ByteArray? = null,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * 宿主 shell runner —— 直接用 Android 自带 /system/bin/sh（toybox）执行。
 * 无 root、无包管理器，但 ls/cat/mkdir/sed/awk/find/sh 脚本等常用能力可用，
 * 工作目录锁定在工作区内。技能目录以绝对路径可达（同 uid）。
 */
class HostShellRunner(
    private val skillsDir: File,
) : WorkspaceShellRunner {
    override val skillsPath: String get() = skillsDir.absolutePath

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val workingDir = context.root.resolve(context.cwd.ifBlank { "." })
            .canonicalFile
            .apply { mkdirs() }
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

/**
 * PRoot shell runner —— 当 filesDir/proot/bin/proot 与 filesDir/linux/rootfs
 * 均就位时启用（用户自行放入 proot 二进制与解压好的 rootfs）。
 *
 * 通过 proot 把 rootfs 挂载为根、把工作区绑定到 /workspace、技能目录绑定到 /skills，
 * 从而在无 root 设备上获得完整 Linux 用户态（apt/python/node 等取决于 rootfs），
 * skill 附带脚本可直接 `python /skills/<name>/scripts/x.py` 执行。
 */
class ProotShellRunner(
    private val prootBinary: File,
    private val rootfs: File,
    private val skillsDir: File,
) : WorkspaceShellRunner {
    override val skillsPath: String = SKILLS_MOUNT

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val workingDir = context.root.resolve(context.cwd.ifBlank { "." })
            .canonicalFile
            .apply { mkdirs() }
        val args = buildList {
            add(prootBinary.absolutePath)
            add("--link2symlink")
            add("-S")
            add(rootfs.absolutePath)
            add("-b")
            add("${context.root.absolutePath}:/workspace")
            add("-b")
            add("${skillsDir.absolutePath}:$SKILLS_MOUNT")
            add("-w")
            add("/workspace/${context.cwd.trimStart('/')}")
            add("/bin/sh")
            add("-c")
            add(context.command)
        }
        val process = ProcessBuilder(args)
            .directory(workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    companion object {
        const val SKILLS_MOUNT = "/skills"
    }
}

/** 单流最大字符数，防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文 */
const val MAX_SHELL_OUTPUT_CHARS = 128 * 1024

private fun Process.readResult(timeoutMillis: Long, stdin: ByteArray?): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { StreamWriter(outputStream, it) }
    if (stdinWriter == null) {
        // 无 stdin 输入时立即关闭管道，避免 cat 等按 isatty 判断的命令永久阻塞
        try { outputStream.close() } catch (_: IOException) { }
    }
    val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    val timedOut = !finished
    if (timedOut) destroyForcibly()
    stdinWriter?.join(1_000)
    stdout.join(2_000)
    stderr.join(2_000)
    val exit = if (finished) exitValue() else -1
    val outText = stdout.toString(Charsets.UTF_8)
    val errText = stderr.toString(Charsets.UTF_8)
    destroyForcibly()
    return WorkspaceCommandResult(exit, outText, errText, timedOut)
}

private class StreamCollector(private val input: InputStream) : Thread() {
    private val builder = StringBuilder()
    override fun run() {
        val buffer = ByteArray(4096)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (n <= 0) break
            if (builder.length < MAX_SHELL_OUTPUT_CHARS) {
                val available = MAX_SHELL_OUTPUT_CHARS - builder.length
                val text = String(buffer, 0, n, Charsets.UTF_8)
                builder.append(text.take(available))
            }
        }
    }

    fun toString(charset: Charset): String {
        runCatching { join(500) }
        var text = builder.toString()
        if (text.length >= MAX_SHELL_OUTPUT_CHARS) {
            text += "\n... [output truncated]"
        }
        return text
    }
}

private class StreamWriter(
    private val output: java.io.OutputStream,
    private val bytes: ByteArray,
) : Thread() {
    override fun run() {
        try {
            output.write(bytes)
            output.flush()
        } catch (_: IOException) {
            // 子进程提前退出
        } finally {
            try { output.close() } catch (_: IOException) { }
        }
    }
}
