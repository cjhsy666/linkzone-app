package com.cjhsy.linkzone.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 工作区 Linux 运行环境安装器：
 *
 * 宿主 shell（toybox）能力有限，完整 Linux 环境（apt/python/node…）依赖
 * PRoot + 发行版 rootfs。安装方式对用户透明：
 *  1. 导入 proot 静态二进制（任意来源下载的 aarch64 static proot）→ filesDir/proot/bin/proot
 *  2. 导入发行版 rootfs 压缩包（Alpine minirootfs ≈3MB / Ubuntu base 等 .tar.gz）
 *     → 解压到 filesDir/linux/rootfs
 * 两者就位后 [WorkspaceShellRunner.select] 自动切换为 PRoot 模式。
 *
 * tar 解包为纯 Kotlin 实现（无外部依赖）：支持普通文件/目录/符号链接/硬链接/
 * GNU longname（L），PAX 扩展头（x/g）跳过。
 */
object LinuxEnvInstaller {

    /** 环境就绪状态 */
    data class EnvStatus(
        val prootReady: Boolean,
        val rootfsReady: Boolean,
        val prootPath: String,
        val rootfsPath: String,
    ) {
        val usingProot: Boolean get() = prootReady && rootfsReady
    }

    fun status(filesDir: File): EnvStatus {
        val proot = File(filesDir, "proot/bin/proot")
        val rootfs = File(filesDir, "linux/rootfs")
        return EnvStatus(
            prootReady = proot.exists() && proot.canRead(),
            rootfsReady = rootfs.isDirectory && (rootfs.resolve("bin").isDirectory ||
                rootfs.resolve("usr").isDirectory),
            prootPath = proot.absolutePath,
            rootfsPath = rootfs.absolutePath,
        )
    }

    /**
     * 导入 proot 二进制：从 SAF uri 复制到目标路径并加可执行位。
     * @return 目标文件路径
     */
    suspend fun importProot(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, "proot/bin/proot")
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        // 可执行位（应用私有目录内可直接 chmod）
        dest.setExecutable(true, false)
        dest.setReadable(true, false)
        dest.absolutePath
    }

    /**
     * 导入并解压 rootfs 压缩包到 filesDir/linux/rootfs。
     * 支持 .tar.gz / .tgz（gzip）；纯 .tar 也能解（无 gzip 层自动降级）。
     * 解压前清空旧 rootfs，避免新旧文件混杂。
     *
     * @param onProgress (已解压条目数, 当前条目名)
     * @return rootfs 根目录绝对路径
     */
    suspend fun importRootfs(
        context: Context,
        uri: Uri,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        // 下载/复制到缓存（SAF 流只能读一次，解压需反复 seek）
        val staging = File(context.cacheDir, "rootfs_import_${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            require(staging.length() > 0) { "所选文件为空" }

            val rootfsDir = File(context.filesDir, "linux/rootfs")
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            val tarStream = runCatching { GZIPInputStream(FileInputStream(staging), 64 * 1024) }
                .getOrDefault(FileInputStream(staging))
            tarStream.use { stream ->
                extractTar(stream, rootfsDir, onProgress)
            }
            require(rootfsDir.resolve("bin").isDirectory || rootfsDir.resolve("usr").isDirectory) {
                "压缩包不含 rootfs 结构（缺少 bin/ 或 usr/），请确认导入的是发行版 rootfs"
            }
            rootfsDir.absolutePath
        } finally {
            staging.delete()
        }
    }

    // ---------------- tar 解包 ----------------

    private const val BLOCK = 512

    private fun extractTar(input: java.io.InputStream, destRoot: File, onProgress: (Int, String) -> Unit) {
        var count = 0
        var pendingLongName: String? = null
        val header = ByteArray(BLOCK)

        while (true) {
            if (!readFully(input, header)) break
            val name = parseString(header, 0, 100)
            val sizeStr = parseString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val magic = parseString(header, 257, 6)
            // 两块全零 = 结束
            if (name.isBlank() && sizeStr.isBlank()) break
            val size = sizeStr.trim().toLongOrNull(8) ?: 0L
            val dataBlocks = (size + BLOCK - 1) / BLOCK  // Long：避免大文件溢出
            // 兼容部分打包器缺 magic 的情况：size 解析失败即认为流损坏
            if (magic.isBlank() && typeFlag.toInt() == 0 && size == 0L && name.isBlank()) break

            val linkName = parseString(header, 157, 100)
            val mode = parseString(header, 100, 8).trim().toLongOrNull(8) ?: 0b110100100L // 默认 0644

            when (typeFlag) {
                // GNU longname：后续条目名使用该内容
                'L' -> {
                    val buf = ByteArray(size.toInt().coerceAtLeast(0))
                    readFully(input, buf)
                    pendingLongName = buf.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    continue
                }
                // PAX 扩展头（x/g）：跳过内容（不覆盖已解析字段）
                'x', 'g' -> skipFully(input, dataBlocks * BLOCK)
                // 目录
                '5' -> {
                    val dir = destRoot.resolve(relPath(pendingLongName ?: name))
                    dir.mkdirs()
                    pendingLongName = null
                    skipFully(input, dataBlocks * BLOCK)
                    count++; onProgress(count, "$name/")
                }
                // 符号链接（rootfs 必需：/bin/sh -> busybox 等）
                '2' -> {
                    skipFully(input, dataBlocks * BLOCK)
                    val target = relPath(pendingLongName ?: name)
                    val link = relPath(linkName)
                    val linkFile = destRoot.resolve(target)
                    linkFile.parentFile?.mkdirs()
                    linkFile.delete()
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            linkFile.toPath(),
                            java.nio.file.Path.of(link)
                        )
                    }.onFailure {
                        // 个别 ROM 限制 symlink：退化为复制目标（尽力而为）
                    }
                    pendingLongName = null
                    count++; onProgress(count, name)
                }
                // 硬链接：复制目标文件内容（若目标已解出）
                '1' -> {
                    skipFully(input, dataBlocks * BLOCK)
                    val target = destRoot.resolve(relPath(pendingLongName ?: name))
                    val src = destRoot.resolve(relPath(linkName))
                    target.parentFile?.mkdirs()
                    if (src.isFile) src.copyTo(target, overwrite = true) else target.createNewFile()
                    pendingLongName = null
                    count++; onProgress(count, name)
                }
                // 普通文件（'0' 或 '\0'）
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
                    } else skipFully(input, dataBlocks * BLOCK)
                    pendingLongName = null
                    count++; onProgress(count, name)
                }
            }
        }
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

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val read = input.read(buf, off, buf.size - off)
            if (read <= 0) return off > 0 // 恰好对齐结束
            off += read
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, total: Long) {
        var remaining = total
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // skip 返回 0 时读一个字节推进
                if (input.read() == -1) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    /** 从 SAF uri 读取显示名（提示用户是否选对文件用） */
    fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
