package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.cjhsy.linkzone.ui.chat.ZoomableImageDialog
import com.cjhsy.linkzone.ui.chat.resolveMedia

/** markdown 渲染内的图片 baseUrl（相对路径补全用），由 MarkdownContent 外层提供 */
val LocalMdBaseUrl = staticCompositionLocalOf { "" }

/**
 * markdown 内联图片：Coil 加载 + 点击打开全屏可缩放预览（复用聊天图片的 ZoomableImageDialog）。
 */
@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val baseUrl = LocalMdBaseUrl.current
    val url = remember(model, baseUrl) { resolveMedia(model ?: "", baseUrl) }
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.clickable { showImageViewer = true },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
    )
    if (showImageViewer) {
        ZoomableImageDialog(rawUrl = url, baseUrl = "", onDismiss = { showImageViewer = false })
    }
}
