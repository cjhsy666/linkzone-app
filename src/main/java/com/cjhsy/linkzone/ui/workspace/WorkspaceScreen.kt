package com.cjhsy.linkzone.ui.workspace

import com.cjhsy.linkzone.core.error.toUserMessage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.data.ai.SshSessionManager
import com.cjhsy.linkzone.data.model.SshWorkspaceConfig
import com.cjhsy.linkzone.workspace.UnifiedWorkspaceRepository
import com.cjhsy.linkzone.workspace.Workspace
import com.cjhsy.linkzone.workspace.WorkspaceBackend
import com.cjhsy.linkzone.workspace.WorkspaceCommandResult
import com.cjhsy.linkzone.workspace.WorkspaceEntry
import com.cjhsy.linkzone.workspace.WorkspaceKind
import com.cjhsy.linkzone.workspace.WorkspaceManager
import com.cjhsy.linkzone.workspace.WorkspaceShellContext
import com.cjhsy.linkzone.workspace.WorkspaceShellRunner
import com.cjhsy.linkzone.workspace.WorkspaceStats
import java.io.File
import java.text.SimpleDateFormat
import com.cjhsy.linkzone.workspace.term.PtySession
import com.cjhsy.linkzone.workspace.term.TermCell
import com.cjhsy.linkzone.workspace.term.TermScreen
import com.cjhsy.linkzone.workspace.term.TerminalLauncher
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/** 文件条目（UI 展示用） */
data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val relPath: String,
)

/** 文件浏览视图模式 */
enum class FileViewMode { LIST, GRID }

/** 文件排序方式 */
enum class FileSortBy { NAME, SIZE, TIME }

