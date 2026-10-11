package com.cjhsy.linkzone.workspace

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.cjhsy.linkzone.R
import java.io.File

/**
 * 通过 Storage Access Framework 把应用私有工作区目录暴露给系统文件管理器：
 * 用户可在「文件」应用的侧边栏看到 LinkZone 工作区，直接浏览/编辑/导出文件，
 * 无需 root、无需手动选目录授权。
 *
 * 目录结构：
 *   <root>
 *   └── {工作区名}(=filesDir/workspaces/{id})
 *       └── ... 工作区内的实际文件（含 workspace.json 元数据）
 *
 * documentId 设计：
 * - 顶层根：[ROOT_DOC_ID]
 * - 工作区根："ws/{id}"
 * - 内部文件："ws/{id}/{相对路径}"
 *
 * 工作区本体不可在此删除/重命名（避免破坏 workspace.json 与智能体绑定关系），
 * 其内部文件支持完整增删改。
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private val manager: WorkspaceManager? by lazy { context?.let { WorkspaceManager(it.applicationContext) } }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name) + " 工作区")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val target = parseDocId(documentId)
        if (target.isRoot) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, context?.getString(R.string.app_name) + " 工作区")
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            resolveFile(target)?.takeIf { it.exists() }?.let { file ->
                addFileRow(cursor, target, file)
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val mgr = manager ?: return cursor
        val parent = parseDocId(parentDocumentId)
        if (parent.isRoot) {
            // 顶层：列出全部工作区
            mgr.list().forEach { meta ->
                val dir = mgr.get(meta.id)?.root ?: return@forEach
                addFileRow(cursor, DocId(false, meta.id, ""), dir, displayName = meta.name)
            }
        } else {
            val dir = resolveFile(parent)
            if (dir != null && dir.isDirectory) {
                dir.listFiles().orEmpty()
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .forEach { addFileRow(cursor, parent, it) }
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val target = parseDocId(documentId)
        require(!target.isRoot) { "无法打开根节点" }
        val file = resolveFile(target) ?: error("文件不存在")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        val parent = parseDocId(parentDocumentId)
        require(!parent.isRoot) { "不能在根节点下直接创建（请先在 App 内新建工作区）" }
        val parentDir = resolveFile(parent)
        require(parentDir != null && parentDir.isDirectory) { "父目录不存在" }
        val safeName = displayName.replace('/', '_').ifBlank { "untitled" }
        val target = uniqueChild(parentDir, safeName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            require(target.mkdirs()) { "创建目录失败" }
        } else {
            require(target.createNewFile()) { "创建文件失败" }
        }
        notifyChange(parentDocumentId)
        return buildDocId(parent.root, joinRel(parent.relPath, target.name))
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "不能删除工作区本身（请在 App 内管理）" }
        val file = resolveFile(target) ?: return
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        require(ok) { "删除失败" }
        notifyChange(buildDocId(target.root, target.relPath.substringBeforeLast('/', "")))
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "不能重命名工作区本身" }
        val file = resolveFile(target) ?: return null
        val dest = uniqueChild(file.parentFile ?: return null, displayName.replace('/', '_'))
        require(file.renameTo(dest)) { "重命名失败" }
        notifyChange(buildDocId(target.root, target.relPath.substringBeforeLast('/', "")))
        return buildDocId(target.root, joinRel(target.relPath.substringBeforeLast('/', ""), dest.name))
    }

    override fun getDocumentType(documentId: String): String {
        val target = parseDocId(documentId)
        if (target.isRoot) return Document.MIME_TYPE_DIR
        return mimeOf(resolveFile(target))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parseDocId(parentDocumentId)
        val child = parseDocId(documentId)
        if (child.isRoot) return false
        if (parent.isRoot) return true
        if (parent.root != child.root) return false
        if (parent.relPath.isEmpty()) return true
        return child.relPath == parent.relPath || child.relPath.startsWith(parent.relPath + "/")
    }

    // ---------------- helpers ----------------

    private fun addFileRow(
        cursor: MatrixCursor,
        target: DocId,
        file: File,
        displayName: String = file.name
    ) {
        val isDir = file.isDirectory
        val isWorkspaceRoot = !target.isRoot && target.relPath.isEmpty()
        val flags = when {
            // 工作区根：允许内部新建，但不能删除/重命名工作区本体
            isWorkspaceRoot -> Document.FLAG_DIR_SUPPORTS_CREATE
            isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(target.root, target.relPath))
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun mimeOf(file: File?): String {
        if (file == null || file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun uniqueChild(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val stem = candidate.nameWithoutExtension
        val ext = if (candidate.extension.isNotEmpty()) ".${candidate.extension}" else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(parent, "$stem ($n)$ext")
            n++
        }
        return candidate
    }

    /** 解析 documentId 到实际文件，并校验路径不逃逸工作区根目录 */
    private fun resolveFile(target: DocId): File? {
        if (target.isRoot) return null
        val base = manager?.get(target.root)?.root?.canonicalFile ?: return null
        if (target.relPath.isEmpty()) return base
        require(!target.relPath.contains('\u0000')) { "非法路径" }
        val file = File(base, target.relPath.trimStart('/')).canonicalFile
        require(file.path == base.path || file.path.startsWith(base.path + File.separator)) {
            "路径逃逸工作区根目录"
        }
        return file
    }

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId(isRoot = true, root = "", relPath = "")
        require(documentId.startsWith(DOC_PREFIX)) { "非法 documentId: $documentId" }
        val rest = documentId.removePrefix(DOC_PREFIX)
        val idx = rest.indexOf('/')
        return if (idx < 0) DocId(false, rest, "")
        else DocId(false, rest.substring(0, idx), rest.substring(idx + 1))
    }

    private fun buildDocId(root: String, relPath: String): String =
        if (relPath.isEmpty()) "$DOC_PREFIX$root" else "$DOC_PREFIX$root/$relPath"

    private fun joinRel(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = android.provider.DocumentsContract.buildChildDocumentsUri(
            ctx.packageName + ".workspace.documents",
            parentDocumentId
        )
        ctx.contentResolver.notifyChange(uri, null)
    }

    private data class DocId(
        val isRoot: Boolean,
        val root: String,
        val relPath: String
    )

    companion object {
        private const val ROOT_ID = "linkzone_workspaces"
        private const val ROOT_DOC_ID = "root"
        private const val DOC_PREFIX = "ws/"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
    }
}
