package com.cjhsy.linkzone.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 发送功能面板（豆包式精简）：拍照 / 相册 / 录像 / 位置 / 文件。
 * 语音通过输入栏的「按住说话」发送。
 */
@Composable
fun AttachPanel(
    enabled: Boolean,
    onSendMedia: (File, String?) -> Unit,
    onSendLocation: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 拍照/录像结果文件（点击时创建，系统相机写入）
    var pendingCapture = remember { arrayOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCapture[0]
        pendingCapture[0] = null
        if (success && file != null && file.exists() && file.length() > 0) {
            onSendMedia(file, "image")
        } else {
            file?.delete()
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        val file = pendingCapture[0]
        pendingCapture[0] = null
        if (success && file != null && file.exists() && file.length() > 0) {
            onSendMedia(file, "video")
        } else {
            file?.delete()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        // Photo Picker 多选：图片+视频；无 Google 服务的设备由 androidx 回退到
        // ACTION_OPEN_DOCUMENT 系统选择器（回退路径不支持多选），国产 ROM 同样可用
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        // 返回 content:// 列表，上传需要真实文件：逐个复制到缓存后按 mime 判型
        uris.forEach { uri ->
            val cached = runCatching { uri.copyToCache(context) }.getOrNull()
            if (cached != null) onSendMedia(cached, null)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        // OpenMultipleDocuments：系统「文件」应用完整页面（可浏览全部目录、最近文件、
        // 各云盘），长按即可多选，与 RikkaHub 上传文件一致的体验
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            val cached = runCatching { uri.copyToCache(context) }.getOrNull()
            if (cached != null) onSendMedia(cached, "file")
        }
    }

    // 位置：申请权限 → 读最近已知定位 → 直接发送
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            sendCurrentLocation(context, onSendLocation)
        } else {
            Toast.makeText(context, "需要定位权限才能发送位置", Toast.LENGTH_SHORT).show()
        }
    }

    val actions = listOf(
        AttachAction(
            label = "拍照",
            icon = Icons.Filled.CameraAlt,
            tint = MaterialTheme.colorScheme.primary
        ) {
            // 委托系统相机应用，无需声明 CAMERA 权限
            val photo = File(
                context.cacheDir,
                "photo_${System.currentTimeMillis()}.jpg"
            )
            pendingCapture[0] = photo
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", photo
            )
            cameraLauncher.launch(uri)
        },
        AttachAction(
            label = "相册",
            icon = Icons.Filled.Photo,
            tint = MaterialTheme.colorScheme.tertiary
        ) {
            // 图片和视频都从相册选，类型由上传结果按扩展名自动判断
            galleryLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                )
            )
        },
        AttachAction(
            label = "录像",
            icon = Icons.Filled.Videocam,
            tint = MaterialTheme.colorScheme.error
        ) {
            val video = File(
                context.cacheDir,
                "video_${System.currentTimeMillis()}.mp4"
            )
            pendingCapture[0] = video
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", video
            )
            videoLauncher.launch(uri)
        },
        AttachAction(
            label = "位置",
            icon = Icons.Filled.LocationOn,
            tint = MaterialTheme.colorScheme.secondary
        ) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                sendCurrentLocation(context, onSendLocation)
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
        },
        AttachAction(
            label = "文件",
            icon = Icons.Filled.InsertDriveFile,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            fileLauncher.launch(arrayOf("*/*"))
        }
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            AttachGridItem(
                action = action,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { action.onClick() }
        }
    }
}

/** 读取最近已知定位并发送；取不到时提示用户先开定位 */
@SuppressLint("MissingPermission")
private fun sendCurrentLocation(context: Context, onSend: (Double, Double) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val best = lm.getProviders(true).asSequence()
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (best != null) {
        onSend(best.latitude, best.longitude)
    } else {
        Toast.makeText(context, "暂时获取不到位置，请确认已开启定位", Toast.LENGTH_SHORT).show()
    }
}

private data class AttachAction(
    val label: String,
    val icon: ImageVector,
    val tint: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit
)

@Composable
private fun AttachGridItem(
    action: AttachAction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 暗色下用功能色 18% 透明底替代浅色粉彩底，避免夜间刺眼色块
    val dark = isSystemInDarkTheme()
    val container = if (dark) action.tint.copy(alpha = 0.18f)
    else action.tint.copy(alpha = 0.12f)
    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .background(container, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                action.icon, contentDescription = action.label,
                tint = action.tint, modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * 把 content:// 选择结果复制到应用缓存目录，返回可上传的真实文件。
 * 文件名优先取 OpenableColumns.DISPLAY_NAME，取不到时按 mime 补扩展名。
 */
private fun Uri.copyToCache(context: Context): File {
    val mime = context.contentResolver.getType(this)

    // 优先保留系统返回的原始文件名（文档解析依赖扩展名分发：pdf/docx/pptx/xlsx/epub…）
    var displayName: String? = null
    runCatching {
        context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) displayName = cursor.getString(0)
            }
    }
    val rawName = displayName?.substringAfterLast('/')?.trim()?.take(120)

    // mime → 扩展名兜底（原名无扩展名时使用）
    val mimeExt = when {
        mime?.startsWith("video/") == true -> ".mp4"
        mime == "image/png" -> ".png"
        mime == "image/gif" -> ".gif"
        mime == "image/webp" -> ".webp"
        mime?.startsWith("image/") == true -> ".jpg"
        mime?.startsWith("audio/") == true -> ".m4a"
        mime == "application/pdf" -> ".pdf"
        mime == "application/epub+zip" -> ".epub"
        mime?.contains("wordprocessingml") == true -> ".docx"
        mime?.contains("spreadsheetml") == true -> ".xlsx"
        mime?.contains("presentationml") == true -> ".pptx"
        mime?.contains("zip") == true -> ".zip"
        mime?.contains("text/") == true -> ".txt"
        else -> ""
    }

    val safeName = rawName
        ?.replace(Regex("[\\\\:*?\"<>|]"), "_")
        ?.takeIf { it.isNotBlank() }
    val finalName = when {
        safeName != null && safeName.contains('.') -> safeName
        safeName != null -> safeName + mimeExt
        else -> {
            val prefix = when {
                mime?.startsWith("video/") == true -> "video"
                mime?.startsWith("image/") == true -> "img"
                else -> "file"
            }
            "${prefix}_${System.currentTimeMillis()}$mimeExt"
        }
    }
    val outFile = File(context.cacheDir, finalName)
    // 同名文件（同一文档重复选择）加序号避免覆盖
    var unique = outFile
    var n = 1
    while (unique.exists()) {
        val dot = finalName.lastIndexOf('.')
        unique = if (dot > 0) {
            File(context.cacheDir, finalName.substring(0, dot) + "_$n" + finalName.substring(dot))
        } else {
            File(context.cacheDir, "${finalName}_$n")
        }
        n++
    }
    context.contentResolver.openInputStream(this).use { input ->
        requireNotNull(input) { "无法读取所选文件" }
        unique.outputStream().use { output -> input.copyTo(output) }
    }
    return unique
}
