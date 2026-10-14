package com.cjhsy.linkzone.ui.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown 渲染设计令牌（唯一取色/取尺入口）。
 *
 * 两套主题（浅/暗）× 两种容器（[MdContainer.Document] AI 文档流 / [MdContainer.Bubble] 品牌色气泡），
 * 组件禁止再自行 textColor.copy(alpha=…) 硬编码取色，一律走 [MdPalette]。
 *
 * 视觉取向：DeepSeek 的克制留白 + 豆包的卡片质感 + GitHub 的技术元素表达。
 */

enum class MdContainer { Document, Bubble }

// ============================================================================
// 字号 / 间距 / 圆角（不分主题，全局常量）
// ============================================================================

internal object MdType {
    val bodySize = 15.sp
    val bodyLineHeight = 25.5.sp                 // 1.7 倍行高，长文阅读舒展
    val codeSize = 12.5.sp
    val codeLineHeight = 20.sp                  // 1.6
    val captionSize = 12.sp
    val tableSize = 13.sp

    // 标题：字号 / 行高 / 段前距 / 段后距
    val h1Size = 21.sp;  val h1Line = 30.sp
    val h2Size = 18.sp;  val h2Line = 26.sp
    val h3Size = 16.sp;  val h3Line = 24.sp
    val h4Size = 15.sp;  val h4Line = 22.sp
    val h5Size = 14.sp;  val h5Line = 21.sp
    val h6Size = 13.sp;  val h6Line = 20.sp
}

internal object MdSpace {
    val blockGap = 10.dp                        // 块间距
    val paragraphGap = 6.dp
    val codeRadius = 12.dp
    val cardRadius = 10.dp                     // 表格 / 引用
    val quoteBar = 3.5.dp
    val quotePadH = 12.dp
    val quotePadV = 9.dp
    val codePadH = 14.dp
    val codePadV = 12.dp
    val tablePadH = 11.dp
    val tablePadV = 7.dp
    val inlineCodePadH = 6.dp
    val inlineCodePadV = 1.dp
    val inlineCodeRadius = 6.dp
}

/** 正文样式（兜底纯文本与富渲染必须同版式，异步切换时零跳变） */
internal val mdBodyStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MdType.bodySize,
        lineHeight = MdType.bodyLineHeight
    )

internal fun mdHeadingStyle(level: Int): TextStyle = when (level) {
    1 -> TextStyle(fontSize = MdType.h1Size, fontWeight = FontWeight.Bold, lineHeight = MdType.h1Line)
    2 -> TextStyle(fontSize = MdType.h2Size, fontWeight = FontWeight.Bold, lineHeight = MdType.h2Line)
    3 -> TextStyle(fontSize = MdType.h3Size, fontWeight = FontWeight.SemiBold, lineHeight = MdType.h3Line)
    4 -> TextStyle(fontSize = MdType.h4Size, fontWeight = FontWeight.SemiBold, lineHeight = MdType.h4Line)
    5 -> TextStyle(fontSize = MdType.h5Size, fontWeight = FontWeight.SemiBold, lineHeight = MdType.h5Line)
    else -> TextStyle(fontSize = MdType.h6Size, fontWeight = FontWeight.SemiBold, lineHeight = MdType.h6Line)
}

internal val mdCodeStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = MdType.codeSize,
    lineHeight = MdType.codeLineHeight
)

// ============================================================================
// 语法高亮调色板
// ============================================================================

@Immutable
internal data class MdCodePalette(
    val codeText: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
    val operator: Color,
    val punctuation: Color,
    val tag: Color,
    val attrName: Color,
    val selector: Color,
    val property: Color
)

/** One Light 精修（浅主题代码面） */
private val CODE_LIGHT = MdCodePalette(
    codeText = Color(0xFF24292F),
    keyword = Color(0xFFCF222E), string = Color(0xFF0A7A3D), number = Color(0xFFB25C00),
    comment = Color(0xFF6E7781), function = Color(0xFF6639B7), type = Color(0xFF953800),
    operator = Color(0xFF0550AE), punctuation = Color(0xFF57606A),
    tag = Color(0xFFD1242F), attrName = Color(0xFF953800),
    selector = Color(0xFF8250DF), property = Color(0xFFD1242F)
)

