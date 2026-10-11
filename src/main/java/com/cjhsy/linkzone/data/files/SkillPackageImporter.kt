package com.cjhsy.linkzone.data.files

import java.util.zip.ZipInputStream

/**
 * 标准 skill 包（.zip/.skill）导入解析。
 *
 * 包结构（Agent Skills 规范）：
 * - 根目录或一级子目录下放 SKILL.md（YAML frontmatter：name/description）
 * - 其余文件作为 skill 子文件（scripts/、references/、assets/ 等）
 *
 * 文本文件进入 [SkillPackage.files] 供模型按需读取；二进制文件（图片/PDF/字体等）
 * 进入 [SkillPackage.binaryFiles]，原样落盘，可被工作区脚本/程序使用。
 */
object SkillPackageImporter {

    data class SkillPackage(
        val skillMd: String,
        val files: Map<String, String>,
        /** 二进制子文件：相对路径 → 原始字节（只落盘，不进入 LLM 上下文） */
        val binaryFiles: Map<String, ByteArray> = emptyMap()
    )

    /**
     * 解析 zip 字节流。找不到 SKILL.md 返回 null。
     * 单个文本文件上限 1MB（二进制 8MB），总包解压上限 32MB（zip bomb 防护）。
     */
    fun parse(zipBytes: ByteArray): SkillPackage? {
        // 第一遍：收集全部条目
        data class Entry(val path: String, val bytes: ByteArray)
        val entries = mutableListOf<Entry>()
        var total = 0L
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val rawPath = entry.name.replace('\\', '/').trimStart('/')
                    val limit = if (rawPath.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS)
                        MAX_BINARY_BYTES else MAX_TEXT_BYTES
                    val bytes = zis.readBytesLimited(limit)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) error("技能包过大（超过 32MB 解压上限）")
                    entries += Entry(rawPath, bytes)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        // 以 SKILL.md 所在目录为技能根：a/b/SKILL.md → 前缀 "a/b/"
        val skillEntry = entries.firstOrNull {
            it.path.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)
        } ?: return null
        val prefix = skillEntry.path.removeSuffix(
            skillEntry.path.substringAfterLast('/')
        ) // 如 "my-skill/" 或 ""
        var skillMd: String? = null
        val files = mutableMapOf<String, String>()
        val binaryFiles = mutableMapOf<String, ByteArray>()
        entries.forEach { e ->
            val relative = if (prefix.isNotEmpty() && e.path.startsWith(prefix)) {
                e.path.removePrefix(prefix)
            } else e.path
            if (relative.isBlank() || relative.endsWith('/')) return@forEach
            val safeRelative = com.cjhsy.linkzone.data.model.LlmSkill.normalizePath(relative)
                ?: return@forEach
            when {
                relative.equals("SKILL.md", ignoreCase = true) -> {
                    if (skillMd == null) skillMd = e.bytes.toString(Charsets.UTF_8)
                }
                isTextCandidate(relative, e.bytes) -> {
                    files[safeRelative] = e.bytes.toString(Charsets.UTF_8)
                }
                else -> binaryFiles[safeRelative] = e.bytes
            }
        }
        val md = skillMd ?: return null
        return SkillPackage(skillMd = md, files = files, binaryFiles = binaryFiles)
    }

    private fun java.util.zip.ZipInputStream.readBytesLimited(max: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) error("单个文件超过 ${max / (1024 * 1024)}MB 上限")
        }
        return out.toByteArray()
    }

    private fun isTextCandidate(path: String, head: ByteArray): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in TEXT_EXTENSIONS) return true
        if (ext in BINARY_EXTENSIONS) return false
        if (head.isEmpty()) return false
        // NUL 字节几乎必然是二进制
        if (head.contains(0x00.toByte())) return false
        // 无扩展名/未知：嗅探前 1KB。合法文本字节 = 常见空白/可打印 ASCII，
        // 或 UTF-8 多字节序列首字节（0x80..0xFD，负值）——中文字节也按文本计
        val sample = head.take(1024)
        val printable = sample.count { b ->
            b in 0x09..0x0D || b in 0x20..0x7E ||
                (b.toInt() and 0xFF) in 0x80..0xFD
        }
        return printable.toDouble() / sample.size > 0.85
    }

    private val TEXT_EXTENSIONS = setOf(
        "md", "txt", "markdown", "py", "js", "ts", "jsx", "tsx", "java", "kt", "kts",
        "json", "yaml", "yml", "xml", "html", "htm", "css", "scss", "sh", "bash",
        "zsh", "sql", "csv", "toml", "ini", "conf", "cfg", "c", "h", "cpp", "hpp",
        "go", "rs", "rb", "php", "swift", "dart", "lua", "r", "m", "gradle", "properties",
        "env", "gitignore", "dockerfile", "vue", "svelte"
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "jar",
        "apk", "dex", "so", "a", "o", "class", "mp3", "mp4", "wav", "ogg", "ttf",
        "otf", "woff", "woff2", "eot", "exe", "bin", "dat"
    )

    private const val MAX_TEXT_BYTES = 1_000_000
    private const val MAX_BINARY_BYTES = 8_000_000
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
}
