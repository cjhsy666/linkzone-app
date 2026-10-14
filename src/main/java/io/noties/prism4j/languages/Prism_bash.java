package io.noties.prism4j.languages;

import org.jetbrains.annotations.NotNull;

import io.noties.prism4j.Prism4j;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.compile;
import static io.noties.prism4j.Prism4j.grammar;
import static io.noties.prism4j.Prism4j.pattern;
import static io.noties.prism4j.Prism4j.token;

/**
 * Bash / POSIX shell 语法（精简移植自 Prism.js bash 定义，覆盖 shell 脚本高频元素）：
 * shebang、注释、单双引号/反引号字符串、变量与命令替换、控制流关键字、
 * 内建命令、函数定义、数字、操作符与标点。sh / shell / zsh 共用。
 */
@SuppressWarnings("unused")
public class Prism_bash {

  @NotNull
  public static Prism4j.Grammar create(@NotNull Prism4j prism4j) {
    return grammar("bash",
      // shebang 行：#! 开头，着关键字色（important 语义走 keyword 映射）
      token("keyword", pattern(compile("^#![ \\t]*/[^\\r\\n]*", MULTILINE))),
      token("comment", pattern(
        compile("(^|[^\\\\\"'`])#[^\\r\\n]*"),
        true
      )),
      token("string",
        pattern(compile("\"(?:\\\\[\\s\\S]|(?!\")[^\\\\\\r\\n])*\"")),
        pattern(compile("'[^'\\r\\n]*'")),
        pattern(compile("`(?:\\\\[\\s\\S]|[^`\\\\])*`"))
      ),
      // 变量：$VAR / ${VAR} / $? $@ $# 等 / $(...) 命令替换
      token("variable", pattern(compile(
        "\\$(?:\\{[#!]?[A-Za-z_][A-Za-z0-9_]*(?::[-=?+][^}]*)?}|" +
          "\\([^)]*\\)|[A-Za-z_][A-Za-z0-9_]*|[#@?$!*0-9-])"
      ))),
      // 控制流关键字
      token("keyword", pattern(compile(
        "\\b(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|in|function|" +
          "select|time|coproc|declare|typeset|local|readonly|export|unset|shift|set|" +
          "source|return|exit|break|continue|trap|eval|exec)\\b"
      ))),
      // 高频内建命令 / 常见 CLI（Prism.js 同款 builtin 语义，映射 type 色）
      token("builtin", pattern(compile(
        "\\b(?:echo|printf|cd|pwd|ls|cat|grep|egrep|fgrep|sed|awk|xargs|find|sort|uniq|" +
          "head|tail|wc|cut|tr|tee|mkdir|rmdir|rm|cp|mv|ln|chmod|chown|touch|stat|df|du|" +
          "sudo|su|apt|apt-get|dpkg|yum|dnf|apk|brew|curl|wget|tar|gzip|gunzip|zip|unzip|" +
          "ssh|scp|rsync|kill|killall|ps|top|env|which|whoami|date|sleep|test|true|false|" +
          "read|mapfile|printf|pushd|popd|dirs|umask|wait|bg|fg|jobs|nohup|disown|" +
          "git|docker|docker-compose|kubectl|helm|npm|npx|yarn|pnpm|pip|pip3|" +
          "python|python3|node|ruby|java|javac|gradle|mvn|go|rustc|cargo|make|cmake)\\b",
        CASE_INSENSITIVE
      ))),
      // 函数定义：name() { 或 function name
      token("function",
        pattern(compile("^\\s*[A-Za-z_][A-Za-z0-9_.-]*(?=\\s*\\(\\s*\\))", MULTILINE)),
        pattern(compile("(\\bfunction\\s+)[A-Za-z_][A-Za-z0-9_.-]*", CASE_INSENSITIVE))
      ),
      token("number", pattern(compile("\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b"))),
      token("operator", pattern(compile(
        "&&|\\|\\||>>|<<-?|==|!=|=~|=[~#?+-]?|\\|\\||[|&;]|\\$\\?"
      ))),
      token("punctuation", pattern(compile("[(){}\\[\\]]")))
    );
  }
}