/** GitHub Dark / One Dark 之间的深色方案（暗主题代码面 #0D1117） */
private val CODE_DARK = MdCodePalette(
    codeText = Color(0xFFE6EDF3),
    keyword = Color(0xFFFF7B72), string = Color(0xFFA5D6FF), number = Color(0xFF79C0FF),
    comment = Color(0xFF8B949E), function = Color(0xFFD2A8FF), type = Color(0xFFFFA657),
    operator = Color(0xFF79C0FF), punctuation = Color(0xFFABB2BF),
    tag = Color(0xFF7EE787), attrName = Color(0xFF79C0FF),
    selector = Color(0xFFFF7B72), property = Color(0xFF7EE787)
)

/** 浅色主题品牌气泡（#5B6BF0 底 + 黑色压暗面）：白色阶 */
private val CODE_BUBBLE_LIGHT = MdCodePalette(
    codeText = Color.White,
    keyword = Color.White, string = Color(0xCCFFFFFF), number = Color(0xD9FFFFFF),
    comment = Color(0x8CFFFFFF), function = Color(0xF2FFFFFF), type = Color(0xDBFFFFFF),
    operator = Color(0xB8FFFFFF), punctuation = Color(0xA6FFFFFF),
    tag = Color.White, attrName = Color(0xD9FFFFFF),
    selector = Color.White, property = Color(0xE6FFFFFF)
)

/** 暗色主题品牌气泡（#5B8CFF 亮蓝底 + 白色提亮面）：深海军蓝阶 */
private val CODE_BUBBLE_DARK = MdCodePalette(
    codeText = Color(0xFF081830),
    keyword = Color(0xFF081830), string = Color(0xFF0F5132), number = Color(0xFF8A4B00),
    comment = Color(0xFF54657C), function = Color(0xFF1D4ED8), type = Color(0xFF6D28D9),
    operator = Color(0xFF0F3D8C), punctuation = Color(0xFF334661),
    tag = Color(0xFFB91C1C), attrName = Color(0xFF8A4B00),
    selector = Color(0xFF6D28D9), property = Color(0xFFB91C1C)
)

// ============================================================================
// GFM Alerts 语义色
// ============================================================================

internal enum class MdAlertKind(val title: String) {
    NOTE("注意"), TIP("提示"), IMPORTANT("重要"), WARNING("警告"), CAUTION("危险")
}

@Immutable
internal data class MdAlertColors(val fg: Color, val bar: Color, val bg: Color)

// ============================================================================
// 总令牌
// ============================================================================

@Immutable
internal data class MdPalette(
    val dark: Boolean,
    val container: MdContainer,
    val text: Color,
    val dim: Color,                           // 次要文字（语言标签、复制按钮）
    val faint: Color,                        // 行号、占位
    val link: Color,
    val quoteBg: Color,
    val quoteBar: Color,
    val divider: Color,                      // 分割线 / 卡片边框
    val faintDivider: Color,                 // 更淡的分隔（h1/h2 下线、表格行线）
    val codeSurface: Color,
    val codeBorder: Color,
    val codeHeaderBg: Color,
    val codeHeaderDivider: Color,
    val codeFade: Color,                     // 折叠渐隐遮罩
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val tableSurface: Color,
    val tableBorder: Color,
    val tableHeaderBg: Color,
    val tableRowDivider: Color,
    val checkChecked: Color,
    val checkUnchecked: Color,
    val code: MdCodePalette
) {
    /** GFM Alert 语义色；气泡容器内降级为中性引用样式（保证品牌底上可读） */
    fun alert(kind: MdAlertKind): MdAlertColors {
        if (container == MdContainer.Bubble) {
            return MdAlertColors(fg = text, bar = text.copy(alpha = 0.55f), bg = quoteBg)
        }
        return if (dark) ALERT_DARK.getValue(kind) else ALERT_LIGHT.getValue(kind)
    }

    /** 嵌套引用竖条：层级越深越淡（层次可辨，TR-3.3） */
    fun quoteBarAt(depth: Int): Color = when (depth.coerceAtLeast(0)) {
        0 -> quoteBar
        1 -> quoteBar.copy(alpha = quoteBar.alpha * 0.62f)
        else -> quoteBar.copy(alpha = quoteBar.alpha * 0.38f)
    }

    /** 嵌套引用底色：层级越深越淡（避免多层引用糊成一片） */
    fun quoteBgAt(depth: Int): Color = when (depth.coerceAtLeast(0)) {
        0 -> quoteBg
        1 -> quoteBg.copy(alpha = quoteBg.alpha * 0.72f)
        else -> quoteBg.copy(alpha = quoteBg.alpha * 0.5f)
    }

    companion object
}

