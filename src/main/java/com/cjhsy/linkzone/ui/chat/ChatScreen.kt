package com.cjhsy.linkzone.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cjhsy.linkzone.R
import com.cjhsy.linkzone.di.BASE_URL
import com.cjhsy.linkzone.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 云端空状态建议提问（点击直接发送） */
private val SUGGESTIONS = listOf(
    "你好，介绍一下你自己",
    "你能帮我做什么？",
    "今天有什么可以帮我的？"
)

/** 本地智能体空会话建议提问 */
private val LOCAL_SUGGESTIONS = listOf(
    "你好，介绍一下你自己",
    "你能帮我做什么？",
    "写一段代码示例"
)

/**
 * 唯一对话页（云端 LinkZone / 本地智能体统一界面）—— 豆包式单会话画布 +
 * RikkaHub 式 AI 文档流消息。
 *
 * 会话绑定一个「对话引擎」，由 currentConvId 前缀区分：
 * - 云端：LinkZone 适配器，服务端智能体/插件响应；
 * - 本地：用户自建智能体（RikkaHub 式），本机直连 AI 服务商流式生成。
 * 同一套页面骨架/消息列表/输入栏，差异通过能力参数与插槽收敛：
 * 顶栏标题区点击（云端→引擎选择器；本地→模型面板）、扩展 chips（仅本地）、
 * 连接状态条（仅云端）、思考过程/重生成能力（仅本地）。
 *
 * @param onOpenDrawer 打开左侧抽屉（功能入口）
 * @param onOpenEnginePicker 弹出对话引擎选择器
 * @param onNewConversation 顶栏右上角新建对话（弹出引擎选择器）
 * @param onManageAgents 本地模型面板中的「管理智能体」入口
 */
