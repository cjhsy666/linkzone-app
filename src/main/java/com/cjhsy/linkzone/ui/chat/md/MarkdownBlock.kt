package com.cjhsy.linkzone.ui.chat.md

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import com.cjhsy.linkzone.ui.components.DataTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown AST 渲染 —— 移植自 RikkaHub (richtext/Markdown.kt)。
 * 关键特性：Dispatchers.Default 后台解析 AST + 顶层节点分块渲染，
 * 流式追加时只有最后一个块重组，长文滚动不卡。
 *
 * 本地化适配：fontSizeRatio=1f、LaTeX 渲染常开、等宽字体用 FontFamily.Monospace、
 * 代码块走现有 MdCodeCard（mermaid 走 MdMermaidIsland）、图片走 ZoomableAsyncImage。
 */

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

private val parser by lazy {
    MarkdownParser(flavour)
}

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

@Composable
internal fun TextUnit.toDp(): Dp = with(LocalDensity.current) {
    if (this@toDp.type == TextUnitType.Sp) this@toDp.toDp() else 0.dp
}

// 预处理markdown内容
private fun preProcess(content: String): String {
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            val formula = matchResult.groupValues[1]
                .trim()
                .replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
            "$$" + formula + "$$"
        }
    }

    return result
}

private data class MarkdownParseResult(
    val preprocessed: String,
    val astTree: ASTNode,
    val hasHtml: Boolean,
)

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

private fun parseMarkdown(content: String): MarkdownParseResult {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    return MarkdownParseResult(preprocessed, astTree, astTree.containsHtml())
}

@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {}
) {
    var (data, setData) = remember { mutableStateOf(parseMarkdown(content)) }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .mapLatest { parseMarkdown(it) }
            .catch { exception -> exception.printStackTrace() }
            .flowOn(Dispatchers.Default)
            .collect { setData(it) }
    }

    if (data.hasHtml) {
        MarkdownNew(
            content = content,
            modifier = modifier,
            style = style,
            onClickCitation = onClickCitation,
        )
    } else {
        ProvideTextStyle(style) {
            Column(
                modifier = modifier.padding(horizontal = 4.dp)
            ) {
                data.astTree.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = data.preprocessed, onClickCitation = onClickCitation
                    )
                }
            }
        }
    }
}

object HeaderStyle {
    private const val LINE_HEIGHT_RATIO = 1.25f

    fun fromLevel(level: Int, fontSizeRatio: Float): TextStyle {
        val fontSize = when (level) {
            1 -> 24.sp
            2 -> 22.sp
            3 -> 20.sp
            4 -> 18.sp
            5 -> 16.sp
            else -> 14.sp
        } * fontSizeRatio

        return TextStyle(
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT_RATIO,
        )
    }

    fun verticalPadding(level: Int) = when (level) {
        1 -> 16.dp
        2 -> 14.dp
        3 -> 12.dp
        4 -> 10.dp
        5 -> 8.dp
        else -> 6.dp
    }

    fun fromMarkdownType(type: IElementType, fontSizeRatio: Float): TextStyle = fromLevel(
        level = when (type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            MarkdownElementTypes.ATX_6 -> 6
            else -> 6
        },
        fontSizeRatio = fontSizeRatio,
    )

    fun verticalPadding(type: IElementType) = verticalPadding(
        level = when (type) {
            MarkdownElementTypes.ATX_1 -> 1
            MarkdownElementTypes.ATX_2 -> 2
            MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4
            MarkdownElementTypes.ATX_5 -> 5
            else -> 6
        }
    )
}

