package com.cjhsy.linkzone.data.ai

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * 图片类本地工具：
 * - ask_user_for_photo：HITL 选图。生成挂起 → 弹系统 Photo Picker → 用户选完把图回传给模型。
 *   Photo Picker 无需任何权限（系统授权临时只读）。
 * - photo_query：相册静默搜索（MediaStore Images），按时间/相册/文件名过滤，
 *   拷贝到缓存后以 file:// 附件回传。需要图片读取权限（Android 13+ READ_MEDIA_IMAGES，
 *   旧版本 READ_EXTERNAL_STORAGE；Android 14 部分授权 READ_MEDIA_VISUAL_USER_SELECTED 也认可）。
 */

/** ask_user_for_photo 的挂起桥：execute 内等待用户在 Photo Picker 选图/跳过 */
fun interface AskPhotoBridge {
    /** 返回 JSON：{"cancelled":true} 或 {"url":"file://...","display_name":"..."} */
    suspend fun ask(reason: String): JsonElement
}

fun buildAskUserPhotoTool(bridge: AskPhotoBridge): Tool = Tool(
    name = "ask_user_for_photo",
    description = """
        Pause the conversation and ask the user to pick one image (photo or screenshot) from their
        device gallery via the system photo picker. Pass a short 'reason' explaining what you need to
        see (e.g. "the error message shown on your screen").
        The picked image is returned as an attached image you can see, plus its file metadata;
        if the user skips, cancelled=true is returned — then adapt (ask them to paste text instead, etc.).
        Use this whenever the user refers to something visual you cannot see yet.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "Short explanation of what image is needed, shown to the user while picking.")
                })
            }
        )
    },
    execute = { args ->
        val reason = args.jsonObject["reason"]?.jsonPrimitive?.contentOrNull ?: "请选择一张图片"
        val result = bridge.ask(reason).jsonObject

        if (result["cancelled"]?.jsonPrimitive?.contentOrNull == "true") {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("cancelled", true)
                put("message", "The user did not pick an image. Ask the user to describe it in text instead.")
            }.toString()))
        }

        val url = result["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("cancelled", true)
                put("message", "Photo picking returned no image.")
            }.toString()))
        }

        listOf(
            UIMessagePart.Image(url),
            UIMessagePart.Text(buildJsonObject {
                put("picked", true)
                put("url", url)
                put("display_name", result["display_name"]?.jsonPrimitive?.contentOrNull ?: "")
                put(
                    "message",
                    "The user picked an image; it is attached above. Analyze it now."
                )
            }.toString())
        )
    }
)

fun buildPhotoQueryTool(context: Context): Tool = Tool(
    name = "photo_query",
    description = """
        Silently search the device's gallery (MediaStore: screenshots, camera photos, saved images —
        no user interaction needed) and return the newest matching images as attachments you can see.
        - folder: screenshots (preset for screenshot folders across ROMs), camera, downloads, or all (default).
        - keyword: matches the file name (case-insensitive substring).
        - begin/end: time range. Accepts an ISO-8601 date 'yyyy-MM-dd', a local date-time 'yyyy-MM-ddTHH:mm:ss',
          an offset date-time, or epoch milliseconds. Omit both to ignore time (then newest first).
          The device timezone is '${java.time.ZoneId.systemDefault()}'.
        - limit: max images to return (default 3, max 8). Each image is copied and attached — avoid large limits.
        Returns metadata JSON plus the images themselves as attachments.
        Requires gallery permission; if not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("folder", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("all"); add("screenshots"); add("camera"); add("downloads")
                    })
                    put("description", "Gallery area to search. Default all.")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file-name keyword (substring, case-insensitive).")
                })
                put("begin", buildJsonObject {
                    put("type", "string")
                    put("description", "Range start (inclusive), ISO date/date-time or epoch ms. Optional.")
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put("description", "Range end (exclusive), same formats as begin. Optional.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max images returned. Default 3, max 8.")
                })
            }
        )
    },
    execute = { args ->
        val appContext = context.applicationContext
        if (!hasGalleryReadPermission(appContext)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Gallery read permission is not granted. Please ask the user to enable the " +
                        "'相册搜索' tool (or gallery permission in system settings)."
                )
            }.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 8) ?: 3
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val zone = java.time.ZoneId.systemDefault()
        val beginMs = params["begin"]?.jsonPrimitive?.contentOrNull?.let { runCatching { parsePhotoTime(it, zone) }.getOrNull() }
        val endMs = params["end"]?.jsonPrimitive?.contentOrNull?.let { runCatching { parsePhotoTime(it, zone) }.getOrNull() }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val selection = StringBuilder("1=1")
        val selectionArgs = mutableListOf<String>()

        // RELATIVE_PATH 为 API 29+ 字段，低版本设备引用会 NoSuchFieldError，需守卫
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        when (params["folder"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "screenshots" -> {
                if (hasRelativePath) {
                    selection.append(
                        " AND (${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
                    )
                    selectionArgs.addAll(listOf("%Screenshot%", "%截图%", "%截屏%", "%Screenshots%"))
                } else {
                    selection.append(
                        " AND (${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)"
                    )
                    selectionArgs.addAll(listOf("%Screenshot%", "%截图%", "%截屏%"))
                }
            }
            "camera" -> {
                if (hasRelativePath) {
                    selection.append(
                        " AND (${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
                    )
                    selectionArgs.addAll(listOf("%Camera%", "%DCIM%"))
                } else {
                    selection.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?")
                    selectionArgs.add("%Camera%")
                }
            }
            "downloads" -> {
                if (hasRelativePath) {
                    selection.append(
                        " AND (${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?" +
                            " OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
                    )
                    selectionArgs.addAll(listOf("%Download%", "%Download%"))
                } else {
                    selection.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?")
                    selectionArgs.add("%Download%")
                }
            }
        }
        if (keyword != null) {
            selection.append(" AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?")
            selectionArgs.add("%$keyword%")
        }
        if (beginMs != null) {
            selection.append(" AND ${MediaStore.Images.Media.DATE_ADDED} >= ?")
            selectionArgs.add((beginMs / 1000).toString())
        }
        if (endMs != null) {
            selection.append(" AND ${MediaStore.Images.Media.DATE_ADDED} < ?")
            selectionArgs.add((endMs / 1000).toString())
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )

        data class PhotoRow(
            val id: Long,
            val name: String,
            val bucket: String,
            val addedSec: Long,
            val size: Long,
            val mime: String
        )

        val rows = ArrayList<PhotoRow>()
        runCatching {
            appContext.contentResolver.query(
                collection, projection, selection.toString(),
                selectionArgs.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        PhotoRow(
                            id = cursor.getLong(0),
                            name = cursor.getString(1).orEmpty(),
                            bucket = cursor.getString(2).orEmpty(),
                            addedSec = cursor.getLong(3),
                            size = cursor.getLong(4),
                            mime = cursor.getString(5).orEmpty()
                        )
                    )
                }
            }
        }.onFailure {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "QUERY_FAILED")
                put("message", "Gallery query failed: ${it.message ?: "unknown"}")
            }.toString()))
        }

        if (rows.isEmpty()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("count", 0)
                put("message", "No matching images found. Tell the user nothing was found, or refine the query.")
            }.toString()))
        }

        // 拷贝到缓存目录，回传 file:// 附件（与聊天附件链路一致，vision/OCR 均可直接用）
        val cacheDir = File(appContext.cacheDir, "photo_query").apply { mkdirs() }
        val photoJsons = ArrayList<kotlinx.serialization.json.JsonObject>()
        val parts = ArrayList<UIMessagePart>(rows.size + 1)
        var copied = 0
        rows.forEach { row ->
            val ext = row.mime.substringAfter("image/", "").ifBlank { "jpg" }
            val target = File(cacheDir, "photo_${row.id}_$copied.$ext")
            val copiedFile = runCatching {
                val source = ContentUris.withAppendedId(collection, row.id)
                appContext.contentResolver.openInputStream(source)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target
            }.getOrNull()

            if (copiedFile != null && copiedFile.length() > 0) {
                photoJsons.add(buildJsonObject {
                    put("display_name", row.name)
                    put("folder", row.bucket)
                    put("taken_at", if (row.addedSec > 0) {
                        Instant.ofEpochSecond(row.addedSec).atZone(zone).toString()
                    } else "")
                    put("size_bytes", row.size)
                    put("file", "file://${copiedFile.absolutePath}")
                })
                parts.add(UIMessagePart.Image("file://${copiedFile.absolutePath}"))
                copied++
            }
        }

        if (copied == 0) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "COPY_FAILED")
                put("message", "Found ${rows.size} matches but failed to copy any image.")
            }.toString()))
        }

        parts.add(
            0,
            UIMessagePart.Text(buildJsonObject {
                put("count", copied)
                put("photos", buildJsonArray { photoJsons.forEach { add(it) } })
                put(
                    "message",
                    "The $copied matching image(s) are attached as parts after this one, newest first. Analyze them now."
                )
            }.toString())
        )
        parts
    }
)

/** 相册读取权限：READ_MEDIA_IMAGES(33+) / READ_EXTERNAL_STORAGE(<33) / Android 14 部分授权 任一即可 */
private fun hasGalleryReadPermission(context: Context): Boolean {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/** 兼容 epoch 毫秒 / ISO 日期 / 本地时间 / 带偏移时间的宽松解析 */
private fun parsePhotoTime(raw: String, zone: java.time.ZoneId): Long {
    val text = raw.trim()
    text.toLongOrNull()?.let { return it }
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching { return Instant.parse(text).toEpochMilli() }
    runCatching { return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli() }
    runCatching { return ZonedDateTime.parse(text).toInstant().toEpochMilli() }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
