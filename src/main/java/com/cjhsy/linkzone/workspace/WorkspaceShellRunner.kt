package com.cjhsy.linkzone.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** Shell 执行抽象：Host（设备自带 /system/bin/sh）或 PRoot（发行版 rootfs） */
interface WorkspaceShellRunner {
    /** 技能目录在 shell 视角下的路径（PRoot 为 /skills，宿主为 filesDir/skills 绝对路径） */
    val skillsPath: String

    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult

    companion object {
        /** 选择当前可用 runner：检测到 proot 与 rootfs 时用 PRoot，否则回退宿主 shell */
        fun select(context: android.content.Context): WorkspaceShellRunner {
            val proot = LinuxEnvInstaller.prootBinary(context)
            val rootfs = LinuxEnvInstaller.rootfsDir(context)
            val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
            return if (proot != null && rootfs.isDirectory) {
                ProotShellRunner(proot, rootfs, skillsDir, File(context.filesDir, "tmp").apply { mkdirs() })
            } else {
                HostShellRunner(skillsDir)
            }
        }
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
 * PRoot shell runner —— 当内置 proot（jniLibs/libproot_exec.so）与
 * filesDir/linux/rootfs 均就位时启用。
 *
 * 通过 proot 把 rootfs 挂载为根、把工作区绑定到 /workspace、技能目录绑定到 /skills，
 * 再把宿主的 /dev /proc /sys 挂进 guest（否则 /dev/null、/dev/urandom 与进程信息全缺），
 * 从而在无 root 设备上获得完整 Linux 用户态（apk/python/node 等取决于 rootfs）。
 */
class ProotShellRunner(
    private val prootBinary: File,
    private val rootfs: File,
    private val skillsDir: File,
    private val tempDir: File,
) : WorkspaceShellRunner {
    override val skillsPath: String = SKILLS_MOUNT

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val workingDir = context.root.resolve(context.cwd.ifBlank { "." })
            .canonicalFile
            .apply { mkdirs() }
        val args = buildList {
            add(prootBinary.absolutePath)
            add("--link2symlink")
            add("--kill-on-exit")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("${context.root.absolutePath}:/workspace")
            add("-b")
            add("${skillsDir.absolutePath}:$SKILLS_MOUNT")
            // 内核文件系统：guest rootfs 里这些目录是空的，必须借宿主
            listOf("/dev", "/proc", "/sys").forEach {
                add("-b")
                add(it)
            }
            add("-w")
            add("/workspace/${context.cwd.trimStart('/')}")
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("PATH=$GUEST_PATH")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            add("/bin/sh")
            add("-l")
            add("-c")
            add(context.command)
        }
        val process = ProcessBuilder(args)
            .directory(workingDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
            }
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    companion object {
        const val SKILLS_MOUNT = "/skills"
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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
