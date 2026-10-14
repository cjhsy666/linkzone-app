package com.cjhsy.linkzone.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.languages.Prism_brainfuck
import io.noties.prism4j.languages.Prism_c
import io.noties.prism4j.languages.Prism_clike
import io.noties.prism4j.languages.Prism_clojure
import io.noties.prism4j.languages.Prism_cpp
import io.noties.prism4j.languages.Prism_csharp
import io.noties.prism4j.languages.Prism_css
import io.noties.prism4j.languages.Prism_css_extras
import io.noties.prism4j.languages.Prism_dart
import io.noties.prism4j.languages.Prism_git
import io.noties.prism4j.languages.Prism_go
import io.noties.prism4j.languages.Prism_groovy
import io.noties.prism4j.languages.Prism_java
import io.noties.prism4j.languages.Prism_javascript
import io.noties.prism4j.languages.Prism_json
import io.noties.prism4j.languages.Prism_kotlin
import io.noties.prism4j.languages.Prism_latex
import io.noties.prism4j.languages.Prism_makefile
import io.noties.prism4j.languages.Prism_markdown
import io.noties.prism4j.languages.Prism_markup
import io.noties.prism4j.languages.Prism_python
import io.noties.prism4j.languages.Prism_scala
import io.noties.prism4j.languages.Prism_sql
import io.noties.prism4j.languages.Prism_swift
import io.noties.prism4j.languages.Prism_yaml

/**
 * Prism4j 语法高亮：真实语法规则（25 种语言 vendored 自 Prism4j 官方仓库），
 * 手写 GrammarLocator（替代官方 kapt bundler，更确定、CI 安全）。
 *
 * 调色板为 One Light / One Dark 风格（DeepSeek、豆包等主流应用同款方案）。
 */

// ---------- One Light / One Dark 调色板 ----------

private data class CodePalette(
    val keyword: Color, val string: Color, val number: Color,
    val comment: Color, val function: Color, val type: Color,
    val operator: Color, val punctuation: Color, val tag: Color,
    val attrName: Color, val selector: Color, val property: Color
)

private val ONE_LIGHT = CodePalette(
    keyword = Color(0xFFA626A4), string = Color(0xFF50A14F), number = Color(0xFFB76B01),
    comment = Color(0xFFA0A1A7), function = Color(0xFF4078F2), type = Color(0xFFC18401),
    operator = Color(0xFF0184BC), punctuation = Color(0xFF383A42),
    tag = Color(0xFFE45649), attrName = Color(0xFFC18401),
    selector = Color(0xFFA626A4), property = Color(0xFFE45649)
)

private val ONE_DARK = CodePalette(
    keyword = Color(0xFFC678DD), string = Color(0xFF98C379), number = Color(0xFFD19A66),
    comment = Color(0xFF7F848E), function = Color(0xFF61AFEF), type = Color(0xFFE5C07B),
    operator = Color(0xFF56B6C2), punctuation = Color(0xFFABB2BF),
    tag = Color(0xFFE06C75), attrName = Color(0xFFE5C07B),
    selector = Color(0xFFC678DD), property = Color(0xFFE06C75)
)

// ---------- GrammarLocator：语言名 → vendored 语法 ----------

private object AppPrismLocator : GrammarLocator {

    // grammar 构建有成本（大量正则编译），按语言缓存
    private val grammarCache = java.util.concurrent.ConcurrentHashMap<String, Prism4j.Grammar?>()

    /** 围栏语言标识 → vendored 语言名；未知语言返回 null（不高亮，纯等宽兜底） */
    private fun realName(lang: String): String? = when (lang.lowercase().trim()) {
        "brainfuck" -> "brainfuck"
        "c" -> "c"
        "clike" -> "clike"
        "clojure", "clj" -> "clojure"
        "cpp", "c++", "cc", "cxx" -> "cpp"
        "csharp", "cs", "dotnet" -> "csharp"
        "css" -> "css"
        "css-extras", "css_extras" -> "css_extras"
        "dart" -> "dart"
        "git" -> "git"
        "go", "golang" -> "go"
        "groovy", "gradle" -> "groovy"
        "java" -> "java"
        "javascript", "js", "jsx", "ts", "typescript", "tsx" -> "javascript"
        "json", "jsonp", "json5" -> "json"
        "kotlin", "kt", "kts" -> "kotlin"
        "latex", "tex" -> "latex"
        "makefile", "mk" -> "makefile"
        "markdown", "md" -> "markdown"
        "markup", "html", "xml", "svg", "vue" -> "markup"
        "python", "py" -> "python"
        "scala" -> "scala"
        "sql" -> "sql"
        "swift" -> "swift"
        "yaml", "yml" -> "yaml"
        else -> null
    }