data class WorkspaceUiState(
    val workspaces: List<WorkspaceEntry> = emptyList(),
    val stats: Map<String, WorkspaceStats> = emptyMap(),
    /** 非空时进入该工作区详情 */
    val opened: WorkspaceEntry? = null,
    /** 当前浏览目录（相对工作区根，空串=根） */
    val cwd: String = "",
    val entries: List<FileEntry> = emptyList(),
    /** 目录读取中（SSH 下网络延迟明显，用于展示加载态） */
    val dirLoading: Boolean = false,
    /** 当前目录搜索过滤词 */
    val query: String = "",
    val viewMode: FileViewMode = FileViewMode.LIST,
    val sortBy: FileSortBy = FileSortBy.NAME,
    val sortAsc: Boolean = true,
    /** 多选集合（FileEntry.relPath），非空=多选模式 */
    val selection: Set<String> = emptySet(),
    /** 0=文件 1=终端（SSH 为命令台） */
    val tab: Int = 0,
    /** 非空时显示 SSH 配置编辑页 */
    val editingSsh: SshWorkspaceConfig? = null,
    /** 编辑的是已有配置（用于显示「移除」按钮） */
    val sshEditingExisting: Boolean = false,
    val sshSaving: Boolean = false,
    val sshTesting: Boolean = false,
    /** SSH 命令台输出 */
    val commandOutput: String = "",
    val commandRunning: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * 工作区管理页 VM：文件系统直读（无 Room），全部 IO 操作在 Dispatchers.IO。
 * 终端基于 WorkspaceShellRunner（自动选择宿主 sh / PRoot Linux）。
 */
class WorkspaceViewModel(
    context: Context,
    private val llmRepository: com.cjhsy.linkzone.data.repository.LlmRepository,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val manager = WorkspaceManager(appContext)
    private val repo = UnifiedWorkspaceRepository(manager, llmRepository)

    /** 每次按当前环境重新选择 runner（安装完成后立即生效，无需重建 VM） */
    private fun runner(): WorkspaceShellRunner = WorkspaceShellRunner.select(appContext)

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    val shellMode: String get() = manager.shellMode

    init {
        SshSessionManager.init(appContext.filesDir)
        refreshList()
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun notify(message: String?, error: String? = null) {
        _state.value = _state.value.copy(message = message, error = error)
    }

    // ---------------- Linux 运行环境（PRoot）安装 ----------------

    /** 环境安装进度（非空 = 安装中，值为提示文本）；完成/失败时回 null 并发 notify */
    private val _envInstalling = MutableStateFlow<String?>(null)
    val envInstalling: StateFlow<String?> = _envInstalling.asStateFlow()

    /** 当前环境状态（proot/rootfs 是否就绪） */
    fun envStatus(): com.cjhsy.linkzone.workspace.LinuxEnvInstaller.EnvStatus =
        com.cjhsy.linkzone.workspace.LinuxEnvInstaller.status(appContext)

    /** 一键安装：下载官方 Alpine rootfs → 解压 → 修补 → 启用 */
    fun installLinuxEnv() {
        if (_envInstalling.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _envInstalling.value = "准备安装…"
            runCatching {
                com.cjhsy.linkzone.workspace.LinuxEnvInstaller.installFromUrl(appContext) { p ->
                    _envInstalling.value = p.message
                }
            }.onSuccess {
                _envInstalling.value = null
                notify("Linux 环境安装完成（Alpine），终端已切换为完整环境")
            }.onFailure { e ->
                _envInstalling.value = null
                notify(null, "安装失败：${e.toUserMessage("网络或存储异常，可尝试手动导入 rootfs")}")
            }
        }
    }

    /** 导入 rootfs 压缩包（SAF）并解压 */
    fun importRootfsTar(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _envInstalling.value = "正在解压 rootfs（可能需要 1-3 分钟）…"
            var lastTick = 0L
            runCatching {
                com.cjhsy.linkzone.workspace.LinuxEnvInstaller.importRootfs(appContext, uri) { count, name ->
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 800) { // 节流刷新进度
                        lastTick = now
                        _envInstalling.value = "正在解压 rootfs… 已处理 $count 项（$name）"
                    }
                }
            }.onSuccess {
                _envInstalling.value = null
                notify("Linux 环境安装完成，已切换为完整环境")
            }.onFailure { e ->
                _envInstalling.value = null
                notify(null, "rootfs 安装失败：${e.toUserMessage("未知错误")}")
            }
        }
    }

    // ---------------- 工作区列表 ----------------

    fun refreshList() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.list()
            // 只对本地工作区统计文件数/体积；SSH 统计需要连远端，放到详情页按需做
            val stats = list.filter { it.kind == WorkspaceKind.LOCAL }.associate { meta ->
                val ws = manager.get(meta.id)
                meta.id to (ws?.let { manager.stats(it) } ?: WorkspaceStats(0, 0))
            }
            _state.value = _state.value.copy(workspaces = list, stats = stats)
        }
    }

    fun createWorkspace(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = repo.createLocal(name)
            notify("已创建工作区「${entry.name}」")
            refreshList()
        }
    }

    fun renameWorkspace(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = _state.value.workspaces.firstOrNull { it.id == id }
            if (entry?.isSsh == true) {
                val cfg = entry.ssh ?: return@launch
                llmRepository.upsertSshWorkspace(cfg.copy(name = name.ifBlank { cfg.summary }))
                SshSessionManager.invalidate(cfg.id)
            } else {
                manager.rename(id, name)
            }
            _state.value = _state.value.copy(
                opened = _state.value.opened?.let {
                    if (it.id == id) it.copy(name = name.ifBlank { it.name }, ssh = it.ssh?.copy(name = name)) else it
                }
            )
            notify("已重命名")
            refreshList()
        }
    }

    fun deleteWorkspace(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = _state.value.workspaces.firstOrNull { it.id == id }
            if (entry?.isSsh == true) {
                SshSessionManager.invalidate(id)
                llmRepository.deleteSshWorkspace(id)
            } else {
                manager.delete(id)
            }
            if (_state.value.opened?.id == id) {
                _state.value = _state.value.copy(opened = null, cwd = "", entries = emptyList())
            }
            notify(if (entry?.isSsh == true) "已移除 SSH 工作区" else "已删除工作区")
            refreshList()
        }
    }

    // ---------------- SSH 配置 ----------------

    fun startCreateSsh() {
        _state.value = _state.value.copy(
            editingSsh = SshWorkspaceConfig(),
            sshEditingExisting = false,
            message = null,
            error = null,
        )
    }

    fun startEditSsh(config: SshWorkspaceConfig) {
        _state.value = _state.value.copy(
            editingSsh = config,
            sshEditingExisting = true,
            message = null,
            error = null,
        )
    }

    fun cancelSshEdit() {
        _state.value = _state.value.copy(editingSsh = null, sshEditingExisting = false)
    }

    fun updateSshEditing(transform: (SshWorkspaceConfig) -> SshWorkspaceConfig) {
        _state.value = _state.value.editingSsh?.let { cur ->
            _state.value.copy(editingSsh = transform(cur))
        } ?: _state.value
    }

    fun saveSsh() {
        val current = _state.value.editingSsh ?: return
        if (current.host.isBlank() || current.username.isBlank()) {
            _state.value = _state.value.copy(error = "主机地址和用户名不能为空")
            return
        }
        if (current.authType == "password" && current.password.isBlank()) {
            _state.value = _state.value.copy(error = "密码不能为空（或改用私钥认证）")
            return
        }
        if (current.authType == "key" && current.privateKey.isBlank()) {
            _state.value = _state.value.copy(error = "私钥内容不能为空（或改用密码认证）")
            return
        }
        val editing = if (current.name.isBlank()) current.copy(name = current.summary) else current
        _state.value = _state.value.copy(sshSaving = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            llmRepository.upsertSshWorkspace(editing)
            SshSessionManager.invalidate(editing.id)
            _state.value = _state.value.copy(
                editingSsh = null,
                sshSaving = false,
                message = "已保存 SSH 工作区"
            )
            refreshList()
        }
    }

    fun testSshConnection() {
        val current = _state.value.editingSsh ?: return
        _state.value = _state.value.copy(sshTesting = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            SshSessionManager.invalidate(current.id)
            runCatching { SshSessionManager.testConnection(current) }
                .onSuccess { ms ->
                    _state.value = _state.value.copy(sshTesting = false, message = "连接成功（${ms}ms）")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        sshTesting = false,
                        error = "连接失败：${e.toUserMessage("网络或认证异常")}"
                    )
                }
        }
    }

    fun toggleSshEnabled(config: SshWorkspaceConfig, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            llmRepository.upsertSshWorkspace(config.copy(enabled = enabled))
            if (!enabled) SshSessionManager.invalidate(config.id)
            refreshList()
        }
    }

    // ---------------- 文件浏览 ----------------

    fun openWorkspace(id: String) {
        val entry = _state.value.workspaces.firstOrNull { it.id == id } ?: return
        if (entry.isSsh && !entry.enabled) {
            notify(null, "SSH 工作区已停用，请先在设置中启用")
            return
        }
        _state.value = _state.value.copy(
            opened = entry,
            cwd = "",
            tab = 0,
            entries = emptyList(),
            query = "",
            selection = emptySet(),
            commandOutput = "",
        )
        listDir("")
    }

    fun closeWorkspace() {
        stopTerminal()
        _state.value = _state.value.copy(
            opened = null, cwd = "", entries = emptyList(),
            query = "", selection = emptySet(),
        )
    }

    fun switchTab(tab: Int) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun enterDir(name: String) {
        jumpTo(joinPath(_state.value.cwd, name))
    }

    fun jumpTo(path: String) {
        // 换目录时重置过滤与多选，避免对新目录误操作
        _state.value = _state.value.copy(cwd = path, query = "", selection = emptySet())
        listDir(path)
    }

    /** 仅本地工作区可用的根目录（导入/导出/终端等本机能力） */
    private fun rootOf(): Workspace? = _state.value.opened
        ?.takeIf { it.kind == WorkspaceKind.LOCAL }
        ?.let { manager.get(it.id) }

    private fun openedBackend(): WorkspaceBackend? =
        _state.value.opened?.let { repo.backendFor(it, appContext) }

    /** 导入/导出/交互终端等本机能力仅对本地工作区开放 */
    private fun localRootOrNotify(): Workspace? {
        val ws = rootOf()
        if (ws == null) notify(null, "该操作仅支持本地工作区（SSH 请用命令台）")
        return ws
    }

    private fun listDir(cwd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend()
            if (backend == null) {
                _state.value = _state.value.copy(entries = emptyList(), dirLoading = false)
                return@launch
            }
            _state.value = _state.value.copy(dirLoading = true)
            val infos = runCatching { backend.listDir(cwd) }.getOrElse { e ->
                notify(null, "读取目录失败：${e.toUserMessage("连接或权限异常")}")
                emptyList()
            }
            val entries = infos.map {
                FileEntry(
                    name = it.name,
                    isDir = it.isDir,
                    size = it.size,
                    lastModified = it.lastModified,
                    relPath = joinPath(cwd, it.name),
                )
            }
            _state.value = _state.value.copy(entries = entries, dirLoading = false)
        }
    }

    fun refreshDir() = listDir(_state.value.cwd)

    // ---------------- 搜索 / 排序 / 视图 / 多选 ----------------

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun toggleViewMode() {
        val next = if (_state.value.viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST
        _state.value = _state.value.copy(viewMode = next)
    }

    /** 重复点同一排序项时翻转升/降序 */
    fun setSort(sortBy: FileSortBy) {
        val cur = _state.value
        _state.value = if (cur.sortBy == sortBy) cur.copy(sortAsc = !cur.sortAsc)
        else cur.copy(sortBy = sortBy, sortAsc = sortBy == FileSortBy.NAME)
    }

    fun toggleSelection(relPath: String) {
        val cur = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (relPath in cur) cur - relPath else cur + relPath
        )
    }

    /** 全选（传当前可见项，与过滤结果对齐） */
    fun selectAll(relPaths: List<String>) {
        _state.value = _state.value.copy(selection = relPaths.toSet())
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    private fun selectedEntries(): List<FileEntry> {
        val sel = _state.value.selection
        return _state.value.entries.filter { it.relPath in sel }
    }

    /** 批量删除（本地/SSH 通用） */
    fun deleteSelected() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            var ok = 0
            var fail = 0
            targets.forEach {
                if (runCatching { backend.delete(it.relPath) }.isSuccess) ok++ else fail++
            }
            clearSelection()
            notify(
                if (fail == 0) "已删除 $ok 项" else null,
                if (fail > 0) "删除完成：成功 $ok，失败 $fail" else null
            )
            listDir(_state.value.cwd)
        }
    }

    /** 批量移动到目标目录（本地/SSH 通用），targetDir 空串=工作区根 */
    fun moveSelected(targetDir: String) {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        if (targetDir == _state.value.cwd) {
            notify("已在当前目录，无需移动")
            clearSelection()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            var ok = 0
            var fail = 0
            targets.forEach { entry ->
                // 跳过「把目录移进自己/自己的子目录」
                if (entry.isDir && (targetDir == entry.relPath || targetDir.startsWith(entry.relPath + "/"))) {
                    fail++
                    return@forEach
                }
                if (runCatching { backend.move(entry.relPath, targetDir) }.isSuccess) ok++ else fail++
            }
            clearSelection()
            notify(
                if (fail == 0) "已移动 $ok 项" else null,
                if (fail > 0) "移动完成：成功 $ok，失败 $fail" else null
            )
            listDir(_state.value.cwd)
        }
    }

    /** 移动对话框用：列出 path 下的子目录（只含文件夹） */
    suspend fun listDirsFor(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val backend = openedBackend() ?: return@withContext emptyList()
        runCatching { backend.listDir(path) }.getOrDefault(emptyList())
            .filter { it.isDir }
            .map { FileEntry(it.name, true, 0L, it.lastModified, joinPath(path, it.name)) }
    }

    fun createDir(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            val ok = runCatching { backend.mkdir(joinPath(_state.value.cwd, name)) }.isSuccess
            notify(if (ok) "已创建文件夹「$name」" else null, if (ok) null else "创建失败")
            listDir(_state.value.cwd)
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            runCatching { backend.touch(joinPath(_state.value.cwd, name)) }
            notify("已创建文件「$name」")
            listDir(_state.value.cwd)
        }
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            val ok = runCatching { backend.rename(entry.relPath, newName) }.isSuccess
            notify(if (ok) "已重命名为「$newName」" else null, if (ok) null else "重命名失败")
            listDir(_state.value.cwd)
        }
    }

    fun deleteEntry(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            val ok = runCatching { backend.delete(entry.relPath) }.isSuccess
            notify(if (ok) "已删除「${entry.name}」" else null, if (ok) null else "删除失败")
            listDir(_state.value.cwd)
        }
    }

    /**
     * 读取文本文件用于预览：二进制文件返回 null 并提示；
     * 超过 256KB 截断（预览用途，编辑走命令台/AI）。
     */
    suspend fun readTextFile(entry: FileEntry): String? = withContext(Dispatchers.IO) {
        val backend = openedBackend() ?: return@withContext null
        var text = runCatching { backend.readText(entry.relPath) }.getOrElse { e ->
            notify(null, "「${entry.name}」无法预览：${e.toUserMessage("可能是二进制文件")}")
            return@withContext null
        }
        if (text.length > MAX_PREVIEW_CHARS) text = text.take(MAX_PREVIEW_CHARS) + "\n\n… [内容过长已截断]"
        text
    }

    /** 导入文件（SAF content uri）到当前目录；重名自动加序号 */
    fun importFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = localRootOrNotify() ?: return@launch
            runCatching {
                val resolver = appContext.contentResolver
                val displayName = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "imported")
                val safeName = displayName.substringAfterLast('/').ifBlank { "imported" }
                var target = File(ws.root, joinPath(_state.value.cwd, safeName))
                // 重名去重：a.txt → a(1).txt
                if (target.exists()) {
                    val dot = safeName.lastIndexOf('.')
                    val base = if (dot > 0) safeName.substring(0, dot) else safeName
                    val ext = if (dot > 0) safeName.substring(dot) else ""
                    var i = 1
                    while (target.exists()) {
                        target = File(ws.root, joinPath(_state.value.cwd, "$base($i)$ext"))
                        i++
                    }
                }
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                notify("已导入「${target.name}」")
            }.onFailure { e ->
                notify(null, "导入失败：${e.toUserMessage("未知错误")}")
            }
            listDir(_state.value.cwd)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    /** 分享：读取原始字节到 cacheDir/exports 再走系统分享（本地/SSH 通用，FileProvider 仅开放 cache 目录） */
    fun exportFile(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            runCatching {
                val bytes = backend.readBytes(entry.relPath)
                val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                val dst = File(dir, "${entry.name}_${System.currentTimeMillis()}")
                dst.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", dst)
                val mime = guessMime(entry.name)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    appContext.startActivity(Intent.createChooser(intent, "导出「${entry.name}」"))
                }
            }.onFailure { e ->
                notify(null, "导出失败：${e.toUserMessage("未知错误")}")
            }
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    // ---------------- SAF 导出到用户自选目录 ----------------

    /** 当前打开工作区的根路径（本地绝对路径 / SSH host:port · workDir） */
    fun currentRootPath(): String? = openedBackend()?.rootLabel

    /**
     * 下载单个文件到系统下载目录（Download/LinkZone/Workspace/），本地/SSH 通用。
     * 与聊天里的媒体/代码下载一致：走 MediaStore 直接落盘，不弹目录选择器。
     */
    fun downloadEntry(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            runCatching {
                require(!entry.isDir) { "暂不支持下载文件夹" }
                val bytes = backend.readBytes(entry.relPath)
                saveToDownloads(entry.name, bytes)
                notify("已保存到 $DOWNLOAD_DIR_LABEL/${entry.name}")
            }.onFailure { e -> notify(null, "下载失败：${e.toUserMessage("未知错误")}") }
        }
    }

    /** 批量下载所选文件（跳过文件夹）到系统下载目录，本地/SSH 通用 */
    fun downloadSelected() {
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            val files = selectedEntries().filter { !it.isDir }
            if (files.isEmpty()) {
                notify(null, "所选内容中没有可下载的文件（不支持下载文件夹）")
                return@launch
            }
            var ok = 0
            var fail = 0
            files.forEach { entry ->
                runCatching { saveToDownloads(entry.name, backend.readBytes(entry.relPath)) }
                    .onSuccess { ok++ }
                    .onFailure { fail++ }
            }
            clearSelection()
            notify(
                if (fail == 0) "已下载 $ok 个文件到 $DOWNLOAD_DIR_LABEL" else null,
                if (fail > 0) "下载完成：成功 $ok，失败 $fail" else null
            )
        }
    }

    /**
     * 写入系统下载目录 Download/LinkZone/Workspace/（targetSdk 29+ 无需存储权限）。
     * 同名文件由 MediaStore 自动追加序号，不会覆盖已有文件。
     */
    private fun saveToDownloads(name: String, bytes: ByteArray) {
        val resolver = appContext.contentResolver
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOAD_DIR_LABEL)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录创建文件")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入目标文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    /**
     * 把整个工作区（含子目录结构）导出到 SAF 选定目录下的同名文件夹。
     * workspace.json 等内部文件同样保留，方便整体备份/迁移。
     */
    fun exportWholeWorkspaceToTree(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = localRootOrNotify() ?: return@launch
            runCatching {
                val resolver = appContext.contentResolver
                val treeRoot = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                // 目标目录：所选目录/<工作区名>
                val folderUri = DocumentsContract.createDocument(
                    resolver, treeRoot, DocumentsContract.Document.MIME_TYPE_DIR,
                    uniqueChildName(resolver, treeUri, ws.name)
                ) ?: error("无法创建目标文件夹")
                var count = 0
                fun copyDir(srcDir: File, dirUri: Uri) {
                    srcDir.listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
                        if (f.isDirectory) {
                            val sub = DocumentsContract.createDocument(
                                resolver, dirUri, DocumentsContract.Document.MIME_TYPE_DIR, f.name
                            ) ?: return@forEach
                            copyDir(f, sub)
                        } else {
                            val doc = DocumentsContract.createDocument(
                                resolver, dirUri, guessMime(f.name), f.name
                            ) ?: return@forEach
                            resolver.openOutputStream(doc)?.use { out ->
                                f.inputStream().use { it.copyTo(out) }
                            }
                            count++
                        }
                    }
                }
                copyDir(ws.root, folderUri)
                notify("已导出整个工作区（$count 个文件）到所选目录")
            }.onFailure { e -> notify(null, "导出失败：${e.toUserMessage("未知错误")}") }
        }
    }

    /** 目标目录重名时追加序号 */
    private fun uniqueChildName(resolver: android.content.ContentResolver, parentTreeUri: Uri, name: String): String {
        var candidate = name
        var i = 1
        while (true) {
            val exists = runCatching {
                resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                        parentTreeUri, DocumentsContract.getTreeDocumentId(parentTreeUri)
                    ),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    "${DocumentsContract.Document.COLUMN_DISPLAY_NAME}=?",
                    arrayOf(candidate),
                    null
                )?.use { it.moveToFirst() } ?: false
            }.getOrDefault(false)
            if (!exists) return candidate
            candidate = "$name($i)"
            i++
        }
    }

    // ---------------- 交互式终端（PTY） ----------------

    private var session: PtySession? = null
    private var screenJob: Job? = null
    private val _termScreen = MutableStateFlow<TermScreen?>(null)
    val termScreen: StateFlow<TermScreen?> = _termScreen.asStateFlow()
    private val _termExit = MutableStateFlow<Int?>(null)
    val termExit: StateFlow<Int?> = _termExit.asStateFlow()
    private val _termScroll = MutableStateFlow(0)
    val termScroll: StateFlow<Int> = _termScroll.asStateFlow()
    private val _termFontSp = MutableStateFlow(12)
    val termFontSp: StateFlow<Int> = _termFontSp.asStateFlow()

    /** 打开当前工作区的交互会话；已存在则复用，restart=true 时重启 */
    fun startTerminal(restart: Boolean = false) {
        val ws = rootOf() ?: run {
            notify(null, "交互式终端仅支持本地工作区，SSH 请使用命令台")
            return
        }
        if (!restart && session != null) return
        stopTerminal()
        val s = TerminalLauncher.create(appContext, ws.root)
        _termExit.value = null
        _termScreen.value = null
        _termScroll.value = 0
        if (!s.start()) {
            notify(null, "终端启动失败：无法创建 PTY")
            return
        }
        session = s
        screenJob = viewModelScope.launch {
            launch { s.screen.collect { _termScreen.value = it } }
            launch { s.exitCode.collect { _termExit.value = it } }
            launch { s.scroll.collect { _termScroll.value = it } }
        }
    }

    fun stopTerminal() {
        screenJob?.cancel()
        screenJob = null
        session?.close()
        session = null
    }

    /** 发送一整行（附带回车） */
    fun sendLine(text: String) {
        session?.submit(text)
    }

    /** 发送原始按键序列（方向键、Ctrl 组合等） */
    fun sendRaw(text: String) {
        session?.write(text)
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        session?.resize(cols, rows)
    }

    fun interruptTerminal() {
        session?.interrupt()
    }

    fun clearTerminal() {
        session?.clear()
    }

    /** 手指上下滑动翻历史（正数向上翻） */
    fun scrollTerminal(lines: Int) {
        session?.scrollBy(lines)
    }

    fun scrollTerminalToBottom() {
        if (_termScroll.value == 0) return
        session?.scrollToBottom()
    }

    fun zoomTerminal(delta: Int) {
        _termFontSp.value = (_termFontSp.value + delta).coerceIn(9, 20)
    }

    // ---------------- SSH 命令台 ----------------

    /** 在 SSH 工作区执行单次命令（不做 PTY 交互，输出回填到面板） */
    fun runCommand(command: String) {
        if (command.isBlank() || _state.value.commandRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            val backend = openedBackend() ?: return@launch
            _state.value = _state.value.copy(commandRunning = true)
            val timeout = minOf(backend.maxTimeoutMs, 120_000L)
            val output = runCatching { backend.exec(command, "", timeout) }
                .fold(
                    onSuccess = { res ->
                        buildString {
                            if (res.stdout.isNotBlank()) append(res.stdout.trimEnd())
                            if (res.stderr.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(res.stderr.trimEnd())
                            }
                            if (res.timedOut) append("\n[超时]")
                            else append("\n[退出码 ${res.exitCode}]")
                        }
                    },
                    onFailure = { e -> "执行失败：${e.toUserMessage("连接异常")}" }
                )
            _state.value = _state.value.copy(commandRunning = false, commandOutput = output)
        }
    }

    fun clearCommandOutput() {
        _state.value = _state.value.copy(commandOutput = "")
    }

    override fun onCleared() {
        // 页面销毁时兜底回收 pty 子进程，避免 shell/proot 泄漏
        stopTerminal()
        super.onCleared()
    }

    companion object {
        const val MAX_PREVIEW_CHARS = 256 * 1024
    }
}

