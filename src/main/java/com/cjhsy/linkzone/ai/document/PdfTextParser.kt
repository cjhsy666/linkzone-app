package com.cjhsy.linkzone.ai.document

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF 文本提取（PdfBox-Android）。
 *
 * 使用前必须在 Application 中调用 [init] 初始化资源加载器。
 * 扫描件（纯图片页）提取不到文本，需要视觉/OCR 能力的模型另行支持。
 */
object PdfTextParser {

    @Volatile
    private var initialized = false

    /** 在 Application.onCreate 调用一次 */
    fun init(context: android.content.Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }

    fun parse(file: File): String {
        check(initialized) { "PdfTextParser not initialized; call PdfTextParser.init(context) first" }
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            val pages = doc.numberOfPages
            if (pages <= 1) return stripper.getText(doc).orEmpty().trim()
            // 分页标注，与 Rikka PdfParser 输出风格一致，便于模型定位
            val sb = StringBuilder()
            for (i in 1..pages) {
                stripper.startPage = i
                stripper.endPage = i
                val pageText = stripper.getText(doc)?.trim().orEmpty()
                if (pageText.isNotBlank()) {
                    sb.append("--- Page ").append(i).append(":\n")
                    sb.append(pageText).append('\n')
                }
            }
            return sb.toString().trim().ifEmpty { "[PDF 内容为空或为扫描图片，无法提取文本: ${file.name}]" }
        }
    }
}
