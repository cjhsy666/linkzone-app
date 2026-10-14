package com.cjhsy.linkzone.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX 渲染：JLatexMath（Android 移植）→ Bitmap → Compose Image。
 * 位图生成禁止在主线程执行（NFR-1）：块级公式经 produceState 切到 Dispatchers.Default，
 * 行内公式的位图在富文本后台构建管线中生成（缓存命中时零成本）。
 * 缓存 key 含颜色（主题维度）：明暗切换按新 palette 重绘，不串色（NFR-7）。
 * 解析失败返回 null，调用方降级为源码原文渲染，绝不抛出。
 */

private data class LatexKey(val tex: String, val sizePx: Float, val colorArgb: Int)

private val latexCache = LruCache<LatexKey, Bitmap>(40)

/** 在后台线程渲染公式为 Bitmap（调用方负责切线程）；重复内容走缓存 */
internal suspend fun renderLatexBitmapAsync(
    tex: String,
    textSizePx: Float,
    colorArgb: Int
): Bitmap? = withContext(Dispatchers.Default) {
    renderLatexBitmap(tex, textSizePx, colorArgb)
}

/** 同步渲染（仅在已处于后台线程时直接调用，如富文本构建管线） */
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

/** 块级公式：后台渲染 + 150ms 淡入；水平居中 + 横向滚动（宽公式可滑出查看） */
@Composable
internal fun MdLatexBlock(tex: String, ctx: MdRenderContext) {
    val density = ctx.density
    val colorArgb = ctx.palette.text.toArgb()
    // key 含主题色：主题切换时自动以后台线程重绘
    val bitmapState = produceState<Bitmap?>(initialValue = null, tex, colorArgb) {
        val sizePx = with(density) { MdType.bodySize.toPx() }
        value = renderLatexBitmapAsync(tex, sizePx, colorArgb)
    }
    val bitmap = bitmapState.value
    if (bitmap == null) {
        // 解析失败 / 渲染中占位：流式半截公式先显源码，成品到位后替换（同版式无跳变）
        Text(
            text = tex,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = ctx.palette.text,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }
    // bitmap 到位（或主题切换重绘）时 0→1 淡入 150ms
    var shown by remember(bitmap) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(bitmap) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "latexFade"
    )
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
            modifier = Modifier
                .width(w)
                .height(h)
                .alpha(alpha)
        )
    }
}
