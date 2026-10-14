package com.cjhsy.linkzone.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX 渲染：JLatexMath（Android 移植）→ Bitmap → Compose Image。
 * 解析失败的公式返回 null，调用方降级为源码原文渲染，绝不抛出。
 */

private data class LatexKey(val tex: String, val sizePx: Float, val colorArgb: Int)

private val latexCache = LruCache<LatexKey, Bitmap>(32)

/** 同步渲染公式为 Bitmap（公式通常很少，主线程可接受；缓存兜底重复渲染） */
internal fun renderLatexBitmap(tex: String, textSizePx: Float, colorArgb: Int): Bitmap? {
    val key = LatexKey(tex, textSizePx, colorArgb)
    latexCache.get(key)?.let { return it }
    return runCatching {
        val drawable = JLatexMathDrawable.builder(tex)
            .textSize(textSizePx)
            .color(colorArgb)
            .fitCanvas(true)
            .align(JLatexMathDrawable.ALIGN_CENTER)
            .build()
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bmp))
        }
    }.getOrNull()?.also { latexCache.put(key, it) }
}

/** 块级公式：水平居中 + 横向滚动（宽公式可滑出查看） */
@Composable
internal fun MdLatexBlock(tex: String, textColor: androidx.compose.ui.graphics.Color) {
    val density = LocalDensity.current
    val sizePx = with(density) { 15.sp.toPx() }
    val bitmap = remember(tex, textColor) { renderLatexBitmap(tex, sizePx, textColor.toArgb()) }
    if (bitmap == null) {
        // 解析失败：源码原文兜底（流式半截公式走这里）
        androidx.compose.material3.Text(
            text = tex,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = textColor,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }
    val w = with(density) { bitmap.width.toDp() }
    val h = with(density) { bitmap.height.toDp() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = tex,
            alignment = Alignment.Center,
            modifier = Modifier.width(w).height(h)
        )
    }
}
