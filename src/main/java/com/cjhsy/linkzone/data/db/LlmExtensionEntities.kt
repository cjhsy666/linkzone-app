package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * AI 扩展配置实体（第三批）：Skill / 模式注入 / 世界书 / 快捷指令。
 *
 * 同 Provider/Agent 策略：常用列 + 全量 JSON 列，字段演进免迁移。
 * 表结构与查询形态高度一致，故四者共用本文件。
 */

// ---------- llm_skill（SKILL.md 技能） ----------

@Entity(tableName = "llm_skill")
data class LlmSkillEntity(
    @PrimaryKey val id: String,
    /** 技能名（frontmatter.name），use_skill 引用标识 */
    val name: String,
    val sort: Long,
    val json: String,
)

@Dao
interface LlmSkillDao {
    @Query("SELECT * FROM llm_skill ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmSkillEntity>>

    @Query("SELECT * FROM llm_skill")
    suspend fun getAll(): List<LlmSkillEntity>

    @Query("SELECT * FROM llm_skill WHERE id = :id")
    suspend fun getById(id: String): LlmSkillEntity?

    @Upsert
    suspend fun upsert(entity: LlmSkillEntity)

    @Query("DELETE FROM llm_skill WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------- llm_prompt（模式注入） ----------

@Entity(tableName = "llm_prompt")
data class LlmPromptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val sort: Long,
    val json: String,
)

@Dao
interface LlmPromptDao {
    @Query("SELECT * FROM llm_prompt ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmPromptEntity>>

    @Query("SELECT * FROM llm_prompt")
    suspend fun getAll(): List<LlmPromptEntity>

    @Upsert
    suspend fun upsert(entity: LlmPromptEntity)

    @Query("DELETE FROM llm_prompt WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------- llm_lorebook（世界书，含条目 JSON 数组） ----------

@Entity(tableName = "llm_lorebook")
data class LlmLorebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val sort: Long,
    val json: String,
)

@Dao
interface LlmLorebookDao {
    @Query("SELECT * FROM llm_lorebook ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmLorebookEntity>>

    @Query("SELECT * FROM llm_lorebook")
    suspend fun getAll(): List<LlmLorebookEntity>

    @Upsert
    suspend fun upsert(entity: LlmLorebookEntity)

    @Query("DELETE FROM llm_lorebook WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------- llm_quick_message（快捷指令） ----------

@Entity(tableName = "llm_quick_message")
data class LlmQuickMessageEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sort: Long,
    val json: String,
)

@Dao
interface LlmQuickMessageDao {
    @Query("SELECT * FROM llm_quick_message ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmQuickMessageEntity>>

    @Query("SELECT * FROM llm_quick_message")
    suspend fun getAll(): List<LlmQuickMessageEntity>

    @Upsert
    suspend fun upsert(entity: LlmQuickMessageEntity)

    @Query("DELETE FROM llm_quick_message WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------- cloud_quick_command（云端助手快捷指令） ----------

@Entity(tableName = "cloud_quick_command")
data class CloudQuickCommandEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sort: Long,
    val json: String,
)

@Dao
interface CloudQuickCommandDao {
    @Query("SELECT * FROM cloud_quick_command ORDER BY sort ASC")
    fun observeAll(): Flow<List<CloudQuickCommandEntity>>

    @Upsert
    suspend fun upsert(entity: CloudQuickCommandEntity)

    @Query("DELETE FROM cloud_quick_command WHERE id = :id")
    suspend fun deleteById(id: String)
}