/** 工作区文件下载落盘目录（与聊天媒体下载同一套 LinkZone 分类目录） */
private const val DOWNLOAD_DIR_LABEL = "Download/LinkZone/Workspace"

/** 拼接相对路径（cwd 为空串时直接返回 name） */
private fun joinPath(cwd: String, name: String): String =
    if (cwd.isBlank()) name else "$cwd/$name"

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
fun WorkspaceScreen(
    onBack: () -> Unit
) {
    val viewModel: WorkspaceViewModel = org.koin.androidx.compose.koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 系统返回：先退出 SSH 编辑页/工作区详情，而不是直接把整页弹掉
    BackHandler(enabled = state.opened != null || state.editingSsh != null) {
        if (state.editingSsh != null) viewModel.cancelSshEdit() else viewModel.closeWorkspace()
    }

    // Toast 提示
    state.message?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }
    state.error?.let {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        viewModel.consumeNotice()
    }

    val editingSsh = state.editingSsh
    if (editingSsh != null) {
        SshEditorPane(
            config = editingSsh,
            existing = state.sshEditingExisting,
            state = state,
            viewModel = viewModel,
        )
        return
    }

    if (state.opened == null) {
        val envInstalling by viewModel.envInstalling.collectAsStateWithLifecycle()
        val env = viewModel.envStatus()
        WorkspaceListPane(
            state = state,
            shellMode = viewModel.shellMode,
            env = env,
            envInstalling = envInstalling,
            onImportRootfs = { uri -> viewModel.importRootfsTar(uri) },
            onInstall = viewModel::installLinuxEnv,
            onBack = onBack,
            onOpen = viewModel::openWorkspace,
            onCreate = viewModel::createWorkspace,
            onCreateSsh = viewModel::startCreateSsh,
            onEditSsh = viewModel::startEditSsh,
            onRename = viewModel::renameWorkspace,
            onDelete = viewModel::deleteWorkspace
        )
    } else {
        WorkspaceDetailPane(
            state = state,
            viewModel = viewModel
        )
    }
}

