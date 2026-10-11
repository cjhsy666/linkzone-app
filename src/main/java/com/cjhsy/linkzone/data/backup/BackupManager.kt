package com.cjhsy.linkzone.data.backup

import android.content.Context
import com.cjhsy.linkzone.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 本地备份管理器（参考 RikkaHub BackupManager 思路，裁剪为 LinkZone 的数据面）。
 *
 * 备份内容：本地 AI 全量配置与数据
 * - Room 数据库文件（AppDatabase.DB_NAME 及 -wal / -shm 边车，打包前先执行
 *   PRAGMA wal_checkpoint(FULL) 将 WAL 落盘，保证快照一致性）：
 *   Provider（含 API Key）/ Agent / 技能元数据 / 提示词 / 世界书 / 快捷指令 /
 *   MCP / 搜索服务 / 记忆 / 本地会话与消息
 * - LLM 功能域偏好文件（shared_prefs/llm_prefs.xml）
 * - 技能文件目录（filesDir/skills/ 整棵树，含 SKILL.md / 子文件 / 二进制资产 / .skill_id）
 * - 用量统计（filesDir/usage_stats.json）
 *
 * 产物为 zip，放在 cacheDir/backup/ 下；恢复时覆盖写回原路径，
 * 由于 Room / SharedPreferences 实例在进程内仍持有旧句柄，恢复后必须重启 APP 生效。
 */
