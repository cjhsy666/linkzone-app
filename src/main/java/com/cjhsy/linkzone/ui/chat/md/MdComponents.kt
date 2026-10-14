package com.cjhsy.linkzone.ui.chat.md

import androidx.compose.ui.graphics.Color
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import dev.snipme.highlights.Highlights

/**
 * 组件覆层：仅替换代码围栏/缩进代码块为自家卡片（语言标签+复制+高亮），
 * checkbox 显式保持 m3 版本（传入自定义 components 时库默认 checkbox 会退回 core 版），
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
                MdCodeCard(code, language, style, textColor, highlightsBuilder)
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(
                model.content,
                model.node,
                style = model.typography.code
            ) { code, language, style ->
                MdCodeCard(code, language, style, textColor, highlightsBuilder)
            }
        },
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
    )
