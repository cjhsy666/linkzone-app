package com.cjhsy.linkzone.di

import com.cjhsy.linkzone.core.network.AuthInterceptor
import com.cjhsy.linkzone.core.network.Envelope401Interceptor
import com.cjhsy.linkzone.core.network.TokenAuthenticator
import com.cjhsy.linkzone.core.network.TokenHolder
import com.cjhsy.linkzone.core.storage.SecureStore
import com.cjhsy.linkzone.ai.provider.ProviderManager
import com.cjhsy.linkzone.data.db.AppDatabase
import com.cjhsy.linkzone.data.remote.ChatApi
import com.cjhsy.linkzone.data.remote.ChatSocketManager
import com.cjhsy.linkzone.data.remote.RefreshApi
import com.cjhsy.linkzone.data.remote.UserApi
import com.cjhsy.linkzone.data.ai.mcp.McpManager
import com.cjhsy.linkzone.data.repository.AgentRepository
import com.cjhsy.linkzone.data.repository.AssetRepository
import com.cjhsy.linkzone.data.repository.AuthRepository
import com.cjhsy.linkzone.data.repository.ChatRepository
import com.cjhsy.linkzone.data.repository.EngageRepository
import com.cjhsy.linkzone.data.repository.FavoriteRepository
import com.cjhsy.linkzone.data.repository.LlmRepository
import com.cjhsy.linkzone.data.repository.LocalChatStore
import com.cjhsy.linkzone.data.repository.UserRepository
import com.cjhsy.linkzone.ui.auth.ForgotPasswordViewModel
import com.cjhsy.linkzone.ui.auth.LoginViewModel
import com.cjhsy.linkzone.ui.chat.ChatViewModel
import com.cjhsy.linkzone.ui.main.MainViewModel
import com.cjhsy.linkzone.ui.llm.LlmAgentViewModel
import com.cjhsy.linkzone.ui.llm.LlmExtensionViewModel
import com.cjhsy.linkzone.ui.llm.LlmMcpViewModel
import com.cjhsy.linkzone.ui.llm.LlmMemoryViewModel
import com.cjhsy.linkzone.ui.llm.LlmProviderViewModel
import com.cjhsy.linkzone.ui.llm.LlmSearchViewModel
import com.cjhsy.linkzone.ui.notification.NotificationViewModel
import com.cjhsy.linkzone.ui.profile.ProfileEditViewModel
import com.cjhsy.linkzone.ui.profile.ProfileViewModel
import com.cjhsy.linkzone.ui.settings.SettingsViewModel
import com.cjhsy.linkzone.ui.signin.SignInViewModel
import com.cjhsy.linkzone.ui.wallet.WalletViewModel
import androidx.room.Room
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 后端 Base URL（结尾必须带 /） */
const val BASE_URL = "http://www.linkzone.cc.cd:8808/"

private const val REFRESH_QUALIFIER = "refresh_api"

private fun OkHttpClient.Builder.defaultTimeouts(): OkHttpClient.Builder = apply {
    connectTimeout(15, TimeUnit.SECONDS)
    readTimeout(30, TimeUnit.SECONDS)
    writeTimeout(30, TimeUnit.SECONDS)
}

