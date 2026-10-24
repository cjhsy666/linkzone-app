package com.cjhsy.linkzone.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * rootfs 修补器：把官方发行版 rootfs 调整为「Android + PRoot」下可直接运行的状态。
 *
 * 官方 rootfs 假设自己跑在真机上，以下三处在 Android 上会直接失效：
 *  1. 绝对路径符号链接（`/bin/sh -> /bin/busybox`）：PRoot 之外的宿主机内核按
 *     Android 根目录解析，全部变成断链 → 统一改写为相对链接；
 *  2. 缺 /etc/resolv.conf：musl/glibc 无法解析域名，apk/apt/curl 全部超时；
 *  3. 软件源：Alpine 默认从海外 CDN 拉包，国内基本不可用 → 换国内镜像。
 * 另外补齐 /dev /proc /sys 挂载点、/tmp 权限与基础环境变量。
 */
object RootfsPatcher {

    /** 修补结果，用于给用户展示「修了什么」 */
    data class Report(val notes: List<String>)

    fun patch(rootfs: File): Report {
        val notes = mutableListOf<String>()

        listOf("dev", "proc", "sys", "tmp", "root", "home", "etc", "etc/profile.d", "etc/ssl/certs", "var/cache/apk")
            .forEach { ensureDir(rootfs, it) }
        chmodWritable(rootfs.resolve("tmp"))

        if (rewriteAbsoluteSymlinks(rootfs) > 0) notes += "修正绝对路径符号链接"

        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.readText().isBlank()) {
            resolv.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
            notes += "写入 DNS"
        }

        ensureLines(
            File(rootfs, "etc/hosts"),
            "127.0.0.1 localhost\n::1 localhost\n",
            required = "127.0.0.1 localhost",
        )
        ensureLines(File(rootfs, "etc/passwd"), "root:x:0:0:root:/root:/bin/sh\n", required = "root:")
        ensureLines(File(rootfs, "etc/group"), "root:x:0:\n", required = "root:")

        File(rootfs, "etc/ld.so.preload").takeIf { it.exists() }?.let {
            it.delete()
            notes += "移除 ld.so.preload"
        }

        val profile = File(rootfs, "etc/profile.d/linkzone.sh")
        if (!profile.exists()) {
            profile.writeText(
                """
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export LANG=C.UTF-8
                export LC_ALL=C.UTF-8
                export TERM=xterm-256color
                export HOME=/root
                """.trimIndent() + "\n"
            )
            notes += "写入环境变量"
        }

        patchRepositories(rootfs)?.let { notes += it }
        return Report(notes)
    }

    /** Alpine：按版本号把软件源换到阿里云镜像（带 CA，https 可用） */
    private fun patchRepositories(rootfs: File): String? {
        val release = File(rootfs, "etc/alpine-release")
        val repos = File(rootfs, "etc/apk/repositories")
        if (!release.isFile) return null
        val version = release.readText().trim().split('.').take(2).joinToString(".")
        if (version.isBlank()) return null
        val content = buildString {
            appendLine("https://mirrors.aliyun.com/alpine/v$version/main")
            appendLine("https://mirrors.aliyun.com/alpine/v$version/community")
        }
        if (repos.isFile && repos.readText() == content) return null
        repos.parentFile?.mkdirs()
        repos.writeText(content)
        return "切换 apk 软件源到阿里云 v$version"
    }

    /**
     * 绝对符号链接 → 相对链接。
     * 例：`usr/bin/yes -> /bin/busybox` 改写为 `../../bin/busybox`。
     * PRoot 不接管符号链接解析（由宿主机内核完成），绝对链接会指向 Android 根目录而不是 rootfs。
     */
    private fun rewriteAbsoluteSymlinks(rootfs: File): Int {
        var fixed = 0
        val root: Path = rootfs.toPath()
        runCatching {
            Files.walk(root).use { paths ->
                paths.forEach { p ->
                    if (p == root || !Files.isSymbolicLink(p)) return@forEach
                    val target = runCatching { Files.readSymbolicLink(p).toString() }.getOrNull() ?: return@forEach
                    if (!target.startsWith("/")) return@forEach
                    val rel = root.relativize(p).toString().replace('\\', '/')
                    val depth = rel.split('/').size - 1
                    val rewritten = ("../".repeat(depth) + target.trimStart('/')).ifBlank { "." }
                    runCatching {
                        Files.delete(p)
                        Files.createSymbolicLink(p, Path.of(rewritten))
                        fixed++
                    }
                }
            }
        }
        return fixed
    }

    private fun ensureDir(root: File, relative: String) {
        val dir = File(root, relative)
        dir.mkdirs()
        dir.setReadable(true, false)
        dir.setWritable(true, false)
        dir.setExecutable(true, false)
    }

    /** /tmp 需要全局可写（0777），PRoot 下以 root 身份运行也无副作用 */
    private fun chmodWritable(dir: File) {
        if (!dir.exists()) return
        dir.setReadable(true, false)
        dir.setWritable(true, false)
        dir.setExecutable(true, false)
    }

    private fun ensureLines(file: File, block: String, required: String) {
        val existing = if (file.isFile) file.readText() else ""
        if (existing.contains(required)) return
        file.parentFile?.mkdirs()
        file.writeText((existing.trimEnd() + "\n" + block).trimStart('\n'))
    }
}
