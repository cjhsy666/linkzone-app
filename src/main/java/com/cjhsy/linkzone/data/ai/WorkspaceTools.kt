package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.workspace.WorkspaceBackend
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 工作区工具集 —— 本地与 SSH 完全同一套工具名与参数。
 *
 * 智能体绑定工作区（本地目录或 SSH 远端）后注入四个工具：
 * - workspace_read_file：读 UTF-8 文本文件（二进制拒绝），返回纯文本
 * - workspace_write_file：写入/覆盖文件（自动建父目录）
 * - workspace_edit_file：局部替换（精确 → 忽略空白行级 → 报错）
 * - workspace_shell：在工作区执行命令（本地 sh/PRoot 或远端 SSH exec）
 *
 * 路径经后端校验，禁止越出工作区根目录。
 */
fun createWorkspaceTools(backend: WorkspaceBackend): List<Tool> = listOf(
    buildReadFileTool(backend),
    buildWriteFileTool(backend),
    buildEditFileTool(backend),
    buildShellTool(backend),
)

/** read_file 单次返回上限：行数与字符数双闸，超出走 offset 分页续读 */
private const val MAX_READ_LINES = 2_000
private const val MAX_READ_CHARS = 32 * 1024

/** shell 输出头/尾保留量：报错多在尾部，只截头会丢关键信息 */
private const val SHELL_HEAD_CHARS = 16 * 1024
private const val SHELL_TAIL_CHARS = 16 * 1024

/** 长输出保头尾，中间用 marker 标注省略的字数（模型可据此 grep/sed 精确定位） */
private fun headTailKeep(s: String, head: Int, tail: Int): String {
    if (s.length <= head + tail) return s
    val omitted = s.length - head - tail
    return buildString(s.length - omitted + 64) {
        append(s, 0, head)
        append("\n[… ").append(omitted).append(" chars omitted; use grep/sed to locate the middle part …]\n")
        append(s, s.length - tail, s.length)
    }
}

