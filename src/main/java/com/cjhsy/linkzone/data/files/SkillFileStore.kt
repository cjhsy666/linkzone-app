package com.cjhsy.linkzone.data.files

import android.content.Context
import com.cjhsy.linkzone.data.model.LlmSkill
import java.io.File

/**
 * 技能文件系统存储 —— 对齐 RikkaHub SkillManager：
 *
 *   filesDir/skills/<目录名>/SKILL.md          主指令
 *   filesDir/skills/<目录名>/scripts/...        附属脚本/参考文件（真实文件，可被工作区挂载执行）
 *   filesDir/skills/<目录名>/.skill_id          技能 UUID，用于改名后稳定定位与同名消歧
 *
 * Room 的 llm_skill 表退化为元数据索引（id/name/createdAt），文件内容以磁盘为准；
 * 写入采用 staging → rename 原子落盘，带 shebang 的脚本恢复可执行位，
 * 以便在 PRoot 工作区里直接 /skills/<name>/scripts/x.py 执行。
 */
class SkillFileStore(context: Context) {

    private val rootDir: File = File(context.filesDir, ROOT).apply { mkdirs() }

    /** 技能目录根（工作区 shell 挂载 /skills 的源路径） */
    val root: File get() = rootDir

    data class OnDiskSkill(
        val dirName: String,
        val skillMd: String,
        /** 文本子文件：相对路径 → 内容；二进制资产存在于磁盘但不进此映射 */
        val files: Map<String, String>,
    )

    /** 列出磁盘上所有含 SKILL.md 的技能目录 */
    fun list(): List<OnDiskSkill> =
        rootDir.listFiles { f -> f.isDirectory && File(f, SKILL_MD).isFile }
            ?.mapNotNull { read(it.name) }
            .orEmpty()

