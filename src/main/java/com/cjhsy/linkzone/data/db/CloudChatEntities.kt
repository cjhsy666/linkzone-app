package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 云端会话缓存（表 cloud_conversation）—— QQ 式本地为主。
 *
 * 服务端仍是数据源：列表刷新 / WS 事件 / 历史拉取结果全部幂等 upsert 进来，
 * 下次打开会话先读缓存秒显（零网络等待），网络可达后再静默覆盖。
 * 缓存只存服务端返回过的数据，字段与 [com.cjhsy.linkzone.data.model.ConvItem] 一一对应。
 */
@Entity(tableName = "cloud_conversation")
data class CloudConversationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val unread: Int,
    val pinned: Boolean,
    val targetId: String,
    val lastMsg: String,
    val lastTime: Long,
    val lastMsgId: String,
    val updatedAt: Long,
)

/**
 * 云端消息缓存（表 cloud_message）。
 *
 * [segmentsJson] 为 List<MessageSegment> JSON；按消息 id 幂等 upsert（同 id 覆盖），
 * 服务端回显/断线补拉/翻页结果统一入库，乐观 local- 气泡不入库。
 */
@Entity(
    tableName = "cloud_message",
    indices = [Index("convId"), Index("timestamp")]
)
data class CloudMessageEntity(
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
interface CloudChatDao {

    // ---------- 会话 ----------

    @Query("SELECT * FROM cloud_conversation ORDER BY pinned DESC, lastTime DESC")
    suspend fun getConversations(): List<CloudConversationEntity>

    @Upsert
    suspend fun upsertConversations(items: List<CloudConversationEntity>)

    @Query("DELETE FROM cloud_conversation WHERE id NOT IN (:keepIds)")
    suspend fun pruneConversations(keepIds: List<String>)

    @Query("DELETE FROM cloud_conversation")
    suspend fun clearConversations()

    @Query("UPDATE cloud_conversation SET lastMsg = :lastMsg, lastTime = :lastTime WHERE id = :convId")
    suspend fun touchConversation(convId: String, lastMsg: String, lastTime: Long)

    @Query("UPDATE cloud_conversation SET unread = 0 WHERE id = :convId")
    suspend fun clearUnread(convId: String)

    @Query("UPDATE cloud_conversation SET lastMsg = '', unread = 0 WHERE id = :convId")
    suspend fun resetConversationMeta(convId: String)

    // ---------- 消息 ----------

    /** 倒序取一页（before 为 null 时取最新一页）；store 层反转回正序 */
    @Query(
        "SELECT * FROM cloud_message WHERE convId = :convId " +
            "AND (:before IS NULL OR timestamp < :before) " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun loadPageDesc(convId: String, before: Long?, limit: Int): List<CloudMessageEntity>

    @Query("SELECT MAX(timestamp) FROM cloud_message WHERE convId = :convId")
    suspend fun lastTimestamp(convId: String): Long?

    @Upsert
    suspend fun insertMessages(items: List<CloudMessageEntity>)

    @Query("UPDATE cloud_message SET recalled = 1 WHERE id = :id")
    suspend fun setRecalled(id: String)

    @Query("DELETE FROM cloud_message WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM cloud_message WHERE convId = :convId")
    suspend fun clearMessages(convId: String)
}