    private fun create(prism4j: Prism4j, name: String): Prism4j.Grammar = when (name) {
        "brainfuck" -> Prism_brainfuck.create(prism4j)
        "c" -> Prism_c.create(prism4j)
        "clike" -> Prism_clike.create(prism4j)
        "clojure" -> Prism_clojure.create(prism4j)
        "cpp" -> Prism_cpp.create(prism4j)
        "csharp" -> Prism_csharp.create(prism4j)
        "css" -> Prism_css.create(prism4j)
        "css_extras" -> Prism_css_extras.create(prism4j)
        "dart" -> Prism_dart.create(prism4j)
        "git" -> Prism_git.create(prism4j)
        "go" -> Prism_go.create(prism4j)
        "groovy" -> Prism_groovy.create(prism4j)
        "java" -> Prism_java.create(prism4j)
        "javascript" -> Prism_javascript.create(prism4j)
        "json" -> Prism_json.create(prism4j)
        "kotlin" -> Prism_kotlin.create(prism4j)
        "latex" -> Prism_latex.create(prism4j)
        "makefile" -> Prism_makefile.create(prism4j)
        "markdown" -> Prism_markdown.create(prism4j)
        "markup" -> Prism_markup.create(prism4j)
        "python" -> Prism_python.create(prism4j)
        "scala" -> Prism_scala.create(prism4j)
        "sql" -> Prism_sql.create(prism4j)
        "swift" -> Prism_swift.create(prism4j)
        "yaml" -> Prism_yaml.create(prism4j)
        else -> throw IllegalArgumentException("unknown language: $name")
    }

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        val real = realName(language) ?: return null
        // 注意 ConcurrentHashMap 不允许 null value，未命中用 Optional 语义手动处理
        if (grammarCache.containsKey(real)) return grammarCache[real]
        val g = runCatching { create(prism4j, real) }.getOrNull()
        if (g != null) grammarCache[real] = g
        return g
    }

    override fun languages(): Set<String> = setOf(
        "brainfuck", "c", "clike", "clojure", "cpp", "csharp", "css", "css_extras",
        "dart", "git", "go", "groovy", "java", "javascript", "json", "kotlin",
        "latex", "makefile", "markdown", "markup", "python", "scala", "sql", "swift", "yaml"
    )
}

private val prism by lazy { Prism4j(AppPrismLocator) }

// ---------- token 树 → AnnotatedString ----------

/** token type → 调色板颜色；未映射类型返回 null（正文色） */
private fun tokenColor(type: String, p: CodePalette): Color? = when (type) {
    "keyword", "important", "atrule", "rule", "directive" -> p.keyword
    "string", "char", "template-string", "regex", "attr-value", "url" -> p.string
    "number", "boolean", "constant", "symbol", "unit" -> p.number
    "comment", "prolog", "doctype", "cdata" -> p.comment
    "function", "function-name", "method", "method-name" -> p.function
    "class-name", "type", "builtin", "namespace", "annotation", "decorator" -> p.type
    "operator", "arrow", "assignment" -> p.operator
    "punctuation", "delimiter" -> p.punctuation
    "tag" -> p.tag
    "attr-name", "attribute" -> p.attrName
    "selector" -> p.selector
    "property", "property-access" -> p.property
    else -> null
}

private fun walkNode(node: Prism4j.Node, p: CodePalette, out: AnnotatedString.Builder) {
    if (node.isSyntax) {
        val syntax = node as Prism4j.Syntax
        val color = tokenColor(syntax.type(), p)
        if (color == null) {
            syntax.children().forEach { walkNode(it, p, out) }
        } else {
            val start = out.length
            syntax.children().forEach { walkNode(it, p, out) }
            if (out.length > start) {
                out.addStyle(SpanStyle(color = color), start, out.length)
            }
        }
    } else {
        out.append((node as Prism4j.Text).literal())
    }
}

/**
 * Prism4j 高亮代码块为 AnnotatedString；未知语言 / 超长代码（>20k，防正则回溯卡顿）
 * / 任何解析异常均返回 null，调用方按正文色纯文本渲染。
 */
internal fun highlightWithPrism(code: String, lang: String, dark: Boolean): AnnotatedString? {
    if (code.length > 20_000) return null
    val grammar = AppPrismLocator.grammar(prism, lang) ?: return null
    val palette = if (dark) ONE_DARK else ONE_LIGHT
    return runCatching {
        val nodes = prism.tokenize(code, grammar)
        buildAnnotatedString { nodes.forEach { walkNode(it, palette, this) } }
    }.getOrNull()
}
