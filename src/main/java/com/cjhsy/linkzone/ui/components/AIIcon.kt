package com.cjhsy.linkzone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest

/**
 * 模型/厂商图标（素材来自 RikkaHub assets/icons，54 个 svg/png）。
 * 按名称匹配到图标则显示圆形底 + 图标，否则回退名称首字圆形头像。
 */
@Composable
fun AutoAIIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val path = remember(name) { computeAIIconByName(name) } ?: run {
        TextAvatar(text = name, modifier = modifier, size = size, color = color)
        return
    }
    val context = LocalContext.current
    val model = remember(path, context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/icons/$path")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = color,
    ) {
        AsyncImage(
            model = model,
            contentDescription = name,
            modifier = Modifier.padding(size / 6)
        )
    }
}

@Composable
fun TextAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(1).uppercase(),
            color = LocalContentColor.current,
            fontSize = (size.value * 0.44f).sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
