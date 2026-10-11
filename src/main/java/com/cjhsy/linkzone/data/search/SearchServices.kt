package com.cjhsy.linkzone.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.data.search.SearchResult.SearchResultItem
import com.cjhsy.linkzone.data.search.SearchService.Companion.httpClient
import com.cjhsy.linkzone.data.search.SearchService.Companion.json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 四家搜索服务实现（Tavily / Exa / Brave / SearXNG），对齐 RikkaHub search 模块行为。
 */

// ---------------------------------------------------------------------------
// Tavily
// ---------------------------------------------------------------------------

object TavilySearchService : SearchService<SearchServiceOptions.TavilyOptions> {
    override val name: String = "Tavily"

    override fun parameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "search topic (one of `general`, `news`, `finance`)")
                    put("enum", buildJsonArray {
                        add("general")
                        add("news")
                        add("finance")
                    })
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull ?: "general"
            if (topic !in listOf("general", "news", "finance")) {
                error("topic must be one of `general`, `news`, `finance`")
            }

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
                put("search_depth", serviceOptions.depth.ifEmpty { "advanced" })
                put("topic", topic)
                put("include_answer", "advanced")
                put("include_images", true)
            }

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) error("response failed #${response.code}")
            val parsed = json.decodeFromString<SearchResponse>(response.body?.string().orEmpty())
            SearchResult(
                answer = parsed.answer,
                items = parsed.results.map {
                    SearchResult.SearchResultItem(title = it.title, url = it.url, text = it.content)
                },
                images = parsed.images,
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions,
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", buildJsonArray { add(url) })
            }
            val request = Request.Builder()
                .url("https://api.tavily.com/extract")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()
            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) error("response failed #${response.code}")
            val parsed = json.decodeFromString<ScrapeResponse>(response.body?.string().orEmpty())
            ScrapedResult(
                urls = parsed.results.map {
                    ScrapedResultUrl(url = it.url, content = it.rawContent)
                }
            )
        }
    }

    @Serializable
    data class SearchResponse(
        val query: String,
        val followUpQuestions: String? = null,
        val answer: String? = null,
        val images: List<String> = emptyList(),
        val results: List<SearchResultItem>,
    )

    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val content: String,
        val score: Double,
        val rawContent: String? = null,
    )

    @Serializable
    data class ScrapeResponse(
        val results: List<ScrapedResultItem>,
    )

    @Serializable
    data class ScrapedResultItem(
        val url: String,
        @SerialName("raw_content") val rawContent: String,
    )
}

// ---------------------------------------------------------------------------
// Exa
// ---------------------------------------------------------------------------

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    private const val MAX_EVIDENCE_TEXT_CHARACTERS = 8_000
    private const val MAX_EVIDENCE_HIGHLIGHT_CHARACTERS = 1_200
    private const val MIN_MAX_AGE_HOURS = -1
    private const val MAX_MAX_AGE_HOURS = 720

    override val name: String = "Exa"

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
                put("startPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date lower bound; results are published after this date")
                })
                put("endPublishedDate", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO-8601 publication date upper bound; results are published before this date")
                })
                put("includeDomains", domainArraySchema("Optional domains to include"))
                put("excludeDomains", domainArraySchema("Optional domains to exclude"))
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content; use only when content freshness matters")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
                put("maxAgeHours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional maximum age in hours for fetched page content")
                    put("minimum", MIN_MAX_AGE_HOURS)
                    put("maximum", MAX_MAX_AGE_HOURS)
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildSearchRequestBody(params, commonOptions.resultSize)
            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: ""
                val data = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.getOrElse { error("Failed to decode response: $bodyRaw") }
                mapSearchResult(data)
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions,
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildScrapeRequestBody(params)
            val request = Request.Builder()
                .url("https://api.exa.ai/contents")
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: ""
                val data = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.getOrElse { error("Failed to decode response: $bodyRaw") }
                mapScrapedResult(data)
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    @Serializable
    data class ExaData(
        @SerialName("requestId") val requestId: String? = null,
        @SerialName("autopromptString") val autopromptString: String? = null,
        @SerialName("resolvedSearchType") val resolvedSearchType: String? = null,
        @SerialName("results") val results: List<ExaResult>,
        @SerialName("output") val output: ExaOutput? = null,
    )

    @Serializable
    data class ExaOutput(
        @SerialName("content") val content: String? = null,
    )

    @Serializable
    data class ExaResult(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("url") val url: String,
        @SerialName("publishedDate") val publishedDate: String? = null,
        @SerialName("author") val author: String? = null,
        @SerialName("text") val text: String? = null,
        @SerialName("image") val image: String? = null,
        @SerialName("highlights") val highlights: List<String>? = null,
    )

    internal fun buildSearchRequestBody(
        params: JsonObject,
        resultSize: Int,
    ) = buildJsonObject {
        val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
        val maxAgeHours = optionalMaxAgeHours(params)
        val hasEvidenceOptions = hasOptionalString(params, "startPublishedDate") ||
            hasOptionalString(params, "endPublishedDate") ||
            hasOptionalStringArray(params, "includeDomains") ||
            hasOptionalStringArray(params, "excludeDomains") ||
            maxAgeHours != null

        put("query", JsonPrimitive(query))
        put("numResults", JsonPrimitive(resultSize))
        put("type", JsonPrimitive(params["type"]?.jsonPrimitive?.content ?: "auto"))
        putOptionalString(this, params, "startPublishedDate")
        putOptionalString(this, params, "endPublishedDate")
        putOptionalStringArray(this, params, "includeDomains")
        putOptionalStringArray(this, params, "excludeDomains")
        put("contents", buildJsonObject {
            if (hasEvidenceOptions) {
                put("text", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_TEXT_CHARACTERS))
                })
                put("highlights", buildJsonObject {
                    put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_HIGHLIGHT_CHARACTERS))
                })
            } else {
                put("text", JsonPrimitive(true))
            }
            maxAgeHours?.let { put("maxAgeHours", it) }
        })
    }

    internal fun buildScrapeRequestBody(params: JsonObject) = buildJsonObject {
        val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
        put("urls", buildJsonArray {
            add(JsonPrimitive(url))
        })
        put("text", buildJsonObject {
            put("maxCharacters", JsonPrimitive(MAX_EVIDENCE_TEXT_CHARACTERS))
        })
        optionalMaxAgeHours(params)?.let { put("maxAgeHours", it) }
    }

    internal fun mapSearchResult(data: ExaData): SearchResult = SearchResult(
        answer = data.output?.content,
        items = data.results.map {
            SearchResultItem(
                title = it.title,
                url = it.url,
                text = it.text ?: "",
                publishedDate = it.publishedDate,
                highlights = it.highlights.orEmpty(),
            )
        },
        images = data.results.mapNotNull { it.image?.takeIf { url -> url.isNotBlank() } },
    )

    internal fun mapScrapedResult(data: ExaData): ScrapedResult = ScrapedResult(
        urls = data.results.map {
            ScrapedResultUrl(
                url = it.url,
                content = it.text ?: "",
                metadata = ScrapedResultMetadata(
                    title = it.title,
                    publishedDate = it.publishedDate,
                )
            )
        },
    )

    private fun domainArraySchema(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "string")
        })
    }

    private fun putOptionalString(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        params[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.put(name, it) }
    }

    private fun putOptionalStringArray(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        params: JsonObject,
        name: String,
    ) {
        val values = params[name]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?: return
        builder.put(name, buildJsonArray { values.forEach { add(it) } })
    }

    private fun hasOptionalString(params: JsonObject, name: String): Boolean =
        runCatching {
            params[name]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }.getOrDefault(false)

    private fun hasOptionalStringArray(params: JsonObject, name: String): Boolean =
        runCatching {
            params[name]?.jsonArray?.any {
                it.jsonPrimitive.contentOrNull?.isNotBlank() == true
            } == true
        }.getOrDefault(false)

    private fun optionalMaxAgeHours(params: JsonObject): Int? =
        runCatching { params["maxAgeHours"]?.jsonPrimitive?.intOrNull }
            .getOrNull()
            ?.takeIf { it in MIN_MAX_AGE_HOURS..MAX_MAX_AGE_HOURS }
}