private fun buildReadFileTool(backend: WorkspaceBackend): Tool = Tool(
    name = "workspace_read_file",
    description = """
        Read a UTF-8 text file inside the workspace '${backend.name}' (${backend.rootLabel}).
        Paths are relative to the workspace root (e.g. "src/main.py"). Max 10MB per file.
        Returns at most $MAX_READ_LINES lines / ${MAX_READ_CHARS / 1024}KB per call. When the file is longer,
        a [TRUNCATED …] header is prepended — call again with offset to continue reading.
        offset: 1-based line number to start from, default 1. limit: max lines to return, default $MAX_READ_LINES.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path of the file to read")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "1-based line number to start from. Default 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max lines to return. Default $MAX_READ_LINES.")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject.stringParam("path") ?: error("path is required")
        val offset = (args.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
        val limit = (args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: MAX_READ_LINES)
            .coerceIn(1, MAX_READ_LINES)
        val full = backend.readText(path)
        val lines = full.lines()
        val totalLines = lines.size
        val from = (offset - 1).coerceAtMost(totalLines)
        val to = minOf(from + limit, totalLines)
        var text = lines.subList(from, to).joinToString("\n")
        // 单行超长（如压缩 JSON）时按字符截断，避免一次返回过大
        val hitCharCap = text.length > MAX_READ_CHARS
        if (hitCharCap) text = text.take(MAX_READ_CHARS)
        val result = if (to >= totalLines && !hitCharCap) text else buildString {
            append("[TRUNCATED: showing lines ").append(offset).append('-').append(to)
                .append(" of ").append(totalLines)
                .append(" (").append(full.length / 1024).append("KB)")
            if (hitCharCap) append("; output cut at ").append(MAX_READ_CHARS / 1024).append("KB")
            append(" — call again with offset=").append(to + 1).append(" to continue]\n")
            append(text)
        }
        listOf(UIMessagePart.Text(result))
    }
)

private fun buildWriteFileTool(backend: WorkspaceBackend): Tool = Tool(
    name = "workspace_write_file",
    description = """
        Write UTF-8 text to a file inside the workspace '${backend.name}' (${backend.rootLabel}),
        creating parent directories. Overwrites existing files by default; set overwrite=false to refuse.
    """.trimIndent().replace("\n", " "),
    needsApproval = { backend.requiresApproval },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path of the file to write")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text")
        )
    },
    execute = { args ->
        val path = args.jsonObject.stringParam("path") ?: error("path is required")
        val text = args.jsonObject.stringParam("text") ?: error("text is required")
        val overwrite = args.jsonObject["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        val size = backend.writeText(path, text, overwrite)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("size_bytes", size)
            }.toString()
        ))
    }
)

private fun buildEditFileTool(backend: WorkspaceBackend): Tool = Tool(
    name = "workspace_edit_file",
    description = """
        Replace an exact fragment of a UTF-8 text file in the workspace '${backend.name}' (${backend.rootLabel}).
        Provide old_text (must be unique unless replace_all=true) and new_text.
        Whitespace-tolerant line matching is used as a fallback. Prefer this over write_file when changing part of a file.
    """.trimIndent().replace("\n", " "),
    needsApproval = { backend.requiresApproval },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path of the file to edit")
                })
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace. Include enough context to be unique.")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text. Empty string deletes the fragment.")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text")
        )
    },
    execute = { args ->
        val path = args.jsonObject.stringParam("path") ?: error("path is required")
        val oldText = args.jsonObject.stringParam("old_text") ?: error("old_text is required")
        val newText = args.jsonObject.stringParam("new_text") ?: error("new_text is required")
        val replaceAll = args.jsonObject["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
        val (replacements, size) = backend.editText(path, oldText, newText, replaceAll)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("replacements", replacements)
                put("size_bytes", size)
            }.toString()
        ))
    }
)

private fun buildShellTool(backend: WorkspaceBackend): Tool {
    // 技能目录存在技能时才提示：脚本可在 shell 中直接执行（只读使用，不得修改）
    val skillsHint = backend.skillsPath?.let { path ->
        runCatching {
            val dir = File(path)
            val names = dir.listFiles { f -> f.isDirectory }?.map { it.name }?.take(20).orEmpty()
            if (names.isEmpty()) "" else """

                **Skill scripts**: Installed skill packages are available at '$path/<skill-name>/'
                with SKILL.md and bundled scripts/references. You may read them and execute their scripts,
                but treat this directory as READ-ONLY: never create, modify, or delete files there.
                Available skills: ${names.joinToString(", ")}
            """.trimIndent()
        }.getOrDefault("")
    }.orEmpty()

    val maxSeconds = backend.maxTimeoutMs / 1000
    return Tool(
        name = "workspace_shell",
        description = """
        Run a shell command in the workspace '${backend.name}' (${backend.rootLabel}) and return its output.
        Working directory is the workspace root. Long output keeps the first and last
        ${SHELL_HEAD_CHARS / 1024}KB with the middle marked as omitted.
        Default timeout 30s; raise timeout (milliseconds) up to ${maxSeconds * 1000} for long-running commands.
        Interactive commands are not supported.
    """.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ -> skillsHint },
        needsApproval = { backend.requiresApproval },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to execute")
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional working directory relative to workspace root")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in milliseconds, max ${maxSeconds * 1000}. Default 30000.")
                    })
                },
                required = listOf("command")
            )
        },
        execute = { args ->
            val command = args.jsonObject.stringParam("command") ?: error("command is required")
            val cwd = args.jsonObject.stringParam("cwd").orEmpty()
            if (cwd.contains("..")) error("cwd must not contain '..'")
            val timeout = (args.jsonObject["timeout"]?.jsonPrimitive?.intOrNull ?: 30_000)
                .coerceIn(1_000, backend.maxTimeoutMs.toInt()).toLong()
            val result = backend.exec(command, cwd, timeout)
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("exit_code", result.exitCode)
                    put("timed_out", result.timedOut)
                    if (result.stdout.isNotBlank()) {
                        put("stdout", headTailKeep(result.stdout, SHELL_HEAD_CHARS, SHELL_TAIL_CHARS))
                    }
                    if (result.stderr.isNotBlank()) {
                        put("stderr", headTailKeep(result.stderr, SHELL_HEAD_CHARS, SHELL_TAIL_CHARS))
                    }
                }.toString()
            ))
        }
    )
}

private fun JsonObject.stringParam(key: String): String? =
    this[key]?.jsonPrimitive?.let { if (it is JsonNull) null else it.content }
