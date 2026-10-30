package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * SSH 工作区配置（表 llm_ssh_workspace）。
 *
 * 同 MCP 服务器策略：常用列（列表页直接渲染）+ 全量 JSON 列（认证方式/私钥等演进免迁移）。
 * 敏感字段（密码/私钥）与 Provider apiKey 同级——存本机 Room，不出设备。
 */
@Entity(tableName = "llm_ssh_workspace")
data class LlmSshWorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val enabled: Boolean,
    val sort: Long,
    /** 完整 SshWorkspaceConfig JSON */
    val json: String,
)

@Dao
interface LlmSshWorkspaceDao {
    @Query("SELECT * FROM llm_ssh_workspace ORDER BY sort ASC")
    fun observeAll(): Flow<List<LlmSshWorkspaceEntity>>

    @Query("SELECT * FROM llm_ssh_workspace ORDER BY sort ASC")
    suspend fun getAll(): List<LlmSshWorkspaceEntity>

    @Query("SELECT * FROM llm_ssh_workspace WHERE id = :id")
    suspend fun getById(id: String): LlmSshWorkspaceEntity?

    @Upsert
    suspend fun upsert(entity: LlmSshWorkspaceEntity)

    @Query("DELETE FROM llm_ssh_workspace WHERE id = :id")
    suspend fun deleteById(id: String)
}
