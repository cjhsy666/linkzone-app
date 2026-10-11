package com.cjhsy.linkzone.data.search

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.cjhsy.linkzone.ai.core.InputSchema
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 联网搜索服务抽象（RikkaHub search 模块对齐，仅保留 Tavily/Exa/Brave/SearXNG 四家）。
 */
interface SearchService<T : SearchServiceOptions> {
    val name: String

    fun parameters(options: T): InputSchema?

    /** 支持 scrape_web 时返回参数 schema，否则 null */
    fun scrapingParameters(options: T): InputSchema?

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T,
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T,
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                else -> error("未知的搜索服务类型: ${options::class.simpleName}")
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun init(client: OkHttpClient) {
            httpClient = client
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10,
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
    /** 本地取回时间；并非发布日期 */
    val retrievedAt: String? = null,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        /** 供应商提供的发布日期；null 表示未提供 */
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
    /** 本地取回时间；并非发布日期 */
    val retrievedAt: String? = null,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    val publishedDate: String? = null,
)

/**
 * 搜索服务配置（sealed 多态，Room llm_search_provider 表 JSON 列存储）。
 * 每种类型一条配置；实际使用的是 enabled 且 selected 的那条。
 */
@Serializable
sealed class SearchServiceOptions {
    abstract val id: String

    open val displayName: String
        get() = TYPES[this::class] ?: "Unknown"

    companion object {
        val TYPES = mapOf(
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            BraveOptions::class to "Brave",
            SearXNGOptions::class to "SearXNG",
        )
    }

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: String = UUID.randomUUID().toString(),
        val apiKey: String = "",
        val depth: String = "advanced",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: String = UUID.randomUUID().toString(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: String = UUID.randomUUID().toString(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: String = UUID.randomUUID().toString(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()
}

internal suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.runCatching { close() } }
            }
        })
    }
}
