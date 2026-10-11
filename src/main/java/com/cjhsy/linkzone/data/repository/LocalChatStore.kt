package com.cjhsy.linkzone.data.repository

import androidx.room.withTransaction
import com.cjhsy.linkzone.data.db.AppDatabase
import com.cjhsy.linkzone.data.db.LocalConversationEntity
import com.cjhsy.linkzone.data.db.LocalMessageEntity
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessageSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 本地智能体会话存储（RikkaHub 式，完全脱离服务器）。
 *
 * 会话与消息只存本机 Room；发送不经 WS、媒体不上传（消息段内是 file:// 路径）。
 * 进出统一使用 UI 层模型 [ConvItem] / [MessageItem]，ChatViewModel 无感切换。
 */
class LocalChatStore(
    private val db: AppDatabase,
    private val json: Json
) {

    private val dao get() = db.localChatDao()

    companion object {
        /** 本地会话 id 前缀（判定会话归属的唯一依据，旧 bot-local- 服务器会话不算本地） */
        const val CONV_PREFIX = "local-conv-"

        /** 本地会话 bot_id 前缀（bot-local-{agentId}） */
        const val BOT_PREFIX = "bot-local-"

        /** 每页历史条数（与服务端 loadHistory 默认一致） */
        private const val PAGE_SIZE = 50
    }

    fun isLocalConvId(convId: String): Boolean = convId.startsWith(CONV_PREFIX)

    // ============ 会话 ============

    /** 本地会话列表（置顶优先，按最近时间倒序），供与会话列表合并 */
    fun observeConversations(): Flow<List<ConvItem>> =
        dao.observeConversations().map { list -> list.map { it.toConvItem() } }

    suspend fun getConversations(): List<ConvItem> =
        dao.getConversations().map { it.toConvItem() }

    suspend fun getConversation(convId: String): ConvItem? =
        dao.getConversation(convId)?.toConvItem()

    /** 新建本地会话：id = local-conv-{uuid}，botId = bot-local-{agentId} */
    suspend fun createConversation(agent: LlmAgent, name: String? = null): ConvItem {
        val now = System.currentTimeMillis()
        val entity = LocalConversationEntity(
            id = CONV_PREFIX + UUID.randomUUID(),
            name = name ?: agent.name.ifBlank { "AI 对话" },
            agentId = agent.id,
            botId = BOT_PREFIX + agent.id,
            pinned = false,
            unread = 0,
            lastMsg = "",
            lastTime = now,
            createdAt = now
        )
        dao.upsertConversation(entity)
        return entity.toConvItem()
    }

    suspend fun rename(convId: String, name: String) {
        val conv = dao.getConversation(convId) ?: return
        dao.upsertConversation(conv.copy(name = name))
    }

    suspend fun setPinned(convId: String, pinned: Boolean) {
        val conv = dao.getConversation(convId) ?: return
        dao.upsertConversation(conv.copy(pinned = pinned))
    }

    suspend fun clearUnread(convId: String) {
        val conv = dao.getConversation(convId) ?: return
        if (conv.unread != 0) dao.upsertConversation(conv.copy(unread = 0))
    }

    /** 刷新最近消息预览/时间（发送与生成结束时调用） */
    suspend fun touch(convId: String, lastMsg: String, lastTime: Long) {
        val conv = dao.getConversation(convId) ?: return
        dao.upsertConversation(conv.copy(lastMsg = lastMsg, lastTime = lastTime))
    }

    /** 删除会话及全部消息（单事务） */
    suspend fun deleteConversation(convId: String) {
        db.withTransaction {
            val msgs = dao.loadAll(convId)
            if (msgs.isNotEmpty()) dao.deleteMessages(msgs.map { it.id })
            dao.deleteConversation(convId)
        }
    }

    /** 读取会话级智能体覆盖配置 JSON（null = 跟随原智能体） */
    suspend fun getAgentOverrideJson(convId: String): String? =
        dao.getConversation(convId)?.agentOverrideJson

    /** 写入/清除会话级智能体覆盖配置 */
    suspend fun setAgentOverrideJson(convId: String, json: String?) {
        dao.setAgentOverride(convId, json)
    }

    // ============ 消息 ============

    /** 正序一页历史（向上翻页传 before=最早时间戳），hasMore 按多取一条判断 */
    suspend fun loadMessagesPage(convId: String, before: Long?): Pair<List<MessageItem>, Boolean> {
        val raw = dao.loadPageDesc(convId, before, PAGE_SIZE + 1)
        val hasMore = raw.size > PAGE_SIZE
        val items = raw.take(PAGE_SIZE).reversed().map { it.toMessageItem() }
        return items to hasMore
    }

    suspend fun loadAllMessages(convId: String): List<MessageItem> =
        dao.loadAll(convId).map { it.toMessageItem() }

    suspend fun insertMessage(msg: MessageItem) = dao.insertMessage(msg.toEntity())

    suspend fun insertMessages(msgs: List<MessageItem>) {
        if (msgs.isNotEmpty()) dao.insertMessages(msgs.map { it.toEntity() })
    }

    suspend fun deleteMessages(ids: List<String>) {
        if (ids.isNotEmpty()) dao.deleteMessages(ids)
    }

    suspend fun updateMessageContent(id: String, segments: List<MessageSegment>, content: String) {
        dao.updateMessageContent(id, segments.encodeJson(), content)
    }

    suspend fun setRecalled(id: String) = dao.setRecalled(id)

    // ============ 映射 ============

    private fun LocalConversationEntity.toConvItem() = ConvItem(
        id = id,
        name = name,
        type = "local",
        unread = unread,
        pinned = pinned,
        target_id = botId,
        last_msg = lastMsg,
        lastTime = lastTime,
        updatedAt = lastTime
    )

    private fun LocalMessageEntity.toMessageItem(): MessageItem = MessageItem(
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

    private fun MessageItem.toEntity() = LocalMessageEntity(
        id = id,
        convId = convId,
        senderId = senderId,
        senderName = senderName,
        segmentsJson = segments.encodeJson(),
        content = content,
        isSelf = isSelf,
        recalled = recalled,
        timestamp = timestamp
    )

    private fun List<MessageSegment>?.encodeJson(): String =
        json.encodeToString(ListSerializer(MessageSegment.serializer()), this ?: emptyList())
}
