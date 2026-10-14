package com.cjhsy.linkzone.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.languages.Prism_brainfuck
import io.noties.prism4j.languages.Prism_bash
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
 * Prism4j 语法高亮：真实语法规则（26 种语言 vendored 自 Prism4j 官方仓库 + 自绘 bash），
 * 手写 GrammarLocator（替代官方 kapt bundler，更确定、CI 安全）。
 *
 * 语法色统一取自 [MdCodePalette]（明暗 × 文档流/气泡共四套，见 MdStyle.kt）。
 */

// ---------- GrammarLocator：语言名 → vendored 语法 ----------

private object AppPrismLocator : GrammarLocator {

    // grammar 构建有成本（大量正则编译），按语言缓存
    private val grammarCache = java.util.concurrent.ConcurrentHashMap<String, Prism4j.Grammar?>()

    /** 围栏语言标识 → vendored 语言名；未知语言返回 null（不高亮，纯等宽兜底） */
    private fun realName(lang: String): String? = when (lang.lowercase().trim()) {
        "bash", "sh", "shell", "zsh", "console", "shellsession" -> "bash"
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

    // Prism_css_extras.create 带 @Nullable（原是 prism4j 的 @Modify 扩展）：整体返回可空
    private fun create(prism4j: Prism4j, name: String): Prism4j.Grammar? = when (name) {
        "bash" -> Prism_bash.create(prism4j)
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

    /** 该围栏语言是否有 vendored 语法（顶栏标签用：未知语言显示"代码"） */
    fun supported(language: String): Boolean = realName(language) != null

    override fun languages(): Set<String> = setOf(
        "bash", "brainfuck", "c", "clike", "clojure", "cpp", "csharp", "css", "css_extras",
        "dart", "git", "go", "groovy", "java", "javascript", "json", "kotlin",
        "latex", "makefile", "markdown", "markup", "python", "scala", "sql", "swift", "yaml"
    )
}

private val prism by lazy { Prism4j(AppPrismLocator) }

/** 围栏语言是否有 vendored 语法（顶栏标签：未知/纯文本语言显示"代码"） */
internal fun hasPrismGrammar(lang: String): Boolean = AppPrismLocator.supported(lang)

// ---------- token 树 → AnnotatedString ----------

/** token type → 调色板颜色；未映射类型返回 null（正文色） */
private fun tokenColor(type: String, p: MdCodePalette): Color? = when (type) {
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
    "property", "property-access", "variable" -> p.property
    else -> null
}

private fun walkNode(node: Prism4j.Node, p: MdCodePalette, out: AnnotatedString.Builder) {
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
 * 颜色由调用方按主题/容器传入（缓存 key 必须包含 palette 维度）。
 */
internal fun highlightWithPrism(code: String, lang: String, palette: MdCodePalette): AnnotatedString? {
    if (code.length > 20_000) return null
    val grammar = AppPrismLocator.grammar(prism, lang) ?: return null
    return runCatching {
        val nodes = prism.tokenize(code, grammar)
        buildAnnotatedString { nodes.forEach { walkNode(it, palette, this) } }
    }.getOrNull()
}
