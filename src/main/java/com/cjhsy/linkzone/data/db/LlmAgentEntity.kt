package com.cjhsy.linkzone.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * LLM Agent（智能体）持久化实体（表 llm_agent）。
 *
 * Agent 配置字段多且后续批次持续扩展（skills/memory/mcp...），
 * 同 Provider 策略：常用列 + 全量 JSON 列（[com.cjhsy.linkzone.data.model.LlmAgent]）。
 */
@Entity(tableName = "llm_agent")
data class LlmAgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 排序（创建顺序），内置默认 Agent 恒为最前 */
    val sort: Long,
    /** 是否内置默认智能体（不可删除） */
    val builtIn: Boolean,
    /** 本地工具开关（List<LocalToolOption> JSON，v5 新增；列与 JSON 列双写，以列为准） */
    @ColumnInfo(name = "localTools", defaultValue = "'[]'")
    val localTools: String = "[]",
    /** LlmAgent 完整 JSON */
    val json: String,
)

@Dao
interface LlmAgentDao {

    @Query("SELECT * FROM llm_agent ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmAgentEntity>>

    @Query("SELECT * FROM llm_agent ORDER BY sort ASC")
    suspend fun getAll(): List<LlmAgentEntity>

    @Query("SELECT * FROM llm_agent WHERE id = :id")
    suspend fun getById(id: String): LlmAgentEntity?

    @Query("SELECT COUNT(*) FROM llm_agent")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: LlmAgentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<LlmAgentEntity>)

    @Query("DELETE FROM llm_agent WHERE id = :id")
    suspend fun deleteById(id: String)
}
