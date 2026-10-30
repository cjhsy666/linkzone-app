package com.cjhsy.linkzone.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cjhsy.linkzone.core.storage.SecureStore
import com.cjhsy.linkzone.ui.auth.LoginScreen
import com.cjhsy.linkzone.ui.auth.ForgotPasswordScreen
import com.cjhsy.linkzone.ui.llm.AiHubScreen
import com.cjhsy.linkzone.ui.llm.LlmAgentScreen
import com.cjhsy.linkzone.ui.llm.LlmExtensionScreen
import com.cjhsy.linkzone.ui.llm.LlmLocalToolsScreen
import com.cjhsy.linkzone.ui.llm.LlmMcpScreen
import com.cjhsy.linkzone.ui.llm.LlmMemoryScreen
import com.cjhsy.linkzone.ui.llm.LlmProviderScreen
import com.cjhsy.linkzone.ui.llm.LlmSearchScreen
import com.cjhsy.linkzone.ui.main.MainScreen
import com.cjhsy.linkzone.ui.notification.NotificationScreen
import com.cjhsy.linkzone.ui.profile.ProfileEditScreen
import com.cjhsy.linkzone.ui.profile.ProfileScreen
import com.cjhsy.linkzone.ui.settings.SettingsScreen
import com.cjhsy.linkzone.ui.signin.SignInScreen
import com.cjhsy.linkzone.ui.wallet.WalletScreen
import com.cjhsy.linkzone.ui.workspace.WorkspaceScreen
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 路由常量 */
object Routes {
    const val LOGIN = "login"
    const val FORGOT = "forgot"
    const val MAIN = "main"
    const val PROFILE = "profile"
    const val PROFILE_EDIT = "profile_edit"
    const val WALLET = "wallet"
    const val SIGNIN = "signin"
    const val NOTIFICATION = "notification"
    const val SETTINGS = "settings"
    const val LLM_HUB = "llm_hub"
    const val LLM_PROVIDERS = "llm_providers"
    const val LLM_AGENTS = "llm_agents"
    const val LLM_EXTENSIONS = "llm_extensions"
    const val LLM_MCP = "llm_mcp"
    const val LLM_SEARCH = "llm_search"
    const val LLM_MEMORY = "llm_memory"
    const val LLM_WORKSPACE = "llm_workspace"
    const val LLM_SSH = "llm_ssh"
    const val LLM_LOCAL_TOOLS = "llm_local_tools"
    const val STATS = "stats"
}

/** 导航转场时长（毫秒） */
private const val NAV_ANIM_MS = 260

/**
 * 通过 KoinComponent 获取依赖，避免依赖 Koin Compose 扩展（版本差异较大）。
 * inject() 来自 koin-core，API 稳定。
 */
private object NavDeps : KoinComponent {
    val secureStore: SecureStore by inject()
}

/**
 * 导航宿主 + 路由守卫。
 *
 * 启动时检查本地是否存有 refresh_token：
 * - 有 → 直接进入主界面（底部导航）
 * - 无 → 进入登录页
 *
 * 登录成功 / 会话失效时切换目的地并清空返回栈，避免返回到上一状态。
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    val startDestination = remember {
        if (NavDeps.secureStore.isLoggedIn()) Routes.MAIN else Routes.LOGIN
    }

    // 全局页面转场：新页面自右轻推淡入；返回时逆放。时长 260ms。
    // 沉浸式：不加全局安全区 padding（否则等于没沉浸），由各页面自行处理避让——
    // 聊天页顶栏背景延伸到状态栏（QQ 效果），其余页面根布局 safeDrawingPadding。
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(tween(NAV_ANIM_MS)) { it / 6 } + fadeIn(tween(NAV_ANIM_MS))
        },
        exitTransition = {
            slideOutHorizontally(tween(NAV_ANIM_MS)) { -it / 8 } + fadeOut(tween(NAV_ANIM_MS))
        },
        popEnterTransition = {
            slideInHorizontally(tween(NAV_ANIM_MS)) { -it / 8 } + fadeIn(tween(NAV_ANIM_MS))
        },
        popExitTransition = {
            slideOutHorizontally(tween(NAV_ANIM_MS)) { it / 6 } + fadeOut(tween(NAV_ANIM_MS))
        }
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onForgotPassword = { navController.navigate(Routes.FORGOT) }
            )
        }

        composable(Routes.FORGOT) {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                onDone = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                onNavigate = { route -> navController.navigate(route) },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PROFILE_EDIT) {
            ProfileEditScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(Routes.WALLET) {
            WalletScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SIGNIN) {
            SignInScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.NOTIFICATION) {
            NotificationScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onChangePassword = { navController.navigate(Routes.PROFILE_EDIT) },
                onAiHub = { navController.navigate(Routes.LLM_HUB) },
                onStats = { navController.navigate(Routes.STATS) },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.LLM_HUB) {
            AiHubScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        composable(Routes.LLM_PROVIDERS) {
            LlmProviderScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_AGENTS) {
            LlmAgentScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        composable(Routes.LLM_EXTENSIONS) {
            LlmExtensionScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_MCP) {
            LlmMcpScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_SEARCH) {
            LlmSearchScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_MEMORY) {
            LlmMemoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_WORKSPACE) {
            WorkspaceScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_SSH) {
            com.cjhsy.linkzone.ui.llm.LlmSshScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LLM_LOCAL_TOOLS) {
            LlmLocalToolsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.STATS) {
            com.cjhsy.linkzone.ui.stats.StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
