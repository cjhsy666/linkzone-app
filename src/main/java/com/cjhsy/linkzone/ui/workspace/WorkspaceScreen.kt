package com.cjhsy.linkzone.ui.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cjhsy.linkzone.workspace.Workspace
import com.cjhsy.linkzone.workspace.WorkspaceCommandResult
import com.cjhsy.linkzone.workspace.WorkspaceManager
import com.cjhsy.linkzone.workspace.WorkspaceMeta
import com.cjhsy.linkzone.workspace.WorkspaceShellContext
import com.cjhsy.linkzone.workspace.WorkspaceShellRunner
import com.cjhsy.linkzone.workspace.WorkspaceStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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

data class WorkspaceUiState(
    val workspaces: List<WorkspaceMeta> = emptyList(),
    val stats: Map<String, WorkspaceStats> = emptyMap(),
    /** 非空时进入该工作区详情 */
    val opened: WorkspaceMeta? = null,
    /** 当前浏览目录（相对工作区根，空串=根） */
    val cwd: String = "",
    val entries: List<FileEntry> = emptyList(),
    /** 0=文件 1=终端 */
    val tab: Int = 0,
    val running: Boolean = false,
    val output: String = "",
    val message: String? = null,
    val error: String? = null,
)

/**
 * 工作区管理页 VM：文件系统直读（无 Room），全部 IO 操作在 Dispatchers.IO。
 * 终端基于 WorkspaceShellRunner（自动选择宿主 sh / PRoot Linux）。
 */
class WorkspaceViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val manager = WorkspaceManager(appContext)
    private val runner: WorkspaceShellRunner by lazy { WorkspaceShellRunner.select(appContext.filesDir) }

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    val shellMode: String get() = manager.shellMode

    init {
        refreshList()
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun notify(message: String?, error: String? = null) {
        _state.value = _state.value.copy(message = message, error = error)
    }

    // ---------------- 工作区列表 ----------------

    fun refreshList() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = manager.list()
            val stats = list.associate { meta ->
                val ws = manager.get(meta.id)
                meta.id to (ws?.let { manager.stats(it) } ?: WorkspaceStats(0, 0))
            }
            _state.value = _state.value.copy(workspaces = list, stats = stats)
        }
    }

    fun createWorkspace(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.create(name)
            notify("已创建工作区「${name.ifBlank { "工作区" }}」")
            refreshList()
        }
    }

    fun renameWorkspace(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.rename(id, name)
            _state.value = _state.value.copy(
                opened = _state.value.opened?.let { if (it.id == id) it.copy(name = name) else it }
            )
            notify("已重命名")
            refreshList()
        }
    }

    fun deleteWorkspace(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.delete(id)
            if (_state.value.opened?.id == id) {
                _state.value = _state.value.copy(opened = null, cwd = "", entries = emptyList())
            }
            notify("已删除工作区")
            refreshList()
        }
    }

    // ---------------- 文件浏览 ----------------

    fun openWorkspace(id: String) {
        val ws = manager.get(id) ?: return
        _state.value = _state.value.copy(opened = ws.meta, cwd = "", tab = 0)
        listDir("")
    }

    fun closeWorkspace() {
        _state.value = _state.value.copy(opened = null, cwd = "", entries = emptyList(), output = "")
    }

    fun switchTab(tab: Int) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun enterDir(name: String) {
        val cwd = joinPath(_state.value.cwd, name)
        _state.value = _state.value.copy(cwd = cwd)
        listDir(cwd)
    }

    fun goUp() {
        val cwd = _state.value.cwd.substringBeforeLast('/', "")
        _state.value = _state.value.copy(cwd = cwd)
        listDir(cwd)
    }

    fun jumpTo(path: String) {
        _state.value = _state.value.copy(cwd = path)
        listDir(path)
    }

    private fun rootOf(): Workspace? = _state.value.opened?.let { manager.get(it.id) }

    private fun listDir(cwd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf()
            if (ws == null) {
                _state.value = _state.value.copy(entries = emptyList())
                return@launch
            }
            val dir = File(ws.root, cwd.ifBlank { "." })
            if (!dir.isDirectory) {
                _state.value = _state.value.copy(cwd = "", entries = emptyList())
                return@launch
            }
            val entries = dir.listFiles()
                ?.map { f ->
                    FileEntry(
                        name = f.name,
                        isDir = f.isDirectory,
                        size = if (f.isDirectory) 0L else f.length(),
                        lastModified = f.lastModified(),
                        relPath = joinPath(cwd, f.name),
                    )
                }
                ?.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
                .orEmpty()
            _state.value = _state.value.copy(entries = entries)
        }
    }

    fun createDir(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
            val ok = runCatching { File(ws.root, joinPath(_state.value.cwd, name)).mkdirs() }
                .getOrDefault(false)
            notify(if (ok) "已创建文件夹「$name」" else null, if (ok) null else "创建失败")
            listDir(_state.value.cwd)
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
            runCatching {
                val f = File(ws.root, joinPath(_state.value.cwd, name))
                if (!f.exists()) f.createNewFile()
            }
            notify("已创建文件「$name」")
            listDir(_state.value.cwd)
        }
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
            val from = File(ws.root, entry.relPath)
            val to = File(from.parentFile, newName)
            val ok = runCatching { from.renameTo(to) }.getOrDefault(false)
            notify(if (ok) "已重命名为「$newName」" else null, if (ok) null else "重命名失败")
            listDir(_state.value.cwd)
        }
    }

    fun deleteEntry(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
            File(ws.root, entry.relPath).deleteRecursively()
            notify("已删除「${entry.name}」")
            listDir(_state.value.cwd)
        }
    }

    /**
     * 读取文本文件用于预览：二进制文件返回 null 并提示；
     * 超过 256KB 截断（预览用途，编辑走终端/AI）。
     */
    suspend fun readTextFile(entry: FileEntry): String? = withContext(Dispatchers.IO) {
        val ws = rootOf() ?: return@withContext null
        val f = File(ws.root, entry.relPath)
        if (!f.isFile) return@withContext null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@withContext null
        val isBinary = bytes.take(8192).any { it == 0.toByte() }
        if (isBinary) {
            notify(null, "「${entry.name}」是二进制文件，不支持预览")
            return@withContext null
        }
        var text = String(bytes, Charsets.UTF_8)
        if (text.length > MAX_PREVIEW_CHARS) text = text.take(MAX_PREVIEW_CHARS) + "\n\n… [内容过长已截断]"
        text
    }

    /** 导入文件（SAF content uri）到当前目录；重名自动加序号 */
    fun importFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
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
                notify(null, "导入失败：${e.message ?: "未知错误"}")
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

    /** 导出：复制到 cacheDir/exports 再走系统分享（FileProvider 仅开放 cache 目录） */
    fun exportFile(entry: FileEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
            runCatching {
                val src = File(ws.root, entry.relPath)
                val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                val dst = File(dir, "${entry.name}_${System.currentTimeMillis()}")
                src.copyTo(dst, overwrite = true)
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
                notify(null, "导出失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    // ---------------- SAF 导出到用户自选目录 ----------------

    /** 当前打开工作区根目录的绝对路径（界面展示，让用户知道文件真实落盘位置） */
    fun currentRootPath(): String? = rootOf()?.root?.absolutePath

    /**
     * 把单个文件保存到 SAF 用户选定的目录树（Download/U盘/自建文件夹均可）。
     * treeUri 由 OpenDocumentTree 返回，调用方需先 takePersistableUriPermission。
     */
    fun exportEntryToTree(entry: FileEntry, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
            runCatching {
                val src = File(ws.root, entry.relPath)
                require(src.isFile) { "不是文件" }
                val resolver = appContext.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val doc = DocumentsContract.createDocument(resolver, parent, guessMime(entry.name), entry.name)
                    ?: error("无法在目标目录创建文件")
                resolver.openOutputStream(doc)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: error("无法写入目标文件")
                notify("已保存到所选目录：${entry.name}")
            }.onFailure { e -> notify(null, "保存失败：${e.message ?: "未知错误"}") }
        }
    }

    /**
     * 把整个工作区（含子目录结构）导出到 SAF 选定目录下的同名文件夹。
     * workspace.json 等内部文件同样保留，方便整体备份/迁移。
     */
    fun exportWholeWorkspaceToTree(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ws = rootOf() ?: return@launch
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
            }.onFailure { e -> notify(null, "导出失败：${e.message ?: "未知错误"}") }
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

    // ---------------- 终端 ----------------

    fun runCommand(command: String) {
        if (command.isBlank() || _state.value.running) return
        val ws = rootOf() ?: return
        _state.value = _state.value.copy(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                runner.execute(WorkspaceShellContext(root = ws.root, command = command.trim()))
            }.getOrElse {
                WorkspaceCommandResult(-1, "", it.message ?: "执行异常", false)
            }
            val header = "$ ${command.trim()}\n"
            val footer = buildString {
                if (result.stderr.isNotBlank()) append(result.stderr.trimEnd() + "\n")
                append(
                    when {
                        result.timedOut -> "[超时（30s 上限），已强制终止]"
                        result.exitCode != 0 -> "[退出码 ${result.exitCode}]"
                        else -> "[完成]"
                    }
                )
            }
            appendOutput(header + result.stdout.trimEnd().ifBlank { "" }.let { if (it.isBlank()) "" else "$it\n" } + footer + "\n\n")
            _state.value = _state.value.copy(running = false)
        }
    }

    fun clearTerminal() {
        _state.value = _state.value.copy(output = "")
    }

    private fun appendOutput(chunk: String) {
        var text = _state.value.output + chunk
        if (text.length > MAX_TERMINAL_CHARS) text = "… [更早日志已截断]\n" + text.takeLast(MAX_TERMINAL_CHARS)
        _state.value = _state.value.copy(output = text)
    }

    companion object {
        const val MAX_PREVIEW_CHARS = 256 * 1024
        const val MAX_TERMINAL_CHARS = 200 * 1024
    }
}

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

    // Toast 提示
    state.message?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        viewModel.consumeNotice()
    }
    state.error?.let {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        viewModel.consumeNotice()
    }

    if (state.opened == null) {
        WorkspaceListPane(
            state = state,
            shellMode = viewModel.shellMode,
            onBack = onBack,
            onOpen = viewModel::openWorkspace,
            onCreate = viewModel::createWorkspace,
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
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkspaceMeta?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceMeta?>(null) }

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
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建工作区")
            }
        }

        // shell 模式说明
        Text(
            text = "Shell 模式：$shellMode · AI 可在绑定的工作区读写文件、执行命令",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

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
                                    Icons.Filled.Folder,
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
                                    "${stats?.fileCount ?: 0} 个文件 · ${formatBytes(stats?.totalBytes ?: 0)} · " +
                                        formatDate(ws.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = { menuOpen = false; renaming = ws }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
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

    if (showCreate) {
        NameInputDialog(
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
        NameInputDialog(
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
            title = { Text("删除工作区") },
            text = { Text("将永久删除「${ws.name}」及其全部文件，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { onDelete(ws.id); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
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
                    "${stats?.fileCount ?: 0} 个文件 · ${formatBytes(stats?.totalBytes ?: 0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 真实落盘路径：让用户明确文件位置，点击复制
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
                            Icons.Filled.Folder,
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
            IconButton(onClick = { exportAllLauncher.launch(null) }) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "导出整个工作区到所选文件夹",
                    modifier = Modifier.size(20.dp)
                )
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
            TabPill("终端", Icons.Filled.Terminal, state.tab == 1, Modifier.weight(1f)) { viewModel.switchTab(1) }
        }

        when (state.tab) {
            0 -> FilesPane(state, viewModel)
            else -> TerminalPane(state, viewModel)
        }
    }

    if (showRenameWs) {
        NameInputDialog(
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
            title = { Text("删除工作区") },
            text = { Text("将永久删除「${ws.name}」及其全部文件，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWorkspace(ws.id)
                    showDeleteWs = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
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

// ---------------- 文件面板 ----------------

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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importFile)
    }

    // 单文件导出：用户自选目标文件夹（Download/自建目录等）
    var pendingSaveEntry by remember { mutableStateOf<FileEntry?>(null) }
    val saveToTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val entry = pendingSaveEntry
        pendingSaveEntry = null
        if (treeUri != null && entry != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.exportEntryToTree(entry, treeUri)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 面包屑
        BreadcrumbRow(
            cwd = state.cwd,
            onJump = viewModel::jumpTo,
            onUp = viewModel::goUp
        )

        // 操作行：导入 / 新建文件夹 / 新建文件
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { importLauncher.launch("*/*") }) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("导入", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { showNewDir = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("文件夹", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { showNewFile = true }) {
                Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("文件", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (state.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "空目录 · 用上方按钮导入或新建文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(state.entries, key = { it.relPath }) { entry ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (entry.isDir) viewModel.enterDir(entry.name)
                                else scope.launch {
                                    viewModel.readTextFile(entry)?.let { viewing = entry to it }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                            tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
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
                                if (entry.isDir) "文件夹" else "${formatBytes(entry.size)} · ${formatDate(entry.lastModified)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!entry.isDir) {
                                    DropdownMenuItem(
                                        text = { Text("查看") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                viewModel.readTextFile(entry)?.let { viewing = entry to it }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出分享") },
                                        onClick = { menuOpen = false; viewModel.exportFile(entry) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("保存到文件夹…") },
                                        onClick = {
                                            menuOpen = false
                                            pendingSaveEntry = entry
                                            saveToTreeLauncher.launch(null)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = { menuOpen = false; renaming = entry }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; deleting = entry }
                                )
                            }
                        }
                    }
                }
            }
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

    if (showNewDir) {
        NameInputDialog(
            title = "新建文件夹",
            placeholder = "文件夹名称",
            onConfirm = { name -> showNewDir = false; viewModel.createDir(name) },
            onDismiss = { showNewDir = false }
        )
    }

    if (showNewFile) {
        NameInputDialog(
            title = "新建文件",
            placeholder = "文件名（可含扩展名）",
            onConfirm = { name -> showNewFile = false; viewModel.createFile(name) },
            onDismiss = { showNewFile = false }
        )
    }

    renaming?.let { entry ->
        NameInputDialog(
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
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除") },
            text = { Text("删除「${entry.name}」${if (entry.isDir) "及其全部内容" else ""}？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

/** 面包屑：根 / a / b，每段可点击跳转；尾部「上级」按钮 */
@Composable
private fun BreadcrumbRow(
    cwd: String,
    onJump: (String) -> Unit,
    onUp: () -> Unit
) {
    val segments = remember(cwd) { cwd.split('/').filter { it.isNotBlank() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BreadcrumbChip("根目录", cwd.isBlank()) { onJump("") }
            segments.forEachIndexed { idx, seg ->
                Text(
                    "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                BreadcrumbChip(seg, idx == segments.lastIndex) { onJump(segments.take(idx + 1).joinToString("/")) }
            }
        }
        if (cwd.isNotBlank()) {
            TextButton(onClick = onUp) {
                Text("上级", style = MaterialTheme.typography.labelMedium)
            }
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

// ---------------- 终端面板 ----------------

@Composable
private fun TerminalPane(
    state: WorkspaceUiState,
    viewModel: WorkspaceViewModel
) {
    var command by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // 输出变化时滚到底部
    androidx.compose.runtime.LaunchedEffect(state.output) {
        if (state.output.isNotBlank()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 输出区
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            if (state.output.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "在下方输入命令并运行\n工作目录固定为工作区根目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(10.dp)
                ) {
                    Text(
                        state.output,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    )
                }
            }
        }

        // 输出区右上角清空
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = viewModel::clearTerminal, enabled = state.output.isNotBlank()) {
                Text("清空输出", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 快捷命令
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ls -la", "pwd", "uname -a", "cat workspace.json", "df -h").forEach { cmd ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.clickable { command = cmd }
                ) {
                    Text(
                        cmd,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 输入行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("输入 shell 命令", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                enabled = !state.running
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.FilledIconButton(
                onClick = {
                    viewModel.runCommand(command)
                    command = ""
                },
                enabled = !state.running && command.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(48.dp)
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "运行")
                }
            }
        }
    }
}

// ---------------- 通用小组件 ----------------

/** 名称输入对话框（新建/重命名共用） */
@Composable
private fun NameInputDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun formatDate(ts: Long): String =
    if (ts <= 0) "—" else dateFormat.format(Date(ts))
