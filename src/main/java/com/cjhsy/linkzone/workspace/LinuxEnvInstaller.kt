package com.cjhsy.linkzone.workspace

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * 工作区 Linux 运行环境安装器。
 *
 * 宿主 shell（toybox）能力有限，完整 Linux 环境（apk/apt/python/node…）依赖
 * PRoot + 发行版 rootfs。两者来源不同：
 *
 *  - **proot**：随 APK 打包在 jniLibs（`libproot_exec.so`），运行时从
 *    `nativeLibraryDir` 直接执行。Android 10+ 禁止 exec 应用私有目录下的可写文件，
 *    只有 apk 的 native 库目录可执行，因此不能用「下载到 filesDir 再 chmod」的老做法。
 *  - **rootfs**：一键下载官方 Alpine minirootfs（≈4MB），解压 + 修补（见 RootfsPatcher）。
 *    也保留手动导入 .tar.gz/.tar/.zip 的入口，供自定义发行版使用。
 */
object LinuxEnvInstaller {

    /** 当前系统架构对应的 Alpine 官方 minirootfs 地址 */
    fun defaultRootfsUrl(): String {
        val arch = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "aarch64"
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
            Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" || it == "armeabi" } -> "armhf"
            Build.SUPPORTED_ABIS.any { it == "x86" } -> "x86"
            else -> "aarch64"
        }
        return "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/$arch/" +
            "alpine-minirootfs-3.24.0-$arch.tar.gz"
    }

    /** 环境就绪状态 */
    data class EnvStatus(
        val prootReady: Boolean,
        val rootfsReady: Boolean,
        val prootPath: String,
        val rootfsPath: String,
    ) {
        val usingProot: Boolean get() = prootReady && rootfsReady
    }

    /** 安装进度：percent 0-100，message 为当前步骤文案 */
    data class InstallProgress(val percent: Int, val message: String)

    /** proot 可执行文件：Termux 版 proot，随 APK 打包在 native 库目录 */
    fun prootBinary(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")
            .takeIf { it.isFile && it.canRead() }

    /** proot 外置 loader：Android 10+ 私有目录不可 exec，只能放在 native 库目录 */
    fun prootLoader(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")
            .takeIf { it.isFile && it.canRead() }

    /** rootfs 目录 */
    fun rootfsDir(context: Context): File = File(context.filesDir, "linux/rootfs")

    fun status(context: Context): EnvStatus {
        val proot = prootBinary(context)
        val rootfs = rootfsDir(context)
        return EnvStatus(
            prootReady = proot != null && prootLoader(context) != null,
            rootfsReady = isUsableRootfs(rootfs),
            prootPath = proot?.absolutePath.orEmpty(),
            rootfsPath = rootfs.absolutePath,
        )
    }

    private fun isUsableRootfs(rootfs: File): Boolean =
        rootfs.isDirectory && (rootfs.resolve("bin").isDirectory || rootfs.resolve("usr").isDirectory)

    // ---------------- 一键安装 ----------------

    /**
     * 一键安装：下载官方 rootfs → 解压到临时目录 → 修补 → 原子替换。
     * proot 已随 APK 打包，无需下载。
     *
     * @return rootfs 根目录绝对路径
     */
    suspend fun installFromUrl(
        context: Context,
        rootfsUrl: String = defaultRootfsUrl(),
        onProgress: (InstallProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        require(prootBinary(context) != null && prootLoader(context) != null) {
            "当前设备架构没有内置 proot（仅支持 arm64-v8a / x86_64），无法安装"
        }
        require(rootfsUrl.startsWith("http")) { "rootfs 地址无效" }

        val tempDir = File(context.cacheDir, "rootfs-install").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            onProgress(InstallProgress(3, "正在下载 rootfs…"))
            val archive = File(tempDir, "rootfs.archive")
            download(rootfsUrl, archive) { percent ->
                onProgress(InstallProgress(3 + percent * 57 / 100, "正在下载 rootfs… $percent%"))
            }

            onProgress(InstallProgress(62, "正在解压 rootfs…"))
            val staging = stagingDir(context)
            staging.deleteRecursively()
            staging.mkdirs()
            var lastTick = 0L
            extract(archive, staging) { count, name ->
                val now = System.currentTimeMillis()
                if (now - lastTick > 500) {
                    lastTick = now
                    onProgress(InstallProgress(62, "正在解压 rootfs… 已处理 $count 项（$name）"))
                }
            }

            require(isUsableRootfs(staging)) { "压缩包不含 rootfs 结构（缺少 bin/ 或 usr/）" }

            onProgress(InstallProgress(92, "正在修补 rootfs…"))
            RootfsPatcher.patch(staging)

            onProgress(InstallProgress(98, "正在启用环境…"))
            activate(staging, rootfsDir(context))
            onProgress(InstallProgress(100, "安装完成"))
            rootfsDir(context).absolutePath
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** 下载到本地文件，回调 0-100（Content-Length 未知时按已知区间给 0/50） */
    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LinkZone-Android")
        }
        try {
            if (conn.responseCode !in 200..299) error("下载失败：HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var written = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        val percent = if (total > 0) ((written * 100) / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            require(dest.length() > 0) { "下载内容为空" }
            if (total > 0) onProgress(100)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 手动导入 rootfs 压缩包（.tar.gz / .tgz / .tar / .zip），同样解压后修补再启用。
     *
     * @param onProgress (已解压条目数, 当前条目名)
     */
    suspend fun importRootfs(
        context: Context,
        uri: Uri,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val staging = stagingDir(context)
        staging.deleteRecursively()
        staging.mkdirs()
        val cached = File(context.cacheDir, "rootfs_import_${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cached).use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            require(cached.length() > 0) { "所选文件为空" }

            extract(cached, staging, onProgress)
            require(isUsableRootfs(staging)) {
                "压缩包不含 rootfs 结构（缺少 bin/ 或 usr/），请确认导入的是发行版 rootfs"
            }
            RootfsPatcher.patch(staging)
            activate(staging, rootfsDir(context))
            rootfsDir(context).absolutePath
        } finally {
            cached.delete()
        }
    }

    private fun stagingDir(context: Context): File =
        File(context.filesDir, "linux/.staging")

    /** 临时目录 → 正式目录：同一分区 rename，避免旧 rootfs 被删到一半时崩在中间 */
    private fun activate(staging: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
    }

    // ---------------- 归档解包 ----------------

    private enum class ArchiveFormat { TAR_GZ, TAR, ZIP }

    private fun detectFormat(file: File): ArchiveFormat {
        val magic = ByteArray(4)
        FileInputStream(file).use { it.read(magic) }
        return when {
            magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> ArchiveFormat.TAR_GZ
            magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() -> ArchiveFormat.ZIP
            else -> ArchiveFormat.TAR
        }
    }

    private fun extract(file: File, dest: File, onProgress: (Int, String) -> Unit = { _, _ -> }) {
        when (detectFormat(file)) {
            ArchiveFormat.ZIP -> FileInputStream(file).use { extractZip(it, dest, onProgress) }
            ArchiveFormat.TAR_GZ -> FileInputStream(file).use { fis ->
                GZIPInputStream(fis, 64 * 1024).use { extractTar(it, dest, onProgress) }
            }
            ArchiveFormat.TAR -> FileInputStream(file).use { extractTar(it, dest, onProgress) }
        }
    }

    private fun extractZip(input: InputStream, destRoot: File, onProgress: (Int, String) -> Unit) {
        ZipInputStream(input).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                val rel = relPath(entry.name)
                if (rel.isNotBlank()) {
                    val out = destRoot.resolve(rel)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                    }
                    count++
                    onProgress(count, entry.name)
                }
                zip.closeEntry()
            }
        }
    }

    // ---------------- tar 解包 ----------------

    private const val BLOCK = 512

    private fun extractTar(input: InputStream, destRoot: File, onProgress: (Int, String) -> Unit) {
        var count = 0
        var pendingLongName: String? = null
        val header = ByteArray(BLOCK)

        while (true) {
            if (!readFully(input, header)) break
            val name = parseString(header, 0, 100)
            val sizeStr = parseString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val magic = parseString(header, 257, 6)
            if (name.isBlank() && sizeStr.isBlank()) break
            val size = sizeStr.trim().toLongOrNull(8) ?: 0L
            // tar 头部 mode 字段决定可执行位：解出来的 /bin/busybox 没有 x 位就完全跑不起来
            val mode = parseString(header, 100, 8).trim().toLongOrNull(8) ?: 0L
            val dataBlocks = (size + BLOCK - 1) / BLOCK
            if (magic.isBlank() && typeFlag.toInt() == 0 && size == 0L && name.isBlank()) break

            val linkName = parseString(header, 157, 100)

            when (typeFlag) {
                'L' -> {
                    val buf = ByteArray(size.toInt().coerceAtLeast(0))
                    readFully(input, buf)
                    pendingLongName = buf.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    // 数据区按 512 对齐，长文件名数据同样有填充
                    skipFully(input, dataBlocks * BLOCK - size)
                    continue
                }
                'x', 'g' -> skipFully(input, dataBlocks * BLOCK)
                '5' -> {
                    val dir = destRoot.resolve(relPath(pendingLongName ?: name))
                    dir.mkdirs()
                    applyMode(dir, mode, isDir = true)
                    pendingLongName = null
                    skipFully(input, dataBlocks * BLOCK)
                    count++; onProgress(count, "$name/")
                }
                '2' -> {
                    skipFully(input, dataBlocks * BLOCK)
                    val path = relPath(pendingLongName ?: name)
                    val linkFile = destRoot.resolve(path)
                    linkFile.parentFile?.mkdirs()
                    linkFile.delete()
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            linkFile.toPath(),
                            java.nio.file.Path.of(relativeSymlinkTarget(path, linkName))
                        )
                    }
                    pendingLongName = null
                    count++; onProgress(count, name)
                }
                '1' -> {
                    skipFully(input, dataBlocks * BLOCK)
                    val target = destRoot.resolve(relPath(pendingLongName ?: name))
                    val src = destRoot.resolve(relPath(linkName))
                    target.parentFile?.mkdirs()
                    if (src.isFile) src.copyTo(target, overwrite = true) else target.createNewFile()
                    pendingLongName = null
                    count++; onProgress(count, name)
                }
                else -> {
                    val f = destRoot.resolve(relPath(pendingLongName ?: name))
                    f.parentFile?.mkdirs()
                    if (!f.isDirectory) {
                        FileOutputStream(f).use { output ->
                            var remaining = size
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                    }
                    // 数据区按 512 字节对齐，不足部分要跳过，否则下一块 header 会错位
                    skipFully(input, dataBlocks * BLOCK - size)
                    applyMode(f, mode, isDir = false)
                    pendingLongName = null
                    count++; onProgress(count, name)
                }
            }
        }
    }

    /**
     * 按 tar 头部 mode 还原权限。
     * PRoot 内以 fake-root 运行，只要保证：可读、可写、可执行位与归档一致。
     * Java 的 File.setExecutable 在 Android 上等价于 chmod，能正确落到 0700/0755。
     */
    private fun applyMode(file: File, mode: Long, isDir: Boolean) {
        file.setReadable(true, false)
        file.setWritable(true, false)
        if (isDir || (mode and 0b001_001_001L) != 0L) {
            file.setExecutable(true, false)
        }
    }

    /** 绝对链接改写为相对链接：`usr/bin/yes -> /bin/busybox` ⇒ `../../bin/busybox` */
    private fun relativeSymlinkTarget(linkPath: String, rawTarget: String): String {
        if (!rawTarget.startsWith("/")) return rawTarget
        val depth = linkPath.split('/').size - 1
        return "../".repeat(depth) + rawTarget.trimStart('/')
    }

    /** 相对路径安全化：剥掉 ./ 与 / 前缀，拒绝 .. 逃逸（zip-slip 同源防护） */
    private fun relPath(raw: String): String {
        val cleaned = raw.replace("\\", "/").split('/').filter { it.isNotBlank() && it != "." }
        require(cleaned.none { it == ".." }) { "压缩包含非法路径: $raw" }
        return cleaned.joinToString("/")
    }

    private fun parseString(block: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val max = offset + len
        while (end < max && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val read = input.read(buf, off, buf.size - off)
            if (read <= 0) return off > 0
            off += read
        }
        return true
    }

    private fun skipFully(input: InputStream, total: Long) {
        var remaining = total
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
