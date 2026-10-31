package com.cjhsy.linkzone.workspace

import android.content.Context
import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 工作区类型：本机目录 / 远程 SSH */
enum class WorkspaceKind { LOCAL, SSH }

/**
 * 统一工作区视图：本地与 SSH 是同一个工作区列表的两种后端，
 * id 各自沿用原有存储（本地 workspace.json / SSH Room 表），不做数据迁移。
 */
data class WorkspaceEntry(
    val id: String,
    val name: String,
    val kind: WorkspaceKind,
    /** 展示用路径：本地绝对路径，SSH 为 host:port · workDir */
    val rootPath: String,
    val ssh: SshWorkspaceConfig? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
) {
    val isSsh: Boolean get() = kind == WorkspaceKind.SSH
}

/**
 * 合并两类工作区的只读仓库：列表、创建本地工作区、按条目构造后端。
 * 本地仍是文件系统事实源，SSH 仍是 Room 表，对外只暴露 [WorkspaceEntry]。
 */
class UnifiedWorkspaceRepository(
    private val manager: WorkspaceManager,
    private val llmRepository: LlmRepository,
) {
    val shellMode: String get() = manager.shellMode

    suspend fun list(): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        localEntries() + sshEntries()
    }.sortedBy { it.createdAt }

    suspend fun createLocal(name: String): WorkspaceEntry = withContext(Dispatchers.IO) {
        val ws = manager.create(name)
        WorkspaceEntry(
            id = ws.id,
            name = ws.name,
            kind = WorkspaceKind.LOCAL,
            rootPath = ws.root.absolutePath,
            createdAt = ws.meta.createdAt,
        )
    }

    fun backendFor(entry: WorkspaceEntry, context: Context): WorkspaceBackend? = when (entry.kind) {
        WorkspaceKind.LOCAL -> manager.get(entry.id)?.let {
            LocalWorkspaceBackend(it, WorkspaceShellRunner.select(context))
        }
        WorkspaceKind.SSH -> entry.ssh?.let { SshWorkspaceBackend(it) }
    }

    private fun localEntries(): List<WorkspaceEntry> = manager.list().map { meta ->
        WorkspaceEntry(
            id = meta.id,
            name = meta.name,
            kind = WorkspaceKind.LOCAL,
            rootPath = manager.get(meta.id)?.root?.absolutePath.orEmpty(),
            createdAt = meta.createdAt,
        )
    }

    private suspend fun sshEntries(): List<WorkspaceEntry> =
        llmRepository.getSshWorkspaces().map { config ->
            WorkspaceEntry(
                id = config.id,
                name = config.name.ifBlank { config.summary },
                kind = WorkspaceKind.SSH,
                rootPath = "${config.summary} · ${config.workDir}",
                ssh = config,
                enabled = config.enabled,
                createdAt = config.createdAt,
            )
        }
}
