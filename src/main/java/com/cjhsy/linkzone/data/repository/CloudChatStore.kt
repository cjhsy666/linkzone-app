package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.data.db.AppDatabase
import com.cjhsy.linkzone.data.db.CloudConversationEntity
import com.cjhsy.linkzone.data.db.CloudMessageEntity
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessageSegment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 云端会话本地缓存（QQ 式本地为主）。
 *
 * 服务端仍是数据源：列表刷新、WS 事件、历史拉取/补拉结果全部幂等 upsert 进 Room；
 * 打开会话先读缓存零等待渲染，网络可达后再静默覆盖。进出统一使用 UI 层模型
 * [ConvItem] / [MessageItem]，ChatViewModel 无感接入。
 */
class CloudChatStore(
    private val db: AppDatabase,
    private val json: Json
) {

    private val dao get() = db.cloudChatDao()

    companion object {
        /** 每页缓存读取条数（与服务端 loadHistory 默认一致） */
        private const val PAGE_SIZE = 50
    }

    // ============ 会话 ============

    /** 冷启动种子：上次刷新缓存下来的云端会话列表（离线也能看到主会话） */
    suspend fun getConversations(): List<ConvItem> =
        dao.getConversations().map { it.toConvItem() }

    /** 网络刷新结果整批落库（幂等 upsert，服务端为准确值） */
    suspend fun upsertConversations(items: List<ConvItem>) {
        if (items.isNotEmpty()) dao.upsertConversations(items.map { it.toEntity() })
    }

    /** 清掉服务端已不存在的残留会话（列表刷新镜像） */
    suspend fun pruneConversations(keepIds: List<String>) {
        if (keepIds.isEmpty()) dao.clearConversations() else dao.pruneConversations(keepIds)
    }

    /** 刷新最近消息预览/时间（WS 收到消息时同步进缓存） */
    suspend fun touch(convId: String, lastMsg: String, lastTime: Long) {
        dao.touchConversation(convId, lastMsg, lastTime)
    }

    suspend fun clearUnread(convId: String) = dao.clearUnread(convId)

    /** 清空会话后复位摘要与未读 */
    suspend fun resetConversationMeta(convId: String) = dao.resetConversationMeta(convId)

    // ============ 消息 ============

    /** 正序一页缓存（向上翻页传 before=最早时间戳），hasMore 按多取一条判断 */
    suspend fun loadMessagesPage(convId: String, before: Long?): Pair<List<MessageItem>, Boolean> {
        val raw = dao.loadPageDesc(convId, before, PAGE_SIZE + 1)
        val hasMore = raw.size > PAGE_SIZE
        val items = raw.take(PAGE_SIZE).reversed().map { it.toMessageItem() }
        return items to hasMore
    }

    /** 消息幂等落库：网络拉取 / WS 事件统一入口，同 id 覆盖（服务端值优先） */
    suspend fun insertMessages(items: List<MessageItem>) {
        if (items.isNotEmpty()) dao.insertMessages(items.map { it.toEntity() })
    }

    /** 缓存里该会话最后一条消息的时间戳（增量同步游标兜底用） */
    suspend fun lastTimestamp(convId: String): Long = dao.lastTimestamp(convId) ?: 0L

    suspend fun setRecalled(id: String) = dao.setRecalled(id)

    suspend fun deleteMessage(id: String) = dao.deleteMessage(id)

    suspend fun clearMessages(convId: String) = dao.clearMessages(convId)

    // ============ 映射 ============

    private fun CloudConversationEntity.toConvItem() = ConvItem(
        id = id,
        name = name,
        type = type,
        unread = unread,
        pinned = pinned,
        target_id = targetId,
        last_msg = lastMsg,
        lastTime = lastTime,
        lastMsgId = lastMsgId,
        updatedAt = updatedAt
    )

    private fun ConvItem.toEntity() = CloudConversationEntity(
        id = id,
        name = name,
        type = type,
        unread = unread,
        pinned = pinned,
        targetId = target_id,
        lastMsg = last_msg,
        lastTime = lastTime,
        lastMsgId = lastMsgId,
        updatedAt = updatedAt
    )

    private fun CloudMessageEntity.toMessageItem(): MessageItem = MessageItem(
        id = id,
        convId = convId,
        senderId = senderId,
        senderName = senderName,
        segments = runCatching {
            json.decodeFromString(ListSerializer(MessageSegment.serializer()), segmentsJson)
        }.getOrNull(),
        content = content,
        isSelf = isSelf,
        recalled = recalled,
        timestamp = timestamp
    )

    private fun MessageItem.toEntity() = CloudMessageEntity(
        id = id,
        convId = convId,
        senderId = senderId,
        senderName = senderName,
        segmentsJson = json.encodeToString(
            ListSerializer(MessageSegment.serializer()),
            segments ?: emptyList()
        ),
        content = content,
        isSelf = isSelf,
        recalled = recalled,
        timestamp = timestamp
    )
}
