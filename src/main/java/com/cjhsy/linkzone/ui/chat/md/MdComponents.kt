package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.ui.graphics.Color
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import dev.snipme.highlights.Highlights

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
        // 整段为 $$...$$ → LaTeX 块级公式小岛；其余段落走库默认
        paragraph = { model ->
            val raw = model.content
                .substring(model.node.startOffset, model.node.endOffset)
                .trim()
            if (raw.length > 4 && BLOCK_FORMULA.matches(raw)) {
                MdKatexIsland(raw.removePrefix("$$").removeSuffix("$$").trim(), textColor)
            } else {
                MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
            }
        },
    )
