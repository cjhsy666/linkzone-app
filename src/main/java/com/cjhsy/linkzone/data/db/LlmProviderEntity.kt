package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * LLM Provider 持久化实体（表 llm_provider）。
 *
 * [com.cjhsy.linkzone.ai.provider.ProviderSetting] 是多态 sealed 类（openai/google/claude），
 * 直接建列会随协议差异膨胀，因此采用「分类列 + 全量 JSON 列」存储：
 * - type / name / enabled 用于列表展示与查询
 * - json 为 ProviderSetting 的 kotlinx-serialization 完整序列化（type 字段做判别）
 */
@Entity(tableName = "llm_provider")
data class LlmProviderEntity(
    @PrimaryKey val id: String,
    /** 协议类型：openai / google / claude（ProviderSetting 子类 SerialName） */
    val type: String,
    val name: String,
    val enabled: Boolean,
    /** 列表排序（创建顺序） */
    val sort: Long,
    /** ProviderSetting 完整 JSON（含 apiKey/baseUrl/models/customHeaders 等全部字段） */
    val json: String,
)

@Dao
interface LlmProviderDao {

    @Query("SELECT * FROM llm_provider ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmProviderEntity>>

    @Query("SELECT * FROM llm_provider ORDER BY sort ASC")
    suspend fun getAll(): List<LlmProviderEntity>

    @Query("SELECT * FROM llm_provider WHERE id = :id")
    suspend fun getById(id: String): LlmProviderEntity?

    @Query("SELECT COUNT(*) FROM llm_provider")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: LlmProviderEntity)

    @Upsert
    suspend fun upsertAll(entities: List<LlmProviderEntity>)

    @Query("DELETE FROM llm_provider WHERE id = :id")
    suspend fun deleteById(id: String)
}
