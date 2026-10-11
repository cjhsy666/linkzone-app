package com.cjhsy.linkzone.ai.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * XLSX（Excel 2007+）解析：OOXML 是 zip+xml，零依赖提取为 Markdown 表格。
 *
 * 读取链路：xl/sharedStrings.xml（共享字符串）→ xl/workbook.xml（工作表清单）
 * → xl/_rels/workbook.xml.rels（关系映射）→ xl/worksheets/sheetN.xml（单元格数据）。
 */
object XlsxParser {

    private data class SheetMeta(val name: String, val target: String)

    fun parse(file: File): String = try {
        ZipFile(file).use { zip ->
            val sharedStrings = readSharedStrings(zip)
            val sheets = readSheets(zip)
            if (sheets.isEmpty()) return "No worksheets found in XLSX file"

            val result = StringBuilder()
            sheets.forEachIndexed { index, sheet ->
                if (index > 0) result.append("\n\n")
                result.append("## ${sheet.name}\n\n")
                val path = when {
                    sheet.target.startsWith("/") -> sheet.target.trimStart('/')
                    sheet.target.startsWith("xl/") -> sheet.target
                    else -> "xl/${sheet.target}"
                }
                val entry = zip.getEntry(path)
                if (entry == null) {
                    result.append("[worksheet file missing: ${sheet.target}]")
                    return@forEachIndexed
                }
                zip.getInputStream(entry).use { stream ->
                    result.append(parseSheet(stream, sharedStrings))
                }
            }
            result.toString().trim()
        }
    } catch (e: Exception) {
        "Error parsing XLSX file: ${e.message}"
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val list = mutableListOf<String>()
        zip.getInputStream(entry).use { stream ->
            val parser = newParser(stream)
            val current = StringBuilder()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> if (parser.name == "si") current.setLength(0)
                    XmlPullParser.TEXT -> current.append(parser.text ?: "")
                    XmlPullParser.END_TAG ->
                        if (parser.name == "si") list.add(current.toString().trim())
                }
                parser.next()
            }
        }
        return list
    }

    private fun readSheets(zip: ZipFile): List<SheetMeta> {
        val rels = mutableMapOf<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { relsEntry ->
            zip.getInputStream(relsEntry).use { stream ->
                val parser = newParser(stream)
                var id: String? = null
                var target: String? = null
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG ->
                            if (parser.name == "Relationship") {
                                id = parser.getAttributeValue(null, "Id")
                                target = parser.getAttributeValue(null, "Target")
                            }
                        XmlPullParser.END_TAG ->
                            if (parser.name == "Relationship" && id != null && target != null) {
                                rels[id] = target
                                id = null; target = null
                            }
                    }
                    parser.next()
                }
            }
        }

        val sheets = mutableListOf<SheetMeta>()
        val wbEntry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
        zip.getInputStream(wbEntry).use { stream ->
            val parser = newParser(stream)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name") ?: "Sheet"
                    val rid = parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"
                    )
                    val target = rid?.let { rels[it] }
                    if (target != null) sheets.add(SheetMeta(name, target))
                }
                parser.next()
            }
        }
        if (sheets.isEmpty()) {
            var i = 1
            while (zip.getEntry("xl/worksheets/sheet$i.xml") != null) {
                sheets.add(SheetMeta("Sheet$i", "worksheets/sheet$i.xml"))
                i++
            }
        }
        return sheets
    }

    private fun parseSheet(input: InputStream, sharedStrings: List<String>): String {
        val parser = newParser(input)
        val rows = mutableListOf<List<String>>()
        var currentRow: MutableList<String>? = null
        var currentCol = 0
        var cellType = ""
        val valueBuf = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r") ?: ""
                        currentCol = columnIndex(ref)
                        cellType = parser.getAttributeValue(null, "t") ?: "n"
                        valueBuf.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> valueBuf.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val row = currentRow
                        if (row != null) {
                            while (row.size < currentCol) row.add("")
                            val raw = valueBuf.toString().trim()
                            val value = if (raw.isNotEmpty() && cellType == "s") {
                                raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: raw
                            } else raw
                            if (row.size == currentCol) row.add(value) else row[currentCol] = value
                        }
                    }
                    "row" -> {
                        currentRow?.let { if (it.any { cell -> cell.isNotBlank() }) rows.add(it) }
                        currentRow = null
                    }
                }
            }
            parser.next()
        }

        if (rows.isEmpty()) return "[empty worksheet]"
        val maxCols = rows.maxOf { it.size }.coerceAtLeast(1)
        val sb = StringBuilder()
        rows.forEachIndexed { rowIdx, row ->
            sb.append("| ")
            for (c in 0 until maxCols) {
                sb.append(row.getOrElse(c) { "" }.replace("|", "\\|").replace("\n", " "))
                sb.append(" | ")
            }
            sb.append("\n")
            if (rowIdx == 0) {
                sb.append("| ")
                repeat(maxCols) { sb.append("--- | ") }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun columnIndex(cellRef: String): Int {
        var idx = 0
        for (ch in cellRef) {
            if (ch.isLetter()) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1)
            else break
        }
        return (idx - 1).coerceAtLeast(0)
    }

    private fun newParser(stream: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply { setInput(stream, "UTF-8") }
    }
}
