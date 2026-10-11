package com.cjhsy.linkzone.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 收藏消息（表 favorite_message）。
 *
 * 对齐 RikkaHub 的消息收藏（MessageNode.isFavorite）：消息本体落服务端，
 * 收藏关系纯本地保存——存内容快照，会话删除后收藏仍可浏览。
 */
@Entity(tableName = "favorite_message")
data class FavoriteMessageEntity(
    /** 消息 id（主键；同消息重复收藏即覆盖更新快照） */
    @PrimaryKey val id: String,
    val convId: String,
    /** 会话名快照（列表展示用） */
    val convName: String,
    val senderName: String,
    /** 纯文本内容快照 */
    val content: String,
    /** 消息时间戳（微秒） */
    val timestamp: Long,
    /** 收藏时间（毫秒，排序用） */
    val createdAt: Long,
)

@Dao
interface FavoriteMessageDao {

    @Query("SELECT * FROM favorite_message ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteMessageEntity>>

    @Query("SELECT * FROM favorite_message WHERE id = :id")
    suspend fun getById(id: String): FavoriteMessageEntity?

    @Query("SELECT id FROM favorite_message")
    fun observeIds(): Flow<List<String>>

    @Upsert
    suspend fun upsert(entity: FavoriteMessageEntity)

    @Query("DELETE FROM favorite_message WHERE id = :id")
    suspend fun deleteById(id: String)
}
