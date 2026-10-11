package com.cjhsy.linkzone.mediagen.provider.providers.openrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import com.cjhsy.linkzone.mediagen.provider.providers.executeJson
import com.cjhsy.linkzone.mediagen.provider.providers.long
import com.cjhsy.linkzone.mediagen.provider.providers.obj
import com.cjhsy.linkzone.mediagen.provider.providers.postJson
import com.cjhsy.linkzone.mediagen.provider.providers.string
import com.cjhsy.linkzone.mediagen.provider.providers.toUrlOrDataUri
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.UUID

/**
 * OpenRouter 的图像接口。带图片输入时是参考图生图，接口同步返回 base64 内容。
 */
internal class OpenRouterImageGeneration(
    private val client: OkHttpClient,
) {
    private val id = OpenRouterMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.OpenRouter,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        // 本地图片要读出来编码成 data URI
        val body = withContext(Dispatchers.IO) { buildBody(model, request) }
        val httpRequest = setting.request("/images").postJson(body).build()
        return parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        val references = request.inputs.map { input ->
            require(input is MediaGenerationInput.Image) {
                "OpenRouter image generation does not support ${input::class.simpleName} input"
            }
            imageUrlPart(input.toUrlOrDataUri(), input.extra)
        }
        if (references.isNotEmpty()) put("input_references", JsonArray(references))
        request.count?.let { put("n", it) }
        putResolution(request.resolution)
        request.aspectRatio?.let { put("aspect_ratio", it) }
        request.seed?.let { put("seed", it) }
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val items = (root["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val outputs = items.mapNotNull { item ->
            val base64 = item.string("b64_json") ?: return@mapNotNull null
            MediaGenerationOutput(
                data = Base64.getDecoder().decode(base64),
                mimeType = item.string("media_type") ?: "image/png",
            )
        }
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = UUID.randomUUID().toString(),
            provider = id,
            model = fallbackModel,
            status = if (outputs.isEmpty()) MediaGenerationStatus.FAILED else MediaGenerationStatus.SUCCEEDED,
            outputs = outputs,
            error = if (outputs.isEmpty()) {
                val errorObject = root.obj("error")
                MediaGenerationError(
                    code = errorObject?.string("code"),
                    message = errorObject?.string("message") ?: "OpenRouter returned no image",
                )
            } else {
                null
            },
            createdAtEpochSeconds = root.long("created"),
            usage = root.obj("usage")?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            // data 里是整张图片的 base64，不放进 metadata
            metadata = JsonObject(root - "data"),
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "OpenRouter image generation requires a non-empty prompt" }
        require(request.durationSeconds == null) { "OpenRouter image generation does not expose duration" }
        require(request.generateAudio == null) { "OpenRouter image generation does not expose generateAudio" }
        require(request.watermark == null) { "OpenRouter image generation does not expose watermark" }
        require(request.promptEnhancement == null) {
            "OpenRouter image generation does not expose prompt enhancement"
        }
        require(request.callbackUrl == null) { "OpenRouter image generation returns results synchronously" }
    }
}
