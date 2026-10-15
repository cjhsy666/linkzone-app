package com.cjhsy.linkzone.data.remote

import com.cjhsy.linkzone.core.network.ApiEnvelope
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.CreateConvRequest
import com.cjhsy.linkzone.data.model.EmptyData
import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessagesResponse
import com.cjhsy.linkzone.data.model.OkResponse
import com.cjhsy.linkzone.data.model.UpdateConvRequest
import com.cjhsy.linkzone.data.model.UploadResponse
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.Part
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 撤回请求体（后端要求 JSON body 传 msg_id） */
@Serializable
data class RecallRequest(val msg_id: String)

/**
 * 上报消息请求体（对齐服务端 upsertMsgReq；timestamp 微秒）。
 * 用于本地智能体 bot 回复落库（is_self=false，sender_id 可为 bot id）。
 */
@Serializable
data class UpsertMsgRequest(
    val id: String = "",
    val segments: JsonElement? = null,
    val content: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("is_self") val isSelf: Boolean = false,
    @SerialName("sender_level") val senderLevel: Int = 0,
    val timestamp: Long = 0L,
)

/**
 * 聊天接口（LinkZone 适配器，前缀 api/v1/linkzone）。
 *
 * 所有接口必须认证，由 AuthInterceptor 自动附加 Authorization 头。
 * WebSocket 实时通道见 [ChatSocketManager]（?token=<access_token>）。
 */
interface ChatApi {

    /** 我的会话列表（含 unread 未读数；后端 data 直接返回数组） */
    @GET("api/v1/linkzone/chat/convs")
    suspend fun getConversations(): ApiEnvelope<List<ConvItem>>

    /** 创建会话（id 由客户端按 conv-{ownerId}-{targetId} 规则生成） */
    @POST("api/v1/linkzone/chat/convs")
    suspend fun createConversation(@Body body: CreateConvRequest): ApiEnvelope<ConvItem>

    /** 置顶 / 重命名 */
    @PATCH("api/v1/linkzone/chat/convs/{id}")
    suspend fun updateConversation(
        @Path("id") convId: String,
        @Body body: UpdateConvRequest
    ): ApiEnvelope<ConvItem>

    /** 删除会话（连同会话内消息） */
    @DELETE("api/v1/linkzone/chat/convs/{id}")
    suspend fun deleteConversation(@Path("id") convId: String): ApiEnvelope<EmptyData?>

    /**
     * 批量删除我的所有云端会话（连同各自消息）；keepMain=true 保留唯一主会话 conv-{ownerId}-linkzone-bot。
     * 客户端固定唯一云端对话：启动时幂等清理历史遗留会话。
     */
    @DELETE("api/v1/linkzone/chat/convs")
    suspend fun deleteAllConversations(@Query("keep_main") keepMain: Int? = null): ApiEnvelope<EmptyData?>

    /**
     * 历史消息 / 断线补拉。
     *
     * - before：向上翻页（timestamp < before）
     * - after：断线补拉（timestamp > after，优先于 before）
     */
    @GET("api/v1/linkzone/chat/msgs/{convId}")
    suspend fun getMessages(
        @Path("convId") convId: String,
        @Query("before") before: Long? = null,
        @Query("after") after: Long? = null,
        @Query("limit") limit: Int = 50
    ): ApiEnvelope<MessagesResponse>

    /** 上报/保存消息（bot 回复落库、发送方回执；服务端返回保存后的消息） */
    @POST("api/v1/linkzone/chat/msgs/{convId}")
    suspend fun upsertMessage(
        @Path("convId") convId: String,
        @Body body: UpsertMsgRequest
    ): ApiEnvelope<MessageItem>

    /** 已读回执（清除未读红点） */
    @POST("api/v1/linkzone/chat/msgs/{convId}/read")
    suspend fun markConversationRead(@Path("convId") convId: String): ApiEnvelope<OkResponse>

    /** 撤回消息（JSON body 传 msg_id） */
    @POST("api/v1/linkzone/chat/msgs/{convId}/recall")
    suspend fun recallMessage(
        @Path("convId") convId: String,
        @Body body: RecallRequest
    ): ApiEnvelope<EmptyData?>

    /** 删除消息 */
    @DELETE("api/v1/linkzone/chat/msgs/{convId}/{msgId}")
    suspend fun deleteMessage(
        @Path("convId") convId: String,
        @Path("msgId") msgId: String
    ): ApiEnvelope<EmptyData?>

    /** 上传文件（最大 20MB），返回相对路径和段类型 */
    @Multipart
    @POST("api/v1/linkzone/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): ApiEnvelope<UploadResponse>
}
