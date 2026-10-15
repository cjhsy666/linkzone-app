package com.cjhsy.linkzone.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cjhsy.linkzone.ui.chat.ChatScreen
import com.cjhsy.linkzone.ui.chat.ChatViewModel
import com.cjhsy.linkzone.ui.chat.EnginePickerSheet
import com.cjhsy.linkzone.ui.drawer.AppDrawer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 抽屉/引擎选择器所需的会话摘要（与聊天高频状态隔离） */
private data class DrawerChatData(
    val currentConvId: String = "",
    /** 当前本地会话绑定的智能体 id（引擎选择器高亮用；云端会话为 null） */
    val currentAgentId: String? = null,
    val connected: Boolean = false
)

/**
 * 主界面（豆包式）：首页即聊天，无底部 Tab；
 * 智能体 / 我的等全部入口收纳在左侧抽屉与抽屉跳转的子页面中。
 *
 * @param onNavigate 跳转子页面路由
 * @param onLoggedOut 会话失效/登出时回到登录页
 */
@Composable
fun MainScreen(
    onNavigate: (String) -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: MainViewModel = koinViewModel()
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()

    // ChatViewModel 提升至此层：抽屉与会话页共享同一实例。
    // 只订阅抽屉/引擎选择器所需的少量字段（distinctUntilChanged 去重），
    // 消息流/sending 等高频变化不会引起本层重组
    val chatViewModel: ChatViewModel = koinViewModel()
    // 抽屉固定置顶的唯一云端主会话 + 本地会话历史列表
    val drawerMainConv by chatViewModel.mainConv.collectAsStateWithLifecycle()
    val drawerConversations by chatViewModel.localConversations.collectAsStateWithLifecycle()
    val drawerChat by remember(chatViewModel) {
        chatViewModel.state
            .map { DrawerChatData(it.currentConvId, it.currentAgent?.id, it.connected) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(DrawerChatData())
    val localAgents by chatViewModel.localAgents.collectAsStateWithLifecycle()

    // 对话引擎选择器：抽屉「新建对话」= 新建；顶栏引擎 chip = 切换（换引擎分叉）
    var showEnginePicker by remember { mutableStateOf(false) }
    var pickerForNew by remember { mutableStateOf(true) }
    val currentIsLocal = ChatViewModel.isLocalConvId(drawerChat.currentConvId)
    val currentPickerAgentId = drawerChat.currentAgentId.takeIf { currentIsLocal }

    LaunchedEffect(loggedOut) {
        if (loggedOut) onLoggedOut()
    }

    val closeDrawer = { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                // 限宽圆角，避免占满整屏；顶部 insets 由抽屉内部处理
                modifier = Modifier.fillMaxWidth(0.84f),
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                AppDrawer(
                    profile = profile,
                    mainConv = drawerMainConv,
                    conversations = drawerConversations,
                    agents = localAgents,
                    currentConvId = drawerChat.currentConvId,
                    connected = drawerChat.connected,
                    onOpenMainConversation = {
                        closeDrawer()
                        chatViewModel.openMainConversation()
                    },
                    onSwitchConversation = { convId ->
                        closeDrawer()
                        chatViewModel.switchConversation(convId)
                    },
                    onDeleteConversation = { convId ->
                        chatViewModel.deleteConversation(convId)
                    },
                    onPinConversation = { convId, pinned ->
                        chatViewModel.pinConversation(convId, pinned)
                    },
                    onRenameConversation = { convId, name ->
                        chatViewModel.renameConversation(convId, name)
                    },
                    onClearMainConversation = {
                        chatViewModel.clearMainConversation()
                    },
                    onNewLocalConversation = {
                        closeDrawer()
                        pickerForNew = true
                        showEnginePicker = true
                    },
                    onNavigate = { route ->
                        closeDrawer()
                        onNavigate(route)
                    }
                )
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // 统一聊天页：云端 LinkZone / 本地智能体共用同一套 ChatScreen，
            // 引擎差异在页面内部通过能力参数与插槽收敛，切换会话不再重建整屏
            ChatScreen(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenEnginePicker = {
                    pickerForNew = false
                    showEnginePicker = true
                },
                onNewConversation = {
                    pickerForNew = true
                    showEnginePicker = true
                },
                onManageAgents = { onNavigate("llm_hub") },
                onOpenWorkspace = { onNavigate("llm_workspace") },
                viewModel = chatViewModel
            )
        }
    }

    // ---------- 对话引擎选择器（云端 / 本地智能体） ----------
    if (showEnginePicker) {
        EnginePickerSheet(
            localAgents = localAgents,
            currentIsLocal = currentIsLocal,
            currentAgentId = currentPickerAgentId,
            onPickCloud = {
                showEnginePicker = false
                // 云端有且只有一个主会话：无论"新建"还是"切换"，都回到主会话
                chatViewModel.openMainConversation()
            },
            onPickLocal = { agent ->
                showEnginePicker = false
                if (pickerForNew) chatViewModel.createLocalConversation(agent) else chatViewModel.switchEngineToLocal(agent)
            },
            onManageAgents = {
                showEnginePicker = false
                onNavigate("llm_agents")
            },
            onDismiss = { showEnginePicker = false }
        )
    }
}
