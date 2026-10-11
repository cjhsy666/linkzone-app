package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.workspace.Workspace
import com.cjhsy.linkzone.workspace.WorkspaceShellContext
import com.cjhsy.linkzone.workspace.WorkspaceShellRunner
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
 * 工作区工具集 —— 移植自 RikkaHub WorkspaceTools 的裁剪版。
 *
 * 智能体绑定工作区后注入四个工具：
 * - workspace_read_file：读 UTF-8 文本文件（图片等二进制拒绝）
 * - workspace_write_file：写入/覆盖文件（自动建父目录）
 * - workspace_edit_file：精确文本替换（一次或全部）
 * - workspace_shell：在工作区执行 shell（宿主 toybox sh 或 PRoot Linux）
 *
 * 所有路径经 canonical 校验，禁止越出工作区根目录。
 */
fun createWorkspaceTools(
    workspace: Workspace,
    runner: WorkspaceShellRunner,
): List<Tool> = listOf(
    buildReadFileTool(workspace),
    buildWriteFileTool(workspace),
    buildEditFileTool(workspace),
    buildShellTool(workspace, runner),
)

private val TEXT_MAX_BYTES = 10 * 1024 * 1024 // 单次读写 10MB 上限
private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "jar", "apk",
    "dex", "so", "a", "o", "class", "mp3", "mp4", "wav", "ogg", "ttf", "otf",
    "woff", "woff2", "eot", "exe", "bin", "dat"
)

/** 把工具相对路径解析为工作区内文件；越界/非法抛异常 */
private fun Workspace.resolveInside(rawPath: String): File {
    val safe = rawPath.trim().removePrefix("/")
    val rootCanonical = root.canonicalPath
    val target = File(root, safe).canonicalFile
    if (!target.path.startsWith(rootCanonical + File.separator) && target.path != rootCanonical) {
        error("Path '$rawPath' is outside the workspace")
    }
    return target
}

private fun buildReadFileTool(workspace: Workspace): Tool = Tool(
    name = "workspace_read_file",
    description = """
        Read a UTF-8 text file inside the bound workspace. Returns the file content.
        Paths are relative to the workspace root (e.g. "src/main.py").
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
        val file = workspace.resolveInside(path)
        if (!file.exists()) error("File not found: $path")
        if (file.isDirectory) error("'$path' is a directory")
        if (file.length() > TEXT_MAX_BYTES) error("File too large (>10MB)")
        if (file.extension.lowercase() in BINARY_EXTENSIONS) error("'$path' appears to be a binary file")
        listOf(UIMessagePart.Text(file.readText(Charsets.UTF_8)))
    }
)

private fun buildWriteFileTool(workspace: Workspace): Tool = Tool(
    name = "workspace_write_file",
    description = """
        Write UTF-8 text to a file inside the bound workspace, creating parent directories.
        Overwrites existing files by default; set overwrite=false to refuse overwriting.
    """.trimIndent().replace("\n", " "),
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
        val file = workspace.resolveInside(path)
        if (file.exists() && !overwrite) error("File already exists: $path (overwrite=false)")
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("size_bytes", file.length())
            }.toString()
        ))
    }
)

private fun buildEditFileTool(workspace: Workspace): Tool = Tool(
    name = "workspace_edit_file",
    description = """
        Replace exact text in an existing workspace file. Provide old_text (must match exactly
        and uniquely unless replace_all=true) and new_text. Use after reading the file.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path of the file to edit")
                })
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
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
        val file = workspace.resolveInside(path)
        if (!file.exists()) error("File not found: $path")
        val original = file.readText(Charsets.UTF_8)
        val occurrences = original.split(oldText).size - 1
        if (occurrences == 0) error("old_text not found in $path")
        if (occurrences > 1 && !replaceAll) {
            error("old_text matches $occurrences places; provide more context or set replace_all=true")
        }
        val updated = if (replaceAll) original.replace(oldText, newText)
        else original.replaceFirst(oldText, newText)
        file.writeText(updated, Charsets.UTF_8)
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("replacements", if (replaceAll) occurrences else 1)
                put("size_bytes", file.length())
            }.toString()
        ))
    }
)

private fun buildShellTool(workspace: Workspace, runner: WorkspaceShellRunner): Tool {
    // 技能目录存在技能时才提示：脚本可在 shell 中直接执行（只读使用，不得修改）
    val skillsHint = runCatching {
        val dir = java.io.File(runner.skillsPath)
        val names = dir.listFiles { f -> f.isDirectory }?.map { it.name }?.take(20).orEmpty()
        if (names.isEmpty()) "" else """

            **Skill scripts**: Installed skill packages are available at '${runner.skillsPath}/<skill-name>/'
            with SKILL.md and bundled scripts/references. You may read them and execute their scripts
            (e.g. `python ${runner.skillsPath}/${names.first()}/scripts/run.py` when an interpreter exists),
            but treat this directory as READ-ONLY: never create, modify, or delete files there.
            Available skills: ${names.joinToString(", ")}
        """.trimIndent()
    }.getOrDefault("")

    return Tool(
        name = "workspace_shell",
        description = """
        Run a shell command in the bound workspace and return its output.
        The working directory is the workspace root. Output is capped at 128KB; timeout default 30s.
        Use this to run scripts, install-free CLI tools available on device, and to inspect results.
    """.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ -> skillsHint },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run with /system/bin/sh (or /bin/sh inside PRoot)")
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional working directory relative to workspace root")
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in milliseconds, max 120000. Default 30000.")
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
                .coerceIn(1_000, 120_000).toLong()
            val result = runner.execute(
                WorkspaceShellContext(
                    root = workspace.root,
                    command = command,
                    cwd = cwd,
                    timeoutMillis = timeout
                )
            )
            val payload = buildJsonObject {
                put("exit_code", result.exitCode)
                put("timed_out", result.timedOut)
                if (result.stdout.isNotBlank()) put("stdout", result.stdout)
                if (result.stderr.isNotBlank()) put("stderr", result.stderr)
            }.toString()
            listOf(UIMessagePart.Text(payload))
        }
    )
}

private fun JsonObject.stringParam(key: String): String? =
    this[key]?.jsonPrimitive?.let { if (it is JsonNull) null else it.content }