// ---------------- 列表态 ----------------

@Composable
private fun WorkspaceListPane(
    state: WorkspaceUiState,
    shellMode: String,
    env: com.cjhsy.linkzone.workspace.LinuxEnvInstaller.EnvStatus,
    envInstalling: String?,
    onImportRootfs: (android.net.Uri) -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onCreateSsh: () -> Unit,
    onEditSsh: (SshWorkspaceConfig) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showCreateType by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceEntry?>(null) }

    // 手动导入 rootfs 压缩包（SAF）
    val rootfsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportRootfs)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
    ) {
        // 顶栏：返回 + 标题 + 新建
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "代码工作区",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showCreateType = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建工作区")
            }
        }

        // shell 模式说明
        Text(
            text = "本地 Shell：$shellMode · SSH 工作区使用远端登录 shell · AI 在两种工作区中使用同一套工具",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        // Linux 运行环境卡：展示当前环境 + 安装完整环境入口
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = if (env.usingProot) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (env.usingProot) "运行环境：完整 Linux（PRoot）"
                        else "运行环境：精简模式（系统 toybox）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (env.usingProot)
                        "已就绪：apk/python/node 等 rootfs 内工具均可在终端使用"
                    else
                        "精简模式仅含 ls/cat/sed 等基础命令。一键安装 Alpine（≈4MB）即可获得完整 Linux 环境",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!env.usingProot) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onInstall,
                        enabled = envInstalling == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (envInstalling != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (env.rootfsReady) "重新安装 Linux 环境" else "一键安装 Linux 环境")
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { rootfsLauncher.launch(arrayOf("*/*")) },
                            enabled = envInstalling == null
                        ) {
                            Text("手动导入 rootfs", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (envInstalling != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            envInstalling,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.workspaces.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有工作区", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "点击右上角 + 新建，绑定给智能体后即可使用文件与命令能力",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.workspaces, key = { it.id }) { ws ->
                    val stats = state.stats[ws.id]
                    var menuOpen by remember { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(ws.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (ws.isSsh) Icons.Filled.Terminal else Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ws.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (ws.isSsh) {
                                        "SSH · ${ws.rootPath}" + if (!ws.enabled) " · 已停用" else ""
                                    } else {
                                        "${stats?.fileCount ?: 0} 个文件 · ${formatBytes(stats?.totalBytes ?: 0)} · " +
                                            formatDate(ws.createdAt)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!ws.enabled) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    if (ws.isSsh) {
                                        DropdownMenuItem(
                                            text = { Text(if (ws.enabled) "设置" else "设置 · 已停用") },
                                            onClick = { menuOpen = false; ws.ssh?.let(onEditSsh) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = { menuOpen = false; renaming = ws }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (ws.isSsh) "移除" else "删除",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = { menuOpen = false; deleting = ws }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateType) {
        AlertDialog(
            onDismissRequest = { showCreateType = false },
            title = { Text("新建工作区") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CreateTypeRow(
                        icon = Icons.Filled.Folder,
                        title = "本地工作区",
                        desc = "App 私有目录，支持导入导出与交互式终端",
                        onClick = { showCreateType = false; showCreate = true }
                    )
                    CreateTypeRow(
                        icon = Icons.Filled.Terminal,
                        title = "SSH 工作区",
                        desc = "连接远端主机，浏览文件并执行命令",
                        onClick = { showCreateType = false; onCreateSsh() }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCreateType = false }) { Text("取消") }
            }
        )
    }

    if (showCreate) {
        NameInputSheet(
            title = "新建工作区",
            placeholder = "工作区名称",
            onConfirm = { name ->
                showCreate = false
                onCreate(name)
            },
            onDismiss = { showCreate = false }
        )
    }

    renaming?.let { ws ->
        NameInputSheet(
            title = "重命名工作区",
            initial = ws.name,
            placeholder = "工作区名称",
            onConfirm = { name ->
                onRename(ws.id, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { ws ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (ws.isSsh) "移除 SSH 工作区" else "删除工作区") },
            text = {
                Text(
                    if (ws.isSsh) "将移除「${ws.name}」的连接配置（不影响远端文件）。"
                    else "将永久删除「${ws.name}」及其全部文件，此操作不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(ws.id); deleting = null }) {
                    Text(if (ws.isSsh) "移除" else "删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

/** 「新建工作区」类型选择行 */
@Composable
private fun CreateTypeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------- 详情态 ----------------

@Composable
private fun WorkspaceDetailPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel
) {
    val ws = state.opened ?: return
    val stats = state.stats[ws.id]
    var showRenameWs by remember { mutableStateOf(false) }
    var showDeleteWs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rootPath = remember(ws.id) { viewModel.currentRootPath() }

    // 导出整个工作区到用户自选目录（SAF OpenDocumentTree）
    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.exportWholeWorkspaceToTree(treeUri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::closeWorkspace) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ws.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (ws.isSsh) "SSH · ${ws.ssh?.summary.orEmpty()}"
                    else "${stats?.fileCount ?: 0} 个文件 · ${formatBytes(stats?.totalBytes ?: 0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 真实路径：本地为落盘目录，SSH 为 host:port · workDir；点击复制
                if (rootPath != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("workspace_path", rootPath))
                                Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                            }
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (ws.isSsh) Icons.Filled.Terminal else Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            rootPath,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            ),
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (ws.isSsh) {
                IconButton(onClick = { ws.ssh?.let(viewModel::startEditSsh) }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "SSH 设置",
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(onClick = { exportAllLauncher.launch(null) }) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "导出整个工作区到所选文件夹",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = { showRenameWs = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "重命名工作区", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { showDeleteWs = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除工作区",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Tab 切换 pill
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(3.dp)
        ) {
            TabPill("文件", Icons.Filled.Folder, state.tab == 0, Modifier.weight(1f)) { viewModel.switchTab(0) }
            TabPill(
                if (ws.isSsh) "命令" else "终端",
                Icons.Filled.Terminal,
                state.tab == 1,
                Modifier.weight(1f)
            ) { viewModel.switchTab(1) }
        }

        when {
            state.tab == 0 -> FilesPane(state, viewModel)
            ws.isSsh -> SshCommandPane(state, viewModel)
            else -> TerminalPane(state, viewModel)
        }
    }

    if (showRenameWs) {
        NameInputSheet(
            title = "重命名工作区",
            initial = ws.name,
            placeholder = "工作区名称",
            onConfirm = { name ->
                viewModel.renameWorkspace(ws.id, name)
                showRenameWs = false
            },
            onDismiss = { showRenameWs = false }
        )
    }

    if (showDeleteWs) {
        AlertDialog(
            onDismissRequest = { showDeleteWs = false },
            title = { Text(if (ws.isSsh) "移除 SSH 工作区" else "删除工作区") },
            text = {
                Text(
                    if (ws.isSsh) "将移除「${ws.name}」的连接配置（不影响远端文件）。"
                    else "将永久删除「${ws.name}」及其全部文件，此操作不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWorkspace(ws.id)
                    showDeleteWs = false
                }) { Text(if (ws.isSsh) "移除" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWs = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------- SSH 配置编辑 ----------------

@Composable
private fun SshEditorPane(
    config: SshWorkspaceConfig,
    existing: Boolean,
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel
) {
    var showRemove by remember { mutableStateOf(false) }
    val upd: ((SshWorkspaceConfig) -> SshWorkspaceConfig) -> Unit = viewModel::updateSshEditing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::cancelSshEdit) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                if (existing) "SSH 工作区设置" else "新建 SSH 工作区",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = viewModel::saveSsh, enabled = !state.sshSaving) {
                if (state.sshSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("保存")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = config.name,
                onValueChange = { v -> upd { it.copy(name = v) } },
                label = { Text("名称（可留空）") },
                placeholder = { Text(config.summary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = config.host,
                onValueChange = { v -> upd { it.copy(host = v.trim()) } },
                label = { Text("主机地址") },
                placeholder = { Text("192.168.1.10 或 example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = config.port.toString(),
                    onValueChange = { v ->
                        v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { p ->
                            if (p in 1..65535) upd { it.copy(port = p) }
                        }
                    },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.width(110.dp)
                )
                OutlinedTextField(
                    value = config.username,
                    onValueChange = { v -> upd { it.copy(username = v.trim()) } },
                    label = { Text("用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
            }

            Text("认证方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.authType == "password",
                    onClick = { upd { it.copy(authType = "password") } },
                    label = { Text("密码") }
                )
                FilterChip(
                    selected = config.authType == "key",
                    onClick = { upd { it.copy(authType = "key") } },
                    label = { Text("私钥") }
                )
            }

            if (config.authType == "password") {
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { v -> upd { it.copy(password = v) } },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = config.privateKey,
                    onValueChange = { v -> upd { it.copy(privateKey = v) } },
                    label = { Text("私钥（PEM 文本）") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 5,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = config.keyPassphrase,
                    onValueChange = { v -> upd { it.copy(keyPassphrase = v) } },
                    label = { Text("私钥口令（可留空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = config.workDir,
                onValueChange = { v -> upd { it.copy(workDir = v) } },
                label = { Text("远端工作目录") },
                placeholder = { Text("~ 或 /home/user/project") },
                singleLine = true,
                supportingText = { Text("文件工具的相对路径与命令都在此目录执行") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "停用后不会出现在智能体可选列表中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { v -> upd { it.copy(enabled = v) } }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::testSshConnection,
                    enabled = !state.sshTesting && config.host.isNotBlank() && config.username.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.sshTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.sshTesting) "测试中…" else "测试连接")
                }
            }

            if (existing) {
                TextButton(onClick = { showRemove = true }) {
                    Text("移除该 SSH 工作区", color = MaterialTheme.colorScheme.error)
                }
            }

            Text(
                "说明：SSH 工作区提供远端文件浏览与单次命令执行；交互式终端仅本地工作区支持。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("移除 SSH 工作区") },
            text = { Text("将移除「${config.name.ifBlank { config.summary }}」的连接配置（不影响远端文件）。") },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    viewModel.cancelSshEdit()
                    viewModel.deleteWorkspace(config.id)
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text("取消") }
            }
        )
    }
}

// ---------------- 文件面板 ----------------

/** 按扩展名挑选图标：让列表/网格一眼区分文件类型 */
private fun fileTypeIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> Icons.Filled.Image
    "mp3", "wav", "ogg", "flac", "m4a" -> Icons.Filled.AudioFile
    "mp4", "mkv", "avi", "mov", "webm" -> Icons.Filled.Movie
    "zip", "tar", "gz", "tgz", "7z", "rar", "jar", "apk" -> Icons.Filled.FolderZip
    "pdf" -> Icons.Filled.PictureAsPdf
    "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh",
    "c", "cpp", "h", "go", "rs", "md", "yml", "yaml", "toml", "sql", "gradle",
    "properties", "conf", "ini", "lua", "rb", "php" -> Icons.Filled.Code
    else -> Icons.Filled.Description
}

@Composable
private fun FilesPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel
) {
    var viewing by remember { mutableStateOf<Pair<FileEntry, String>?>(null) }
    var showNewDir by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    // 条目动作底部弹层的目标（点列表 … 或网格 ⋮ 打开）
    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 导入依赖本机 SAF，SSH 工作区不提供
    val isSsh = state.opened?.isSsh == true
    val selecting = state.selection.isNotEmpty()

    // 多选模式下系统返回键 = 退出多选，而不是退出工作区
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importFile)
    }

    // 过滤 + 排序（文件夹始终在前）
    val visible = remember(state.entries, state.query, state.sortBy, state.sortAsc) {
        val q = state.query.trim().lowercase()
        val filtered = if (q.isEmpty()) state.entries
        else state.entries.filter { it.name.lowercase().contains(q) }
        val cmp: Comparator<FileEntry> = when (state.sortBy) {
            FileSortBy.NAME -> compareBy { it.name.lowercase() }
            FileSortBy.SIZE -> compareBy { it.size }
            FileSortBy.TIME -> compareBy { it.lastModified }
        }
        filtered.sortedWith(
            compareByDescending<FileEntry> { it.isDir }
                .then(if (state.sortAsc) cmp else cmp.reversed())
        )
    }

    val openEntry: (FileEntry) -> Unit = { entry ->
        if (entry.isDir) viewModel.enterDir(entry.name)
        else scope.launch {
            viewModel.readTextFile(entry)?.let { viewing = entry to it }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionTopBar(
                    count = state.selection.size,
                    total = visible.size,
                    onSelectAll = { viewModel.selectAll(visible.map { it.relPath }) },
                    onClear = viewModel::clearSelection,
                )
            } else {
                BreadcrumbRow(
                    cwd = state.cwd,
                    onJump = viewModel::jumpTo,
                    onRefresh = viewModel::refreshDir,
                )
                FileToolRow(
                    state = state,
                    isSsh = isSsh,
                    onQuery = viewModel::setQuery,
                    onSort = viewModel::setSort,
                    onToggleView = viewModel::toggleViewMode,
                    onNewDir = { showNewDir = true },
                    onNewFile = { showNewFile = true },
                    onImport = { importLauncher.launch("*/*") },
                )
            }

            if (state.dirLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 多选时给底部操作栏留出空间
            val bottomPad = if (selecting) 76.dp else 4.dp
            when {
                state.entries.isEmpty() && state.dirLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }
                state.entries.isEmpty() -> EmptyHint(
                    if (isSsh) "空目录 · 点右上角 + 新建，或切到「命令」在远端终端创建"
                    else "空目录 · 点右上角 + 导入或新建"
                )
                visible.isEmpty() -> EmptyHint("没有匹配「${state.query.trim()}」的条目")
                state.viewMode == FileViewMode.LIST -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPad)
                ) {
                    items(visible, key = { it.relPath }) { entry ->
                        FileListRow(
                            entry = entry,
                            selecting = selecting,
                            selected = entry.relPath in state.selection,
                            onOpen = { openEntry(entry) },
                            onToggleSelect = { viewModel.toggleSelection(entry.relPath) },
                            onMore = { actionEntry = entry },
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPad),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    gridItems(visible, key = { it.relPath }) { entry ->
                        FileGridCell(
                            entry = entry,
                            selecting = selecting,
                            selected = entry.relPath in state.selection,
                            onOpen = { openEntry(entry) },
                            onToggleSelect = { viewModel.toggleSelection(entry.relPath) },
                            onMore = { actionEntry = entry },
                        )
                    }
                }
            }
        }

        if (selecting) {
            SelectionActionBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onMove = { showMove = true },
                onDownload = viewModel::downloadSelected,
                onDelete = { confirmBatchDelete = true },
            )
        }
    }

    // 文件查看器
    viewing?.let { (entry, content) ->
        FileViewerDialog(
            title = entry.name,
            content = content,
            onDismiss = { viewing = null }
        )
    }

    // 条目动作底部弹层（MT 管理器风格）：查看/下载/分享/选择/重命名/删除
    actionEntry?.let { entry ->
        EntryActionSheet(
            entry = entry,
            onDismiss = { actionEntry = null },
            onView = {
                scope.launch {
                    viewModel.readTextFile(entry)?.let { viewing = entry to it }
                }
            },
            onDownload = { viewModel.downloadEntry(entry) },
            onShare = { viewModel.exportFile(entry) },
            onSelect = { viewModel.toggleSelection(entry.relPath) },
            onRename = { renaming = entry },
            onDelete = { deleting = entry },
        )
    }

    if (showNewDir) {
        NameInputSheet(
            title = "新建文件夹",
            placeholder = "文件夹名称",
            onConfirm = { name -> showNewDir = false; viewModel.createDir(name) },
            onDismiss = { showNewDir = false }
        )
    }

    if (showNewFile) {
        NameInputSheet(
            title = "新建文件",
            placeholder = "文件名（可含扩展名）",
            onConfirm = { name -> showNewFile = false; viewModel.createFile(name) },
            onDismiss = { showNewFile = false }
        )
    }

    renaming?.let { entry ->
        NameInputSheet(
            title = "重命名",
            initial = entry.name,
            placeholder = "新名称",
            onConfirm = { name ->
                renaming = null
                viewModel.renameEntry(entry, name)
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { entry ->
        DeleteConfirmSheet(
            text = "删除「${entry.name}」${if (entry.isDir) "及其全部内容" else ""}？此操作不可恢复。",
            onConfirm = {
                viewModel.deleteEntry(entry)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }

    if (confirmBatchDelete) {
        DeleteConfirmSheet(
            text = "删除已选的 ${state.selection.size} 项？文件夹会连同其全部内容一起删除，此操作不可恢复。",
            onConfirm = {
                confirmBatchDelete = false
                viewModel.deleteSelected()
            },
            onDismiss = { confirmBatchDelete = false }
        )
    }

    if (showMove) {
        MoveTargetSheet(
            initialPath = state.cwd,
            selection = state.selection,
            viewModel = viewModel,
            onConfirm = { target ->
                showMove = false
                viewModel.moveSelected(target)
            },
            onDismiss = { showMove = false },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 多选模式顶栏：关闭 + 已选计数 + 全选 */
@Composable
private fun SelectionTopBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选", modifier = Modifier.size(20.dp))
            }
            Text(
                "已选 $count 项",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll, enabled = count < total) {
                Text("全选", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 工具行：搜索框 + 排序 + 列表/网格切换 + 新建/导入 */
@Composable
private fun FileToolRow(
    state: WorkspaceUiState,
    isSsh: Boolean,
    onQuery: (String) -> Unit,
    onSort: (FileSortBy) -> Unit,
    onToggleView: () -> Unit,
    onNewDir: () -> Unit,
    onNewFile: () -> Unit,
    onImport: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            placeholder = { Text("搜索当前目录", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空搜索", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Box {
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.Filled.Sort, contentDescription = "排序", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortMenuItem("名称", FileSortBy.NAME, state.sortBy, state.sortAsc) {
                    onSort(FileSortBy.NAME); sortMenu = false
                }
                SortMenuItem("大小", FileSortBy.SIZE, state.sortBy, state.sortAsc) {
                    onSort(FileSortBy.SIZE); sortMenu = false
                }
                SortMenuItem("修改时间", FileSortBy.TIME, state.sortBy, state.sortAsc) {
                    onSort(FileSortBy.TIME); sortMenu = false
                }
            }
        }
        IconButton(onClick = onToggleView) {
            Icon(
                if (state.viewMode == FileViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList,
                contentDescription = if (state.viewMode == FileViewMode.LIST) "切换网格视图" else "切换列表视图",
                modifier = Modifier.size(20.dp)
            )
        }
        Box {
            IconButton(onClick = { addMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建/导入", modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("新建文件夹") },
                    leadingIcon = {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    onClick = { addMenu = false; onNewDir() }
                )
                DropdownMenuItem(
                    text = { Text("新建文件") },
                    leadingIcon = {
                        Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    onClick = { addMenu = false; onNewFile() }
                )
                if (!isSsh) {
                    DropdownMenuItem(
                        text = { Text("导入文件") },
                        leadingIcon = {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = { addMenu = false; onImport() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    target: FileSortBy,
    current: FileSortBy,
    asc: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                if (target == current) {
                    Icon(
                        if (asc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick
    )
}

/** 列表模式行：点击打开，长按进入多选，「…」弹底部动作面板 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    entry: FileEntry,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = { if (!selecting) onToggleSelect() },
            )
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDir) Icons.Filled.Folder else fileTypeIcon(entry.name),
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (entry.isDir) "文件夹 · ${formatDate(entry.lastModified)}"
                else "${formatBytes(entry.size)} · ${formatDate(entry.lastModified)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        } else {
            IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 网格模式单元格：点击打开，长按多选，右上角「⋮」弹底部动作面板 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: FileEntry,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onMore: () -> Unit,
) {
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                )
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelect() else onOpen() },
                    onLongClick = { if (!selecting) onToggleSelect() },
                )
                .padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else fileTypeIcon(entry.name),
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                if (entry.isDir) "文件夹" else formatBytes(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        } else {
            IconButton(onClick = onMore, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 多选模式底部操作栏：移动 / 下载到手机 / 删除（本地与 SSH 通用） */
@Composable
private fun SelectionActionBar(
    modifier: Modifier = Modifier,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BatchAction(Icons.Filled.DriveFileMove, "移动", MaterialTheme.colorScheme.primary, onMove)
            BatchAction(Icons.Filled.Download, "下载", MaterialTheme.colorScheme.primary, onDownload)
            BatchAction(Icons.Filled.Delete, "删除", MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun BatchAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** 批量移动目标目录选择（底部弹层）：从当前目录出发逐级浏览，已选目录不可进入（防止移进自己） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTargetSheet(
    initialPath: String,
    selection: Set<String>,
    viewModel: WorkspaceViewModel,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(initialPath) }
    var dirs by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(path) {
        dirs = null
        dirs = viewModel.listDirsFor(path)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "移动到…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (path.isBlank()) "根目录" else path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp
                )
                val current = dirs
                when {
                    current == null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        if (path.isNotBlank()) {
                            item(key = "__up__") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { path = path.substringBeforeLast('/', "") }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "上一级",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        items(current, key = { it.relPath }) { dir ->
                            val blocked = dir.relPath in selection
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !blocked) { path = dir.relPath }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (blocked) MaterialTheme.colorScheme.outlineVariant
                                    else MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (blocked) MaterialTheme.colorScheme.outlineVariant
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (current.isEmpty()) {
                            item(key = "__empty__") {
                                Text(
                                    "没有子文件夹",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { onConfirm(path) }, modifier = Modifier.weight(1f)) { Text("移动到此处") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 面包屑：根 / a / b，可横向滑动，每段可点击跳转；尾部刷新按钮 */
@Composable
private fun BreadcrumbRow(
    cwd: String,
    onJump: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val segments = remember(cwd) { cwd.split('/').filter { it.isNotBlank() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BreadcrumbChip("根目录", cwd.isBlank()) { if (cwd.isNotBlank()) onJump("") }
            segments.forEachIndexed { idx, seg ->
                Text(
                    "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                val target = segments.take(idx + 1).joinToString("/")
                BreadcrumbChip(seg, idx == segments.lastIndex) { if (target != cwd) onJump(target) }
            }
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新目录",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BreadcrumbChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

/** 文件查看器：全屏 Dialog + 等宽字体逐行渲染 */
@Composable
private fun FileViewerDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 40.dp, horizontal = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    // 占位保持标题居中
                    Spacer(Modifier.width(64.dp))
                }
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp
                )
                val lines = remember(content) { content.lines() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

// ---------------- SSH 命令面板 ----------------

@Composable
private fun SshCommandPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel
) {
    var command by remember { mutableStateOf("") }
    val outputScroll = rememberScrollState()
    val run = { if (command.isNotBlank() && !state.commandRunning) viewModel.runCommand(command) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("输入命令，如 ls -la 或 git status") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { run() }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = run,
                enabled = !state.commandRunning && command.isNotBlank()
            ) {
                if (state.commandRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "运行", modifier = Modifier.size(18.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "在远端登录 shell 中执行，工作目录为配置的远端目录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (state.commandOutput.isNotEmpty()) {
                TextButton(onClick = viewModel::clearCommandOutput) {
                    Text("清空", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ) {
            if (state.commandOutput.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.commandRunning) "执行中…" else "命令输出会显示在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(outputScroll)
                            .padding(12.dp)
                    ) {
                        Text(
                            state.commandOutput,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 终端面板 ----------------

@Composable
private fun TerminalPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel
) {
    val screen by viewModel.termScreen.collectAsState()
    val exit by viewModel.termExit.collectAsState()
    val scrolled by viewModel.termScroll.collectAsState()
    val fontSp by viewModel.termFontSp.collectAsState()
    val context = LocalContext.current

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    // 不可见输入框的镜像文本：只用于逐键 diff，终端自身负责回显
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var composingBase by remember { mutableStateOf<String?>(null) }
    var ctrlLatch by remember { mutableStateOf(false) }
    var altLatch by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    fun resetMirror() {
        field = TextFieldValue("")
        composingBase = null
    }

    // 回车统一出口 + 去抖：onSend 回调与 IME 插入 \n 可能在同一次按键里都触发，
    // 150ms 内的第二次 CR 视为重复事件丢弃
    var lastCrAt = 0L
    fun emitCr() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCrAt < 150) return
        lastCrAt = now
        viewModel.scrollTerminalToBottom()
        viewModel.sendRaw("\r")
        resetMirror()
    }

    /** 按键出口：应用 Ctrl/Alt 锁存后写入 pty */
    fun emit(text: String, clearMirror: Boolean = false) {
        if (text.isEmpty()) return
        var payload = text
        if (ctrlLatch) {
            ctrlLatch = false
            if (text.length == 1) {
                val c = text[0].uppercaseChar()
                payload = when {
                    c in 'A'..'Z' -> (c - 'A' + 1).toChar().toString()
                    c == ' ' || c == '@' -> "\u0000"
                    c == '[' -> "\u001b"
                    c == '\\' -> "\u001c"
                    c == ']' -> "\u001d"
                    c == '^' || c == '_' -> "\u001f"
                    else -> text
                }
            }
        } else if (altLatch) {
            altLatch = false
            payload = "\u001b$text"
        }
        viewModel.scrollTerminalToBottom()
        viewModel.sendRaw(payload)
        if (clearMirror) resetMirror()
    }

    /**
     * 键盘输入：逐键 diff 直送。
     * 输入法组合态（拼音未上屏）不发送，上屏后整段发送，中文也能正常输入。
     */
    fun handleChange(next: TextFieldValue) {
        if (next.composition != null) {
            if (composingBase == null) composingBase = field.text
            field = next
            return
        }
        val base = composingBase ?: field.text
        composingBase = null
        val curr = next.text
        val common = commonPrefixLength(base, curr)
        if (common < base.length) {
            repeat(base.length - common) { viewModel.sendRaw("\u007f") }
        }
        val added = curr.substring(common)
        if (added.isNotEmpty()) {
            // 部分输入法的回车会往文本里塞 \n（同时还回调 onSend）：换行单独走 emitCr 去抖，
            // 否则一次回车被 pty 收到两次 CR，表现为执行后多空一行
            val parts = added.split('\n', '\r')
            parts.forEachIndexed { i, part ->
                if (i > 0) emitCr()
                if (part.isNotEmpty()) emit(part)
            }
        }
        // 必须保留 IME 给的光标位置：TextFieldValue(text) 会把光标重置到开头，
        // 下一个字符会插到最前面并触发"删除重发"，输入 top 会变成 pot
        field = if (curr.length > 256) TextFieldValue("") else next
    }

    fun copyScreen() {
        val snapshot = screen ?: return
        val text = screenText(snapshot)
        if (text.isEmpty()) return
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
    }

    fun paste() {
        val text = clipboard.getText()?.text.orEmpty()
        if (text.isBlank()) return
        val payload = text.replace("\r\n", "\r").replace('\n', '\r').take(16384)
        viewModel.scrollTerminalToBottom()
        viewModel.sendRaw(payload)
        resetMirror()
    }

    LaunchedEffect(Unit) {
        viewModel.startTerminal()
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF12141A),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Box(Modifier.fillMaxSize()) {
                val snapshot = screen
                if (snapshot == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    TerminalScreenView(
                        screen = snapshot,
                        fontSize = fontSp.sp,
                        onResize = viewModel::resizeTerminal,
                        onScroll = viewModel::scrollTerminal,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (exit != null) {
                                            resetMirror()
                                            viewModel.startTerminal(restart = true)
                                        } else {
                                            runCatching { focusRequester.requestFocus() }
                                        }
                                    },
                                    onLongPress = { showMenu = true }
                                )
                            }
                    )
                }

                // 1dp 透明输入框：只为拿到 IME 的逐键事件，界面完全交给终端网格
                BasicTextField(
                    value = field,
                    onValueChange = { handleChange(it) },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { inputFocused = it.isFocused },
                    // singleLine：保证 IME 的回车走 onSend（发送 CR），而不是插入换行
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        // 组合态未上屏就按了发送：先把挂起内容补齐，再发 CR，
                        // 否则像 "top" 可能只到 "to"（最后的字符随组合态被丢）
                        val pendingBase = composingBase
                        if (pendingBase != null) {
                            composingBase = null
                            val curr = field.text
                            val common = commonPrefixLength(pendingBase, curr)
                            if (common < pendingBase.length) {
                                repeat(pendingBase.length - common) { viewModel.sendRaw("\u007f") }
                            }
                            if (curr.length > common) viewModel.sendRaw(curr.substring(common))
                        }
                        emitCr()
                    })
                )

                if (scrolled > 0) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xE02A2D36),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .clickable { viewModel.scrollTerminalToBottom() }
                    ) {
                        Text(
                            "回到底部（$scrolled 行）",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE4E6EB),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("复制屏幕") },
                onClick = { showMenu = false; copyScreen() }
            )
            DropdownMenuItem(
                text = { Text("粘贴") },
                onClick = { showMenu = false; paste() }
            )
            DropdownMenuItem(
                text = { Text("清屏") },
                onClick = { showMenu = false; viewModel.clearTerminal() }
            )
            DropdownMenuItem(
                text = { Text("重启会话") },
                onClick = {
                    showMenu = false
                    resetMirror()
                    viewModel.startTerminal(restart = true)
                }
            )
        }

        // 状态行：运行指示 / 环境 / 字号 / 键盘 / 重启
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (exit == null) Color(0xFF3DCB7A) else Color(0xFF9AA0A6)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (exit == null) viewModel.shellMode else "已退出（${exit}）· 点击重启",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        if (exit != null) {
                            resetMirror()
                            viewModel.startTerminal(restart = true)
                        }
                    }
            )
            SmallTextAction("A-") { viewModel.zoomTerminal(-1) }
            SmallTextAction("A+") { viewModel.zoomTerminal(1) }
            IconButton(
                onClick = {
                    if (inputFocused) focusManager.clearFocus()
                    else runCatching { focusRequester.requestFocus() }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Keyboard,
                    contentDescription = "显示/隐藏键盘",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    resetMirror()
                    viewModel.startTerminal(restart = true)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "重启会话",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 特殊键条：手机键盘打不出的控制序列
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyChip("Ctrl", active = ctrlLatch) {
                ctrlLatch = !ctrlLatch
                if (ctrlLatch) altLatch = false
            }
            KeyChip("Alt", active = altLatch) {
                altLatch = !altLatch
                if (altLatch) ctrlLatch = false
            }
            KeyChip("Esc") { emit("\u001b", clearMirror = true) }
            KeyChip("Tab") { emit("\t", clearMirror = true) }
            KeyChip("⌫") { emit("\u007f", clearMirror = true) }
            KeyChip("←") { emit("\u001b[D", clearMirror = true) }
            KeyChip("↑") { emit("\u001b[A", clearMirror = true) }
            KeyChip("↓") { emit("\u001b[B", clearMirror = true) }
            KeyChip("→") { emit("\u001b[C", clearMirror = true) }
            KeyChip("Home") { emit("\u001b[H", clearMirror = true) }
            KeyChip("End") { emit("\u001b[F", clearMirror = true) }
            KeyChip("PgUp") { emit("\u001b[5~", clearMirror = true) }
            KeyChip("PgDn") { emit("\u001b[6~", clearMirror = true) }
            KeyChip("^C") { emit("\u0003", clearMirror = true) }
            KeyChip("^D") { emit("\u0004", clearMirror = true) }
            KeyChip("^L") { emit("\u000c", clearMirror = true) }
            KeyChip("^Z") { emit("\u001a", clearMirror = true) }
            KeyChip("CLR") { viewModel.clearTerminal() }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 把终端缓冲渲染成固定网格。
 * 字号固定，列/行由可用宽高反推，再回写给 pty —— 与真实终端的行为一致。
 */
@Composable
private fun TerminalScreenView(
    screen: TermScreen,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onResize: (Int, Int) -> Unit,
    onScroll: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.background(Color(0xFF12141A)).padding(horizontal = 6.dp, vertical = 4.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val fontSizePx = with(density) { fontSize.toPx() }
        val charPx = fontSizePx * 0.62f
        val linePx = fontSizePx * 1.28f
        val cols = (widthPx / charPx).toInt().coerceIn(20, 240)
        val rows = (heightPx / linePx).toInt().coerceAtLeast(4)

        LaunchedEffect(cols, rows) { onResize(cols, rows) }

        // 手指拖动翻历史：向下拖看更早的内容
        var dragAccum by remember { mutableFloatStateOf(0f) }

        val lineHeight = with(density) { linePx.toSp() }
        val renderRows = minOf(rows, screen.rows)
        val renderCols = minOf(cols, screen.cols)

        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(linePx) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            dragAccum += dy
                            val step = (dragAccum / linePx).toInt()
                            if (step != 0) {
                                dragAccum -= step * linePx
                                onScroll(step)
                            }
                        }
                    )
                }
        ) {
            for (y in 0 until renderRows) {
                val cells = screen.lines.getOrNull(y) ?: continue
                Text(
                    text = buildTermRow(
                        cells = cells,
                        visibleCols = renderCols,
                        isCursorRow = y == screen.cursorY,
                        cursorX = screen.cursorX,
                        showCursor = screen.cursorVisible,
                    ),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = Color(0xFFE4E6EB),
                    ),
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 按键胶囊：Ctrl/Alt 激活时高亮 */
@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SmallTextAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

/** 终端 16 色 + 256 色索引 → Compose 颜色 */
private fun termColor(index: Int): Color {
    if (index < 0) return Color.Unspecified
    if (index and 0x1000000 != 0) {
        return Color(0xFF000000.toInt() or (index and 0xFFFFFF))
    }
    val palette = TERM_PALETTE
    if (index < 16) return palette[index]
    if (index >= 232) {
        val v = 8 + (index - 232) * 10
        return Color(0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
    }
    val i = index - 16
    fun comp(n: Int) = if (n == 0) 0 else 55 + n * 40
    val r = comp(i / 36)
    val g = comp((i / 6) % 6)
    val b = comp(i % 6)
    return Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
}

private val TERM_PALETTE = listOf(
    Color(0xFF1E2126), // 黑
    Color(0xFFE06C75), // 红
    Color(0xFF98C379), // 绿
    Color(0xFFE5C07B), // 黄
    Color(0xFF61AFEF), // 蓝
    Color(0xFFC678DD), // 品红
    Color(0xFF56B6C2), // 青
    Color(0xFFDCDFE4), // 白
    Color(0xFF5C6370), // 亮黑
    Color(0xFFFF7B86), // 亮红
    Color(0xFFB6E28A), // 亮绿
    Color(0xFFFFD68A), // 亮黄
    Color(0xFF7CC4FF), // 亮蓝
    Color(0xFFD68AF0), // 亮品红
    Color(0xFF7FE0EC), // 亮青
    Color(0xFFFFFFFF), // 亮白
)

/** 按样式把一行单元格拼成富文本，同时在光标位置画反色方块 */
private fun buildTermRow(
    cells: List<TermCell>,
    visibleCols: Int,
    isCursorRow: Boolean,
    cursorX: Int,
    showCursor: Boolean,
): AnnotatedString = buildAnnotatedString {
    var x = 0
    while (x < visibleCols) {
        val cell = cells.getOrNull(x) ?: TermCell()
        val cursorHere = isCursorRow && showCursor && x == cursorX
        val rawFg = termColor(cell.fg)
        val rawBg = termColor(cell.bg)
        // 反显（SGR 7）：前景背景互换
        val fg = if (cursorHere) Color(0xFF12141A) else if (cell.reverse) rawBg else rawFg
        val bg = if (cursorHere) Color(0xFFE4E6EB) else if (cell.reverse) rawFg else rawBg
        val start = length
        append(if (cell.ch == '\t') ' ' else cell.ch)
        val style = SpanStyle(
            color = fg,
            background = bg,
            fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
        )
        if (fg != Color.Unspecified || bg != Color.Unspecified || cell.bold) {
            addStyle(style, start, length)
        }
        x++
    }
}

/** 屏幕可见内容转纯文本（复制用），去掉行尾空白 */
private fun screenText(screen: TermScreen): String =
    screen.lines
        .joinToString("\n") { row ->
            row.joinToString("") { cell ->
                (if (cell.ch == '\t') ' ' else cell.ch).toString()
            }.trimEnd()
        }
        .trimEnd()

private fun commonPrefixLength(a: String, b: String): Int {
    val max = minOf(a.length, b.length)
    var i = 0
    while (i < max && a[i] == b[i]) i++
    return i
}


// ---------------- 通用小组件 ----------------

/** 条目动作底部弹层（MT 管理器风格）：头部文件信息 + 大条目动作列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionSheet(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onView: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else fileTypeIcon(entry.name),
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (entry.isDir) "文件夹 · ${formatDate(entry.lastModified)}"
                    else "${formatBytes(entry.size)} · ${formatDate(entry.lastModified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp
        )
        if (!entry.isDir) {
            SheetAction(Icons.Filled.Visibility, "查看") { onDismiss(); onView() }
            SheetAction(Icons.Filled.Download, "下载到手机…") { onDismiss(); onDownload() }
            SheetAction(Icons.Filled.Share, "分享…") { onDismiss(); onShare() }
        }
        SheetAction(Icons.Filled.CheckCircle, "选择") { onDismiss(); onSelect() }
        SheetAction(Icons.Filled.Edit, "重命名") { onDismiss(); onRename() }
        SheetAction(Icons.Filled.Delete, "删除", MaterialTheme.colorScheme.error) { onDismiss(); onDelete() }
        Spacer(Modifier.height(24.dp))
    }
}

/** 底部弹层动作条目：图标 + 文字，大点击热区 */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** 名称输入底部面板（新建/重命名共用）：自动弹起键盘，输入法「完成」直接确认 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameInputSheet(
    title: String,
    placeholder: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("确定") }
            Spacer(Modifier.height(24.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** 删除确认底部弹层：单个/批量共用，红色强调不可恢复 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteConfirmSheet(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                "删除确认",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("删除") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun formatDate(ts: Long): String =
    if (ts <= 0) "—" else dateFormat.format(Date(ts))