// ---------- 语义色表 ----------

private val ALERT_LIGHT: Map<MdAlertKind, MdAlertColors> = mapOf(
    MdAlertKind.NOTE to MdAlertColors(Color(0xFF1D4ED8), Color(0xFF3B82F6), Color(0x143B82F6)),
    MdAlertKind.TIP to MdAlertColors(Color(0xFF047857), Color(0xFF10B981), Color(0x1210B981)),
    MdAlertKind.IMPORTANT to MdAlertColors(Color(0xFF6D28D9), Color(0xFF8B5CF6), Color(0x148B5CF6)),
    MdAlertKind.WARNING to MdAlertColors(Color(0xFFB45309), Color(0xFFF59E0B), Color(0x16F59E0B)),
    MdAlertKind.CAUTION to MdAlertColors(Color(0xFFB91C1C), Color(0xFFEF4444), Color(0x14EF4444))
)

private val ALERT_DARK: Map<MdAlertKind, MdAlertColors> = mapOf(
    MdAlertKind.NOTE to MdAlertColors(Color(0xFF93C5FD), Color(0xFF60A5FA), Color(0x1F60A5FA)),
    MdAlertKind.TIP to MdAlertColors(Color(0xFF6EE7B7), Color(0xFF34D399), Color(0x1F34D399)),
    MdAlertKind.IMPORTANT to MdAlertColors(Color(0xFFC4B5FD), Color(0xFFA78BFA), Color(0x22A78BFA)),
    MdAlertKind.WARNING to MdAlertColors(Color(0xFFFCD34D), Color(0xFFFBBF24), Color(0x22FBBF24)),
    MdAlertKind.CAUTION to MdAlertColors(Color(0xFFFCA5A5), Color(0xFFF87171), Color(0x22F87171))
)

// ---------- 工厂 ----------

/**
 * 按当前 MaterialTheme + 容器构造渲染令牌。
 * textColor/linkColor 为调用方显式传入的前景色（气泡内为 onPrimary），与派生值一致，
 * 以显式值为准，保证调用点行为不变。
 */
