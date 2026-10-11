package com.cjhsy.linkzone.workspace

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 工作区元数据（落盘在工作区根目录 workspace.json）。
 * 工作区本体是 filesDir/workspaces/{id}/ 下的普通目录，
 * AI 通过 workspace_* 工具在其中读写文件、执行 shell。
 */
@Serializable
data class WorkspaceMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
)

/**
 * 工作区管理器 —— 文件系统持久化，无 Room 依赖。
 *
 * 目录布局：
 *   filesDir/workspaces/{id}/workspace.json   元数据
 *   filesDir/workspaces/{id}/...              用户与 AI 产生的文件
 *   filesDir/proot/bin/proot                  （可选）PRoot 二进制
 *   filesDir/linux/rootfs/                    （可选）Linux rootfs
 */
class WorkspaceManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val baseDir: File = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val filesDir: File = context.filesDir

    /** 当前 shell 模式：检测到 proot+rootfs 为 PRoot，否则为宿主 toybox sh */
    val shellMode: String
        get() = if (File(filesDir, "proot/bin/proot").exists() &&
            File(filesDir, "linux/rootfs").isDirectory
        ) "PRoot Linux" else "Android Shell"

    fun list(): List<WorkspaceMeta> = baseDir.listFiles { f -> f.isDirectory }
        ?.mapNotNull { dir -> readMeta(dir) }
        ?.sortedBy { it.createdAt }
        .orEmpty()

    fun get(id: String): Workspace? {
        if (id.isBlank()) return null
        val dir = File(baseDir, id)
        if (!dir.isDirectory) return null
        val meta = readMeta(dir) ?: return null
        return Workspace(meta, dir)
    }

    fun create(name: String): Workspace {
        val id = java.util.UUID.randomUUID().toString()
        val dir = File(baseDir, id).apply { mkdirs() }
        val meta = WorkspaceMeta(id = id, name = name.ifBlank { "工作区" }, createdAt = System.currentTimeMillis())
        writeMeta(dir, meta)
        return Workspace(meta, dir)
    }

    fun rename(id: String, name: String) {
        val dir = File(baseDir, id)
        val meta = readMeta(dir) ?: return
        writeMeta(dir, meta.copy(name = name.ifBlank { meta.name }))
    }

    fun delete(id: String) {
        if (id.contains('/') || id.contains("..")) return
        File(baseDir, id).deleteRecursively()
    }

    fun writeMeta(workspace: Workspace) = writeMeta(workspace.root, workspace.meta)

    fun stats(workspace: Workspace): WorkspaceStats {
        var files = 0
        var bytes = 0L
        workspace.root.walkTopDown().forEach { f ->
            if (f.isFile && f.name != META_FILE) {
                files++
                bytes += f.length()
            }
        }
        return WorkspaceStats(files, bytes)
    }

    private fun readMeta(dir: File): WorkspaceMeta? =
        runCatching {
            val f = File(dir, META_FILE)
            if (!f.exists()) return null
            json.decodeFromString(WorkspaceMeta.serializer(), f.readText())
        }.getOrNull()

    private fun writeMeta(dir: File, meta: WorkspaceMeta) {
        File(dir, META_FILE).writeText(json.encodeToString(WorkspaceMeta.serializer(), meta))
    }

    companion object {
        const val META_FILE = "workspace.json"
    }
}

data class Workspace(
    val meta: WorkspaceMeta,
    val root: File,
) {
    val id: String get() = meta.id
    val name: String get() = meta.name
}

data class WorkspaceStats(
    val fileCount: Int,
    val totalBytes: Long,
)
