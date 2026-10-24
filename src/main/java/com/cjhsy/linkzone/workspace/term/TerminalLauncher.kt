package com.cjhsy.linkzone.workspace.term

import android.content.Context
import com.cjhsy.linkzone.workspace.LinuxEnvInstaller
import java.io.File

/**
 * 交互终端启动器：根据环境状态决定进 PRoot Linux 还是宿主 toybox shell。
 * 与一次性执行的 WorkspaceShellRunner 共用同一套挂载参数，保证两边看到的目录一致。
 */
object TerminalLauncher {

    private const val SKILLS_MOUNT = "/skills"
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun create(context: Context, workspaceRoot: File): PtySession {
        val proot = LinuxEnvInstaller.prootBinary(context)
        val loader = LinuxEnvInstaller.prootLoader(context)
        val rootfs = LinuxEnvInstaller.rootfsDir(context)
        val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
        val tempDir = File(context.filesDir, "tmp").apply { mkdirs() }

        // 直接以 guest 的 /bin/sh 作为入口，不依赖 /usr/bin/env：
        // 后者只是个指向 busybox 的符号链接，缺了就会让整个终端死在
        // 「proot error: '/usr/bin/env' not found」上。环境变量走进程环境传入，
        // login shell 再读 /etc/profile 覆盖成 guest 的 PATH 与提示符。
        val envReady = rootfs.resolve("bin/sh").exists()

        return if (proot != null && loader != null && envReady) {
            rootfs.resolve("workspace").mkdirs()
            rootfs.resolve("skills").mkdirs()
            PtySession(
                argv = buildList {
                    add(proot.absolutePath)
                    add("--link2symlink")
                    add("--kill-on-exit")
                    add("-0")
                    add("-r")
                    add(rootfs.absolutePath)
                    add("-b")
                    add("${workspaceRoot.absolutePath}:/workspace")
                    add("-b")
                    add("${skillsDir.absolutePath}:$SKILLS_MOUNT")
                    listOf("/dev", "/proc", "/sys").forEach {
                        add("-b")
                        add(it)
                    }
                    add("-w")
                    add("/workspace")
                    add("/bin/sh")
                    add("-l")
                },
                cwd = workspaceRoot,
                env = listOf(
                    "PROOT_LOADER=${loader.absolutePath}",
                    "PROOT_TMP_DIR=${tempDir.absolutePath}",
                    "TMPDIR=${tempDir.absolutePath}",
                    "PROOT_NO_SECCOMP=1",
                    "LD_LIBRARY_PATH=${proot.parentFile?.absolutePath.orEmpty()}",
                    // guest 侧环境：PRoot 会把进程环境透传进 rootfs，
                    // 随后 /etc/profile 会再按发行版覆盖 PATH/PS1
                    "PATH=$GUEST_PATH",
                    "HOME=/root",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "LC_ALL=C.UTF-8",
                ),
            )
        } else {
            // 精简模式：宿主 toybox sh，至少能 ls/cat/sed 与进入工作区目录
            PtySession(
                argv = listOf("/system/bin/sh", "-l"),
                cwd = workspaceRoot,
                env = listOf(
                    "PATH=/system/bin:/system/xbin:/system/sbin",
                    "HOME=${context.filesDir.absolutePath}",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "TMPDIR=${tempDir.absolutePath}",
                    // mksh 会对 PS1 做命令替换：只显示当前目录名，完整路径太长会顶到换行
                    "PS1=\$(basename \"\$PWD\") \$ ",
                ),
            )
        }
    }
}
