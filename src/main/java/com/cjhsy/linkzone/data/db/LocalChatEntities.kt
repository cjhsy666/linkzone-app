package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 本地智能体会话（表 llm_local_conversation）。
 *
 * 本地会话完全脱离服务器（RikkaHub 式）：会话与消息只存本机 Room，
 * 不建服务端会话、不经 WS 发送、媒体不上传（消息段内只存 file:// 本地路径）。
 * 会话 id 规则 local-conv-{uuid}，[botId] 为 bot-local-{agentId}。
 */
@Entity(tableName = "llm_local_conversation")
data class LocalConversationEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 绑定的本地智能体 id */
    val agentId: String,
    /** bot-local-{agentId}，聊天页 currentBotId / 图标判定用 */
    val botId: String,
    val pinned: Boolean,
    val unread: Int,
    val lastMsg: String,
    val lastTime: Long,
    val createdAt: Long,
)

/**
 * 本地智能体会话消息（表 llm_local_message）。
 *
 * [segmentsJson] 为 List<MessageSegment> JSON；媒体段 url 为 file:// 本地路径。
 */
@Entity(
    tableName = "llm_local_message",
    indices = [Index("convId"), Index("timestamp")]
)
data class LocalMessageEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val senderId: String,
    val senderName: String,
    val segmentsJson: String,
    val content: String,
    val isSelf: Boolean,
    val recalled: Boolean,
    val timestamp: Long,
)

@Dao
interface LocalChatDao {

    // ---------- 会话 ----------

    @Query("SELECT * FROM llm_local_conversation ORDER BY pinned DESC, lastTime DESC")
    fun observeConversations(): Flow<List<LocalConversationEntity>>

    @Query("SELECT * FROM llm_local_conversation ORDER BY pinned DESC, lastTime DESC")
    suspend fun getConversations(): List<LocalConversationEntity>

    @Query("SELECT * FROM llm_local_conversation WHERE id = :id")
    suspend fun getConversation(id: String): LocalConversationEntity?

    @Upsert
    suspend fun upsertConversation(entity: LocalConversationEntity)

    @Query("DELETE FROM llm_local_conversation WHERE id = :convId")
    suspend fun deleteConversation(convId: String)

    // ---------- 消息 ----------

    /** 倒序取一页（before 为 null 时取最新一页）；store 层反转回正序 */
    @Query(
        "SELECT * FROM llm_local_message WHERE convId = :convId " +
            "AND (:before IS NULL OR timestamp < :before) " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun loadPageDesc(convId: String, before: Long?, limit: Int): List<LocalMessageEntity>

    @Query("SELECT * FROM llm_local_message WHERE convId = :convId ORDER BY timestamp ASC")
    suspend fun loadAll(convId: String): List<LocalMessageEntity>

    @Upsert
    suspend fun insertMessage(entity: LocalMessageEntity)

    @Upsert
    suspend fun insertMessages(entities: List<LocalMessageEntity>)

    @Query("DELETE FROM llm_local_message WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<String>)

    @Query("UPDATE llm_local_message SET segmentsJson = :segmentsJson, content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, segmentsJson: String, content: String)

    @Query("UPDATE llm_local_message SET recalled = 1 WHERE id = :id")
    suspend fun setRecalled(id: String)
}