val appModule = module {

    // ---------- 基础组件 ----------
    single { SecureStore(androidContext()) }
    single { TokenHolder() }

    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
            // 必须开启：否则值等于默认值的字段会被跳过不序列化。
            // 例如 CreateConvRequest.type="private"（默认值）会被省略，
            // 后端收不到 type 字段报 "missing id/type/target_id"
            encodeDefaults = true
        }
    }

    // ---------- 刷新 Token 专用 Retrofit ----------
    // 关键：不挂 AuthInterceptor、不挂 TokenAuthenticator，
    // 刷新请求本身永远不会再次触发 401 刷新，从根源上杜绝死循环。
    single<RefreshApi>(named(REFRESH_QUALIFIER)) {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient.Builder().defaultTimeouts().build())
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RefreshApi::class.java)
    }

    // ---------- 鉴权链 ----------
    single { AuthInterceptor(get()) }

    single {
        TokenAuthenticator(
            secureStore = get(),
            tokenHolder = get(),
            refreshApiProvider = { get<RefreshApi>(named(REFRESH_QUALIFIER)) }
        )
    }

    single {
        OkHttpClient.Builder()
            .addInterceptor(get<AuthInterceptor>())
            .authenticator(get<TokenAuthenticator>())
            // 包络式 401 → 真实 HTTP 401，使 TokenAuthenticator 对业务码 401 同样生效
            .addNetworkInterceptor(Envelope401Interceptor())
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .defaultTimeouts()
            .build()
    }

    // ---------- 业务 Retrofit ----------
    single {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(get())
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
    }

    single<UserApi> { get<Retrofit>().create(UserApi::class.java) }
    single<ChatApi> { get<Retrofit>().create(ChatApi::class.java) }

    // ---------- 聊天 WebSocket ----------
    // 专用客户端：WS 认证走 URL token，不需要 AuthInterceptor/Authenticator；
    // pingInterval 发送协议层 ping（服务端 gorilla 自动回 pong），
    // 穿透运营商 NAT（移动网络常静默回收空闲连接）；读写不设超时由心跳与重连负责。
    // 注意：不能挂 HttpLoggingInterceptor——WS URL 携带 ?token=，BASIC 级别会
    // 把 access_token 写进 logcat。
    single(named("ws_client")) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    single {
        ChatSocketManager(
            okHttpClient = get(named("ws_client")),
            tokenHolder = get(),
            json = get(),
            secureStore = get(),
            // WS 自恢复路径复用独立刷新接口（无 AuthInterceptor/Authenticator）
            refreshApiProvider = { get<RefreshApi>(named(REFRESH_QUALIFIER)) }
        )
    }

    // ---------- 本地 LLM（RikkaHub 移植） ----------
    // LLM 专用客户端：流式响应 readTimeout 必须为 0（token 间隔可能远超 30s）。
    // 禁止挂 HttpLoggingInterceptor——BASIC 级别会把请求行/头里的 apiKey 泄露进 logcat。
    single(named("llm_client")) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    single { ProviderManager(client = get(named("llm_client")), context = androidContext()) }

    // AI 代码工作区（filesDir/workspaces）
    single { com.cjhsy.linkzone.workspace.WorkspaceManager(androidContext()) }

    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, AppDatabase.DB_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    single { LlmRepository(androidContext(), get()) }

    // 本地智能体会话存储（RikkaHub 式，完全脱离服务器）
    single { LocalChatStore(get(), get()) }

    // MCP：管理器内部自建 OkHttpClient（长超时）/状态存储/OAuth 协调器/会话注册表
    single { McpManager(get()) }

    // Token 用量统计（filesDir/usage_stats.json，本地会话生成完成后由 ChatViewModel 记录）
    single { com.cjhsy.linkzone.data.stats.UsageStatsStore(androidContext(), get()) }

    // ---------- 仓库 ----------
    single { AuthRepository(get(), get(), get(), get()) }
    single { UserRepository(get(), get(), get()) }
    single { AssetRepository(get(), get()) }
    single { EngageRepository(get(), get()) }
    single { AgentRepository(get(), get()) }
    single { ChatRepository(get(), get()) }
    single { FavoriteRepository(get()) }

    // ---------- ViewModel ----------
    viewModel { LoginViewModel(get()) }
    viewModel { ForgotPasswordViewModel(get()) }
    viewModel { ProfileViewModel(get(), get(), get()) }
    viewModel { ProfileEditViewModel(get()) }
    viewModel { WalletViewModel(get()) }
    viewModel { SignInViewModel(get()) }
    viewModel { NotificationViewModel(get()) }
    viewModel { MainViewModel(get(), get(), get(), get()) }
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { LlmProviderViewModel(get(), get()) }
    viewModel { LlmAgentViewModel(get(), get()) }
    viewModel { LlmExtensionViewModel(androidContext(), get()) }
    viewModel { LlmMcpViewModel(get(), get()) }
    viewModel { LlmSearchViewModel(get()) }
    viewModel { LlmMemoryViewModel(get()) }
    viewModel { com.cjhsy.linkzone.ui.stats.StatsViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
