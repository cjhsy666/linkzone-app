package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * 含行内 $...$ 公式的段落：GFM 解析器已把 $...$ 识别为 INLINE_MATH 节点
 * （自带保守规则：定界 $ 内侧非空白、收尾 $ 后不接数字所以 `$5`/`$1,200` 不误报、
 * 行内代码 span 内不产生该节点、流式期间未闭合的 `$...` 不产生该节点），
 * 这里把每个 INLINE_MATH 换成 InlineTextContent 透明底位图（匹配段落文字色），
 * 其余子节点逐一复用库注解器（粗体/斜体/行内代码/链接/删除线全部保留）。
 * 无 INLINE_MATH 的段落委托库默认 MarkdownParagraph，行为完全不变。
 */
@Composable
internal fun MdInlineMathParagraph(model: MarkdownComponentModel) {
    val mathNodes = remember(model.content, model.node) {
        model.node.children.mapIndexedNotNull { index, child ->
            if (child.type == GFMElementTypes.INLINE_MATH) {
                val tex = child.getTextInNode(model.content).toString()
                    .removeSurrounding("$", "$")
                if (tex.isNotBlank() && tex.length <= 200 && '\n' !in tex) index to tex else null
            } else {
                null
            }
        }
    }
    if (mathNodes.isEmpty()) {
        MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
        return
    }

    val style = model.typography.paragraph
    val context = LocalContext.current
    LaunchedEffect(mathNodes) {
        mathNodes.forEach { MdInlineMathRenderer.request(context, it.second, style.color) }
    }

    val mathByIndex = mathNodes.toMap()
    val annotatorSettings = annotatorSettings()
    val styled = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        model.node.children.forEachIndexed { index, child ->
            val tex = mathByIndex[index]
            if (tex != null) {
                appendInlineContent("lzmath_$index", tex)
            } else {
                buildMarkdownAnnotatedString(model.content, listOf(child), annotatorSettings)
            }
        }
        pop()
    }

    val density = LocalDensity.current
    val baseInline = LocalMarkdownInlineContent.current.inlineContent
    val mathInline = HashMap<String, InlineTextContent>(mathNodes.size)
    mathNodes.forEach { (index, tex) ->
        val key = inlineMathKey(tex, style.color)
        val result = MdInlineMathRenderer.results[key]
        val width = if (result is MdInlineMathRenderer.Result.Ready) {
            with(density) { result.bitmap.width.toDp() }
        } else {
            (tex.length * 8).dp.coerceIn(16.dp, 320.dp)
        }
        val height = if (result is MdInlineMathRenderer.Result.Ready) {
            with(density) { result.bitmap.height.toDp() }
        } else {
            20.dp
        }
        mathInline["lzmath_$index"] = InlineTextContent(
            Placeholder(
                width = with(density) { width.toSp() },
                height = with(density) { height.toSp() },
                placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
            )
        ) { _ ->
            when (result) {
                is MdInlineMathRenderer.Result.Ready -> Image(
                    bitmap = result.bitmap.asImageBitmap(),
                    contentDescription = tex,
                )
                is MdInlineMathRenderer.Result.Failed -> Text(text = tex, style = style)
                null -> Unit
            }
        }
    }

    CompositionLocalProvider(
        LocalMarkdownInlineContent provides DefaultMarkdownInlineContent(baseInline + mathInline)
    ) {
        MarkdownText(
            content = styled,
            node = model.node,
            style = style,
            sourceContent = model.content,
        )
    }
}
