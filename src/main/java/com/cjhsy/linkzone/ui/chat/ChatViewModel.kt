package com.cjhsy.linkzone.ui.chat

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ProcessLifecycleOwner
import com.cjhsy.linkzone.data.model.ConvItem
import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessageSegment
import com.cjhsy.linkzone.data.model.Segments
import com.cjhsy.linkzone.data.model.displayText
import com.cjhsy.linkzone.data.remote.ChatSocketManager
import com.cjhsy.linkzone.data.repository.ChatRepository
import com.cjhsy.linkzone.data.repository.LocalChatStore
import com.cjhsy.linkzone.data.repository.UserRepository
import com.cjhsy.linkzone.ai.core.MessageRole
import com.cjhsy.linkzone.ai.core.TokenUsage
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ModelType
import com.cjhsy.linkzone.ai.provider.Modality
import com.cjhsy.linkzone.ai.provider.ProviderManager
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.provider.TextGenerationParams
import com.cjhsy.linkzone.ai.provider.supportsTools
import com.cjhsy.linkzone.ai.ui.StreamChunk
import com.cjhsy.linkzone.ai.ui.StreamChunkHandler
import com.cjhsy.linkzone.ai.ui.UIMessage
import com.cjhsy.linkzone.ai.document.DocumentParser
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.ai.ui.limitContext
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.LlmPromptInjector
import com.cjhsy.linkzone.data.model.LlmLorebook
import com.cjhsy.linkzone.data.model.LlmPrompt
import com.cjhsy.linkzone.data.model.LlmQuickMessage
import com.cjhsy.linkzone.data.model.LlmSkill
import com.cjhsy.linkzone.data.ai.buildMemoryPrompt
import com.cjhsy.linkzone.data.ai.buildMemoryTools
import com.cjhsy.linkzone.data.ai.createLocalTools
import com.cjhsy.linkzone.data.ai.createSearchTools
import com.cjhsy.linkzone.data.ai.createSkillTools
import com.cjhsy.linkzone.data.ai.createConversationTools
import com.cjhsy.linkzone.data.ai.ConvSummary
import com.cjhsy.linkzone.data.ai.buildImageGenerateTool
import com.cjhsy.linkzone.data.ai.createWorkspaceTools
import com.cjhsy.linkzone.data.ai.applyTimeReminder
import com.cjhsy.linkzone.data.ai.mcp.McpManager
import com.cjhsy.linkzone.data.remote.UpsertMsgRequest
import com.cjhsy.linkzone.data.repository.LlmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import com.cjhsy.linkzone.ai.util.encodeBase64
import java.io.File
import java.util.UUID

/** 待发送附件（UI 层 → ViewModel 层传递用） */
data class PendingMediaItem(
    val file: File,
    val type: String?,
    /** 选中即预上传完成后的服务端 URL；非空时发送直接引用，跳过重复上传 */
    val remoteUrl: String? = null,
    val remoteSegType: String = "",
    val remoteName: String = "",
    val remoteSize: Long = 0L
)

/** ask_user 追问中的一道问题（模型 ask_user 工具入参的 UI 形态） */
data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String> = emptyList(),
    /** text=自由输入 / single=单选 / multi=多选（均可附自定义文本） */
    val selectionType: String = "text"
)

/** 一次 ask_user 追问（生成挂起中，等待用户作答） */
data class AskUserPrompt(
    val toolCallId: String,
    val questions: List<AskUserQuestion>
)

/** 乐观（未确认）消息 ID 前缀，确认后由服务端同 id 正式消息替换 */
const val LOCAL_PREFIX = "local-"

/** 本地智能体单次回复的最大工具调用轮数（防失控循环） */
private const val MAX_TOOL_STEPS = 32

data class ChatUiState(
    val currentConvId: String = "",
    /** 当前会话的 bot_id（= 会话 target_id），发送消息时携带 */
    val currentBotId: String = "",
    val currentName: String = "",
    val messages: List<MessageItem> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val connected: Boolean = false,
    val hasMore: Boolean = false,
    /** 当前会话是否已打开（可发送消息） */
    val ready: Boolean = false,
    /** 待发送的回复引用（长按消息→回复），发送后自动清除 */
    val replyTo: MessageItem? = null,
    /** 本地智能体会话正在流式生成中（显示停止按钮） */
    val generating: Boolean = false,
    /** ask_user 追问挂起中（非空时聊天页显示作答卡，提交后生成继续） */
    val askUser: AskUserPrompt? = null,
    /** AI 生成的回复建议（首答后生成，点击直接发送） */
    val aiSuggestions: List<String> = emptyList(),
    /** 当前会话绑定的本地智能体（非本地会话为 null）。本地会话中可能是会话级覆盖快照 */
    val currentAgent: LlmAgent? = null,
    /** 当前本地会话存在临时覆盖配置（仅本会话生效，未回写智能体） */
    val agentOverridden: Boolean = false,
    /** 正在编辑的消息（编辑重发：非空时聊天页弹出编辑对话框） */
    val editing: MessageItem? = null,
    /** 会话内搜索面板打开中 */
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    /** 搜索结果（按时间倒序，最新的在前） */
    val searchResults: List<MessageItem> = emptyList(),
    /** 正在全量拉取历史以供搜索 */
    val searching: Boolean = false,
    /** 定位高亮的消息 id（搜索结果点击跳转） */
    val highlightMessageId: String? = null,
    val error: String? = null
)

/** 可选模型项（多模型重答选择器用） */
data class ChatModelOption(
    val modelId: String,
    val label: String,
    /** provider 显示名（副标题） */
    val providerName: String,
    /** 是否支持图片输入（视觉模型） */
    val supportsVision: Boolean = false,
    /** 是否支持工具调用 */
    val supportsTools: Boolean = false,
    /** 是否支持深度思考（reasoning） */
    val supportsReasoning: Boolean = false,
)

