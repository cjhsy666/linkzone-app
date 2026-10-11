package com.cjhsy.linkzone.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import com.cjhsy.linkzone.ai.util.json
import com.cjhsy.linkzone.data.search.SearchCommonOptions
import com.cjhsy.linkzone.data.search.SearchResult
import com.cjhsy.linkzone.data.search.ScrapedResult
import com.cjhsy.linkzone.data.search.SearchService
import com.cjhsy.linkzone.data.search.SearchServiceOptions
import java.time.LocalDate
import java.time.Instant

/**
 * 联网搜索工具（RikkaHub SearchTools 对齐）：
 * `search_web` 搜索 + `scrape_web` 抓取（仅 Tavily/Exa 支持）。
 *
 * @param options 选中的搜索服务配置；null（未配置/未启用）时返回空集
 */
fun createSearchTools(
    options: SearchServiceOptions?,
    commonOptions: SearchCommonOptions = SearchCommonOptions(),
): Set<Tool> {
    if (options == null) return emptySet()
    val service = SearchService.getService(options)
    val today = LocalDate.now().toString()

    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Do not treat result order as proof of freshness. Prefer primary sources and inspect
                    each result's title, URL, publication date, and content before making a current claim.
                    Use the optional publication-date and domain filters only when they match the question.
                    If a date or primary source is missing, or sources conflict, run another focused search
                    or use scrape_web to verify the most relevant source before answering.
                    Generate focused keywords and run multiple searches if needed.
                    Today is $today.

                    Response format:
                    - retrievedAt is the local retrieval time, never a publication date
                    - items[].id (short id), index, title, url, publishedDate (if supplied), highlights (if supplied), text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                """.trimIndent(),
                parameters = { service.parameters(options) },
                execute = {
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = commonOptions,
                        serviceOptions = options,
                    ).getOrThrow().copy(retrievedAt = Instant.now().toString())
                    val results = json.encodeToJsonElement(SearchResult.serializer(), result)
                        .jsonObject.let { jsonObj ->
                            val map = jsonObj.toMutableMap()
                            map["items"] = JsonArray(
                                map["items"]!!.let { items ->
                                    if (items is JsonArray) items.mapIndexed { index, item ->
                                        JsonObject(item.jsonObject.toMutableMap().apply {
                                            put("id", JsonPrimitive(java.util.UUID.randomUUID().toString().take(6)))
                                            put("index", JsonPrimitive(index + 1))
                                        })
                                    } else emptyList()
                                }
                            )
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page, when search snippets are insufficient,
                        or when a current claim needs verification against a specific source.
                        Avoid using it for common questions unless the user asks.
                    """.trimIndent(),
                    parameters = { service.scrapingParameters(options) },
                    execute = {
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = commonOptions,
                            serviceOptions = options,
                        ).getOrThrow().copy(retrievedAt = Instant.now().toString())
                        val payload = json.encodeToJsonElement(ScrapedResult.serializer(), result).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                )
            )
        }
    }
}