@Composable
internal fun rememberMdPalette(
    container: MdContainer,
    textColor: Color,
    linkColor: Color
): MdPalette {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    return when {
        container == MdContainer.Document && !dark -> MdPalette(
            dark = false, container = MdContainer.Document,
            text = textColor,
            dim = Color(0xFF5B6678),
            faint = Color(0xFF9AA4B2),
            link = linkColor,
            quoteBg = Color(0x0F5B6BF0),
            quoteBar = Color(0xFF5B6BF0),
            divider = Color(0xFFE2E8F0),
            faintDivider = Color(0xFFEDF1F6),
            codeSurface = Color(0xFFF6F8FA),
            codeBorder = Color(0xFFD8E0EA),
            codeHeaderBg = Color(0xFFEEF2F7),
            codeHeaderDivider = Color(0xFFD8E0EA),
            codeFade = Color(0xFFF6F8FA),
            inlineCodeBg = Color(0x175B6BF0),
            inlineCodeText = Color(0xFF4651C7),
            tableSurface = scheme.surface,
            tableBorder = Color(0xFFE2E8F0),
            tableHeaderBg = Color(0xFFF1F5F9),
            tableRowDivider = Color(0xFFEDF1F6),
            checkChecked = Color(0xFF5B6BF0),
            checkUnchecked = Color(0xFF94A3B8),
            code = CODE_LIGHT
        )

        container == MdContainer.Document && dark -> MdPalette(
            dark = true, container = MdContainer.Document,
            text = textColor,
            dim = Color(0xFF93A2BC),
            faint = Color(0xFF5E6E86),
            link = linkColor,
            quoteBg = Color(0x245B8CFF),
            quoteBar = Color(0xFF7DA8FF),
            divider = Color(0xFF2A3648),
            faintDivider = Color(0xFF222E42),
            codeSurface = Color(0xFF0D1117),
            codeBorder = Color(0xFF283246),
            codeHeaderBg = Color(0xFF151C28),
            codeHeaderDivider = Color(0xFF283246),
            codeFade = Color(0xFF0D1117),
            inlineCodeBg = Color(0x295B8CFF),
            inlineCodeText = Color(0xFFBCD2FF),
            tableSurface = Color(0xFF151E31),
            tableBorder = Color(0xFF2A3648),
            tableHeaderBg = Color(0xFF1E293B),
            tableRowDivider = Color(0xFF222E42),
            checkChecked = Color(0xFF7DA8FF),
            checkUnchecked = Color(0xFF64748B),
            code = CODE_DARK
        )

        // 浅色主题品牌气泡：蓝紫底 + 黑色压暗表面 + 白字（对比度高于白面方案，满足 WCAG AA）
        container == MdContainer.Bubble && !dark -> MdPalette(
            dark = false, container = MdContainer.Bubble,
            text = textColor,
            dim = Color(0xE6FFFFFF),
            faint = Color(0x99FFFFFF),
            link = linkColor,
            quoteBg = Color(0x1A000000),
            quoteBar = Color(0xE6FFFFFF),
            divider = Color(0x40FFFFFF),
            faintDivider = Color(0x26FFFFFF),
            codeSurface = Color(0x33000000),
            codeBorder = Color(0x3DFFFFFF),
            codeHeaderBg = Color(0x47000000),
            codeHeaderDivider = Color(0x3DFFFFFF),
            codeFade = Color(0xCC202854),
            inlineCodeBg = Color(0x3D000000),
            inlineCodeText = Color.White,
            tableSurface = Color(0x24000000),
            tableBorder = Color(0x3DFFFFFF),
            tableHeaderBg = Color(0x38000000),
            tableRowDivider = Color(0x24FFFFFF),
            checkChecked = Color.White,
            checkUnchecked = Color(0x99FFFFFF),
            code = CODE_BUBBLE_LIGHT
        )

        // 暗色主题品牌气泡：亮蓝底 + 白色提亮表面 + 深海军蓝字
        else -> MdPalette(
            dark = true, container = MdContainer.Bubble,
            text = textColor,
            dim = Color(0xE60A1A33),
            faint = Color(0x990A1A33),
            link = linkColor,
            quoteBg = Color(0x2EFFFFFF),
            quoteBar = Color(0xB30A1A33),
            divider = Color(0x330A1A33),
            faintDivider = Color(0x1F0A1A33),
            codeSurface = Color(0x38FFFFFF),
            codeBorder = Color(0x290A1A33),
            codeHeaderBg = Color(0x4DFFFFFF),
            codeHeaderDivider = Color(0x290A1A33),
            codeFade = Color(0xCC9DBEFF),
            inlineCodeBg = Color(0x1F0A1A33),
            inlineCodeText = Color(0xFF0A1A33),
            tableSurface = Color(0x2EFFFFFF),
            tableBorder = Color(0x330A1A33),
            tableHeaderBg = Color(0x4DFFFFFF),
            tableRowDivider = Color(0x1F0A1A33),
            checkChecked = Color(0xFF0A1A33),
            checkUnchecked = Color(0x800A1A33),
            code = CODE_BUBBLE_DARK
        )
    }
}