/**
 * 聊天 ViewModel —— 与 LinkZone 适配器直连的会话模型。
 *
 * 云端有且只有一个主会话：bot_id 固定为 [MAIN_BOT_ID]（linkzone-bot），
 * conv_id 规则为 conv-{myId}-linkzone-bot；启动时幂等清理其它云端会话。
 * - 发送：经 [ChatSocketManager] WS 发给适配器（receiver_id = 本人），
 *   框架内部如何处理（智能体/插件/不回复）由框架决定，本层不关心
 * - 接收：按 WS payload 的 conv_id 路由——属于当前会话则展示并已读，
 *   属于其他会话只更新列表未读；框架 AI 上下文按 bot_id 天然隔离
 * - 历史/已读/撤回/删除/重命名走 REST（[ChatRepository]）
 *
 * 本地智能体会话（[LocalChatStore.CONV_PREFIX] 前缀）完全脱离服务器：
 * 会话与消息只存本机 Room（[LocalChatStore]），不经 WS/REST，媒体不上传。
 *
 * 本层不包含任何智能体概念；智能体偏好由智能体页面管理。
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val localStore: LocalChatStore,
    private val userRepository: UserRepository,
    private val socketManager: ChatSocketManager,
    private val llmRepository: LlmRepository,
    private val providerManager: ProviderManager,
    private val mcpManager: McpManager,
    private val workspaceManager: com.cjhsy.linkzone.workspace.WorkspaceManager,
    private val json: Json,
    /** Token 用量统计（本地会话每轮生成完成后记录） */
    private val usageStats: com.cjhsy.linkzone.data.stats.UsageStatsStore,
    /** 应用级 Context：本地工具（剪贴板/日历）与权限查询使用，不持 UI 引用 */
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 当前用户资料（消息气泡显示昵称/头像用），直接透传 UserRepository 的流 */
    val userProfile: StateFlow<com.cjhsy.linkzone.data.model.UserProfile?> = userRepository.profile

    /** 服务器会话列表（本地会话由 [localStore] 独立供流，两者合并为 [conversations]） */
    private val serverConvs = MutableStateFlow<List<ConvItem>>(emptyList())

    /** 会话列表 = 服务器会话 + 本地会话（置顶优先按最近时间倒序） */
    val conversations: StateFlow<List<ConvItem>> =
        combine(serverConvs, localStore.observeConversations()) { srv, loc ->
            sortConvs(srv + loc)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 唯一云端主会话（抽屉固定置顶项，独立于历史列表） */
    val mainConv: StateFlow<ConvItem?> = conversations
        .map { list -> list.firstOrNull { isMainConv(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 本地智能体会话列表（抽屉历史区；仅本地会话，排除所有云端会话） */
    val localConversations: StateFlow<List<ConvItem>> = conversations
        .map { list -> list.filter { isLocalConvId(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 本地智能体列表（抽屉「新建本地对话」选择器用） */
    val localAgents: StateFlow<List<LlmAgent>> = llmRepository.observeAgents()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前智能体关联的快捷指令（输入区上方一键填入 chips） */
    val quickMessages: StateFlow<List<LlmQuickMessage>> = combine(
        _state,
        llmRepository.observeQuickMessages()
    ) { s, all ->
        val ids = s.currentAgent?.quickMessageIds ?: emptySet()
        if (ids.isEmpty()) emptyList() else all.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 可选模型列表（多模型重答选择器，打开选择器时加载） */
    private val _chatModels = MutableStateFlow<List<ChatModelOption>>(emptyList())
    val chatModels: StateFlow<List<ChatModelOption>> = _chatModels.asStateFlow()

    /** 全部技能（扩展面板勾选 enabledSkills） */
    val skills: StateFlow<List<LlmSkill>> = llmRepository.observeSkills()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 全部提示词模式 / 世界书 / 快捷指令（扩展面板关联多选用全量清单） */
    val allPrompts: StateFlow<List<LlmPrompt>> = llmRepository.observePrompts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allLorebooks: StateFlow<List<LlmLorebook>> = llmRepository.observeLorebooks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allQuickMessages: StateFlow<List<LlmQuickMessage>> = llmRepository.observeQuickMessages()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 全部 MCP 服务器（扩展面板勾选 mcpServers） */
    val mcpServers: StateFlow<List<com.cjhsy.linkzone.data.ai.mcp.McpServerConfig>> =
        llmRepository.observeMcpServers()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** MCP 连接状态（配置 id → 状态） */
    val mcpStatus: StateFlow<Map<String, com.cjhsy.linkzone.data.ai.mcp.McpStatus>> =
        mcpManager.syncingStatus

    /** 本地智能体流式生成任务（stopGenerating 取消） */
    private var generatingJob: Job? = null

    /** 生成中用户又发了消息：当前生成结束后再跑一轮（排队，不阻塞发送） */
    @Volatile
    private var pendingDispatch = false

    /**
     * 本地会话自动标题跟踪：在集合中 = 标题仍是自动命名（首条消息截断），
     * AI 标题生成后仍可覆盖；用户手动重命名即移除锁定，AI 不再改标题。
     */
    private val autoTitledConvs = mutableSetOf<String>()

    /** ask_user 挂起中的应答通道；工具 execute 内 await，提交后 complete 继续 */
    private var pendingAskUser: CompletableDeferred<JsonElement>? = null

    /**
     * ask_user 桥：置入 UI 状态并挂起等待用户作答。
     * 用户停止生成时协程被取消，await 抛 CancellationException 随工具链路向上传播。
     */
    private val askUserBridge = com.cjhsy.linkzone.data.ai.AskUserBridge { questions ->
        val deferred = CompletableDeferred<JsonElement>()
        pendingAskUser = deferred
        _state.value = _state.value.copy(askUser = parseAskUserQuestions(questions))
        try {
            deferred.await()
        } finally {
            pendingAskUser = null
            _state.value = _state.value.copy(askUser = null)
        }
    }

    /** 解析 ask_user 工具入参为 UI 模型（宽松解析：缺 id 自动补序号） */
    private fun parseAskUserQuestions(args: JsonElement): AskUserPrompt? {
        val items = runCatching { args.jsonObject["questions"]?.jsonArray }.getOrNull()
            ?: return null
        val questions = items.mapIndexedNotNull { idx, el ->
            runCatching {
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.let { if (it is JsonNull) null else it.content }?.takeIf { it.isNotBlank() }
                    ?: "q$idx"
                val question = obj["question"]?.jsonPrimitive?.let { if (it is JsonNull) null else it.content }?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                AskUserQuestion(
                    id = id,
                    question = question,
                    options = obj["options"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.let { p -> if (p is JsonNull) null else p.content } }
                        .orEmpty(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.let { if (it is JsonNull) null else it.content } ?: "text"
                )
            }.getOrNull()
        }
        if (questions.isEmpty()) return null
        return AskUserPrompt(toolCallId = "", questions = questions)
    }

    /** 提交追问答案：{问题id: 答案}（多选/自定义文本以「、」拼接） */
    fun submitAskUser(answers: Map<String, String>) {
        val payload = buildJsonObject {
            answers.forEach { (id, answer) -> put(id, answer) }
        }
        pendingAskUser?.complete(payload)
    }

    /** 跳过追问：以提示语作答，模型可据此继续或改述 */
    fun skipAskUser() {
        pendingAskUser?.complete(buildJsonObject {
            put("_skipped", "The user skipped these questions. Continue with reasonable assumptions.")
        })
    }

    /** 上传中被用户删除的媒体消息 clientId（不含 local- 前缀），上传完成后放弃发送 */
    private val cancelledUploads = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 本人 linkzone_id（判定消息归属 + 生成会话 id） */
    private var myId: String = ""

    /**
     * App 前后台监听：回前台时探活 WS + HTTP 增量同步。
     * 后台期间系统会挂起网络/CPU，WS 必断或假死——
     * 消息不会丢（回复已落库），回前台这里把断连期间的消息/未读补回来。
     */
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
            socketManager.resume()
            syncAfterReconnect()
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        viewModelScope.launch {
            socketManager.events.collect { handleSocketEvent(it) }
        }
        viewModelScope.launch {
            // 优先用其他页面已拉取的 profile 缓存，避免启动重复请求
            val profile = userRepository.profile.value ?: userRepository.loadProfile().getOrNull()
            myId = profile?.linkzoneId ?: ""
            if (myId.isBlank()) {
                _state.value = _state.value.copy(error = "未能获取用户信息")
                return@launch
            }
            llmRepository.ensureDefaultAgent()
            bootstrapConversations()
            socketManager.connect()
        }
    }

    // ============ 会话管理 ============

    /**
     * 启动：云端有且只有一个主会话（conv-{myId}-linkzone-bot，bot=linkzone-bot）。
     * 先幂等清理历史遗留的其它云端会话（keep_main=1），再确保主会话存在并打开；
     * 主会话不可用（网络失败等）时回退打开最近的本地会话。
     */
    private suspend fun bootstrapConversations() {
        _state.value = _state.value.copy(loading = true, error = null)
        chatRepository.deleteAllConversations(keepMain = true)
        val main = chatRepository.ensureConversation(myId, MAIN_BOT_ID, MAIN_CONV_NAME).getOrNull()
        refreshConvList()

        val target = main
            ?: serverConvs.value.firstOrNull { isMainConv(it.id) }
            ?: localStore.getConversations().maxByOrNull { it.lastTime }
        if (target != null) {
            openConv(target)
        } else {
            _state.value = _state.value.copy(loading = false, error = "云端主会话不可用，请检查网络后重试")
        }
    }

    /** 会话列表排序：置顶优先，其余按最近时间倒序 */
    private fun sortConvs(convs: List<ConvItem>): List<ConvItem> =
        convs.sortedWith(compareByDescending<ConvItem> { it.pinned }.thenByDescending { it.lastTime })

    /**
     * 打开唯一云端主会话：列表里已有则直接切换；缺失（被清理/未创建）则重新 ensure 后打开。
     * 已在主会话时为 no-op，避免重复加载。云端不再支持"新建对话"，一切云端入口都汇聚到此。
     */
    fun openMainConversation() {
        viewModelScope.launch {
            val existing = serverConvs.value.firstOrNull { isMainConv(it.id) }
            if (existing != null) {
                if (existing.id != _state.value.currentConvId) openConv(existing)
                return@launch
            }
            chatRepository.ensureConversation(myId, MAIN_BOT_ID, MAIN_CONV_NAME).onSuccess { conv ->
                refreshConvList()
                openConv(conv)
            }.onFailure { e ->
                _state.value = _state.value.copy(error = e.message ?: "打开云端对话失败")
            }
        }
    }

    /**
     * 新建本地智能体会话（引擎选择器「本地智能体」）：
     * 完全脱离服务器——会话/消息只存本机 Room（[LocalChatStore]），
     * 智能体回复由本机流式生成后直接落本地库。
     */
    fun createLocalConversation(agent: LlmAgent) {
        viewModelScope.launch {
            val conv = localStore.createConversation(agent)
            // 预设对话（RikkaHub presetMessages）：新建会话时注入为用户/助手消息
            if (agent.presetMessages.isNotEmpty()) {
                val baseTs = System.currentTimeMillis() * 1000
                agent.presetMessages.forEachIndexed { i, preset ->
                    val isUser = preset.role.equals("user", ignoreCase = true)
                    localStore.insertMessage(
                        MessageItem(
                            id = UUID.randomUUID().toString(),
                            convId = conv.id,
                            senderId = if (isUser) "me" else LOCAL_BOT_PREFIX + agent.id,
                            senderName = if (isUser) "" else agent.name.ifBlank { "AI" },
                            segments = listOf(Segments.markdown(preset.content)),
                            content = preset.content,
                            isSelf = isUser,
                            timestamp = baseTs + i
                        )
                    )
                }
                localStore.touch(conv.id, agent.presetMessages.last().content.take(NAME_MAX_LEN), baseTs)
            }
            openConv(conv)
        }
    }

    /** bot-local-{agentId}（或旧格式 bot-local-{agentId}:{suffix}）→ agentId */
    private fun extractAgentId(botId: String): String =
        botId.removePrefix(LOCAL_BOT_PREFIX).substringBefore(':')

    /** 切换到指定会话 */
    fun switchConversation(convId: String) {
        val conv = conversations.value.firstOrNull { it.id == convId } ?: return
        if (conv.id == _state.value.currentConvId) return
        viewModelScope.launch { openConv(conv) }
    }

    /**
     * 删除会话（长按抽屉会话项）：仅允许删除本地会话；
     * 云端主会话是唯一云端入口，不可删除。
     * 删的是当前会话时，自动切换到下一个本地会话（没有则回到云端主会话）。
     */
    fun deleteConversation(convId: String) {
        if (!isLocalConvId(convId)) {
            _state.value = _state.value.copy(error = "云端对话不可删除")
            return
        }
        viewModelScope.launch {
            val wasCurrent = _state.value.currentConvId == convId
            localStore.deleteConversation(convId)
            if (wasCurrent) {
                val remaining = conversations.value.filter { it.id != convId && isLocalConvId(it.id) }
                remaining.firstOrNull()?.let { openConv(it) } ?: openMainConversation()
            }
        }
    }

    /**
     * 清空云端主会话聊天记录（保留会话本身）。
     * 清空后当前消息列表置空，会话列表该会话摘要复位。
     */
    fun clearMainConversation() {
        val convId = serverConvs.value.firstOrNull { isMainConv(it.id) }?.id
            ?: _state.value.currentConvId.takeIf { isMainConv(it) }
            ?: return
        viewModelScope.launch {
            chatRepository.clearMessages(convId)
                .onSuccess {
                    if (_state.value.currentConvId == convId) {
                        _state.value = _state.value.copy(messages = emptyList())
                    }
                    updateConvInList(convId) { it.copy(last_msg = "", unread = 0) }
                }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "清空失败") }
        }
    }

    /** 打开会话：切换当前状态 + 加载历史 + 已读 + 刷新列表未读 */
    private suspend fun openConv(conv: ConvItem) {
        if (isLocalConvId(conv.id)) {
            // 本地会话：加载绑定的 Agent，并叠加会话级覆盖配置（仅本会话生效）
            val localAgent = llmRepository.getAgent(extractAgentId(conv.target_id))
            val overrideAgent = localAgent?.let { base ->
                localStore.getAgentOverrideJson(conv.id)?.let { raw ->
                    runCatching {
                        json.decodeFromString(LlmAgent.serializer(), raw).copy(id = base.id)
                    }.getOrNull()
                }
            }
            _state.value = _state.value.copy(
                currentConvId = conv.id,
                currentBotId = conv.target_id,
                currentName = conv.name.ifBlank { NEW_CONV_NAME },
                currentAgent = overrideAgent ?: localAgent,
                agentOverridden = overrideAgent != null,
                messages = emptyList(),
                loading = true,
                ready = true,
                hasMore = false,
                generating = false,
                error = null
            )
            loadHistory()
            localStore.clearUnread(conv.id)
            return
        }
        _state.value = _state.value.copy(
            currentConvId = conv.id,
            currentBotId = conv.target_id,
            currentName = conv.name.ifBlank { NEW_CONV_NAME },
            currentAgent = null,
            messages = emptyList(),
            loading = true,
            ready = true,
            hasMore = false,
            generating = false,
            error = null
        )
        loadHistory()
        chatRepository.markRead(conv.id)
        // 本地列表立即清未读，后台再同步一次
        updateConvInList(conv.id) { it.copy(unread = 0) }
        refreshConvList()
    }

    private suspend fun refreshConvList() {
        chatRepository.loadConversations().onSuccess { convs ->
            serverConvs.value = sortConvs(convs.filter { it.type == "private" })
        }
    }

    // ============ WS 事件 ============

    private fun handleSocketEvent(event: ChatSocketManager.Event) {
        when (event) {
            is ChatSocketManager.Event.Connected -> {
                _state.value = _state.value.copy(connected = true)
                syncAfterReconnect()
            }

            is ChatSocketManager.Event.Disconnected -> {
                _state.value = _state.value.copy(connected = false)
            }

            is ChatSocketManager.Event.ChatMessage -> {
                if (event.senderId.isEmpty()) return
                if (event.receiverId != myId) return
                // 自己消息的服务端回显：用于替换 local- 乐观气泡（确认送达），
                // 不做未读累加
                val isSelfEcho = event.senderId == myId

                val convId = event.convId.ifEmpty { _state.value.currentConvId }
                // 本地会话不产生服务器事件：防御性跳过
                if (isLocalConvId(convId)) return
                val preview = event.text.ifBlank {
                    extractPreview(event.segments)
                }

                if (convId != _state.value.currentConvId) {
                    // 自己消息的回显不属于当前会话：说明本地气泡在别的会话，
                    // 跳过即可（切过去时全量历史会拿到正式消息）
                    if (isSelfEcho) return
                    // 其他会话的回复：仅累加未读/预览，不插入消息流
                    val known = serverConvs.value.any { it.id == convId }
                    updateConvInList(convId) { c ->
                        c.copy(
                            unread = c.unread + 1,
                            last_msg = preview.ifBlank { c.last_msg },
                            lastTime = event.timestamp.takeIf { it > 0 } ?: c.lastTime
                        )
                    }
                    // 列表里没有该会话（可能在其他设备创建）：刷新列表而不是丢消息
                    if (!known && convId.isNotBlank()) {
                        viewModelScope.launch { refreshConvList() }
                    }
                    return
                }

                appendMessage(
                    MessageItem(
                        id = event.messageId,
                        convId = convId,
                        senderId = event.senderId,
                        senderName = event.senderName,
                        segments = event.segments,
                        content = event.text,
                        isSelf = isSelfEcho,
                        timestamp = event.timestamp
                    )
                )
                if (!isSelfEcho) markRead()
                updateConvInList(convId) { c ->
                    c.copy(
                        unread = 0,
                        last_msg = preview.ifBlank { c.last_msg },
                        lastTime = event.timestamp.takeIf { it > 0 } ?: c.lastTime
                    )
                }
            }

            is ChatSocketManager.Event.Recalled -> {
                // 本地会话不产生服务器事件：防御性跳过
                if (isLocalConvId(_state.value.currentConvId)) return@handleSocketEvent
                // 服务端定向投递且携带 conv_id：只标记本会话；
                // convId 为空兼容旧服务端（退化为按 message_id 全局标记）
                if (event.convId.isNotEmpty() && event.convId != _state.value.currentConvId) return@handleSocketEvent
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map {
                        if (it.id == event.messageId) it.copy(recalled = true) else it
                    }
                )
            }
        }
    }

    /** 上次增量同步时间（elapsedRealtime），Connected 与回前台可能同时触发，节流 1s */
    @Volatile
    private var lastSyncAt = 0L

    /**
     * 重连 / 回前台后的增量同步。
     *
     * 后台期间 WS 必断或假死，但智能体回复在服务端已落库（数据库即持久消息队列，
     * 不丢消息、不怕服务端重启），所以这里走 HTTP 把断连期间的数据补回来：
     * 1. 刷新会话列表：各会话 unread / last_msg / last_time 全部补回（红点 + 最新预览）
     * 2. 当前打开的会话以「本地最新已确认消息时间戳」为锚点 after 增量补拉，
     *    只追加缺失消息——不做全量替换，保留用户已向上翻页加载的历史。
     *    锚点只取服务端消息（非 local-），不受手机时钟偏差影响。
     */
    private fun syncAfterReconnect() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncAt < 1_000L) return
        lastSyncAt = now

        viewModelScope.launch {
            refreshConvList()

            val convId = _state.value.currentConvId
            if (!_state.value.ready || convId.isBlank()) return@launch
            // 本地会话不经服务器：消息只存本机，重连无需补拉
            if (isLocalConvId(convId)) return@launch

            // 锚点只取服务端消息：排除 local- 乐观气泡，也排除本地生成中的
            // bot 占位气泡（bot-local-msg-，时间戳是“现在”，会把锚点推到未来，
            // 导致最后一条真实消息与占位之间的服务端消息被跳过）
            val anchor = _state.value.messages
                .filter { !it.id.startsWith(LOCAL_PREFIX) && !it.id.startsWith(BOT_LOCAL_MSG_PREFIX) }
                .maxOfOrNull { it.timestamp }
                ?: return@launch // 本地没有任何已确认消息：首次 openConv/bootstrap 已全量拉过

            // 断连很久可能积压多页，逐页补拉（每页 100，最多 3 页防极端流量）
            var after = anchor
            var fetched = emptyList<MessageItem>()
            var pages = 0
            while (pages < 3) {
                val resp = chatRepository.loadSince(convId, after = after, limit = 100).getOrNull()
                    ?: break
                if (resp.items.isEmpty()) break
                fetched = fetched + resp.items
                after = resp.items.maxOf { it.timestamp }
                pages++
                if (!resp.hasMore) break
            }
            if (fetched.isEmpty()) return@launch
            if (_state.value.currentConvId != convId) return@launch

            // fetched 在前：同一条消息若本地有 local- 乐观版、服务端有正式版，
            // distinctBy 保留先出现者——优先采用服务端正式版（含远端 URL）
            val merged = (fetched + _state.value.messages)
                .distinctBy { cleanId(it.id) }
                .sortedBy { it.timestamp }
            _state.value = _state.value.copy(messages = merged)

            // 正在看该会话：补拉回来的回复视为已读，清掉红点
            chatRepository.markRead(convId)
            updateConvInList(convId) { it.copy(unread = 0) }
            refreshConvList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
    }

    // ============ 历史消息 ============

    private fun loadHistory() {
        val convId = _state.value.currentConvId
        if (convId.isBlank()) return
        viewModelScope.launch {
            if (isLocalConvId(convId)) {
                val (items, hasMore) = localStore.loadMessagesPage(convId, before = null)
                // 响应返回期间用户已切走，丢弃过期结果，防止覆盖新会话
                if (_state.value.currentConvId != convId) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    messages = items,
                    hasMore = hasMore
                )
                return@launch
            }
            val result = chatRepository.loadHistory(convId)
            // 响应返回期间用户已切走，丢弃过期结果，防止覆盖新会话
            if (_state.value.currentConvId != convId) return@launch
            result.onSuccess { resp ->
                _state.value = _state.value.copy(
                    loading = false,
                    messages = resp.items.sortedBy { it.timestamp },
                    hasMore = resp.hasMore
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = it.message ?: "加载消息失败"
                )
            }
        }
    }

    /** 向上翻页加载更早的消息 */
    fun loadMore() {
        val s = _state.value
        if (!s.ready || !s.hasMore || s.loading) return
        val oldest = s.messages.firstOrNull()?.timestamp ?: return
        _state.value = s.copy(loading = true)
        viewModelScope.launch {
            if (isLocalConvId(s.currentConvId)) {
                val (items, hasMore) = localStore.loadMessagesPage(s.currentConvId, before = oldest)
                if (_state.value.currentConvId != s.currentConvId) {
                    _state.value = _state.value.copy(loading = false)
                    return@launch
                }
                val merged = (items + _state.value.messages).sortedBy { it.timestamp }
                _state.value = _state.value.copy(messages = merged, hasMore = hasMore, loading = false)
                return@launch
            }
            val result = chatRepository.loadHistory(s.currentConvId, before = oldest)
            if (_state.value.currentConvId != s.currentConvId) {
                // 用户已切走，恢复 loading 防止下次进不来
                _state.value = _state.value.copy(loading = false)
                return@launch
            }
            result.onSuccess { resp ->
                val merged = (resp.items + _state.value.messages)
                    .distinctBy { cleanId(it.id) }
                    .sortedBy { it.timestamp }
                _state.value = _state.value.copy(messages = merged, hasMore = resp.hasMore, loading = false)
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "加载失败")
            }
        }
    }

    // ============ 发送 ============

    /**
     * 发送文本到 LinkZone 适配器（消息发给框架，框架内部决定如何响应）。
     * @return 是否已进入发送通道；false 时 UI 应保留输入内容供用户重试
     */
    fun sendMessage(text: String): Boolean {
        val s = _state.value
        if (!s.ready || s.currentConvId.isBlank()) return false
        val content = text.trim()
        if (content.isEmpty()) return false
        return dispatch(listOf(Segments.text(content)), content)
    }

    /**
     * 附件选中即预上传（DeepSeek/豆包式）：输入栏附件入列后立即调用，
     * 完成后回调结果；发送时 remoteUrl 非空直接引用，免二次上传。
     */
    fun uploadPending(file: File, onDone: (Result<com.cjhsy.linkzone.data.model.UploadResponse>) -> Unit) {
        viewModelScope.launch { onDone(chatRepository.uploadFile(file)) }
    }

    /**
     * 组合发送（豆包式）：文字 + 多个附件合并为**单条多段消息**。
     * 先乐观上屏（本地 file:// 地址立即可见），后台逐个上传，
     * 全部上传完成后经 WS 发送完整段列表，服务端确认后回显替换。
     */
    fun sendCombined(text: String, attachments: List<PendingMediaItem>) {
        val s = _state.value
        if (!s.ready || s.currentConvId.isBlank()) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        // 用户开始输入新消息：清掉上一轮的回复建议
        _state.value = s.copy(aiSuggestions = emptyList())

        val clientId = UUID.randomUUID().toString()

        // 构建本地段（file:// 直接可预览）
        val localSegs = mutableListOf<MessageSegment>()
        if (trimmed.isNotEmpty()) localSegs.add(Segments.text(trimmed))
        for (att in attachments) {
            val type = att.type ?: guessSegType(att.file)
            localSegs.add(buildMediaSeg(type, "file://" + att.file.absolutePath, att.file.name, att.file.length(), 0L))
        }

        // 回复引用
        val replySeg = s.replyTo?.let {
            Segments.reply(messageId = it.id, senderId = it.senderId, senderName = it.senderName, preview = it.displayText.take(40))
        }
        val allLocal = if (replySeg != null) listOf(replySeg) + localSegs else localSegs
        val preview = extractPreview(allLocal)

        // 本地会话：不经 WS，直接落本地库
        if (isLocalConvId(s.currentConvId)) {
            // 无附件：纯文本直接落库
            if (attachments.isEmpty()) {
                val isFirst = s.messages.none { it.isSelf }
                val msg = MessageItem(
                    id = UUID.randomUUID().toString(),
                    convId = s.currentConvId,
                    senderId = myId,
                    senderName = "我",
                    segments = activeBranchConstraints() + allLocal,
                    content = preview,
                    isSelf = true,
                    timestamp = System.currentTimeMillis() * 1000
                )
                appendMessage(msg)
                if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
                viewModelScope.launch {
                    localStore.insertMessage(msg)
                    localStore.touch(s.currentConvId, preview, msg.timestamp)
                }
                if (replySeg != null) _state.value = _state.value.copy(replyTo = null)
                maybeDispatchLocal()
                return
            }

            // 有附件：先 file:// 上屏预览，后台上传服务端拿 URL 后替换段再发 AI。
            // 落库存服务端 URL，AI 上下文直接拿到可访问的图片链接（不再 base64）。
            val clientId = UUID.randomUUID().toString()
            val isFirst = s.messages.none { it.isSelf }
            appendMessage(MessageItem(
                id = clientId,
                convId = s.currentConvId,
                senderId = myId,
                senderName = "我",
                segments = activeBranchConstraints() + allLocal,
                content = preview,
                isSelf = true,
                timestamp = System.currentTimeMillis() * 1000,
                uploading = true
            ))
            if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
            if (replySeg != null) _state.value = _state.value.copy(replyTo = null)

            viewModelScope.launch {
                // 逐个上传附件，拿到服务端 URL 替换对应段
                val uploadedSegs = mutableListOf<MessageSegment>()
                if (replySeg != null) uploadedSegs.add(replySeg)
                if (trimmed.isNotEmpty()) uploadedSegs.add(Segments.text(trimmed))
                var allOk = true
                for (att in attachments) {
                    // 预上传已完成：直接引用服务端 URL，跳过重复上传
                    if (att.remoteUrl != null) {
                        val segType = att.type ?: att.remoteSegType.ifBlank { guessSegType(att.file) }
                        uploadedSegs.add(buildMediaSeg(
                            segType, att.remoteUrl,
                            att.remoteName.ifBlank { att.file.name }, att.remoteSize, 0L
                        ))
                        continue
                    }
                    val result = chatRepository.uploadFile(att.file)
                    if (result.isFailure) {
                        allOk = false
                        _state.value = _state.value.copy(
                            error = result.exceptionOrNull()?.message ?: "图片上传失败"
                        )
                        break
                    }
                    val upload = result.getOrThrow()
                    val segType = att.type ?: upload.segType.ifBlank { guessSegType(att.file) }
                    uploadedSegs.add(buildMediaSeg(segType, upload.url, upload.name, upload.size, 0L))
                }
                // 上传完成：替换消息段为服务端 URL 并落库，AI 上下文即可拿到 URL
                val finalSegs = activeBranchConstraints() + uploadedSegs
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map { m ->
                        if (m.id == clientId) m.copy(
                            segments = finalSegs,
                            uploading = false,
                            uploadFailed = !allOk
                        ) else m
                    }
                )
                if (allOk) {
                    val finalPreview = extractPreview(uploadedSegs)
                    val msg = _state.value.messages.firstOrNull { it.id == clientId } ?: return@launch
                    localStore.insertMessage(msg)
                    localStore.touch(s.currentConvId, finalPreview, msg.timestamp)
                    maybeDispatchLocal()
                }
            }
            return
        }

        // 乐观上屏
        val isFirst = s.messages.none { it.isSelf }
        appendMessage(MessageItem(
            id = LOCAL_PREFIX + clientId,
            convId = s.currentConvId,
            senderId = myId,
            senderName = "我",
            segments = allLocal,
            content = preview,
            isSelf = true,
            timestamp = System.currentTimeMillis() * 1000,
            uploading = attachments.isNotEmpty()
        ))
        if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
        updateConvInList(s.currentConvId) { c -> c.copy(last_msg = preview, lastTime = System.currentTimeMillis() * 1000) }
        if (replySeg != null) _state.value = _state.value.copy(replyTo = null)

        // 无附件时直接发文字
        if (attachments.isEmpty()) {
            val ok = socketManager.sendSegments(allLocal, s.currentConvId, myId, myId, s.currentBotId, clientId)
            if (!ok) {
                _state.value = _state.value.copy(error = "连接已断开，消息未发送")
                socketManager.connect()
                return
            }
            return
        }

        // 有附件：后台上传，全部完成后替换本地段为服务端 URL 并发送
        viewModelScope.launch {
            val remoteSegs = mutableListOf<MessageSegment>()
            if (replySeg != null) remoteSegs.add(replySeg)
            if (trimmed.isNotEmpty()) remoteSegs.add(Segments.text(trimmed))

            var allOk = true
            for (att in attachments) {
                if (clientId in cancelledUploads) {
                    cancelledUploads.remove(clientId)
                    return@launch
                }
                // 预上传已完成：直接引用服务端 URL，跳过重复上传
                if (att.remoteUrl != null) {
                    val segType = att.type ?: att.remoteSegType.ifBlank { guessSegType(att.file) }
                    remoteSegs.add(buildMediaSeg(
                        segType, att.remoteUrl,
                        att.remoteName.ifBlank { att.file.name }, att.remoteSize, 0L
                    ))
                    continue
                }
                val result = chatRepository.uploadFile(att.file)
                if (result.isFailure) {
                    allOk = false
                    markMediaStatus(clientId, failed = true)
                    _state.value = _state.value.copy(error = result.exceptionOrNull()?.message ?: "上传失败")
                    break
                }
                val upload = result.getOrThrow()
                val segType = att.type ?: upload.segType.ifBlank { guessSegType(att.file) }
                remoteSegs.add(buildMediaSeg(segType, upload.url, upload.name, upload.size, 0L))
            }
            if (!allOk) return@launch

            val ok = socketManager.sendSegments(remoteSegs, s.currentConvId, myId, myId, s.currentBotId, clientId)
            if (!ok) {
                markMediaStatus(clientId, failed = true)
                _state.value = _state.value.copy(error = "连接已断开，消息未发送")
                socketManager.connect()
                return@launch
            }
            // 上传完成：本地段替换为服务端 URL
            replaceLocalSegments(clientId, remoteSegs)
        }
    }

    /**
     * 发送图片 / 视频 / 语音 / 音频 / 文件（QQ 式）：
     * 1. 立即把本地文件以 file:// 地址插入消息流，界面马上可见可播；
     * 2. 后台上传，上传成功后替换为服务端 URL 并经 WS 发送（clientId 与乐观消息一致，
     *    服务端确认后走原有 local- 替换流程）；
     * 3. 上传失败消息保留并标红，可删除重发。
     *
     * @param file 已选中的本地文件
     * @param forcedType 强制段类型（"image"|"video"|"voice"|"file"），null 时按扩展名预判
     * @param durationSec 语音/视频时长（秒），无时长信息传 0
     */
    fun sendMedia(file: File, forcedType: String? = null, durationSec: Long = 0L) {
        val s = _state.value
        if (!s.ready || s.currentConvId.isBlank()) return

        // 本地会话：先 file:// 上屏预览，后台上传服务端拿 URL 后替换段再发 AI。
        // 落库存服务端 URL，AI 上下文直接拿到可访问的图片链接（不再 base64）。
        if (isLocalConvId(s.currentConvId)) {
            if (file.length() > MAX_UPLOAD_BYTES) {
                _state.value = _state.value.copy(error = "文件过大，限制 20MB")
                return
            }
            val clientId = UUID.randomUUID().toString()
            val localType = forcedType ?: guessSegType(file)
            val localSeg = buildMediaSeg(localType, "file://" + file.absolutePath, file.name, file.length(), durationSec)
            val replySeg = s.replyTo?.let {
                Segments.reply(
                    messageId = it.id,
                    senderId = it.senderId,
                    senderName = it.senderName,
                    preview = it.displayText.take(40)
                )
            }
            val localSegs = if (replySeg != null) listOf(replySeg, localSeg) else listOf(localSeg)
            val preview = localSeg.display
            val isFirst = s.messages.none { it.isSelf }
            appendMessage(MessageItem(
                id = clientId,
                convId = s.currentConvId,
                senderId = myId,
                senderName = "我",
                segments = activeBranchConstraints() + localSegs,
                content = preview,
                isSelf = true,
                timestamp = System.currentTimeMillis() * 1000,
                uploading = true
            ))
            if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
            if (replySeg != null) {
                _state.value = _state.value.copy(replyTo = null)
            }

            viewModelScope.launch {
                val result = chatRepository.uploadFile(file)
                if (result.isFailure) {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { m ->
                            if (m.id == clientId) m.copy(uploading = false, uploadFailed = true) else m
                        },
                        error = result.exceptionOrNull()?.message ?: "上传失败"
                    )
                    return@launch
                }
                val upload = result.getOrThrow()
                val segType = forcedType ?: upload.segType.ifBlank { localType }
                val remoteSeg = buildMediaSeg(segType, upload.url, upload.name, upload.size, durationSec)
                val remoteSegs = if (replySeg != null) listOf(replySeg, remoteSeg) else listOf(remoteSeg)
                val finalSegs = activeBranchConstraints() + remoteSegs
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map { m ->
                        if (m.id == clientId) m.copy(segments = finalSegs, uploading = false) else m
                    }
                )
                val msg = _state.value.messages.firstOrNull { it.id == clientId } ?: return@launch
                localStore.insertMessage(msg)
                localStore.touch(s.currentConvId, remoteSeg.display, msg.timestamp)
                maybeDispatchLocal()
            }
            return
        }

        // 客户端预检：超过服务端 20MB 限制就不必白传一遍
        if (file.length() > MAX_UPLOAD_BYTES) {
            _state.value = _state.value.copy(error = "文件过大，限制 20MB")
            return
        }

        val clientId = UUID.randomUUID().toString()
        val localType = forcedType ?: guessSegType(file)
        val localUrl = "file://" + file.absolutePath
        val localSeg = buildMediaSeg(localType, localUrl, file.name, file.length(), durationSec)

        val replySeg = s.replyTo?.let {
            Segments.reply(
                messageId = it.id,
                senderId = it.senderId,
                senderName = it.senderName,
                preview = it.displayText.take(40)
            )
        }
        val localSegs = if (replySeg != null) listOf(replySeg, localSeg) else listOf(localSeg)
        val preview = localSeg.display

        // ① 立即上屏：本地文件直接渲染（Coil / ExoPlayer / MediaPlayer 均支持 file://）
        val isFirst = s.messages.none { it.isSelf }
        appendMessage(
            MessageItem(
                id = LOCAL_PREFIX + clientId,
                convId = s.currentConvId,
                senderId = myId,
                senderName = "我",
                segments = localSegs,
                content = preview,
                isSelf = true,
                timestamp = System.currentTimeMillis() * 1000,
                uploading = true
            )
        )
        if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
        updateConvInList(s.currentConvId) { c ->
            c.copy(last_msg = preview, lastTime = System.currentTimeMillis() * 1000)
        }
        if (replySeg != null) {
            _state.value = _state.value.copy(replyTo = null)
        }

        // ② 后台上传 + WS 发送；不阻塞继续发其他消息
        viewModelScope.launch {
            val result = chatRepository.uploadFile(file)
            // 上传期间用户已删除该消息：放弃后续发送
            if (clientId in cancelledUploads) {
                cancelledUploads.remove(clientId)
                return@launch
            }
            if (result.isFailure) {
                markMediaStatus(clientId, failed = true)
                _state.value = _state.value.copy(
                    error = result.exceptionOrNull()?.message ?: "上传失败"
                )
                return@launch
            }
            val upload = result.getOrThrow()
            val segType = forcedType ?: upload.segType.ifBlank { localType }
            val remoteSeg = buildMediaSeg(segType, upload.url, upload.name, upload.size, durationSec)
            val remoteSegs = if (replySeg != null) listOf(replySeg, remoteSeg) else listOf(remoteSeg)

            val ok = socketManager.sendSegments(
                segments = remoteSegs,
                convId = s.currentConvId,
                senderId = myId,
                receiverId = myId,
                botId = s.currentBotId,
                messageId = clientId
            )
            if (!ok) {
                markMediaStatus(clientId, failed = true)
                _state.value = _state.value.copy(error = "连接已断开，消息未发送")
                socketManager.connect()
                return@launch
            }
            // ③ 已进入发送通道：本地消息替换为服务端 URL（防止缓存文件被清理后裂图），
            //    保持 local- id 直到服务端确认消息到达
            replaceLocalSegments(clientId, remoteSegs)
        }
    }

    /** 按扩展名预判媒体段类型（与服务端上传归类保持一致） */
    private fun guessSegType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "image"
            "mp4", "mov", "mkv", "avi", "3gp", "webm", "m4v" -> "video"
            "mp3", "aac", "wav", "flac", "ogg", "m4a" -> "audio"
            else -> "file"
        }
    }

    /** 构造媒体消息段（本地预览与远端发送共用同一套字段） */
    private fun buildMediaSeg(
        segType: String,
        url: String,
        name: String,
        size: Long,
        durationSec: Long
    ): MessageSegment = when (segType) {
        "image" -> Segments.image(url, name, size = size)
        "flash" -> Segments.flash(url, name)
        "video" -> Segments.video(url, durationSec)
        "voice" -> Segments.voice(url, durationSec)
        "audio" -> Segments.audio(url, title = name)
        else -> Segments.file(url, name, size)
    }

    /** 更新上传中媒体消息的状态（找不到对应消息时忽略，可能已切换会话） */
    private fun markMediaStatus(clientId: String, failed: Boolean) {
        val localId = LOCAL_PREFIX + clientId
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m ->
                if (m.id == localId) {
                    m.copy(uploading = !failed, uploadFailed = failed)
                } else m
            }
        )
    }

    /** 上传完成后用服务端段替换本地段 */
    private fun replaceLocalSegments(clientId: String, segments: List<MessageSegment>) {
        val localId = LOCAL_PREFIX + clientId
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m ->
                if (m.id == localId) {
                    m.copy(segments = segments, content = segments.displayText(), uploading = false)
                } else m
            }
        )
    }

    /**
 * 发送面板构造的任意消息段（位置/名片/音乐/投票/骰子等）。
     */
    fun sendSegments(segments: List<MessageSegment>, preview: String = "") {
        val s = _state.value
        if (!s.ready || s.currentConvId.isBlank() || segments.isEmpty()) return
        dispatch(segments, preview.ifBlank { segments.joinToString("") { it.display } })
    }

    /**
     * 统一发送通道：
     * - 若处于回复状态，自动前置 reply 段（发送后清除引用）
     * - WS 发送 → 乐观追加 → 首消息命名 → 会话预览更新
     */
    private fun dispatch(segments: List<MessageSegment>, preview: String): Boolean {
        val s = _state.value
        // 用户发送新消息：清掉上一轮的 AI 回复建议
        if (s.aiSuggestions.isNotEmpty()) {
            _state.value = s.copy(aiSuggestions = emptyList())
        }
        val isLocalConv = isLocalConvId(s.currentConvId)
        val replySeg = s.replyTo?.let {
            Segments.reply(
                messageId = it.id,
                senderId = it.senderId,
                senderName = it.senderName,
                preview = it.displayText.take(40)
            )
        }
        val all = if (replySeg != null) listOf(replySeg) + segments else segments

        // 客户端生成消息 ID：云端乐观气泡与服务端落库共用同一 ID，
        // 重连对齐时按 id 去重，不会出现「本地一条 + 服务端一条」的重复消息；
        // 本地会话无确认流程，该 ID 即最终 ID
        val clientId = UUID.randomUUID().toString()

        if (isLocalConv) {
            // 本地会话：不经 WS/服务器，直接落本地库；
            // 挂上当前分支约束（sys.br），切上游版本时新尾巴随分支切换
            val isFirst = s.messages.none { it.isSelf }
            val msg = MessageItem(
                id = clientId,
                convId = s.currentConvId,
                senderId = myId,
                senderName = "我",
                segments = activeBranchConstraints() + all,
                content = preview,
                isSelf = true,
                timestamp = System.currentTimeMillis() * 1000
            )
            appendMessage(msg)
            if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
            viewModelScope.launch {
                localStore.insertMessage(msg)
                localStore.touch(s.currentConvId, preview, msg.timestamp)
            }
            if (replySeg != null) {
                _state.value = _state.value.copy(replyTo = null)
            }
            maybeDispatchLocal()
            return true
        }

        val ok = socketManager.sendSegments(
            segments = all,
            convId = s.currentConvId,
            senderId = myId,
            receiverId = myId,
            botId = s.currentBotId,
            messageId = clientId
        )
        if (!ok) {
            // 基于最新状态写错误（不能复用入口快照 s，期间状态可能已变化）
            _state.value = _state.value.copy(
                sending = false,
                error = "连接已断开，消息未发送"
            )
            socketManager.connect()
            return false
        }

        val isFirst = s.messages.none { it.isSelf }
        appendMessage(
            MessageItem(
                id = LOCAL_PREFIX + clientId,
                convId = s.currentConvId,
                senderId = myId,
                senderName = "我",
                segments = all,
                content = preview,
                isSelf = true,
                timestamp = System.currentTimeMillis() * 1000
            )
        )
        // 当前仍是默认名就重命名（首条消息，或历史遗留的未命名会话）
        if (isFirst || s.currentName == NEW_CONV_NAME) maybeNameConversation(preview)
        updateConvInList(s.currentConvId) { c ->
            c.copy(last_msg = preview, lastTime = System.currentTimeMillis() * 1000)
        }
        if (replySeg != null) {
            _state.value = _state.value.copy(replyTo = null)
        }
        return true
    }

    // ============ 本地智能体流式生成 ============

    /** 中断标记：本轮由用户主动停止（而非异常），用于在气泡尾部标记"已中断"并提供继续生成 */
    private var interruptedByUser = false

    /** 用户主动停止生成 */
    fun stopGenerating() {
        interruptedByUser = true
        pendingDispatch = false
        generatingJob?.cancel()
        generatingJob = null
    }

    /** 用户消息已入会话后触发本地生成；同一会话正在生成时排队，结束后续跑 */
    private fun maybeDispatchLocal() {
        if (!isLocalConv()) return
        val botId = _state.value.currentBotId
        if (generatingJob?.isActive == true) {
            pendingDispatch = true
            return
        }
        generatingJob = viewModelScope.launch {
            runLocalGeneration(botId)
            // 当前轮结束：若期间有新消息入队，再跑一轮（直到队列清空）
            while (pendingDispatch) {
                pendingDispatch = false
                runLocalGeneration(botId)
            }
        }
    }

    /**
     * 本地智能体回复生成：
     * 组装 system + 上下文 → ProviderManager 流式请求 → 80ms 节流刷新气泡 →
     * 结束后（正常/停止/异常）把已生成文本以 markdown 段经 REST upsert 落库同步。
     *
     * @param overrideModelId 非空时用指定模型生成（多模型重答），忽略 Agent 绑定模型
     * @param trimTrailingAssistant 生成前从上下文末尾剔除 bot 回复（重生成/换模型重答
     *   保留原回复上屏为旧版本，但不参与本次上下文，避免模型对着旧答案复述）
     * @param versionAnchorId 非空时本次回复作为该节点的新版本（携带 sys.ver 段、
     *   时间戳 = 锚点 + versionIdx，版本聚集在锚点位置，‹ n/m › 可切换）
     * @param continueMode 续写模式：在现有最后一条 bot 回复基础上继续生成（用户中断后点「继续生成」），
     *   上下文末尾保留已有回复并提示模型接着写
     */
    private suspend fun runLocalGeneration(
        botId: String,
        overrideModelId: String? = null,
        trimTrailingAssistant: Boolean = false,
        versionAnchorId: String? = null,
        versionIdx: Int = 0,
        versionAnchorTs: Long = 0L,
        continueMode: Boolean = false
    ) {
        val convId = _state.value.currentConvId
        // 优先用当前会话的智能体快照（含会话级覆盖配置），保证 AI 扩展面板的
        // 改动（模型/参数/提示词/开关/工具）**即时生效**；无快照时回退数据库
        val baseAgentId = extractAgentId(botId)
        val agent = _state.value.currentAgent?.takeIf { it.id == baseAgentId }
            ?: llmRepository.getAgent(baseAgentId)
        if (agent == null) {
            _state.value = _state.value.copy(error = "智能体不存在或已被删除")
            return
        }
        val resolved = if (overrideModelId != null) resolveChatModelById(overrideModelId) else null
        val (setting, model) = resolved ?: (resolveChatModel(agent) ?: run {
            _state.value = _state.value.copy(error = "尚未配置可用模型，请到「设置 → AI 模型服务」添加")
            return
        })

        _state.value = _state.value.copy(generating = true)
        // 前台服务保活：退到后台/锁屏时生成不被系统杀死
        com.cjhsy.linkzone.service.ChatGenerationForegroundService.acquire(appContext, convId)

        // 在插入占位气泡前组装上下文（此时消息流末尾是刚发送的用户消息）。
        // 上下文组装含 PDF/文档解析与图片压缩编码（CPU+IO 密集），必须离开主线程，
        // 否则带图/带文件生成时界面冻结数秒，系统可能弹 ANR 被当成"闪退"
        val context = withContext(Dispatchers.IO) {
            buildLocalContext(agent, model, trimTrailingAssistant || continueMode)
        }
            .let { ctx ->
                if (continueMode) {
                    // 续写：末尾附"请继续"提示，让模型接着上一条回复写
                    ctx + UIMessage.user("（请从上一条回复中断处继续，接着写完，不要重复已有内容）")
                } else ctx
            }

        // 工具集：记忆（memory_tool）+ 联网搜索（search_web/scrape_web）+ 技能（use_skill）
        // + MCP 服务器工具（mcp__{服务器}__{工具}），按需注入工具循环
        val memoryAgentId = if (agent.useGlobalMemory) {
            LlmRepository.MEMORY_GLOBAL_ID
        } else {
            agent.id
        }
        val memories = if (agent.enableMemory) {
            llmRepository.getMemoriesOfAgent(memoryAgentId)
        } else {
            emptyList()
        }
        val searchOptions = if (agent.enableWebSearch) llmRepository.getSelectedSearchOptions() else null
        val tools = buildList {
            if (agent.enableMemory) {
                addAll(
                    buildMemoryTools(
                        json = json,
                        onCreation = { content -> llmRepository.addMemory(memoryAgentId, content) },
                        onUpdate = { id, content -> llmRepository.updateMemoryContent(id, content) },
                        onDelete = { id -> llmRepository.deleteMemory(id) },
                    )
                )
            }
            addAll(createSearchTools(searchOptions))
            addAll(createSkillTools(agent.enabledSkills, llmRepository.getSkills()))
            // 本地工具：时间感知/剪贴板/追问/日历/TTS，按智能体开关启用
            addAll(createLocalTools(appContext, agent.localTools, askUserBridge))
            // 会话查询工具：让模型按需检索历史会话（仅本地会话可见）
            addAll(createConversationTools(
                loadConversations = {
                    localStore.getConversations().map {
                        ConvSummary(it.id, it.name, it.last_msg, it.lastTime)
                    }
                },
                loadAllMessages = { convId -> localStore.loadAllMessages(convId) }
            ))
            // 图片生成：模型按用户要求主动出图，结果直接渲染进回复气泡
            if (agent.enableImageGeneration) {
                resolveImageModel(agent)?.let { (imgSetting, imgModel) ->
                    add(buildImageGenerateTool(appContext, providerManager, imgSetting, imgModel))
                }
            }
            // 代码工作区：绑定工作区后注入 read_file/write_file/edit_file/shell
            agent.workspaceId?.let { wsId ->
                workspaceManager.get(wsId)?.let { ws ->
                    addAll(
                        createWorkspaceTools(
                            workspace = ws,
                            runner = com.cjhsy.linkzone.workspace.WorkspaceShellRunner
                                .select(appContext.filesDir)
                        )
                    )
                }
            }
            mcpManager.getAllAvailableTools(agent.mcpServers).forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { args -> mcpManager.callTool(serverId, tool.name, args.jsonObject) },
                    )
                )
            }
        }
        // 记忆与工具索引拼进系统提示（顺序对齐 RikkaHub：记忆块在前、工具索引在后）
        val memoryPrompt = if (agent.enableMemory) buildMemoryPrompt(memories) else ""
        val toolPrompt = tools.joinToString("\n") { it.systemPrompt(model, context) }.trim()
        val extraPrompt = listOf(memoryPrompt.trim(), toolPrompt)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val baseContext = if (extraPrompt.isNotBlank()) mergeSystemPrompt(context, extraPrompt) else context

        // 占位气泡：首个 delta 到达前显示 …
        // 换模型重答的回复带模型名署名，便于区分同问多答；
        // 版本化生成（重生成/多模型重答）携带 sys.ver 段并锚定时间戳
        val senderName = agent.name.ifBlank { "AI" } +
            if (overrideModelId != null) " · " + model.displayName.ifBlank { model.modelId } else ""
        // 续写模式：复用最后一条 bot 消息（清除中断标记），不新建占位气泡；
        // 起始文本取已有正文，流式在其后追加
        val continueFromMsg = if (continueMode) {
            _state.value.messages.lastOrNull { !it.isSelf && it.senderId == botId }
        } else null
        val botMsgId: String
        if (continueFromMsg != null) {
            botMsgId = continueFromMsg.id
            // 清掉中断标记段，保持纯正文继续追加
            _state.value = _state.value.copy(
                messages = _state.value.messages.map { m ->
                    if (m.id == botMsgId) m.copy(
                        segments = m.segments?.filter { it.type != "sys.stop" }
                    ) else m
                }
            )
        } else {
            botMsgId = "bot-local-msg-" + UUID.randomUUID()
            val placeholderSegs = buildList {
                if (versionAnchorId != null) {
                    // 版本化生成：继承锚点**当前选中版本**的分支约束（嵌套分支）+ sys.ver 段，
                    // 新版本属于锚点新分支，旧尾巴已钉到旧版本
                    _state.value.messages.toMessageNodes()
                        .firstOrNull { it.anchorId == versionAnchorId }
                        ?.selected?.branchConstraints()
                        ?.forEach { add(branchSegment(it.first, it.second)) }
                    add(versionSegment(versionAnchorId, versionIdx, versionIdx, System.currentTimeMillis() * 1000))
                } else {
                    // 普通追加：挂到当前可见分支（切上游版本时新尾巴随分支切换）
                    addAll(activeBranchConstraints())
                }
                add(Segments.markdown("…"))
            }
            appendMessage(
                MessageItem(
                    id = botMsgId,
                    convId = convId,
                    senderId = botId,
                    senderName = senderName,
                    segments = placeholderSegs,
                    content = "…",
                    isSelf = false,
                    timestamp = if (versionAnchorId != null) versionAnchorTs + versionIdx
                    else System.currentTimeMillis() * 1000
                )
            )
        }

        val handler = StreamChunkHandler(model)
        var uiMessages = baseContext
        // 续写模式从已有正文开始追加
        var latestText = continueFromMsg?.content ?: ""
        var latestThinking = ""
        var lastEmitAt = 0L
        // 工具产出的图片（image_generate 等）：收尾时追加进回复气泡并落库
        val generatedImageUrls = mutableListOf<String>()

        try {
            var step = 0
            while (true) {
                if (++step > MAX_TOOL_STEPS) {
                    _state.value = _state.value.copy(error = "工具调用步数超过 $MAX_TOOL_STEPS，已中止")
                    break
                }
                val stepStartedAt = System.currentTimeMillis()
                providerManager.getProviderByType(setting)
                    .streamText(
                        providerSetting = setting,
                        messages = uiMessages,
                        params = TextGenerationParams(
                            model = model,
                            temperature = agent.temperature,
                            topP = agent.topP,
                            maxTokens = agent.maxTokens,
                            tools = tools,
                            reasoningLevel = agent.reasoningLevel,
                            customHeaders = setting.customHeaders + agent.customHeaders,
                            customBody = agent.customBodies,
                        )
                    )
                    .collect { chunk ->
                        uiMessages = handler.handle(uiMessages, chunk)
                        if (uiMessages.last().role == MessageRole.ASSISTANT) {
                            val tail = uiMessages.last().toText()
                            // 续写模式：handler 每轮从 baseContext 重新开始，新输出只含增量尾部，
                            // 气泡显示 = 已有正文 + 新增量；避免被重置丢内容
                            latestText = if (continueMode) (continueFromMsg?.content ?: "") + tail else tail
                            // 思考过程（reasoning part）随流式累积，气泡内折叠展示
                            latestThinking = uiMessages.last().parts
                                .filterIsInstance<UIMessagePart.Reasoning>()
                                .joinToString("") { it.reasoning }
                        }
                        val now = System.currentTimeMillis()
                        if (chunk is StreamChunk.Finish || now - lastEmitAt >= THROTTLE_MS) {
                            lastEmitAt = now
                            updateLocalBotBubble(botMsgId, latestText, latestThinking)
                        }
                    }

                // 用量统计：本轮 tokens 记入 usage_stats.json。
                // Usage chunk 会被 merge 到流末尾的 assistant 消息上（新一轮覆盖旧值），
                // 所以每轮记录后清零，保证多步工具循环逐轮累计不重复
                val lastMsg = uiMessages.lastOrNull()
                val lastUsage = lastMsg?.takeIf { it.role == MessageRole.ASSISTANT }?.usage
                if (lastUsage != null && (lastUsage.promptTokens > 0 || lastUsage.completionTokens > 0)) {
                    try {
                        usageStats.record(
                            modelId = model.modelId,
                            promptTokens = lastUsage.promptTokens.toLong(),
                            completionTokens = lastUsage.completionTokens.toLong(),
                            durationMs = System.currentTimeMillis() - stepStartedAt,
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 统计写盘失败不影响生成流程
                    }
                    uiMessages = uiMessages.dropLast(1) + lastMsg.copy(usage = TokenUsage())
                }

                // 本轮回复是否携带未执行的工具调用；没有则生成结束
                val lastMessage = uiMessages.lastOrNull() ?: break
                val pendingCalls = lastMessage
                    .takeIf { it.role == MessageRole.ASSISTANT }
                    ?.getTools()
                    ?.filter { !it.isExecuted }
                    .orEmpty()
                if (pendingCalls.isEmpty() || tools.isEmpty()) break

                // 执行工具（技能加载等），气泡在**保留正文**的前提下于尾部追加执行状态，
                // 而不是整段替换（否则已生成的回复在工具执行期间会"消失"）
                if (pendingCalls.isNotEmpty()) {
                    val hint = pendingCalls.joinToString("、") { it.toolName }
                    updateLocalBotBubble(
                        botMsgId, latestText, latestThinking,
                        statusHint = "⚙ 正在调用 $hint …"
                    )
                }
                val executedTools = executeToolCalls(tools, pendingCalls)
                // 收集工具输出中的图片（image_generate），收尾时渲染进回复气泡
                executedTools.flatMap { it.output }
                    .filterIsInstance<UIMessagePart.Image>()
                    .map { it.url }
                    .filter { it.startsWith("file://") && it !in generatedImageUrls }
                    .let { generatedImageUrls.addAll(it) }
                uiMessages = uiMessages.dropLast(1) + lastMessage.copy(
                    parts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            executedTools.firstOrNull { it.toolCallId == part.toolCallId } ?: part
                        } else part
                    }
                )
                // 工具结果回传后继续下一轮生成（同一助手消息继续追加文本）
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: "生成失败")
        } finally {
            // 停止/异常/正常结束统一收尾；NonCancellable 保证取消后仍能落库
            withContext(NonCancellable) {
                // finalize 内部异常（如落库失败）不得中断收尾：
                // 否则 sys.stop 标记与 generating 复位被跳过，
                // UI 永远卡在生成态、中断后不出现「继续生成」按钮
                runCatching {
                    finalizeLocalBotMessage(
                        convId, botId, agent, model, botMsgId, senderName,
                        latestText, latestThinking, generatedImageUrls
                    )
                }
                // 用户主动中断：在气泡尾部追加"已中断"标记（正文保留不撤回），
                // 消息行上会出现「继续生成」按钮，点击后 sendContinueGeneration 续写
                if (interruptedByUser) {
                    interruptedByUser = false
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { m ->
                            // 正文或思考过程非空（思考阶段中断也要保留并可续写）
                            val hasThinking = m.segments?.any { it.type == "thinking" } == true
                            if (m.id == botMsgId && (m.content.isNotBlank() || hasThinking)) {
                                val segs = (m.segments ?: emptyList()) +
                                    MessageSegment(
                                        type = "sys.stop",
                                        data = mapOf("reason" to kotlinx.serialization.json.JsonPrimitive("user"))
                                    )
                                m.copy(segments = segs)
                            } else m
                        }
                    )
                    // 同步落库（本地会话），重启后仍能看到中断标记
                    if (isLocalConvId(convId)) {
                        runCatching {
                            val msg = _state.value.messages.firstOrNull { it.id == botMsgId }
                            if (msg != null) localStore.insertMessage(msg) // REPLACE 语义更新
                        }
                    }
                }
                _state.value = _state.value.copy(generating = false)
                com.cjhsy.linkzone.service.ChatGenerationForegroundService.release(appContext)
            }
        }
    }

    /** 点击「继续生成」：在现有回复基础上续写（Deepseek 式） */
    fun continueGeneration() {
        if (!isLocalConv() || generatingJob?.isActive == true) return
        val botId = _state.value.currentBotId
        generatingJob = viewModelScope.launch {
            runLocalGeneration(botId, continueMode = true)
            while (pendingDispatch) {
                pendingDispatch = false
                runLocalGeneration(botId)
            }
        }
    }

    /** 生成结束：无内容移除占位，有内容刷新气泡并落库（本地 Room / 云端 upsert） */
    private suspend fun finalizeLocalBotMessage(
        convId: String,
        botId: String,
        agent: LlmAgent,
        model: Model,
        botMsgId: String,
        senderName: String,
        text: String,
        thinking: String = "",
        imageUrls: List<String> = emptyList()
    ) {
        val trimmed = text.trim()
        // 剥离正文中的 <think> 标签（部分模型把思考过程写进正文而非 reasoning 通道）
        val (thinkFromText, cleanText) = extractThinkTags(trimmed)
        val finalText = cleanText.trim()
        val finalThinking = (thinking + thinkFromText).trim()
        // 用户中断时即使正文还没开始（只有思考过程），也保留气泡：思考内容可回看，
        // 且下方需要出现「继续生成」按钮；无思考无正文（刚发就停）才移除占位
        val keepThinkingOnly = interruptedByUser && finalThinking.isNotBlank()
        if (finalText.isEmpty() && imageUrls.isEmpty() && !keepThinkingOnly) {
            _state.value = _state.value.copy(
                messages = _state.value.messages.filterNot { it.id == botMsgId }
            )
            return
        }
        // 版本化生成的消息：保留占位气泡携带的版本/分支元数据段（sys.ver / sys.br）
        val sysSegs = _state.value.messages.firstOrNull { it.id == botMsgId }
            ?.segments?.filter { it.type == VERSION_SEG || it.type == BRANCH_SEG }.orEmpty()
        val segments = buildList {
            addAll(sysSegs)
            if (finalThinking.isNotBlank()) add(Segments.thinking(finalThinking))
            if (finalText.isNotBlank()) add(Segments.markdown(finalText))
            imageUrls.forEach { url -> add(Segments.image(url = url)) }
        }
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m ->
                if (m.id == botMsgId) m.copy(segments = segments, content = finalText) else m
            }
        )
        // 占位气泡已被用户删除/重新生成清掉：本地都不在了，不再落库陈旧内容
        if (_state.value.messages.none { it.id == botMsgId }) return
        if (isLocalConvId(convId)) {
            // 本地会话：占位气泡即最终消息（同 id），直接落本地库；
            // 版本化生成的消息沿用占位气泡的锚点时间戳，保证版本聚集
            val placeholder = _state.value.messages.firstOrNull { it.id == botMsgId }
            val timestamp = placeholder?.timestamp ?: System.currentTimeMillis() * 1000
            localStore.insertMessage(
                MessageItem(
                    id = botMsgId,
                    convId = convId,
                    senderId = botId,
                    senderName = senderName,
                    segments = segments,
                    content = finalText,
                    isSelf = false,
                    timestamp = timestamp
                )
            )
            localStore.touch(
                convId,
                finalText.ifBlank { if (imageUrls.isNotEmpty()) "[图片]" else "[思考中断]" }.take(NAME_MAX_LEN),
                // 会话排序用真实当前时间（旧消息重生成后会话应置顶）；
                // 消息自身时间戳沿用锚点聚集值，仅用于版本排序/分页
                System.currentTimeMillis() * 1000
            )
            // 首答完成：AI 生成会话标题（失败回退首条消息）+ 回复建议
            if (finalText.isNotBlank()) {
                maybeGenerateLlmTitle(
                    convId = convId,
                    agent = agent,
                    userText = _state.value.messages.firstOrNull { it.isSelf }?.content.orEmpty(),
                    aiText = finalText
                )
                maybeGenerateSuggestions(agent, model, finalText)
            }
            return
        }
        chatRepository.upsertMessage(
            convId,
            UpsertMsgRequest(
                id = UUID.randomUUID().toString(),
                segments = json.encodeToJsonElement(ListSerializer(MessageSegment.serializer()), segments),
                content = finalText,
                senderId = botId,
                senderName = senderName,
                isSelf = false,
                timestamp = System.currentTimeMillis() * 1000
            )
        ).onSuccess { saved ->
            // 服务端正式消息（其他设备可经增量同步拿到）替换本地占位
            _state.value = _state.value.copy(
                messages = _state.value.messages.map { if (it.id == botMsgId) saved else it }
            )
        }
    }

    /** 刷新本地生成中的 bot 气泡文本（空文本显示 …）；thinking 非空时前置思考过程段；保留 sys.ver 版本段 */
    private fun updateLocalBotBubble(
        msgId: String,
        text: String,
        thinking: String = "",
        /** 非空时在正文尾部追加工具执行状态行（不覆盖正文），流式恢复后自动消失 */
        statusHint: String? = null,
    ) {
        val (thinkFromText, clean) = extractThinkTags(text)
        val mergedThinking = (thinking + thinkFromText).trim()
        val md = clean.ifBlank { if (statusHint != null) "" else "…" }
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { m ->
                if (m.id == msgId) {
                    val segments = buildList {
                        // 保留版本/分支元数据段（sys.ver / sys.br），流式刷新不丢失
                        addAll(m.segments?.filter {
                            it.type == VERSION_SEG || it.type == BRANCH_SEG
                        }.orEmpty())
                        if (mergedThinking.isNotBlank()) add(Segments.thinking(mergedThinking))
                        if (md.isNotBlank()) add(Segments.markdown(md))
                        if (!statusHint.isNullOrBlank()) add(Segments.markdown(statusHint))
                    }
                    m.copy(segments = segments, content = (listOf(md, statusHint).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")))
                } else m
            }
        )
    }

    /** 解析 Agent 绑定的模型；未绑定时回退第一个可用 Provider 的首个 CHAT 模型 */
    private suspend fun resolveChatModel(agent: LlmAgent): Pair<ProviderSetting, Model>? {
        val providers = llmRepository.getProviders().filter { it.enabled }
        agent.chatModelId?.let { modelId ->
            providers.forEach { p ->
                p.models.firstOrNull { it.modelId == modelId }?.let { return p to it }
            }
        }
        providers.forEach { p ->
            p.models.firstOrNull { it.type == ModelType.CHAT }?.let { return p to it }
        }
        return null
    }

    /** 按 modelId 精确解析模型（多模型重答用）；找不到返回 null 回退默认解析 */
    private suspend fun resolveChatModelById(modelId: String): Pair<ProviderSetting, Model>? {
        llmRepository.getProviders().filter { it.enabled }.forEach { p ->
            p.models.firstOrNull { it.modelId == modelId }?.let { return p to it }
        }
        return null
    }

    /**
     * 解析图片生成模型：优先智能体指定的 imageModelId，否则取第一个 IMAGE 类型模型。
     * 指定的 id 匹配时不限制模型类型（用户可能把图像接口模型标成 CHAT）。
     */
    private suspend fun resolveImageModel(agent: LlmAgent): Pair<ProviderSetting, Model>? {
        val providers = llmRepository.getProviders().filter { it.enabled }
        agent.imageModelId?.let { id ->
            providers.forEach { p ->
                p.models.firstOrNull { it.modelId == id }?.let { return p to it }
            }
        }
        providers.forEach { p ->
            p.models.firstOrNull { it.type == ModelType.IMAGE }?.let { return p to it }
        }
        return null
    }

    /** 解析 OCR 视觉模型：优先 ocrModelId，否则取第一个支持 IMAGE 输入的 CHAT 模型 */
    private suspend fun resolveVisionChatModel(preferredId: String?): Pair<ProviderSetting, Model>? {
        val providers = llmRepository.getProviders().filter { it.enabled }
        preferredId?.let { id ->
            providers.forEach { p ->
                p.models.firstOrNull { it.modelId == id }?.let { return p to it }
            }
        }
        providers.forEach { p ->
            p.models.firstOrNull {
                it.type == ModelType.CHAT && Modality.IMAGE in it.inputModalities
            }?.let { return p to it }
        }
        return null
    }

    /** 组装 LLM 请求消息：system（prompt 变量替换）+ 历史上下文（阶梯式截断）+ 提示词注入 */
    private suspend fun buildLocalContext(
        agent: LlmAgent,
        model: Model,
        trimTrailingAssistant: Boolean = false
    ): List<UIMessage> {
        val messages = mutableListOf<UIMessage>()
        val systemPrompt = renderSystemPrompt(agent, model)
        if (systemPrompt.isNotBlank()) {
            messages += UIMessage.system(systemPrompt)
        }
        // 上下文源：按版本节点取每个节点当前选中的版本（旧版本不进上下文）
        val selected = _state.value.messages.toMessageNodes().selectedMessages()
        val source = if (trimTrailingAssistant) {
            // 重生成/换模型重答：剔除末尾的 bot 回复（保留在界面为旧版本，但不进本次上下文）
            selected.dropLastWhile { !it.isSelf }.ifEmpty { selected }
        } else {
            selected
        }
        val history = buildList {
            for (msg in source) {
                if (msg.recalled || msg.uploadFailed) continue
                msg.toLlmUIMessage()?.let { ui ->
                    // 非视觉正则：仅修改 Text part 的文本（进上下文），Image 等其他 part 完整保留
                    val scope = if (msg.isSelf) "user" else "assistant"
                    val newParts = ui.parts.map { part ->
                        if (part is com.cjhsy.linkzone.ai.ui.UIMessagePart.Text) {
                            com.cjhsy.linkzone.ai.ui.UIMessagePart.Text(
                                applyAgentRegexes(agent, part.text, scope, visual = false)
                            )
                        } else part
                    }
                    // 调试：确认图片是否真的进了上下文
                    val imgParts = newParts.filterIsInstance<com.cjhsy.linkzone.ai.ui.UIMessagePart.Image>()
                    if (imgParts.isNotEmpty()) {
                        android.util.Log.d("LZC-image", "buildLocalContext userMsg images=${imgParts.size} urls=${imgParts.map { it.url }}")
                    }
                    add(ui.copy(parts = newParts))
                }
            }
        }
        // 上下文压缩：手动检查点优先（用户从扩展面板主动触发），其次自动压缩
        //（RikkaHub CompressPrompt 思路：把将被截断的旧消息总结为检查点，保留近期消息）
        val convKey = _state.value.currentConvId
        val manual = manualCheckpoint
            ?.takeIf { it.first == convKey && history.size > manualCheckpointCovered }
        val compressed = when {
            manual != null -> listOf(manual.second) + history.drop(manualCheckpointCovered)
            agent.enableContextCompression && agent.contextMessageLimit > 0 &&
                history.size > agent.contextMessageLimit
            -> compressContext(history, agent.contextMessageLimit, agent, model)
            else -> history.limitContext(agent.contextMessageLimit)
        }
        messages += compressed
            .let {
                if (agent.enableTimeReminder) {
                    applyTimeReminder(it, agent.timeReminderIntervalMinutes)
                } else it
            }
            // 非视觉模型：图片必须转文本（启用 OCR 时用视觉模型识别，否则降级占位）
            .let { applyOcrTransform(agent, model, it) }
            // 发送前把 file:// 图片统一预编码为 data: URL（fail-loud：
            // 读不到文件立即报错上屏，杜绝 Provider 静默丢图只剩"[图片]"占位）
            .let { ensureImagesEncoded(it) }

        // 提示词注入：模式注入 + 世界书（Lorebook）关键词触发
        val hasPromptRefs = agent.promptIds.isNotEmpty() || agent.lorebookIds.isNotEmpty()
        val result = if (!hasPromptRefs) {
            messages
        } else {
            val prompts = llmRepository.getPrompts()
            val lorebooks = llmRepository.getLorebooks()
            LlmPromptInjector.apply(messages, prompts, lorebooks, agent.promptIds, agent.lorebookIds)
        }
        android.util.Log.d("LZC-image", "context built: " + result.joinToString { m ->
            m.role.name.lowercase() + m.parts.joinToString(prefix = "[", postfix = "]") { p ->
                when (p) {
                    is UIMessagePart.Image -> "image(${p.url.take(24)}…len=${p.url.length})"
                    is UIMessagePart.Text -> "text"
                    else -> p::class.simpleName ?: "?"
                }
            }
        })
        return result
    }

    /**
     * 上下文压缩：把超过 [limit] 的旧消息用 LLM 总结为一条检查点 SYSTEM 消息，
     * 保留近期 [limit] 条。总结失败时退化为纯截断（limitContext）。
     */
    private suspend fun compressContext(
        history: List<UIMessage>,
        limit: Int,
        agent: LlmAgent,
        model: Model
    ): List<UIMessage> {
        if (history.size <= limit) return history
        val dropCount = history.size - limit
        val toSummarize = history.take(dropCount)
        val summary = runCatching {
            val (setting, _) = resolveChatModel(agent) ?: return@runCatching null
            val content = toSummarize.joinToString("\n") { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "用户"
                    MessageRole.ASSISTANT -> "助手"
                    MessageRole.SYSTEM -> "系统"
                    else -> "未知"
                }
                "$role：${msg.toText().take(400)}"
            }
            val result = providerManager.getProviderByType(setting).generateText(
                providerSetting = setting,
                messages = listOf(
                    UIMessage.user(
                        """
                        Below is an earlier part of a conversation. Summarize it concisely,
                        preserving key facts, decisions, user preferences, and any unfinished tasks.
                        Write the summary in the same language as the conversation.
                        Do not add commentary.

                        $content
                        """.trimIndent()
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.2f,
                    maxTokens = 400
                )
            )
            result.message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
        }.getOrNull().orEmpty()

        if (summary.isBlank()) return history.limitContext(limit)
        val checkpoint = UIMessage.system(
            "<context_summary>\n$summary\n</context_summary>"
        ).copy(isContextCheckpoint = true)
        return listOf(checkpoint) + history.drop(dropCount)
    }

    // ---- 手动压缩（扩展面板入口） ----
    /** 手动压缩检查点（内存态）：convId to checkpoint；进程重启后丢失，自动压缩兜底 */
    private var manualCheckpoint: Pair<String, UIMessage>? = null
    /** 检查点覆盖的消息条数（buildMessages 时 drop 掉这部分历史） */
    private var manualCheckpointCovered: Int = 0

    /** 当前会话智能体绑定的代码工作区名（扩展面板入口显示用） */
    fun boundWorkspaceName(): String? =
        _state.value.currentAgent?.workspaceId?.let { workspaceManager.get(it)?.name }

    /**
     * 手动触发上下文压缩：把较早消息总结为检查点并在后续每轮请求复用
     * （内存态，跨会话/进程重启失效）。生成中忽略。
     */
    fun compressContextNow() {
        if (_state.value.generating) return
        val convId = _state.value.currentConvId
        val agent = _state.value.currentAgent ?: return
        viewModelScope.launch {
            val (_, model) = resolveChatModel(agent) ?: run {
                _state.value = _state.value.copy(error = "未配置可用模型，无法压缩")
                return@launch
            }
            val selected = _state.value.messages.toMessageNodes().selectedMessages()
            // 文档解析（PDF/DOCX 等）是重 IO/CPU，离开主线程避免压缩前界面冻结
            val history = withContext(Dispatchers.IO) {
                buildList {
                    for (msg in selected) {
                        if (msg.recalled || msg.uploadFailed) continue
                        msg.toLlmUIMessage()?.let { add(it) }
                    }
                }
            }
            val keep = agent.contextMessageLimit.takeIf { it > 0 } ?: 20
            if (history.size <= keep + 4) {
                _state.value = _state.value.copy(error = "当前消息不多，无需压缩")
                return@launch
            }
            val before = history.size
            val checkpoint = compressContext(history, keep, agent, model)
                .firstOrNull { it.isContextCheckpoint }
            if (checkpoint == null) {
                _state.value = _state.value.copy(error = "压缩失败（模型未返回摘要），可稍后重试")
            } else {
                manualCheckpoint = convId to checkpoint
                manualCheckpointCovered = before - keep
                _state.value = _state.value.copy(error = null)
                _manualCompressNotice.value = "已压缩：早期 ${before - keep} 条消息已总结为检查点"
            }
        }
    }

    /** 手动压缩结果提示（一次性事件流，UI 侧 Toast） */
    private val _manualCompressNotice = MutableStateFlow<String?>(null)
    val manualCompressNotice: StateFlow<String?> = _manualCompressNotice.asStateFlow()

    fun consumeCompressNotice() {
        _manualCompressNotice.value = null
    }

    /** OCR 结果进程内缓存（图片 URL → 识别文本），同图多轮对话只识别一次 */
    private val ocrCache = android.util.LruCache<String, String>(50)

    /**
     * OCR 输入变换器（移植 RikkaHub OcrTransformer）：
     * 当前对话模型不支持图片输入时，把消息中的本地图片 part 替换为文本——
     * 智能体启用 OCR 且存在视觉模型时，用视觉模型识别/描述图片；否则降级为 "[图片]" 占位，
     * 避免把 image part 发给纯文本模型导致接口报错。
     */
    private suspend fun applyOcrTransform(
        agent: LlmAgent,
        chatModel: Model,
        messages: List<UIMessage>
    ): List<UIMessage> {
        // 只有用户明确开启 OCR 时才做图片→文本替换；否则始终传图给 API，
        // 即使模型没在 registry 标 visionInput()——主流模型大多实际支持，
        // API 层报错比静默替换成 [图片] 让 AI 看不到好
        if (!agent.enableOcr) return messages
        val vision = resolveVisionChatModel(agent.ocrModelId) ?: return messages
        return messages.map { m ->
            m.copy(
                parts = m.parts.map { part ->
                    if (part is UIMessagePart.Image) {
                        val text = performOcr(part.url, vision.first, vision.second)
                        UIMessagePart.Text(text)
                    } else part
                }
            )
        }
    }

    /** file:// 图片预编码缓存：url → data: URL（避免每轮生成重复压缩编码） */
    private val imageEncodeCache = android.util.LruCache<String, String>(32)

    /**
     * 发送前预编码（fail-loud）：把上下文里所有 file:// 图片统一转成 data: URL，
     * 三个 Provider 的编码器对 data: 均原样透传，等于在唯一入口完成读图。
     * 文件丢失/解码失败时：①聊天页立即报错（不再让 AI 答"只看到占位符"）；
     * ②该图替换为显式文本标记，模型与用户都能明确看到哪张图没传上。
     */
    private fun ensureImagesEncoded(messages: List<UIMessage>): List<UIMessage> {
        var firstError: String? = null
        val out = messages.map { m ->
            if (m.parts.none { it is UIMessagePart.Image }) return@map m
            m.copy(parts = m.parts.map { part ->
                if (part !is UIMessagePart.Image) return@map part
                val url = part.url
                // http(s)/data: 由 Provider 原样透传，无需本地处理
                if (!url.startsWith("file://")) return@map part
                imageEncodeCache.get(url)?.let { return@map UIMessagePart.Image(it) }
                val result = part.encodeBase64(withPrefix = true)
                result.onSuccess { encoded ->
                    imageEncodeCache.put(url, encoded.base64)
                    android.util.Log.d("LZC-image", "pre-encode ok url=$url bytes=${encoded.base64.length}")
                }
                result.onFailure { e ->
                    android.util.Log.w("LZC-image", "pre-encode FAILED url=$url", e)
                    if (firstError == null) firstError = e.message ?: "未知错误"
                }
                result.getOrNull()?.let { UIMessagePart.Image(it.base64) }
                    ?: UIMessagePart.Text("[图片读取失败：${url.substringAfterLast('/')}]")
            })
        }
        firstError?.let {
            _state.value = _state.value.copy(error = "图片读取失败，AI 看不到这张图：$it")
        }
        return out
    }

    /** 调视觉模型识别单张图片，带缓存；失败返回错误占位文本 */
    private suspend fun performOcr(
        imageUrl: String,
        setting: ProviderSetting,
        visionModel: Model
    ): String {
        ocrCache.get(imageUrl)?.let { return it }
        val result = runCatching {
            providerManager.getProviderByType(setting).generateText(
                providerSetting = setting,
                messages = listOf(
                    UIMessage.system(OCR_PROMPT),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(imageUrl))
                    )
                ),
                params = TextGenerationParams(
                    model = visionModel,
                    temperature = 0.2f,
                    maxTokens = 800
                )
            )
        }.getOrElse { return "[ERROR, OCR failed: ${it.message ?: "未知错误"}]" }
        val content = result.message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
            .trim()
            .ifBlank { return "[ERROR, OCR failed]" }
        val wrapped = """
            <image_file_ocr>
            $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()
        ocrCache.put(imageUrl, wrapped)
        return wrapped
    }

    /** 把工具系统提示（技能索引等）追加进首条 SYSTEM 消息 */
    private fun mergeSystemPrompt(
        messages: List<UIMessage>,
        extra: String
    ): List<UIMessage> {
        if (messages.isEmpty()) return listOf(UIMessage.system(extra).copy(isSynthetic = true))
        val first = messages.first()
        if (first.role != MessageRole.SYSTEM) {
            return listOf(UIMessage.system(extra).copy(isSynthetic = true)) + messages
        }
        val original = first.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        return listOf(first.copy(parts = listOf(UIMessagePart.Text("$original\n$extra")))) + messages.drop(1)
    }

    /** 执行工具调用并回填输出；单个工具失败以 error JSON 作为输出继续（对齐 RikkaHub） */
    private suspend fun executeToolCalls(
        tools: List<Tool>,
        calls: List<UIMessagePart.Tool>
    ): List<UIMessagePart.Tool> = calls.map { call ->
        val def = tools.firstOrNull { it.name == call.toolName }
        val output = if (def == null) {
            listOf(UIMessagePart.Text(errorJson("Tool '${call.toolName}' not found")))
        } else {
            runCatching {
                val args = json.parseToJsonElement(call.input.ifBlank { "{}" })
                // 工具执行统一切 IO：shell 命令等待/文件读写/联系人短信查询等均为阻塞调用，
                // 在主线程跑满 5s 即触发 ANR 被系统查杀（三次实测 ANR 堆栈均指向此处）
                withContext(Dispatchers.IO) { def.execute(args) }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                listOf(UIMessagePart.Text(errorJson("[${e.javaClass.simpleName}] ${e.message ?: "tool error"}")))
            }
        }
        call.copy(output = output)
    }

    private fun errorJson(message: String): String =
        json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject { put("error", message) }
        )

    /**
     * 剥离正文中的 `<think>...</think>` 块，把思考内容合并进 thinking 段。
     * 兼容流式输出中 `<think>` 未闭合的情况（末尾残缺标签当思考处理）。
     * 返回 (思考文本, 清理后的正文)。
     */
    private val thinkTagRegex = Regex("(?s)<think>(.*?)</think>")
    private fun extractThinkTags(text: String): Pair<String, String> {
        if (!text.contains("<think>", ignoreCase = true)) return "" to text
        val think = StringBuilder()
        val clean = thinkTagRegex.replace(text) { m ->
            think.append(m.groupValues[1].trim()).append('\n')
            ""
        }
        // 处理未闭合的 <think>（流式半截）：把其后所有内容归为思考
        val openIdx = clean.indexOf("<think>", ignoreCase = true)
        val finalClean = if (openIdx >= 0) {
            think.append(clean.substring(openIdx + "<think>".length).trim()).append('\n')
            clean.substring(0, openIdx)
        } else clean
        return think.toString().trim() to finalClean.trim()
    }

    /** 提取 UIMessage 纯文本（拼接所有 Text part） */
    private fun UIMessage.toText(): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    /**
     * 消息段 → UIMessage（借鉴 RikkaHub 输入变换器思路）：
     * - text/markdown → 文本 part
     * - image（http/file://）→ 视觉 part
     * - file（本地 file:// 文档）→ 解析 PDF/DOCX/PPTX/XLSX/EPUB/文本/HTML，
     *   包装为 <UploadFile> prompt 块插到 parts 最前面（进程内缓存，多轮对话只解析一次）
     */
    private suspend fun MessageItem.toLlmUIMessage(): UIMessage? {
        val parts = mutableListOf<UIMessagePart>()
        val docs = StringBuilder()
        segments?.forEach { seg ->
            when (seg.type) {
                "text" -> seg.get("text").takeIf { it.isNotBlank() }
                    ?.let { parts += UIMessagePart.Text(it) }
                "markdown" -> seg.get("content").takeIf { it.isNotBlank() }
                    ?.let { parts += UIMessagePart.Text(it) }
                "image" -> {
                    // 服务端上传后段里存的是自托管相对路径（/public/...）：
                    // 相对路径同样是合法来源，否则整张图会被丢弃、AI 完全看不到
                    //（服务端转发上游前会把该路径读回原图并内联为 base64）
                    val url = seg.mediaUrl
                    if (url.isNotBlank()) {
                        parts += UIMessagePart.Image(url)
                    } else {
                        android.util.Log.w("LZC-image", "image seg DROPPED (blank url) id=$id data=${seg.data}")
                    }
                }
                "file" -> {
                    val url = seg.mediaUrl
                    val name = seg.get("name", "title").ifBlank { "attachment" }
                    if (url.startsWith("file://")) {
                        val docFile = File(url.removePrefix("file://"))
                        if (docFile.exists()) {
                            val text = DocumentParser.parse(docFile, name)
                            android.util.Log.d("LZC-image", "file parsed name=$name chars=${text.length}")
                            docs.append(DocumentParser.wrapAsPrompt(name, text)).append('\n')
                        } else {
                            // fail-loud：文件丢失时让模型明确说出"没读到内容"，而不是只剩文件名占位
                            android.util.Log.w("LZC-image", "file seg DROPPED (missing): id=$id url=$url")
                            docs.append(DocumentParser.wrapAsPrompt(name, "[ERROR, 文件不存在或已被清理：$name]")).append('\n')
                        }
                    } else {
                        android.util.Log.w("LZC-image", "file seg DROPPED (bad url): id=$id url=$url data=${seg.data}")
                    }
                }
            }
        }
        if (docs.isNotEmpty()) parts.add(0, UIMessagePart.Text(docs.toString()))
        if (parts.isEmpty() && content.isNotBlank()) {
            // 触发此分支 = 媒体段全部丢失，AI 只会看到 "[图片]"/"[文件:x]" 占位文本
            android.util.Log.w("LZC-image", "CONTENT FALLBACK: id=$id segs=${segments?.map { it.type }} content=$content")
            parts += UIMessagePart.Text(content)
        }
        if (parts.isEmpty()) return null
        return UIMessage(
            role = if (senderId == myId) MessageRole.USER else MessageRole.ASSISTANT,
            parts = parts,
            // 携带原始时间戳：applyTimeReminder 按消息间隔注入时间提醒
            createdAt = Instant.ofEpochMilli(timestamp / 1000)
                .atZone(ZoneId.systemDefault()).toLocalDateTime()
        )
    }

    /** 应用智能体 regexes（RikkaHub AssistantRegex）：对文本做正则查找替换
     * @param scope "user" / "assistant"；visualOnly=true 只改显示不进上下文
     */
    private fun applyAgentRegexes(agent: LlmAgent, text: String, scope: String, visual: Boolean): String {
        if (text.isBlank() || agent.regexes.isEmpty()) return text
        return agent.regexes.fold(text) { acc, r ->
            if (!r.enabled || r.visualOnly != visual || scope !in r.affectingScope) return@fold acc
            if (r.findRegex.isBlank()) return@fold acc
            runCatching { acc.replace(Regex(r.findRegex), r.replaceString) }.getOrElse { acc }
        }
    }

    /** prompt 变量替换（对齐 RikkaHub PlaceholderTransformer）：
     * {{model}} 模型显示名 / {{model_id}} 模型 ID / {{time}} 当前时间 / {{date}} 当前日期 /
     * {{user}} 用户昵称（个人资料）/ {{char}} 智能体名 / {{locale}} 系统语言 /
     * {{timezone}} 时区 / {{device_info}} 机型 / {{system_version}} 系统版本 / {{battery}} 电量
     */
    private fun renderSystemPrompt(agent: LlmAgent, model: Model): String {
        if (agent.systemPrompt.isBlank()) return ""
        val now = LocalDateTime.now()
        val nickname = userRepository.profile.value?.username?.ifBlank { "用户" } ?: "用户"
        val battery = runCatching {
            val bm = appContext.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        return agent.systemPrompt
            .replace("{{model}}", model.displayName.ifBlank { model.modelId })
            .replace("{{model_id}}", model.modelId)
            .replace("{{time}}", now.format(DateTimeFormatter.ofPattern("HH:mm")))
            .replace("{{date}}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .replace("{{weekday}}", now.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.CHINESE)))
            .replace("{{user}}", nickname)
            .replace("{{char}}", agent.name.ifBlank { "智能体" })
            .replace("{{locale}}", java.util.Locale.getDefault().displayName)
            .replace("{{timezone}}", java.util.TimeZone.getDefault().id)
            .replace("{{device_info}}", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            .replace("{{system_version}}", "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            .replace("{{battery}}", if (battery >= 0) "$battery%" else "未知")
    }

    /** 长按消息 → 回复：记录引用并聚焦输入 */
    fun setReplyTo(message: MessageItem) {
        _state.value = _state.value.copy(replyTo = message)
    }

    fun clearReplyTo() {
        _state.value = _state.value.copy(replyTo = null)
    }

    /** 首条消息后用消息摘要重命名会话（豆包式）：默认标题 = 首条用户消息截断，本地/云端同规则。
     *  云端主会话是固定入口，名字不随消息变化，跳过重命名。
     *  可命名条件：名称为空（新建未命名）或仍是「新对话」兜底。 */
    private fun maybeNameConversation(firstText: String) {
        val s = _state.value
        if (s.currentName.isNotBlank() && !s.currentName.startsWith(NEW_CONV_NAME)) return
        if (isMainConv(s.currentConvId)) return
        val name = firstText.take(NAME_MAX_LEN)
        if (name.isBlank()) return
        if (isLocalConvId(s.currentConvId)) {
            // 本地会话：默认标题 = 首条用户消息截断（AI 可在后升级为总结标题，用户可手动改名锁定）
            autoTitledConvs.add(s.currentConvId)
            viewModelScope.launch {
                localStore.rename(s.currentConvId, name)
                if (_state.value.currentConvId == s.currentConvId) {
                    _state.value = _state.value.copy(currentName = name)
                }
            }
            return
        }
        viewModelScope.launch {
            chatRepository.updateConversation(s.currentConvId, name = name)
            _state.value = _state.value.copy(currentName = name)
            updateConvInList(s.currentConvId) { it.copy(name = name) }
        }
    }

    /**
     * 手动重命名本地会话（抽屉长按菜单「重命名」）。
     * 改的是当前会话时同步顶部标题；同时锁定标题，AI 不再自动覆盖。
     */
    fun renameConversation(convId: String, name: String) {
        if (!isLocalConvId(convId)) return
        val trimmed = name.trim().take(RENAME_MAX_LEN)
        if (trimmed.isBlank()) return
        autoTitledConvs.remove(convId)
        viewModelScope.launch {
            localStore.rename(convId, trimmed)
            if (_state.value.currentConvId == convId) {
                _state.value = _state.value.copy(currentName = trimmed)
            }
        }
    }

    /**
     * 本地会话首答完成后用 LLM 生成短标题（DeepSeek 式）。
     * 仅当标题仍是自动命名（未被手动改名）时执行一次；
     * 生成失败/无模型时静默保留首条用户消息标题（回退）。
     */
    private fun maybeGenerateLlmTitle(
        convId: String,
        agent: LlmAgent,
        userText: String,
        aiText: String
    ) {
        if (!autoTitledConvs.remove(convId)) return
        viewModelScope.launch {
            val title = runCatching {
                val (setting, model) = resolveChatModel(agent) ?: return@runCatching null
                val result = providerManager.getProviderByType(setting).generateText(
                    providerSetting = setting,
                    messages = listOf(
                        UIMessage.user(
                            """
                            请根据下面的对话内容，生成一个简短的会话标题。要求：
                            1. 标题语言与用户使用的语言保持一致（中文对话就用中文）
                            2. 6 到 12 个汉字，概括对话的核心主题，不要照抄原句
                            3. 不要使用任何标点符号、引号、 emoji 或特殊符号
                            4. 不要出现"对话""聊天""咨询"这类无信息量的词
                            5. 只输出标题文本本身，不要输出任何解释、序号或前缀

                            对话内容：
                            用户：${userText.take(500)}
                            助手：${aiText.take(500)}
                            """.trimIndent()
                        )
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                        maxTokens = 60
                    )
                )
                result.message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
            }.getOrNull().orEmpty()
                .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .removePrefix("标题：").removePrefix("标题:")
                .trim('"', '\'', '「', '」', '『', '』', '：', ':', '。', '.', ' ', '\t', '\n', '*', '#')
                .take(NAME_MAX_LEN)
            if (title.isBlank()) return@launch
            localStore.rename(convId, title)
            if (_state.value.currentConvId == convId) {
                _state.value = _state.value.copy(currentName = title)
            }
        }
    }

    /**
     * 根据刚生成的助手回复，调用 LLM 产出 2~3 条建议追问（RikkaHub Suggestion 思路），
     * 显示在输入区上方供用户一键发送。失败/超时则不显示建议。
     */
    private fun maybeGenerateSuggestions(agent: LlmAgent, model: Model, aiText: String) {
        if (aiText.isBlank()) return
        viewModelScope.launch {
            val suggestions = runCatching {
                val (setting, _) = resolveChatModel(agent) ?: return@runCatching emptyList()
                val result = providerManager.getProviderByType(setting).generateText(
                    providerSetting = setting,
                    messages = listOf(
                        UIMessage.user(
                            """
                            Based on the assistant's reply below, generate 3 short suggested
                            follow-up questions the user might want to ask next.
                            Each suggestion must be on its own line, no numbering, no quotes, no punctuation at the end.
                            Use the same language as the reply. Keep each under 20 characters.

                            Assistant reply:
                            ${aiText.take(1000)}
                            """.trimIndent()
                        )
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.7f,
                        maxTokens = 120
                    )
                )
                result.message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                    .lineSequence()
                    .map { it.trim().trimStart { c -> c.isDigit() || c in ".、)\"'「」『』" }.trim() }
                    .filter { it.length in 2..30 }
                    .take(3)
                    .toList()
            }.getOrNull().orEmpty()
            if (suggestions.isNotEmpty()) {
                _state.value = _state.value.copy(aiSuggestions = suggestions)
            }
        }
    }

    /** 去掉乐观前缀，用于同一条消息的 local- 版与服务端版去重 */
    private fun cleanId(id: String): String = id.removePrefix(LOCAL_PREFIX)

    /**
     * 追加消息；自动处理 local- 乐观消息与服务端确认消息的替换：
     * - 若收到服务端版（无 local-）且本地存在对应乐观版 → 替换
     * - 若 id 已存在（含乐观/正式） → 忽略
     */
    private fun appendMessage(msg: MessageItem) {
        val s = _state.value
        if (msg.id.isNotBlank()) {
            val clean = cleanId(msg.id)
            val existingLocal = s.messages.firstOrNull { cleanId(it.id) == clean && it.id.startsWith(LOCAL_PREFIX) }
            if (existingLocal != null && !msg.id.startsWith(LOCAL_PREFIX)) {
                // 服务端确认到达：替换乐观气泡，保留其他状态（recalled 等由后续事件处理）
                _state.value = s.copy(
                    messages = s.messages.map { if (it.id == existingLocal.id) msg else it }
                        .sortedBy { it.timestamp },
                    sending = false,
                    error = null
                )
                return
            }
            if (s.messages.any { it.id == msg.id }) return
        }
        _state.value = s.copy(
            messages = (s.messages + msg).sortedBy { it.timestamp },
            sending = false,
            error = null
        )
    }

    /** 更新服务器会话列表项并重排；会话不存在/本地会话时忽略（本地走 localStore） */
    private fun updateConvInList(convId: String, transform: (ConvItem) -> ConvItem) {
        if (convId.isBlank() || isLocalConvId(convId)) return
        val list = serverConvs.value
        if (list.none { it.id == convId }) return
        serverConvs.value = sortConvs(list.map { if (it.id == convId) transform(it) else it })
    }

    private fun extractPreview(segments: List<MessageSegment>): String =
        segments.firstOrNull()?.display ?: ""

    // ============ 消息操作 ============

    fun markRead() {
        val convId = _state.value.currentConvId
        if (convId.isBlank()) return
        if (isLocalConvId(convId)) {
            viewModelScope.launch { localStore.clearUnread(convId) }
            return
        }
        viewModelScope.launch { chatRepository.markRead(convId) }
    }

    fun recall(message: MessageItem) {
        val convId = _state.value.currentConvId
        if (convId.isBlank()) return
        if (isLocalConvId(convId)) {
            viewModelScope.launch {
                localStore.setRecalled(message.id)
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map {
                        if (it.id == message.id) it.copy(recalled = true) else it
                    }
                )
            }
            return
        }
        if (message.id.startsWith(LOCAL_PREFIX)) return
        viewModelScope.launch {
            chatRepository.recall(convId, message.id).onSuccess {
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map {
                        if (it.id == message.id) it.copy(recalled = true) else it
                    }
                )
            }
        }
    }

    fun deleteMessage(message: MessageItem) {
        // 本地会话：消息只存本机，直接删本地库（生成中的占位气泡删除后，
        // finalize 检测到气泡已不在会放弃落库）
        if (isLocalConvId(message.convId.ifBlank { _state.value.currentConvId })) {
            _state.value = _state.value.copy(
                messages = _state.value.messages.filterNot { it.id == message.id }
            )
            viewModelScope.launch { localStore.deleteMessages(listOf(message.id)) }
            return
        }
        // 仅存在于本地的乐观消息（发送时未确认/已离线）：直接本地移除，
        // 不调 REST——服务端根本没有这条消息，调用必然 404/失败
        if (message.id.startsWith(LOCAL_PREFIX)) {
            // 媒体仍在后台上传：登记取消，上传完成后不再发 WS
            val target = _state.value.messages.firstOrNull { it.id == message.id }
            if (target?.uploading == true) {
                cancelledUploads.add(message.id.removePrefix(LOCAL_PREFIX))
            }
            _state.value = _state.value.copy(
                messages = _state.value.messages.filterNot { it.id == message.id }
            )
            return
        }
        val convId = _state.value.currentConvId
        if (convId.isBlank()) return
        viewModelScope.launch {
            chatRepository.deleteMessage(convId, message.id).onSuccess {
                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == message.id }
                )
            }
        }
    }

    fun retryInit() {
        if (myId.isBlank() || _state.value.loading) return
        viewModelScope.launch { bootstrapConversations() }
    }

    // ============ 消息操作扩展（对齐 RikkaHub：编辑重发/重新生成/多模型重答） ============

    /** 是否本地智能体会话（发送落库/重新生成/多模型重答/编辑重发仅本地会话可用） */
    private fun isLocalConv(): Boolean = isLocalConvId(_state.value.currentConvId)

    /** 当前登录用户的云端唯一主会话判定（conv-{myId}-linkzone-bot） */
    private fun isMainConv(convId: String): Boolean = isMainConvId(myId, convId)

    /** 消息可编辑文本（text/markdown 段拼接，回退 content）；纯媒体消息返回空 */
    private fun editableText(m: MessageItem): String {
        val fromSegs = m.segments
            ?.filter { it.type == "text" || it.type == "markdown" }
            ?.map { it.get("text", "content") }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()
        return fromSegs.ifBlank { if (m.segments.isNullOrEmpty()) m.content else "" }.trim()
    }

    /** 长按消息 → 编辑重发：聊天页弹出编辑对话框 */
    fun startEditMessage(m: MessageItem) {
        _state.value = _state.value.copy(editing = m)
    }

    fun cancelEditMessage() {
        _state.value = _state.value.copy(editing = null)
    }

    /**
     * 确认编辑（仅本地会话，RikkaHub 式版本链）：
     * 在原消息所在节点追加一个新版本，旧版本完整保留、‹ n/m › 可切换；
     * 旧尾巴钉到旧版本分支（切回旧版本时整条换回），随后以新版本为末尾重新生成。
     * 云端会话不提供编辑功能。
     */
    fun confirmEditMessage(newText: String) {
        val target = _state.value.editing ?: return
        val content = newText.trim()
        if (content.isEmpty()) return
        _state.value = _state.value.copy(editing = null)
        // 生成进行中忽略：快速连点会让两个占位气泡拿到相同 version idx，版本链永久错乱
        if (generatingJob?.isActive == true) return
        viewModelScope.launch {
            stopGeneratingAndWait()
            val s = _state.value
            if (!isLocalConvId(s.currentConvId)) return@launch
            forkUserVersion(target, content)
            maybeDispatchLocal()
        }
    }

    /**
     * 在用户消息节点追加一个新版本（RikkaHub 式分叉，非破坏式）：
     * - [newText] 非空 → 新版本使用编辑后的文本（编辑重发）
     * - [newText] 为空 → 克隆原消息全部内容段（图片/文件等，消息下「重新生成」入口）
     * 旧尾巴钉到旧版本分支完整保留；新版本时间戳仍聚集在锚点（排序/分页稳定），
     * 真实重发时间记录在 sys.ver 的 ts 字段供 UI 展示。
     */
    private suspend fun forkUserVersion(target: MessageItem, newText: String?) {
        val s = _state.value
        val node = s.messages.toMessageNodes().nodeOf(target.id)
        if (node == null) {
            _state.value = _state.value.copy(error = "消息已不存在，操作失败")
            return
        }
        // 旧尾巴钉到当前选中版本（作为旧分支完整保留，切回旧版本时整条换回）
        pinTailToVersion(node.anchorId, node.selectedIndex)
        val newIdx = node.versions.size
        val source = node.selected
        val contentSegs = if (newText != null) {
            listOf(Segments.text(newText))
        } else {
            // 克隆原文：剔除版本/分支/思考/中断等内部段，保留 text/image/file 等全部内容段
            source.segments.orEmpty().filter {
                it.type != VERSION_SEG && it.type != BRANCH_SEG &&
                    it.type != "thinking" && it.type != "sys.stop"
            }.ifEmpty { listOf(Segments.text(source.content)) }
        }
        val displayContent = newText ?: source.content
        val now = System.currentTimeMillis() * 1000
        val edited = MessageItem(
            id = UUID.randomUUID().toString(),
            convId = s.currentConvId,
            senderId = source.senderId.ifBlank { myId },
            senderName = source.senderName,
            segments = source.branchConstraints()
                .map { branchSegment(it.first, it.second) } +
                listOf(versionSegment(node.anchorId, newIdx, newIdx, now)) +
                contentSegs,
            content = displayContent,
            isSelf = true,
            // 排序时间聚集在锚点（+idx 微秒），真实时间放 sys.ver.ts
            timestamp = node.anchorTs + newIdx
        )
        appendMessage(edited)
        localStore.insertMessage(edited)
        localStore.touch(s.currentConvId, displayContent, now)
    }

    /**
     * 切换消息节点显示版本（仅本地会话）：‹ n/m › 切换器回调。
     * 把该节点所有版本消息的 sys.ver 段同步为 sel=index 并回写 Room。
     */
    fun selectVersion(anchorId: String, index: Int) {
        if (!isLocalConv()) return
        val s = _state.value
        val node = s.messages.toMessageNodes().firstOrNull { it.anchorId == anchorId } ?: return
        if (index !in node.versions.indices || index == node.selectedIndex) return
        val updated = node.versions.map { v ->
            val meta = v.versionMeta() ?: return@map v
            v.copy(segments = v.segments?.map { seg ->
                // 只重写 sel，保留 node/idx 与该版本自身的生成时间 ts
                if (seg.type == VERSION_SEG) versionSegment(anchorId, meta.idx, index, meta.displayTs) else seg
            })
        }
        val byId = updated.associateBy { it.id }
        _state.value = s.copy(
            messages = s.messages.map { byId[it.id] ?: it }
        )
        viewModelScope.launch { localStore.insertMessages(updated) }
    }

    /** 停止生成并等待收尾完成（防止旧任务 finalize 延迟落库陈旧内容） */
    private suspend fun stopGeneratingAndWait() {
        val job = generatingJob
        generatingJob = null
        job?.cancelAndJoin()
    }

    /**
     * 把锚点之后、尚未标记该锚点分支归属的消息钉到指定版本（sys.br 约束）。
     * 在创建新版本前调用：旧尾巴完整保留为旧版本的分支，切换版本时整条换回。
     */
    private suspend fun pinTailToVersion(anchorId: String, versionIdx: Int) {
        val s = _state.value
        val nodes = s.messages.toMessageNodes()
        val idx = nodes.indexOfFirst { it.anchorId == anchorId }
        if (idx < 0) return
        val toUpdate = nodes.drop(idx + 1).flatMap { it.versions }
            .filter { m -> m.branchConstraints().none { it.first == anchorId } }
            .map { m -> m.copy(segments = listOf(branchSegment(anchorId, versionIdx)) + m.segments.orEmpty()) }
        if (toUpdate.isEmpty()) return
        val byId = toUpdate.associateBy { it.id }
        _state.value = s.copy(messages = s.messages.map { byId[it.id] ?: it })
        localStore.insertMessages(toUpdate)
    }

    /** 当前可见分支上所有多版本节点的分支约束（新消息/新尾巴挂到当前选中分支） */
    private fun activeBranchConstraints(): List<MessageSegment> =
        _state.value.messages.toMessageNodes().resolveVisible()
            .filter { it.versions.size > 1 }
            .map { branchSegment(it.anchorId, it.selectedIndex) }

    /**
     * 从某条消息重新生成（仅本地会话，对齐 RikkaHub regenerateAtMessage）：
     * - 用户消息：在该节点**非破坏式分叉**一个新版本（克隆原文，含图片/文件），
     *   旧尾巴完整保留为旧版本分支，随后对新版本重新生成（不再删除任何消息）
     * - bot 回复：旧回复保留为旧版本（其尾巴钉到旧版本分支），新回复作为该节点新版本
     */
    fun regenerateAt(message: MessageItem) {
        if (!isLocalConv()) return
        // 生成进行中直接忽略：并发重生成会产生相同 version idx 的占位，版本链错乱
        if (generatingJob?.isActive == true) return
        viewModelScope.launch {
            stopGeneratingAndWait()
            val node = _state.value.messages.toMessageNodes().nodeOf(message.id) ?: return@launch
            if (node.isSelf) {
                forkUserVersion(message, null)
                maybeDispatchLocal()
            } else {
                pinTailToVersion(node.anchorId, node.selectedIndex)
                val newIdx = node.versions.size
                generatingJob = viewModelScope.launch {
                    runLocalGeneration(
                        botId = _state.value.currentBotId,
                        trimTrailingAssistant = true,
                        versionAnchorId = node.anchorId,
                        versionIdx = newIdx,
                        versionAnchorTs = node.anchorTs
                    )
                }
            }
        }
    }

    /** 加载全部启用 Provider 的 CHAT 模型（多模型重答选择器数据源）。
     *
     * 能力判断走两层：
     * 1. 模型自身显式声明（inputModalities / abilities）——用户手动配置的覆盖项
     * 2. ModelRegistry 查表 —— 内置已知模型的默认能力，或自定义模型的安全 fallback
     *    （registry 未匹配时 resolveModalities 默认 IMAGE+TEXT，MODEL_ABILITIES 默认工具全开）
     */
    fun loadChatModels() {
        viewModelScope.launch {
            _chatModels.value = llmRepository.getProviders()
                .filter { it.enabled }
                .flatMap { p ->
                    p.models.filter { it.type == ModelType.CHAT }.map { m ->
                        // 优先用模型自身显式声明；未声明时走 ModelRegistry 查表
                        val registryInput = com.cjhsy.linkzone.ai.registry.ModelRegistry.MODEL_INPUT_MODALITIES.getData(m.modelId)
                        val registryAbilities = com.cjhsy.linkzone.ai.registry.ModelRegistry.MODEL_ABILITIES.getData(m.modelId)
                        val vision = Modality.IMAGE in m.inputModalities ||
                                (m.inputModalities.size == 1 && m.inputModalities.first() == Modality.TEXT && Modality.IMAGE in registryInput) ||
                                (m.inputModalities.isEmpty() && Modality.IMAGE in registryInput)
                        val reasoning = com.cjhsy.linkzone.ai.provider.ModelAbility.REASONING in m.abilities ||
                                (m.abilities.isEmpty() && com.cjhsy.linkzone.ai.provider.ModelAbility.REASONING in registryAbilities)
                        ChatModelOption(
                            modelId = m.modelId,
                            label = m.displayName.ifBlank { m.modelId },
                            providerName = p.name,
                            supportsVision = vision,
                            supportsTools = m.supportsTools(),
                            supportsReasoning = reasoning,
                        )
                    }
                }
        }
    }

    /** 更新本地智能体默认模型：聊天页内改模型只写入本会话覆盖（不影响原智能体全局配置） */
    fun setLocalAgentModel(agent: LlmAgent, modelId: String) {
        if (agent.chatModelId == modelId) return
        updateCurrentAgent { it.copy(chatModelId = modelId) }
        recordRecentModel(modelId)
    }

    /**
     * 更新当前会话的智能体配置（模型/参数/扩展开关/工具/工作区等全部字段）。
     * 统一写入**会话级覆盖**（llm_local_conversation.agentOverrideJson），
     * 只在本会话生效，不回写 llm_agent 表，不影响智能体本身和其他会话；云端会话忽略。
     */
    fun updateCurrentAgent(transform: (LlmAgent) -> LlmAgent) {
        val current = _state.value.currentAgent ?: return
        val convId = _state.value.currentConvId
        if (!isLocalConvId(convId)) return
        val updated = transform(current)
        if (updated == current) return
        _state.value = _state.value.copy(currentAgent = updated, agentOverridden = true)
        viewModelScope.launch {
            localStore.setAgentOverrideJson(
                convId,
                json.encodeToString(LlmAgent.serializer(), updated)
            )
        }
    }

    /** 清除本会话的全部临时配置，恢复为原智能体当前配置 */
    fun resetAgentOverride() {
        val convId = _state.value.currentConvId
        val agentId = _state.value.currentAgent?.id ?: return
        if (!isLocalConvId(convId)) return
        viewModelScope.launch {
            localStore.setAgentOverrideJson(convId, null)
            val base = llmRepository.getAgent(agentId)
            _state.value = _state.value.copy(currentAgent = base, agentOverridden = false)
        }
    }

    /** 会话内绑定/解绑代码工作区（只写会话覆盖；工具执行时按 agent.workspaceId 实时解析） */
    fun bindWorkspace(workspaceId: String?) {
        updateCurrentAgent { it.copy(workspaceId = workspaceId) }
    }

    /** 会话内新建工作区并立即绑定到本会话 */
    fun createAndBindWorkspace(name: String) {
        val ws = workspaceManager.create(name)
        updateCurrentAgent { it.copy(workspaceId = ws.id) }
    }

    // ---- 最近使用模型（SharedPreferences，模型选择器「最近使用」分组） ----
    private val recentPrefs = appContext.getSharedPreferences("recent_models", android.content.Context.MODE_PRIVATE)

    private val _recentModelIds = MutableStateFlow(loadRecentModelIds())
    val recentModelIds: StateFlow<List<String>> = _recentModelIds.asStateFlow()

    private fun loadRecentModelIds(): List<String> = runCatching {
        json.parseToJsonElement(recentPrefs.getString(KEY_RECENT, null) ?: "[]")
            .jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())

    private fun recordRecentModel(modelId: String) {
        val list = recentModelIds.value.toMutableList().apply {
            remove(modelId)
            add(0, modelId)
        }.take(8)
        recentPrefs.edit()
            .putString(KEY_RECENT, kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
            .apply()
        _recentModelIds.value = list
    }

    /**
     * 多模型重答（仅本地会话，对齐 RikkaHub 换模型重新生成）：
     * 用指定模型对最后一个 AI 节点重新生成一个**新版本**（旧版本保留可切换）；
     * 新回复署名带模型名，旧回复不参与本次上下文。
     */
    fun regenerateWithModel(modelId: String) {
        if (!isLocalConv()) return
        if (generatingJob?.isActive == true) return
        viewModelScope.launch {
            stopGeneratingAndWait()
            val node = _state.value.messages.toMessageNodes().resolveVisible()
                .lastOrNull { !it.isSelf } ?: return@launch
            // 旧尾巴钉到旧版本分支，新版本从空白尾巴重新长
            pinTailToVersion(node.anchorId, node.selectedIndex)
            generatingJob = viewModelScope.launch {
                runLocalGeneration(
                    botId = _state.value.currentBotId,
                    overrideModelId = modelId,
                    trimTrailingAssistant = true,
                    versionAnchorId = node.anchorId,
                    versionIdx = node.versions.size,
                    versionAnchorTs = node.anchorTs
                )
            }
        }
    }

    /** 把上下文消息回放到新本地会话（引擎切换分叉用）：仅保留文本/markdown 内容 */
    private suspend fun replayContextInto(convId: String, context: List<MessageItem>) {
        for (m in context) {
            val text = editableText(m)
            if (text.isBlank()) continue
            val isSelf = m.senderId == myId
            val segs = listOf(if (isSelf) Segments.text(text) else Segments.markdown(text))
            localStore.insertMessage(
                MessageItem(
                    id = UUID.randomUUID().toString(),
                    convId = convId,
                    senderId = m.senderId,
                    senderName = m.senderName,
                    segments = segs,
                    content = text,
                    isSelf = isSelf,
                    timestamp = m.timestamp
                )
            )
        }
    }

    // ============ 对话引擎切换（顶栏 chip） ============

    /**
     * 顶栏引擎切换 → 本地智能体：
     * - 当前已是绑定该智能体的本地会话：忽略；
     * - 当前会话没有可用历史：直接新建该智能体的空本地会话；
     * - 有历史（云端或其他智能体的本地会话）：分叉——新建绑定该智能体的
     *   本地会话，并把当前会话消息回放为上下文（原会话保留不动）。
     */
    fun switchEngineToLocal(agent: LlmAgent) {
        val s = _state.value
        if (!s.ready) return
        // 已在该智能体的会话中：无会话覆盖时刷新 currentAgent（防止编辑后 state 滞留旧快照）；
        // 存在会话覆盖时保留本会话的临时配置，不被全局编辑冲掉
        if (isLocalConvId(s.currentConvId) && extractAgentId(s.currentBotId) == agent.id) {
            if (!s.agentOverridden) _state.value = s.copy(currentAgent = agent)
            return
        }
        viewModelScope.launch {
            val context = s.messages.toMessageNodes().selectedMessages()
                .filter { !it.recalled && editableText(it).isNotBlank() }
            if (context.isEmpty()) {
                createLocalConversation(agent)
                return@launch
            }
            val baseName = s.currentName
            val name = if (baseName.isBlank() || baseName == NEW_CONV_NAME) {
                agent.name.ifBlank { "AI 对话" }
            } else {
                "$baseName · ${agent.name.ifBlank { "智能体" }}"
            }
            val conv = localStore.createConversation(agent, name)
            replayContextInto(conv.id, context)
            openConv(conv)
        }
    }

    /** 置顶/取消置顶会话：仅本地会话支持（云端主会话固定在抽屉最上面，不可置顶） */
    fun pinConversation(convId: String, pinned: Boolean) {
        if (!isLocalConvId(convId)) return
        viewModelScope.launch { localStore.setPinned(convId, pinned) }
    }

    // ============ 会话内搜索 ============

    /**
     * 打开搜索面板：后台全量拉取当前会话历史（每页 100，最多 15 页）合并进
     * 消息流，保证搜索覆盖完整历史而不只是已加载分页。
     */
    fun openSearch() {
        _state.value = _state.value.copy(searchOpen = true, searching = true, searchResults = emptyList())
        // 本地会话：消息全在本地库，一次读全即可
        if (isLocalConvId(_state.value.currentConvId)) {
            val convId = _state.value.currentConvId
            viewModelScope.launch {
                val items = localStore.loadAllMessages(convId)
                if (_state.value.currentConvId != convId) return@launch
                _state.value = _state.value.copy(messages = items, searching = false)
                applySearchFilter()
            }
            return
        }
        viewModelScope.launch {
            var pages = 0
            while (pages < 15) {
                val s = _state.value
                if (!s.searchOpen || s.currentConvId.isBlank()) return@launch
                val oldest = s.messages.firstOrNull()?.timestamp
                    ?: chatRepository.loadHistory(s.currentConvId, limit = 1).getOrNull()?.items?.firstOrNull()?.timestamp
                    ?: break
                val resp = chatRepository.loadHistory(s.currentConvId, before = oldest, limit = 100).getOrNull()
                    ?: break
                if (resp.items.isEmpty()) break
                val merged = (resp.items + _state.value.messages)
                    .distinctBy { cleanId(it.id) }
                    .sortedBy { it.timestamp }
                _state.value = _state.value.copy(messages = merged, hasMore = resp.hasMore)
                pages++
                if (!resp.hasMore) break
            }
            if (_state.value.searchOpen) {
                _state.value = _state.value.copy(searching = false)
                applySearchFilter()
            }
        }
    }

    fun closeSearch() {
        _state.value = _state.value.copy(
            searchOpen = false, searchQuery = "", searchResults = emptyList(),
            searching = false, highlightMessageId = null
        )
    }

    fun updateSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query, highlightMessageId = null)
        applySearchFilter()
    }

    /** 内存过滤：命中 displayText / content（忽略大小写），按时间倒序展示 */
    private fun applySearchFilter() {
        val s = _state.value
        val q = s.searchQuery.trim()
        if (q.isEmpty()) {
            _state.value = s.copy(searchResults = emptyList())
            return
        }
        val results = s.messages
            .filter { !it.recalled }
            .filter {
                it.displayText.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true)
            }
            .sortedByDescending { it.timestamp }
        _state.value = s.copy(searchResults = results)
    }

    /** 搜索结果点击：关闭搜索面板，定位并高亮该消息 */
    fun jumpToSearchResult(m: MessageItem) {
        _state.value = _state.value.copy(searchOpen = false, highlightMessageId = m.id)
    }

    fun clearHighlight() {
        _state.value = _state.value.copy(highlightMessageId = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        const val NEW_CONV_NAME = "新对话"

        /** 云端唯一主会话固定 bot id（会话 id 规则 conv-{ownerId}-linkzone-bot） */
        const val MAIN_BOT_ID = "linkzone-bot"
        /** 云端唯一主会话固定名称（固定入口，不随消息自动命名） */
        const val MAIN_CONV_NAME = "LinkZone 对话"

        /** 主会话 id 判定（UI 层可用）：conv-{ownerId}-linkzone-bot */
        fun isMainConvId(ownerId: String, convId: String): Boolean =
            convId == "conv-$ownerId-$MAIN_BOT_ID"

        /** SharedPreferences 键：最近使用的聊天模型 id 列表 */
        private const val KEY_RECENT = "recent_chat_model_ids"

        /** OCR 视觉模型的系统提示：要求逐字提取文字并描述画面（对齐 RikkaHub OcrPrompt） */
        private const val OCR_PROMPT =
            "You are an OCR and image description engine. Extract all readable text from the image " +
                "verbatim (preserve layout with line breaks where possible), and briefly describe the " +
                "non-text visual content. Reply in the same language as the text in the image."
        /** 本地智能体会话 bot_id 前缀（bot-local-{agentId}） */
        const val LOCAL_BOT_PREFIX = LocalChatStore.BOT_PREFIX
        /** 本地会话 id 前缀（会话归属判定的唯一依据） */
        const val LOCAL_CONV_PREFIX = LocalChatStore.CONV_PREFIX

        /** 是否本地会话（UI 层判定用；旧服务器 bot-local- 会话属云端，不算本地） */
        fun isLocalConvId(convId: String): Boolean = convId.startsWith(LOCAL_CONV_PREFIX)

        /** 本地智能体回复占位气泡 id 前缀（本地会话中该 id 即最终消息 id） */
        private const val BOT_LOCAL_MSG_PREFIX = "bot-local-msg-"
        /** 流式生成 UI 刷新节流（毫秒） */
        private const val THROTTLE_MS = 80L
        private const val NAME_MAX_LEN = 12
        /** 手动重命名标题上限（抽屉长按「重命名」） */
        private const val RENAME_MAX_LEN = 50
        /** 与服务端 handleUpload 限制一致（20MB） */
        private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
    }
}