@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit,
    onOpenEnginePicker: () -> Unit,
    onNewConversation: () -> Unit,
    onManageAgents: () -> Unit,
    onOpenWorkspace: () -> Unit = {},
    viewModel: ChatViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chatModels by viewModel.chatModels.collectAsStateWithLifecycle()
    val quickMessages by viewModel.quickMessages.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpStatus by viewModel.mcpStatus.collectAsStateWithLifecycle()
    val recentModelIds by viewModel.recentModelIds.collectAsStateWithLifecycle()
    val allPrompts by viewModel.allPrompts.collectAsStateWithLifecycle()
    val allLorebooks by viewModel.allLorebooks.collectAsStateWithLifecycle()
    val allQuickMessages by viewModel.allQuickMessages.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()

    // 多模型重答选择器开关（本地）
    var showModelPicker by remember { mutableStateOf(false) }
    // 顶栏模型选择面板（本地，Rikka 式按服务商分组）
    var showModelSheet by remember { mutableStateOf(false) }
    // AI 扩展面板（本地：推理/MCP/技能/记忆/本地工具）
    var showExtensionSheet by remember { mutableStateOf(false) }

    // 模型选择面板打开时加载全部启用 Provider 的模型
    LaunchedEffect(showModelSheet, showModelPicker) {
        if (showModelSheet || showModelPicker) viewModel.loadChatModels()
    }

    // 首次本地生成时申请通知权限（Android 13+，前台服务通知需要）
    val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    var notifRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.generating) {
        if (state.generating && !notifRequested &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            notifRequested = true
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 搜索定位高亮自动清除
    LaunchedEffect(state.highlightMessageId) {
        if (state.highlightMessageId != null) {
            delay(2200)
            viewModel.clearHighlight()
        }
    }

    // 引擎判定：本地智能体会话（local-conv- 前缀）完全脱离服务器，不依赖连接
    val isLocal = ChatViewModel.isLocalConvId(state.currentConvId)
    val agent = state.currentAgent

    // 明确铺一层主题底色：暗色模式下避免列表边缘/过顶辉光露出白色窗口底
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        // navigationBarsPadding 在外、imePadding 在内：insets 按几何方式消费，
        // 键盘弹起时 ime 会扣掉已消费的导航栏高度，不会双重 padding；
        // 键盘收起时只避让导航栏。顶栏状态栏在 ChatTopBar 内单独处理。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            val ctx = LocalContext.current
            ChatTopBar(
                isLocal = isLocal,
                cloudTitle = state.currentName.let {
                    if (it.isBlank() || it == ChatViewModel.NEW_CONV_NAME) "LinkZone" else it
                },
                agentName = state.currentName.let {
                    if (it.isBlank() || it == ChatViewModel.NEW_CONV_NAME) "新对话" else it
                },
                avatarEmoji = agent?.avatarEmoji.orEmpty(),
                connected = state.connected,
                onOpenDrawer = onOpenDrawer,
                onOpenSearch = viewModel::openSearch
            )

            // 连接状态条：断开时红色条滑入，连接后滑出（本地引擎会话不展示）
            if (!isLocal) {
                ConnectionStatusBar(connected = state.connected)
            }

            if (state.searchOpen) {
                // 会话内搜索面板（覆盖消息流区域）
                SearchPanel(
                    state = state,
                    onQueryChange = viewModel::updateSearchQuery,
                    onClose = viewModel::closeSearch,
                    onJump = viewModel::jumpToSearchResult,
                    modifier = Modifier.weight(1f)
                )
            } else {
                MessageList(
                    state = state,
                    isLocal = isLocal,
                    agentEmoji = agent?.avatarEmoji.orEmpty(),
                    onLoadMore = viewModel::loadMore,
                    onSend = viewModel::sendMessage,
                    onReply = viewModel::setReplyTo,
                    onRecall = viewModel::recall,
                    onDelete = viewModel::deleteMessage,
                    onEdit = viewModel::startEditMessage,
                    onRegenerate = viewModel::regenerateAt,
                    onRegenerateWithModel = {
                        viewModel.loadChatModels()
                        showModelPicker = true
                    },
                    onContinue = viewModel::continueGeneration,
                    onSelectVersion = viewModel::selectVersion,
                    modifier = Modifier.weight(1f),
                    userName = userProfile?.username.orEmpty(),
                    userAvatarUrl = userProfile?.avatarUrl.orEmpty(),
                    // 传完整模型 id：头像图标匹配用全量 id，署名显示取末段
                    modelName = agent?.chatModelId.orEmpty()
                )
            }

            // 错误条：展开/收起带动画，点击清除
            AnimatedVisibility(
                visible = state.error != null,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                state.error?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = viewModel::clearError)
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // AI 回复建议：首答后由 LLM 生成，点击直接发送
            AnimatedVisibility(
                visible = state.aiSuggestions.isNotEmpty(),
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    items(state.aiSuggestions) { suggestion ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { viewModel.sendCombined(suggestion, emptyList()) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // ask_user 追问卡：模型信息不足时挂起生成，等待用户作答后继续
            AnimatedVisibility(
                visible = state.askUser != null,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                state.askUser?.let { prompt ->
                    AskUserAnswerCard(
                        prompt = prompt,
                        onSubmit = viewModel::submitAskUser,
                        onSkip = viewModel::skipAskUser
                    )
                }
            }

            ChatInputBar(
                sending = state.sending,
                enabled = state.ready,
                generating = state.generating,
                quickMessages = quickMessages,
                onStopGenerate = viewModel::stopGenerating,
                replyTo = state.replyTo,
                onCancelReply = viewModel::clearReplyTo,
                onSendCombined = { text, attachments ->
                    viewModel.sendCombined(text, attachments)
                },
                onUploadPending = { file, onDone ->
                    viewModel.uploadPending(file, onDone)
                },
                onSendLocation = { lat, lon ->
                    viewModel.sendSegments(
                        listOf(com.cjhsy.linkzone.data.model.Segments.location(lat, lon)),
                        "[位置]"
                    )
                },
                extensionChips = if (isLocal) {
                    {
                        LocalExtensionChips(
                            agent = state.currentAgent,
                            onToggleSearch = {
                                viewModel.updateCurrentAgent { it.copy(enableWebSearch = !it.enableWebSearch) }
                            },
                            onReasoningChange = { level ->
                                viewModel.updateCurrentAgent { it.copy(reasoningLevel = level) }
                            },
                            onOpenExtensions = { showExtensionSheet = true },
                            extensionsCount = agent?.let {
                                it.enabledSkills.size + it.mcpServers.size + (if (it.enableMemory) 1 else 0)
                            } ?: 0
                        )
                    }
                } else null
            )
        }
    }

    // ---------- 编辑重发对话框（仅本地会话：原消息生成新版本，版本链可切换） ----------
    state.editing?.let { target ->
        EditMessageDialog(
            initial = target.let { m ->
                m.segments
                    ?.filter { it.type == "text" || it.type == "markdown" }
                    ?.map { it.get("text", "content") }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString("\n")
                    .orEmpty()
                    .ifBlank { m.content }
            },
            onConfirm = viewModel::confirmEditMessage,
            onDismiss = viewModel::cancelEditMessage
        )
    }

    // ---------- 多模型重答选择器（本地 AI 消息） ----------
    if (showModelPicker) {
        ModelPickerDialog(
            models = chatModels,
            onPick = { modelId ->
                showModelPicker = false
                viewModel.regenerateWithModel(modelId)
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // ---------- 顶栏模型选择面板（本地，按服务商分组，点选即改默认模型） ----------
    if (showModelSheet) {
        LocalModelSheet(
            models = chatModels,
            currentModelId = state.currentAgent?.chatModelId.orEmpty(),
            recentModelIds = recentModelIds,
            onPick = { modelId ->
                val current = state.currentAgent
                if (current != null) viewModel.setLocalAgentModel(current, modelId)
            },
            onManageAgents = {
                showModelSheet = false
                onManageAgents()
            },
            onDismiss = { showModelSheet = false }
        )
    }

    // ---------- AI 扩展面板（本地：会话级全功能配置，仅本会话生效） ----------
    if (showExtensionSheet) {
        LocalExtensionSheet(
            agent = state.currentAgent,
            skills = skills,
            prompts = allPrompts,
            lorebooks = allLorebooks,
            quickMessages = allQuickMessages,
            mcpServers = mcpServers,
            mcpStatus = mcpStatus,
            agentOverridden = state.agentOverridden,
            onResetOverride = viewModel::resetAgentOverride,
            onPickModel = {
                showExtensionSheet = false
                showModelSheet = true
            },
            onSwitchAgent = {
                showExtensionSheet = false
                onOpenEnginePicker()
            },
            onBindWorkspace = viewModel::bindWorkspace,
            onCreateWorkspace = viewModel::createAndBindWorkspace,
            onOpenWorkspaceManager = onOpenWorkspace,
            onCompressNow = viewModel::compressContextNow,
            onUpdate = { transform -> viewModel.updateCurrentAgent(transform) },
            onDismiss = { showExtensionSheet = false }
        )
    }

    // 手动压缩结果提示（一次性 Toast）
    val compressNotice by viewModel.manualCompressNotice.collectAsStateWithLifecycle()
    val noticeCtx = LocalContext.current
    LaunchedEffect(compressNotice) {
        compressNotice?.let {
            Toast.makeText(noticeCtx, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeCompressNotice()
        }
    }
}

/**
 * 统一顶部栏：汉堡菜单 + 标题区（点击行为随引擎变化）+ 新建对话 + 搜索 + 连接状态。
 * - 云端：居中粗体标题 chip，点击弹出引擎选择器；右侧连接指示点/未连接文案
 * - 本地：智能体头像 + 名称/模型副标题 chip，点击弹出模型选择面板；无连接指示
 */
@Composable
private fun ChatTopBar(
    isLocal: Boolean,
    cloudTitle: String,
    agentName: String,
    avatarEmoji: String,
    connected: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 沉浸式：与页面同底色（悬浮输入栏风格的"一整张画布"感）
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "菜单")
            }
            // 标题区：居中、不可点击。云端=纯标题；本地=头像 + 名称（模型选择去 AI 扩展）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLocal) {
                    AgentAvatar(avatarEmoji, size = 30.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = cloudTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 连接状态指示点（颜色平滑过渡）；断线时展开"未连接"提示。
            // 本地智能体会话不依赖服务端连接，隐藏全部连接指示
            val dotColor by animateColorAsState(
                targetValue = if (connected) SuccessGreen
                else MaterialTheme.colorScheme.outline,
                animationSpec = tween(300),
                label = "connDot"
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 会话内搜索
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
                AnimatedVisibility(
                    visible = !connected && !isLocal,
                    enter = expandHorizontally(tween(180)) + fadeIn(tween(180)),
                    exit = shrinkHorizontally(tween(180)) + fadeOut(tween(180))
                ) {
                    Text(
                        text = "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                AnimatedVisibility(
                    visible = !isLocal,
                    enter = expandHorizontally(tween(180)) + fadeIn(tween(180)),
                    exit = shrinkHorizontally(tween(180)) + fadeOut(tween(180))
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }
        Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 连接状态条：断网时从顶部滑入红色警示条，恢复后收起 */
@Composable
private fun ConnectionStatusBar(connected: Boolean) {
    AnimatedVisibility(
        visible = !connected,
        enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(260)),
        exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "当前网络不可用，消息将在恢复后发送",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** 消息流：空状态（云端 Logo / 本地智能体头像 + 建议提问）或统一消息列表 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageList(
    state: ChatUiState,
    isLocal: Boolean,
    agentEmoji: String,
    onLoadMore: () -> Unit,
    onSend: (String) -> Unit,
    onReply: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    onRecall: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    onDelete: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    onEdit: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    onRegenerate: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    onRegenerateWithModel: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    onContinue: () -> Unit = {},
    onSelectVersion: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 当前用户昵称/头像 + 当前模型名（消息气泡署名显示用） */
    userName: String = "",
    userAvatarUrl: String = "",
    modelName: String = ""
) {
    val listState = rememberLazyListState()

    // 版本节点视图：按分支约束解析出当前可见分支后渲染（切版本时下方整条分支联动切换）
    val nodes = remember(state.messages) { state.messages.toMessageNodes().resolveVisible() }

    // 列表头部在有更多历史时多一个「加载更早」按钮项，node 的列表下标 = 节点下标 + 头部项数
    val headerCount = if (state.hasMore) 1 else 0

    // 进软件 / 切换会话时首包到达：直接定位到最新消息（QQ 式），不是顶部
    // （普通 remember：进程重建后仍需重新定位；用 saveable 会残留导致不滚底）
    var scrolledFor by remember { mutableStateOf("") }
    LaunchedEffect(state.currentConvId, state.messages.isEmpty()) {
        if (state.currentConvId.isBlank()) return@LaunchedEffect
        // 会话被清空（切换/删除后重开）：重置定位标记，下一包到达时重新滚底
        if (state.messages.isEmpty()) {
            scrolledFor = ""
            return@LaunchedEffect
        }
        if (scrolledFor != state.currentConvId) {
            scrolledFor = state.currentConvId
            listState.scrollToItem(nodes.lastIndex + (if (state.hasMore) 1 else 0))
        }
    }

    // 搜索结果跳转定位：高亮消息进入视野（按节点定位，命中任一版本即跳）
    LaunchedEffect(state.highlightMessageId) {
        val target = state.highlightMessageId ?: return@LaunchedEffect
        val idx = nodes.indexOfFirst { n -> n.versions.any { it.id == target } }
        if (idx >= 0) listState.animateScrollToItem(idx + (if (state.hasMore) 1 else 0))
    }

    // 翻页（加载更早）锚定：加载完成头部插入历史后，把首个可见消息恢复到原位置，
    // 消除"跳到列表头"的跳变感
    var pendingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(state.messages.size) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        pendingAnchor = null
        val newIdx = nodes.indexOfFirst { it.anchorId == anchor.first }
        if (newIdx >= 0) {
            listState.scrollToItem(newIdx + headerCount, anchor.second)
        }
    }

    // 新消息到达时：自己发的消息一定滚到底；对方消息仅在已贴近底部时滚动，
    // 用户正在翻看历史（或向上翻页加载）时不打断
    val lastId = nodes.lastOrNull()?.selected?.id
    LaunchedEffect(lastId) {
        val last = nodes.lastOrNull() ?: return@LaunchedEffect
        val bottomIndex = nodes.lastIndex + headerCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val nearBottom = lastVisible == null || lastVisible >= bottomIndex - 1
        if (last.isSelf || nearBottom) {
            listState.animateScrollToItem(bottomIndex)
        }
    }

    // ---------- 流式输出贴底跟随（ChatGPT 式 stick-to-bottom） ----------
    // 正文/思考增长时，用户仍贴底则持续滚底（即时滚动无动画，80ms 节流不抖动）；
    // 用户上翻立即释放跟随，回到底部附近自动恢复。
    // 跟随状态只由用户手势决定：程序化滚动不干扰（DragInteraction 仅在用户拖动时触发）
    var followStream by remember { mutableStateOf(true) }
    var userDragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.generating) { if (state.generating) followStream = true }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    userDragging = false
                    // 拖动结束瞬间采样：末条底边贴近视口底端（容差 ~56dp）则保持/恢复跟随
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    followStream = last != null &&
                        last.index >= info.totalItemsCount - 1 &&
                        last.offset + last.size <= info.viewportEndOffset + 160
                }
            }
        }
    }
    val streamBottomIndex = nodes.lastIndex + headerCount
    val streamTail = nodes.lastOrNull()?.selected
    // 流式尾部内容长度（正文 + 思考过程），每次节流刷新都会变化，驱动滚底
    val streamTailLen = if (state.generating && streamTail != null) {
        streamTail.content.length + (streamTail.segments
            ?.filter { it.type == "thinking" }
            ?.sumOf { it.get("text").length } ?: 0)
    } else 0
    LaunchedEffect(streamTailLen, streamBottomIndex) {
        if (state.generating && followStream && !userDragging && streamBottomIndex >= 0) {
            // 超大 scrollOffset 触发列表最大滚动位置钳制 = 末条底边贴齐视口底
            listState.scrollToItem(streamBottomIndex, Int.MAX_VALUE)
        }
    }

    if (state.messages.isEmpty() && !state.loading) {
        EmptyState(
            isLocal = isLocal,
            agentEmoji = agentEmoji,
            agentName = state.currentAgent?.name?.ifBlank { "智能体" } ?: "智能体",
            onSend = onSend,
            enabled = state.ready,
            modifier = modifier
        )
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (state.hasMore) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = {
                                // 记录首个可见消息项（key 非 String 的是"加载更早"头部按钮），
                                // 加载完成后恢复到原位置
                                val anchorItem = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key is String }
                                pendingAnchor = anchorItem?.let { (it.key as String) to it.offset }
                                onLoadMore()
                            },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        ) {
                            Text(
                                "加载更早的消息",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            itemsIndexed(nodes, key = { _, n -> n.anchorId }) { index, node ->
                // 入场动画只给列表尾部的新消息播放（发/收消息体感不变）；
                // 历史消息不挂动画状态（rememberSaveable + 动画），滚动更流畅
                val isNew = index >= nodes.lastIndex - 2
                Column(
                    modifier = Modifier
                        // 条目增删/位置变化平滑过渡（撤回/删除/版本切换不再瞬跳）；
                        // 关闭自带淡入淡出，避免与 messageEnter 叠加
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                        .then(if (isNew) Modifier.messageEnter() else Modifier)
                ) {
                    val msg = node.selected
                    val prev = nodes.getOrNull(index - 1)?.selected
                    if (shouldShowTime(prev?.let { it.displayTimestamp() } ?: 0L, msg.displayTimestamp())) {
                        // 现代化时间分隔：居中弱化胶囊（QQ/Telegram 式）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatMessageTime(msg.displayTimestamp()),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                    // 仅本地流式生成中的 AI 消息（占位气泡同 id 定稿）：
                    // 禁尺寸动画/隐操作行，消除布局抖动
                    val streaming = isLocal && state.generating && !msg.isSelf &&
                        msg.id.startsWith("bot-local-msg-")
                    MessageItemView(
                        message = msg,
                        baseUrl = BASE_URL.trimEnd('/'),
                        isLocal = isLocal,
                        agentEmoji = agentEmoji,
                        streaming = streaming,
                        // 回复/撤回云端与本地均开放；重生成/多模型仅本地智能体
                        canReply = true,
                        canRecall = true,
                        canRegenerate = isLocal,
                        // 版本链（RikkaHub 式）：多版本节点显示 ‹ n/m › 切换器
                        versionCount = node.versions.size,
                        versionIndex = node.selectedIndex,
                        onSelectVersion = { idx -> onSelectVersion(node.anchorId, idx) },
                        highlight = node.versions.any { it.id == state.highlightMessageId },
                        onReply = { onReply(msg) },
                        onRecall = { onRecall(msg) },
                        onDelete = { onDelete(msg) },
                        onEdit = { onEdit(msg) },
                        onRegenerate = { onRegenerate(msg) },
                        onRegenerateWithModel = { onRegenerateWithModel(msg) },
                        onContinue = onContinue.takeIf { isLocal && !streaming },
                        userName = userName,
                        userAvatarUrl = userAvatarUrl,
                        modelName = modelName
                    )
                }
            }
        }

        // 回到底部按钮：向上翻消息后浮现，点击平滑滚回底部。
        // 只从 listState 派生（totalItemsCount 随新消息自动更新），
        // 避免 remember 捕获旧 state 导致按钮状态不刷新
        val showScrollToBottom by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                    ?: return@derivedStateOf false
                lastVisible < info.totalItemsCount - 2
            }
        }
        val scope = rememberCoroutineScope()
        AnimatedVisibility(
            visible = showScrollToBottom,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(44.dp)
                    .clickable {
                        // 回到底部后恢复流式跟随；下标 = 末节点 + 头部偏移（旧版用 messages 下标会错位）
                        followStream = true
                        scope.launch {
                            listState.animateScrollToItem(nodes.lastIndex + headerCount)
                        }
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 空状态：云端=品牌 Logo 问候；本地=智能体大头像 + 名称问候；下方建议提问 chips
 */
@Composable
private fun EmptyState(
    isLocal: Boolean,
    agentEmoji: String,
    agentName: String,
    onSend: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    // 首次进入空会话：整体轻微上浮淡入
    AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = fadeIn(tween(420)) +
            slideInVertically(tween(420)) { it / 10 }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLocal) {
                AgentAvatar(
                    emoji = agentEmoji,
                    size = 88.dp,
                    iconSize = 44.dp
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    // 使用与 APP 图标/web 端同款的品牌 logo（透明底 PNG）
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(84.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "我是 LinkZone 智能助手",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "问我任何问题，或从下面开始",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            val suggestions = if (isLocal) LOCAL_SUGGESTIONS else SUGGESTIONS
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions, key = { it }) { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable(enabled = enabled) { onSend(suggestion) }
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 录音手势状态（云端/本地聊天界面共用） */
internal enum class RecordUi { IDLE, RECORDING, WILL_CANCEL }

/** 待发送附件上传状态（选中即预上传） */
internal enum class MediaUploadState { UPLOADING, UPLOADED, FAILED }

/** 待发送附件：文件 + 类型（image/video/file）+ 预上传状态/服务端 URL，发送后清除 */
internal data class PendingMedia(
    val file: java.io.File,
    val type: String?,
    val id: Long = System.nanoTime(),
    val uploadState: MediaUploadState = MediaUploadState.UPLOADING,
    val remoteUrl: String? = null,
    val remoteSegType: String = "",
    val remoteName: String = "",
    val remoteSize: Long = 0L
)

/**
 * ask_user 追问作答卡（HITL）：
 * 模型调用 ask_user 后生成挂起，此卡浮在输入栏上方收集答案。
 * 每题支持：选项 chips（单选/多选）+ 自定义文本，提交后以 {问题id: 答案} 回传工具。
 */
@Composable
private fun AskUserAnswerCard(
    prompt: AskUserPrompt,
    onSubmit: (Map<String, String>) -> Unit,
    onSkip: () -> Unit
) {
    // 题目 id → 已选选项集合（single 为 0/1 项，multi 可多项）
    val selections = remember(prompt) { mutableStateMapOf<String, Set<String>>() }
    // 题目 id → 自定义文本
    val customs = remember(prompt) { mutableStateMapOf<String, String>() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "智能体需要补充信息",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSkip) {
                    Text("跳过", style = MaterialTheme.typography.labelMedium)
                }
            }

            prompt.questions.forEach { q ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = q.question,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (q.options.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(q.options) { opt ->
                                val isSel = opt in (selections[q.id] ?: emptySet())
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .border(
                                            BorderStroke(
                                                1.dp,
                                                if (isSel) MaterialTheme.colorScheme.primary
                                                else Color.Transparent
                                            ),
                                            RoundedCornerShape(50)
                                        )
                                        .clickable {
                                            val current = selections[q.id] ?: emptySet()
                                            selections[q.id] = when {
                                                q.selectionType == "single" ->
                                                    if (isSel) emptySet() else setOf(opt)
                                                isSel -> current - opt
                                                else -> current + opt
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = opt,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customs[q.id].orEmpty(),
                        onValueChange = { customs[q.id] = it },
                        placeholder = {
                            Text(
                                "自定义答案…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Button(
                onClick = {
                    val answers = prompt.questions.associate { q ->
                        val picked = selections[q.id] ?: emptySet()
                        val custom = customs[q.id]?.trim().orEmpty()
                        val answer = (picked + listOfNotNull(custom.ifBlank { null }))
                            .joinToString("、")
                            .ifBlank { "（未作答）" }
                        q.id to answer
                    }
                    onSubmit(answers)
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Text("提交回答", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 底部输入栏（豆包式）：
 * - 行布局：相机 | 胶囊输入框 | 麦克风 | ⊕（或发送）
 * - 附件面板选中后加入待发送列表（缩略条浮在输入区上方），不直接发送
 * - 发送时文字 + 所有附件组合发出（文字在前，附件依次跟发）
 * - 按住说话：按下开始录音，松开发送，上滑取消；录音中显示居中浮层与计时
 */
@Composable
internal fun ChatInputBar(
    sending: Boolean,
    enabled: Boolean,
    generating: Boolean,
    quickMessages: List<com.cjhsy.linkzone.data.model.LlmQuickMessage>,
    onStopGenerate: () -> Unit,
    replyTo: com.cjhsy.linkzone.data.model.MessageItem?,
    onCancelReply: () -> Unit,
    onSendCombined: (String, List<PendingMediaItem>) -> Unit,
    onSendLocation: (Double, Double) -> Unit,
    /** 附件预上传：选中入列后立即调用，完成回调更新条目状态 */
    onUploadPending: (java.io.File, (Result<com.cjhsy.linkzone.data.model.UploadResponse>) -> Unit) -> Unit,
    extensionChips: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var panelOpen by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }

    // 待发送附件列表（豆包式：选中不直接发，浮在输入区上方）
    var pendingAttachments by remember { mutableStateOf(listOf<PendingMedia>()) }

    /**
     * 附件选中即预上传（DeepSeek/豆包式）：先入列显示上传动画，
     * 后台上传完成后回填服务端 URL / 标记失败，发送时直接引用免二次上传。
     */
    fun addAttachment(file: java.io.File, type: String?) {
        val item = PendingMedia(file = file, type = type)
        pendingAttachments = pendingAttachments + item
        onUploadPending(file) { result ->
            pendingAttachments = pendingAttachments.map {
                if (it.id != item.id) it
                else result.fold(
                    onSuccess = { up ->
                        it.copy(
                            uploadState = MediaUploadState.UPLOADED,
                            remoteUrl = up.url,
                            remoteSegType = up.segType,
                            remoteName = up.name,
                            remoteSize = up.size
                        )
                    },
                    onFailure = { it.copy(uploadState = MediaUploadState.FAILED) }
                )
            }
        }
    }

    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recordState by remember { mutableStateOf(RecordUi.IDLE) }
    var recordStartMs by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    // 录音计时（100ms 刷新）
    LaunchedEffect(recordState) {
        if (recordState != RecordUi.IDLE) {
            while (true) {
                elapsedMs = SystemClock.elapsedRealtime() - recordStartMs
                delay(100)
            }
        }
    }

    // 离开页面时确保释放录音器
    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 授权结果不自动开录（手指交互已结束），用户再次按住即可
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 90.dp.toPx() }

    // 快捷拍照 launcher（输入行左侧相机按钮）
    var pendingCapture = remember { arrayOf<java.io.File?>(null) }
    val quickCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCapture[0]
        pendingCapture[0] = null
        if (success && file != null && file.exists() && file.length() > 0) {
            addAttachment(file, "image")
        } else {
            file?.delete()
        }
    }

    /** 组合发送：文字 + 附件合并为单条消息 */
    fun doSend() {
        val text = input.trim()
        // 预上传未完成/失败时不允许发送（DeepSeek/豆包同款约束）
        if (pendingAttachments.any { it.uploadState == MediaUploadState.UPLOADING }) {
            Toast.makeText(context, "附件上传中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingAttachments.any { it.uploadState == MediaUploadState.FAILED }) {
            Toast.makeText(context, "附件上传失败，请移除后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val items = pendingAttachments.map {
            PendingMediaItem(
                file = it.file, type = it.type,
                remoteUrl = it.remoteUrl, remoteSegType = it.remoteSegType,
                remoteName = it.remoteName, remoteSize = it.remoteSize
            )
        }
        onSendCombined(text, items)
        input = ""
        pendingAttachments = emptyList()
        panelOpen = false
    }

    // 悬浮式输入区（豆包式）：无通栏底色，各层悬浮在页面背景之上
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 10.dp)
    ) {
        // 回复引用预览条（悬浮小卡，展开/收起动画）
        AnimatedVisibility(
            visible = replyTo != null,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
        ) {
            replyTo?.let { ReplyPreviewBar(it, onCancelReply) }
        }

        // 待发送附件预览条（悬浮缩略卡 + X 移除）
        AnimatedVisibility(
            visible = pendingAttachments.isNotEmpty(),
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
        ) {
            PendingMediaStrip(
                items = pendingAttachments,
                onRemove = { item ->
                    pendingAttachments = pendingAttachments.filter { it.id != item.id }
                    item.file.delete()
                    }
                )
            }

            // 快捷指令条（智能体关联，点击填入输入框）
            AnimatedVisibility(
                visible = quickMessages.isNotEmpty(),
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    items(quickMessages, key = { it.id }) { quick ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            shadowElevation = 6.dp,
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.clickable {
                                input = if (input.isBlank()) quick.content else input + quick.content
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    quick.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // AI 扩展 chips（本地会话：联网搜索 / 扩展入口等）
            if (extensionChips != null) {
                extensionChips()
            }

            // ---------- 悬浮输入胶囊：相机 | 输入/语音 | 麦克风 | ⊕/发送 ----------
            val pillShape = RoundedCornerShape(28.dp)
            val pillBg = MaterialTheme.colorScheme.surface
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 16.dp,
                        shape = pillShape,
                        ambientColor = Color.Black.copy(alpha = if (isSystemInDarkTheme()) 0.40f else 0.14f),
                        spotColor = Color.Black.copy(alpha = if (isSystemInDarkTheme()) 0.40f else 0.14f)
                    )
                    .clip(pillShape)
                    .background(pillBg)
                    .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), pillShape)
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // 快捷相机（胶囊内左侧，豆包式）
                // 所有图标统一 44dp 底座 + bottom 2dp：单行输入时与文字中心线齐平
                IconButton(
                    onClick = {
                        val photo = java.io.File(
                            context.cacheDir,
                            "photo_${System.currentTimeMillis()}.jpg"
                        )
                        pendingCapture[0] = photo
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", photo
                        )
                        quickCameraLauncher.launch(uri)
                    },
                    enabled = enabled && !sending,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(bottom = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "拍照",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 输入区（胶囊中段，无独立底色）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (voiceMode) {
                        // ---------- 按住说话 ----------
                        val active = recordState != RecordUi.IDLE
                        val holdBg by animateColorAsState(
                            targetValue = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent,
                            animationSpec = tween(150),
                            label = "holdBg"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(holdBg)
                                .pointerInput(enabled, sending) {
                                    if (!enabled || sending) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (!hasRecordPermission()) {
                                            recordPermissionLauncher.launch(
                                                Manifest.permission.RECORD_AUDIO
                                            )
                                            return@awaitEachGesture
                                        }
                                        if (!recorder.start()) return@awaitEachGesture
                                        recordStartMs = SystemClock.elapsedRealtime()
                                        elapsedMs = 0L
                                        recordState = RecordUi.RECORDING

                                        var cancelArmed = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.first()
                                            val dy = change.position.y - down.position.y
                                            val shouldCancel = dy < -cancelThresholdPx
                                            if (shouldCancel != cancelArmed) {
                                                cancelArmed = shouldCancel
                                                recordState = if (cancelArmed) RecordUi.WILL_CANCEL
                                                else RecordUi.RECORDING
                                            }
                                            if (!change.pressed) {
                                                if (cancelArmed) {
                                                    recorder.cancel()
                                                } else {
                                                    when (val r = recorder.finish()) {
                                                        null -> Toast.makeText(
                                                            context, "说话时间太短", Toast.LENGTH_SHORT
                                                        ).show()
                                                        else -> onSendCombined("", listOf(PendingMediaItem(r.file, "voice")))
                                                    }
                                                }
                                                recordState = RecordUi.IDLE
                                                break
                                            }
                                        }
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when (recordState) {
                                    RecordUi.RECORDING -> "松开发送 · 上滑取消"
                                    RecordUi.WILL_CANCEL -> "松开手指，取消发送"
                                    RecordUi.IDLE -> "按住 说话"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (recordState == RecordUi.WILL_CANCEL)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        // ---------- 文字输入 ----------
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                            placeholder = {
                                Text(
                                    text = if (pendingAttachments.isNotEmpty()) "输入问题或直接发送"
                                    else "发消息…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 麦克风 / 键盘切换（与相机同规格：44dp + bottom 2dp）
                IconButton(
                    onClick = { voiceMode = !voiceMode },
                    enabled = enabled && !sending,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(bottom = 2.dp)
                ) {
                    Icon(
                        imageVector = if (voiceMode) Icons.Filled.Keyboard
                        else Icons.Filled.Mic,
                        contentDescription = if (voiceMode) "键盘" else "语音",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(2.dp))

                // ⊕ / 发送按钮（有内容时变为发送；本地智能体生成中变为停止按钮）
                val canSend = (input.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending && enabled && !generating
                val sendBg by animateColorAsState(
                    targetValue = if (canSend || generating) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                    animationSpec = tween(180),
                    label = "sendBg"
                )
                val sendTint by animateColorAsState(
                    targetValue = if (canSend || generating) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(180),
                    label = "sendTint"
                )
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(sendBg)
                        .clickable(enabled = canSend || generating || (!canSend && !voiceMode)) {
                            when {
                                generating -> onStopGenerate()
                                canSend -> doSend()
                                else -> panelOpen = !panelOpen
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // ⊕/↑/■ 状态切换：缩放 + 淡入淡出过渡，避免生硬跳变
                    val sendState = when {
                        generating -> "stop"
                        sending -> "sending"
                        canSend -> "send"
                        panelOpen -> "close"
                        else -> "add"
                    }
                    AnimatedContent(
                        targetState = sendState,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.6f))
                                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
                        },
                        label = "sendBtn"
                    ) { st ->
                        when (st) {
                            // 生成中：停止按钮（方形停符）
                            "stop" -> Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White)
                            )
                            "sending" -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            else -> Icon(
                                imageVector = if (st == "send") Icons.Filled.ArrowUpward
                                else if (st == "close") Icons.Filled.Close
                                else Icons.Filled.Add,
                                contentDescription = if (st == "send") "发送" else "功能",
                                tint = sendTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 功能面板（拍照/相册/录像/位置/文件）：展开在输入胶囊**下方**，贴屏幕底边
            AnimatedVisibility(
                visible = panelOpen,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 10.dp,
                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    AttachPanel(
                        enabled = enabled && !sending,
                        onSendMedia = { file, forcedType ->
                            addAttachment(file, forcedType)
                        },
                        onSendLocation = onSendLocation
                    )
                }
            }
        }

    // 录音中浮层（Window 级 Popup，居中卡片 + 半透明遮罩）
    if (recordState != RecordUi.IDLE) {
        RecordingOverlay(state = recordState, elapsedMs = elapsedMs)
    }
}

/** 待发送附件预览条：图片/视频/文件缩略卡 + X 移除 + 上传动画（豆包式） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PendingMediaStrip(
    items: List<PendingMedia>,
    onRemove: (PendingMedia) -> Unit,
    modifier: Modifier = Modifier
) {
    // 图片点击全屏预览（本地 file:// 直接可开）
    var preview by remember { mutableStateOf<PendingMedia?>(null) }
    preview?.let { item ->
        ZoomableImageDialog(
            rawUrl = "file://" + item.file.absolutePath,
            baseUrl = "",
            onDismiss = { preview = null }
        )
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items, key = { it.id }) { item ->
            Box(
                modifier = Modifier
            ) {
                // 缩略卡
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    when (item.type) {
                        "image" -> {
                            AsyncImage(
                                model = item.file,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { preview = item }
                            )
                        }
                        "video" -> {
                            // 视频缩略帧（深色底 + 摄像机图标 + 文件名）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0B1120)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Text(
                                text = item.file.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        else -> {
                            // 文件：图标 + 文件名两行
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = item.file.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    // 上传中：半透明遮罩 + 进度圈；失败：遮罩 + 红色错误角标
                    when (item.uploadState) {
                        MediaUploadState.UPLOADING -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        MediaUploadState.FAILED -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = "上传失败",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        MediaUploadState.UPLOADED -> Unit
                    }
                }
                // 右上角 X 移除
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { onRemove(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/** 录音浮层：计时 + 麦克风（取消态变红 + 抖动/缩放提示） */
@Composable
internal fun RecordingOverlay(state: RecordUi, elapsedMs: Long) {
    val willCancel = state == RecordUi.WILL_CANCEL
    val transition = rememberInfiniteTransition(label = "rec")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (willCancel) 1f else 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recPulse"
    )
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (willCancel) Icons.Filled.Delete else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = if (willCancel) MaterialTheme.colorScheme.error else Color.White,
                        modifier = Modifier
                            .size(46.dp)
                            .scale(pulse)
                    )
                }
                Spacer(Modifier.height(16.dp))
                val totalSec = elapsedMs / 1000
                Text(
                    text = "%d:%02d".format(totalSec / 60, totalSec % 60),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (willCancel) "松开手指，取消发送" else "松开发送 · 上滑取消",
                    color = if (willCancel) MaterialTheme.colorScheme.error else Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 回复引用预览条：竖线 + 回复对象 + 内容摘要 + 取消 */
@Composable
internal fun ReplyPreviewBar(
    message: com.cjhsy.linkzone.data.model.MessageItem,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "回复 ${message.senderName.ifBlank { "消息" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message.displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "取消回复",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onCancel)
        )
    }
}

/**
 * 消息首次进入组合时上浮 16dp + 淡入。
 * 低回弹弹簧曲线（dampingRatio 0.86）：末端轻微过冲，比线性缓动更自然跟手。
 * 以消息 id 为 key 的条目 remember 状态在其存活期内只播放一次；
 * 撤回/删除由 LazyColumn 的 animateItemPlacement 负责平滑移动。
 */
@Composable
internal fun Modifier.messageEnter(): Modifier {
    // rememberSaveable：LazyColumn 条目滚出视口回收后再次进入时，
    // 已播过的动画不重复播放（普通 remember 会随回收丢失状态）
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "messageEnter"
    )
    val offsetPx = with(LocalDensity.current) { 16.dp.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * offsetPx
    }
}

// ============ 编辑重发 / 多模型重答 / 会话内搜索 ============

/** 编辑重发对话框：仅本地会话（原消息所在节点生成新版本，旧版本 ‹ n/m › 可切换） */
@Composable
internal fun EditMessageDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑重发") },
        text = {
            Column {
                Text(
                    text = "确认后原消息保留为旧版本（可随时切换查看），并以编辑内容重新生成回复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    // material3 1.1.2 无 minLines：用 maxLines 限制高度，随内容增高
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 多模型重答：选择一个模型重新生成回复 */
@Composable
internal fun ModelPickerDialog(
    models: List<ChatModelOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换模型重答") },
        text = {
            if (models.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(models, key = { it.modelId + it.providerName }) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(option.modelId) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = option.providerName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 会话内搜索面板：顶部搜索框 + 结果列表（点击跳转并高亮） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SearchPanel(
    state: ChatUiState,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onJump: (com.cjhsy.linkzone.data.model.MessageItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 搜索框
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索聊天记录", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            onQueryChange("")
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    state.searching -> "正在加载全部聊天记录…"
                    state.searchQuery.isBlank() -> "输入关键词搜索本会话消息"
                    else -> "${state.searchResults.size} 条结果"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }

        // 结果列表（时间倒序）
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(
                state.searchResults,
                key = { _, m -> m.id }
            ) { _, msg ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(msg) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = if (msg.isSelf) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (msg.isSelf) "我" else msg.senderName.ifBlank { "AI" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = msg.displayText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatMessageTime(msg.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
