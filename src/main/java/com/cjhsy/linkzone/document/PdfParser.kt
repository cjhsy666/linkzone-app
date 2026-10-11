package com.cjhsy.linkzone.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * PDF 解析（PdfRenderer 重写版，替代 RikkaHub 依赖的 MuPDF）。
 *
 * Android 平台没有系统级 PDF 文本提取 API，因此逐页把 PDF 渲染成位图
 * （宽约 1080px、白底），转 PNG 后编码为 base64 data URL，
 * 以 Markdown 图片形式返回，交给多模态模型读取页面内容。
 *
 * 与原版保持一致的 API：`PdfParser.parserPdf(file: File): String`。
 */
object PdfParser {
    // 渲染目标宽度（像素）；页面高度按原始宽高比缩放
    private const val TARGET_WIDTH = 1080

    // 单次解析的页数上限，防止超大 PDF 撑爆内存
    private const val MAX_PAGES = 30

    fun parserPdf(file: File): String {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return "No pages found in PDF file"

                    val result = StringBuilder()
                    val pageCount = minOf(renderer.pageCount, MAX_PAGES)

                    for (i in 0 until pageCount) {
                        renderer.openPage(i).use { page ->
                            val bitmap = renderPage(page)
                            val dataUrl = bitmapToPngDataUrl(bitmap)
                            bitmap.recycle()

                            result.append("---")
                            result.append("Page ${i + 1}:\n")
                            result.append("![PDF Page ${i + 1}]($dataUrl)")
                            result.appendLine()
                        }
                    }

                    if (renderer.pageCount > MAX_PAGES) {
                        result.appendLine()
                        result.appendLine("(PDF has ${renderer.pageCount} pages, only first $MAX_PAGES pages were rendered)")
                    }

                    result.toString().trim().ifEmpty { "No readable content found in PDF file" }
                }
            }
        } catch (e: Exception) {
            "Error parsing PDF file: ${e.message}"
        }
    }

    // 白底渲染整页位图，宽固定 TARGET_WIDTH，高按宽高比换算
    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = TARGET_WIDTH.toFloat() / page.width
        val width = TARGET_WIDTH
        val height = (page.height * scale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun bitmapToPngDataUrl(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val base64 = Base64.getEncoder().encodeToString(output.toByteArray())
        return "data:image/png;base64,$base64"
    }
}
