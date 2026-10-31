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

private fun buildReadFileTool(backend: WorkspaceBackend): Tool = Tool(
    name = "workspace_read_file",
    description = """
        Read a UTF-8 text file inside the workspace '${backend.name}' (${backend.rootLabel}).
        Paths are relative to the workspace root (e.g. "src/main.py"). Max 10MB per read.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path of the file to read")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject.stringParam("path") ?: error("path is required")
        listOf(UIMessagePart.Text(backend.readText(path)))
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
        Working directory is the workspace root. Output is capped at 128KB.
        Default timeout 30s; raise timeout (milliseconds) up to $maxSeconds s for long-running commands.
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
                        put("description", "Timeout in milliseconds, max $maxSeconds. Default 30000.")
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
                    if (result.stdout.isNotBlank()) put("stdout", result.stdout)
                    if (result.stderr.isNotBlank()) put("stderr", result.stderr)
                }.toString()
            ))
        }
    )
}

private fun JsonObject.stringParam(key: String): String? =
    this[key]?.jsonPrimitive?.let { if (it is JsonNull) null else it.content }
