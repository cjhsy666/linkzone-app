package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.data.db.AppDatabase
import com.cjhsy.linkzone.data.db.FavoriteMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 收藏消息仓库：本地 Room（消息本体在服务端，收藏关系纯本地）。
 */
class FavoriteRepository(db: AppDatabase) {

    private val dao = db.favoriteMessageDao()

    /** 全部收藏（按收藏时间倒序） */
    fun observeAll(): Flow<List<FavoriteMessageEntity>> = dao.observeAll()

    /** 已收藏的消息 id 集合（气泡菜单「收藏/取消收藏」状态用） */
    fun observeFavoriteIds(): Flow<Set<String>> = dao.observeIds().map { it.toSet() }

    suspend fun isFavorite(messageId: String): Boolean = dao.getById(messageId) != null

    /** 切换收藏状态，返回切换后是否已收藏 */
    suspend fun toggle(
        messageId: String,
        convId: String,
        convName: String,
        senderName: String,
        content: String,
        timestamp: Long
    ): Boolean {
        if (isFavorite(messageId)) {
            dao.deleteById(messageId)
            return false
        }
        dao.upsert(
            FavoriteMessageEntity(
                id = messageId,
                convId = convId,
                convName = convName,
                senderName = senderName,
                content = content,
                timestamp = timestamp,
                createdAt = System.currentTimeMillis()
            )
        )
        return true
    }

    suspend fun remove(messageId: String) = dao.deleteById(messageId)
}