// ---------------------------------------------------------------------------
// Brave
// ---------------------------------------------------------------------------

object BraveSearchService : SearchService<SearchServiceOptions.BraveOptions> {
    override val name: String = "Brave"

    override fun parameters(options: SearchServiceOptions.BraveOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BraveOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = "https://api.search.brave.com/res/v1/web/search" +
                "?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&count=${commonOptions.resultSize}"

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", serviceOptions.apiKey)
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val searchResponse = json.decodeFromString<BraveSearchResponse>(responseBody)
                val items = searchResponse.web?.results?.map { result ->
                    SearchResultItem(
                        title = result.title,
                        url = result.url,
                        text = result.description ?: ""
                    )
                } ?: emptyList()
                SearchResult(answer = null, items = items)
            } else {
                error("Brave search failed with code ${response.code}: ${response.message}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions,
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Brave"))
    }

    @Serializable
    data class BraveSearchResponse(
        val type: String? = null,
        val web: WebResults? = null,
    )

    @Serializable
    data class WebResults(
        val type: String? = null,
        val results: List<WebResult>? = null,
    )

    @Serializable
    data class WebResult(
        val type: String,
        val title: String,
        val url: String,
        val description: String? = null,
    )
}

// ---------------------------------------------------------------------------
// SearXNG
// ---------------------------------------------------------------------------

object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override val name: String = "SearXNG"

    override fun parameters(options: SearchServiceOptions.SearXNGOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.SearXNGOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.url.isNotBlank()) { "SearXNG URL cannot be empty" }
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val baseUrl = serviceOptions.url.trimEnd('/')
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?q=$encodedQuery&format=json"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (serviceOptions.engines.isNotBlank()) {
                        addQueryParameter("engines", serviceOptions.engines)
                    }
                    if (serviceOptions.language.isNotBlank()) {
                        addQueryParameter("language", serviceOptions.language)
                    }
                }
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .apply {
                    if (serviceOptions.username.isNotBlank() && serviceOptions.password.isNotBlank()) {
                        header("Authorization", Credentials.basic(serviceOptions.username, serviceOptions.password))
                    }
                }
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: ""
                val searchResponse = runCatching {
                    json.decodeFromString<SearXNGResponse>(bodyRaw)
                }.getOrElse { error("Failed to decode SearXNG response: ${it.message}") }

                val items = searchResponse.results
                    .take(commonOptions.resultSize)
                    .map { result ->
                        SearchResultItem(
                            title = result.title,
                            url = result.url,
                            text = result.content
                        )
                    }
                SearchResult(items = items)
            } else {
                error("SearXNG request failed with status ${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions,
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for SearXNG"))
    }

    @Serializable
    data class SearXNGResponse(
        @SerialName("results") val results: List<SearXNGResult>,
    )

    @Serializable
    data class SearXNGResult(
        @SerialName("url") val url: String,
        @SerialName("title") val title: String,
        @SerialName("content") val content: String,
    )
}