@Composable
private fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }

        // 段落
        MarkdownElementTypes.PARAGRAPH -> {
            Paragraph(
                node = node, content = content, modifier = modifier, onClickCitation = onClickCitation
            )
        }

        // 标题
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val style = HeaderStyle.fromMarkdownType(
                type = node.type,
                fontSizeRatio = 1f,
            )
            val headingPadding = HeaderStyle.verticalPadding(node.type)
            ProvideTextStyle(value = LocalTextStyle.current.merge(style)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.fastForEach { node ->
                        if (node.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = node,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = modifier.padding(vertical = headingPadding),
                                trim = true,
                            )
                        }
                    }
                }
            }
        }

        // 列表
        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                level = listLevel
            )
        }

        // Checkbox
        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).trim() == "[x]"
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 引用块
        MarkdownElementTypes.BLOCK_QUOTE -> {
            ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                color = bgColor, size = size
                            )
                            drawRect(
                                color = borderColor, size = Size(10f, size.height)
                            )
                        }
                        .padding(8.dp)) {
                    node.children.fastForEach { child ->
                        MarkdownNode(
                            node = child, content = content, onClickCitation = onClickCitation
                        )
                    }
                }
            }
        }

        // 链接
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.findChildOfTypeRecursive(GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.TEXT)?.getTextInNode(content)
                ?: ""
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val context = LocalContext.current
            Text(
                text = linkText,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, linkDest.toUri())
                    context.startActivity(intent)
                })
        }

        // 加粗和斜体
        MarkdownElementTypes.EMPH -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Bold)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        // GFM 特殊元素
        GFMElementTypes.STRIKETHROUGH -> {
            Text(
                text = node.getTextInNode(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        GFMElementTypes.TABLE -> {
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            Column(
                modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZoomableAsyncImage(
                    model = imageUrl,
                    contentDescription = altText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            }
        }

        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            MathInline(
                formula, modifier = modifier.padding(horizontal = 1.dp),
                fontSize = LocalTextStyle.current.fontSize
            )
        }

        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content)
            MathBlock(
                formula, modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                fontSize = LocalTextStyle.current.fontSize
            )
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
                modifier = modifier
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(content)
            Text(
                text = code,
                modifier = modifier,
            )
        }

        // 代码块
        MarkdownElementTypes.CODE_FENCE -> {
            // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
            // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
            val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            if (contentStartIndex == -1) return
            val eolElement =
                node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return
            val codeContentStartOffset = eolElement.endOffset
            val codeContentEndOffset =
                node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return
            val code = content.substring(
                codeContentStartOffset, codeContentEndOffset
            ).trimIndent()

            val language =
                node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext"
            val hasEnd = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END) != null

            HighlightCodeBlock(
                code = code,
                language = language,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(),
                completeCodeBlock = hasEnd
            )
        }

        MarkdownTokenTypes.TEXT -> {
            val text = node.getTextInNode(content)
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val text = node.getTextInNode(content)
            SimpleHtmlBlock(
                html = text, modifier = modifier
            )
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }
    }
}

@Composable
private fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
) {
    val bulletStyle = when (level % 3) {
        0 -> "• "
        1 -> "◦ "
        else -> "▪ "
    }

    Column(
        modifier = modifier.padding(start = (level * 8).dp)
    ) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level
                )
            }
        }
    }
}