class BackupManager(
    private val context: Context,
    private val appDatabase: AppDatabase
) {

    /**
     * 创建备份 zip。
     * @return 备份文件（cacheDir/backup/linkzone_backup_yyyyMMdd_HHmmss.zip）
     */
    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!dbFile.exists()) {
            throw IllegalStateException("本地数据库不存在，无法备份")
        }
        val dbWal = File(dbFile.path + "-wal")
        val dbShm = File(dbFile.path + "-shm")
        val prefsFile = prefsXmlFile()
        val usageStatsFile = File(context.filesDir, USAGE_STATS_FILE_NAME)
        val skillsRoot = File(context.filesDir, SKILLS_DIR_NAME)

        val backupDir = File(context.cacheDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) backupDir.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File(backupDir, "linkzone_backup_$timestamp.zip")

        // 打包前把 WAL 中的已提交事务合并进主库文件，快照更一致
        checkpointDatabase()

        withContext(Dispatchers.IO) {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                // 元信息
                zip.putNextEntry(ZipEntry(ENTRY_META))
                zip.write(
                    buildMetaJson(timestamp, dbFile.length()).toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()

                // 数据库主体 + 边车
                addFile(zip, dbFile, ENTRY_DATABASE)
                if (dbWal.exists()) addFile(zip, dbWal, ENTRY_DATABASE_WAL)
                if (dbShm.exists()) addFile(zip, dbShm, ENTRY_DATABASE_SHM)

                // LLM 偏好（SharedPreferences xml）
                if (prefsFile.exists()) addFile(zip, prefsFile, ENTRY_PREFS)

                // 用量统计
                if (usageStatsFile.exists()) addFile(zip, usageStatsFile, ENTRY_USAGE_STATS)

                // 技能文件目录整棵树（含隐藏的 .skill_id 标记与二进制资产）
                if (skillsRoot.exists()) {
                    skillsRoot.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val relative = file.relativeTo(skillsRoot).path.replace(File.separatorChar, '/')
                            addFile(zip, file, ENTRY_SKILLS_PREFIX + relative)
                        }
                }
            }
        }
        archive
    }

    /**
     * 从备份 zip 恢复：覆盖写回数据库与偏好的原始路径。
     * 注意：恢复后需重启 APP 生效（Room / SharedPreferences 旧实例仍在内存中）。
     */
    suspend fun restoreBackup(archive: File) = withContext(Dispatchers.IO) {
        if (!archive.exists() || archive.length() == 0L) {
            throw IllegalStateException("备份文件不存在或为空")
        }
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        val dbWal = File(dbFile.path + "-wal")
        val dbShm = File(dbFile.path + "-shm")
        val prefsFile = prefsXmlFile()
        val usageStatsFile = File(context.filesDir, USAGE_STATS_FILE_NAME)
        val skillsRoot = File(context.filesDir, SKILLS_DIR_NAME)
        val skillsRootCanonical = skillsRoot.canonicalPath

        val seenEntries = mutableSetOf<String>()
        var restored = false
        // 技能目录恢复语义 = 整体替换（与 DB 覆盖一致）：
        // 遇到首个 skills 条目时清空现有目录再解压，避免留下 DB 元数据已不存在的孤儿技能
        var skillsCleared = false

        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                // continue 作为 when 分支表达式（Nothing 类型），target 直接为非空 File，
                // 避免部分编译器版本下 val 声明类型 File? 的可空接收者报错
                val target: File = when {
                    entry.name == ENTRY_DATABASE -> dbFile
                    entry.name == ENTRY_DATABASE_WAL -> dbWal
                    entry.name == ENTRY_DATABASE_SHM -> dbShm
                    entry.name == ENTRY_PREFS -> prefsFile
                    entry.name == ENTRY_USAGE_STATS -> usageStatsFile
                    entry.name.startsWith(ENTRY_SKILLS_PREFIX) -> {
                        val relative = entry.name.removePrefix(ENTRY_SKILLS_PREFIX)
                        // 防御 zip slip / 非法路径：逐段校验，避免误伤含 ".." 字样的合法技能名
                        val segments = relative.split('/')
                        if (relative.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." }) {
                            continue
                        }
                        val candidate = File(skillsRoot, relative)
                        if (!candidate.canonicalPath.startsWith(skillsRootCanonical + File.separator)) {
                            continue
                        }
                        if (!skillsCleared) {
                            skillsRoot.deleteRecursively()
                            skillsRoot.mkdirs()
                            skillsCleared = true
                        }
                        candidate
                    }
                    entry.name == ENTRY_META -> continue // 元信息仅作展示，无需恢复
                    else -> continue // 未知条目忽略，保证向前兼容
                }
                require(seenEntries.add(entry.name)) { "备份文件内存在重复条目：${entry.name}" }

                // 先写临时文件再原子替换，避免中途失败留下半个库
                val tmp = File(target.parentFile, target.name + ".restore_tmp")
                val tmpParent = tmp.parentFile
                if (tmpParent != null && !tmpParent.exists()) tmpParent.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IllegalStateException("写入 ${target.name} 失败")
                }
                restored = true
            }
        }
        if (!restored) throw IllegalStateException("备份文件中没有可恢复的数据")

        // 备份里没有的边车必须清掉，否则旧 WAL/SHM 会污染新库
        if (ENTRY_DATABASE_WAL !in seenEntries && dbWal.exists()) dbWal.delete()
        if (ENTRY_DATABASE_SHM !in seenEntries && dbShm.exists()) dbShm.delete()
    }

    /** 备份产物所在目录（供 UI 展示 / 清理） */
    fun backupDirectory(): File = File(context.cacheDir, BACKUP_DIR_NAME)

    // ---------- 内部 ----------

    /** SharedPreferences 中 llm_prefs.xml 的实际路径（无公开 API，按数据目录拼接） */
    private fun prefsXmlFile(): File =
        File(context.applicationInfo.dataDir, "shared_prefs/${PREFS_FILE_NAME}.xml")

    /** 执行 PRAGMA wal_checkpoint(FULL)（仅主库文件落 WAL，边车仍随包备份） */
    private fun checkpointDatabase() {
        runCatching {
            val db = appDatabase.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                cursor.moveToFirst()
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun buildMetaJson(timestamp: String, dbSize: Long): String {
        // 简单拼接即可，避免为元信息再定义 @Serializable 结构
        return "{\"app\":\"linkzone\",\"type\":\"llm_backup\"," +
            "\"createdAt\":\"$timestamp\",\"dbSize\":$dbSize}"
    }

    companion object {
        private const val BACKUP_DIR_NAME = "backup"
        private const val PREFS_FILE_NAME = "llm_prefs"
        private const val USAGE_STATS_FILE_NAME = "usage_stats.json"
        private const val SKILLS_DIR_NAME = "skills"

        /** zip 内部条目名（恢复时按名回填） */
        const val ENTRY_META = "backup_meta.json"
        const val ENTRY_DATABASE = "database/linkzone.db"
        const val ENTRY_DATABASE_WAL = "database/linkzone.db-wal"
        const val ENTRY_DATABASE_SHM = "database/linkzone.db-shm"
        const val ENTRY_PREFS = "prefs/llm_prefs.xml"
        const val ENTRY_USAGE_STATS = "files/usage_stats.json"
        const val ENTRY_SKILLS_PREFIX = "skills/"
    }
}
