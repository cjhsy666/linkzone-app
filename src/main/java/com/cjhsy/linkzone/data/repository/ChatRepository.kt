package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.CreateConvRequest
import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessagesResponse
import com.cjhsy.linkzone.data.model.UpdateConvRequest
import com.cjhsy.linkzone.data.model.UploadResponse
import com.cjhsy.linkzone.data.remote.ChatApi
import com.cjhsy.linkzone.data.remote.RecallRequest
import com.cjhsy.linkzone.data.remote.UpsertMsgRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * 聊天仓库（REST 部分）：会话列表、历史消息/断线补拉、已读回执、撤回、删除。
 *
 * 实时通道由 [com.cjhsy.linkzone.data.remote.ChatSocketManager] 负责，
 * 此仓库负责持久化与增量同步。智能体回复由服务端自动落库，无需客户端上报。
 */
class ChatRepository(
    private val chatApi: ChatApi,
    private val tokenHolder: TokenHolder
) {

    /** 我的会话列表（含 unread 未读数，后端返回数组） */
    suspend fun loadConversations(): Result<List<ConvItem>> =
        runEnvelope(tokenHolder) { chatApi.getConversations() }

    /**
     * 与指定 bot 确保会话存在：
     * 已有同 target 的私聊会话则复用，否则按 conv-{ownerId}-{targetId} 规则创建（幂等）。
     */
    suspend fun ensureConversation(
        ownerId: String,
        targetId: String,
        name: String? = null
    ): Result<ConvItem> {
        loadConversations().getOrNull()
            ?.firstOrNull { it.type == "private" && it.target_id == targetId }
            ?.let { return Result.success(it) }

        return runEnvelope(tokenHolder) {
            chatApi.createConversation(
                CreateConvRequest(
                    id = "conv-$ownerId-$targetId",
                    targetId = targetId,
                    name = name ?: targetId,
                    type = "private"
                )
            )
        }
    }

    /** 置顶 / 重命名 */
    suspend fun updateConversation(convId: String, name: String? = null, pinned: Boolean? = null): Result<ConvItem> =
        runEnvelope(tokenHolder) {
            chatApi.updateConversation(convId, UpdateConvRequest(name, pinned))
        }

    /** 删除会话（连同会话内消息） */
    suspend fun deleteConversation(convId: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { chatApi.deleteConversation(convId) }

    /**
     * 批量删除我的所有云端会话（连同各自消息）；keepMain=true 保留唯一主会话 conv-{ownerId}-main。
     * 用于启动迁移：云端有且只有一个主会话，幂等可重复调用。
     */
    suspend fun deleteAllConversations(keepMain: Boolean = true): Result<Unit> =
        runUnitEnvelope(tokenHolder) { chatApi.deleteAllConversations(if (keepMain) 1 else null) }

    /** 拉取历史消息（向上翻页） */
    suspend fun loadHistory(convId: String, before: Long? = null, limit: Int = 50): Result<MessagesResponse> =
        runEnvelope(tokenHolder) { chatApi.getMessages(convId, before = before, limit = limit) }

    /** 断线补拉（timestamp > after） */
    suspend fun loadSince(convId: String, after: Long, limit: Int = 50): Result<MessagesResponse> =
        runEnvelope(tokenHolder) { chatApi.getMessages(convId, after = after, limit = limit) }

    /** 已读回执（清除未读红点） */
    suspend fun markRead(convId: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { chatApi.markConversationRead(convId) }

    /** 撤回消息 */
    suspend fun recall(convId: String, msgId: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { chatApi.recallMessage(convId, RecallRequest(msgId)) }

    /** 删除消息 */
    suspend fun deleteMessage(convId: String, msgId: String): Result<Unit> =
        runUnitEnvelope(tokenHolder) { chatApi.deleteMessage(convId, msgId) }

    /** 上报消息：本地智能体 bot 回复落库（is_self=false，sender_id 可为 bot id），微秒时间戳 */
    suspend fun upsertMessage(convId: String, request: UpsertMsgRequest): Result<MessageItem> =
        runEnvelope(tokenHolder) { chatApi.upsertMessage(convId, request) }

    /** 上传文件，返回 URL 与段类型 */
    suspend fun uploadFile(file: File): Result<UploadResponse> =
        runEnvelope(tokenHolder) {
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                else -> "application/octet-stream"
            }
            val body = file.asRequestBody(mime.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", file.name, body)
            chatApi.uploadFile(part)
        }
}
