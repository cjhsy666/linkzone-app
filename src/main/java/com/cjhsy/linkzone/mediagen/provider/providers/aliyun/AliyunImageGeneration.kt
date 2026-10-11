package com.cjhsy.linkzone.mediagen.provider.providers.aliyun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.mediagen.model.MediaGenerationError
import com.cjhsy.linkzone.mediagen.model.MediaGenerationInput
import com.cjhsy.linkzone.mediagen.model.MediaGenerationModel
import com.cjhsy.linkzone.mediagen.model.MediaGenerationOutput
import com.cjhsy.linkzone.mediagen.model.MediaGenerationRequest
import com.cjhsy.linkzone.mediagen.model.MediaGenerationStatus
import com.cjhsy.linkzone.mediagen.model.MediaGenerationTask
import com.cjhsy.linkzone.mediagen.model.MediaGenerationUsage
import com.cjhsy.linkzone.mediagen.provider.MediaGenerationProviderSetting
import com.cjhsy.linkzone.mediagen.provider.providers.bearerRequest
import com.cjhsy.linkzone.mediagen.provider.providers.executeJson
import com.cjhsy.linkzone.mediagen.provider.providers.imageMimeType
import com.cjhsy.linkzone.mediagen.provider.providers.int
import com.cjhsy.linkzone.mediagen.provider.providers.long
import com.cjhsy.linkzone.mediagen.provider.providers.obj
import com.cjhsy.linkzone.mediagen.provider.providers.postJson
import com.cjhsy.linkzone.mediagen.provider.providers.string
import com.cjhsy.linkzone.mediagen.provider.providers.toUrlOrDataUri
import com.cjhsy.linkzone.mediagen.provider.providers.urlFileExtension
import okhttp3.OkHttpClient
import java.util.UUID

/**
 * 万相 / 千问图像生成与编辑，走同步的多模态生成接口。带图片输入时是图像编辑 / 多图参考。
 */
internal class AliyunImageGeneration(
    private val client: OkHttpClient,
) {
    private val id = AliyunMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.Aliyun,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        // 本地图片要读出来编码成 data URI
        val body = withContext(Dispatchers.IO) { buildBody(model, request) }
        val httpRequest = bearerRequest(
            "${setting.resolvedBaseUrl()}/services/aigc/multimodal-generation/generation",
            setting.apiKey,
        ).postJson(body).build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        put("model", model.modelId)
        put("input", buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("text", request.prompt) })
                        request.inputs.forEach { input ->
                            require(input is MediaGenerationInput.Image) {
                                "Aliyun image generation does not support ${input::class.simpleName} input"
                            }
                            add(buildJsonObject { put("image", input.toUrlOrDataUri()) })
                        }
                    })
                })
            })
        })
        put("parameters", buildJsonObject {
            request.extraParameters.forEach { (key, value) -> put(key, value) }
            request.resolution?.takeIf(String::isNotBlank)?.let { put("size", it.toAliyunSize()) }
            request.count?.let { put("n", it) }
            request.watermark?.let { put("watermark", it) }
            request.seed?.let { put("seed", it) }
            request.promptEnhancement?.let { put("prompt_extend", it) }
        })
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val usageObject = root.obj("usage")
        val resolution = usageObject?.resolution()
        val urls = (root.obj("output")?.get("choices") as? JsonArray).orEmpty()
            .flatMap { choice -> ((choice as? JsonObject)?.obj("message")?.get("content") as? JsonArray).orEmpty() }
            // 图文混排时 content 里还会有 text 项
            .mapNotNull { (it as? JsonObject)?.string("image") }
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = UUID.randomUUID().toString(),
            provider = id,
            model = fallbackModel,
            status = if (urls.isEmpty()) MediaGenerationStatus.FAILED else MediaGenerationStatus.SUCCEEDED,
            outputs = urls.map { url ->
                MediaGenerationOutput(
                    url = url,
                    mimeType = imageMimeType(url.urlFileExtension(), "image/png"),
                    resolution = resolution,
                )
            },
            error = if (urls.isEmpty()) {
                MediaGenerationError(root.string("code"), root.string("message") ?: "Aliyun returned no image")
            } else {
                null
            },
            usage = usageObject?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            metadata = root,
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "Aliyun image generation requires a non-empty prompt" }
        require(request.aspectRatio == null) { "Aliyun image generation uses resolution instead of aspectRatio" }
        require(request.durationSeconds == null) { "Aliyun image generation does not expose duration" }
        require(request.generateAudio == null) { "Aliyun image generation does not expose generateAudio" }
        require(request.callbackUrl == null) { "Aliyun image generation returns results synchronously" }
    }

    // 公共字段的像素尺寸写作 1536x1024，百炼写作 1536*1024；1K / 2K 这类档位原样下发
    private fun String.toAliyunSize(): String =
        PIXEL_SIZE.matchEntire(trim())?.let { "${it.groupValues[1]}*${it.groupValues[2]}" } ?: this

    // 万相返回 size（1488*704），千问返回 width / height
    private fun JsonObject.resolution(): String? {
        string("size")?.let { return it.replace('*', 'x') }
        val width = int("width") ?: return null
        val height = int("height") ?: return null
        return "${width}x$height"
    }

    private companion object {
        val PIXEL_SIZE = Regex("""(\d+)\s*[xX*]\s*(\d+)""")
    }
}
