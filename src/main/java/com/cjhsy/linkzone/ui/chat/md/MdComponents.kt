package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import dev.snipme.highlights.Highlights
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/** $$...$$ 整块公式（允许跨行）：trim 后首尾恰为 $$ 且内部非空 */
private val BLOCK_FORMULA = Regex("^\\$\\$[\\s\\S]+\\$\\$$")

/**
 * 组件覆层：仅替换代码围栏/缩进代码块为自家卡片（语言标签+复制+高亮），
 * checkbox 显式保持 m3 版本（传入自定义 components 时库默认 checkbox 会退回 core 版），
 * mermaid 围栏与 $$ 块级公式走 WebView 小岛（渲染失败自动回退源码卡片），
 * 其余组件（表格/引用/标题/列表/图片/分割线）全部使用库默认 + 设计 token。
 */
fun mdComponents(textColor: Color, highlightsBuilder: Highlights.Builder): MarkdownComponents =
    markdownComponents(
        codeFence = { model ->
            MarkdownCodeFence(
                model.content,
                model.node,
                style = model.typography.code
            ) { code, language, style ->
                if (language?.trim()?.lowercase() == "mermaid") {
                    MdMermaidIsland(code, textColor)
                } else {
                    MdCodeCard(code, language, style, textColor, highlightsBuilder)
                }
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(
                model.content,
                model.node,
                style = model.typography.code
            ) { code, language, style ->
                if (language?.trim()?.lowercase() == "mermaid") {
                    MdMermaidIsland(code, textColor)
                } else {
                    MdCodeCard(code, language, style, textColor, highlightsBuilder)
                }
            }
        },
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
        // 库默认表格全部左对齐，换成支持 `:---:` 对齐语法的自家表格
        table = { MdAlignedTable(it.content, it.node, it.typography.table) },
        // 整段为 $$...$$ → LaTeX 块级公式小岛；含行内 $...$ → 自绘段落；其余走库默认
        paragraph = { model ->
            val raw = model.content
                .substring(model.node.startOffset, model.node.endOffset)
                .trim()
            if (raw.length > 4 && BLOCK_FORMULA.matches(raw)) {
                MdKatexIsland(raw.removePrefix("$$").removeSuffix("$$").trim(), textColor)
            } else {
                MdInlineMathParagraph(model)
            }
        },
    )

/**
 * 含行内 $...$ 公式的段落：GFM 解析器已把 $...$ 识别为 INLINE_MATH 节点
 * （自带保守规则：定界 $ 内侧非空白、收尾 $ 后不接数字所以 `$5`/`$1,200` 不误报、
 * 行内代码 span 内不产生该节点、流式期间未闭合的 `$...` 不产生该节点），
 * 这里把每个 INLINE_MATH 换成 InlineTextContent 白底圆角位图芯片，其余子节点
 * 逐一复用库注解器（粗体/斜体/行内代码/链接/删除线全部保留）。
 * 无 INLINE_MATH 的段落委托库默认 MarkdownParagraph，行为完全不变。
 */
@Composable
private fun MdInlineMathParagraph(model: MarkdownComponentModel) {
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
        mathNodes.forEach { MdInlineMathRenderer.request(context, it.second) }
    }

    val mathByIndex = mathNodes.toMap()
    val annotatorSettings = annotatorSettings()
    // 与库 MarkdownParagraph 同一配方：段落基样 + 逐子节点注解，INLINE_MATH 处换成行内容
    val styled = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        model.node.children.forEachIndexed { index, child ->
            val tex = mathByIndex[index]
            if (tex != null) {
                // alternateText 用原始 tex（不含 $）：无障碍朗读与缺 id 时均显示纯文本
                appendInlineContent("lzmath_$index", tex)
            } else {
                buildMarkdownAnnotatedString(model.content, listOf(child), annotatorSettings)
            }
        }
        pop()
    }

    val density = LocalDensity.current
    val baseInline = LocalMarkdownInlineContent.current.inlineContent
    // 每次重组重建（渲染结果到达后占位尺寸换成真实位图尺寸），配合 LruCache 命中零重渲染
    val mathInline = HashMap<String, InlineTextContent>(mathNodes.size)
    mathNodes.forEach { (index, tex) ->
        val result = MdInlineMathRenderer.results[tex]
        val width = if (result is MdInlineMathRenderer.Result.Ready) {
            with(density) { result.bitmap.width.toDp() }
        } else {
            // 估算占位：渲染完成前后布局跳动尽量小
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
                // 位图底部留 depth 空白，底部对齐文字基线即公式基线对齐
                placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
            )
        ) { _ ->
            when (result) {
                is MdInlineMathRenderer.Result.Ready -> Image(
                    bitmap = result.bitmap.asImageBitmap(),
                    contentDescription = tex,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)),
                )
                // 失败/超时：降级为纯文本（原始 tex，不带 $ 定界符）
                is MdInlineMathRenderer.Result.Failed -> Text(text = tex, style = style)
                // 渲染中：白底小圆角占位芯片
                null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                )
            }
        }
    }

    // 复用库 MarkdownText 渲染管线（ExtendedSpans/行内图片/链接语义保持），
    // 仅经 CompositionLocal 在库行内内容之上追加行内公式芯片
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