@Composable
private fun OrderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
) {
    Column(modifier.padding(start = (level * 8).dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: ASTNode, content: String, bulletText: String, onClickCitation: (String) -> Unit = {}, level: Int
) {
    Column {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            Row {
                Text(
                    text = bulletText,
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    directContent.fastForEach { contentChild ->
                        MarkdownNode(
                            node = contentChild,
                            content = content,
                            onClickCitation = onClickCitation,
                            listLevel = level,
                        )
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            MarkdownNode(
                node = nestedList, content = content, onClickCitation = onClickCitation, listLevel = level + 1 // 增加层级
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}

@Composable
private fun Paragraph(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
) {
    if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
        FlowRow(modifier = modifier) {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, onClickCitation = onClickCitation
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val inlineContents = remember {
        mutableStateMapOf<String, InlineTextContent>()
    }
    val hasInlineMath = remember(node) {
        node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
    }

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val latexColorArgb = LocalContentColor.current.toArgb()
    FlowRow(
        modifier = modifier.then(
            if (node.nextSibling() != null) Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
            else Modifier
        )
    ) {
        val annotatedString = remember(content, latexColorArgb) {
            buildAnnotatedString {
                node.children.fastForEach { child ->
                    appendMarkdownNodeContent(
                        node = child,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClickCitation = onClickCitation,
                        style = textStyle,
                        density = density,
                        trim = trim,
                        latexColorArgb = latexColorArgb,
                    )
                }
            }
        }
        Text(
            text = annotatedString,
            modifier = Modifier,
            inlineContent = inlineContents,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current.copy(
                lineHeight = if (hasInlineMath) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
            )
        )
    }
}

@Composable
private fun TableNode(node: ASTNode, content: String, modifier: Modifier = Modifier) {
    // 提取表格的标题行和数据行
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }

    // 计算列数（从标题行获取）
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    // 检查是否有足够的列来显示表格
    if (columnCount == 0) return

    // 提取表头单元格文本
    val headerCells =
        headerNode?.children?.filter { it.type == GFMTokenTypes.CELL }?.map { it.getTextInNode(content).trim() }
            ?: emptyList()

    // 提取所有行的数据
    val rows = rowNodes.map { rowNode ->
        rowNode.children.filter { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(content).trim() }
    }

    // 创建表头composable列表
    val headers = List(columnCount) { columnIndex ->
        @Composable {
            MarkdownBlock(
                content = if (columnIndex < headerCells.size) headerCells[columnIndex] else "",
            )
        }
    }

    // 创建行数据composable列表
    val rowComposables = rows.map { rowData ->
        List(columnCount) { columnIndex ->
            @Composable {
                MarkdownBlock(
                    content = if (columnIndex < rowData.size) rowData[columnIndex] else "",
                )
            }
        }
    }

    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 表格原始markdown文本（用于复制）和CSV内容（用于下载）
    val tableMarkdown = remember(node, content) { node.getTextInNode(content).trim() }
    val tableCsv = remember(headerCells, rows) { buildTableCsv(headerCells, rows) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(tableCsv.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 渲染表格卡片（工具栏 + 表格）
    Column(
        modifier = modifier
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "表格",
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            scope.launch {
                                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableMarkdown)))
                            }
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )

                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = "Download",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            createDocumentLauncher.launch("table_${System.currentTimeMillis()}.csv")
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
        DataTable(
            headers = headers,
            rows = rowComposables,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            outerBorder = null,
            shape = RectangleShape,
            scrollKey = tableMarkdown,
        )
    }
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(headerCells: List<String>, rows: List<List<String>>): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(headerCells.joinToString(",") { escape(it) })
        rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it) })
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    latexColorArgb: Int = 0,
    onClickCitation: (String) -> Unit = {},
) {
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            withLink(LinkAnnotation.Url(link)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        node is LeafASTNode -> {
            val text = node.getTextInNode(content).let {
                if (trim) {
                    it.trim()
                } else {
                    it
                }.replace(BREAK_LINE_REGEX, "\n")
            }
            append(
                text = text,
            )
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation
                    )
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        latexColorArgb = latexColorArgb,
                        onClickCitation = onClickCitation
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
            withLink(LinkAnnotation.Url(linkDest)) {
                withStyle(
                    SpanStyle(
                        color = colorScheme.primary, textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(linkText)
                }
            }
        }

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                withLink(LinkAnnotation.Url(link.getTextInNode(content))) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.getTextInNode(content))
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
                    fontSize = 0.9.em,
                    color = colorScheme.primary,
                )
            ) {
                append(' ')
                append(code)
                append(' ')
            }
        }

        node.type == GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            val fontSizePx = with(density) { style.fontSize.toPx() }
            // 将过长的行内公式按顶层运算符水平拆分为多段，每段最大宽度限制为字号的两倍，
            // 使其能在文本流中换行，避免单体公式超出可用宽度被挤出屏幕
            val drawables = splitLatex(
                latex = formula,
                maxWidthPx = fontSizePx * 2,
                fontSize = fontSizePx,
                color = latexColorArgb,
            )
            if (drawables.isEmpty()) {
                // 拆分失败时回退为单体内联渲染
                appendInlineContent(formula, "[Latex]")
                val (width, height) = with(density) {
                    assumeLatexSize(
                        latex = formula, fontSize = fontSizePx
                    ).let {
                        it.width().toSp() to it.height().toSp()
                    }
                }
                inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                    placeholder = Placeholder(
                        width = width,
                        height = height,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    ), children = {
                        MathInline(
                            latex = formula, modifier = Modifier
                        )
                    })
                )
            } else {
                drawables.forEachIndexed { index, drawable ->
                    // 段间插入零宽空格，提供换行点
                    if (index > 0) append('​')
                    val key = "latex:${formula.hashCode()}:$index"
                    appendInlineContent(key, "[Latex]")
                    val (width, height) = with(density) {
                        drawable.bounds.width().toSp() to drawable.bounds.height().toSp()
                    }
                    inlineContents.putIfAbsent(
                        key, InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = height,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                            ), children = {
                                LatexDrawable(drawable = drawable)
                            })
                    )
                }
            }
        }

        // 其他类型继续递归处理
        else -> {
            node.children.fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    latexColorArgb = latexColorArgb,
                    onClickCitation = onClickCitation
                )
            }
        }
    }
}

@Composable
internal fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
) {
    val textColor = LocalContentColor.current
    when (language.trim().lowercase()) {
        "mermaid" -> {
            Box(modifier = modifier) {
                MdMermaidIsland(code, textColor)
            }
            return
        }
        "svg" -> {
            Box(modifier = modifier) {
                MdSvgIsland(code, textColor)
            }
            return
        }
    }
    val highlightsBuilder = rememberMdHighlightsBuilder(dark = textColor.luminance() > 0.5f)
    Box(modifier = modifier) {
        MdCodeCard(
            code = code,
            language = language,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            textColor = textColor,
            highlightsBuilder = highlightsBuilder,
        )
    }
}

internal fun ASTNode.getTextInNode(text: String): String {
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.nextSibling(): ASTNode? {
    val brother = this.parent?.children ?: return null
    for (i in brother.indices) {
        if (brother[i] == this) {
            if (i + 1 < brother.size) {
                return brother[i + 1]
            }
        }
    }
    return null
}

internal fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun List<ASTNode>.trim(type: IElementType, size: Int): List<ASTNode> {
    if (this.isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    // 从头裁剪
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) {
        start++
        trimmed++
    }
    // 从尾裁剪
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return this.subList(start, end)
}
