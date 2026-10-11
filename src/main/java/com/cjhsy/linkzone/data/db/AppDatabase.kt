package com.cjhsy.linkzone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地 LLM 配置库。
 *
 * 存储范围：Provider / Agent 等纯本地配置 + 收藏消息 + 本地智能体会话（v6 起，
 * 本地会话完全脱离服务器，会话与消息只存本机，媒体只存 file:// 路径）。
 */
@Database(
    entities = [
        LlmProviderEntity::class,
        LlmAgentEntity::class,
        FavoriteMessageEntity::class,
        LlmSkillEntity::class,
        LlmPromptEntity::class,
        LlmLorebookEntity::class,
        LlmQuickMessageEntity::class,
        LlmMcpServerEntity::class,
        LlmSearchProviderEntity::class,
        LlmMemoryEntity::class,
        LocalConversationEntity::class,
        LocalMessageEntity::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun llmProviderDao(): LlmProviderDao
    abstract fun llmAgentDao(): LlmAgentDao
    abstract fun favoriteMessageDao(): FavoriteMessageDao
    abstract fun llmSkillDao(): LlmSkillDao
    abstract fun llmPromptDao(): LlmPromptDao
    abstract fun llmLorebookDao(): LlmLorebookDao
    abstract fun llmQuickMessageDao(): LlmQuickMessageDao
    abstract fun llmMcpServerDao(): LlmMcpServerDao
    abstract fun llmSearchProviderDao(): LlmSearchProviderDao
    abstract fun llmMemoryDao(): LlmMemoryDao
    abstract fun localChatDao(): LocalChatDao

    companion object {
        const val DB_NAME = "linkzone.db"

        /** v1 → v2：新增收藏消息表 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorite_message (
                        id TEXT NOT NULL PRIMARY KEY,
                        convId TEXT NOT NULL,
                        convName TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3：新增 AI 扩展配置表（Skill / 模式注入 / 世界书 / 快捷指令） */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_skill (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sort INTEGER NOT NULL,
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_prompt (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        sort INTEGER NOT NULL,
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_lorebook (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        sort INTEGER NOT NULL,
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_quick_message (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        sort INTEGER NOT NULL,
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        /** v3 → v4：新增 MCP 服务器 / 联网搜索服务 / 长期记忆表 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_mcp_server (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        sort INTEGER NOT NULL,
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_search_provider (
                        id TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        selected INTEGER NOT NULL,
                        sort INTEGER NOT NULL,
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        agentId TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v4 → v5：llm_agent 新增本地工具开关列（List<LocalToolOption> JSON） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE llm_agent ADD COLUMN localTools TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        /** v5 → v6：本地智能体会话两表（会话 + 消息，完全脱离服务器） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_local_conversation (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        botId TEXT NOT NULL,
                        pinned INTEGER NOT NULL,
                        unread INTEGER NOT NULL,
                        lastMsg TEXT NOT NULL,
                        lastTime INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS llm_local_message (
                        id TEXT NOT NULL PRIMARY KEY,
                        convId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        segmentsJson TEXT NOT NULL,
                        content TEXT NOT NULL,
                        isSelf INTEGER NOT NULL,
                        recalled INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_local_message_convId ON llm_local_message(convId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_llm_local_message_timestamp ON llm_local_message(timestamp)"
                )
            }
        }

        /** v6 → v7：本地会话新增会话级智能体覆盖配置（仅本会话生效，不影响原智能体） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE llm_local_conversation ADD COLUMN agentOverrideJson TEXT"
                )
            }
        }
    }
}
