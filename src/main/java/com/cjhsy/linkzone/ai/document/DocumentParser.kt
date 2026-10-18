package com.cjhsy.linkzone.ai.document

import com.cjhsy.linkzone.core.error.toUserMessage

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文档解析统一入口（借鉴 RikkaHub DocumentAsPromptTransformer）。
 *
 * 支持类型：
 * - PDF（PdfBox-Android）
 * - DOCX / PPTX / XLSX / EPUB（OOXML/EPUB 本质是 zip+xml，用系统内置 XmlPullParser 解析，零额外体积）
 * - TXT / Markdown / JSON / CSV / XML / 代码等文本类（直接读取）
 * - HTML / HTM（标签剥离为纯文本）
 *
 * 解析结果带进程内缓存（key = 路径+长度+修改时间），同一份文档在多轮对话中只解析一次；
 * 超过 [MAX_TEXT_CHARS] 的大文档截断，保护上下文窗口。
 */
object DocumentParser {

    /** 注入给模型的单文档文本上限（约 2~4 万 token，留足对话余量） */
    private const val MAX_TEXT_CHARS = 60_000

    private val textExtensions = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "xml", "yaml", "yml",
        "log", "ini", "conf", "properties", "kt", "java", "swift", "py", "js",
        "ts", "go", "rs", "c", "cpp", "h", "hpp", "cs", "rb", "php", "sh",
        "bash", "sql", "html", "htm", "css", "scss", "less", "vue", "gradle",
        "toml", "proto", "graphql", "dart", "lua", "r", "m", "mm"
    )

    private val cache = object : LruCache<String, String>(12) {}

    /**
     * 解析文档为纯文本（Markdown 友好格式）。
     * @param file 本地文件
     * @param fileName 原始文件名（用于按扩展名分发与错误提示）
     */
    suspend fun parse(file: File, fileName: String = file.name): String = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val cacheKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        cache.get(cacheKey)?.let { return@withContext it }

        val result = runCatching {
            when {
                !file.exists() || !file.isFile ->
                    "[ERROR, file not found: $fileName]"
                ext == "pdf" -> PdfTextParser.parse(file)
                ext == "docx" -> DocxParser.parse(file)
                ext == "pptx" -> PptxParser.parse(file)
                ext == "xlsx" -> XlsxParser.parse(file)
                ext == "epub" -> EpubParser.parse(file)
                ext == "html" || ext == "htm" -> HtmlTextParser.parse(file)
                ext in textExtensions -> file.readText(Charsets.UTF_8)
                else -> parseUnknown(file)
            }
        }.getOrElse { e ->
            "[ERROR] 读取文件 $fileName 失败：${e.toUserMessage(e.javaClass.simpleName)}"
        }

        val normalized = if (result.isBlank()) "[文档内容为空: $fileName]" else result
        val limited = if (normalized.length > MAX_TEXT_CHARS) {
            normalized.take(MAX_TEXT_CHARS) +
                "\n\n...[文档过长，已截断，仅提供前 ${MAX_TEXT_CHARS} 字符（完整文档共 ${normalized.length} 字符）]"
        } else normalized
        cache.put(cacheKey, limited)
        limited
    }

    /** 未知扩展名：先当文本读，出现大量控制字符则判定为二进制 */
    private fun parseUnknown(file: File): String {
        if (file.length() > 5 * 1024 * 1024) {
            return "[UNSUPPORTED, 不支持解析的二进制文件: ${file.name}]"
        }
        val bytes = file.inputStream().use { it.readBytes() }
        val sample = bytes.take(2048).toByteArray()
        val printable = sample.count { it == '\n'.code.toByte() || it == '\r'.code.toByte() ||
            it == '\t'.code.toByte() || it in 32..126 || it >= 0x80.toByte() }
        return if (sample.isNotEmpty() && printable.toDouble() / sample.size > 0.85) {
            String(bytes, Charsets.UTF_8)
        } else {
            "[UNSUPPORTED, 不支持解析的二进制文件: ${file.name}]"
        }
    }

    /**
     * 包装为 Rikka 风格的附件 prompt 块，插入用户消息文本最前面。
     */
    fun wrapAsPrompt(fileName: String, content: String): String =
        "<UploadFile name=\"$fileName\">\n```\n$content\n```\n</UploadFile>"
}