    fun read(dirName: String): OnDiskSkill? {
        val dir = resolveDir(dirName) ?: return null
        val mdFile = dir.resolve(SKILL_MD)
        if (!mdFile.isFile) return null
        val files = linkedMapOf<String, String>()
        dir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val relative = f.relativeTo(dir).invariantSeparatorsPath
            if (relative == SKILL_MD || relative == ID_MARKER || relative == MANIFEST) return@forEach
            if (isTextFile(relative, f)) {
                LlmSkill.normalizePath(relative)?.let { key ->
                    files[key] = runCatching { f.readText(Charsets.UTF_8) }.getOrElse { return@forEach }
                }
            }
        }
        return OnDiskSkill(
            dirName = dir.name,
            skillMd = mdFile.readText(Charsets.UTF_8),
            files = files
        )
    }

    /** 读单个子文件（含越界校验）；不存在返回 null */
    fun readSubFile(dirName: String, path: String): String? {
        val dir = resolveDir(dirName) ?: return null
        val safe = LlmSkill.normalizePath(path) ?: return null
        if (safe == ID_MARKER || safe == MANIFEST) return null
        val target = dir.resolve(safe).canonicalFile
        if (!target.path.startsWith(dir.canonicalPath + File.separator)) return null
        if (!target.isFile) return null
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** 按 .skill_id 标记定位目录；找不到返回 null（改名后仍稳定） */
    fun findDirById(skillId: String): File? =
        rootDir.listFiles { f -> f.isDirectory }
            ?.firstOrNull { dir ->
                runCatching { dir.resolve(ID_MARKER).takeIf { it.isFile }?.readText()?.trim() == skillId }
                    .getOrDefault(false)
            }

    /**
     * 原子写入整套技能文件，返回最终目录名。
     * - [previousDirName]：磁盘旧目录（改名时 rename 到新目录名）
     * - 同名不同 id 时自动追加 id 短后缀消歧
     * - 文本文件以 [skill].files 为准（删除的子文件会移除）；
     *   [binaryFiles]（zip 导入的图片/字体等）只增不删，编辑器保存时予以保留
     */
    fun write(
        skill: LlmSkill,
        previousDirName: String?,
        binaryFiles: Map<String, ByteArray> = emptyMap(),
    ): String? {
        val desired = uniqueDirName(sanitize(skill.name), skill.id)
        // 1) 定位旧目录：优先 id 标记，其次旧名字；改名则整体 rename
        val existing = findDirById(skill.id)
            ?: previousDirName?.let { rootDir.resolve(it) }?.takeIf { it.isDirectory }
        val targetDir = if (existing != null && existing.name != desired) {
            val dest = rootDir.resolve(desired)
            if (dest.exists()) dest.deleteRecursively()
            if (!existing.renameTo(dest)) return null
            dest
        } else {
            rootDir.resolve(desired).apply { mkdirs() }
        }
        return try {
            val targetCanonical = targetDir.canonicalPath
            fun safeChild(relative: String): File? {
                val safe = LlmSkill.normalizePath(relative) ?: return null
                if (safe.equals(SKILL_MD, ignoreCase = true) ||
                    safe == ID_MARKER || safe == MANIFEST
                ) return null
                val f = targetDir.resolve(safe)
                return f.takeIf { it.canonicalPath.startsWith(targetCanonical + File.separator) }
            }

            // 2) 删除已不存在的"受管文本文件"（依据 manifest，二进制资产保留）
            val managedText = readManifest(targetDir)
            val newTextKeys = skill.files.keys.mapNotNull { LlmSkill.normalizePath(it) }.toSet()
            managedText.filter { it !in newTextKeys }.forEach { rel ->
                targetDir.resolve(rel).let { f ->
                    if (f.canonicalPath.startsWith(targetCanonical + File.separator)) f.delete()
                }
            }

            // 3) 写 SKILL.md + 文本子文件（同目录临时文件 + rename 原子覆盖）
            writeFile(targetDir.resolve(SKILL_MD), skill.content.toByteArray(Charsets.UTF_8))
            skill.files.forEach { (rawPath, content) ->
                val safe = LlmSkill.normalizePath(rawPath) ?: return@forEach
                if (safe.equals(SKILL_MD, ignoreCase = true)) return@forEach
                safeChild(safe)?.let { writeFile(it, content.toByteArray(Charsets.UTF_8)) }
            }
            // 4) 二进制资产（仅新增/覆盖，不做删除）
            binaryFiles.forEach { (rawPath, bytes) ->
                val safe = LlmSkill.normalizePath(rawPath) ?: return@forEach
                safeChild(safe)?.let { writeFile(it, bytes) }
            }
            // 5) 清单 + id 标记
            writeFile(
                targetDir.resolve(MANIFEST),
                managedTextManifest(newTextKeys).toByteArray(Charsets.UTF_8)
            )
            writeFile(targetDir.resolve(ID_MARKER), skill.id.toByteArray(Charsets.UTF_8))
            // 6) shebang 脚本恢复可执行位，清理空目录
            targetDir.walkTopDown().filter { it.isFile }.forEach { f ->
                if (f.startsWithShebang()) f.setExecutable(true, false)
            }
            cleanEmptyDirectories(targetDir)
            desired
        } catch (_: Exception) {
            null
        }
    }

    fun delete(dirName: String): Boolean {
        val dir = resolveDir(dirName) ?: return false
        return dir.deleteRecursively()
    }

    /** 按技能 id 删除（改名后仍可用） */
    fun deleteById(skillId: String): Boolean {
        findDirById(skillId)?.let { return it.deleteRecursively() }
        return false
    }

    private fun resolveDir(dirName: String): File? {
        if (dirName.isBlank() || dirName.contains("..") || dirName.contains('/') || dirName.contains('\\')) {
            return null
        }
        val dir = rootDir.resolve(dirName)
        if (!dir.isDirectory) return null
        val rootCanonical = rootDir.canonicalPath
        val canon = dir.canonicalPath
        if (canon != rootCanonical && !canon.startsWith(rootCanonical + File.separator)) return null
        return dir
    }

    private fun uniqueDirName(base: String, skillId: String): String {
        var candidate = base
        var attempt = 0
        while (true) {
            val dir = rootDir.resolve(candidate)
            val owner = runCatching {
                dir.resolve(ID_MARKER).takeIf { it.isFile }?.readText()?.trim()
            }.getOrNull()
            if (!dir.exists() || owner == skillId) return candidate
            attempt++
            candidate = if (attempt == 1) "$base-${skillId.take(8)}" else "$base-${skillId.take(8)}-$attempt"
        }
    }

    private fun writeFile(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = file.resolveSibling(".${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** 受管文本文件清单：每行一个相对路径（用于编辑器保存时识别被删除的子文件） */
    private fun readManifest(dir: File): List<String> =
        runCatching {
            val f = dir.resolve(MANIFEST)
            if (!f.isFile) return emptyList()
            f.readText(Charsets.UTF_8).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }.getOrDefault(emptyList())

    private fun managedTextManifest(keys: Set<String>): String =
        keys.sorted().joinToString("\n")

    private fun cleanEmptyDirectories(root: File) {
        root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { d ->
            if (d.listFiles()?.isEmpty() == true) d.delete()
        }
    }

    private fun File.startsWithShebang(): Boolean = runCatching {
        inputStream().use { it.read() == '#'.code && it.read() == '!'.code }
    }.getOrDefault(false)

    private fun isTextFile(relative: String, file: File): Boolean {
        if (file.length() == 0L) return true // 空文件视为文本（编辑器新建的未填内容子文件）
        val ext = relative.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXTENSIONS) return false
        if (file.length() > MAX_TEXT_BYTES) return false
        if (ext.isNotEmpty() && ext.matches(TEXT_EXT_PATTERN)) return true
        // 无扩展名/未知：嗅探 NUL 字节
        return file.inputStream().use { input ->
            val sample = ByteArray(512)
            val n = input.read(sample)
            if (n <= 0) return@use true
            !sample.copyOf(n).contains(0x00.toByte())
        }
    }

    companion object {
        const val ROOT = "skills"
        const val SKILL_MD = "SKILL.md"
        private const val ID_MARKER = ".skill_id"
        private const val MANIFEST = ".skill_manifest"
        private const val MAX_TEXT_BYTES = 10L * 1024 * 1024
        private val TEXT_EXT_PATTERN = Regex("[a-z0-9+]{1,12}")
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "pdf", "zip",
            "jar", "apk", "dex", "so", "a", "o", "class", "mp3", "mp4", "wav", "ogg",
            "ttf", "otf", "woff", "woff2", "eot", "exe", "bin", "dat", "db", "sqlite"
        )

        /** 目录名净化：去路径分隔/非法字符/控制字符，折叠空白 */
        fun sanitize(name: String): String =
            name.filter { it.code > 31 }
                .replace(Regex("[\\\\/:*?\"<>|]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.')
                .take(64)
                .ifBlank { "skill" }
    }
}
