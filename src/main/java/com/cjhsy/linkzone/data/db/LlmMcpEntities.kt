package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 第四批实体：MCP 服务器 / 联网搜索服务 / 长期记忆。
 *
 * 同既有策略：常用列 + 全量 JSON 列（多态配置进 json），字段演进免迁移。
 */

// ---------- llm_mcp_server（MCP 服务器配置） ----------

@Entity(tableName = "llm_mcp_server")
data class LlmMcpServerEntity(
    @PrimaryKey val id: String,
    /** 服务器名（工具前缀 mcp__{name}__，须为字母数字） */
    val name: String,
    val enabled: Boolean,
    val sort: Long,
    /** 完整 McpServerConfig JSON（sealed 多态，含传输类型/请求头/工具/OAuth 状态） */
    val json: String,
)

@Dao
interface LlmMcpServerDao {
    @Query("SELECT * FROM llm_mcp_server ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmMcpServerEntity>>

    @Query("SELECT * FROM llm_mcp_server")
    suspend fun getAll(): List<LlmMcpServerEntity>

    @Query("SELECT * FROM llm_mcp_server WHERE id = :id")
    suspend fun getById(id: String): LlmMcpServerEntity?

    @Upsert
    suspend fun upsert(entity: LlmMcpServerEntity)

    @Query("DELETE FROM llm_mcp_server WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------- llm_search_provider（联网搜索服务配置） ----------

@Entity(tableName = "llm_search_provider")
data class LlmSearchProviderEntity(
    @PrimaryKey val id: String,
    /** 服务类型：tavily / exa / brave / searxng */
    val type: String,
    val enabled: Boolean,
    /** 当前 Agent 联网搜索使用的服务（全局唯一） */
    val selected: Boolean,
    val sort: Long,
    /** 完整 SearchServiceOptions JSON（sealed 多态） */
    val json: String,
)

@Dao
interface LlmSearchProviderDao {
    @Query("SELECT * FROM llm_search_provider ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmSearchProviderEntity>>

    @Query("SELECT * FROM llm_search_provider")
    suspend fun getAll(): List<LlmSearchProviderEntity>

    @Query("SELECT * FROM llm_search_provider WHERE id = :id")
    suspend fun getById(id: String): LlmSearchProviderEntity?

    @Upsert
    suspend fun upsert(entity: LlmSearchProviderEntity)

    @Query("UPDATE llm_search_provider SET selected = 0")
    suspend fun clearSelected()

    @Query("DELETE FROM llm_search_provider WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------- llm_memory（长期记忆，RikkaHub Memory 对齐） ----------

@Entity(tableName = "llm_memory")
data class LlmMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** 归属：agent id 或全局 "__global__" */
    val agentId: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface LlmMemoryDao {
    @Query("SELECT * FROM llm_memory WHERE agentId = :agentId ORDER BY id ASC")
    fun observeByAgent(agentId: String): Flow<List<LlmMemoryEntity>>

    @Query("SELECT * FROM llm_memory WHERE agentId = :agentId ORDER BY id ASC")
    suspend fun getByAgent(agentId: String): List<LlmMemoryEntity>

    @Query("SELECT * FROM llm_memory WHERE id = :id")
    suspend fun getById(id: Int): LlmMemoryEntity?

    @Insert
    suspend fun insert(entity: LlmMemoryEntity): Long

    @Update
    suspend fun update(entity: LlmMemoryEntity)

    @Query("DELETE FROM llm_memory WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM llm_memory WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: String)
}
